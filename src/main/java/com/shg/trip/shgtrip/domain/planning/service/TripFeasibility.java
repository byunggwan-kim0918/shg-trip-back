package com.shg.trip.shgtrip.domain.planning.service;


import com.shg.trip.shgtrip.domain.itinerary.dto.PlaceRestriction;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
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

    private static final int DAY_START = 9 * 60 + 30;
    private static final int DAY_END = 21 * 60;
    /** 야경·야시장 한 곳을 보는 최소 시간. */
    private static final int MIN_EVENING_MINUTES = 60;

    private TripFeasibility() {}

    /**
     * 그날 해 진 뒤에 저녁 요구 하나를 둘 시간이 남는지. 출발일은 출발 허브 기한에서 허브까지 가는 시간과 추정 여유를 뺀
     * 시각까지 본다.
     *
     * @param arrival   첫날 도착 시각. 모르면 null
     * @param departure 마지막 날 출발 시각. 모르면 null
     */
    public static boolean hasEvening(LocalDate date, double lat, double lng, String country, boolean firstDay,
                                     boolean lastDay, LocalTime arrival, LocalTime departure) {
        int sunset = SolarClock.sunset(date, lat, lng, country);
        int start = firstDay && arrival != null ? minutes(arrival) : DAY_START;
        int end;
        if (!lastDay) {
            end = DAY_END;
        } else if (departure == null) {
            end = minutes(DraftItineraryPlanner.LAST_DAY_END);
        } else {
            end = TripHubs.latestLeave(TripHubs.departureDeadline(departure, null, false, country), null);
        }
        return end - Math.max(start, sunset + DayBuilder.NIGHT_AFTER_SUNSET) >= MIN_EVENING_MINUTES;
    }

    /** 여행의 어느 날에도 저녁 요구를 둘 시간이 없는지. */
    public static boolean noEveningDay(LocalDate start, LocalDate end, double lat, double lng, String country,
                                       LocalTime arrival, LocalTime departure) {
        if (start == null || end == null || end.isBefore(start)) return false;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (hasEvening(d, lat, lng, country, d.equals(start), d.equals(end), arrival, departure)) return false;
        }
        return true;
    }

    /** 첫날 일출 전에 그곳에 닿을 수 있는지. 도착 시각을 모르면 닿지 못한다고 본다. */
    public static boolean sunriseOnFirstDay(LocalDate date, double lat, double lng, String country, LocalTime arrival) {
        return arrival != null && minutes(arrival) <= SolarClock.sunrise(date, lat, lng, country)
                - TripSkeletonPlanner.SUNRISE_LEAD;
    }

    /** 일출을 볼 날이 없는지. 둘째 날부터는 전날 밤을 그 근처에 묵으면 되어 하루 여행만 해당한다. */
    public static boolean noSunriseDay(LocalDate start, LocalDate end, double lat, double lng, String country,
                                       LocalTime arrival) {
        if (start == null || end == null || !start.equals(end)) return false;
        return !sunriseOnFirstDay(start, lat, lng, country, arrival);
    }

    private static int minutes(LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }

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
