package com.shg.trip.shgtrip.domain.planning.service;

import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google Places weekdayDescriptions 텍스트("월요일: 오전 11:00 ~ 오후 3:00, 오후 4:30~10:00, 화요일: ...")를
 * 요일별 영업 구간(분 단위)으로 읽는다.
 *
 * <p>기존에는 요일별로 "휴무"라는 단어가 있는지만 봤다. 그래서 <b>16시에 문 여는 식당이 11:30
 * 점심</b>에 배정되는 일을 아무도 막지 못했다(실측: itinerary 76 제주돗, 78 부산가든·아웃닭,
 * 74 와규호르몬, 73 단골집). 여는 시각·닫는 시각까지 읽어야 그걸 막을 수 있다.
 *
 * <p><b>안전 규약</b>: 읽을 수 없으면 "열림"으로 간주한다({@code null} 반환 → 호출부 통과).
 * 형식이 조금 다른 멀쩡한 장소를 통째로 배제하면 지금보다 나빠지기 때문에, 고신뢰로 읽힌
 * 경우에만 판단한다. 이 방침은 기존 {@code isClosedOnDay}와 동일하다.
 *
 * <p><b>단위 규약</b>: 분. 자정을 넘기는 영업(예: 15:00~익일 02:00)은 종료가 1440을 넘는
 * 값(26:00 = 1560)으로 표현한다.
 */
final class OpeningHoursText {

    private OpeningHoursText() {}

    /** 하루 분량(분). 자정 넘김 보정의 기준이다. */
    private static final int DAY_MINUTES = 24 * 60;

    private static final List<String> KO_DAYS =
            List.of("월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일");

    /** 요일 라벨 앞에서 문자열을 자르기 위한 경계. 쉼표로 자르면 브레이크 타임이 다음 요일에 붙는다. */
    private static final Pattern DAY_BOUNDARY = Pattern.compile(
            "(?=(?:월|화|수|목|금|토|일)요일\\s*:)"
                    + "|(?=(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\\s*:)",
            Pattern.CASE_INSENSITIVE);

    /**
     * 시각 토큰. 앞에 한국어 오전/오후가 붙거나 뒤에 영어 AM/PM이 붙는다(둘 다 없을 수도 있다).
     * 분은 ":30" 또는 "시 30분" 두 표기를 받는다.
     */
    private static final Pattern TIME_TOKEN = Pattern.compile(
            "(오전|오후)?\\s*(\\d{1,2})\\s*(?::\\s*(\\d{1,2})|시(?:\\s*(\\d{1,2})\\s*분)?)?\\s*(AM|PM)?",
            Pattern.CASE_INSENSITIVE);

    /**
     * 그 요일에 "휴무"임을 고신뢰로 말하는지. 데이터 없음·요일 미매칭·판독 실패는 모두 false(열림 가정).
     */
    static boolean isClosedOnDay(String openingHours, DayOfWeek dow) {
        List<int[]> ranges = rangesFor(openingHours, dow);
        return ranges != null && ranges.isEmpty();
    }

    /**
     * {@code [startMin, endMin]} 구간 전체가 그 요일 영업시간 안에 들어가는지.
     * 판독할 수 없으면 true(열림 가정) — 안전 규약 참고.
     */
    static boolean isOpenDuring(String openingHours, DayOfWeek dow, int startMin, int endMin) {
        List<int[]> ranges = rangesFor(openingHours, dow);
        if (ranges == null) return true;   // 못 읽음 → 판단 보류
        if (ranges.isEmpty()) return false; // 정기휴무
        int end = Math.max(endMin, startMin);
        for (int[] r : ranges) {
            if (r[0] <= startMin && end <= r[1]) return true;
        }
        return false;
    }

    /**
     * {@code notBefore} 이후로 {@code duration}분을 통째로 담을 수 있는 가장 이른 시작 시각.
     *
     * <p>"닫혀 있으니 버린다"보다 "열 때까지 기다린다"가 맞는 경우가 많다 — 09:00 개장인 곳이
     * 08:36에 배정됐다면 24분만 늦추면 된다(실측 77 용연, 74 다이소·ATC뮤지엄).
     *
     * @return {@code null} = 판독 불가(제약 없음), {@code -1} = 그 요일엔 불가능,
     *         그 외 = 시작 시각(분)
     */
    static Integer earliestStart(String openingHours, DayOfWeek dow, int notBefore, int duration) {
        List<int[]> ranges = rangesFor(openingHours, dow);
        if (ranges == null) return null;
        if (ranges.isEmpty()) return -1;

        int best = -1;
        for (int[] r : ranges) {
            int start = Math.max(notBefore, r[0]);
            if (start + Math.max(0, duration) > r[1]) continue;
            if (best < 0 || start < best) best = start;
        }
        return best;
    }

    /**
     * 그 요일의 영업 구간 목록.
     *
     * @return {@code null} = 판독 불가(판단 보류), 빈 리스트 = 정기휴무, 그 외 = [시작분, 종료분] 목록
     */
    static List<int[]> rangesFor(String openingHours, DayOfWeek dow) {
        if (openingHours == null || openingHours.isBlank()) return null;

        String segment = segmentFor(normalize(openingHours), dow);
        if (segment == null) return null;

        String lower = segment.toLowerCase(Locale.ROOT);
        if (segment.contains("휴무") || lower.contains("closed")) return List.of();
        if (segment.contains("24시간") || lower.contains("24 hours")) {
            return List.of(new int[]{0, DAY_MINUTES});
        }

        List<int[]> ranges = new ArrayList<>();
        for (String part : segment.split(",")) {
            int[] range = parseRange(part);
            if (range != null) ranges.add(range);
        }
        // 구간을 하나도 못 읽었으면 "휴무"가 아니라 "판독 불가"다 — 빈 리스트로 돌려주면
        // 멀쩡한 장소가 휴무로 오인돼 전부 배제된다.
        return ranges.isEmpty() ? null : ranges;
    }

    /** 대시·물결 표기를 "~"로, 비분리 공백을 보통 공백으로 통일한다. */
    private static String normalize(String text) {
        return text.replace(' ', ' ')
                .replace('–', '~')  // en dash
                .replace('—', '~')  // em dash
                .replace('−', '~')  // minus sign
                .replace('-', '~')
                .replace("부터", "~");
    }

    /** 요일 라벨 경계로 자른 뒤 해당 요일 조각을 찾는다. 없으면 null. */
    private static String segmentFor(String text, DayOfWeek dow) {
        String ko = KO_DAYS.get(dow.getValue() - 1);
        String en = dow.getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT);

        for (String segment : DAY_BOUNDARY.split(text)) {
            String trimmed = segment.trim();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (trimmed.startsWith(ko) || lower.startsWith(en)) {
                // 라벨("월요일:")을 떼고 시각 부분만 남긴다
                int colon = trimmed.indexOf(':');
                return colon >= 0 && colon < trimmed.length() - 1 ? trimmed.substring(colon + 1) : trimmed;
            }
        }
        return null;
    }

    /**
     * "오전 11:00 ~ 오후 3:00", "오후 4:30~10:00", "11:00 AM ~ 3:00 PM", "오전 9시~오후 6시"를
     * [시작분, 종료분]으로. 시각 토큰이 정확히 2개가 아니면 null(판독 실패).
     */
    private static int[] parseRange(String part) {
        List<Token> tokens = tokenize(part);
        if (tokens.size() != 2) return null;

        Token start = tokens.get(0);
        Token end = tokens.get(1);
        // 한쪽에만 오전/오후(AM/PM)가 붙는 표기가 흔하다 — "오후 12:00~10:00"(뒤쪽 생략),
        // "4:00 ~ 7:30 PM"(앞쪽 생략). 없는 쪽은 있는 쪽을 물려받는다.
        Boolean startPm = start.pm != null ? start.pm : end.pm;
        Boolean endPm = end.pm != null ? end.pm : start.pm;

        int startMin = toMinutes(start, startPm);
        int endMin = toMinutes(end, endPm);
        if (startMin < 0 || endMin < 0) return null;

        // 자정 넘김: "오후 3:00 ~ 오전 2:00" → 15:00~26:00.
        // "오전 11:00 ~ 오전 12:00"도 같은 규칙으로 11:00~24:00이 된다(Google은 자정을 오전 12:00으로 쓴다).
        if (endMin <= startMin) endMin += DAY_MINUTES;
        if (endMin - startMin > DAY_MINUTES) return null; // 비정상 구간은 판독 실패로 둔다
        return new int[]{startMin, endMin};
    }

    private static List<Token> tokenize(String part) {
        List<Token> tokens = new ArrayList<>();
        Matcher m = TIME_TOKEN.matcher(part);
        while (m.find()) {
            if (m.group(2) == null) continue;
            String koMarker = m.group(1);
            String enMarker = m.group(5);
            String minuteText = m.group(3) != null ? m.group(3) : m.group(4);
            // 마커도 분 표기도 없는 맨숫자는 시각으로 보지 않는다(주소·번지 오인 방지).
            boolean hasMarker = koMarker != null || enMarker != null;
            if (!hasMarker && minuteText == null && !m.group(0).contains("시")) continue;

            Boolean pm = null;
            if (koMarker != null) pm = "오후".equals(koMarker);
            else if (enMarker != null) pm = enMarker.equalsIgnoreCase("PM");

            try {
                tokens.add(new Token(Integer.parseInt(m.group(2)),
                        minuteText != null ? Integer.parseInt(minuteText) : 0, pm));
            } catch (NumberFormatException e) {
                return List.of(); // 숫자를 못 읽으면 이 구간은 판독 실패
            }
            if (tokens.size() > 2) return tokens; // 2개를 넘으면 호출부가 판독 실패로 처리한다
        }
        return tokens;
    }

    /** 12시간제 → 분. 오전 12시는 자정(0시), 오후 12시는 정오(12시). 마커가 없으면 24시간제로 읽는다. */
    private static int toMinutes(Token token, Boolean pm) {
        int hour = token.hour;
        if (hour > 24 || token.minute > 59) return -1;
        if (pm != null) {
            if (hour > 12) return -1;
            if (pm) hour = hour == 12 ? 12 : hour + 12;
            else hour = hour == 12 ? 0 : hour;
        }
        return hour * 60 + token.minute;
    }

    private record Token(int hour, int minute, Boolean pm) {}
}
