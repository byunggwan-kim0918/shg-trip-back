package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Anthropic Batch API(Sonnet)를 사용하여 미보강 장소에 태그, 설명, 추천 시간대를 보강하는 작업.
 * <p>
 * 처리 흐름:
 * 1. enriched_at IS NULL이고 active=true인 장소를 조회
 * 2. 1000건 단위로 분할하여 Anthropic Batch API 호출
 * 3. 배치 제출 → 폴링 → 결과 처리
 * 4. 성공: tags, description, recommended_time_slots 갱신 + enriched_at 기록
 * 5. 실패: 로그 기록 (다음 사이클에서 enriched_at이 여전히 NULL이므로 자동 재시도)
 * <p>
 * 식당·카페·술집은 프롬프트와 결과 해석이 따로 있다({@link DiningEnrichment}).
 */
@Slf4j
@Component
@Profile("batch")
public class BatchEnrichScheduler {

    /** 연속으로 전부 실패한 청크가 이만큼이면 보강을 멈춘다. */
    static final int MAX_CONSECUTIVE_FAILED_CHUNKS = 3;

    private static final String BATCH_API_URL = "https://api.anthropic.com/v1/messages/batches";
    private static final int MAX_RETRIES = 3;
    private static final long POLL_INTERVAL_MS = 10_000; // 10초

    /** 결과 JSONL 다운로드 타임아웃(분). 청크 1000건 기준 여유 있게 잡는다. */
    private static final int RESULTS_DOWNLOAD_TIMEOUT_MINUTES = 10;
    private static final long MAX_POLL_DURATION_MS = 3_600_000; // 1시간

    private final PlaceRepository placeRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${anthropic.api-key:${ANTHROPIC_API_KEY:}}")
    private String anthropicApiKey;

    @Value("${batch.enrich.chunk-size:1000}")
    private int chunkSize;

    /**
     * 보강용 모델. 기존엔 "claude-sonnet-4-20250514" 하드코딩 상수였는데 모델이 은퇴되면서
     * 전건 not_found_error로 실패했고(실측 484/484), yml의 anthropic.batch.model 설정은
     * 코드가 읽지 않는 설정 표류 상태였다. 설정으로 일원화 — 태그/설명 생성은 Haiku로 충분.
     * Haiku 5.5는 4.5 대비 단가 1/10($0.10/$0.50 per MTok).
     */
    @Value("${anthropic.batch.model:claude-haiku-5-5}")
    private String enrichModel;

    /**
     * 지역 스코프(쉼표 구분, 예: "Jeju,Gangneung"). 비어 있으면 전체 대상.
     * 전량(수만 건) LLM 보강은 비용이 커서 관광지 데이터가 빈약한 지역부터 점진 실행한다.
     */
    @Value("${batch.enrich.regions:}")
    private String enrichRegions;

    /**
     * 지역당 보강 상한(0이면 무제한). 대상 조회 순서가 인기도 → 비식당이라 상한 안에는 유명한 곳부터 들어간다.
     * 지역 목록(batch.enrich.regions)과 함께 써야 지역별로 센다 — 목록이 비어 있으면 전체 합계 상한이다.
     */
    @Value("${batch.enrich.max-per-region:0}")
    private int maxPerRegion;

    /**
     * 이미 제출·완료된 배치의 결과를 다시 적용할 때 쓰는 batchId.
     * Anthropic이 결과를 29일간 보관하므로, 결과 처리 단계에서만 실패한 경우 재제출(재과금) 없이
     * 복구할 수 있다(실측: 515건 배치가 다운로드 타임아웃으로 통째로 버려졌다).
     */
    @Value("${batch.enrich.resume-batch-id:}")
    private String resumeBatchId;

    public BatchEnrichScheduler(PlaceRepository placeRepository, ObjectMapper objectMapper) {
        this.placeRepository = placeRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                // 결과 파일 URL은 서명된 스토리지로 302 리다이렉트된다 — 따라가지 않으면 본문이 비어온다
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * processChunk 결과. failedIds는 이번 실행에서 재시도하지 않도록 제외할 place ID 목록
     * (enriched_at이 여전히 NULL이라 다음 조회에도 다시 잡히기 때문).
     */
    record ChunkResult(int success, int failed, List<Long> failedIds) {
    }

    /**
     * 태그/설명 배치 보강을 실행한다.
     * <p>
     * page offset을 증가시키는 방식 대신 매번 첫 페이지를 다시 조회하고, 이번 실행에서 실패한
     * place ID는 쿼리에서 제외한다(고정 offset 증가는 실제 남은 건수보다 앞서나가 조용히 조기 종료될 수
     * 있다). 조회 순서는 인기도 → 비식당 순이라 중간에 끊겨도 중요한 장소부터 끝나 있다.
     * <p>
     * 연속 {@value #MAX_CONSECUTIVE_FAILED_CHUNKS}개 청크가 전부 실패하면 멈춘다 — API 키 오류처럼
     * 모든 요청이 실패하는 상황에서 수만 건을 헛돌지 않게 한다.
     */
    public void enrich() {
        if (anthropicApiKey == null || anthropicApiKey.isBlank()) {
            log.warn("Anthropic API key가 설정되지 않았습니다. 배치 보강을 건너뜁니다.");
            return;
        }

        List<String> regions = parseRegions();
        log.info("BatchEnrichScheduler 시작 - chunkSize={}, regions={}, maxPerRegion={}",
                chunkSize, regions.isEmpty() ? "전체" : regions, maxPerRegion > 0 ? maxPerRegion : "무제한");

        int[] totals = new int[2];
        if (maxPerRegion > 0 && !regions.isEmpty()) {
            // 지역별로 따로 돌려야 큰 지역(서울)이 상한을 다 먹지 않는다.
            for (String region : regions) {
                enrichScope(List.of(region), totals);
            }
        } else {
            enrichScope(regions, totals);
        }

        log.info("BatchEnrichScheduler 완료 - 보강 성공: {}건, 실패: {}건", totals[0], totals[1]);
    }

    /** 한 범위(지역 목록, 비면 전체)를 상한까지 보강한다. totals[0]=성공, [1]=실패 누적. */
    private void enrichScope(List<String> regions, int[] totals) {
        int processed = 0;
        int failed = 0;
        int consecutiveFailedChunks = 0;
        Set<Long> excludedIds = new HashSet<>();

        while (true) {
            int pageSize = chunkSize;
            if (maxPerRegion > 0) {
                int remaining = maxPerRegion - processed - failed;
                if (remaining <= 0) break;
                pageSize = Math.min(chunkSize, remaining);
            }
            // NOT IN에 빈 컬렉션을 넘기지 않는다(존재할 수 없는 id로 대신한다).
            Collection<Long> excluded = excludedIds.isEmpty() ? List.of(-1L) : excludedIds;
            List<Place> places = regions.isEmpty()
                    ? placeRepository.findEnrichTargets(excluded, PageRequest.of(0, pageSize))
                    : placeRepository.findEnrichTargetsInRegions(regions, excluded, PageRequest.of(0, pageSize));

            if (places.isEmpty()) {
                break;
            }

            log.info("배치 보강 청크 처리 중 - {} {}건 (누적 성공 {}건, 실패 {}건)",
                    regions.isEmpty() ? "전체" : regions, places.size(), processed, failed);

            ChunkResult result = processChunk(places);
            processed += result.success();
            failed += result.failed();
            excludedIds.addAll(result.failedIds());

            consecutiveFailedChunks = result.success() == 0 ? consecutiveFailedChunks + 1 : 0;
            if (consecutiveFailedChunks >= MAX_CONSECUTIVE_FAILED_CHUNKS) {
                log.error("배치 보강 중단 - 연속 {}개 청크 전부 실패 (API 키·모델명·쿼터 확인 필요)",
                        consecutiveFailedChunks);
                break;
            }
        }
        totals[0] += processed;
        totals[1] += failed;
    }

    private List<String> parseRegions() {
        if (enrichRegions == null || enrichRegions.isBlank()) return List.of();
        return Arrays.stream(enrichRegions.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * 장소 청크를 처리하여 Anthropic Batch API로 보강한다.
     *
     * @param places 보강할 장소 목록 (최대 chunkSize건)
     * @return 성공/실패 건수와 실패한 place ID 목록
     */
    ChunkResult processChunk(List<Place> places) {
        try {
            String batchId;
            if (resumeBatchId != null && !resumeBatchId.isBlank()) {
                // 재제출하지 않고 기존 배치 결과만 다시 가져온다(중복 과금 방지)
                batchId = resumeBatchId.trim();
                log.info("기존 배치 결과 재사용 - batchId={} (재제출 없음)", batchId);
            } else {
                // 1. 배치 요청 구성
                List<Map<String, Object>> requests = buildBatchRequests(places);

                // 2. 배치 제출
                batchId = submitBatch(requests);
                if (batchId == null) {
                    log.error("배치 제출 실패 - {}건 모두 실패 처리", places.size());
                    return new ChunkResult(0, places.size(), allIds(places));
                }
            }

            // 3. 폴링으로 완료 대기
            String resultsUrl = pollForCompletion(batchId);
            if (resultsUrl == null) {
                log.error("배치 폴링 실패 또는 타임아웃 - batchId={}", batchId);
                return new ChunkResult(0, places.size(), allIds(places));
            }

            // 4. 결과 조회 및 처리
            return processResults(resultsUrl, places);

        } catch (Exception e) {
            log.error("배치 보강 처리 중 예외 발생: {}", e.getMessage(), e);
            return new ChunkResult(0, places.size(), allIds(places));
        }
    }

    private List<Long> allIds(List<Place> places) {
        return places.stream().map(Place::getId).toList();
    }

    /**
     * 장소 목록을 Anthropic Batch API 요청 형식으로 변환한다.
     */
    List<Map<String, Object>> buildBatchRequests(List<Place> places) {
        List<Map<String, Object>> requests = new ArrayList<>();

        for (Place place : places) {
            String prompt = buildEnrichPrompt(place);
            Map<String, Object> request = Map.of(
                    "custom_id", "place_" + place.getId(),
                    "params", Map.of(
                            "model", enrichModel,
                            "max_tokens", 1024,
                            // Haiku 5.5는 thinking이 기본으로 켜진다. 단순 JSON 생성엔 불필요하고,
                            // 켜두면 thinking 토큰이 max_tokens를 잡아먹어 JSON이 잘린다.
                            "thinking", Map.of("type", "disabled"),
                            "messages", List.of(
                                    Map.of("role", "user", "content", prompt)
                            )
                    )
            );
            requests.add(request);
        }

        return requests;
    }

    /**
     * 장소 정보를 기반으로 보강 프롬프트를 생성한다.
     */
    String buildEnrichPrompt(Place place) {
        if (DiningEnrichment.isDining(place.getCategory())) {
            return DiningEnrichment.buildPrompt(place.getName(), place.getCategory(), place.getDescription(),
                    DiningEnrichment.venueOf(place.getCategory()),
                    DiningEnrichment.isFamous(place.getWikidataSitelinks()));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("다음 장소 정보를 분석하여 태그, 설명, 추천 방문 시간대를 생성해주세요.\n\n");
        sb.append("장소 정보:\n");
        sb.append("- 이름: ").append(place.getName()).append("\n");
        sb.append("- 카테고리: ").append(place.getCategory()).append("\n");
        if (place.getRegion() != null) {
            sb.append("- 지역: ").append(place.getRegion()).append("\n");
        }
        if (place.getCountry() != null) {
            sb.append("- 국가: ").append(place.getCountry()).append("\n");
        }
        if (place.getDescription() != null && !place.getDescription().isBlank()) {
            sb.append("- 기존 설명: ").append(place.getDescription()).append("\n");
        }
        sb.append("\n");
        sb.append("다음 JSON 형식으로만 응답해주세요 (다른 텍스트 없이):\n");
        sb.append("{\n");
        sb.append("  \"tags\": [\"태그1\", \"태그2\", ...],  // 5~10개 관련 태그 (한국어)\n");
        sb.append("  \"description\": \"장소에 대한 2~3문장 설명 (한국어)\",\n");
        sb.append("  \"recommended_time_slots\": [\"morning\", \"afternoon\", \"evening\"],  // 추천 방문 시간대\n");
        sb.append("  \"recommended_duration_minutes\": 90,  // 평균 체류시간(분, 정수)\n");
        sb.append("  \"admission_fee\": 0,  // 입장료(원, 정수). 무료면 0, 유료면 성인 1인 기준 실제 입장료\n");
        sb.append("  \"place_role\": \"SIGHT\"  // 여행 일정에서의 역할(아래 목록 중 하나)\n");
        sb.append("}\n");
        sb.append("\n추천 시간대는 다음 중에서 선택: morning, afternoon, evening, night, all_day");
        sb.append("\n체류시간 가이드: 등산·오름·트레킹 150~240 / 해변·시장 60 / 박물관·미술관 90 / 카페 60 / 식당 70");
        sb.append("\n입장료 가이드: 오름·해변·포구·공원·산책로 등 자연 명소는 대부분 0(무료). "
                + "성산일출봉 5000, 산방산 1000처럼 관리되는 유료 명소만 실제 금액. 식당·카페·숙소는 0.");
        sb.append("\nplace_role: SIGHT(명소·자연·유적·박물관·공원) / ACTIVITY(체험·레저·테마파크) / "
                + "SHOPPING(쇼핑몰·백화점·쇼핑거리) / MARKET(전통시장·야시장) / LODGING(숙소) / TRANSIT(공항·역·터미널) / "
                + "NOT_VISITABLE(여행자가 일부러 찾아갈 곳이 아님: 편의점·은행·병원·사무실·주차장·일반 매장·아파트·학교 등)");
        return sb.toString();
    }

    /**
     * Anthropic Batch API에 배치를 제출한다. 5xx/IOException은 MAX_RETRIES회까지
     * 지수 백오프로 재시도한다(OpenAIEmbeddingService.callEmbeddingsApi와 동일한 전략 —
     * 이전엔 MAX_RETRIES 필드만 선언돼 있고 실제 재시도가 전혀 없었음). 4xx는 재시도 불필요.
     *
     * @return 배치 ID (실패 시 null)
     */
    String submitBatch(List<Map<String, Object>> requests) {
        String requestBody;
        try {
            Map<String, Object> body = Map.of("requests", requests);
            requestBody = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            log.error("배치 요청 본문 직렬화 실패: {}", e.getMessage(), e);
            return null;
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(BATCH_API_URL))
                .header("Content-Type", "application/json")
                .header("x-api-key", anthropicApiKey)
                .header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200 || response.statusCode() == 201) {
                    JsonNode root = objectMapper.readTree(response.body());
                    String batchId = root.get("id").asText();
                    log.info("배치 제출 성공 - batchId={}, 요청 수={}", batchId, requests.size());
                    return batchId;
                }

                if (response.statusCode() >= 500) {
                    log.warn("배치 제출 서버 에러 (HTTP {}), 재시도 {}/{}",
                            response.statusCode(), attempt + 1, MAX_RETRIES);
                    sleepWithBackoff(attempt);
                    continue;
                }

                log.error("배치 제출 실패 - HTTP {}: {}", response.statusCode(), response.body());
                return null;

            } catch (IOException e) {
                log.warn("배치 제출 IO 에러, 재시도 {}/{}: {}", attempt + 1, MAX_RETRIES, e.getMessage());
                sleepWithBackoff(attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("배치 제출 중 인터럽트 발생", e);
                return null;
            }
        }

        log.error("배치 제출 실패: {}회 재시도 후에도 실패", MAX_RETRIES);
        return null;
    }

    private void sleepWithBackoff(int attempt) {
        try {
            Thread.sleep(1000L * (1L << attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 배치 완료를 폴링으로 대기한다.
     *
     * @param batchId 배치 ID
     * @return 결과 파일 URL (실패/타임아웃 시 null)
     */
    String pollForCompletion(String batchId) {
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < MAX_POLL_DURATION_MS) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BATCH_API_URL + "/" + batchId))
                        .header("x-api-key", anthropicApiKey)
                        .header("anthropic-version", "2023-06-01")
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    log.warn("배치 상태 조회 실패 - HTTP {}", response.statusCode());
                    sleep(POLL_INTERVAL_MS);
                    continue;
                }

                JsonNode root = objectMapper.readTree(response.body());
                String status = root.get("processing_status").asText();

                switch (status) {
                    case "ended" -> {
                        String resultsUrl = root.get("results_url").asText();
                        log.info("배치 완료 - batchId={}", batchId);
                        return resultsUrl;
                    }
                    case "canceling", "expired" -> {
                        log.error("배치 비정상 종료 - batchId={}, status={}", batchId, status);
                        return null;
                    }
                    default -> {
                        // in_progress 등 → 계속 폴링
                        log.debug("배치 진행 중 - batchId={}, status={}", batchId, status);
                    }
                }

                sleep(POLL_INTERVAL_MS);

            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                log.warn("배치 상태 폴링 중 예외: {}", e.getMessage());
                sleep(POLL_INTERVAL_MS);
            }
        }

        log.error("배치 폴링 타임아웃 - batchId={}", batchId);
        return null;
    }

    /**
     * 배치 결과를 조회하고 장소를 보강한다.
     *
     * @param resultsUrl 결과 파일 URL
     * @param places     원본 장소 목록
     * @return 성공/실패 건수와 실패한 place ID 목록
     */
    ChunkResult processResults(String resultsUrl, List<Place> places) {
        try {
            // 결과는 요청 수만큼의 JSONL이라 청크가 커질수록 길어진다. 60초로는 515건에서
            // 이미 타임아웃이 났다(실측) — 배치 비용은 이미 지불된 뒤라 여기서 실패하면 그냥 날린다.
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(resultsUrl))
                    .header("x-api-key", anthropicApiKey)
                    .header("anthropic-version", "2023-06-01")
                    .timeout(Duration.ofMinutes(RESULTS_DOWNLOAD_TIMEOUT_MINUTES))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("배치 결과 조회 실패 - HTTP {}", response.statusCode());
                return new ChunkResult(0, places.size(), allIds(places));
            }

            return applyResults(response.body(), places);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("배치 결과 처리 중 예외: {}", e.getMessage(), e);
            return new ChunkResult(0, places.size(), allIds(places));
        }
    }

    /**
     * 결과 JSONL을 장소에 반영한다.
     * <p>
     * 실패 목록은 "청크에서 성공하지 못한 전부"로 계산한다. 행 처리 중 예외가 나거나 결과에서 빠진
     * 장소를 하나씩 챙기지 않으면 enriched_at이 NULL로 남아 다음 청크마다 다시 제출된다.
     */
    ChunkResult applyResults(String jsonl, List<Place> places) {
        Map<Long, Place> placeMap = new HashMap<>();
        for (Place place : places) {
            placeMap.put(place.getId(), place);
        }

        List<Long> succeededIds = new ArrayList<>();

        for (String line : jsonl.split("\n")) {
            if (line.isBlank()) continue;

            try {
                JsonNode resultNode = objectMapper.readTree(line);
                String customId = resultNode.get("custom_id").asText();
                Long placeId = Long.parseLong(customId.replace("place_", ""));
                Place place = placeMap.get(placeId);

                if (place == null) {
                    log.warn("결과에 매칭되는 장소 없음 - customId={}", customId);
                    continue;
                }

                JsonNode resultBody = resultNode.get("result");
                if (resultBody == null || !"succeeded".equals(resultBody.path("type").asText())) {
                    log.warn("장소 보강 실패 - placeId={}, result={}", placeId, resultBody);
                    continue;
                }

                // 메시지 응답에서 텍스트 추출
                String content = extractTextContent(resultBody.get("message"));
                if (content == null) {
                    log.warn("응답 텍스트 추출 실패 - placeId={}", placeId);
                    continue;
                }

                // JSON 파싱 및 장소 보강
                if (applyEnrichment(place, content)) {
                    placeRepository.save(place);
                    succeededIds.add(placeId);
                }

            } catch (Exception e) {
                log.warn("결과 행 처리 중 오류: {}", e.getMessage());
            }
        }

        // 보강 성공분은 임베딩을 초기화해 다음 임베딩 단계가 새 tags/description으로
        // 재임베딩하게 한다(embedding 컬럼은 updatable=false라 save()로는 반영 불가).
        if (!succeededIds.isEmpty()) {
            placeRepository.resetEmbeddings(succeededIds);
        }

        Set<Long> succeeded = new HashSet<>(succeededIds);
        List<Long> failedIds = places.stream().map(Place::getId).filter(id -> !succeeded.contains(id)).toList();
        log.info("배치 결과 처리 완료 - 성공: {}건, 실패: {}건", succeededIds.size(), failedIds.size());
        return new ChunkResult(succeededIds.size(), failedIds.size(), failedIds);
    }

    /**
     * Anthropic 메시지 응답에서 텍스트 콘텐츠를 추출한다.
     */
    String extractTextContent(JsonNode message) {
        if (message == null) return null;

        JsonNode contentArray = message.get("content");
        if (contentArray == null || !contentArray.isArray()) return null;

        StringBuilder text = new StringBuilder();
        for (JsonNode block : contentArray) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.get("text").asText());
            }
        }

        return text.isEmpty() ? null : text.toString();
    }

    /**
     * LLM 응답 JSON을 파싱하여 장소에 보강 데이터를 적용한다.
     *
     * @param place   대상 장소
     * @param content LLM 응답 텍스트 (JSON 형식)
     * @return 보강 성공 여부
     */
    /** 관광지 프롬프트가 허용하는 역할 값만 받는다. 모르는 값이면 null(일정 로직이 카테고리로 추정). */
    static String parseAttractionRole(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String v = node.asText("").trim().toUpperCase(java.util.Locale.ROOT);
        return ATTRACTION_ROLES.contains(v) ? v : null;
    }

    private static final java.util.Set<String> ATTRACTION_ROLES = java.util.Set.of(
            "SIGHT", "ACTIVITY", "SHOPPING", "MARKET", "LODGING", "TRANSIT", "NOT_VISITABLE");

    boolean applyEnrichment(Place place, String content) {
        try {
            // JSON 블록 추출 (코드 블록이 있을 수 있음)
            String json = extractJson(content);
            JsonNode root = objectMapper.readTree(json);

            if (DiningEnrichment.isDining(place.getCategory())) {
                if (!root.isObject()) {
                    log.warn("식당 보강 응답이 JSON 객체가 아님 - placeId={}", place.getId());
                    return false;
                }
                DiningEnrichment.Result dining = DiningEnrichment.interpret(root,
                        DiningEnrichment.venueOf(place.getCategory()),
                        DiningEnrichment.isFamous(place.getWikidataSitelinks()));
                place.enrichWith(dining.tags(), dining.description(), dining.timeSlots(),
                        dining.durationMinutes(), null);
                // 카테고리 리프까지 보고 음식 종류를 다시 정한다(태그만으로는 "라멘집"의 면요리가 빠질 수 있다).
                String cuisine = "MEAL".equals(dining.placeRole())
                        ? DiningEnrichment.cuisineOf(dining.tags(), place.getCategory()) : null;
                place.assignRole(dining.placeRole(), cuisine);
                return true;
            }

            List<String> tags = new ArrayList<>();
            JsonNode tagsNode = root.get("tags");
            if (tagsNode != null && tagsNode.isArray()) {
                for (JsonNode tag : tagsNode) {
                    tags.add(tag.asText());
                }
            }

            String description = null;
            JsonNode descNode = root.get("description");
            if (descNode != null && !descNode.isNull()) {
                description = descNode.asText();
            }

            List<String> timeSlots = new ArrayList<>();
            JsonNode timeSlotsNode = root.get("recommended_time_slots");
            if (timeSlotsNode != null && timeSlotsNode.isArray()) {
                for (JsonNode slot : timeSlotsNode) {
                    timeSlots.add(slot.asText());
                }
            }

            Integer durationMinutes = null;
            JsonNode durationNode = root.get("recommended_duration_minutes");
            if (durationNode != null && durationNode.canConvertToInt()) {
                int parsed = durationNode.asInt();
                if (parsed > 0) durationMinutes = parsed;
            }

            Integer admissionFee = null;
            JsonNode feeNode = root.get("admission_fee");
            if (feeNode != null && feeNode.canConvertToInt()) {
                int parsed = feeNode.asInt();
                if (parsed >= 0) admissionFee = parsed; // 0(무료)도 유효값
            }

            place.enrichWith(tags, description, timeSlots, durationMinutes, admissionFee);
            place.assignRole(parseAttractionRole(root.get("place_role")), null);
            return true;

        } catch (Exception e) {
            log.warn("보강 데이터 파싱 실패 - placeId={}, error={}", place.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * 응답 텍스트에서 JSON 부분을 추출한다.
     * 코드 블록(```json ... ```) 또는 순수 JSON을 지원한다.
     */
    String extractJson(String content) {
        String trimmed = content.trim();

        // 코드 블록 제거
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastBackticks = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastBackticks > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastBackticks).trim();
            }
        }

        // { 로 시작하는 JSON 찾기
        int jsonStart = trimmed.indexOf('{');
        int jsonEnd = trimmed.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return trimmed.substring(jsonStart, jsonEnd + 1);
        }

        return trimmed;
    }

    /**
     * 장소 목록을 chunkSize 단위로 분할한다.
     */
    List<List<Place>> splitIntoChunks(List<Place> places) {
        List<List<Place>> chunks = new ArrayList<>();
        for (int i = 0; i < places.size(); i += chunkSize) {
            chunks.add(places.subList(i, Math.min(i + chunkSize, places.size())));
        }
        return chunks;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
