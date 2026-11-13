package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * enrich 입력의 해외 여행지 처리. DB의 해외 {@code places.region}은 destinations.json 표준명이라, regions가 그 이름이
 * 아니면 지역 필터가 0건을 낸다.
 */
final class EnrichDestinations {

    static final String PLACEHOLDER = "{overseasDestinations}";

    private EnrichDestinations() {}

    /**
     * 프롬프트에 넣는 국가별 여행지 목록. "Tokyo(도쿄; 관문 Haneda Airport / Narita International Airport)",
     * 관문 없는 근교 여행지는 "Hoi An(호이안; 상위 Da Nang)" 꼴이다. 상위는 {@link Destinations#gatewayRegion}과 같아야
     * 모델이 고른 허브가 후보에 붙인 관문과 맞는다.
     */
    static String promptLines(Destinations destinations) {
        Map<String, List<Destination>> byCountry = new LinkedHashMap<>();
        for (Destination d : destinations.all()) byCountry.computeIfAbsent(d.country(), k -> new ArrayList<>()).add(d);
        return byCountry.entrySet().stream()
                .map(e -> "    - " + Destinations.countryNameKo(e.getKey()) + ": "
                        + e.getValue().stream().map(d -> entry(d, destinations)).collect(Collectors.joining(", ")))
                .collect(Collectors.joining("\n"));
    }

    private static String entry(Destination d, Destinations destinations) {
        String gatewayRegion = destinations.gatewayRegion(d.name());
        String hub = !d.gateways().isEmpty()
                ? "; 관문 " + d.gateways().stream().map(Destination.Gateway::name).collect(Collectors.joining(" / "))
                : !gatewayRegion.equals(d.name()) ? "; 상위 " + gatewayRegion : "";
        return d.name() + "(" + d.nameKo() + hub + ")";
    }

    /** 여행지 표준명·한국어 이름·별칭을 표준명으로 바꾼다. 목록에 없는 이름(한국 도시 등)은 그대로 둔다. */
    static List<String> canonicalRegions(List<String> regions, Destinations destinations) {
        if (regions == null) return null;
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String r : regions) {
            if (r == null || r.isBlank()) continue;
            out.add(destinations.resolve(r).map(Destination::name).orElse(r.trim()));
        }
        return List.copyOf(out);
    }

    /**
     * 일차별 지역 배분의 값 중 여행지 표준명·한국어 이름만 표준명으로 바꾼다. 별칭(하위 구역)은 검색 힌트로 쓰이므로
     * 상위 여행지로 올리지 않는다(아사쿠사를 Tokyo로 바꾸면 그날 구역 힌트가 사라진다).
     */
    static Map<String, List<String>> canonicalAllocation(Map<?, ?> allocation, Destinations destinations) {
        if (allocation == null) return null;
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : allocation.entrySet()) {
            List<String> values = asStrings(e.getValue()).stream()
                    .filter(v -> !v.isBlank())
                    .map(v -> destinations.findByName(v).map(Destination::name).orElse(v.trim()))
                    .toList();
            out.put(String.valueOf(e.getKey()), values);
        }
        return out;
    }

    static final int MAX_MENTIONED_DESTINATIONS = 2;
    static final int MIN_KOREAN_ALIAS_LENGTH = 3;

    /**
     * 요청 문장에 같은 여행 묶음 근교 여행지의 한국어 별칭("만리장성")이 나오면 그 여행지를 돌려준다. 모델이 도시만 내면
     * 근교 명소가 검색되지 않는다. 한국어 이름("나라"·"후에"·"이란")은 일반 문장에도 나와 별칭만 본다.
     */
    static List<String> mentionedDestinations(List<String> regions, String text, Destinations destinations) {
        if (regions == null || regions.isEmpty() || text == null || text.isBlank()) return List.of();
        String compact = text.replaceAll("\\s+", "");
        Set<String> roots = regions.stream().map(destinations::rootOf).collect(Collectors.toSet());
        List<String> out = new ArrayList<>();
        for (Destination d : destinations.all()) {
            if (out.size() >= MAX_MENTIONED_DESTINATIONS) break;
            if (regions.contains(d.name()) || !roots.contains(destinations.rootOf(d.name()))) continue;
            if (d.aliases().stream().filter(EnrichDestinations::isKoreanAlias)
                    .anyMatch(a -> compact.contains(a.replaceAll("\\s+", "")))) {
                out.add(d.name());
            }
        }
        return out;
    }

    /** 더한 여행지를 그 상위 여행지가 든 첫 배분 항목에 넣는다. 배분이 있으면 그 항목 단위로만 검색해 빠지면 후보가 없다. */
    static Map<String, List<String>> withAllocated(Map<String, List<String>> allocation, List<String> added,
                                                   Destinations destinations) {
        if (allocation == null || added.isEmpty()) return allocation;
        Map<String, List<String>> out = new LinkedHashMap<>(allocation);
        for (String region : added) {
            String root = destinations.rootOf(region);
            out.entrySet().stream()
                    .filter(e -> e.getValue().stream().anyMatch(v -> root.equals(destinations.rootOf(v))))
                    .findFirst()
                    .ifPresent(e -> {
                        List<String> values = new ArrayList<>(e.getValue());
                        if (!values.contains(region)) values.add(region);
                        e.setValue(List.copyOf(values));
                    });
        }
        return out;
    }

    private static boolean isKoreanAlias(String alias) {
        String compact = alias.replaceAll("\\s+", "");
        return compact.length() >= MIN_KOREAN_ALIAS_LENGTH
                && compact.codePoints().allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL);
    }

    private static List<String> asStrings(Object value) {
        if (value instanceof String s) return List.of(s);
        if (value instanceof List<?> list) return list.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        return List.of();
    }
}
