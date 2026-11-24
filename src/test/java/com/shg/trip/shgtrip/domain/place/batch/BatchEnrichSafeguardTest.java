package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 같은 요청 묶기·옛 결과 거부·실행 범위·응답 검증. */
@ExtendWith(MockitoExtension.class)
class BatchEnrichSafeguardTest {

    private static final String CAFE = "Dining and Drinking > Cafe, Coffee, and Tea House > Coffee Shop";
    private static final String PARK = "Landmarks and Outdoors > Park";

    @Mock
    private PlaceRepository placeRepository;

    @Mock
    private EmbeddingBatchJob embeddingBatchJob;

    private BatchEnrichScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = scheduler(mock(AnthropicBatchClient.class));
        lenient().when(placeRepository.writeEnrichment(any(Place.class))).thenReturn(1);
    }

    private BatchEnrichScheduler scheduler(AnthropicBatchClient client) {
        BatchEnrichScheduler scheduler = new BatchEnrichScheduler(placeRepository, new ObjectMapper(), embeddingBatchJob,
                client);
        ReflectionTestUtils.setField(scheduler, "maxDuration", java.time.Duration.ofHours(1));
        ReflectionTestUtils.setField(scheduler, "chunkSize", 1000);
        ReflectionTestUtils.setField(scheduler, "parallelChunks", 1);
        ReflectionTestUtils.setField(scheduler, "anthropicApiKey", "test-api-key");
        ReflectionTestUtils.setField(scheduler, "enrichModel", "claude-haiku-5-5");
        ReflectionTestUtils.setField(scheduler, "rewriteDescriptionsAfterV1", false);
        return scheduler;
    }

    private static Place place(long id, String name, String category, String region) {
        return Place.builder().id(id).name(name).address("주소 " + id).latitude(BigDecimal.ONE)
                .longitude(BigDecimal.ONE).category(category).region(region).country("KR").active(true).build();
    }

    private static String textLine(Place p, String json) {
        return """
                {"custom_id":"%s","result":{"type":"succeeded","message":{"content":[{"type":"text","text":%s}]}}}"""
                .formatted(BatchEnrichScheduler.customId(p, BatchEnrichScheduler.Mode.V1), quote(json));
    }

    private static String quote(String s) {
        try {
            return new ObjectMapper().writeValueAsString(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("요청 본문이 같은 장소(체인 지점)는 요청 하나로 묻고, 결과를 모두에 따로 쓴다")
    void identicalRequestsAreAskedOnce() {
        Place a = place(1L, "메가MGC커피", CAFE, "Seoul");
        Place b = place(2L, "메가MGC커피", CAFE, "Seoul");
        Place other = place(3L, "테라로사", CAFE, "Seoul");

        List<Map<String, Object>> requests = scheduler.buildBatchRequests(List.of(a, b, other));
        BatchEnrichScheduler.ChunkResult result = scheduler.applyResults(String.join("\n",
                textLine(a, "{\"tags\":[\"커피\"],\"recommended_time_slots\":[\"afternoon\"]}"),
                textLine(other, "{\"tags\":[\"커피\"],\"recommended_time_slots\":[\"afternoon\"]}")),
                List.of(a, b, other));

        assertThat(requests).hasSize(2);
        assertThat(result.success()).isEqualTo(3);
        verify(placeRepository).writeEnrichment(a);
        verify(placeRepository).writeEnrichment(b);
        assertThat(b.getTags()).containsExactly("카페", "커피");
    }

    @Test
    @DisplayName("대표 요청이 실패하면 묶인 장소도 함께 실패로 세고, 응답 불량은 모두 실패 횟수에 넣는다")
    void groupedFailureCountsForEveryMember() {
        Place a = place(1L, "메가MGC커피", CAFE, "Seoul");
        Place b = place(2L, "메가MGC커피", CAFE, "Seoul");
        Place ok = place(3L, "테라로사", CAFE, "Seoul");
        String broken = """
                {"custom_id":"%s","result":{"type":"succeeded","message":{"content":[{"type":"text","text":"JSON 아님"}]}}}"""
                .formatted(BatchEnrichScheduler.customId(a, BatchEnrichScheduler.Mode.V1));

        BatchEnrichScheduler.ChunkResult result = scheduler.applyResults(
                String.join("\n", broken, textLine(ok, "{\"tags\":[\"커피\"]}")), List.of(a, b, ok));

        assertThat(result.failedIds()).containsExactly(1L, 2L);
        verify(placeRepository).incrementEnrichAttempts(java.util.Set.of(1L, 2L));
    }

    @Test
    @DisplayName("묶음 나누기는 요청 수로 센다. 같은 요청으로 묶인 장소는 한 묶음에 들어간다")
    void chunksCountRequestsAndKeepGroupsTogether() {
        ReflectionTestUtils.setField(scheduler, "chunkSize", 2);
        List<Place> places = List.of(place(1L, "체인", CAFE, "Seoul"), place(2L, "단독1", CAFE, "Seoul"),
                place(3L, "체인", CAFE, "Seoul"), place(4L, "단독2", CAFE, "Seoul"), place(5L, "체인", CAFE, "Seoul"));

        List<List<Place>> chunks = scheduler.splitIntoChunks(places);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).extracting(Place::getId).containsExactly(1L, 3L, 5L, 2L);
        assertThat(chunks.get(1)).extracting(Place::getId).containsExactly(4L);
    }

    @Test
    @DisplayName("결과를 기다리는 사이 보강됐거나 입력이 바뀐 행(쓰기 0건)은 건너뜀이라 실패·재임베딩·실패 횟수·중단 판정에 넣지 않는다")
    void conditionalWriteMissIsSkipped() {
        Place p = place(1L, "새별오름", PARK, "Jeju");
        when(placeRepository.writeEnrichment(p)).thenReturn(0);

        BatchEnrichScheduler.ChunkResult result = scheduler.applyResults(textLine(p,
                "{\"tags\":[\"오름\"],\"place_role\":\"SIGHT\"}"), List.of(p));

        assertThat(result.success()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.countsTowardAbort()).isFalse();
        verify(embeddingBatchJob, never()).reembed(anyList());
        verify(placeRepository, never()).incrementEnrichAttempts(anyCollection());
    }

    @Test
    @DisplayName("재반영: 요청 뒤 이름이 바뀐 장소에는 옛 이름으로 받은 v1 결과를 쓰지 않는다")
    void resumeRejectsResultForChangedInput() {
        Place requested = place(7L, "옛 이름", PARK, "Jeju");
        String line = textLine(requested, "{\"tags\":[\"공원\"],\"place_role\":\"SIGHT\"}");
        Place renamed = place(7L, "새 이름", PARK, "Jeju");
        BatchEnrichScheduler spied = spy(scheduler);
        doReturn(line).when(spied).fetchResults("b1");
        when(placeRepository.findAllById(List.of(7L))).thenReturn(List.of(renamed));

        spied.resume(List.of("b1"));

        verify(placeRepository, never()).writeEnrichment(any());
    }

    @Test
    @DisplayName("관광지 응답의 시간대는 허용 값만, 태그는 #·빈 값·중복을 떼고 10개까지 받는다")
    void attractionOutputIsNormalized() {
        Place p = place(1L, "사려니숲길", PARK, "Jeju");
        String content = """
                {"tags":["#숲길"," 숲길 ","","산책","1","2","3","4","5","6","7","8","9"],
                 "recommended_time_slots":["morning","NOT_VISITABLE","Afternoon"],"place_role":"SIGHT"}""";

        assertThat(scheduler.applyEnrichment(p, content)).isTrue();
        assertThat(p.getTags()).containsExactly("숲길", "산책", "1", "2", "3", "4", "5", "6", "7", "8");
        assertThat(p.getRecommendedTimeSlots()).containsExactly("morning", "afternoon");
    }

    @Test
    @DisplayName("관광지 응답에 태그·설명·역할이 모두 없으면 보강 완료로 찍지 않고 실패로 남긴다")
    void emptyAttractionOutputIsRejected() {
        Place p = place(1L, "어딘가", PARK, "Jeju");

        assertThat(scheduler.applyEnrichment(p, "{\"tags\":[],\"recommended_time_slots\":[\"morning\"]}")).isFalse();
        assertThat(p.getEnrichedAt()).isNull();
    }

    @Test
    @DisplayName("dry-run은 견적만 남기고 요청을 보내지 않는다")
    void dryRunSubmitsNothing() {
        ReflectionTestUtils.setField(scheduler, "dryRun", true);
        when(placeRepository.countEnrichTargetsByKind(anyBoolean(), anyList(), anyString()))
                .thenReturn(List.<Object[]>of(new Object[]{"KR", 0, 10L}));
        BatchEnrichScheduler spied = spy(scheduler);

        spied.enrich();

        verify(spied, never()).processChunk(anyList());
        verify(placeRepository, never()).findEnrichTargets(anyString(), anyCollection(), any(Pageable.class));
    }

    @Test
    @DisplayName("패스당 요청 상한에 닿으면 남은 장소를 다음 실행으로 넘긴다")
    void requestCapStopsTheRun() {
        AnthropicBatchClient client = mock(AnthropicBatchClient.class);
        BatchEnrichScheduler capped = scheduler(client);
        ReflectionTestUtils.setField(capped, "maxRequests", 3L);
        ReflectionTestUtils.setField(capped, "chunkSize", 2);
        AtomicLong nextId = new AtomicLong();
        when(placeRepository.findEnrichTargets(anyString(), anyCollection(), any(Pageable.class)))
                .thenAnswer(inv -> {
                    Pageable page = inv.getArgument(2);
                    return java.util.stream.LongStream.range(0, page.getPageSize())
                            .mapToObj(i -> place(nextId.incrementAndGet(), "장소" + nextId.get(), PARK, "Jeju")).toList();
                });
        when(client.submit(anyList())).thenReturn("b");
        when(client.poll(eq("b"), anyLong())).thenReturn(new AnthropicBatchClient.PollResult("u", false));
        when(client.download("u")).thenReturn("");

        org.assertj.core.api.Assertions.assertThatThrownBy(capped::enrich).isInstanceOf(BatchStageFailed.class);

        verify(client, atMost(2)).submit(anyList());
        // 상한까지 3곳을 읽고, 남은 대상이 있는지 한 곳을 더 본다
        assertThat(nextId.get()).isEqualTo(4);
    }

    @Test
    @DisplayName("국가를 정하면 그 나라 활성 장소의 지역으로 좁히고, 지역 목록과 겹치는 곳만 남긴다")
    void countriesNarrowToRegions() {
        ReflectionTestUtils.setField(scheduler, "enrichCountries", "jp");
        ReflectionTestUtils.setField(scheduler, "enrichRegions", "Kyoto,Jeju");
        when(placeRepository.findActiveRegionsInCountries(List.of("JP"))).thenReturn(List.of("Kyoto", "Osaka"));
        when(placeRepository.findEnrichTargetsInRegions(anyList(), anyString(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of());

        scheduler.enrich();

        verify(placeRepository).findEnrichTargetsInRegions(eq(List.of("Kyoto")), anyString(), anyCollection(),
                any(Pageable.class));
    }

    @Test
    @DisplayName("국가에 맞는 지역이 없으면 전체로 넓히지 않고 단계 실패로 끝낸다(국가 코드 오타)")
    void countriesWithoutRegionsFail() {
        ReflectionTestUtils.setField(scheduler, "enrichCountries", "Japan");
        when(placeRepository.findActiveRegionsInCountries(List.of("JAPAN"))).thenReturn(List.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(scheduler::enrich).isInstanceOf(BatchStageFailed.class);

        verify(placeRepository, never()).findEnrichTargets(anyString(), anyCollection(), any(Pageable.class));
    }

    @Test
    @DisplayName("견적 조회가 실패해도 보강은 그대로 돈다")
    void estimateFailureDoesNotBlock() {
        when(placeRepository.countEnrichTargetsByKind(anyBoolean(), anyList(), anyString()))
                .thenThrow(new IllegalStateException("bind"));
        when(placeRepository.findEnrichTargets(anyString(), anyCollection(), any(Pageable.class))).thenReturn(List.of());

        scheduler.enrich();

        verify(placeRepository).findEnrichTargets(anyString(), anyCollection(), any(Pageable.class));
    }

    @Test
    @DisplayName("재반영: 결과 행이 없는 묶인 장소는 반영하지 않고 실패로도 세지 않는다(다음 실행에서 다시 묻는다)")
    void resumeAppliesOnlyAnsweredPlaces() {
        Place rep = place(1L, "메가MGC커피", CAFE, "Seoul");
        BatchEnrichScheduler spied = spy(scheduler);
        doReturn(textLine(rep, "{\"tags\":[\"커피\"]}")).when(spied).fetchResults("b1");
        when(placeRepository.findAllById(List.of(1L))).thenReturn(List.of(rep));

        spied.resume(List.of("b1"));

        verify(placeRepository).writeEnrichment(rep);
        verify(placeRepository, times(1)).writeEnrichment(any());
    }

    @Test
    @DisplayName("dry-run이면 재반영도 DB에 쓰지 않는다")
    void dryRunSkipsResume() {
        ReflectionTestUtils.setField(scheduler, "dryRun", true);
        ReflectionTestUtils.setField(scheduler, "resumeBatchId", "b1");
        BatchEnrichScheduler spied = spy(scheduler);

        spied.enrich();

        verify(spied, never()).resume(anyList());
    }

    @Test
    @DisplayName("견적은 규칙을 캐시 단가로 셈하고 요청 상한을 넘겨 세지 않는다")
    void estimateUsesCachedRulesAndCap() {
        EnrichEstimate.Total total = EnrichEstimate.of(List.of(
                new EnrichEstimate.Line("KR", false, 1_000), new EnrichEstimate.Line("JP", true, 1_000)), 1_500);

        assertThat(total.requests()).isEqualTo(1_500);
        assertThat(total.outputTokens()).isEqualTo(1_000L * 240 + 500L * 70);
        assertThat(total.cachedUsd()).isLessThan(total.uncachedUsd());
        assertThat(total.summary()).contains("요청 1,500건");
    }
}
