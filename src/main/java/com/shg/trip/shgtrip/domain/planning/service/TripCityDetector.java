package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.place.destination.DomesticCities;
import com.shg.trip.shgtrip.domain.planning.dto.TransportationHub;
import com.shg.trip.shgtrip.domain.planning.dto.TripCities;
import com.shg.trip.shgtrip.global.util.GeoUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 요청 여행지에서 하루 동선으로 묶을 수 없는 도시들을 찾는다. 근교 여행지(교토·나라)는 상위 도시(오사카)에 묶인다.
 * 해외는 {@link Destinations}, 국내는 {@link DomesticCities}에서 도시를 찾는다.
 */
final class TripCityDetector {

    private static final Pattern DAY_RANGE = Pattern.compile("(\\d+)(?:\\s*[-~]\\s*(\\d+))?");

    private TripCityDetector() {}

    /**
     * @param country    여행 나라. 다른 나라 도시(출발지로 적은 서울 등)는 구간으로 두지 않는다. null·빈 값이면 거르지 않는다
     * @param allocation 일차 범위("1-2") → 여행지 목록. 모든 날을 도시 하나씩으로 이어서 덮으면 그 배분을 쓴다
     * @param hub        배분이 없을 때 도착 관문의 도시를 처음, 출발 관문의 도시를 마지막에 둔다. null 허용
     * @return 도시가 둘 이상이고 날이 도시 수 이상이면 방문 순서와 일차별 도시, 아니면 null
     */
    static TripCities detect(String country, List<String> regions, Map<String, List<String>> allocation, long days,
                             Destinations destinations, TransportationHub hub) {
        if (regions == null || regions.isEmpty()) return null;
        List<Destination> kept = new ArrayList<>();
        for (String region : regions) {
            Optional<Destination> root = cityOf(region, destinations)
                    .filter(d -> country == null || country.isBlank() || country.equalsIgnoreCase(d.country()));
            if (root.isEmpty() || kept.stream().anyMatch(k -> k.name().equals(root.get().name()))) continue;
            if (kept.stream().allMatch(k -> distanceKm(k, root.get()) >= ScheduleTuning.MULTI_CITY_MIN_KM)) {
                kept.add(root.get());
            }
        }
        if (kept.size() < 2 || days < kept.size()) return null;

        Map<Integer, String> dayCity = dayCityFromAllocation(allocation, days, kept, destinations);
        if (dayCity.isEmpty()) dayCity = evenLayout(orderByHub(kept, hub, destinations), days);

        Map<String, Integer> firstDay = new HashMap<>();
        dayCity.forEach((day, city) -> firstDay.merge(city, day, Math::min));
        List<Destination> ordered = kept.stream()
                .filter(d -> firstDay.containsKey(d.name()))
                .sorted(Comparator.comparingInt(d -> firstDay.get(d.name())))
                .toList();
        if (ordered.size() < 2) return null;
        return new TripCities(ordered.stream()
                .map(d -> new TripCities.City(d.name(), d.nameKo(), d.country(), d.lat(), d.lng()))
                .toList(), dayCity);
    }

    /** 배분이 1일차부터 마지막 날까지 도시 하나씩으로 이어진 구간이면 일차 → 도시, 아니면 빈 맵. */
    private static Map<Integer, String> dayCityFromAllocation(Map<String, List<String>> allocation, long days,
                                                              List<Destination> cities, Destinations destinations) {
        if (allocation == null || allocation.isEmpty()) return Map.of();
        Map<Integer, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : allocation.entrySet()) {
            Matcher m = DAY_RANGE.matcher(entry.getKey());
            if (!m.find()) return Map.of();
            int from = Integer.parseInt(m.group(1));
            int to = m.group(2) != null ? Integer.parseInt(m.group(2)) : from;
            String city = singleCity(entry.getValue(), cities, destinations);
            if (city == null || from < 1 || from > to || to > days) return Map.of();
            for (int day = from; day <= to; day++) {
                if (out.put(day, city) != null) return Map.of();
            }
        }
        for (int day = 1; day <= days; day++) {
            if (!out.containsKey(day)) return Map.of();
        }
        return contiguous(out, days) ? out : Map.of();
    }

    private static String singleCity(List<String> values, List<Destination> cities, Destinations destinations) {
        String city = null;
        for (String value : values == null ? List.<String>of() : values) {
            Optional<Destination> d = destinations.resolve(value).or(() -> DomesticCities.find(value));
            if (d.isEmpty()) continue;
            String match = nearestCity(d.get(), cities).name();
            if (city != null && !city.equals(match)) return null;
            city = match;
        }
        return city;
    }

    private static Optional<Destination> cityOf(String region, Destinations destinations) {
        return destinations.find(destinations.rootOf(region)).or(() -> DomesticCities.find(region));
    }

    /** 다른 도시와 150km 안이라 따로 두지 않은 여행지(후쿠오카 옆 나가사키)는 가장 가까운 도시에 묶인다. */
    private static Destination nearestCity(Destination d, List<Destination> cities) {
        return cities.stream().min(Comparator.comparingDouble(c -> distanceKm(c, d))).orElseThrow();
    }

    /** 배분 중 일차 범위가 [firstDay, lastDay] 안에 드는 항목. 도시 구간 하나만 검색할 때 그 구간의 세부 지역 힌트로 쓴다. */
    static Map<String, List<String>> allocationWithin(Map<String, List<String>> allocation, int firstDay, int lastDay) {
        if (allocation == null) return null;
        Map<String, List<String>> out = new LinkedHashMap<>();
        allocation.forEach((range, values) -> {
            Matcher m = DAY_RANGE.matcher(range);
            if (!m.find()) return;
            int from = Integer.parseInt(m.group(1));
            int to = m.group(2) != null ? Integer.parseInt(m.group(2)) : from;
            if (from >= firstDay && to <= lastDay) out.put(range, values);
        });
        return out.isEmpty() ? null : out;
    }

    private static boolean contiguous(Map<Integer, String> dayCity, long days) {
        List<String> seen = new ArrayList<>();
        for (int day = 1; day <= days; day++) {
            String city = dayCity.get(day);
            if (!seen.isEmpty() && seen.get(seen.size() - 1).equals(city)) continue;
            if (seen.contains(city)) return false;
            seen.add(city);
        }
        return true;
    }

    /** 요청 순서를 두되 도착 관문 도시를 처음, 출발 관문 도시를 마지막으로 옮긴다. */
    private static List<Destination> orderByHub(List<Destination> cities, TransportationHub hub,
                                                Destinations destinations) {
        List<Destination> ordered = new ArrayList<>(cities);
        if (hub == null) return ordered;
        Destination departure = cityOfGateway(ordered, hub.departureHub(), destinations);
        Destination arrival = cityOfGateway(ordered, hub.arrivalHub(), destinations);
        if (departure != null) {
            ordered.remove(departure);
            ordered.add(departure);
        }
        if (arrival != null && arrival != departure) {
            ordered.remove(arrival);
            ordered.add(0, arrival);
        }
        return ordered;
    }

    private static Destination cityOfGateway(List<Destination> cities, String gatewayName, Destinations destinations) {
        if (gatewayName == null || gatewayName.isBlank()) return null;
        for (Destination d : destinations.all()) {
            if (d.gateways().stream().noneMatch(g -> g.name().equalsIgnoreCase(gatewayName.trim()))) continue;
            String root = destinations.rootOf(d.name());
            Optional<Destination> city = cities.stream().filter(c -> c.name().equals(root)).findFirst();
            if (city.isPresent()) return city.get();
        }
        // 국내 관문은 목록이 없어 이름의 위치에서 가장 가까운 도시로 본다(김포공항 → 서울).
        if (cities.isEmpty() || !"KR".equals(cities.get(0).country())) return null;
        return DomesticCities.hubPosition(gatewayName)
                .map(at -> cities.stream()
                        .min(Comparator.comparingDouble(c -> GeoUtils.haversine(c.lat(), c.lng(), at[0], at[1])))
                        .orElseThrow())
                .orElse(null);
    }

    /** 날을 도시 수로 고르게 나눈다. 나머지는 앞 도시부터 하루씩 더한다. */
    private static Map<Integer, String> evenLayout(List<Destination> ordered, long days) {
        Map<Integer, String> out = new LinkedHashMap<>();
        int day = 1;
        for (int i = 0; i < ordered.size(); i++) {
            long span = days / ordered.size() + (i < days % ordered.size() ? 1 : 0);
            for (long k = 0; k < span; k++) out.put(day++, ordered.get(i).name());
        }
        return out;
    }

    private static double distanceKm(Destination a, Destination b) {
        return GeoUtils.haversine(a.lat(), a.lng(), b.lat(), b.lng());
    }
}
