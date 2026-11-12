package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Batch API 응답 분기(상태 조회·제출·결과 종류)를 HttpClient 목으로 고정한다. */
@SuppressWarnings("unchecked")
class BatchEnrichSchedulerHttpTest {

    private final PlaceRepository repository = mock(PlaceRepository.class);
    private final HttpClient http = mock(HttpClient.class);
    private BatchEnrichScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new BatchEnrichScheduler(repository, new ObjectMapper(), mock(EmbeddingBatchJob.class), http);
        ReflectionTestUtils.setField(scheduler, "anthropicApiKey", "test-key");
        ReflectionTestUtils.setField(scheduler, "enrichModel", "test-model");
        ReflectionTestUtils.setField(scheduler, "chunkSize", 1000);
        ReflectionTestUtils.setField(scheduler, "pollIntervalMs", 1L);
        ReflectionTestUtils.setField(scheduler, "retryBaseMs", 1L);
        ReflectionTestUtils.setField(scheduler, "maxPollDuration", Duration.ofSeconds(5));
    }

    private static HttpResponse<String> response(int status, String body, Map<String, List<String>> headers) {
        HttpResponse<String> r = mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(status);
        when(r.body()).thenReturn(body);
        when(r.headers()).thenReturn(HttpHeaders.of(headers, (a, b) -> true));
        return r;
    }

    private void respond(HttpResponse<String> first, HttpResponse<String>... rest) throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(first, rest);
    }

    @Test
    @DisplayName("상태 조회가 429 아닌 4xx(없는 batchId·키 오류)면 기다리지 않고 바로 실패한다")
    void poll_clientErrorFailsImmediately() throws Exception {
        respond(response(404, "{\"error\":\"not_found\"}", Map.of()));

        BatchEnrichScheduler.PollResult result = scheduler.pollForCompletion("msgbatch_typo");

        assertThat(result.resultsUrl()).isNull();
        assertThat(result.timedOut()).isFalse();
        verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @DisplayName("상태 조회 429·5xx는 기다렸다가 다시 묻고, 끝나면 결과 URL을 준다")
    void poll_retriesTransientThenEnds() throws Exception {
        respond(response(429, "", Map.of("retry-after", List.of("0"))),
                response(503, "", Map.of()),
                response(200, "{\"processing_status\":\"ended\",\"results_url\":\"https://r\"}", Map.of()));

        assertThat(scheduler.pollForCompletion("b1").resultsUrl()).isEqualTo("https://r");
    }

    @Test
    @DisplayName("취소 중인 배치는 시간 초과가 아닌 실패다")
    void poll_canceling() throws Exception {
        respond(response(200, "{\"processing_status\":\"canceling\"}", Map.of()));

        BatchEnrichScheduler.PollResult result = scheduler.pollForCompletion("b1");

        assertThat(result.resultsUrl()).isNull();
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    @DisplayName("보강 단계 끝 시각이 지나면 결과 대기를 시간 초과로 끊는다")
    void poll_stopsAtRunDeadline() throws Exception {
        ReflectionTestUtils.setField(scheduler, "runDeadlineMs", System.currentTimeMillis() - 1);

        BatchEnrichScheduler.PollResult result = scheduler.pollForCompletion("b1");

        assertThat(result.timedOut()).isTrue();
        verify(http, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @DisplayName("제출 429는 다시 보내고, 세 번 모두 실패하면 null이다")
    void submit_retriesOn429() throws Exception {
        respond(response(429, "", Map.of("retry-after", List.of("0"))),
                response(200, "{\"id\":\"msgbatch_1\"}", Map.of()));
        assertThat(scheduler.submitBatch(List.of(Map.of("custom_id", "place_1")))).isEqualTo("msgbatch_1");

        HttpClient always429 = mock(HttpClient.class);
        HttpResponse<String> limited = response(429, "", Map.of("retry-after", List.of("0")));
        when(always429.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(limited);
        BatchEnrichScheduler other = new BatchEnrichScheduler(repository, new ObjectMapper(), mock(EmbeddingBatchJob.class), always429);
        ReflectionTestUtils.setField(other, "anthropicApiKey", "test-key");
        ReflectionTestUtils.setField(other, "retryBaseMs", 1L);

        assertThat(other.submitBatch(List.of(Map.of("custom_id", "place_1")))).isNull();
        verify(always429, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    private Place place(long id) {
        return Place.builder().id(id).name("장소" + id).address("주소")
                .latitude(BigDecimal.ONE).longitude(BigDecimal.ONE).category("Landmarks and Outdoors > Park")
                .region("Jeju").country("KR").savedAt(OffsetDateTime.now()).active(true).build();
    }

    @Test
    @DisplayName("결과 대기 시간 초과는 중단에 세지 않고, 결과 다운로드 실패는 센다")
    void processChunk_mapsWaitAndDownloadFailures() {
        BatchEnrichScheduler spied = spy(scheduler);
        doReturn("b1").when(spied).submitBatch(anyList());
        doReturn(new BatchEnrichScheduler.PollResult(null, true)).when(spied).pollForCompletion("b1");
        assertThat(spied.processChunk(List.of(place(1))).countsTowardAbort()).isFalse();

        doReturn(new BatchEnrichScheduler.PollResult("https://r", false)).when(spied).pollForCompletion("b1");
        doReturn(null).when(spied).downloadResults("https://r");
        assertThat(spied.processChunk(List.of(place(1))).countsTowardAbort()).isTrue();
    }

    @Test
    @DisplayName("요청 오류(invalid_request_error)만 장소 탓이고 일시 오류(overloaded·api_error)는 아니다")
    void requestErrorClassification() throws Exception {
        ObjectMapper om = new ObjectMapper();
        assertThat(BatchEnrichScheduler.isRequestError(om.readTree(
                "{\"type\":\"errored\",\"error\":{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\"}}}"))).isTrue();
        assertThat(BatchEnrichScheduler.isRequestError(om.readTree(
                "{\"type\":\"errored\",\"error\":{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}}"))).isFalse();
        assertThat(BatchEnrichScheduler.isRequestError(om.readTree(
                "{\"type\":\"errored\",\"error\":{\"type\":\"api_error\"}}"))).isFalse();
    }

    @Test
    @DisplayName("성공이 있는 묶음에서도 일시 오류는 실패 횟수에 세지 않는다")
    void transientErrorsNotCounted() {
        String jsonl = String.join("\n",
                "{\"custom_id\":\"place_1\",\"result\":{\"type\":\"succeeded\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"tags\\\":[\\\"공원\\\"]}\"}]}}}",
                "{\"custom_id\":\"place_2\",\"result\":{\"type\":\"errored\",\"error\":{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}}}",
                "{\"custom_id\":\"place_3\",\"result\":{\"type\":\"errored\",\"error\":{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\"}}}}");

        scheduler.applyResults(jsonl, List.of(place(1), place(2), place(3)));

        verify(repository).incrementEnrichAttempts(java.util.Set.of(3L));
    }

    @Test
    @DisplayName("속성 보강(v2) 재반영은 v1 보강이 끝난 행에도 속성이 없으면 반영한다")
    void resume_attributeModeUsesEnrichVersion() {
        ReflectionTestUtils.setField(scheduler, "mode", "v2");
        Place enriched = place(5);
        enriched.enrichWith(List.of("공원"), "설명", List.of("morning"));
        BatchEnrichScheduler spied = spy(scheduler);
        String jsonl = "{\"custom_id\":\"place_5\",\"result\":{\"type\":\"succeeded\",\"message\":{\"content\":[]}}}";
        doReturn(jsonl).when(spied).fetchResults("b1");
        when(repository.findAllById(List.of(5L))).thenReturn(List.of(enriched));

        try {
            spied.resume(List.of("b1"));
        } catch (BatchStageFailed ignored) {
            // 내용 없는 응답이라 반영은 실패한다. 대상에 들어갔는지만 본다
        }

        verify(spied).applyResults(eq(jsonl), argThat((List<Place> l) -> l.size() == 1), eq(false));
    }

    @Test
    @DisplayName("이번 실행 실패가 상한을 넘으면 대상 조회가 바인드 한도에 걸리기 전에 멈춘다")
    void stopsWhenExcludedIdsExceedCap() {
        BatchEnrichScheduler spied = spy(scheduler);
        List<Long> many = LongStream.rangeClosed(1, BatchEnrichScheduler.MAX_EXCLUDED_IDS + 1).boxed().toList();
        doReturn(new BatchEnrichScheduler.ChunkResult(0, many.size(), many, false)).when(spied).processChunk(anyList());
        when(repository.findEnrichTargets(anyString(), anyCollection(), any(Pageable.class))).thenReturn(List.of(place(1)));

        assertThatThrownBy(spied::enrich).isInstanceOf(BatchStageFailed.class);

        verify(spied, times(1)).processChunk(anyList());
    }
}
