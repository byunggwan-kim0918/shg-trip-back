package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.entity.PlaceAttributes;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Anthropic Batch API로 미보강 장소의 태그·설명·추천 시간대를 채운다.
 * 실패한 장소는 enriched_at이 NULL로 남아 다음 실행에서 다시 잡힌다.
 * <p>
 * 식당·카페·술집은 프롬프트와 결과 해석이 따로 있다({@link DiningEnrichment}).
 */
@Slf4j
@Component
@Profile("batch")
public class BatchEnrichScheduler {

    /** 연속으로 전부 실패한 청크가 이만큼이면 보강을 멈춘다. */
    static final int MAX_CONSECUTIVE_FAILED_CHUNKS = 3;
    private static final double MAX_FAILURE_RATIO = 0.2;

    private static final String BATCH_API_URL = "https://api.anthropic.com/v1/messages/batches";
    private static final int MAX_RETRIES = 3;
    /** 실행에서 제외한 실패 id 상한. 대상 조회의 NOT IN 바인드 파라미터 한도(pgjdbc 32767)보다 작아야 한다. */
    static final int MAX_EXCLUDED_IDS = 20_000;

    /** 결과 JSONL 다운로드 타임아웃(분). 청크 1000건 기준 여유 있게 잡는다. */
    private static final int RESULTS_DOWNLOAD_TIMEOUT_MINUTES = 10;
    /** 429의 Retry-After 상한(초). */
    private static final long MAX_RETRY_AFTER_SEC = 120;

    private final PlaceRepository placeRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final EmbeddingBatchJob embeddingBatchJob;

    @Value("${anthropic.api-key:${ANTHROPIC_API_KEY:}}")
    private String anthropicApiKey;

    @Value("${batch.enrich.chunk-size:1000}")
    private int chunkSize;

    /** 태그·설명 생성은 Haiku로 충분하다. */
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
     * 지역 목록(batch.enrich.regions)과 함께 써야 지역별로 센다. 목록이 비어 있으면 전체 합계 상한이다.
     */
    @Value("${batch.enrich.max-per-region:0}")
    private int maxPerRegion;

    /**
     * 이미 제출·완료된 배치의 결과를 다시 적용할 때 쓰는 batchId.
     * Anthropic이 결과를 29일간 보관하므로, 결과 처리 단계에서만 실패한 경우 재제출(재과금) 없이
     * 복구할 수 있다.
     */
    @Value("${batch.enrich.resume-batch-id:}")
    private String resumeBatchId;

    /** 묶음 하나의 결과를 기다리는 상한. Batch API는 최대 24시간 걸린다. 끊긴 묶음은 resume-batch-id로 다시 반영한다. */
    @Value("${batch.enrich.max-poll-duration:PT24H}")
    private Duration maxPollDuration = Duration.ofHours(24);

    /**
     * 보강 단계 전체(결과 대기 포함)의 상한. 넘기면 배치 작업 제한(Step Functions 36h)에 걸려 뒤의 임베딩 단계가
     * 돌지 않고, 내려 둔 근사 검색 인덱스도 다시 만들어지지 않는다.
     */
    @Value("${batch.enrich.max-duration:PT10H}")
    private Duration maxDuration = Duration.ofHours(10);

    @Value("${anthropic.batch.poll-interval-ms:10000}")
    private long pollIntervalMs = 10_000;

    /** 재시도 대기의 첫 단위. 시도마다 두 배로 늘린다. */
    private long retryBaseMs = 1_000;

    /** 이번 보강 단계의 끝 시각(epoch ms). 결과 대기도 이 시각에서 끊는다. */
    private volatile long runDeadlineMs = Long.MAX_VALUE;

    /**
     * v1: 미보강 장소의 태그·설명·역할 보강. v2: 속성만 분류({@link AttributeEnrichment}).
     * 운영 배치가 켜져 있어 기본값은 v1이다. v2는 대상·비용이 달라 명시적으로 켤 때만 돈다.
     */
    @Value("${batch.enrich.mode:v1}")
    private String mode;

    /** 동시에 제출해 기다리는 묶음 수. 묶음마다 10초 간격으로 상태를 조회해 이 수만큼 Batch API 요청이 늘어난다. */
    @Value("${batch.enrich.parallel-chunks:5}")
    private int parallelChunks;

    @org.springframework.beans.factory.annotation.Autowired
    public BatchEnrichScheduler(PlaceRepository placeRepository, ObjectMapper objectMapper,
                                EmbeddingBatchJob embeddingBatchJob) {
        this(placeRepository, objectMapper, embeddingBatchJob, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                // 결과 파일 URL은 서명된 스토리지로 302 리다이렉트되어, 따라가지 않으면 본문이 비어 온다
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    BatchEnrichScheduler(PlaceRepository placeRepository, ObjectMapper objectMapper,
                         EmbeddingBatchJob embeddingBatchJob, HttpClient httpClient) {
        this.placeRepository = placeRepository;
        this.objectMapper = objectMapper;
        this.embeddingBatchJob = embeddingBatchJob;
        this.httpClient = httpClient;
    }

    /**
     * failedIds는 enriched_at이 여전히 NULL이라 다음 조회에 다시 잡히므로 이번 실행에서 제외할 ID다.
     *
     * @param countsTowardAbort 연속 실패 중단에 세는가. 결과 대기 시간 초과와 전부 만료된 묶음은 API 키·모델
     *                          오류가 아니라 세지 않는다
     */
    record ChunkResult(int success, int failed, List<Long> failedIds, boolean countsTowardAbort) {
        ChunkResult(int success, int failed, List<Long> failedIds) {
            this(success, failed, failedIds, true);
        }
    }

    /** @param resultsUrl 결과 파일 URL, 끝나지 않았거나 실패면 null */
    record PollResult(String resultsUrl, boolean timedOut) {
    }

    /**
     * 매번 첫 페이지를 다시 조회하고 이번 실행의 실패 ID는 쿼리에서 뺀다. 고정 offset은 남은 건수보다
     * 앞서 나가 조기 종료된다. 조회 순서가 인기도 → 비식당이라 중간에 끊겨도 중요한 장소부터 끝나 있다.
     * <p>
     * 연속 {@value #MAX_CONSECUTIVE_FAILED_CHUNKS}개 청크가 전부 실패하면 멈춘다. API 키 오류처럼
     * 모든 요청이 실패할 때 수만 건을 헛돌지 않게 한다.
     */
    public void enrich() {
        if (anthropicApiKey == null || anthropicApiKey.isBlank()) {
            log.warn("Anthropic API key가 설정되지 않았습니다. 배치 보강을 건너뜁니다.");
            return;
        }

        List<String> resumeIds = parseList(resumeBatchId);
        if (!resumeIds.isEmpty()) {
            resume(resumeIds);
            return;
        }

        List<String> regions = parseRegions();
        if (mode != null && !mode.isBlank() && !attributeMode() && !"v1".equalsIgnoreCase(mode.trim())) {
            log.warn("알 수 없는 batch.enrich.mode={} — v1로 실행합니다", mode);
        }
        if (attributeMode() && regions.isEmpty()) {
            log.warn("속성 보강(v2)은 batch.enrich.regions가 필요합니다. 건너뜁니다.");
            return;
        }
        log.info("BatchEnrichScheduler 시작 - mode={}, chunkSize={}, regions={}, maxPerRegion={}",
                attributeMode() ? "v2" : "v1", chunkSize, regions.isEmpty() ? "전체" : regions,
                maxPerRegion > 0 ? maxPerRegion : "무제한");
        if (attributeMode()) {
            log.info("속성 보강 대상 {}건", placeRepository.countAttributeTargetsInRegions(regions));
        } else {
            long waiting = placeRepository.countEnrichWaitingForWikidata();
            // batch.wikidata.countries 밖 국가, 한국 50곳 미만 지역, 좌표·지역 없는 행은 계속 기다려 로그로 드러낸다(지역 무관 전체 건수).
            if (waiting > 0) log.warn("Wikidata 매칭 전이라 보강을 미룬 장소 {}건", waiting);
        }

        long exhausted = placeRepository.countEnrichExhausted();
        if (exhausted > 0) log.warn("보강 응답이 3번 실패해 제외된 장소 {}건", exhausted);

        long deadline = System.currentTimeMillis() + maxDuration.toMillis();
        runDeadlineMs = deadline;
        int[] totals = new int[3];
        if (maxPerRegion > 0 && !regions.isEmpty()) {
            // 지역별로 따로 돌려야 큰 지역(서울)이 상한을 다 먹지 않는다.
            for (String region : regions) {
                enrichScope(List.of(region), totals, deadline);
            }
        } else {
            enrichScope(regions, totals, deadline);
        }

        log.info("BatchEnrichScheduler 완료 - 보강 성공: {}건, 실패: {}건, 중단 {}회",
                totals[0], totals[1], totals[2]);
        // 장소 단위 실패는 모델 응답 불량으로 늘 조금씩 나와 비율로 본다.
        if (totals[2] > 0 || totals[1] > (totals[0] + totals[1]) * MAX_FAILURE_RATIO) {
            throw new BatchStageFailed("배치 보강 실패 " + totals[1] + "건, 중단 " + totals[2] + "회");
        }
    }

    /** 한 범위(지역 목록, 비면 전체)를 상한까지 보강한다. totals[0]=성공, [1]=실패, [2]=연속 실패 중단 누적. */
    private void enrichScope(List<String> regions, int[] totals, long deadline) {
        int processed = 0;
        int failed = 0;
        int consecutiveFailedChunks = 0;
        Set<Long> excludedIds = new HashSet<>();
        int parallel = Math.max(1, parallelChunks);

        while (true) {
            if (System.currentTimeMillis() >= deadline) {
                log.warn("보강 시간 상한({}) 도달 - 남은 장소는 다음 실행에서 보강", maxDuration);
                break;
            }
            int pageSize = chunkSize * parallel;
            if (maxPerRegion > 0) {
                int remaining = maxPerRegion - processed - failed;
                if (remaining <= 0) break;
                pageSize = Math.min(pageSize, remaining);
            }
            // NOT IN에 빈 컬렉션을 넘기지 않는다(존재할 수 없는 id로 대신한다).
            Collection<Long> excluded = excludedIds.isEmpty() ? List.of(-1L) : excludedIds;
            List<Place> places = attributeMode()
                    ? placeRepository.findAttributeTargetsInRegions(regions, excluded, PageRequest.of(0, pageSize))
                    : regions.isEmpty()
                    ? placeRepository.findEnrichTargets(excluded, PageRequest.of(0, pageSize))
                    : placeRepository.findEnrichTargetsInRegions(regions, excluded, PageRequest.of(0, pageSize));

            if (places.isEmpty()) {
                break;
            }

            log.info("배치 보강 청크 처리 중 - {} {}건 (누적 성공 {}건, 실패 {}건)",
                    regions.isEmpty() ? "전체" : regions, places.size(), processed, failed);

            for (ChunkResult result : processChunks(splitIntoChunks(places))) {
                processed += result.success();
                failed += result.failed();
                excludedIds.addAll(result.failedIds());
                if (result.success() > 0) {
                    consecutiveFailedChunks = 0;
                } else if (result.countsTowardAbort()) {
                    consecutiveFailedChunks++;
                }
            }
            if (excludedIds.size() > MAX_EXCLUDED_IDS) {
                log.error("배치 보강 중단 - 이번 실행 실패 {}건이 상한 {}건을 넘음", excludedIds.size(), MAX_EXCLUDED_IDS);
                totals[2]++;
                break;
            }
            if (consecutiveFailedChunks >= MAX_CONSECUTIVE_FAILED_CHUNKS) {
                log.error("배치 보강 중단 - 연속 {}개 청크 전부 실패 (API 키·모델명·쿼터 확인 필요)",
                        consecutiveFailedChunks);
                totals[2]++;
                break;
            }
        }
        totals[0] += processed;
        totals[1] += failed;
    }

    boolean attributeMode() {
        return mode != null && "v2".equalsIgnoreCase(mode.trim());
    }

    private List<String> parseRegions() {
        return parseList(enrichRegions);
    }

    private static List<String> parseList(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * 끝난 배치들의 결과만 다시 반영한다(재제출 없음, 결과 보관 29일). 장소는 결과의 custom_id로 읽는다.
     * 그 사이 보강된 행은 옛 결과로 덮지 않고, 실패 횟수도 올리지 않는다.
     */
    void resume(List<String> batchIds) {
        runDeadlineMs = Long.MAX_VALUE;
        int succeeded = 0;
        int failed = 0;
        int failedBatches = 0;
        for (String batchId : batchIds) {
            try {
                log.info("기존 배치 결과 재반영 - batchId={} (재제출 없음)", batchId);
                String jsonl = fetchResults(batchId);
                if (jsonl == null) {
                    log.error("배치 결과를 가져오지 못함 - batchId={}", batchId);
                    failedBatches++;
                    continue;
                }
                List<Place> pending = placeRepository.findAllById(placeIdsIn(jsonl)).stream()
                        .filter(this::stillPending)
                        .toList();
                if (pending.isEmpty()) log.warn("재반영할 장소 없음(모두 이미 반영됨) - batchId={}, mode={}", batchId, mode);
                ChunkResult result = applyResults(jsonl, pending, false);
                succeeded += result.success();
                failed += result.failed();
            } catch (Exception e) {
                log.error("배치 결과 재반영 중 예외 - batchId={}: {}", batchId, e.getMessage(), e);
                failedBatches++;
            }
        }
        log.info("배치 결과 재반영 완료 - 성공 {}건, 실패 {}건, 처리 못 한 배치 {}개", succeeded, failed, failedBatches);
        if (failedBatches > 0 || failed > (succeeded + failed) * MAX_FAILURE_RATIO) {
            throw new BatchStageFailed("배치 결과 재반영 실패 " + failed + "건, 배치 " + failedBatches + "개");
        }
    }

    private boolean stillPending(Place p) {
        if (!attributeMode()) return p.getEnrichedAt() == null;
        return p.getEnrichVersion() == null || p.getEnrichVersion() < AttributeEnrichment.VERSION;
    }

    /** 끝난 배치의 결과 JSONL. 끝나지 않았거나 실패면 null. */
    String fetchResults(String batchId) {
        PollResult poll = pollForCompletion(batchId);
        return poll.resultsUrl() == null ? null : downloadResults(poll.resultsUrl());
    }

    List<Long> placeIdsIn(String jsonl) {
        List<Long> ids = new ArrayList<>();
        for (String line : jsonl.split("\n")) {
            if (line.isBlank()) continue;
            try {
                String customId = objectMapper.readTree(line).path("custom_id").asText("");
                if (customId.startsWith("place_")) ids.add(Long.parseLong(customId.substring("place_".length())));
            } catch (Exception e) {
                log.warn("결과 행 custom_id 읽기 실패: {}", e.getMessage());
            }
        }
        return ids;
    }

    /** 결과는 chunks와 같은 순서다. 묶음 처리 중 예외는 그 묶음 전부를 실패로 돌려준다. */
    List<ChunkResult> processChunks(List<List<Place>> chunks) {
        if (chunks.size() == 1) {
            return List.of(processChunk(chunks.get(0)));
        }
        List<ChunkResult> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ChunkResult>> futures = chunks.stream()
                    .map(chunk -> pool.submit(() -> processChunk(chunk)))
                    .toList();
            for (int i = 0; i < chunks.size(); i++) {
                try {
                    results.add(futures.get(i).get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(new ChunkResult(0, chunks.get(i).size(), allIds(chunks.get(i))));
                } catch (ExecutionException e) {
                    log.error("배치 보강 묶음 처리 중 예외: {}", e.getCause().getMessage(), e.getCause());
                    results.add(new ChunkResult(0, chunks.get(i).size(), allIds(chunks.get(i))));
                }
            }
        }
        return results;
    }

    ChunkResult processChunk(List<Place> places) {
        try {
            List<Map<String, Object>> requests = buildBatchRequests(places);
            String batchId = submitBatch(requests);
            if (batchId == null) {
                log.error("배치 제출 실패 - {}건 모두 실패 처리", places.size());
                return new ChunkResult(0, places.size(), allIds(places));
            }

            PollResult poll = pollForCompletion(batchId);
            if (poll.resultsUrl() == null) {
                // 결과는 나중에 batch.enrich.resume-batch-id로 다시 반영할 수 있다
                log.error("배치 결과 대기 실패 - batchId={}, 시간 초과={}", batchId, poll.timedOut());
                return new ChunkResult(0, places.size(), allIds(places), !poll.timedOut());
            }

            return processResults(batchId, poll.resultsUrl(), places);

        } catch (Exception e) {
            log.error("배치 보강 처리 중 예외 발생: {}", e.getMessage(), e);
            return new ChunkResult(0, places.size(), allIds(places));
        }
    }

    private List<Long> allIds(List<Place> places) {
        return places.stream().map(Place::getId).toList();
    }

    List<Map<String, Object>> buildBatchRequests(List<Place> places) {
        List<Map<String, Object>> requests = new ArrayList<>();

        for (Place place : places) {
            if (attributeMode()) {
                requests.add(Map.of("custom_id", "place_" + place.getId(),
                        "params", AttributeEnrichment.requestParams(place, enrichModel)));
                continue;
            }
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

    String buildEnrichPrompt(Place place) {
        boolean overseas = OverseasEnrichment.isOverseas(place.getCountry());
        if (DiningEnrichment.isDiningVenue(place.getCategory())) {
            return DiningEnrichment.buildPrompt(place.getName(), place.getCategory(), place.getDescription(),
                    DiningEnrichment.venueOf(place.getCategory()),
                    DiningEnrichment.isFamous(place.getWikidataSitelinks()),
                    overseas ? OverseasEnrichment.contextLines(place) : null,
                    overseas && place.getNameKo() == null);
        }
        if (overseas) return buildOverseasPrompt(place);
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
     * 해외 관광지 프롬프트. 한국 프롬프트와 같은 항목에 국가·여행지·주소, 현지 통화 입장료, (비어 있으면) name_ko를 더한다.
     * 입장료 예시가 제주 명소라 해외 행에 쓰면 원화 금액이 나온다.
     */
    String buildOverseasPrompt(Place place) {
        OverseasEnrichment.Currency currency = OverseasEnrichment.currencyOf(place.getCountry());
        boolean askNameKo = place.getNameKo() == null;
        StringBuilder sb = new StringBuilder();
        sb.append("다음 해외 장소 정보를 분석하여 한국인 여행자용 태그, 설명, 추천 방문 시간대를 생성해주세요.\n\n");
        sb.append("장소 정보:\n");
        sb.append("- 이름: ").append(place.getName()).append("\n");
        sb.append("- 카테고리: ").append(place.getCategory()).append("\n");
        sb.append(OverseasEnrichment.contextLines(place));
        if (place.getDescription() != null && !place.getDescription().isBlank()) {
            sb.append("- 기존 설명: ").append(place.getDescription()).append("\n");
        }
        sb.append("\n");
        sb.append("다음 JSON 형식으로만 응답해주세요 (다른 텍스트 없이):\n");
        sb.append("{\n");
        if (askNameKo) sb.append("  \"name_ko\": \"...\",  // 한국어 표시 이름(아래 규칙)\n");
        sb.append("  \"tags\": [\"태그1\", \"태그2\", ...],  // 5~10개 관련 태그 (한국어)\n");
        sb.append("  \"description\": \"장소에 대한 2~3문장 설명 (한국어)\",\n");
        sb.append("  \"recommended_time_slots\": [\"morning\", \"afternoon\", \"evening\"],  // 추천 방문 시간대\n");
        sb.append("  \"recommended_duration_minutes\": 90,  // 평균 체류시간(분, 정수)\n");
        sb.append("  \"admission_fee_local\": 0,  // 입장료(").append(currency.label()).append(", 정수). ")
                .append("무료면 0, 유료면 성인 1인 기준 실제 입장료\n");
        sb.append("  \"place_role\": \"SIGHT\",  // 여행 일정에서의 역할(아래 목록 중 하나)\n");
        sb.append("  \"traveler_fame\": 0  // 알려진 정도(0~2, 아래 규칙)\n");
        sb.append("}\n");
        sb.append("\n추천 시간대는 다음 중에서 선택: morning, afternoon, evening, night, all_day");
        sb.append("\n체류시간 가이드: 등산·트레킹 150~240 / 해변·시장 60 / 박물관·미술관 90 / 사찰·신사 60 / 테마파크 300");
        sb.append("\n입장료 가이드: 공원·해변·거리·외관만 보는 곳·대부분의 신사는 0(무료). 관리되는 유료 명소만 ")
                .append(currency.label()).append(" 단위 실제 금액. 식당·카페·숙소는 0.");
        sb.append("\nplace_role: SIGHT(명소·자연·유적·박물관·공원) / ACTIVITY(체험·레저·테마파크) / "
                + "SHOPPING(쇼핑몰·백화점·쇼핑거리) / MARKET(전통시장·야시장) / LODGING(숙소) / TRANSIT(공항·역·터미널) / "
                + "NOT_VISITABLE(여행자가 일부러 찾아갈 곳이 아님: 편의점·은행·병원·사무실·주차장·일반 매장·아파트·학교 등)");
        sb.append("\n").append(OverseasEnrichment.fameRule());
        if (askNameKo) sb.append("\n").append(OverseasEnrichment.nameKoRule());
        return sb.toString();
    }

    /**
     * 5xx/IOException은 MAX_RETRIES회까지 지수 백오프로 재시도하고, 4xx는 재시도하지 않는다.
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

                if (response.statusCode() >= 500 || response.statusCode() == 429) {
                    log.warn("배치 제출 일시 오류 (HTTP {}), 재시도 {}/{}",
                            response.statusCode(), attempt + 1, MAX_RETRIES);
                    if (attempt < MAX_RETRIES - 1) sleepBeforeRetry(attempt, response);
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
        sleep(retryBaseMs * (1L << attempt));
    }

    /** Retry-After보다 일찍 보내면 다시 429다. */
    private void sleepBeforeRetry(int attempt, HttpResponse<String> response) {
        sleep(Math.max(retryBaseMs * (1L << attempt), retryAfterMs(response)));
    }

    static long retryAfterMs(HttpResponse<String> response) {
        java.net.http.HttpHeaders headers = response.headers();
        if (response.statusCode() != 429 || headers == null) return 0;
        return headers.firstValue("retry-after").map(v -> {
            try {
                return Math.min(Long.parseLong(v.trim()), MAX_RETRY_AFTER_SEC) * 1000;
            } catch (NumberFormatException e) {
                return 0L;
            }
        }).orElse(0L);
    }

    PollResult pollForCompletion(String batchId) {
        long until = Math.min(System.currentTimeMillis() + maxPollDuration.toMillis(), runDeadlineMs);

        while (System.currentTimeMillis() < until) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BATCH_API_URL + "/" + batchId))
                        .header("x-api-key", anthropicApiKey)
                        .header("anthropic-version", "2023-06-01")
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                int code = response.statusCode();
                if (code >= 400 && code < 500 && code != 429) {
                    // 없는 batchId(오타·보관 기간 지남)·키 오류는 기다려도 같다
                    log.error("배치 상태 조회 실패 - batchId={}, HTTP {}: {}", batchId, code, response.body());
                    return new PollResult(null, false);
                }
                if (code != 200) {
                    log.warn("배치 상태 조회 실패 - HTTP {}", code);
                    sleep(Math.max(pollIntervalMs, retryAfterMs(response)));
                    continue;
                }

                JsonNode root = objectMapper.readTree(response.body());
                String status = root.get("processing_status").asText();

                switch (status) {
                    case "ended" -> {
                        String resultsUrl = root.get("results_url").asText();
                        log.info("배치 완료 - batchId={}", batchId);
                        return new PollResult(resultsUrl, false);
                    }
                    case "canceling" -> {
                        log.error("배치 취소 중 - batchId={}", batchId);
                        return new PollResult(null, false);
                    }
                    default -> {
                        log.debug("배치 진행 중 - batchId={}, status={}", batchId, status);
                    }
                }

                sleep(pollIntervalMs);

            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return new PollResult(null, false);
                }
                log.warn("배치 상태 폴링 중 예외: {}", e.getMessage());
                sleep(pollIntervalMs);
            }
        }

        log.error("배치 폴링 타임아웃 - batchId={}", batchId);
        return new PollResult(null, true);
    }

    ChunkResult processResults(String batchId, String resultsUrl, List<Place> places) {
        String jsonl = downloadResults(resultsUrl);
        if (jsonl == null) {
            log.error("배치 결과 다운로드 실패 - batchId={} (resume-batch-id로 다시 반영 가능)", batchId);
            return new ChunkResult(0, places.size(), allIds(places));
        }
        return applyResults(jsonl, places);
    }

    /** 결과 JSONL. 실패면 null. 비용은 이미 낸 뒤라 실패해도 resume-batch-id로 다시 받을 수 있다. */
    String downloadResults(String resultsUrl) {
        try {
            // 결과는 요청 수만큼의 JSONL이라 청크가 커질수록 길어진다.
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
                return null;
            }
            return response.body();

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("배치 결과 다운로드 중 예외: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 실패 목록은 "청크에서 성공하지 못한 전부"로 계산한다. 행 처리 중 예외가 나거나 결과에서 빠진
     * 장소를 하나씩 챙기지 않으면 enriched_at이 NULL로 남아 다음 청크마다 다시 제출된다.
     */
    ChunkResult applyResults(String jsonl, List<Place> places) {
        return applyResults(jsonl, places, true);
    }

    /**
     * 장소 실패 횟수에는 응답을 해석하지 못한 경우와, 성공이 있는 묶음의 요청 오류(invalid_request_error)만 센다.
     * 만료·취소·일시 오류(overloaded·api_error)·성공 0건 묶음의 오류(모델명 등 묶음 전체 장애)는 장소 탓이 아니다.
     *
     * @param countAttempts 장소 실패 횟수(enrich_attempts)를 올린다. 이미 반영한 결과를 다시 반영할 때는 끈다
     */
    ChunkResult applyResults(String jsonl, List<Place> places, boolean countAttempts) {
        Map<Long, Place> placeMap = new HashMap<>();
        for (Place place : places) {
            placeMap.put(place.getId(), place);
        }

        List<Long> succeededIds = new ArrayList<>();
        Set<Long> erroredIds = new HashSet<>();
        Set<Long> unreadableIds = new HashSet<>();
        int expiredOrCanceled = 0;
        int seen = 0;
        long[] usage = new long[4]; // input, output, cache read, cache write
        int unclassifiable = 0;

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

                seen++;
                JsonNode resultBody = resultNode.get("result");
                String type = resultBody == null ? "" : resultBody.path("type").asText();
                if (!"succeeded".equals(type)) {
                    log.warn("장소 보강 실패 - placeId={}, result={}", placeId, resultBody);
                    if ("expired".equals(type) || "canceled".equals(type)) expiredOrCanceled++;
                    else if (isRequestError(resultBody)) erroredIds.add(placeId);
                    continue;
                }

                addUsage(usage, resultBody.path("message").path("usage"));
                if (attributeMode()) {
                    PlaceAttributes attributes =
                            AttributeEnrichment.interpret(resultBody.get("message"), place.getCategory());
                    if (attributes == null) {
                        log.warn("속성 분류 응답 없음 또는 잘림 - placeId={}", placeId);
                        unreadableIds.add(placeId);
                        continue;
                    }
                    if (attributes.hasNoClassification()) unclassifiable++;
                    placeRepository.writeAttributeClassification(placeId, attributes.classificationJson(),
                            (short) AttributeEnrichment.VERSION);
                    succeededIds.add(placeId);
                    continue;
                }

                String content = extractTextContent(resultBody.get("message"));
                if (content == null) {
                    log.warn("응답 텍스트 추출 실패 - placeId={}", placeId);
                    unreadableIds.add(placeId);
                    continue;
                }

                if (!applyEnrichment(place, content)) {
                    unreadableIds.add(placeId);
                    continue;
                }
                placeRepository.writeEnrichment(place);
                succeededIds.add(placeId);

            } catch (Exception e) {
                // 저장 실패·코드 오류는 장소 탓이 아니라 실패 횟수에 넣지 않는다
                log.error("결과 행 처리 중 오류: {}", e.getMessage(), e);
            }
        }

        // 보강분을 비워 두고 마지막 임베딩 단계에서 채우면 보강이 도는 내내 벡터 검색에서 빠진다.
        // 재임베딩에 실패한 것만 비워 임베딩 단계가 다시 잡게 한다. 속성 보강은 임베딩 텍스트를 바꾸지 않는다.
        if (!succeededIds.isEmpty() && !attributeMode()) {
            List<Place> enriched = succeededIds.stream().map(placeMap::get).toList();
            List<Long> notReembedded = embeddingBatchJob.reembed(enriched);
            if (!notReembedded.isEmpty()) {
                placeRepository.resetEmbeddings(notReembedded);
            }
        }

        Set<Long> succeeded = new HashSet<>(succeededIds);
        List<Long> failedIds = places.stream().map(Place::getId).filter(id -> !succeeded.contains(id)).toList();
        Set<Long> placeFaults = new HashSet<>(unreadableIds);
        if (!succeededIds.isEmpty()) placeFaults.addAll(erroredIds);
        if (countAttempts && !placeFaults.isEmpty() && !attributeMode()) {
            try {
                placeRepository.incrementEnrichAttempts(placeFaults);
            } catch (Exception e) {
                log.warn("보강 실패 횟수 기록 실패: {}", e.getMessage());
            }
        }
        boolean allExpired = succeededIds.isEmpty() && seen > 0 && expiredOrCanceled == seen;
        log.info("배치 결과 처리 완료 - 성공: {}건(분류 없음 {}건), 실패: {}건, 토큰 input={} output={} 캐시읽음={} 캐시쓰기={}",
                succeededIds.size(), unclassifiable, failedIds.size(), usage[0], usage[1], usage[2], usage[3]);
        return new ChunkResult(succeededIds.size(), failedIds.size(), failedIds, !allExpired);
    }

    /** errored 결과의 오류 종류. 결과 형식이 error.error.type이라 한 단계 안쪽을 먼저 본다. */
    static boolean isRequestError(JsonNode resultBody) {
        JsonNode error = resultBody == null ? null : resultBody.path("error");
        if (error == null || error.isMissingNode()) return false;
        String type = error.path("error").path("type").asText(error.path("type").asText(""));
        return "invalid_request_error".equals(type);
    }

    private static void addUsage(long[] usage, JsonNode node) {
        usage[0] += node.path("input_tokens").asLong(0);
        usage[1] += node.path("output_tokens").asLong(0);
        usage[2] += node.path("cache_read_input_tokens").asLong(0);
        usage[3] += node.path("cache_creation_input_tokens").asLong(0);
    }

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

    /** 관광지 프롬프트가 허용하는 역할 값만 받는다. 모르는 값이면 null(일정 로직이 카테고리로 추정). */
    static String parseAttractionRole(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String v = node.asText("").trim().toUpperCase(java.util.Locale.ROOT);
        return ATTRACTION_ROLES.contains(v) ? v : null;
    }

    private static final java.util.Set<String> ATTRACTION_ROLES = java.util.Set.of(
            "SIGHT", "ACTIVITY", "SHOPPING", "MARKET", "LODGING", "TRANSIT", "NOT_VISITABLE");

    /** 응답이 JSON이 아니면(잘림 포함) false다. 그 밖의 예외는 던진다. 코드 오류를 장소 실패 횟수로 세지 않는다. */
    boolean applyEnrichment(Place place, String content) {
        try {
            String json = extractJson(content);
            JsonNode root = objectMapper.readTree(json);
            boolean overseas = OverseasEnrichment.isOverseas(place.getCountry());

            if (DiningEnrichment.isDiningVenue(place.getCategory())) {
                if (!root.isObject()) {
                    log.warn("식당 보강 응답이 JSON 객체가 아님 - placeId={}", place.getId());
                    return false;
                }
                Short fame = overseas ? OverseasEnrichment.fame(root.get("traveler_fame")) : null;
                // Wikidata에 없는 유명 식당은 모델이 알려진 곳이라고 할 때만 설명을 받는다.
                boolean describe = DiningEnrichment.isFamous(place.getWikidataSitelinks()) || (fame != null && fame >= 1);
                DiningEnrichment.Result dining = DiningEnrichment.interpret(root,
                        DiningEnrichment.venueOf(place.getCategory()), describe,
                        overseas ? OverseasEnrichment.ownRegionNames(place) : Set.of());
                place.enrichWith(dining.tags(), dining.description(), dining.timeSlots(),
                        dining.durationMinutes(), null);
                // 카테고리 리프까지 보고 음식 종류를 다시 정한다(태그만으로는 "라멘집"의 면요리가 빠질 수 있다).
                String cuisine = !"MEAL".equals(dining.placeRole()) ? null
                        : overseas ? OverseasEnrichment.cuisineOf(dining.tags(), place.getCategory())
                        : DiningEnrichment.cuisineOf(dining.tags(), place.getCategory());
                place.assignRole(dining.placeRole(), cuisine);
                place.rateFame(fame);
                if (overseas) place.fillNameKo(OverseasEnrichment.nameKo(root.get("name_ko")));
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
            if (!overseas && feeNode != null && feeNode.canConvertToInt()) {
                int parsed = feeNode.asInt();
                if (parsed >= 0) admissionFee = parsed; // 0(무료)도 유효값
            }

            // 공식 상세를 반영한 행은 모델 추정 입장료로 덮지 않는다
            place.enrichWith(tags, description, timeSlots, durationMinutes,
                    place.getTourapiDetailAt() != null ? null : admissionFee);
            place.assignRole(parseAttractionRole(root.get("place_role")), null);
            if (overseas) {
                OverseasEnrichment.Currency currency = OverseasEnrichment.currencyOf(place.getCountry());
                place.recordAdmissionLocal(OverseasEnrichment.admissionLocal(root.get("admission_fee_local"), currency),
                        currency.code());
                place.rateFame(OverseasEnrichment.fame(root.get("traveler_fame")));
                place.fillNameKo(OverseasEnrichment.nameKo(root.get("name_ko")));
            }
            return true;

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("보강 데이터 파싱 실패 - placeId={}, error={}", place.getId(), e.getMessage());
            return false;
        }
    }

    /** 코드 블록(```json ... ```)과 순수 JSON을 모두 받는다. */
    String extractJson(String content) {
        String trimmed = content.trim();

        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastBackticks = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastBackticks > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastBackticks).trim();
            }
        }

        int jsonStart = trimmed.indexOf('{');
        int jsonEnd = trimmed.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return trimmed.substring(jsonStart, jsonEnd + 1);
        }

        return trimmed;
    }

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
