package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.batch.PlacePairJudge.PairPlace;
import com.shg.trip.shgtrip.domain.place.batch.PlacePairJudge.Relation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlacePairJudgeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final PairPlace TOWER = new PairPlace(308559L, "부산타워", null,
            "Landmarks and Outdoors > Tourist Attraction", "tourapi", "부산광역시 중구 용두산길 37-30 (광복동2가)", "Q490777");
    private static final PairPlace DIAMOND = new PairPlace(1734764L, "부산다이아몬드타워", "부산다이아몬드타워",
            "Landmarks and Outdoors > Scenic Lookout", "foursquare", null, null);

    @Test
    @DisplayName("요청 문장은 거리와 두 장소의 이름·카테고리·출처·주소·Wikidata 항목이고, 같은 한국어 이름과 빈 칸은 뺀다")
    void userMessage() {
        assertThat(PlacePairJudge.userMessage(TOWER, DIAMOND, 33)).isEqualTo("""
                두 장소의 거리: 33m
                A
                - 이름: 부산타워
                - 카테고리: Landmarks and Outdoors > Tourist Attraction
                - 출처: 관광공사
                - 주소: 부산광역시 중구 용두산길 37-30 (광복동2가)
                - Wikidata: Q490777
                B
                - 이름: 부산다이아몬드타워
                - 카테고리: Landmarks and Outdoors > Scenic Lookout
                - 출처: Foursquare
                """);
    }

    @Test
    @DisplayName("시스템 프롬프트는 라벨 표본으로 고른 판정 버전의 문장과 같다")
    void systemPromptIsTheMeasuredVersion() throws Exception {
        String measured = new String(getClass().getResourceAsStream(
                "/place-pair/system-prompt-v" + PlacePairJudge.VERSION + ".txt").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);

        assertThat(PlacePairJudge.SYSTEM_PROMPT).isEqualTo(measured);
    }

    @Test
    @DisplayName("입력 지문은 이름·거리가 바뀌면 달라진다")
    void inputKey() {
        String key = PlacePairJudge.inputKey(TOWER, DIAMOND, 33);

        assertThat(PlacePairJudge.inputKey(TOWER, DIAMOND, 33)).isEqualTo(key);
        assertThat(PlacePairJudge.inputKey(TOWER, DIAMOND, 34)).isNotEqualTo(key);
        assertThat(PlacePairJudge.inputKey(new PairPlace(308559L, "부산 타워", null, TOWER.category(), "tourapi",
                TOWER.address(), "Q490777"), DIAMOND, 33)).isNotEqualTo(key);
    }

    @Test
    @DisplayName("요청은 도구 강제 호출 + thinking 끔 + system 캐시, 고를 수 있는 관계는 넷이다")
    @SuppressWarnings("unchecked")
    void requestParams() {
        Map<String, Object> params = PlacePairJudge.requestParams(TOWER, DIAMOND, 33, "claude-haiku-5-5");

        assertThat(params.get("tool_choice")).isEqualTo(Map.of("type", "tool", "name", PlacePairJudge.TOOL_NAME));
        assertThat(params.get("thinking")).isEqualTo(Map.of("type", "disabled"));
        assertThat(((List<Map<String, Object>>) params.get("system")).get(0).get("text"))
                .isEqualTo(PlacePairJudge.SYSTEM_PROMPT);
        assertThat(PlacePairJudge.SYSTEM_PROMPT).contains(PlacePairJudge.TOOL_NAME).doesNotContain("%s");
        Map<String, Object> schema = (Map<String, Object>) PlacePairJudge.toolDefinition().get("input_schema");
        Map<String, Object> relation = (Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get("relation");
        assertThat((List<String>) relation.get("enum"))
                .containsExactly("SAME", "A_PART_OF_B", "B_PART_OF_A", "DIFFERENT");
    }

    @Test
    @DisplayName("A는 id가 작은 쪽이라 A_PART_OF_B는 낮은 id가 높은 id에 딸린 것이고, 잘렸거나 모르는 값은 실패(null)")
    void interpret() throws Exception {
        assertThat(PlacePairJudge.interpret(answer("SAME"))).isEqualTo(Relation.SAME);
        assertThat(PlacePairJudge.interpret(answer("A_PART_OF_B"))).isEqualTo(Relation.LOW_IN_HIGH);
        assertThat(PlacePairJudge.interpret(answer("B_PART_OF_A"))).isEqualTo(Relation.HIGH_IN_LOW);
        assertThat(PlacePairJudge.interpret(answer("DIFFERENT"))).isEqualTo(Relation.DIFFERENT);
        assertThat(PlacePairJudge.interpret(answer("MAYBE"))).isNull();
        assertThat(PlacePairJudge.interpret(objectMapper.readTree("""
                {"stop_reason":"max_tokens","content":[{"type":"tool_use","name":"record_place_relation","input":{"relation":"SAME"}}]}""")))
                .isNull();
        assertThat(PlacePairJudge.interpret(objectMapper.readTree("""
                {"content":[{"type":"tool_use","name":"record_main_ingredients","input":{"relation":"SAME"}}]}""")))
                .isNull();
    }

    private com.fasterxml.jackson.databind.JsonNode answer(String relation) throws Exception {
        return objectMapper.readTree("""
                {"content":[{"type":"tool_use","name":"record_place_relation","input":{"relation":"%s"}}]}"""
                .formatted(relation));
    }
}
