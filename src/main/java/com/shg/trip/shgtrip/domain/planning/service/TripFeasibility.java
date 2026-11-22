package com.shg.trip.shgtrip.domain.planning.service;


import com.shg.trip.shgtrip.domain.itinerary.dto.PlaceRestriction;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 요청 자체로 지킬 수 없는 조건의 판정. 대화가 답한 그 턴에 되묻고, 생성은 대화를 거치지 않은 요청을 같은 기준으로 막거나
 * 알린다. 두 곳이 다른 기준을 쓰면 대화를 통과한 요청이 생성에서 다시 막힌다.
 */
public final class TripFeasibility {

    /** 한국 운전면허(국제운전면허 포함)로 운전할 수 없는 나라. 자동차 요청은 택시·차량 호출로 본다. */
    private static final Set<String> NO_KOREAN_LICENSE_COUNTRIES = Set.of("CN", "VN");

    private TripFeasibility() {}

    /**
     * 여행지 장소 데이터로 그 일수의 일정을 지을 수 있는지. 날마다 관광 한 곳, 끼니 두 번, 숙소 한 곳이 있어야 한다.
     *
     * @param roles 장소 역할별 개수(SIGHT·ACTIVITY·MEAL·LODGING)
     */
    public static boolean enoughPlaceData(Map<String, Long> roles, long days) {
        long sights = roles.getOrDefault("SIGHT", 0L) + roles.getOrDefault("ACTIVITY", 0L);
        return sights >= days && roles.getOrDefault("MEAL", 0L) >= 2 * days
                && roles.getOrDefault("LODGING", 0L) >= 1;
    }

    public static boolean drivingNotAllowed(String country) {
        return country != null && NO_KOREAN_LICENSE_COUNTRIES.contains(country.trim().toUpperCase(Locale.ROOT));
    }

    /** 먹고 싶다고 한 음식이 피하겠다고 한 음식에 걸리면 그 조건. 없으면 null이다. */
    public static PlaceRestriction dishConflict(String dish, Collection<PlaceRestriction> avoid) {
        return PlaceRestrictions.of(avoid).violatedByDish(dish);
    }

    /**
     * 여행 기간의 모든 날이 휴무인지. 영업시간을 읽지 못하고 휴무 요일도 모르면 아니라고 본다.
     *
     * @param closedDays 장소 사실의 정기 휴무 요일
     */
    public static boolean closedEveryDay(String name, String openingHours, Set<DayOfWeek> closedDays,
                                         LocalDate start, LocalDate end) {
        if (start == null || end == null || end.isBefore(start)) return false;
        String hours = OpeningHoursText.withClosedDays(DayTiming.openingHoursOf(name, null, openingHours), closedDays);
        if (hours == null || hours.isBlank()) return false;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (!OpeningHoursText.isClosedOnDay(hours, d.getDayOfWeek())) return false;
        }
        return true;
    }
}
