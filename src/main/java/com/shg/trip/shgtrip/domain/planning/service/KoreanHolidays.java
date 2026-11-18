package com.shg.trip.shgtrip.domain.planning.service;

import java.time.LocalDate;
import java.util.Map;

/**
 * 국내 공휴일(설·추석 연휴와 대체공휴일 포함). 음력 명절은 계산하지 않고 표로 둔다.
 * 박물관·궁 상당수가 1월 1일과 설·추석 당일에 쉬어, 요일만 보면 그날 휴관을 놓친다.
 */
final class KoreanHolidays {

    // TODO: 2028년 이후 날짜는 정부 발표에 맞춰 추가한다.
    private static final Map<LocalDate, String> HOLIDAYS = Map.ofEntries(
            Map.entry(LocalDate.of(2026, 12, 25), "성탄절"),
            Map.entry(LocalDate.of(2027, 1, 1), "신정"),
            Map.entry(LocalDate.of(2027, 2, 6), "설 연휴"),
            Map.entry(LocalDate.of(2027, 2, 7), "설날"),
            Map.entry(LocalDate.of(2027, 2, 8), "설 연휴"),
            Map.entry(LocalDate.of(2027, 2, 9), "대체공휴일"),
            Map.entry(LocalDate.of(2027, 3, 1), "삼일절"),
            Map.entry(LocalDate.of(2027, 5, 5), "어린이날"),
            Map.entry(LocalDate.of(2027, 5, 13), "부처님오신날"),
            Map.entry(LocalDate.of(2027, 6, 6), "현충일"),
            Map.entry(LocalDate.of(2027, 8, 15), "광복절"),
            Map.entry(LocalDate.of(2027, 8, 16), "대체공휴일"),
            Map.entry(LocalDate.of(2027, 9, 14), "추석 연휴"),
            Map.entry(LocalDate.of(2027, 9, 15), "추석"),
            Map.entry(LocalDate.of(2027, 9, 16), "추석 연휴"),
            Map.entry(LocalDate.of(2027, 10, 3), "개천절"),
            Map.entry(LocalDate.of(2027, 10, 4), "대체공휴일"),
            Map.entry(LocalDate.of(2027, 10, 9), "한글날"),
            Map.entry(LocalDate.of(2027, 12, 25), "성탄절"));

    private static final java.util.Set<String> MAIN_DAYS = java.util.Set.of("신정", "설날", "추석");

    private KoreanHolidays() {
    }

    /** 공휴일 이름, 아니면 null. */
    static String nameOf(LocalDate date) {
        return HOLIDAYS.get(date);
    }

    /** 실내 관람 시설 상당수가 쉬는 날(신정·설날·추석 당일). */
    static boolean museumsClosed(LocalDate date) {
        String name = HOLIDAYS.get(date);
        return name != null && MAIN_DAYS.contains(name);
    }
}
