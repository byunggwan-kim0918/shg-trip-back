package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DescriptionRewriteTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private PlaceRepository placeRepository;

    @Mock
    private EmbeddingBatchJob embeddingBatchJob;

    private BatchEnrichScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new BatchEnrichScheduler(placeRepository, objectMapper, embeddingBatchJob, mock(AnthropicBatchClient.class));
        ReflectionTestUtils.setField(scheduler, "chunkSize", 1000);
        ReflectionTestUtils.setField(scheduler, "anthropicApiKey", "test-api-key");
        ReflectionTestUtils.setField(scheduler, "enrichModel", "claude-haiku-5-5");
        ReflectionTestUtils.setField(scheduler, "mode", "descriptions");
        ReflectionTestUtils.setField(scheduler, "maxDuration", java.time.Duration.ofHours(1));
    }

    private static Place place(long id, String name, String country, String description) {
        return Place.builder().id(id).name(name).address("부산 해운대구")
                .latitude(BigDecimal.ONE).longitude(BigDecimal.ONE)
                .category("Landmarks and Outdoors > Beach").region("Busan").country(country)
                .description(description).build();
    }

    private JsonNode message(String description) throws Exception {
        return objectMapper.readTree("""
                {"content":[{"type":"tool_use","name":"rewrite_description","input":{"description":%s}}]}"""
                .formatted(objectMapper.writeValueAsString(description)));
    }

    private String line(Place place, String description) throws Exception {
        return """
                {"custom_id":"%s","result":{"type":"succeeded","message":{"content":[{"type":"tool_use","name":"rewrite_description","input":{"description":%s}}],"usage":{"input_tokens":300,"output_tokens":80}}}}"""
                .formatted(BatchEnrichScheduler.customId(place, BatchEnrichScheduler.Mode.DESCRIPTIONS),
                        objectMapper.writeValueAsString(description));
    }

    private static boolean reputation(String text) {
        return DescriptionRewrite.hasReputation(text, place(0, "어느 곳", "KR", text));
    }

    @Test
    @DisplayName("평판·권유 문구는 잡고, 사실을 전하는 말(곳으로 알려진·현지인들이 참배하는·예약 필수)과 장소 정보는 잡지 않는다")
    void hasReputation() {
        assertThat(reputation("현지인들이 즐겨 찾는 맛집입니다.")).isTrue();
        assertThat(reputation("사진 촬영 명소로 인기가 있습니다.")).isTrue();
        assertThat(reputation("제주 서부의 대표적인 오름입니다.")).isTrue();
        assertThat(reputation("널리 알려진 사찰입니다.")).isTrue();
        assertThat(reputation("관광객과 주민이 함께 찾는 식당입니다.")).isTrue();
        assertThat(reputation("여행객에게 추천할 만합니다.")).isTrue();
        assertThat(reputation("부산 여행의 필수 코스입니다.")).isTrue();
        assertThat(reputation("많은 현지인과 여행자가 찾는 명소입니다.")).isTrue();

        assertThat(reputation("임진왜란 때 쌓은 성으로 알려져 있습니다.")).isFalse();
        assertThat(reputation("가부키가 처음 시작된 곳으로 알려진 거리입니다.")).isFalse();
        assertThat(reputation("현지인의 장보기를 볼 수 있는 시장입니다.")).isFalse();
        assertThat(reputation("현지인들이 참배하는 모습을 볼 수 있습니다.")).isFalse();
        assertThat(reputation("앞에 바다가 보이는 언덕의 카페입니다.")).isFalse();
        assertThat(reputation("입장 시 신분증 지참이 필수이며 탐방 예약이 필요합니다.")).isFalse();
        assertThat(reputation("노을과 함께 찾으면 더 인상적인 해안입니다.")).isFalse();
        assertThat(reputation(null)).isFalse();
    }

    @Test
    @DisplayName("장소 이름에 든 낱말은 평판으로 보지 않는다. 이름 밖의 평판 문구는 그대로 잡는다")
    void hasReputation_ignoresNameWords() {
        Place gukbap = place(1, "소문난 국밥", "KR", "소문난 국밥은 돼지국밥을 내는 식당입니다.");
        Place church = Place.builder().id(2L).name("Myeongseong Church").nameKo("경주명성교회").address("경주")
                .latitude(BigDecimal.ONE).longitude(BigDecimal.ONE).category("Spiritual Center").region("Gyeongju")
                .country("KR").description("경주명성교회는 시내에 있는 교회입니다.").build();

        assertThat(DescriptionRewrite.needsRewrite(gukbap)).isFalse();
        assertThat(DescriptionRewrite.needsRewrite(church)).isFalse();
        assertThat(DescriptionRewrite.hasReputation("소문난 국밥은 현지인들이 즐겨 찾는 곳입니다.", gukbap)).isTrue();
    }

    @Test
    @DisplayName("다시 쓸 대상: 평판 문구, 국내 소개의 섞인 글자. 이름을 따라 쓴 한자·해외 장소·빈 소개는 아니다")
    void needsRewrite() {
        assertThat(DescriptionRewrite.needsRewrite(place(1, "광안리", "KR", "야경 명소로 인기입니다."))).isTrue();
        assertThat(DescriptionRewrite.needsRewrite(place(2, "경주레져", "KR", "가족과 즐기기에 适합니다."))).isTrue();
        assertThat(DescriptionRewrite.needsRewrite(place(3, "석불사 (石佛寺)", "KR", "石佛寺는 바위 불상이 있는 절입니다.")))
                .isFalse();
        assertThat(DescriptionRewrite.needsRewrite(place(4, "金閣寺", "JP", "鹿苑寺라고도 부르는 절입니다."))).isFalse();
        assertThat(DescriptionRewrite.needsRewrite(place(5, "金閣寺", "JP", "교토의 대표적인 절입니다."))).isTrue();
        assertThat(DescriptionRewrite.needsRewrite(place(6, "빈 곳", "KR", "  "))).isFalse();
    }

    @Test
    @DisplayName("응답 해석: 평판 문구·섞인 글자가 남았거나, 비었거나, 잘렸거나, 도구 호출이 없으면 실패(null)")
    void interpret() throws Exception {
        Place kr = place(1, "광안리", "KR", "야경 명소로 인기입니다.");

        assertThat(DescriptionRewrite.interpret(message(" 광안대교가 보이는 해변입니다. "), kr))
                .isEqualTo("광안대교가 보이는 해변입니다.");
        assertThat(DescriptionRewrite.interpret(message("야경 명소로 사랑받는 해변입니다."), kr)).isNull();
        assertThat(DescriptionRewrite.interpret(message("산책하기 适합한 해변입니다."), kr)).isNull();
        assertThat(DescriptionRewrite.interpret(message(" "), kr)).isNull();
        assertThat(DescriptionRewrite.interpret(objectMapper.readTree("""
                {"stop_reason":"max_tokens","content":[]}"""), kr)).isNull();
        assertThat(DescriptionRewrite.interpret(objectMapper.readTree("""
                {"content":[{"type":"text","text":"광안대교가 보이는 해변입니다."}]}"""), kr)).isNull();
        assertThat(DescriptionRewrite.interpret(message("鹿苑寺라고도 부르는 절입니다."), place(4, "金閣寺", "JP", "교토의 대표적인 절")))
                .isEqualTo("鹿苑寺라고도 부르는 절입니다.");
    }

    @Test
    @DisplayName("고친 소개가 기존 소개보다 눈에 띄게 길면 지어낸 내용이 붙은 것으로 보고 받지 않는다")
    void interpret_rejectsLongerThanOriginal() throws Exception {
        Place shortIntro = place(1, "광안리", "KR", "야경 명소로 인기입니다.");
        Place longIntro = place(2, "해운대", "KR", "해운대는 " + "모래사장이 넓고 ".repeat(20) + "유명한 해변입니다.");

        assertThat(DescriptionRewrite.interpret(message("광안대교가 정면으로 보이고 밤에는 조명이 켜지며 근처에 횟집 골목이 있는 해변입니다."),
                shortIntro)).isNull();
        assertThat(DescriptionRewrite.interpret(message("광안대교가 정면으로 보이는 해변입니다."), shortIntro)).isNotNull();
        assertThat(DescriptionRewrite.interpret(message("해운대는 " + "모래사장이 넓고 ".repeat(20) + "긴 해변입니다."), longIntro))
                .isNotNull();
    }

    @Test
    @DisplayName("기존 소개보다 괄호가 늘면 원문에 없던 읽는 법을 붙인 것으로 보고 받지 않는다")
    void interpret_rejectsAddedParentheses() throws Exception {
        Place ryokan = place(1, "玉半", "JP", "玉半은 교토의 전통 숙박 시설로, 일본식 정원으로 유명합니다.");
        Place temple = place(2, "석불사 (石佛寺)", "KR", "석불사(石佛寺)는 바위 불상으로 유명한 절입니다.");

        assertThat(DescriptionRewrite.interpret(message("玉半(다마한)은 교토의 전통 숙박 시설입니다."), ryokan)).isNull();
        assertThat(DescriptionRewrite.interpret(message("玉半은 일본식 정원이 있는 교토의 전통 숙박 시설입니다."), ryokan))
                .isNotNull();
        assertThat(DescriptionRewrite.interpret(message("석불사(石佛寺)는 바위 불상이 있는 절입니다."), temple)).isNotNull();
    }

    @Test
    @DisplayName("요청은 소개 도구를 강제로 부르고 thinking을 끄며, 입력 지문은 소개가 바뀌면 달라진다")
    @SuppressWarnings("unchecked")
    void buildBatchRequests() {
        Place before = place(7, "광안리", "KR", "야경 명소로 인기입니다.");

        List<Map<String, Object>> requests = scheduler.buildBatchRequests(List.of(before));

        assertThat(requests).singleElement().satisfies(r -> {
            assertThat(r.get("custom_id")).isEqualTo("place_7_" + DescriptionRewrite.inputKey(before));
            Map<String, Object> params = (Map<String, Object>) r.get("params");
            assertThat(params.get("tool_choice")).isEqualTo(Map.of("type", "tool", "name", DescriptionRewrite.TOOL_NAME));
            assertThat(params.get("thinking")).isEqualTo(Map.of("type", "disabled"));
        });
        assertThat(DescriptionRewrite.userMessage(before)).contains("- 기존 설명: 야경 명소로 인기입니다.");
        assertThat(DescriptionRewrite.inputKey(place(7, "광안리", "KR", "다른 소개입니다.")))
                .isNotEqualTo(DescriptionRewrite.inputKey(before));
        assertThat(DescriptionRewrite.SYSTEM_PROMPT).doesNotContain("%s").contains(DescriptionRewrite.TOOL_NAME);
    }

    @Test
    @DisplayName("결과 반영: 보낼 때의 소개가 그대로인 행만 바꾸고 다시 임베딩한다. 그사이 바뀐 행은 실패로 세지 않고 이번 실행에서만 뺀다")
    void applyResults() throws Exception {
        Place ok = place(1, "광안리", "KR", "야경 명소로 인기입니다.");
        Place changed = place(2, "해운대", "KR", "가장 유명한 해변입니다.");
        Place stillBad = place(3, "송정", "KR", "서퍼들에게 인기입니다.");
        when(placeRepository.writeDescription(1L, "광안대교가 보이는 해변입니다.", "야경 명소로 인기입니다.")).thenReturn(1);
        when(placeRepository.writeDescription(2L, "모래사장이 넓은 해변입니다.", "가장 유명한 해변입니다.")).thenReturn(0);
        when(embeddingBatchJob.reembed(anyList())).thenReturn(List.of());
        String jsonl = String.join("\n",
                line(ok, "광안대교가 보이는 해변입니다."),
                line(changed, "모래사장이 넓은 해변입니다."),
                line(stillBad, "서퍼들에게 사랑받는 해변입니다."));

        BatchEnrichScheduler.ChunkResult result = scheduler.applyResults(jsonl, List.of(ok, changed, stillBad));

        assertThat(result.success()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failedIds()).containsExactly(2L, 3L);
        assertThat(ok.getDescription()).isEqualTo("광안대교가 보이는 해변입니다.");
        verify(embeddingBatchJob).reembed(List.of(ok));
        verify(placeRepository, never()).writeDescription(eq(3L), anyString(), anyString());
        verify(placeRepository).incrementDescriptionRewriteAttempts(Set.of(3L));
        verify(placeRepository, never()).incrementEnrichAttempts(anyCollection());
        verify(placeRepository, never()).save(any());
        verify(placeRepository, never()).resetEmbeddings(anyList());
    }

    @Test
    @DisplayName("다시 임베딩하지 못한 행은 임베딩을 비워 임베딩 단계가 다시 만들게 한다")
    void applyResults_resetsEmbeddingWhenReembedFails() throws Exception {
        Place ok = place(1, "광안리", "KR", "야경 명소로 인기입니다.");
        when(placeRepository.writeDescription(1L, "광안대교가 보이는 해변입니다.", "야경 명소로 인기입니다.")).thenReturn(1);
        when(embeddingBatchJob.reembed(List.of(ok))).thenReturn(List.of(1L));

        scheduler.applyResults(line(ok, "광안대교가 보이는 해변입니다."), List.of(ok));

        verify(placeRepository).resetEmbeddings(List.of(1L));
    }

    @Test
    @DisplayName("지역 없이 전체를 고르고, 이름을 따라 쓴 한자만 걸린 행은 요청하지 않고 다음 조회에서 뺀다")
    void enrich_filtersNameOnlyMatches() {
        Place nameOnly = place(1, "석불사 (石佛寺)", "KR", "石佛寺는 바위 불상이 있는 절입니다.");
        Place target = place(2, "광안리", "KR", "야경 명소로 인기입니다.");
        when(placeRepository.findDescriptionRewriteTargets(eq(true), eq(List.of("")), anyList(),
                eq(DescriptionRewrite.REPUTATION_REGEX), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(nameOnly, target), List.of());
        BatchEnrichScheduler spied = spy(scheduler);
        doReturn(List.of(new BatchEnrichScheduler.ChunkResult(1, 0, List.of())))
                .when(spied).processChunks(List.of(List.of(target)));

        spied.enrich();

        verify(placeRepository).countDescriptionRewriteTargets(eq(true), eq(List.of("")),
                eq(List.of("KR")),
                eq(DescriptionRewrite.REPUTATION_REGEX));
        verify(placeRepository, times(2)).findDescriptionRewriteTargets(anyBoolean(), anyList(), anyList(), anyString(),
                anyCollection(), any(Pageable.class));
        verify(placeRepository).findDescriptionRewriteTargets(anyBoolean(), anyList(), anyList(), anyString(),
                argThat((Collection<Long> excluded) -> excluded.contains(1L) && !excluded.contains(2L)),
                any(Pageable.class));
        verify(spied).processChunks(List.of(List.of(target)));
    }

    @Test
    @DisplayName("재반영은 아직 고칠 소개가 남은 행만 대상으로 하고, 결과 모드는 도구 이름으로 가린다")
    void resume_usesNeedsRewriteAsPending() throws Exception {
        Place done = place(5, "해운대", "KR", "모래사장이 넓은 해변입니다.");
        Place pending = place(6, "광안리", "KR", "야경 명소로 인기입니다.");
        String jsonl = line(pending, "광안대교가 보이는 해변입니다.");
        BatchEnrichScheduler spied = spy(scheduler);
        doReturn(jsonl).when(spied).fetchResults("b1");
        when(placeRepository.findAllById(List.of(6L))).thenReturn(List.of(done, pending));
        when(placeRepository.writeDescription(6L, "광안대교가 보이는 해변입니다.", "야경 명소로 인기입니다.")).thenReturn(1);
        when(embeddingBatchJob.reembed(anyList())).thenReturn(List.of());

        spied.resume(List.of("b1"));

        assertThat(scheduler.modeOf(jsonl)).isEqualTo(BatchEnrichScheduler.Mode.DESCRIPTIONS);
        verify(spied).applyResults(eq(jsonl), argThat((List<Place> l) -> l.equals(List.of(pending))), eq(false));
    }

    @Test
    @DisplayName("v1 보강은 평판 문구가 남은 소개도 그대로 저장한다. 사실이 섞인 문장을 지우지 않고 다시 쓰기에 맡긴다")
    void v1EnrichmentKeepsDescription() {
        ReflectionTestUtils.setField(scheduler, "mode", "v1");
        Place beach = place(9, "송정해수욕장", "KR", null);

        boolean applied = scheduler.applyEnrichment(beach, """
                {"tags":["해변"],"description":"서핑 강습을 받을 수 있어 서퍼들에게 인기가 많은 해변입니다.","recommended_time_slots":["afternoon"],"place_role":"SIGHT"}""");

        assertThat(applied).isTrue();
        assertThat(beach.getDescription()).isEqualTo("서핑 강습을 받을 수 있어 서퍼들에게 인기가 많은 해변입니다.");
        assertThat(DescriptionRewrite.needsRewrite(beach)).isTrue();
    }

    @Test
    @DisplayName("v1 보강 뒤 같은 실행에서 소개 다시 쓰기를 돌고, 끄면 돌지 않는다")
    void v1RunIsFollowedByDescriptionRewrite() {
        ReflectionTestUtils.setField(scheduler, "mode", "v1");
        when(placeRepository.findEnrichTargets(any(), anyCollection(), any(Pageable.class))).thenReturn(List.of());
        when(placeRepository.findDescriptionRewriteTargets(anyBoolean(), anyList(), anyList(), anyString(),
                anyCollection(), any(Pageable.class))).thenReturn(List.of());

        scheduler.enrich();

        var order = inOrder(placeRepository);
        order.verify(placeRepository).findEnrichTargets(any(), anyCollection(), any(Pageable.class));
        order.verify(placeRepository).findDescriptionRewriteTargets(eq(true), eq(List.of("")), anyList(),
                eq(DescriptionRewrite.REPUTATION_REGEX), anyCollection(), any(Pageable.class));
        assertThat(scheduler.mode()).isEqualTo(BatchEnrichScheduler.Mode.V1);

        clearInvocations(placeRepository);
        ReflectionTestUtils.setField(scheduler, "categoryPrefix", "Dining and Drinking");
        scheduler.enrich();
        verify(placeRepository, never()).findDescriptionRewriteTargets(anyBoolean(), anyList(), anyList(), anyString(),
                anyCollection(), any(Pageable.class));

        clearInvocations(placeRepository);
        ReflectionTestUtils.setField(scheduler, "categoryPrefix", "");
        ReflectionTestUtils.setField(scheduler, "rewriteDescriptionsAfterV1", false);
        scheduler.enrich();
        verify(placeRepository, never()).findDescriptionRewriteTargets(anyBoolean(), anyList(), anyList(), anyString(),
                anyCollection(), any(Pageable.class));
    }

    @Test
    @DisplayName("v1 패스가 실패하면 소개 다시 쓰기를 돌지 않고, 소개 다시 쓰기가 실패하면 단계가 실패한다")
    void passFailures() {
        ReflectionTestUtils.setField(scheduler, "mode", "v1");
        Place unenriched = place(1, "송정해수욕장", "KR", null);
        Place target = place(2, "광안리", "KR", "야경 명소로 인기입니다.");
        BatchEnrichScheduler spied = spy(scheduler);
        when(placeRepository.findEnrichTargets(any(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(unenriched), List.of());
        doReturn(List.of(new BatchEnrichScheduler.ChunkResult(0, 1, List.of(1L))))
                .when(spied).processChunks(List.of(List.of(unenriched)));

        assertThatThrownBy(spied::enrich).isInstanceOf(BatchStageFailed.class).hasMessageContaining("v1");
        verify(placeRepository, never()).findDescriptionRewriteTargets(anyBoolean(), anyList(), anyList(), anyString(),
                anyCollection(), any(Pageable.class));

        when(placeRepository.findEnrichTargets(any(), anyCollection(), any(Pageable.class))).thenReturn(List.of());
        when(placeRepository.findDescriptionRewriteTargets(anyBoolean(), anyList(), anyList(), anyString(),
                anyCollection(), any(Pageable.class))).thenReturn(List.of(target), List.of());
        doReturn(List.of(new BatchEnrichScheduler.ChunkResult(0, 1, List.of(2L))))
                .when(spied).processChunks(List.of(List.of(target)));

        assertThatThrownBy(spied::enrich).isInstanceOf(BatchStageFailed.class).hasMessageContaining("descriptions");
        assertThat(spied.mode()).isEqualTo(BatchEnrichScheduler.Mode.V1);
    }
}
