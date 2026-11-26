package com.shg.trip.shgtrip.domain.place.enrich;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.errors.BadRequestException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.batch.PlaceEmbedder;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotedPlaceEnricherTest {

    private static final long ID = 3050028L;
    private static final String OSU_RESPONSE = """
            {"tags":["상점가","전자상가","먹거리"],"description":"나고야 중구의 아케이드 상점가로 전자상가와 노포 먹거리가 섞여 있다.",
             "place_role":"SHOPPING","recommended_time_slots":["afternoon"],"recommended_duration_minutes":90,
             "traveler_fame":2,"name_ko":"오스 상점가","admission_fee_local":0}
            """;

    private final PlaceEnrichRequests requests = mock(PlaceEnrichRequests.class);
    private final PlaceEnrichBudget budget = mock(PlaceEnrichBudget.class);
    private final PlaceRepository placeRepository = mock(PlaceRepository.class);
    private final PlaceEmbedder embedder = mock(PlaceEmbedder.class);
    private final AnthropicClient client = mock(AnthropicClient.class, RETURNS_DEEP_STUBS);
    private final List<Runnable> queued = new ArrayList<>();
    private final Executor manualExecutor = queued::add;

    private PromotedPlaceEnricher enricher;

    @BeforeEach
    void setUp() {
        enricher = enricher(true, manualExecutor);
        when(client.withOptions(any())).thenReturn(client);
        when(requests.add(ID)).thenReturn(true);
        when(budget.tryAcquire()).thenReturn(true);
        when(placeRepository.writeEnrichment(any())).thenReturn(1);
        when(embedder.embed(anyList())).thenReturn(new PlaceEmbedder.Result(1, 0, List.of(), false));
    }

    private PromotedPlaceEnricher enricher(boolean enabled, Executor executor) {
        return new PromotedPlaceEnricher(requests, budget, placeRepository, embedder, client, new ObjectMapper(),
                executor, enabled, "claude-haiku-5-5");
    }

    private static Place osu() {
        return Place.builder().id(ID).name("大須商店街").address("愛知県名古屋市中区大須")
                .latitude(BigDecimal.valueOf(35.1595)).longitude(BigDecimal.valueOf(136.9030))
                .category("Retail > Shopping Mall").region("Nagoya").country("JP").source("overture").active(true)
                .build();
    }

    private void haikuReturns(String text) {
        haikuReturns(text, 0);
    }

    private void haikuReturns(String text, long delayMs) {
        Message message = mock(Message.class);
        ContentBlock block = mock(ContentBlock.class);
        TextBlock textBlock = mock(TextBlock.class);
        when(block.isText()).thenReturn(true);
        when(block.asText()).thenReturn(textBlock);
        when(textBlock.text()).thenReturn(text);
        when(message.content()).thenReturn(List.of(block));
        when(client.messages().create(any(MessageCreateParams.class), any(RequestOptions.class))).thenAnswer(inv -> {
            if (delayMs > 0) Thread.sleep(delayMs);
            return message;
        });
    }

    private void claimed(int attempt, Place place) {
        when(requests.claim(eq(ID), any(), any())).thenReturn(Optional.of(attempt));
        when(placeRepository.findById(ID)).thenReturn(Optional.ofNullable(place));
    }

    @Test
    @DisplayName("한국어 이름이 없는 승격 장소를 배치 규칙대로 보강하고 다시 임베딩한 뒤 요청을 지운다")
    void enrichesWithBatchRules() {
        Place place = osu();
        claimed(1, place);
        haikuReturns(OSU_RESPONSE);

        assertThat(enricher.process(ID)).isEqualTo(PromotedPlaceEnricher.Outcome.ENRICHED);

        assertThat(place.getNameKo()).isEqualTo("오스 상점가");
        assertThat(place.getDescription()).startsWith("나고야 중구의 아케이드 상점가");
        assertThat(place.getTags()).contains("상점가");
        assertThat(place.getPlaceRole()).isEqualTo("SHOPPING");
        assertThat(place.getEnrichedAt()).isNotNull();
        verify(placeRepository).writeEnrichment(place);
        verify(embedder).embed(List.of(place));
        verify(requests).remove(ID);
        ArgumentCaptor<MessageCreateParams> params = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(client.messages()).create(params.capture(), any(RequestOptions.class));
        assertThat(params.getValue().model().asString()).isEqualTo("claude-haiku-5-5");
        assertThat(params.getValue().maxTokens()).isEqualTo(1024);
    }

    @Test
    @DisplayName("다른 작업자가 맡았거나 때가 아닌 요청은 Haiku를 부르지 않는다")
    void notClaimedSkipsCall() {
        when(requests.claim(eq(ID), any(), any())).thenReturn(Optional.empty());

        assertThat(enricher.process(ID)).isEqualTo(PromotedPlaceEnricher.Outcome.NOT_CLAIMED);

        verify(client, never()).messages();
        verify(placeRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("이미 보강된 행은 부르지 않고 요청만 지운다")
    void alreadyEnrichedRemovesRequest() {
        Place place = osu();
        place.enrichWith(List.of("상점가"), "소개", List.of());
        claimed(1, place);

        assertThat(enricher.process(ID)).isEqualTo(PromotedPlaceEnricher.Outcome.NOT_NEEDED);

        verify(requests).remove(ID);
        verify(client, never()).messages();
    }

    @Test
    @DisplayName("호출 상한을 넘으면 부르지 않고 시도 횟수 없이 미룬다")
    void budgetExhaustedPostpones() {
        claimed(1, osu());
        when(budget.tryAcquire()).thenReturn(false);

        assertThat(enricher.process(ID)).isEqualTo(PromotedPlaceEnricher.Outcome.BUDGET_EXHAUSTED);

        verify(requests).postpone(ID, PromotedPlaceEnricher.BUDGET_RETRY);
        verify(client, never()).messages();
        verify(requests, never()).remove(anyLong());
    }

    @Test
    @DisplayName("호출이 실패하면 장소 실패로 세지 않고 1분 뒤 다시 맡는다")
    void callFailureRetriesWithoutCountingPlaceFault() {
        claimed(1, osu());
        when(client.messages().create(any(MessageCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new RuntimeException("overloaded"));

        assertThat(enricher.process(ID)).isEqualTo(PromotedPlaceEnricher.Outcome.CALL_FAILED);

        verify(requests).release(eq(ID), any());
        verify(requests, never()).remove(anyLong());
        verify(placeRepository, never()).incrementEnrichAttempts(any());
        verify(placeRepository, never()).writeEnrichment(any());
    }

    @Test
    @DisplayName("일시 오류는 몇 번 실패해도 요청을 지우지 않고, 맡을 때 정한 백오프 뒤 다시 맡는다")
    void transientFailuresNeverDropRequest() {
        claimed(10, osu());
        when(client.messages().create(any(MessageCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new RuntimeException("timeout"));

        enricher.process(ID);

        verify(requests).claim(ID, PromotedPlaceEnricher.LEASE, PromotedPlaceEnricher.BACKOFF);
        verify(requests).release(eq(ID), any());
        verify(requests, never()).remove(anyLong());
    }

    @Test
    @DisplayName("규칙에 맞지 않는 응답은 배치와 같이 장소 실패로 세고 저장하지 않는다")
    void unreadableResponseCountsPlaceFault() {
        claimed(2, osu());
        haikuReturns("{\"tags\": [], \"description\": null}");

        assertThat(enricher.process(ID)).isEqualTo(PromotedPlaceEnricher.Outcome.UNREADABLE);

        verify(placeRepository).incrementEnrichAttempts(List.of(ID));
        verify(requests).release(eq(ID), any());
        verify(placeRepository, never()).writeEnrichment(any());
    }

    @Test
    @DisplayName("기다리는 사이 다른 경로가 먼저 보강하면 덮어쓰지 않고 임베딩도 하지 않는다")
    void concurrentWriteIsNotOverwritten() {
        claimed(1, osu());
        haikuReturns(OSU_RESPONSE);
        when(placeRepository.writeEnrichment(any())).thenReturn(0);

        assertThat(enricher.process(ID)).isEqualTo(PromotedPlaceEnricher.Outcome.NOT_NEEDED);

        verify(embedder, never()).embed(anyList());
        verify(requests).remove(ID);
    }

    @Test
    @DisplayName("재임베딩에 실패하면 임베딩을 비워 임베딩 배치가 다시 만든다")
    void reembedFailureResetsEmbedding() {
        claimed(1, osu());
        haikuReturns(OSU_RESPONSE);
        when(embedder.embed(anyList())).thenReturn(new PlaceEmbedder.Result(0, 1, List.of(ID), true));

        assertThat(enricher.process(ID)).isEqualTo(PromotedPlaceEnricher.Outcome.ENRICHED);

        verify(placeRepository).resetEmbeddings(List.of(ID));
    }

    @Test
    @DisplayName("같은 장소를 진행 중에 다시 요청하면 한 번만 실행기에 넣는다")
    void duplicateStartRunsOnce() {
        enricher.start(ID);
        enricher.start(ID);

        assertThat(queued).hasSize(1);
    }

    @Test
    @DisplayName("꺼져 있으면 요청을 남기지도 보강을 시작하지도 않는다")
    void disabledDoesNothing() {
        PromotedPlaceEnricher off = enricher(false, manualExecutor);

        off.request(ID);

        verify(requests, never()).add(anyLong());
        assertThat(queued).isEmpty();
        assertThat(off.pendingAmong(List.of(ID))).isEmpty();
    }

    @Test
    @DisplayName("트랜잭션 밖 요청은 바로 시작하고, 이야기 단계는 보강을 마친 장소만 받는다")
    void awaitReturnsEnrichedOnly() {
        claimed(1, osu());
        // 대기를 시작하기 전에 작업이 끝나 inFlight에서 빠지지 않게 호출을 늦춘다.
        haikuReturns(OSU_RESPONSE, 300);
        enricher.request(ID);
        assertThat(queued).hasSize(1);

        Thread worker = new Thread(queued.get(0));
        worker.start();
        Set<Long> enriched = enricher.awaitEnriched(List.of(ID, 99L), Duration.ofSeconds(5));

        assertThat(enriched).containsExactly(ID);
        verify(client.messages(), times(1)).create(any(MessageCreateParams.class), any(RequestOptions.class));
    }

    @Test
    @DisplayName("보강이 대기 시간 안에 끝나지 않으면 기다리지 않은 것으로 돌려준다")
    void awaitTimesOut() {
        enricher.start(ID);

        long started = System.nanoTime();
        Set<Long> enriched = enricher.awaitEnriched(List.of(ID), Duration.ofMillis(200));

        assertThat(enriched).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("주기 작업은 맡을 수 있는 요청을 모두 시작한다")
    void sweepStartsDueRequests() {
        when(requests.due(any(Integer.class))).thenReturn(List.of(1L, 2L, 3L));

        enricher.sweep();

        assertThat(queued).hasSize(3);
    }

    @Test
    @DisplayName("보강 응답이 상한만큼 실패한 행은 다시 묻지 않는다")
    void exhaustedPlaceIsNotRetried() {
        Place place = osu();
        org.springframework.test.util.ReflectionTestUtils.setField(place, "enrichAttempts",
                (short) com.shg.trip.shgtrip.domain.place.repository.PlaceRepository.MAX_ENRICH_ATTEMPTS);
        claimed(1, place);

        assertThat(enricher.process(ID)).isEqualTo(PromotedPlaceEnricher.Outcome.NOT_NEEDED);
        verify(client, never()).messages();
    }

    @Test
    @DisplayName("보강 시각이 있는 행은 다시 묻지 않는다")
    void enrichedAtIsRespected() {
        Place place = osu();
        org.springframework.test.util.ReflectionTestUtils.setField(place, "enrichedAt", OffsetDateTime.now());
        claimed(1, place);

        assertThat(enricher.process(ID)).isEqualTo(PromotedPlaceEnricher.Outcome.NOT_NEEDED);
    }

    @Test
    @DisplayName("이미 요청이 있거나 보강이 필요 없는 장소는 다시 시작하지 않는다")
    void existingRequestIsNotStartedAgain() {
        when(requests.add(ID)).thenReturn(false);

        enricher.request(ID);

        assertThat(queued).isEmpty();
    }

    @Test
    @DisplayName("호출 뒤 저장이 예외로 끝나면 장소 실패로 세어 끝없이 다시 사지 않는다")
    void exceptionAfterCallBacksOff() {
        claimed(1, osu());
        haikuReturns(OSU_RESPONSE);
        when(placeRepository.writeEnrichment(any())).thenThrow(new IllegalStateException("db down"));

        assertThat(enricher.process(ID)).isEqualTo(PromotedPlaceEnricher.Outcome.FAILED);

        // 응답을 산 뒤의 실패는 장소 실패로 세어, 배치 상한에 닿으면 다시 사지 않는다.
        verify(placeRepository).incrementEnrichAttempts(List.of(ID));
        verify(requests).release(eq(ID), any());
        verify(requests, never()).remove(anyLong());
    }

    @Test
    @DisplayName("400 거절은 배치와 같이 장소 탓 실패로 센다")
    void badRequestCountsPlaceFault() {
        claimed(1, osu());
        when(client.messages().create(any(MessageCreateParams.class), any(RequestOptions.class)))
                .thenThrow(mock(BadRequestException.class));

        assertThat(enricher.process(ID)).isEqualTo(PromotedPlaceEnricher.Outcome.REJECTED);

        verify(placeRepository).incrementEnrichAttempts(List.of(ID));
        verify(requests).release(eq(ID), any());
    }

    @Test
    @DisplayName("백오프는 1분에서 시작해 마지막 값(6시간)으로 끝없이 이어진다")
    void backoffStartsWithinFiveMinutesAndNeverEnds() {
        assertThat(PromotedPlaceEnricher.BACKOFF.get(0)).isLessThanOrEqualTo(Duration.ofMinutes(1));
        assertThat(PromotedPlaceEnricher.BACKOFF.get(PromotedPlaceEnricher.BACKOFF.size() - 1))
                .isEqualTo(Duration.ofHours(6));
    }

    @Test
    @DisplayName("SDK 재시도를 끈 클라이언트로 부른다")
    void callsWithoutSdkRetries() {
        claimed(1, osu());
        haikuReturns(OSU_RESPONSE);
        ArgumentCaptor<java.util.function.Consumer<com.anthropic.core.ClientOptions.Builder>> options =
                ArgumentCaptor.forClass(java.util.function.Consumer.class);

        enricher.process(ID);

        verify(client).withOptions(options.capture());
        com.anthropic.core.ClientOptions.Builder builder = mock(com.anthropic.core.ClientOptions.Builder.class);
        options.getValue().accept(builder);
        verify(builder).maxRetries(0);
    }
}
