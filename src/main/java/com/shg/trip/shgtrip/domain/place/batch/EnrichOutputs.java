package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 태그·설명 보강(v1) 응답에서 식당·관광지 규칙이 함께 쓰는 해석. */
@Slf4j
final class EnrichOutputs {

    private EnrichOutputs() {}

    static final Set<String> TIME_SLOTS = Set.of("morning", "afternoon", "evening", "night", "all_day");

    /** 응답의 텍스트 블록을 이은 글. 텍스트가 없으면 null. */
    static String extractText(JsonNode message) {
        if (message == null) return null;
        JsonNode contentArray = message.get("content");
        if (contentArray == null || !contentArray.isArray()) return null;
        StringBuilder text = new StringBuilder();
        for (JsonNode block : contentArray) {
            if ("text".equals(block.path("type").asText())) text.append(block.get("text").asText());
        }
        return text.isEmpty() ? null : text.toString();
    }

    /** 코드 블록(```json ... ```)과 순수 JSON을 모두 받는다. */
    static String extractJson(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastBackticks = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastBackticks > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastBackticks).trim();
            }
        }
        int jsonStart = trimmed.indexOf('{');
        int jsonEnd = trimmed.lastIndexOf('}');
        return jsonStart >= 0 && jsonEnd > jsonStart ? trimmed.substring(jsonStart, jsonEnd + 1) : trimmed;
    }

    static List<String> textList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        for (JsonNode item : node) {
            if (item != null && !item.isNull()) out.add(item.asText());
        }
        return out;
    }

    /** "#"·앞뒤 공백을 뗀 태그. 비었거나 maxLength보다 길면 null. */
    static String normalizeTag(String raw, int maxLength) {
        if (raw == null) return null;
        String tag = raw.strip();
        while (tag.startsWith("#")) tag = tag.substring(1).strip();
        if (tag.isEmpty() || tag.length() > maxLength) return null;
        return tag;
    }

    /** 허용 시간대만 남긴다. 모르는 값(역할 이름 등)은 시간대 판정에서 낮·저녁 어느 쪽에도 걸리지 않아 버린다. */
    static List<String> validSlots(List<String> modelSlots) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : modelSlots) {
            if (raw == null) continue;
            String slot = raw.strip().toLowerCase(Locale.ROOT);
            if (TIME_SLOTS.contains(slot)) out.add(slot);
        }
        return List.copyOf(out);
    }

    /** traveler_fame 규칙. 국내·해외 관광지·식당 프롬프트가 같은 기준을 쓴다(유명도 점수가 국내외에서 같은 눈금이 된다). */
    static String fameRule() {
        return """
                traveler_fame은 여행자 사이에서 알려진 정도다.
                - 2: 이 도시 여행 가이드북의 주요 명소·맛집, 미쉐린 스타 식당, 이 도시에 가는 여행자 대부분이 들르는 곳. \
                한 도시에 수십 곳 정도다(경복궁, 불국사, 자금성, 센소지, 스린 야시장, 니시키 시장, 하노이 문묘, 딘타이펑 신이 본점).
                - 1: 현지에서 이름난 곳(오래된 노포, 지역 대표 맛집·명소, 여행 후기에 자주 나오는 곳).
                - 0: 그 밖의 평범한 곳. 체인점은 본점·대표 지점만 1 이상이다.
                - 모르는 장소면 0이다.""";
    }

    /** 0~2 정수만 받는다. 그 밖은 모름(null)이다. */
    static Short fame(JsonNode node) {
        if (node == null || !node.isIntegralNumber()) return null;
        int v = node.asInt();
        return v >= 0 && v <= 2 ? (short) v : null;
    }

    private static final String FOREIGN_SCRIPT = "[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]";
    private static final Pattern FOREIGN_SCRIPT_RUN = Pattern.compile(FOREIGN_SCRIPT + "+");
    /** 한글 낱말에 붙은 한자·가나("适합니다"). 괄호로 병기한 한자는 한글과 붙지 않아 걸리지 않는다. */
    private static final Pattern FOREIGN_SCRIPT_IN_WORD = Pattern.compile(
            "[가-힣]" + FOREIGN_SCRIPT + "|" + FOREIGN_SCRIPT + "[가-힣]");

    /** 장소 이름에 든 한자·가나("세이천洗耳泉")는 빼고 본다. 이름을 따라 쓴 설명까지 막으면 그 장소는 매번 실패한다. */
    static boolean mixesForeignScript(String text, String placeName) {
        if (text == null) return false;
        String checked = text;
        if (placeName != null) {
            Matcher run = FOREIGN_SCRIPT_RUN.matcher(placeName);
            while (run.find()) checked = checked.replace(run.group(), "");
        }
        return FOREIGN_SCRIPT_IN_WORD.matcher(checked).find();
    }

    /** 국내 장소 설명에 한자·가나가 섞였는지. 해외 장소 설명은 현지어 이름에 조사가 붙어("金閣寺는") 판정하지 않는다. */
    static boolean rejectsDescription(long placeId, String placeName, boolean overseas, String description) {
        if (overseas || !mixesForeignScript(description, placeName)) return false;
        log.warn("국내 장소 설명에 한자·가나 섞임 - placeId={}", placeId);
        return true;
    }
}
