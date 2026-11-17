package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.shg.trip.shgtrip.domain.place.entity.Place;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 장소 소개에서 평판·권유 문구("현지인들이 즐겨 찾는 맛집")와 한글에 섞인 한자·가나를 걷어 낸다.
 * 소개는 장소 카드에 사실처럼 보이고 선택 프롬프트에도 들어가므로 장소에 대한 정보만 남긴다.
 */
public final class DescriptionRewrite {

    private DescriptionRewrite() {}

    static final String TOOL_NAME = "rewrite_description";
    private static final int MAX_TOKENS = 400;
    /** 짧은 소개를 고칠 때 조사·어미가 바뀌며 늘어나는 정도. */
    private static final int MIN_LENGTH_SLACK = 20;

    /**
     * 평판·권유 문구. 대상 조회 SQL({@code PlaceRepository.findDescriptionRewriteTargets})도 이 식을 그대로 쓴다.
     * "예약 필수"·"함께 찾으면"처럼 사실을 전하는 말은 걸리지 않게 앞뒤 말까지 붙여 본다.
     */
    public static final String REPUTATION_REGEX = "인기|유명|맛집|사랑받|손꼽|소문난|입소문|명성|호평|핫플|추천|대표적인|명소로"
            + "|필수 ?(?:코스|방문|명소|관광|여행|장소)|꼭 (?:가 ?봐야|들러야|방문해야)"
            + "|(?:널리|잘) 알려|(?:즐겨|자주|많이|꾸준히|함께) 찾는|많은 [^.]{0,15}[이가] 찾";
    private static final Pattern REPUTATION = Pattern.compile(REPUTATION_REGEX);

    private static final String REPUTATION_EXAMPLES =
            "인기·유명·맛집·추천·필수 코스·대표적인·명소로·널리 알려진·현지인이 즐겨 찾는·많은 방문객이 찾는·사랑받는";
    static final String NO_REPUTATION = REPUTATION_EXAMPLES + " 같은 평판과 권유는 쓰지 않는다.";

    /** 태그·설명 보강 프롬프트(국내·해외 관광지)에 붙이는 설명 규칙. */
    static final String ENRICH_RULE = "설명 가이드: 어떤 곳인지, 무엇을 보거나 먹거나 할 수 있는지, 주변 풍경과 위치 특징"
            + "(해변 앞, 시장 안, 산 중턱)을 쓴다. " + NO_REPUTATION + " 확실하지 않은 내용은 지어내지 않는다.";

    static final String SYSTEM_PROMPT = """
            여행 일정 서비스가 장소 카드에 보여 주는 한국어 소개를 고친다. 기존 설명을 읽고 %s 도구로 고친 소개를 기록한다.
            - 어떤 곳인지, 무엇을 보거나 먹거나 할 수 있는지, 주변 풍경과 위치 특징(해변 앞, 시장 안, 산 중턱)처럼
              장소에 대한 정보만 남긴다.
            - %s 같은 평판과 권유는 지운다. 다른 낱말로 바꿔 같은 뜻을 남기지 않는다.
            - 평판 구절만 지우고 같은 문장에 있는 사실(가격대·시설·풍경)은 남긴다.
            - 기존 설명에 있는 내용만 쓴다. 풍경·메뉴·역사·이름 읽는 법을 새로 지어내지 않는다.
            - 카테고리는 남길 정보가 없을 때만 쓴다. 기존 설명의 업종 표현을 카테고리로 바꾸거나 덧붙이지 않는다.
            - 한국어 1~3문장이고 기존 설명보다 길지 않다. 문장은 기존 설명처럼 "~입니다", "~습니다"로 끝낸다.
            - 기존 설명에 없는 괄호 표기와 이름 읽는 법은 더하지 않는다. 한자·가나는 기존 설명이나 이름에 있을 때만 쓴다.
            - 남길 정보가 없으면 장소 종류와 지역만으로 한 문장을 쓴다.
            """.formatted(TOOL_NAME, REPUTATION_EXAMPLES);

    static boolean needsRewrite(Place place) {
        String description = place.getDescription();
        if (description == null || description.isBlank()) return false;
        return hasReputation(description, place) || (!OverseasEnrichment.isOverseas(place.getCountry())
                && BatchEnrichScheduler.mixesForeignScript(description, place.getName()));
    }

    /** 장소 이름에 든 낱말("소문난 국밥", "명성교회")은 평판으로 보지 않는다. 이름은 고쳐 쓸 수 없어 매번 실패한다. */
    static boolean hasReputation(String text, Place place) {
        if (text == null) return false;
        Matcher matcher = REPUTATION.matcher(text);
        while (matcher.find()) {
            if (!inName(matcher.group(), place)) return true;
        }
        return false;
    }

    private static boolean inName(String word, Place place) {
        return (place.getName() != null && place.getName().contains(word))
                || (place.getNameKo() != null && place.getNameKo().contains(word));
    }

    static Map<String, Object> toolDefinition() {
        return Map.of(
                "name", TOOL_NAME,
                "description", "고친 장소 소개를 기록한다.",
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of("description", Map.of("type", "string")),
                        "required", List.of("description"),
                        "additionalProperties", false));
    }

    static Map<String, Object> requestParams(Place place, String model) {
        return ForcedToolCall.params(model, MAX_TOKENS, SYSTEM_PROMPT, toolDefinition(), userMessage(place));
    }

    static String userMessage(Place place) {
        StringBuilder sb = new StringBuilder("장소 정보:\n");
        sb.append("- 이름: ").append(place.getName()).append('\n');
        if (place.getNameKo() != null && !place.getNameKo().isBlank()) {
            sb.append("- 한국어 이름: ").append(place.getNameKo()).append('\n');
        }
        if (place.getCategory() != null) sb.append("- 카테고리: ").append(place.getCategory()).append('\n');
        if (place.getRegion() != null) sb.append("- 지역: ").append(place.getRegion()).append('\n');
        sb.append("- 기존 설명: ").append(place.getDescription().strip()).append('\n');
        return sb.toString();
    }

    static String inputKey(Place place) {
        return Integer.toHexString(userMessage(place).hashCode());
    }

    /**
     * 고친 소개. 도구 호출이 없거나 잘렸거나, 평판 문구·섞인 글자가 남았거나, 기존 소개보다 눈에 띄게 길거나 괄호가
     * 늘었으면 null이라 다음 실행에서 다시 묻는다. 길어지거나 괄호가 는 소개는 기존 설명에 없던 내용을 지어낸 것이다.
     */
    static String interpret(JsonNode message, Place place) {
        JsonNode input = ForcedToolCall.input(message, TOOL_NAME);
        if (input == null || !input.path("description").isTextual()) return null;
        String text = input.get("description").asText().strip();
        if (text.isEmpty() || hasReputation(text, place) || longerThanOriginal(text, place)
                || addsParentheses(text, place)) {
            return null;
        }
        if (!OverseasEnrichment.isOverseas(place.getCountry())
                && BatchEnrichScheduler.mixesForeignScript(text, place.getName())) {
            return null;
        }
        return text;
    }

    // 괄호가 늘면 원문에 없던 읽는 법·병기를 붙인 것이다. 일본 고유명사는 읽는 법이 여럿이라 틀리기 쉽다.
    private static boolean addsParentheses(String text, Place place) {
        return text.chars().filter(c -> c == '(').count()
                > (place.getDescription() == null ? 0 : place.getDescription().chars().filter(c -> c == '(').count());
    }

    private static boolean longerThanOriginal(String text, Place place) {
        int original = place.getDescription() == null ? 0 : place.getDescription().strip().length();
        return text.length() > original + Math.max(MIN_LENGTH_SLACK, original / 5);
    }
}
