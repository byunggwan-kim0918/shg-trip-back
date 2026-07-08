package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
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
 */
@Slf4j
@Component
@Profile("batch")
public class BatchEnrichScheduler {

    private static final String BATCH_API_URL = "https://api.anthropic.com/v1/messages/batches";
    private static final int MAX_RETRIES = 3;
    private static final long POLL_INTERVAL_MS = 10_000; // 10초
    private static final long MAX_POLL_DURATION_MS = 3_600_000; // 1시간
    private static final String ENRICH_MODEL = "claude-sonnet-4-20250514";

    private final PlaceRepository placeRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${anthropic.api-key:${ANTHROPIC_API_KEY:}}")
    private String anthropicApiKey;

    @Value("${batch.enrich.chunk-size:1000}")
    private int chunkSize;

    public BatchEnrichScheduler(PlaceRepository placeRepository, ObjectMapper objectMapper) {
        this.placeRepository = placeRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
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
     * page offset을 증가시키는 방식 대신, 이번 실행에서 실패한 place ID를 메모리에 누적해
     * 매번 첫 페이지를 다시 조회하면서 그 ID들을 걸러낸다(EmbeddingBatchJob과 동일한 이유 —
     * 고정 offset 증가는 실제 남은 건수보다 앞서나가 조용히 조기 종료될 수 있다).
     */
    public void enrich() {
        if (anthropicApiKey == null || anthropicApiKey.isBlank()) {
            log.warn("Anthropic API key가 설정되지 않았습니다. 배치 보강을 건너뜁니다.");
            return;
        }

        log.info("BatchEnrichScheduler 시작 - chunkSize={}", chunkSize);

        int totalProcessed = 0;
        int totalFailed = 0;
        Set<Long> excludedIds = new HashSet<>();

        while (true) {
            Page<Place> page = placeRepository.findByEnrichedAtIsNullAndActiveTrue(
                    PageRequest.of(0, chunkSize));

            if (page.isEmpty()) {
                break;
            }

            List<Place> places = page.getContent().stream()
                    .filter(p -> !excludedIds.contains(p.getId()))
                    .toList();

            if (places.isEmpty()) {
                // 남은 건 이번 실행에서 이미 실패 처리된 장소뿐 — 다음 실행에서 재시도
                break;
            }

            log.info("배치 보강 청크 처리 중 - {}건 (전체 미보강: {}건)", places.size(), page.getTotalElements());

            ChunkResult result = processChunk(places);
            totalProcessed += result.success();
            totalFailed += result.failed();
            excludedIds.addAll(result.failedIds());
        }

        log.info("BatchEnrichScheduler 완료 - 보강 성공: {}건, 실패: {}건", totalProcessed, totalFailed);
    }

    /**
     * 장소 청크를 처리하여 Anthropic Batch API로 보강한다.
     *
     * @param places 보강할 장소 목록 (최대 chunkSize건)
     * @return 성공/실패 건수와 실패한 place ID 목록
     */
    ChunkResult processChunk(List<Place> places) {
        try {
            // 1. 배치 요청 구성
            List<Map<String, Object>> requests = buildBatchRequests(places);

            // 2. 배치 제출
            String batchId = submitBatch(requests);
            if (batchId == null) {
                log.error("배치 제출 실패 - {}건 모두 실패 처리", places.size());
                return new ChunkResult(0, places.size(), allIds(places));
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
                            "model", ENRICH_MODEL,
                            "max_tokens", 1024,
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
        sb.append("  \"recommended_time_slots\": [\"morning\", \"afternoon\", \"evening\"]  // 추천 방문 시간대\n");
        sb.append("}\n");
        sb.append("\n추천 시간대는 다음 중에서 선택: morning, afternoon, evening, night, all_day");
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
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(resultsUrl))
                    .header("x-api-key", anthropicApiKey)
                    .header("anthropic-version", "2023-06-01")
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("배치 결과 조회 실패 - HTTP {}", response.statusCode());
                return new ChunkResult(0, places.size(), allIds(places));
            }

            // 장소 ID → Place 매핑
            Map<Long, Place> placeMap = new HashMap<>();
            for (Place place : places) {
                placeMap.put(place.getId(), place);
            }

            int success = 0;
            int failed = 0;
            List<Long> failedIds = new ArrayList<>();

            // JSONL 형식으로 한 줄씩 처리
            String[] lines = response.body().split("\n");
            for (String line : lines) {
                if (line.isBlank()) continue;

                try {
                    JsonNode resultNode = objectMapper.readTree(line);
                    String customId = resultNode.get("custom_id").asText();
                    Long placeId = Long.parseLong(customId.replace("place_", ""));
                    Place place = placeMap.get(placeId);

                    if (place == null) {
                        log.warn("결과에 매칭되는 장소 없음 - customId={}", customId);
                        failed++;
                        continue;
                    }

                    JsonNode resultBody = resultNode.get("result");
                    if (resultBody == null || !"succeeded".equals(resultBody.path("type").asText())) {
                        log.warn("장소 보강 실패 - placeId={}, result={}", placeId, resultBody);
                        failed++;
                        failedIds.add(placeId);
                        continue;
                    }

                    // 메시지 응답에서 텍스트 추출
                    JsonNode message = resultBody.get("message");
                    String content = extractTextContent(message);

                    if (content == null) {
                        log.warn("응답 텍스트 추출 실패 - placeId={}", placeId);
                        failed++;
                        failedIds.add(placeId);
                        continue;
                    }

                    // JSON 파싱 및 장소 보강
                    if (applyEnrichment(place, content)) {
                        placeRepository.save(place);
                        success++;
                    } else {
                        failed++;
                        failedIds.add(placeId);
                    }

                } catch (Exception e) {
                    log.warn("결과 행 처리 중 오류: {}", e.getMessage());
                    failed++;
                }
            }

            log.info("배치 결과 처리 완료 - 성공: {}건, 실패: {}건", success, failed);
            return new ChunkResult(success, failed, failedIds);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("배치 결과 처리 중 예외: {}", e.getMessage(), e);
            return new ChunkResult(0, places.size(), allIds(places));
        }
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
    boolean applyEnrichment(Place place, String content) {
        try {
            // JSON 블록 추출 (코드 블록이 있을 수 있음)
            String json = extractJson(content);
            JsonNode root = objectMapper.readTree(json);

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

            place.enrichWith(tags, description, timeSlots);
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
