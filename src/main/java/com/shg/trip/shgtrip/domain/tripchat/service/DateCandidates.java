package com.shg.trip.shgtrip.domain.tripchat.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "11월 중순"처럼 대략 말한 날짜에 대해 고를 만한 실제 날짜를 계산한다.
 * 모델은 요일·날짜 계산을 틀리므로 후보는 코드만 만든다.
 */
public final class DateCandidates {

    /** "11|mid", "2027-3|whole" */
    private static final Pattern APPROX = Pattern.compile("(?:(\\d{4})-)?(\\d{1,2})\\|(early|mid|late|whole)");
    private static final int MAX_CANDIDATES = 3;

    private DateCandidates() {}

    public record Approx(Integer year, int month, String part) {}

    public record Range(LocalDate start, LocalDate end) {}

    public static Approx parseApprox(String value) {
        if (value == null) return null;
        // 프롬프트 표에서 파이프를 이스케이프해 적어 모델이 백슬래시째 복사할 때가 있다.
        Matcher m = APPROX.matcher(value.strip().replace("\\", ""));
        if (!m.matches()) return null;
        int month = Integer.parseInt(m.group(2));
        if (month < 1 || month > 12) return null;
        Integer year = m.group(1) != null ? Integer.valueOf(m.group(1)) : null;
        return new Approx(year, month, m.group(3));
    }

    /** 말하지 않은 연도는 오늘 이후 가장 가까운 그 달로 본다. 화면 문구에 연도를 드러내 사용자가 확인한다. */
    public static YearMonth resolveMonth(Approx approx, LocalDate today) {
        int year = approx.year() != null ? approx.year()
                : (approx.month() < today.getMonthValue() ? today.getYear() + 1 : today.getYear());
        return YearMonth.of(year, approx.month());
    }

    public static List<Range> forApprox(Approx approx, Integer nights, LocalDate today) {
        YearMonth ym = resolveMonth(approx, today);
        LocalDate from = switch (approx.part()) {
            case "mid" -> ym.atDay(11);
            case "late" -> ym.atDay(21);
            default -> ym.atDay(1);
        };
        LocalDate to = switch (approx.part()) {
            case "early" -> ym.atDay(10);
            case "mid" -> ym.atDay(20);
            default -> ym.atEndOfMonth();
        };
        return weekendRanges(from, to, nights, today);
    }

    /** 시기를 말하지 않고 기간만 말했을 때 가까운 주말부터 제안한다. */
    public static List<Range> upcoming(Integer nights, LocalDate today) {
        return weekendRanges(today.plusDays(1), today.plusDays(35), nights, today);
    }

    private static List<Range> weekendRanges(LocalDate from, LocalDate to, Integer nights, LocalDate today) {
        LocalDate earliest = today.plusDays(1);
        List<Range> out = new ArrayList<>();
        for (LocalDate d = from.isBefore(earliest) ? earliest : from; !d.isAfter(to); d = d.plusDays(1)) {
            DayOfWeek w = d.getDayOfWeek();
            if (w != DayOfWeek.FRIDAY && w != DayOfWeek.SATURDAY) continue;
            int n = nights != null && nights > 0 ? Math.min(nights, 9) : (w == DayOfWeek.FRIDAY ? 2 : 1);
            out.add(new Range(d, d.plusDays(n)));
            if (out.size() == MAX_CANDIDATES) break;
        }
        return out;
    }

    /** "11월 13일(금)부터 15일(일)까지". 올해가 아니면 연도를 붙인다. */
    public static String phrase(Range r, LocalDate today) {
        String start = (r.start().getYear() != today.getYear() ? r.start().getYear() + "년 " : "")
                + r.start().getMonthValue() + "월 " + r.start().getDayOfMonth() + "일(" + weekday(r.start()) + ")";
        String end = (r.end().getMonthValue() != r.start().getMonthValue() ? r.end().getMonthValue() + "월 " : "")
                + r.end().getDayOfMonth() + "일(" + weekday(r.end()) + ")";
        return start + "부터 " + end + "까지";
    }

    public static String weekday(LocalDate d) {
        return d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN);
    }

    /** "11월 중순", "12월", "2027년 3월 초" */
    public static String approxLabel(Approx approx, LocalDate today) {
        YearMonth ym = resolveMonth(approx, today);
        String part = switch (approx.part()) {
            case "early" -> " 초";
            case "mid" -> " 중순";
            case "late" -> " 말";
            default -> "";
        };
        return (ym.getYear() != today.getYear() ? ym.getYear() + "년 " : "") + ym.getMonthValue() + "월" + part;
    }
}
