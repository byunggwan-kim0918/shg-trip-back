package com.shg.trip.shgtrip.domain.planning.dto;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 하루 동선으로 묶을 수 없을 만큼 떨어진 도시를 오가는 여행(도쿄·오사카). 도시는 방문 순서다.
 *
 * @param dayCity 일차 → 도시 이름. 1일차부터 마지막 날까지 도시마다 이어진 구간이다
 */
public record TripCities(List<City> cities, Map<Integer, String> dayCity) {

    public record City(String name, String nameKo, String country, double lat, double lng) {}

    public TripCities {
        cities = cities == null ? List.of() : List.copyOf(cities);
        dayCity = dayCity == null ? Map.of() : Map.copyOf(dayCity);
    }

    /** 좌표에서 가장 가까운 도시 이름. */
    public String cityOf(double lat, double lng) {
        return cities.stream()
                .min(Comparator.comparingDouble(c -> squaredDistance(c, lat, lng)))
                .map(City::name)
                .orElse(null);
    }

    private static double squaredDistance(City c, double lat, double lng) {
        double dLat = c.lat() - lat;
        double dLng = (c.lng() - lng) * Math.cos(Math.toRadians(lat));
        return dLat * dLat + dLng * dLng;
    }
}
