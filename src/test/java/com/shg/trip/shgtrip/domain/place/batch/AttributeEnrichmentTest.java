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
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AttributeEnrichmentTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private PlaceRepository placeRepository;

    @Mock
    private EmbeddingBatchJob embeddingBatchJob;

    private BatchEnrichScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new BatchEnrichScheduler(placeRepository, objectMapper, embeddingBatchJob);
        ReflectionTestUtils.setField(scheduler, "chunkSize", 1000);
        ReflectionTestUtils.setField(scheduler, "anthropicApiKey", "test-api-key");
        ReflectionTestUtils.setField(scheduler, "enrichModel", "claude-haiku-5-5");
        ReflectionTestUtils.setField(scheduler, "mode", "v2");
    }

    private JsonNode message(String toolInputJson) throws Exception {
        return objectMapper.readTree("""
                {"content":[{"type":"tool_use","name":"record_place_attributes","input":%s}]}""".formatted(toolInputJson));
    }

    private static String line(long id, String toolInputJson) {
        return """
                {"custom_id":"place_%d","result":{"type":"succeeded","message":{"content":[{"type":"tool_use","name":"record_place_attributes","input":%s}],"usage":{"input_tokens":900,"output_tokens":40}}}}"""
                .formatted(id, toolInputJson);
    }

    @Test
    @DisplayName("도구 입력을 속성으로 읽고 허용 목록 밖의 값은 버린다")
    void interpret_readsToolInput() throws Exception {
        PlaceAttributes a = AttributeEnrichment.interpret(message("""
                {"venue":null,"sub_type":"hill","visit_style":"STOP","hike_level":"long","lodging_type":"CASTLE","breakfast":null}"""),
                "Landmarks and Outdoors > Tourist Attraction");

        assertThat(a.subType()).isEqualTo("hill");
        assertThat(a.hikeLevel()).isEqualTo("LONG");
        assertThat(a.lodgingType()).isNull();
    }

    @Test
    @DisplayName("카테고리가 구체적인 식음료는 카테고리 업종을 따른다")
    void interpret_specificCategoryOverridesModelVenue() throws Exception {
        PlaceAttributes a = AttributeEnrichment.interpret(message("""
                {"venue":"MEAL","sub_type":null,"visit_style":null,"hike_level":null,"lodging_type":null,"breakfast":null}"""),
                "Dining and Drinking > Bakery");

        assertThat(a.venue()).isEqualTo("BAKERY");
    }

    @Test
    @DisplayName("총칭 식당 카테고리는 모델 업종을 쓴다")
    void interpret_genericCategoryUsesModelVenue() throws Exception {
        PlaceAttributes a = AttributeEnrichment.interpret(message("""
                {"venue":"BAKERY","sub_type":null,"visit_style":null,"hike_level":null,"lodging_type":null,"breakfast":null}"""),
                "Dining and Drinking > Restaurant");

        assertThat(a.venue()).isEqualTo("BAKERY");
    }

    @Test
    @DisplayName("도구 호출이 없거나 max_tokens에서 잘리면 실패(null)")
    void interpret_missingOrTruncatedIsFailure() throws Exception {
        assertThat(AttributeEnrichment.interpret(objectMapper.readTree("""
                {"content":[{"type":"text","text":"{}"}]}"""), "Landmarks")).isNull();
        assertThat(AttributeEnrichment.interpret(objectMapper.readTree("""
                {"stop_reason":"max_tokens","content":[{"type":"tool_use","name":"record_place_attributes","input":{"sub_type":"hill"}}]}"""),
                "Landmarks")).isNull();
    }

    @Test
    @DisplayName("도구를 호출했는데 전부 null이면 분류 없음(빈 속성)으로 받는다")
    void interpret_allNullIsUnclassifiable() throws Exception {
        PlaceAttributes a = AttributeEnrichment.interpret(message("""
                {"venue":null,"sub_type":null,"visit_style":null,"hike_level":null,"lodging_type":null,"breakfast":null}"""),
                "Travel and Transportation > Ferry Terminal");

        assertThat(a).isNotNull();
        assertThat(a.hasNoClassification()).isTrue();
    }

    @Test
    @DisplayName("요청은 도구 강제 호출 + thinking 끔 + system 캐시")
    @SuppressWarnings("unchecked")
    void requestParams_forcesTool() {
        Map<String, Object> params = AttributeEnrichment.requestParams(place(1L, "한라산", "Landmarks"), "claude-haiku-5-5");

        assertThat(params.get("tool_choice")).isEqualTo(Map.of("type", "tool", "name", AttributeEnrichment.TOOL_NAME));
        assertThat(params.get("thinking")).isEqualTo(Map.of("type", "disabled"));
        assertThat(((List<Map<String, Object>>) params.get("system")).get(0)).containsKey("cache_control");
    }

    @Test
    @DisplayName("v2 결과 반영: 분류 컬럼만 SQL로 쓰고 엔티티 save·임베딩 초기화는 하지 않는다, 잘린 응답은 실패")
    void applyResults_v2_writesClassificationOnly() {
        Place ok = place(1L, "새별오름", "Landmarks and Outdoors > Tourist Attraction");
        Place none = place(2L, "제주항", "Travel and Transportation > Ferry Terminal");
        Place cut = place(3L, "어딘가", "Landmarks and Outdoors > Tourist Attraction");
        String jsonl = String.join("\n",
                line(1, """
                        {"venue":null,"sub_type":"hill","visit_style":"STOP","hike_level":"EASY","lodging_type":null,"breakfast":null}"""),
                line(2, """
                        {"venue":null,"sub_type":null,"visit_style":null,"hike_level":null,"lodging_type":null,"breakfast":null}"""),
                """
                {"custom_id":"place_3","result":{"type":"succeeded","message":{"stop_reason":"max_tokens","content":[]}}}""");

        BatchEnrichScheduler.ChunkResult result = scheduler.applyResults(jsonl, List.of(ok, none, cut));

        assertThat(result.success()).isEqualTo(2);
        assertThat(result.failedIds()).containsExactly(3L);
        verify(placeRepository).writeAttributeClassification(1L,
                "{\"subType\":\"hill\",\"visitStyle\":\"STOP\",\"hikeLevel\":\"EASY\"}", (short) 2);
        verify(placeRepository).writeAttributeClassification(2L, "{}", (short) 2);
        verify(placeRepository, never()).save(any());
        verify(placeRepository, never()).resetEmbeddings(anyList());
        assertThat(ok.getTags()).containsExactly("기존태그");
    }

    @Test
    @DisplayName("분류 JSON에는 TourAPI 원문(official)을 싣지 않는다")
    void classificationJson_excludesOfficial() {
        PlaceAttributes a = new PlaceAttributes(null, "cave", null, null, null, null, Map.of("usetime", "09:00~18:00"));

        assertThat(a.classificationJson()).isEqualTo("{\"subType\":\"cave\"}");
    }

    @Test
    @DisplayName("v2인데 지역이 없으면 아무것도 조회하지 않는다")
    void enrich_v2WithoutRegions_skips() {
        scheduler.enrich();

        verifyNoInteractions(placeRepository);
    }

    private static Place place(Long id, String name, String category) {
        return Place.builder()
                .id(id).name(name).address("제주시").category(category).region("Jeju").country("KR")
                .latitude(BigDecimal.valueOf(33.4)).longitude(BigDecimal.valueOf(126.5))
                .tags(new java.util.ArrayList<>(List.of("기존태그")))
                .savedAt(OffsetDateTime.now()).active(true)
                .build();
    }
}
