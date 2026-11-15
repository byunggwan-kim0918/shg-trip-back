package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.entity.PlaceAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 식당 대표 요리의 주재료만 기록한다. 보강 태그는 주재료와 요리 형태(탕·국밥·면요리)를 한 갈래로만 적어
 * 설렁탕·돈카츠 같은 식당의 고기 종류가 남지 않는다.
 */
final class IngredientEnrichment {

    private IngredientEnrichment() {}

    static final String TOOL_NAME = "record_main_ingredients";
    private static final int MAX_TOKENS = 200;
    private static final int MAX_DESCRIPTION_CHARS = 300;
    /** TourAPI 음식점 원문 중 대표 메뉴 필드. {@code PlaceRepository.writeTourApiDetail}이 같은 필드로 주재료 삭제를 판단한다. */
    private static final List<String> MENU_FIELDS = List.of("firstmenu", "treatmenu");

    static final String SYSTEM_PROMPT = """
            여행 일정 서비스가 돼지고기·소고기·해산물 같은 못 먹는 음식이 있는 사람에게서 그 음식을 주로 파는 식당을 빼려고
            식당의 주재료를 기록한다. 장소 정보를 읽고 %s 도구로 이 식당 대표 요리의 주재료를 고른다.
            반찬·곁들이 메뉴는 넣지 않는다. 국물이 요리의 중심이면(설렁탕, 돈코츠 라멘) 국물 재료도 넣는다.
            이름·태그·메뉴에 드러난 요리의 재료만 고른다. 드러나지 않은 부메뉴(튀김·사이드)를 짐작해 더하지 않는다.

              PORK 돼지고기: 삼겹살·족발·보쌈·돼지국밥·감자탕·순대국·제육·돈카츠·돈코츠 라멘·루러우판
              BEEF 소고기: 한우구이·불고기·갈비탕·설렁탕·곰탕·육개장·육회·우육면·규카츠·스테이크
              CHICKEN 닭고기: 치킨·닭갈비·닭한마리·삼계탕·백숙·야키토리·가라아게
              DUCK 오리고기 / LAMB 양고기(양꼬치) / OTHER_MEAT 위에 없는 고기만(말고기·염소·사슴)
              SEAFOOD 생선·조개·게·새우·오징어·문어·낙지·장어: 회·초밥·해물탕·짬뽕·생선구이·조개구이·어묵·타코야키

            갈비집·고깃집처럼 돼지와 소를 함께 파는 곳이 흔하면 둘 다 고른다.
            고기가 들어가는 것이 기본인 요리는 고기 종류가 드러나지 않아도 흔히 쓰는 재료를 고른다
            (한국 고깃집·막창·곱창은 돼지와 소, 반미·핫도그는 돼지, 버거는 소, 타코(타케리아)는 돼지·소·닭, 케밥은 양·닭·소).
            이름의 한자·현지어도 본다(魚·鮨 생선, 豚 돼지, 牛 소, 鶏·鳥 닭, 鴨 오리, 羊 양). 민물고기(추어탕·장어)도 SEAFOOD다.
            고기·해산물이 주재료가 아닌 식당(비빔밥·두부·채식·디저트)과 이름·카테고리·태그·메뉴에서 근거를 찾을 수 없는
            식당은 빈 배열이다. 그럴듯하게 추측해 채우지 않는다.
            """.formatted(TOOL_NAME);

    static Map<String, Object> toolDefinition() {
        Map<String, Object> ingredients = Map.of(
                "type", "array",
                "items", Map.of("type", "string", "enum", PlaceAttributes.INGREDIENTS.stream().sorted().toList()));
        return Map.of(
                "name", TOOL_NAME,
                "description", "식당 대표 요리의 주재료를 기록한다. 근거가 없으면 빈 배열.",
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of("ingredients", ingredients),
                        "required", List.of("ingredients"),
                        "additionalProperties", false));
    }

    static Map<String, Object> requestParams(Place place, String model) {
        return ForcedToolCall.params(model, MAX_TOKENS, SYSTEM_PROMPT, toolDefinition(), userMessage(place));
    }

    static String userMessage(Place place) {
        StringBuilder sb = new StringBuilder("식당 정보:\n");
        sb.append("- 이름: ").append(place.getName()).append('\n');
        if (place.getNameKo() != null && !place.getNameKo().isBlank()) {
            sb.append("- 한국어 이름: ").append(place.getNameKo()).append('\n');
        }
        if (place.getCategory() != null) sb.append("- 카테고리: ").append(place.getCategory()).append('\n');
        if (place.getRegion() != null) sb.append("- 지역: ").append(place.getRegion()).append('\n');
        if (place.getTags() != null && !place.getTags().isEmpty()) {
            sb.append("- 태그: ").append(String.join(", ", place.getTags())).append('\n');
        }
        Map<String, String> official = place.getAttributes() == null ? null : place.getAttributes().official();
        if (official != null) {
            for (String field : MENU_FIELDS) {
                String menu = official.get(field);
                if (menu != null && !menu.isBlank()) sb.append("- 대표 메뉴: ").append(menu.strip()).append('\n');
            }
        }
        String desc = place.getDescription();
        if (desc != null && !desc.isBlank()) {
            sb.append("- 설명: ")
                    .append(desc.length() > MAX_DESCRIPTION_CHARS ? desc.substring(0, MAX_DESCRIPTION_CHARS) : desc)
                    .append('\n');
        }
        return sb.toString();
    }

    /** 결과를 다시 반영할 때 그사이 입력이 바뀐 장소에 옛 답을 쓰지 않도록 custom_id에 붙이는 요청 입력 지문. */
    static String inputKey(Place place) {
        return Integer.toHexString(userMessage(place).hashCode());
    }

    /**
     * 도구 호출이 없거나 max_tokens에서 잘렸으면 null이다. 다음 실행에서 다시 잡힌다.
     * 빈 목록은 "물었고 고를 것이 없다"는 답이라 저장해서 다시 과금되지 않게 한다.
     */
    static List<String> interpret(JsonNode message) {
        JsonNode input = ForcedToolCall.input(message, TOOL_NAME);
        if (input == null || !input.path("ingredients").isArray()) return null;
        List<String> values = new ArrayList<>();
        for (JsonNode item : input.get("ingredients")) {
            if (item != null && item.isTextual()) values.add(item.asText());
        }
        return PlaceAttributes.pickIngredients(values);
    }
}
