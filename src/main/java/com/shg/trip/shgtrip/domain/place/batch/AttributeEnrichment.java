package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.entity.PlaceAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 태그·설명·체류시간은 다시 만들지 않는다. 그 컬럼이 바뀌면 임베딩을 다시 만들어야 하고, 벡터 검색 순위가
 * 같이 흔들려 생성 결과 변화의 원인을 가를 수 없다.
 *
 * <p>영업시간·휴무·요금은 묻지 않는다. 시각과 금액은 TourAPI 공식 원문에서만 가져온다.
 */
final class AttributeEnrichment {

    private AttributeEnrichment() {}

    static final int VERSION = 2;
    static final String TOOL_NAME = "record_place_attributes";
    private static final int MAX_TOKENS = 300;
    private static final int MAX_DESCRIPTION_CHARS = 400;

    static final String SYSTEM_PROMPT = """
            여행 일정 서비스의 장소 분류기다. 장소 정보를 읽고 record_place_attributes 도구로 분류를 기록한다.
            이름·카테고리·주소·설명·태그에서 근거를 찾을 수 없는 필드는 null로 둔다. 그럴듯하게 추측해 채우지 않는다.
            해당하지 않는 종류의 필드(식당의 lodging_type, 숙소의 sub_type 등)도 null이다.

            venue — 식음료 장소만:
              MEAL 한 끼 식사가 되는 식당 / CAFE 커피·차 / BAKERY 빵집 / DESSERT 디저트·빙수·아이스크림·떡
              BAR 술집·펍 / SNACK 분식·길거리 간식·포장 위주 가게

            sub_type — 관광·체험·쇼핑 장소만:
              viewpoint 전망대·경관 포인트 / beach 해변 / trail 산책로·올레길·둘레길 / hill 오름·낮은 동산
              mountain 산(정상 등반) / waterfall 폭포 / cave 동굴 / island 섬 / garden 정원·수목원 / park 공원
              museum 박물관·전시관 / gallery 미술관 / palace 궁궐 / temple 사찰·신사·성당
              historic_site 유적·성곽·고택 / village 마을·한옥마을 / street 거리·골목 / market 시장
              theme_park 테마파크·놀이공원 / zoo_aquarium 동물원·아쿠아리움 / experience 체험·공방·레저
              shopping 쇼핑몰·백화점·면세점 / spa 온천·찜질방 / landmark 랜드마크 건축물 / other 그 밖
            visit_style — 관광 장소만: STOP 내려서 둘러보는 곳 / PASS_THROUGH 차로 지나가며 보는 해안도로·드라이브 코스
            hike_level — 관광 장소만: NONE 걷기 거의 없음 / EASY 왕복 1시간 안쪽 산책·낮은 오름
              LONG 왕복 2시간 이상 등산·트레킹(한라산·설악산 대청봉 등)
            lodging_type — 숙소만: HOTEL / RESORT / PENSION(펜션·풀빌라) / GUESTHOUSE(게스트하우스·민박)
              HOSTEL(다인실) / MOTEL / CAMPING(캠핑·글램핑)
            breakfast — 숙소만: 이름·설명에 조식 제공이 있으면 true, 조식 없음이 적혀 있으면 false, 그 외 null
            """;

    static Map<String, Object> toolDefinition() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("venue", nullableEnum(PlaceAttributes.VENUES));
        props.put("sub_type", nullableEnum(PlaceAttributes.SUB_TYPES));
        props.put("visit_style", nullableEnum(PlaceAttributes.VISIT_STYLES));
        props.put("hike_level", nullableEnum(PlaceAttributes.HIKE_LEVELS));
        props.put("lodging_type", nullableEnum(PlaceAttributes.LODGING_TYPES));
        props.put("breakfast", Map.of("type", List.of("boolean", "null")));
        return Map.of(
                "name", TOOL_NAME,
                "description", "장소 분류를 기록한다. 근거가 없는 필드는 null.",
                "input_schema", Map.of(
                        "type", "object",
                        "properties", props,
                        "required", List.copyOf(props.keySet()),
                        "additionalProperties", false));
    }

    private static Map<String, Object> nullableEnum(Set<String> values) {
        List<Object> options = new ArrayList<>(values.stream().sorted().toList());
        options.add(null);
        return Map.of("type", List.of("string", "null"), "enum", options);
    }

    static Map<String, Object> requestParams(Place place, String model) {
        return Map.of(
                "model", model,
                "max_tokens", MAX_TOKENS,
                // Haiku 5.5는 thinking이 기본으로 켜져 max_tokens를 잡아먹는다
                "thinking", Map.of("type", "disabled"),
                "system", List.of(Map.of(
                        "type", "text",
                        "text", SYSTEM_PROMPT,
                        "cache_control", Map.of("type", "ephemeral"))),
                "tools", List.of(toolDefinition()),
                "tool_choice", Map.of("type", "tool", "name", TOOL_NAME),
                "messages", List.of(Map.of("role", "user", "content", userMessage(place))));
    }

    static String userMessage(Place place) {
        StringBuilder sb = new StringBuilder("장소 정보:\n");
        sb.append("- 이름: ").append(place.getName()).append('\n');
        sb.append("- 카테고리: ").append(place.getCategory()).append('\n');
        if (place.getAddress() != null && !place.getAddress().isBlank()) {
            sb.append("- 주소: ").append(place.getAddress()).append('\n');
        }
        if (place.getRegion() != null) sb.append("- 지역: ").append(place.getRegion()).append('\n');
        if (place.getPlaceRole() != null) sb.append("- 역할: ").append(place.getPlaceRole()).append('\n');
        if (place.getTags() != null && !place.getTags().isEmpty()) {
            sb.append("- 태그: ").append(String.join(", ", place.getTags())).append('\n');
        }
        String desc = place.getDescription();
        if (desc != null && !desc.isBlank()) {
            sb.append("- 설명: ")
                    .append(desc.length() > MAX_DESCRIPTION_CHARS ? desc.substring(0, MAX_DESCRIPTION_CHARS) : desc)
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * 도구 호출이 없거나 max_tokens에서 잘렸으면 null이다. 버전을 찍지 않아 다음 실행에서 다시 잡힌다.
     * 분류가 전부 null이면 빈 속성을 돌려준다. 출력 형식이 강제돼 있어 "분류할 것 없음"이라는 답이고,
     * 실패로 두면 항구·터미널 같은 곳이 매 실행 다시 과금된다.
     *
     * <p>업종은 카테고리가 구체적이면 카테고리 판정을 따르고, "Restaurant" 같은 총칭일 때만 모델 값을 쓴다.
     */
    static PlaceAttributes interpret(JsonNode message, String category) {
        if (message == null || "max_tokens".equals(message.path("stop_reason").asText())) return null;
        JsonNode input = toolInput(message);
        if (input == null || !input.isObject()) return null;
        String venue = text(input, "venue");
        DiningEnrichment.Venue byCategory = DiningEnrichment.venueOf(category);
        if (byCategory != null) venue = byCategory.name();
        return new PlaceAttributes(venue, text(input, "sub_type"), text(input, "visit_style"),
                text(input, "hike_level"), text(input, "lodging_type"),
                input.path("breakfast").isBoolean() ? input.get("breakfast").booleanValue() : null,
                null).normalized();
    }

    private static JsonNode toolInput(JsonNode message) {
        if (message == null) return null;
        for (JsonNode block : message.path("content")) {
            if ("tool_use".equals(block.path("type").asText()) && TOOL_NAME.equals(block.path("name").asText())) {
                return block.get("input");
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
