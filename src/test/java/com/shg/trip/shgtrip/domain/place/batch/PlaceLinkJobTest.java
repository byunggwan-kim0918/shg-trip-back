package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.batch.PlaceLinkJob.Candidate;
import com.shg.trip.shgtrip.domain.place.repository.PlaceLinkRepository;
import com.shg.trip.shgtrip.domain.place.repository.PlaceLinkRepository.LinkRow;
import com.shg.trip.shgtrip.domain.place.repository.PlaceLinkRepository.PairClass;
import com.shg.trip.shgtrip.domain.place.repository.PlaceLinkRepository.PairRow;
import com.shg.trip.shgtrip.domain.place.repository.PlaceLinkRepository.PlaceRow;
import com.shg.trip.shgtrip.domain.place.repository.PlaceLinkRepository.VerdictRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class PlaceLinkJobTest {

    private final PlaceLinkRepository links = mock(PlaceLinkRepository.class);
    private final AnthropicBatchClient client = mock(AnthropicBatchClient.class);
    private PlaceLinkJob job;

    @BeforeEach
    void setUp() {
        job = new PlaceLinkJob(links, client, new ObjectMapper());
        ReflectionTestUtils.setField(job, "model", "claude-haiku-5-5");
        ReflectionTestUtils.setField(job, "regionsCsv", "Busan");
        ReflectionTestUtils.setField(job, "maxPairs", 100_000);
        when(client.hasApiKey()).thenReturn(true);
        when(links.findNearPairs(anyString(), any(PairClass.class), anyInt())).thenReturn(List.of());
        when(links.findPlaceRows(anyCollection())).thenAnswer(inv -> rows((Collection<Long>) inv.getArgument(0)));
    }

    private static final Map<Long, PlaceRow> ROWS = List.of(
            row(1, "부산타워", null, "Q490777", 16, "tourapi"),
            row(2, "부산다이아몬드타워", null, null, 0, "foursquare"),
            row(3, "용연사(강릉)", null, null, 0, "tourapi"),
            row(4, "용연사", null, null, 0, "foursquare"),
            row(5, "해동용궁사", null, null, 0, "foursquare"),
            row(6, "해동용궁사 Haedong Yonggungsa Temple", null, null, 0, "foursquare"),
            row(7, "범어사", null, "Q487662", 7, "foursquare"),
            row(8, "범어사 조계문", null, null, 0, "foursquare"),
            row(9, "Flower Clock", "꽃시계", null, 0, "foursquare"),
            row(10, "꽃시계", null, null, 0, "tourapi"),
            row(11, "충무공 이순신동상", null, null, 0, "foursquare"),
            row(12, "부산 타워", null, null, 0, "foursquare")
    ).stream().collect(Collectors.toMap(PlaceRow::id, r -> r));

    private static PlaceRow row(long id, String name, String nameKo, String qid, int links, String source) {
        return new PlaceRow(id, name, nameKo, "Landmarks and Outdoors > Park", source, null, qid, links, true, false,
                true, true);
    }

    private static Map<Long, PlaceRow> rows(Collection<Long> ids) {
        return ids.stream().filter(ROWS::containsKey).collect(Collectors.toMap(id -> id, ROWS::get));
    }

    private void attractionPairs(String region, PairRow... pairs) {
        when(links.findNearPairs(region, PairClass.ATTRACTION, PlaceLinkJob.MAX_PAIR_METERS)).thenReturn(List.of(pairs));
    }

    private void answers(String batchId, String... lines) {
        when(client.poll(eq(batchId), anyLong())).thenReturn(new AnthropicBatchClient.PollResult("https://" + batchId, false));
        when(client.download("https://" + batchId)).thenReturn(String.join("\n", lines));
    }

    private static String result(String customId, String relation) {
        return """
                {"custom_id":"%s","result":{"type":"succeeded","message":{"content":[{"type":"tool_use","name":"record_place_relation","input":{"relation":"%s"}}]}}}"""
                .formatted(customId, relation);
    }

    @Test
    @DisplayName("이름 근거: 같은 Wikidata 항목, 괄호 설명·영문 꼬리를 뗀 같은 이름, 한국어 이름, 한쪽 이름이 다른 쪽으로 시작함")
    void nameEvidence() {
        assertThat(PlaceLinkJob.hasNameEvidence(ROWS.get(3L), ROWS.get(4L))).isTrue();
        assertThat(PlaceLinkJob.hasNameEvidence(ROWS.get(5L), ROWS.get(6L))).isTrue();
        assertThat(PlaceLinkJob.hasNameEvidence(ROWS.get(7L), ROWS.get(8L))).isTrue();
        assertThat(PlaceLinkJob.hasNameEvidence(ROWS.get(9L), ROWS.get(10L))).isTrue();
        assertThat(PlaceLinkJob.hasNameEvidence(ROWS.get(1L), ROWS.get(2L))).isFalse();
        assertThat(PlaceLinkJob.hasNameEvidence(ROWS.get(1L), ROWS.get(11L))).isFalse();
    }

    @Test
    @DisplayName("이름 근거 없는 관광지 쌍은 40m 안만 묻고, 숙소·식당 쌍은 쿼리가 고른 대로 묻는다")
    void candidatesDropFarNamelessAttractions() {
        attractionPairs("Busan", new PairRow(1, 2, 17), new PairRow(1, 11, 101), new PairRow(7, 8, 120));
        when(links.findNearPairs("Busan", PairClass.LODGING, PlaceLinkJob.MAX_PAIR_METERS))
                .thenReturn(List.of(new PairRow(9, 11, 140)));

        List<Candidate> candidates = job.candidates("Busan");

        assertThat(candidates).extracting(c -> c.low().id() + "-" + c.high().id())
                .containsExactly("1-2", "7-8", "9-11");
        assertThat(candidates.get(0).inputKey())
                .isEqualTo(PlacePairJudge.inputKey(candidates.get(0).low(), candidates.get(0).high(), 17));
    }

    @Test
    @DisplayName("대소문자·띄어쓰기·문장부호만 다른 이름은 묻지 않고 규칙 판정으로 남긴다")
    void sameNameIsRecordedWithoutAsking() {
        attractionPairs("Busan", new PairRow(1, 12, 120));

        job.run();

        ArgumentCaptor<List<VerdictRow>> written = ArgumentCaptor.forClass(List.class);
        verify(links).upsertVerdicts(written.capture());
        assertThat(written.getValue()).singleElement().satisfies(v -> {
            assertThat(v.relation()).isEqualTo("SAME");
            assertThat(v.basis()).isEqualTo(PlaceLinkJob.BASIS_RULE);
            assertThat(v.lowId()).isEqualTo(1);
            assertThat(v.highId()).isEqualTo(12);
        });
        verify(client, never()).submit(anyList());
    }

    @Test
    @DisplayName("판정이 없거나 입력이 바뀐 쌍만 묻고, 여러 지역을 한 묶음으로 내며, 답은 쌍 id와 입력 지문으로 짝지어 남긴다")
    void asksOnlyNewOrChangedPairsAcrossRegions() {
        ReflectionTestUtils.setField(job, "regionsCsv", "Busan,Gyeongju");
        attractionPairs("Busan", new PairRow(1, 2, 17), new PairRow(3, 4, 39));
        attractionPairs("Gyeongju", new PairRow(5, 6, 12));
        Candidate tower = job.candidates("Busan").get(0);
        Candidate unchanged = job.candidates("Busan").get(1);
        Candidate temple = job.candidates("Gyeongju").get(0);
        when(links.findVerdicts("Busan")).thenReturn(List.of(new VerdictRow(3, 4, "SAME", "LLM", unchanged.inputKey())));
        when(links.findVerdicts("Gyeongju")).thenReturn(List.of(new VerdictRow(5, 6, "SAME", "LLM", "old-key")));
        when(client.submit(anyList())).thenReturn("b1");
        answers("b1", result(PlaceLinkJob.customId(tower), "SAME"), result(PlaceLinkJob.customId(temple), "SAME"),
                result("pair_1_2_stale", "DIFFERENT"));

        job.run();

        ArgumentCaptor<List<Map<String, Object>>> requests = ArgumentCaptor.forClass(List.class);
        verify(client).submit(requests.capture());
        assertThat(requests.getValue()).extracting(r -> r.get("custom_id"))
                .containsExactly(PlaceLinkJob.customId(tower), PlaceLinkJob.customId(temple));
        ArgumentCaptor<List<VerdictRow>> written = ArgumentCaptor.forClass(List.class);
        verify(links).upsertVerdicts(written.capture());
        assertThat(written.getValue()).containsExactly(
                new VerdictRow(1, 2, "SAME", "LLM", tower.inputKey()),
                new VerdictRow(5, 6, "SAME", "LLM", temple.inputKey()));
    }

    @Test
    @DisplayName("상한을 넘겨 묻지 않고, 답이 안 오면 실패로 끝내며, 키가 없으면 묻지 않은 채 대표 행만 다시 계산한다")
    void respectsCapFailureAndMissingKey() {
        attractionPairs("Busan", new PairRow(1, 2, 17), new PairRow(3, 4, 39));
        ReflectionTestUtils.setField(job, "maxPairs", 1);
        when(client.submit(anyList())).thenReturn("b");
        when(client.poll(eq("b"), anyLong())).thenReturn(new AnthropicBatchClient.PollResult(null, true));

        assertThatThrownBy(job::run).isInstanceOf(BatchStageFailed.class);
        ArgumentCaptor<List<Map<String, Object>>> requests = ArgumentCaptor.forClass(List.class);
        verify(client).submit(requests.capture());
        assertThat(requests.getValue()).hasSize(1);

        AnthropicBatchClient noKey = mock(AnthropicBatchClient.class);
        PlaceLinkJob withoutKey = new PlaceLinkJob(links, noKey, new ObjectMapper());
        ReflectionTestUtils.setField(withoutKey, "regionsCsv", "Busan");
        ReflectionTestUtils.setField(withoutKey, "maxPairs", 100_000);
        withoutKey.run();
        verify(noKey, never()).submit(anyList());
        verify(links, atLeastOnce()).replaceLinks(eq("Busan"), anyCollection());
    }

    @Test
    @DisplayName("대표 행은 지금 대상인 쌍 중 입력 지문이 맞는 판정으로만 계산하고, 다른 곳 판정도 넘겨 모순을 본다")
    void resolveUsesOnlyCurrentVerdicts() {
        attractionPairs("Busan", new PairRow(1, 2, 17), new PairRow(7, 8, 30), new PairRow(3, 4, 39),
                new PairRow(1, 11, 20));
        List<Candidate> candidates = job.candidates("Busan");
        when(links.findVerdicts("Busan")).thenReturn(List.of(
                new VerdictRow(1, 2, "SAME", "LLM", candidates.get(0).inputKey()),
                new VerdictRow(7, 8, "HIGH_IN_LOW", "LLM", candidates.get(1).inputKey()),
                new VerdictRow(3, 4, "SAME", "RULE", "stale-key"),
                new VerdictRow(5, 6, "SAME", "LLM", "no-longer-a-candidate"),
                new VerdictRow(1, 11, "DIFFERENT", "LLM", candidates.get(3).inputKey())));
        org.mockito.Mockito.clearInvocations(links);

        job.resolve("Busan", candidates);

        ArgumentCaptor<Collection<LinkRow>> written = ArgumentCaptor.forClass(Collection.class);
        verify(links).replaceLinks(eq("Busan"), written.capture());
        assertThat(written.getValue()).containsExactlyInAnyOrder(new LinkRow(2, 1, "SAME"), new LinkRow(8, 7, "PART"));
        ArgumentCaptor<Collection<Long>> loaded = ArgumentCaptor.forClass(Collection.class);
        verify(links).findPlaceRows(loaded.capture());
        assertThat(loaded.getValue()).containsExactlyInAnyOrder(1L, 2L, 7L, 8L, 11L);
    }

    @Test
    @DisplayName("파이프라인 판정은 근처 쌍에 없거나 입력 지문이 달라도 대표 행 계산에 쓰고 다시 묻지 않는다")
    void pipelineVerdictsSurviveRelinking() {
        attractionPairs("Busan", new PairRow(1, 2, 17));
        when(links.findVerdicts("Busan")).thenReturn(List.of(
                new VerdictRow(1, 2, "SAME", PlaceLinkJob.BASIS_PIPELINE, "run-a"),
                new VerdictRow(5, 6, "SAME", PlaceLinkJob.BASIS_PIPELINE, "run-a")));

        job.run();

        verify(client, never()).submit(anyList());
        verify(links, never()).upsertVerdicts(anyList());
        ArgumentCaptor<Collection<LinkRow>> written = ArgumentCaptor.forClass(Collection.class);
        verify(links).replaceLinks(eq("Busan"), written.capture());
        assertThat(written.getValue()).containsExactlyInAnyOrder(new LinkRow(2, 1, "SAME"), new LinkRow(6, 5, "SAME"));
    }

    @Test
    @DisplayName("상한이 1보다 작으면 묻지 않은 채 조용히 끝나지 않고 실패한다")
    void rejectsNonPositiveCap() {
        ReflectionTestUtils.setField(job, "maxPairs", 0);

        assertThatThrownBy(job::run).isInstanceOf(BatchStageFailed.class).hasMessageContaining("max-pairs");
    }

    @Test
    @DisplayName("판정 요청·반영 중 예외가 나도 모든 지역의 대표 행은 다시 계산하고 단계는 실패로 끝난다")
    void askFailureStillResolves() {
        attractionPairs("Busan", new PairRow(1, 2, 17));
        when(client.submit(anyList())).thenThrow(new IllegalStateException("network"));

        assertThatThrownBy(job::run).isInstanceOf(BatchStageFailed.class);

        verify(links).replaceLinks(eq("Busan"), anyCollection());
    }

    @Test
    @DisplayName("관광특구 같은 구역·음식 거리는 검색에 나와도 대표나 큰 장소가 되지 않는다")
    void zonesAreNotUsableTargets() {
        PlaceRow zone = new PlaceRow(20, "용두산 자갈치 관광특구", null, "Landmarks and Outdoors > Tourist Attraction",
                "tourapi", null, null, 0, true, false, true, false);
        PlaceRow foodStreet = new PlaceRow(21, "국제시장 먹자골목", null, "Dining and Drinking > Food Court",
                "tourapi", null, null, 0, true, false, true, false);

        assertThat(PlaceLinkJob.usableAsCandidate(zone)).isFalse();
        assertThat(PlaceLinkJob.usableAsCandidate(foodStreet)).isFalse();
        assertThat(PlaceLinkJob.usableAsCandidate(ROWS.get(1L))).isTrue();
    }

    @Test
    @DisplayName("한 지역의 조회가 실패해도 다른 지역은 끝까지 처리하고 단계는 실패로 끝난다")
    void regionFailureDoesNotStopOthers() {
        ReflectionTestUtils.setField(job, "regionsCsv", "Broken,Busan");
        when(links.findNearPairs(eq("Broken"), any(PairClass.class), anyInt())).thenThrow(new IllegalStateException("db"));

        assertThatThrownBy(job::run).isInstanceOf(BatchStageFailed.class);

        verify(links).replaceLinks(eq("Busan"), anyCollection());
    }

    @Test
    @DisplayName("결과의 실패·모르는 쌍·잘린 답은 남기지 않는다")
    void applySkipsUnusableRows() {
        attractionPairs("Busan", new PairRow(1, 2, 17));
        Candidate c = job.candidates("Busan").get(0);
        String id = PlaceLinkJob.customId(c);

        int kept = job.apply(String.join("\n",
                "{\"custom_id\":\"" + id + "\",\"result\":{\"type\":\"errored\"}}",
                result("pair_9_10_x", "SAME"),
                "{\"custom_id\":\"" + id + "\",\"result\":{\"type\":\"succeeded\",\"message\":{\"stop_reason\":\"max_tokens\",\"content\":[]}}}"),
                Map.of(id, c));

        assertThat(kept).isZero();
        verify(links, never()).upsertVerdicts(anyList());
    }
}
