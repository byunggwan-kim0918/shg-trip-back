package com.shg.trip.shgtrip.domain.place.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.global.config.OpenAIProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI text-embedding-3-small 기반 임베딩 생성 서비스.
 * Java HttpClient를 사용하여 OpenAI API를 호출한다.
 * 재시도 3회 + 지수 백오프 적용.
 */
@Service
public class OpenAIEmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(OpenAIEmbeddingService.class);

    private static final String EMBEDDINGS_URL = "https://api.openai.com/v1/embeddings";
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 1000;
    private static final int MAX_BATCH_SIZE = 2048;

    private final OpenAIProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAIEmbeddingService(OpenAIProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            log.warn("OPENAI_API_KEY가 설정되지 않았습니다. 임베딩 생성 시 실패합니다. " +
                    ".env 파일에 OPENAI_API_KEY를 설정하세요.");
        }
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("임베딩 생성 텍스트는 비어있을 수 없습니다.");
        }
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new OpenAIApiException("OPENAI_API_KEY가 설정되지 않았습니다. .env 파일을 확인하세요.");
        }

        List<float[]> results = callEmbeddingsApi(List.of(text));
        return results.getFirst();
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("임베딩 생성 텍스트 목록은 비어있을 수 없습니다.");
        }

        List<float[]> allResults = new ArrayList<>();

        // OpenAI API는 최대 2048개 per request, 초과 시 분할
        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            List<String> chunk = texts.subList(i, Math.min(i + MAX_BATCH_SIZE, texts.size()));
            List<float[]> chunkResults = callEmbeddingsApi(chunk);
            allResults.addAll(chunkResults);
        }

        return allResults;
    }

    /**
     * OpenAI Embeddings API를 호출한다. 재시도 3회 + 지수 백오프 적용.
     */
    private List<float[]> callEmbeddingsApi(List<String> inputs) {
        String requestBody = buildRequestBody(inputs);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(EMBEDDINGS_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.apiKey())
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        IOException lastException = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return parseEmbeddingsResponse(response.body(), inputs.size());
                }

                if (isServerError(response.statusCode())) {
                    log.warn("OpenAI API 서버 에러 (HTTP {}), 재시도 {}/{}",
                            response.statusCode(), attempt + 1, MAX_RETRIES);
                    lastException = new IOException(
                            "OpenAI API 서버 에러: HTTP " + response.statusCode());
                    sleepWithBackoff(attempt);
                    continue;
                }

                // 4xx 에러 (재시도 불필요)
                throw new OpenAIApiException(
                        "OpenAI API 호출 실패 (HTTP " + response.statusCode() + "): " + response.body());

            } catch (IOException e) {
                log.warn("OpenAI API 호출 IO 에러, 재시도 {}/{}: {}",
                        attempt + 1, MAX_RETRIES, e.getMessage());
                lastException = e;
                sleepWithBackoff(attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OpenAIApiException("OpenAI API 호출 중 인터럽트 발생", e);
            }
        }

        throw new OpenAIApiException(
                "OpenAI API 호출 실패: " + MAX_RETRIES + "회 재시도 후에도 실패", lastException);
    }

    private String buildRequestBody(List<String> inputs) {
        try {
            Object input = inputs.size() == 1 ? inputs.getFirst() : inputs;
            Map<String, Object> body = Map.of(
                    "input", input,
                    "model", properties.embeddingModel(),
                    "dimensions", properties.embeddingDimensions()
            );
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new OpenAIApiException("요청 본문 직렬화 실패", e);
        }
    }

    private List<float[]> parseEmbeddingsResponse(String responseBody, int expectedCount) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode dataArray = root.get("data");

            if (dataArray == null || !dataArray.isArray()) {
                throw new OpenAIApiException("OpenAI 응답에 data 배열이 없습니다: " + responseBody);
            }

            // index 순서대로 정렬하여 반환
            List<float[]> results = new ArrayList<>(expectedCount);
            for (int i = 0; i < expectedCount; i++) {
                results.add(null);
            }

            for (JsonNode item : dataArray) {
                int index = item.get("index").asInt();
                JsonNode embeddingNode = item.get("embedding");
                float[] embedding = new float[embeddingNode.size()];
                for (int i = 0; i < embeddingNode.size(); i++) {
                    embedding[i] = (float) embeddingNode.get(i).asDouble();
                }
                results.set(index, embedding);
            }

            // null 검증
            for (int i = 0; i < results.size(); i++) {
                if (results.get(i) == null) {
                    throw new OpenAIApiException("인덱스 " + i + "에 대한 임베딩이 누락되었습니다.");
                }
            }

            return results;
        } catch (OpenAIApiException e) {
            throw e;
        } catch (Exception e) {
            throw new OpenAIApiException("OpenAI 응답 파싱 실패", e);
        }
    }

    private boolean isServerError(int statusCode) {
        return statusCode >= 500 && statusCode < 600;
    }

    private void sleepWithBackoff(int attempt) {
        try {
            long sleepMs = INITIAL_BACKOFF_MS * (1L << attempt);
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
