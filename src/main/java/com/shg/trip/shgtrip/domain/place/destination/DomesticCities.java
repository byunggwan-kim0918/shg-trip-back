package com.shg.trip.shgtrip.domain.place.destination;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 국내 도시. 해외 목록({@link Destinations})은 적재 배치와 보강 프롬프트도 읽어 국내 도시를 넣지 않고 여기 둔다.
 * 이름은 {@code places.region}과 보강 프롬프트(enrich-input.txt)의 한국 도시 목록과 같아야 한다.
 */
public final class DomesticCities {

    private DomesticCities() {}

    /** 좌표는 그 지역 장소 좌표의 중앙값이다. 먼 도시인지는 이 점 사이 거리로 정한다. */
    private static final List<Destination> ALL = List.of(
            city("Seoul", "서울", 37.5409, 126.9975),
            city("Busan", "부산", 35.1589, 129.0796),
            city("Jeju", "제주", 33.4581, 126.5155),
            city("Incheon", "인천", 37.4676, 126.6489),
            city("Daegu", "대구", 35.8655, 128.5999),
            city("Daejeon", "대전", 36.3536, 127.3857),
            city("Gwangju", "광주", 35.1511, 126.8829),
            city("Suwon", "수원", 37.2741, 127.0302),
            city("Gangneung", "강릉", 37.7670, 128.8966),
            city("Gyeongju", "경주", 35.8376, 129.2215),
            city("Jeonju", "전주", 35.8182, 127.1418),
            city("Yeosu", "여수", 34.7413, 127.7258));

    private static final Map<String, Destination> BY_NAME = ALL.stream()
            .collect(Collectors.toMap(Destination::name, Function.identity()));

    /** 다른 도시와 오가려면 비행기를 타야 하는 섬. */
    private static final Set<String> ISLANDS = Set.of("Jeju");

    /** 이름에 도시 이름이 없거나 다른 도시 이름과 겹치는 공항·역. 이름은 소문자 영어 낱말 끝 또는 한국어 이름의 일부다. */
    private record Hub(List<String> names, double lat, double lng) {}

    private static final List<Hub> HUBS = List.of(
            new Hub(List.of("gyeonggi", "경기광주"), 37.3995, 127.2533),
            new Hub(List.of("gimpo", "김포"), 37.5583, 126.7906),
            new Hub(List.of("gimhae", "김해"), 35.1795, 128.9382),
            new Hub(List.of("yongsan", "용산"), 37.5299, 126.9648),
            new Hub(List.of("suseo", "수서"), 37.4874, 127.1015),
            new Hub(List.of("gwangmyeong", "광명"), 37.4165, 126.8848),
            new Hub(List.of("cheongju", "청주"), 36.7166, 127.4991),
            new Hub(List.of("osong", "오송"), 36.6205, 127.3272),
            new Hub(List.of("yangyang", "양양"), 38.0611, 128.6692),
            new Hub(List.of("pohang", "포항"), 35.9879, 129.4204),
            new Hub(List.of("ulsan", "울산"), 35.5513, 129.1386),
            new Hub(List.of("muan", "무안"), 34.9914, 126.3828));

    public static List<Destination> all() {
        return ALL;
    }

    public static Optional<Destination> find(String name) {
        if (name == null) return Optional.empty();
        String trimmed = name.trim();
        Destination byName = BY_NAME.get(trimmed);
        if (byName != null) return Optional.of(byName);
        return ALL.stream().filter(d -> d.nameKo().equals(trimmed)).findFirst();
    }

    public static boolean isIsland(String name) {
        return name != null && ISLANDS.contains(name);
    }

    /**
     * 국내 공항·역 이름의 위치 {위도, 경도}. 이름에 도시 이름(영어·한국어)이 있으면 그 도시 좌표다("Busan Station", "제주국제공항").
     * 모르면 비어 있다.
     */
    public static Optional<double[]> hubPosition(String hubName) {
        if (hubName == null || hubName.isBlank()) return Optional.empty();
        for (Hub hub : HUBS) {
            if (hub.names().stream().anyMatch(n -> mentions(hubName, n))) {
                return Optional.of(new double[]{hub.lat(), hub.lng()});
            }
        }
        return ALL.stream()
                .filter(d -> mentions(hubName, d.name()) || mentions(hubName, d.nameKo()))
                .findFirst()
                .map(d -> new double[]{d.lat(), d.lng()});
    }

    /**
     * 영어는 낱말 끝이 같아야 한다("Dongdaegu"는 대구, "Suseong"은 수서가 아니다). 한국어는 띄어 쓰지 않아 포함만 본다.
     */
    private static boolean mentions(String hubName, String name) {
        if (!name.chars().allMatch(c -> c < 128)) return hubName.contains(name);
        String lower = name.toLowerCase(Locale.ROOT);
        return java.util.Arrays.stream(hubName.toLowerCase(Locale.ROOT).split("[^a-z]+"))
                .anyMatch(word -> word.endsWith(lower));
    }

    private static Destination city(String name, String nameKo, double lat, double lng) {
        return new Destination("KR", name, nameKo, lat, lng, 0, "city", null, List.of(nameKo), List.of());
    }
}
