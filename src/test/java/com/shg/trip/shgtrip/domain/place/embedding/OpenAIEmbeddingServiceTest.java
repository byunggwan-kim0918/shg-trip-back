package com.shg.trip.shgtrip.domain.place.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.global.config.OpenAIProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

class OpenAIEmbeddingServiceTest {

    private OpenAIEmbeddingService service;
    private HttpClient mockHttpClient;
    private ObjectMapper objectMapper;
    private OpenAIProperties properties;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        properties = new OpenAIProperties("test-api-key", "text-embedding-3-small", 1536);
        objectMapper = new ObjectMapper();
        mockHttpClient = mock(HttpClient.class);

        // Use reflection to inject mock HttpClient
        service = new OpenAIEmbeddingService(properties, objectMapper);
        var field = OpenAIEmbeddingService.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(service, mockHttpClient);
    }

    @Nested
    @DisplayName("embed - 단일 텍스트 임베딩")
    class EmbedTests {

        @Test
        @DisplayName("정상적으로 임베딩 벡터를 반환한다")
        @SuppressWarnings("unchecked")
        void embed_success() throws Exception {
            String responseBody = """
                {
                    "data": [
                        {"embedding": [0.1, 0.2, 0.3], "index": 0}
                    ],
                    "model": "text-embedding-3-small",
                    "usage": {"prompt_tokens": 5, "total_tokens": 5}
                }
                """;

            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            given(mockResponse.statusCode()).willReturn(200);
            given(mockResponse.body()).willReturn(responseBody);
            given(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .willReturn(mockResponse);

            float[] result = service.embed("테스트 텍스트");

            assertThat(result).hasSize(3);
            assertThat(result[0]).isEqualTo(0.1f);
            assertThat(result[1]).isEqualTo(0.2f);
            assertThat(result[2]).isEqualTo(0.3f);
        }

        @Test
        @DisplayName("null 텍스트에 대해 IllegalArgumentException을 던진다")
        void embed_nullText_throwsException() {
            assertThatThrownBy(() -> service.embed(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("비어있을 수 없습니다");
        }

        @Test
        @DisplayName("빈 텍스트에 대해 IllegalArgumentException을 던진다")
        void embed_emptyText_throwsException() {
            assertThatThrownBy(() -> service.embed("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("비어있을 수 없습니다");
        }
    }

    @Nested
    @DisplayName("embedBatch - 배치 임베딩")
    class EmbedBatchTests {

        @Test
        @DisplayName("다수 텍스트에 대해 순서를 유지하며 임베딩을 반환한다")
        @SuppressWarnings("unchecked")
        void embedBatch_success() throws Exception {
            String responseBody = """
                {
                    "data": [
                        {"embedding": [0.1, 0.2], "index": 0},
                        {"embedding": [0.3, 0.4], "index": 1},
                        {"embedding": [0.5, 0.6], "index": 2}
                    ]
                }
                """;

            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            given(mockResponse.statusCode()).willReturn(200);
            given(mockResponse.body()).willReturn(responseBody);
            given(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .willReturn(mockResponse);

            List<float[]> results = service.embedBatch(List.of("텍스트1", "텍스트2", "텍스트3"));

            assertThat(results).hasSize(3);
            assertThat(results.get(0)).containsExactly(0.1f, 0.2f);
            assertThat(results.get(1)).containsExactly(0.3f, 0.4f);
            assertThat(results.get(2)).containsExactly(0.5f, 0.6f);
        }

        @Test
        @DisplayName("빈 목록에 대해 IllegalArgumentException을 던진다")
        void embedBatch_emptyList_throwsException() {
            assertThatThrownBy(() -> service.embedBatch(List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("비어있을 수 없습니다");
        }

        @Test
        @DisplayName("null 목록에 대해 IllegalArgumentException을 던진다")
        void embedBatch_nullList_throwsException() {
            assertThatThrownBy(() -> service.embedBatch(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("비어있을 수 없습니다");
        }
    }

    @Nested
    @DisplayName("재시도 로직")
    class RetryTests {

        @Test
        @DisplayName("5xx 에러 시 3회 재시도 후 실패한다")
        @SuppressWarnings("unchecked")
        void retry_serverError_exhaustsRetries() throws Exception {
            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            given(mockResponse.statusCode()).willReturn(500);
            given(mockResponse.body()).willReturn("Internal Server Error");
            given(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .willReturn(mockResponse);

            assertThatThrownBy(() -> service.embed("테스트"))
                    .isInstanceOf(OpenAIApiException.class)
                    .hasMessageContaining("3회 재시도 후에도 실패");

            verify(mockHttpClient, times(3)).send(any(), any());
        }

        @Test
        @DisplayName("IOException 발생 시 3회 재시도 후 실패한다")
        @SuppressWarnings("unchecked")
        void retry_ioException_exhaustsRetries() throws Exception {
            given(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .willThrow(new IOException("Connection refused"));

            assertThatThrownBy(() -> service.embed("테스트"))
                    .isInstanceOf(OpenAIApiException.class)
                    .hasMessageContaining("3회 재시도 후에도 실패");

            verify(mockHttpClient, times(3)).send(any(), any());
        }

        @Test
        @DisplayName("5xx 후 성공하면 결과를 반환한다")
        @SuppressWarnings("unchecked")
        void retry_eventualSuccess() throws Exception {
            HttpResponse<String> errorResponse = mock(HttpResponse.class);
            given(errorResponse.statusCode()).willReturn(503);

            String successBody = """
                {"data": [{"embedding": [1.0, 2.0], "index": 0}]}
                """;
            HttpResponse<String> successResponse = mock(HttpResponse.class);
            given(successResponse.statusCode()).willReturn(200);
            given(successResponse.body()).willReturn(successBody);

            given(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .willReturn(errorResponse)
                    .willReturn(successResponse);

            float[] result = service.embed("테스트");

            assertThat(result).containsExactly(1.0f, 2.0f);
            verify(mockHttpClient, times(2)).send(any(), any());
        }

        @Test
        @DisplayName("4xx 에러 시 재시도 없이 즉시 실패한다")
        @SuppressWarnings("unchecked")
        void noRetry_clientError() throws Exception {
            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            given(mockResponse.statusCode()).willReturn(401);
            given(mockResponse.body()).willReturn("Unauthorized");
            given(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .willReturn(mockResponse);

            assertThatThrownBy(() -> service.embed("테스트"))
                    .isInstanceOf(OpenAIApiException.class)
                    .hasMessageContaining("HTTP 401");

            verify(mockHttpClient, times(1)).send(any(), any());
        }
    }

    @Nested
    @DisplayName("응답 파싱")
    class ParseResponseTests {

        @Test
        @DisplayName("index 순서가 뒤섞여도 올바른 순서로 반환한다")
        @SuppressWarnings("unchecked")
        void parseResponse_outOfOrderIndices() throws Exception {
            String responseBody = """
                {
                    "data": [
                        {"embedding": [0.5, 0.6], "index": 1},
                        {"embedding": [0.1, 0.2], "index": 0}
                    ]
                }
                """;

            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            given(mockResponse.statusCode()).willReturn(200);
            given(mockResponse.body()).willReturn(responseBody);
            given(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .willReturn(mockResponse);

            List<float[]> results = service.embedBatch(List.of("첫번째", "두번째"));

            assertThat(results.get(0)).containsExactly(0.1f, 0.2f);
            assertThat(results.get(1)).containsExactly(0.5f, 0.6f);
        }
    }
}
