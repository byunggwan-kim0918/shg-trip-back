package com.shg.trip.shgtrip.domain.planning.service;

import java.util.List;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 이야기 문장의 일차 표현을 실제 일차로 고친다. 프롬프트에 일차·전체 일수를 줘도 모델이 "둘째 날"·"마지막 날"을 틀리게 쓴다.
 * 그날의 시간을 여는 자리("둘째 날 아침,", "2일차 일정은")만 고친다. 다른 날을 돌아보거나 미리 말하는 문장은 맞는 표현이다.
 */
final class StoryDayWords {

    private StoryDayWords() {
    }

    private static final List<String> ORDINALS = List.of("첫째", "둘째", "셋째", "넷째", "다섯째", "여섯째", "일곱째",
            "여덟째", "아홉째", "열째");
    private static final List<String> COUNTED = List.of("첫날", "이틀째", "사흘째", "나흘째", "닷새째", "엿새째", "이레째",
            "여드레째", "아흐레째", "열흘째");

    private static final Pattern ORDINAL_DAY = Pattern.compile("(" + String.join("|", ORDINALS) + ") ?날");
    private static final Pattern COUNTED_DAY = Pattern.compile(String.join("|", COUNTED));
    private static final Pattern NUMBERED_DAY = Pattern.compile("(?<!\\d)(\\d{1,2})일차");
    private static final Pattern LAST_DAY = Pattern.compile("마지막 ?날");
    private static final String TIMES = "아침|오전|점심|오후|저녁|밤";
    private static final Pattern OPENS_THE_DAY = Pattern.compile(
            "\\s+(" + TIMES + "|일정|여정|하루)|\\s*[,，]|[은는] |의 ?(" + TIMES + "|일정|여정|하루|시작)");
    private static final Pattern OTHER_DAY = Pattern.compile("오늘|내일|모레|다음 ?날|어제|전날|그제|그저께");
    private static final Pattern RECALL_AFTER = Pattern.compile("^[^.!?。]{0,15}(던|었|았|였|했)");
    // "저녁에 먹은 곳"처럼 시간어 뒤 지난 행동은 그날을 돌아보는 말이다. "한적한·맑은" 같은 형용사와 같은 꼴이라 동사만 본다.
    private static final Pattern RECALL_ADNOMINAL = Pattern.compile("^\\s+(" + TIMES + ")에?\\s+([가-힣]+(게|히)\\s+)?"
            + "(먹은|본|간|들른|탄|머문|묵은|걸은|만난|즐긴|맛본|다녀온|찾은|쉰|산책한|구경한|방문한|들어간|올라간)\\s");

    /**
     * @param day       이 문장이 붙는 스텝의 일차(1부터)
     * @param totalDays 여행 전체 일수. 모르면 0이고 "마지막 날"은 고치지 않는다
     */
    static String fix(String text, int day, int totalDays) {
        if (text == null || day < 1 || day > ORDINALS.size()) return text;
        String ordinalDay = ORDINALS.get(day - 1) + " 날";
        String out = replace(ORDINAL_DAY, text, m -> ORDINALS.indexOf(m.group(1)) + 1, day, ordinalDay);
        out = replace(COUNTED_DAY, out, m -> COUNTED.indexOf(m.group()) + 1, day, COUNTED.get(day - 1));
        out = replace(NUMBERED_DAY, out, m -> Integer.parseInt(m.group(1)), day, day + "일차");
        if (totalDays > 0) out = replace(LAST_DAY, out, m -> totalDays, day, ordinalDay);
        return out;
    }

    private static String replace(Pattern pattern, String text, ToIntFunction<Matcher> mentionedDay, int day,
                                  String replacement) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            boolean wrong = mentionedDay.applyAsInt(m) != day && opensTheDay(text, m);
            m.appendReplacement(sb, Matcher.quoteReplacement(wrong ? replacement : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // 같은 문장에 오늘·내일 같은 말이 있으면 일차 표현은 다른 날을 가리킨다.
    private static boolean opensTheDay(String text, Matcher m) {
        String after = text.substring(m.end());
        return !OTHER_DAY.matcher(sentenceAround(text, m)).find()
                && OPENS_THE_DAY.matcher(after).lookingAt()
                && !RECALL_AFTER.matcher(after).find()
                && !RECALL_ADNOMINAL.matcher(after).find();
    }

    private static String sentenceAround(String text, Matcher m) {
        int start = m.start();
        while (start > 0 && ".!?。\n".indexOf(text.charAt(start - 1)) < 0) start--;
        int end = m.end();
        while (end < text.length() && ".!?。\n".indexOf(text.charAt(end)) < 0) end++;
        return text.substring(start, end);
    }
}
