package com.shg.trip.shgtrip.domain.place.batch;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TourAPI detailIntro2의 이용시간·쉬는날·요금 텍스트를 일정 로직이 읽는 형식으로 바꾼다.
 *
 * <p>출력은 {@code OpeningHoursText}가 읽는 "월요일: 09:00 ~ 18:00, 화요일: 휴무, …" 형식이다.
 * 판독에 확신이 없으면 null을 돌려 "열림 가정"으로 남긴다. 틀리더라도 닫힌 쪽이 아니라 열린 쪽으로
 * 틀리게 한다 — 멀쩡한 장소를 휴무로 빼는 것이 영업시간을 모르는 것보다 나쁘다.
 *
 * <ul>
 *   <li>시간: 본문의 시각 구간 중 가장 이른 시작 ~ 가장 늦은 종료(준비시간 구간 제외). 계절·요일·부속시설별
 *       시간이 섞여 있어도 그 바깥은 확실히 닫혀 있다는 것만 쓴다. 익일·오전/오후 표기는 구간을 다 읽지
 *       못하므로 시간을 비운다.</li>
 *   <li>휴무: 조건·계절·반일 단서가 없는 "매주 X요일"과, 조각 전체가 "X요일 (휴무)"인 경우만.
 *       "첫째 주"·"격주"·공휴일·명절은 달력이 없어 무시한다.</li>
 * </ul>
 */
final class OfficialHoursParser {

    private OfficialHoursParser() {}

    private static final List<String> DAYS = List.of("월", "화", "수", "목", "금", "토", "일");
    private static final int DAY_MINUTES = 24 * 60;

    private static final Pattern RANGE = Pattern.compile(
            "(\\d{1,2})\\s*:\\s*(\\d{2})\\s*[~∼〜\\-–]\\s*(\\d{1,2})\\s*:\\s*(\\d{2})");
    /** 이 단어 바로 뒤의 구간은 영업 구간이 아니다. */
    private static final Pattern BREAK_LABEL = Pattern.compile("(준비\\s*시간|브레이크\\s*타임|break\\s*time)\\s*[:：]?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ALWAYS_OPEN = Pattern.compile("상시\\s*개방|24\\s*시간");
    /** 시각 구간을 HH:MM~HH:MM만으로는 다 읽을 수 없는 표기("17:00~익일 02:00", "오후 1:00~5:00"). */
    private static final Pattern UNREADABLE_TIME = Pattern.compile("익일|다음\\s*날|새벽|오전|오후|\\b[AP]M\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WEEKLY = Pattern.compile(
            "매주\\s*((?:[월화수목금토일]요일\\s*(?:[,·및과와~∼\\-]\\s*)?)+)");
    private static final Pattern DAY_SPAN = Pattern.compile("([월화수목금토일])요일\\s*[~∼\\-]\\s*([월화수목금토일])요일");
    /** "매주" 앞에 오면 조건부·계절 휴무다("동절기 매주 월요일", "1~2월 매주 월요일"). */
    private static final Pattern CONDITION_BEFORE = Pattern.compile("경우|이면|동절기|하절기|여름|겨울|시즌|기간|\\d\\s*월");
    /** 요일 바로 뒤에 오면 반일 휴무다("매주 월요일 오전 휴무"). */
    private static final Pattern HALF_DAY_AFTER = Pattern.compile("^\\s*(오전|오후)");
    private static final Pattern DAYS_ONLY = Pattern.compile(
            "^\\s*((?:[월화수목금토일]요일\\s*[,·]?\\s*)+)(?:정기\\s*)?(?:휴무|휴관|휴일)?\\s*$");
    private static final Pattern BRACKETED = Pattern.compile("\\([^()]*\\)|\\[[^\\[\\]]*\\]");
    private static final Pattern SEGMENT = Pattern.compile("[/\\n※]");
    private static final Pattern DAY_TOKEN = Pattern.compile("([월화수목금토일])요일");
    private static final Pattern WON = Pattern.compile("([\\d,]+)\\s*원");
    private static final Pattern ADULT_WON = Pattern.compile("성인[^\\d]{0,12}([\\d,]+)\\s*원");
    /** 입장료가 아닌 요금이 섞인 표기. 금액이 무엇의 값인지 가를 수 없어 비운다. */
    private static final Pattern NOT_ADMISSION = Pattern.compile("주차|대여|렌탈|체험|보증금|프로그램|강습|코스");

    /** HTML 태그·엔티티를 걷어 낸 원문. 비면 null. */
    static String clean(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace('\t', ' ')
                .replaceAll("[ \\u00A0]+", " ")
                .replaceAll(" *\\n *", "\n")
                .strip();
        return s.isEmpty() ? null : s;
    }

    /**
     * @return "월요일: 09:00 ~ 18:00, …" 형식, 판독할 것이 없으면 null
     */
    static String toOpeningHours(String useTime, String restDate) {
        String hoursText = clean(useTime);
        String restText = clean(restDate);
        // 두 필드가 뒤바뀐 행이 있다(이용시간에 "매주 월요일", 쉬는날에 "11:00~18:00").
        if (ranges(hoursText).isEmpty() && !ranges(restText).isEmpty()) {
            String t = hoursText;
            hoursText = restText;
            restText = t;
        }

        int[] window = envelope(hoursText);
        Set<Integer> closed = closedDays(restText);
        if (window == null && closed.isEmpty()) return null;

        List<String> parts = new ArrayList<>();
        for (int d = 0; d < DAYS.size(); d++) {
            String value = closed.contains(d) ? "휴무" : window == null ? "영업" : format(window);
            parts.add(DAYS.get(d) + "요일: " + value);
        }
        return String.join(", ", parts);
    }

    /** 영업 창 [시작분, 종료분]. 자정을 넘기면 종료가 1440을 넘는다. 읽을 수 없으면 null. */
    static int[] envelope(String text) {
        if (text == null || UNREADABLE_TIME.matcher(text).find()) return null;
        List<int[]> rs = ranges(text);
        if (ALWAYS_OPEN.matcher(text).find()) {
            // 상시 개방과 시각이 같이 있으면 시각이 부속시설 것인지 본 시설 것인지 모른다
            return rs.isEmpty() ? new int[]{0, DAY_MINUTES} : null;
        }
        if (rs.isEmpty()) return null;
        int start = Integer.MAX_VALUE;
        int end = Integer.MIN_VALUE;
        for (int[] r : rs) {
            start = Math.min(start, r[0]);
            end = Math.max(end, r[1]);
        }
        return end - start > DAY_MINUTES ? new int[]{0, DAY_MINUTES} : new int[]{start, end};
    }

    private static List<int[]> ranges(String text) {
        List<int[]> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = RANGE.matcher(text);
        while (m.find()) {
            String before = text.substring(Math.max(0, m.start() - 12), m.start());
            if (BREAK_LABEL.matcher(before).find()) continue;
            int sh = Integer.parseInt(m.group(1)), sm = Integer.parseInt(m.group(2));
            int eh = Integer.parseInt(m.group(3)), em = Integer.parseInt(m.group(4));
            if (sh > 24 || eh > 24 || sm > 59 || em > 59) continue;
            int s = sh * 60 + sm;
            int e = eh * 60 + em;
            if (e <= s) e += DAY_MINUTES;
            out.add(new int[]{s, e});
        }
        return out;
    }

    /**
     * 매주 쉬는 요일(0=월). 괄호 안 단서("단, 공휴일인 경우 정상 개관")는 떼고, "/"·"※"·줄바꿈 조각마다 본다.
     */
    static Set<Integer> closedDays(String text) {
        Set<Integer> days = new LinkedHashSet<>();
        if (text == null) return days;
        for (String segment : SEGMENT.split(BRACKETED.matcher(text).replaceAll(" "))) {
            Matcher only = DAYS_ONLY.matcher(segment);
            if (only.matches()) {
                addDays(only.group(1), days);
                continue;
            }
            Matcher weekly = WEEKLY.matcher(segment);
            while (weekly.find()) {
                String before = segment.substring(0, weekly.start());
                String after = segment.substring(weekly.end());
                if (CONDITION_BEFORE.matcher(before).find() || HALF_DAY_AFTER.matcher(after).find()) continue;
                addDays(weekly.group(1), days);
            }
        }
        return days;
    }

    /** "화요일, 목요일"은 두 요일, "화요일~목요일"은 그 사이 전부. */
    private static void addDays(String dayList, Set<Integer> days) {
        Matcher span = DAY_SPAN.matcher(dayList);
        while (span.find()) {
            int from = DAYS.indexOf(span.group(1));
            int to = DAYS.indexOf(span.group(2));
            for (int d = from; d != to; d = (d + 1) % DAYS.size()) days.add(d);
            days.add(to);
        }
        Matcher day = DAY_TOKEN.matcher(dayList);
        while (day.find()) days.add(DAYS.indexOf(day.group(1)));
    }

    /**
     * 성인 1인 입장료(원). "무료"만 있으면 0, 성인 요금이 적혀 있으면 그 값, 금액이 하나뿐이면 그 값.
     * 그 밖(여러 금액·권종)은 null.
     */
    static Integer admissionFee(String raw) {
        String text = clean(raw);
        if (text == null || NOT_ADMISSION.matcher(text).find()) return null;
        Matcher adult = ADULT_WON.matcher(text);
        if (adult.find()) return parseWon(adult.group(1));
        Set<Integer> amounts = new LinkedHashSet<>();
        Matcher m = WON.matcher(text);
        while (m.find()) {
            Integer v = parseWon(m.group(1));
            if (v != null) amounts.add(v);
        }
        if (amounts.isEmpty()) return text.contains("무료") ? 0 : null;
        return amounts.size() == 1 && !text.contains("무료") ? amounts.iterator().next() : null;
    }

    private static Integer parseWon(String digits) {
        try {
            int v = Integer.parseInt(digits.replace(",", ""));
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String format(int[] window) {
        return hhmm(window[0]) + " ~ " + hhmm(window[1] > DAY_MINUTES ? window[1] - DAY_MINUTES : window[1]);
    }

    private static String hhmm(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }
}
