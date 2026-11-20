package com.shg.trip.shgtrip.domain.planning.service;

import java.time.LocalDate;
import java.util.Locale;

/**
 * 여행지 나라의 시간대와 공휴일. 공휴일 표가 없는 나라는 공휴일 없이 요일 휴무만 본다.
 * 시각은 모두 현지 시각이라 시간대가 틀리면 일출·일몰 판단이 그만큼 밀린다.
 */
final class TripCalendar {

    private TripCalendar() {
    }

    /** UTC와의 차이(분). 나라를 모르면 한국으로 본다. */
    static int utcOffsetMinutes(String country) {
        return switch (normalize(country)) {
            case "CN", "TW" -> 8 * 60;
            case "VN" -> 7 * 60;
            default -> 9 * 60;
        };
    }

    /** 공휴일 이름. 공휴일이 아니거나 표가 없는 나라면 null. */
    static String holidayName(String country, LocalDate date) {
        return isKorea(country) ? KoreanHolidays.nameOf(date) : null;
    }

    /** 실내 관람 시설 상당수가 쉬는 명절. */
    static boolean museumsClosed(String country, LocalDate date) {
        return isKorea(country) && KoreanHolidays.museumsClosed(date);
    }

    static boolean isKorea(String country) {
        return "KR".equals(normalize(country));
    }

    private static String normalize(String country) {
        return country == null || country.isBlank() ? "KR" : country.trim().toUpperCase(Locale.ROOT);
    }
}
