package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.entity.PlaceAttributes;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngredientEnrichmentTest {

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
        ReflectionTestUtils.setField(scheduler, "mode", "ingredients");
        ReflectionTestUtils.setField(scheduler, "maxDuration", java.time.Duration.ofHours(1));
    }

    private JsonNode message(String toolInputJson) throws Exception {
        return objectMapper.readTree("""
                {"content":[{"type":"tool_use","name":"record_main_ingredients","input":%s}]}""".formatted(toolInputJson));
    }

    private static String line(long id, String toolInputJson) {
        return """
                {"custom_id":"place_%d","result":{"type":"succeeded","message":{"content":[{"type":"tool_use","name":"record_main_ingredients","input":%s}],"usage":{"input_tokens":700,"output_tokens":30}}}}"""
                .formatted(id, toolInputJson);
    }

    @Test
    @DisplayName("도구 입력의 주재료를 읽고 허용 목록 밖의 값과 중복은 버린다")
    void interpret_filtersValues() throws Exception {
        assertThat(IngredientEnrichment.interpret(message("""
                {"ingredients":["beef","PORK","BEEF","FISH"]}"""))).containsExactly("BEEF", "PORK");
        assertThat(IngredientEnrichment.interpret(message("""
                {"ingredients":[]}"""))).isEmpty();
    }

    @Test
    @DisplayName("도구 호출이 없거나 다른 도구거나 잘렸거나 배열이 아니면 실패(null)")
    void interpret_missingOrTruncatedIsFailure() throws Exception {
        assertThat(IngredientEnrichment.interpret(objectMapper.readTree("""
                {"content":[{"type":"text","text":"[]"}]}"""))).isNull();
        assertThat(IngredientEnrichment.interpret(objectMapper.readTree("""
                {"content":[{"type":"tool_use","name":"record_place_attributes","input":{"ingredients":["BEEF"]}}]}""")))
                .isNull();
        assertThat(IngredientEnrichment.interpret(objectMapper.readTree("""
                {"stop_reason":"max_tokens","content":[{"type":"tool_use","name":"record_main_ingredients","input":{"ingredients":["BEEF"]}}]}""")))
                .isNull();
        assertThat(IngredientEnrichment.interpret(message("""
                {"ingredients":"BEEF"}"""))).isNull();
    }

    @Test
    @DisplayName("요청은 도구 강제 호출 + thinking 끔 + system 캐시, 고를 수 있는 값은 저장 허용 목록과 같다")
    @SuppressWarnings("unchecked")
    void requestParams_forcesTool() {
        Map<String, Object> params = IngredientEnrichment.requestParams(
                place(1L, "큰집 설렁탕", List.of("식당", "설렁탕"), null), "claude-haiku-5-5");

        assertThat(params.get("tool_choice")).isEqualTo(Map.of("type", "tool", "name", IngredientEnrichment.TOOL_NAME));
        assertThat(params.get("thinking")).isEqualTo(Map.of("type", "disabled"));
        assertThat(((List<Map<String, Object>>) params.get("system")).get(0)).containsKey("cache_control");
        Map<String, Object> schema = (Map<String, Object>) IngredientEnrichment.toolDefinition().get("input_schema");
        Map<String, Object> items = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                schema.get("properties")).get("ingredients")).get("items");
        assertThat((List<String>) items.get("enum")).containsExactlyInAnyOrderElementsOf(PlaceAttributes.INGREDIENTS);
    }

    @Test
    @DisplayName("입력에는 이름·태그와 관광공사 대표 메뉴를 넣고, 없는 칸은 쓰지 않는다")
    void userMessage_includesMenu() {
        Place withMenu = place(1L, "향원복집", List.of("식당", "한식"), new PlaceAttributes("MEAL", null, null, null,
                null, null, Map.of("firstmenu", "복회", "treatmenu", "생복탕 / 복튀김", "usetime", "09:00~21:30")));
        Place bare = place(2L, "할매국밥", List.of(), null);

        String message = IngredientEnrichment.userMessage(withMenu);

        assertThat(message).contains("- 이름: 향원복집", "- 태그: 식당, 한식", "- 대표 메뉴: 복회", "- 대표 메뉴: 생복탕 / 복튀김")
                .doesNotContain("09:00");
        assertThat(IngredientEnrichment.userMessage(bare)).doesNotContain("태그", "대표 메뉴", "설명");
        Place noCategory = Place.builder().id(3L).name("포장마차").region("Busan").build();
        assertThat(IngredientEnrichment.userMessage(noCategory)).doesNotContain("카테고리", "null");
    }

    @Test
    @DisplayName("결과 반영: 주재료만 SQL로 쓰고 엔티티 저장·재임베딩·실패 횟수는 건드리지 않는다, 잘린 응답은 실패")
    void applyResults_writesIngredientsOnly() {
        Place soup = place(1L, "큰집 설렁탕", List.of("식당", "설렁탕"), null);
        Place bibim = place(2L, "비빔밥집", List.of("식당", "비빔밥"), null);
        Place cut = place(3L, "어딘가", List.of(), null);
        String jsonl = String.join("\n",
                line(1, """
                        {"ingredients":["BEEF"]}"""),
                line(2, """
                        {"ingredients":[]}"""),
                """
                {"custom_id":"place_3","result":{"type":"succeeded","message":{"stop_reason":"max_tokens","content":[]}}}""");

        BatchEnrichScheduler.ChunkResult result = scheduler.applyResults(jsonl, List.of(soup, bibim, cut));

        assertThat(result.success()).isEqualTo(2);
        assertThat(result.failedIds()).containsExactly(3L);
        verify(placeRepository).writeIngredients(1L, "[\"BEEF\"]");
        verify(placeRepository).writeIngredients(2L, "[]");
        verify(placeRepository, never()).save(any());
        verify(placeRepository, never()).resetEmbeddings(anyList());
        verify(placeRepository, never()).incrementEnrichAttempts(anyCollection());
        verifyNoInteractions(embeddingBatchJob);
    }

    @Test
    @DisplayName("요청은 장소 id로 짝을 짓고 주재료 도구를 부른다")
    @SuppressWarnings("unchecked")
    void buildBatchRequests_usesIngredientTool() {
        List<Map<String, Object>> requests = scheduler.buildBatchRequests(
                List.of(place(7L, "큰집 설렁탕", List.of("식당", "설렁탕"), null)));

        assertThat(requests).singleElement().satisfies(r -> {
            assertThat(r.get("custom_id")).isEqualTo("place_7_" + IngredientEnrichment.inputKey(
                    place(7L, "큰집 설렁탕", List.of("식당", "설렁탕"), null)));
            assertThat(((Map<String, Object>) r.get("params")).get("tool_choice"))
                    .isEqualTo(Map.of("type", "tool", "name", IngredientEnrichment.TOOL_NAME));
        });
    }

    @Test
    @DisplayName("다른 모드가 만든 결과는 재반영하지 않고 실패로 끝낸다")
    void resume_rejectsResultsOfAnotherMode() {
        ReflectionTestUtils.setField(scheduler, "mode", "v1");
        BatchEnrichScheduler spied = spy(scheduler);
        doReturn(line(6, """
                {"ingredients":["BEEF"]}""")).when(spied).fetchResults("b1");

        assertThatThrownBy(() -> spied.resume(List.of("b1"))).isInstanceOf(BatchStageFailed.class);

        verify(placeRepository, never()).findAllById(anyList());
        verify(placeRepository, never()).writeIngredients(any(), any());
        assertThat(scheduler.modeOf("""
                {"custom_id":"place_1","result":{"type":"succeeded","message":{"content":[{"type":"text","text":"{}"}]}}}"""))
                .isEqualTo(BatchEnrichScheduler.Mode.V1);
        assertThat(scheduler.modeOf("""
                {"custom_id":"place_1","result":{"type":"succeeded","message":{"content":[{"type":"tool_use","name":"record_place_attributes","input":{}}]}}}"""))
                .isEqualTo(BatchEnrichScheduler.Mode.ATTRIBUTES);
        assertThat(scheduler.modeOf("""
                {"custom_id":"place_1","result":{"type":"expired"}}""")).isNull();
        assertThat(scheduler.modeOf("""
                {"custom_id":"place_1","result":{"type":"succeeded","message":{"stop_reason":"max_tokens","content":[]}}}""")).isNull();
    }

    @Test
    @DisplayName("지역이 없으면 아무것도 조회하지 않는다")
    void enrich_withoutRegions_skips() {
        scheduler.enrich();

        verifyNoInteractions(placeRepository);
    }

    @Test
    @DisplayName("주재료 대상 쿼리로 고르고, 대상이 없으면 끝낸다")
    void enrich_usesIngredientTargets() {
        ReflectionTestUtils.setField(scheduler, "enrichRegions", "Busan,Taipei");
        when(placeRepository.findIngredientTargetsInRegions(eq(List.of("Busan", "Taipei")), anyCollection(),
                any(Pageable.class))).thenReturn(List.of());

        scheduler.enrich();

        verify(placeRepository).countIngredientTargetsInRegions(List.of("Busan", "Taipei"));
        verify(placeRepository, never()).findAttributeTargetsInRegions(anyList(), anyCollection(), any(Pageable.class));
        verify(placeRepository, never()).findEnrichTargetsInRegions(anyList(), any(), anyCollection(), any(Pageable.class));
    }

    @Test
    @DisplayName("재반영은 주재료를 아직 묻지 않은 행만 대상으로 한다")
    void resume_usesIngredientsAsPending() {
        Place asked = place(5L, "할매국밥", List.of(), new PlaceAttributes(null, null, null, null, null, null, null,
                List.of()));
        Place pending = place(6L, "큰집 설렁탕", List.of(), new PlaceAttributes("MEAL", null, null, null, null, null,
                null));
        BatchEnrichScheduler spied = spy(scheduler);
        String jsonl = line(6, """
                {"ingredients":["BEEF"]}""");
        doReturn(jsonl).when(spied).fetchResults("b1");
        when(placeRepository.findAllById(List.of(6L))).thenReturn(List.of(asked, pending));

        spied.resume(List.of("b1"));

        verify(spied).applyResults(eq(jsonl), argThat((List<Place> l) -> l.equals(List.of(pending))), eq(false));
        verify(placeRepository).writeIngredients(6L, "[\"BEEF\"]");
    }

    @Test
    @DisplayName("실행 시간 상한이 지나 있으면 대상 조회 없이 끝나고 실패로 보지 않는다")
    void enrich_pastDeadline_stopsQuietly() {
        ReflectionTestUtils.setField(scheduler, "enrichRegions", "Busan");
        ReflectionTestUtils.setField(scheduler, "maxDuration", java.time.Duration.ZERO);

        scheduler.enrich();

        verify(placeRepository, never()).findIngredientTargetsInRegions(anyList(), anyCollection(), any(Pageable.class));
    }

    @Test
    @DisplayName("알 수 없는 모드는 아무것도 하지 않고 실패하고, 모드 값은 대소문자·공백과 상관없이 알아본다")
    void unknownModeFails() {
        ReflectionTestUtils.setField(scheduler, "mode", "ingredient");

        assertThat(scheduler.mode()).isNull();
        assertThatThrownBy(scheduler::enrich).isInstanceOf(BatchStageFailed.class).hasMessageContaining("ingredient");
        verifyNoInteractions(placeRepository);

        ReflectionTestUtils.setField(scheduler, "mode", " Ingredients ");
        assertThat(scheduler.mode()).isEqualTo(BatchEnrichScheduler.Mode.INGREDIENTS);
        ReflectionTestUtils.setField(scheduler, "mode", "");
        assertThat(scheduler.mode()).isEqualTo(BatchEnrichScheduler.Mode.V1);
    }

    @Test
    @DisplayName("주재료 모드에 속성 보강 결과를 넣으면 재반영하지 않는다")
    void resume_ingredientModeRejectsAttributeResults() {
        BatchEnrichScheduler spied = spy(scheduler);
        doReturn("""
                {"custom_id":"place_6","result":{"type":"succeeded","message":{"content":[{"type":"tool_use","name":"record_place_attributes","input":{"venue":"MEAL"}}]}}}""")
                .when(spied).fetchResults("b1");

        assertThatThrownBy(() -> spied.resume(List.of("b1"))).isInstanceOf(BatchStageFailed.class);

        verify(placeRepository, never()).findAllById(anyList());
    }

    @Test
    @DisplayName("결과를 기다리는 사이 입력이 바뀐 장소에는 옛 답을 쓰지 않고, 입력이 같으면 쓴다")
    void resume_skipsPlacesWhoseInputChanged() {
        Place asked = place(6L, "큰집 설렁탕", List.of("식당", "설렁탕"), null);
        String oldKey = IngredientEnrichment.inputKey(asked);
        Place changed = place(6L, "큰집 설렁탕", List.of("식당", "설렁탕"), new PlaceAttributes("MEAL", null, null, null,
                null, null, Map.of("firstmenu", "도가니탕")));
        Place same = place(7L, "할매국밥", List.of("식당"), null);
        String jsonl = String.join("\n",
                line(6, """
                        {"ingredients":["BEEF"]}""").replace("\"place_6\"", "\"place_6_" + oldKey + "\""),
                line(7, """
                        {"ingredients":["PORK"]}""").replace("\"place_7\"",
                        "\"place_7_" + IngredientEnrichment.inputKey(same) + "\""));
        BatchEnrichScheduler spied = spy(scheduler);
        doReturn(jsonl).when(spied).fetchResults("b1");
        when(placeRepository.findAllById(List.of(6L, 7L))).thenReturn(List.of(changed, same));

        spied.resume(List.of("b1"));

        verify(placeRepository, never()).writeIngredients(eq(6L), any());
        verify(placeRepository).writeIngredients(7L, "[\"PORK\"]");
    }

    @Test
    @DisplayName("custom_id는 입력 지문이 붙어도 장소 id를 읽고, 다른 형식은 버린다")
    void customIdParsing() {
        assertThat(BatchEnrichScheduler.placeIdOf("place_12")).isEqualTo(12L);
        assertThat(BatchEnrichScheduler.placeIdOf("place_12_9f3a")).isEqualTo(12L);
        assertThat(BatchEnrichScheduler.inputKeyOf("place_12_9f3a")).isEqualTo("9f3a");
        assertThat(BatchEnrichScheduler.inputKeyOf("place_12")).isNull();
        assertThat(BatchEnrichScheduler.placeIdOf("other_12")).isNull();
        assertThat(BatchEnrichScheduler.placeIdOf("place_x")).isNull();
        assertThat(BatchEnrichScheduler.customId(place(12L, "큰집 설렁탕", List.of(), null),
                BatchEnrichScheduler.Mode.V1)).matches("place_12_[0-9a-f]{16}");
    }

    private static Place place(Long id, String name, List<String> tags, PlaceAttributes attributes) {
        return Place.builder()
                .id(id).name(name).address("부산시").category("Dining and Drinking > Restaurant").region("Busan")
                .country("KR").placeRole("MEAL")
                .latitude(BigDecimal.valueOf(35.1)).longitude(BigDecimal.valueOf(129.0))
                .tags(new ArrayList<>(tags)).attributes(attributes)
                .savedAt(OffsetDateTime.now()).active(true)
                .build();
    }
}
