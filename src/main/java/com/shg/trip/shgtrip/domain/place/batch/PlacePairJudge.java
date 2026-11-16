package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 출처마다 같은 곳이 다른 언어·옛 이름으로 따로 있어 이름 규칙으로는 못 잡는다. 보강 설명은 자동으로 쓴 글이라
 * 판단을 흐려 넣지 않는다.
 */
final class PlacePairJudge {

    private PlacePairJudge() {}

    static final String TOOL_NAME = "record_place_relation";
    /** 프롬프트·도구·모델을 바꾸면 올린다. 입력 지문에 들어가 남은 판정을 모두 다시 묻게 한다. */
    static final int VERSION = 1;
    private static final int MAX_TOKENS = 60;
    private static final Map<String, String> SOURCE_LABELS = Map.of(
            "tourapi", "관광공사", "foursquare", "Foursquare", "wikidata", "Wikidata", "google", "Google",
            "gateway", "관문");

    enum Relation { SAME, LOW_IN_HIGH, HIGH_IN_LOW, DIFFERENT }

    /** 두 장소 중 id가 작은 쪽을 A로 보여 준다. */
    private enum Answer {
        SAME(Relation.SAME), A_PART_OF_B(Relation.LOW_IN_HIGH), B_PART_OF_A(Relation.HIGH_IN_LOW),
        DIFFERENT(Relation.DIFFERENT);

        final Relation relation;

        Answer(Relation relation) {
            this.relation = relation;
        }
    }

    static final String SYSTEM_PROMPT = """
            여행 일정 서비스가 같은 장소를 일정에 두 번 넣지 않도록, 서로 가까이 있는 두 장소 기록이 같은 곳인지 판단한다.
            장소 기록은 출처(관광공사·Foursquare·Wikidata)가 달라 같은 곳이 다른 언어·옛 이름·별칭으로 따로 있거나, 큰 장소 안의 시설이 따로 있다.
            %s 도구로 둘의 관계 하나를 고른다.

            SAME: 같은 장소다. 언어·표기·옛 이름·별칭만 다르다(부산타워와 부산다이아몬드타워, 深澳象鼻岩과 Shen'ao Elephant Trunk Rock, 같은 호텔의 한글·영문 이름). 탑·등대·건물과 그 전망대처럼 하나를 보면 곧 다른 하나를 보는 것, 같은 숙소의 본관과 별관도 SAME이다.
            지점 이름: 한쪽 이름에만 지점 표시(본점, 강남점, ○○店)가 붙고 수십 m 안이면 같은 가게라 SAME이다. 두 이름에 서로 다른 지점 표시가 붙어 있으면(강남점과 역삼점) 다른 지점이라 DIFFERENT다.
            A_PART_OF_B 또는 B_PART_OF_A: 한쪽이 분명히 다른 쪽에 딸린 일부라 큰 쪽을 방문하면 따로 시간을 들이지 않고 함께 보게 된다. 절의 문·탑·전각, 성의 천수각·정원, 공원 안 동상·조형물·광장·무대, 동물원 안 전시관, 쇼핑몰의 별관과 안의 매장, 백화점 안 매장, 시장 안 가게. 큰 쪽 이름이나 주소가 작은 쪽에 드러날 때 고른다.
            DIFFERENT: 다른 장소다. 이웃한 다른 가게·건물, 같은 큰 장소 안의 서로 다른 두 시설(같은 백화점의 두 매장, 같은 절의 두 전각, 같은 공원의 두 시설), 같은 체인의 다른 지점, 큰 장소 안에 있어도 표를 따로 사거나 따로 시간을 들여 보는 독립 시설(공원 안의 타워, 쇼핑몰 안 수족관·테마파크, 공원 안 박물관·야구장·캠핑장).

            같은 주소나 같은 Wikidata 항목만으로 같은 곳이나 일부로 보지 않는다. 주소는 큰 장소의 대표 주소를 같이 쓰고, Wikidata 항목은 근처 다른 장소에 잘못 붙어 있기도 하다. 설명은 자동으로 쓴 글이라 틀릴 수 있으니 이름·카테고리를 먼저 본다. 확신이 없으면 DIFFERENT를 고른다.
            """.formatted(TOOL_NAME);

    /** 주소는 라벨 표본과 같게 앞 80자다({@code PlaceLinkRepository.findPlaceRows}). */
    record PairPlace(long id, String name, String nameKo, String category, String source, String address,
                     String wikidataId) {
    }

    static Map<String, Object> toolDefinition() {
        return Map.of(
                "name", TOOL_NAME,
                "description", "두 장소 기록의 관계를 기록한다.",
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of("relation", Map.of(
                                "type", "string",
                                "enum", Arrays.stream(Answer.values()).map(Enum::name).toList())),
                        "required", List.of("relation"),
                        "additionalProperties", false));
    }

    static Map<String, Object> requestParams(PairPlace low, PairPlace high, int distanceMeters, String model) {
        return ForcedToolCall.params(model, MAX_TOKENS, SYSTEM_PROMPT, toolDefinition(),
                userMessage(low, high, distanceMeters));
    }

    static String userMessage(PairPlace low, PairPlace high, int distanceMeters) {
        return "두 장소의 거리: " + distanceMeters + "m\nA\n" + side(low) + "\nB\n" + side(high) + "\n";
    }

    /** 남은 판정을 다시 쓸지 정하는 지문. custom_id에도 붙여 결과를 기다리는 사이 입력이 바뀐 쌍의 옛 답을 버린다. */
    static String inputKey(PairPlace low, PairPlace high, int distanceMeters) {
        return Integer.toHexString((VERSION + "|" + userMessage(low, high, distanceMeters)).hashCode());
    }

    /** 도구 호출이 없거나 잘렸거나 허용 값이 아니면 null이다. */
    static Relation interpret(JsonNode message) {
        JsonNode input = ForcedToolCall.input(message, TOOL_NAME);
        if (input == null) return null;
        String value = input.path("relation").asText("");
        return Arrays.stream(Answer.values()).filter(a -> a.name().equals(value)).map(a -> a.relation)
                .findFirst().orElse(null);
    }

    private static String side(PairPlace p) {
        StringBuilder sb = new StringBuilder("- 이름: ").append(p.name());
        if (p.nameKo() != null && !p.nameKo().isBlank() && !p.nameKo().equals(p.name())) {
            sb.append("\n- 한국어 이름: ").append(p.nameKo());
        }
        if (p.category() != null && !p.category().isBlank()) sb.append("\n- 카테고리: ").append(p.category());
        sb.append("\n- 출처: ").append(SOURCE_LABELS.getOrDefault(p.source(), String.valueOf(p.source())));
        if (p.address() != null && !p.address().isBlank()) sb.append("\n- 주소: ").append(p.address());
        if (p.wikidataId() != null && !p.wikidataId().isBlank()) sb.append("\n- Wikidata: ").append(p.wikidataId());
        return sb.toString();
    }
}
