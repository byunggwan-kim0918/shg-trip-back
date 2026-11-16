package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Anthropic Message Batches API의 제출·상태 조회·결과 받기. */
@Slf4j
@Component
@Profile("batch")
class AnthropicBatchClient {

    private static final String BATCH_API_URL = "https://api.anthropic.com/v1/messages/batches";
    private static final int MAX_RETRIES = 3;
    /** 결과 JSONL 다운로드 타임아웃(분). 묶음 2000건 기준 여유 있게 잡는다. */
    private static final int RESULTS_DOWNLOAD_TIMEOUT_MINUTES = 10;
    /** 429의 Retry-After 상한(초). */
    private static final long MAX_RETRY_AFTER_SEC = 120;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${anthropic.api-key:${ANTHROPIC_API_KEY:}}")
    private String apiKey;

    /** 묶음 하나의 결과를 기다리는 상한. Batch API는 최대 24시간 걸리고 결과는 29일 보관된다. */
    @Value("${anthropic.batch.max-poll-duration:${batch.enrich.max-poll-duration:PT24H}}")
    private Duration maxPollDuration = Duration.ofHours(24);

    @Value("${anthropic.batch.poll-interval-ms:10000}")
    private long pollIntervalMs = 10_000;

    /** 재시도 대기의 첫 단위. 시도마다 두 배로 늘린다. */
    private long retryBaseMs = 1_000;

    /** @param resultsUrl 결과 파일 URL, 끝나지 않았거나 실패면 null */
    record PollResult(String resultsUrl, boolean timedOut) {
    }

    @Autowired
    AnthropicBatchClient(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                // 결과 파일 URL은 서명된 스토리지로 302 리다이렉트되어, 따라가지 않으면 본문이 비어 온다
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    AnthropicBatchClient(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 5xx/IOException은 MAX_RETRIES회까지 지수 백오프로 재시도하고, 4xx는 재시도하지 않는다.
     *
     * @return 배치 ID (실패 시 null)
     */
    String submit(List<Map<String, Object>> requests) {
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
                .header("x-api-key", apiKey)
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

    /** @param deadlineMs 이 시각(epoch ms)이 지나면 기다리지 않는다. 호출 단계 전체의 끝 시각이다 */
    PollResult poll(String batchId, long deadlineMs) {
        long until = Math.min(System.currentTimeMillis() + maxPollDuration.toMillis(), deadlineMs);

        while (System.currentTimeMillis() < until) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BATCH_API_URL + "/" + batchId))
                        .header("x-api-key", apiKey)
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

    /** 결과 JSONL. 실패면 null. 결과는 29일 보관이라 비용을 낸 뒤 실패해도 다시 받을 수 있다. */
    String download(String resultsUrl) {
        try {
            // 결과는 요청 수만큼의 JSONL이라 청크가 커질수록 길어진다.
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(resultsUrl))
                    .header("x-api-key", apiKey)
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

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
