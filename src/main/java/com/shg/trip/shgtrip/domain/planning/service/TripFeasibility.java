package com.shg.trip.shgtrip.domain.planning.service;


import com.shg.trip.shgtrip.domain.itinerary.dto.PlaceRestriction;
import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.planning.dto.TripConditions;
import com.shg.trip.shgtrip.global.util.GeoUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

    /** 지역 하나를 보는 최소 시간. 대표 명소 두 곳과 끼니 한 번이다. */
    private static final int AREA_VISIT_MINUTES = 180;
    /** 묵는 도시에서 당일로 다녀올 편도 상한. 넘으면 오가는 데 하루 대부분을 쓰고 그곳에서 지낼 시간이 남지 않는다. */
    private static final int DAY_TRIP_ONE_WAY_MINUTES = 180;
    /** 이 거리 안은 도시 간 이동이 아니라 시내 구간이다. 역·공항을 오가는 시간을 더하면 가까운 곳이 멀게 잡힌다. */
    private static final double LOCAL_KM = 30;
    /** 시내 구간 평균 속도(km/h, 도로 환산 거리 기준)와 갈아타는 시간. */
    private static final double LOCAL_KMH = 25;
    private static final int LOCAL_OVERHEAD_MINUTES = 10;

    /**
     * 들를 지역이 기간에 비해 많은지. 지역마다 최소 체류에 묵는 곳에서 오가는 시간을 더해 기간 동안 쓸 수 있는 시간과 견준다.
     *
     * @param far         묵는 도시에서 당일로 다녀오기 어려운 지역
     * @param tooMany     기간 안에 다 넣기 어려워 덜 넣을 지역. 오가는 시간이 긴 곳부터다
     * @param needMinutes 모든 지역에 필요한 시간
     * @param haveMinutes 기간 동안 쓸 수 있는 시간
     */
    public record AreaOverload(List<Destination> far, List<Destination> tooMany, int needMinutes, int haveMinutes) {

        public List<Destination> unlikely() {
            List<Destination> out = new ArrayList<>(far);
            tooMany.stream().filter(d -> !out.contains(d)).forEach(out::add);
            return out;
        }
    }

    /**
     * @param stays 묵기로 한 도시. 정하지 않았으면 비어 있고 들를 지역 어디에든 묵을 수 있다고 본다
     */
    public static Optional<AreaOverload> areaOverload(List<Destination> areas, List<Destination> stays, LocalDate start,
                                                      LocalDate end, LocalTime arrival, LocalTime departure,
                                                      String transportPref) {
        if (areas.size() < 2 || start == null || end == null || end.isBefore(start)) return Optional.empty();
        String country = areas.get(0).country();
        Map<Destination, Integer> cost = new LinkedHashMap<>();
        List<Destination> far = new ArrayList<>();
        for (Destination area : areas) {
            if (stays.contains(area)) {
                cost.put(area, AREA_VISIT_MINUTES);
                continue;
            }
            List<Destination> from = stays.isEmpty() ? areas.stream().filter(a -> !a.equals(area)).toList() : stays;
            int oneWay = from.stream().mapToInt(b -> travelMinutes(b, area, transportPref)).min().orElse(0);
            if (!stays.isEmpty() && oneWay > DAY_TRIP_ONE_WAY_MINUTES) far.add(area);
            // 묵는 곳에서 당일로 다녀오면 왕복, 묵을 곳이 정해지지 않았으면 그곳을 거쳐 지나가는 한 번이다.
            cost.put(area, AREA_VISIT_MINUTES + (stays.isEmpty() ? oneWay : 2 * oneWay));
        }
        int have = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            have += Math.max(0, dayEnd(d.equals(end), departure, country) - dayStart(d.equals(start), arrival));
        }
        int need = cost.values().stream().mapToInt(Integer::intValue).sum();
        List<Destination> tooMany = new ArrayList<>();
        List<Destination> droppable = cost.keySet().stream().filter(a -> !stays.contains(a))
                .sorted(Comparator.comparingInt((Destination a) -> cost.get(a)).reversed()).toList();
        int left = need;
        for (Destination a : droppable) {
            if (left <= have) break;
            tooMany.add(a);
            left -= cost.get(a);
        }
        if (far.isEmpty() && tooMany.isEmpty()) return Optional.empty();
        return Optional.of(new AreaOverload(far, tooMany, need, have));
    }

    private static int dayStart(boolean first, LocalTime arrival) {
        return first && arrival != null ? Math.max(DAY_START, minutes(arrival)) : DAY_START;
    }

    private static int dayEnd(boolean last, LocalTime departure, String country) {
        if (!last) return DAY_END;
        if (departure == null) return minutes(DraftItineraryPlanner.LAST_DAY_END);
        return TripHubs.latestLeave(TripHubs.departureDeadline(departure, null, false, country), null);
    }

    /** 일정을 짓는 쪽과 같은 도시 간 이동 추정이다. 가까운 곳은 시내 구간으로 본다. */
    private static int travelMinutes(Destination from, Destination to, String transportPref) {
        double km = GeoUtils.haversine(from.lat(), from.lng(), to.lat(), to.lng());
        if (km <= LOCAL_KM) {
            return LOCAL_OVERHEAD_MINUTES + (int) Math.ceil(km * GeoUtils.ROAD_DISTANCE_FACTOR / LOCAL_KMH * 60);
        }
        return IntercityLegs.legBetween(DraftItineraryPlanner.city(from), DraftItineraryPlanner.city(to), transportPref)
                .minutes();
    }

    /** 그 지역 안 어딘가에서 reachKm 안에 그곳이 있는지. 지역 범위 가장자리에 묵으면 닿는 곳까지 받는다. */
    public static boolean withinReach(double[] spot, TripConditions.Area area, double reachKm) {
        return area.kmTo(spot) <= area.rangeKm() + reachKm;
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
