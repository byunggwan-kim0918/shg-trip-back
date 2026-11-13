package com.shg.trip.shgtrip.domain.planning.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record VectorEnrichedInput(
        String destination,
        List<String> themes,
        List<String> categories,
        String pace,
        String transportPref,
        BigDecimal budget,
        LocalDate startDate,
        LocalDate endDate,
        String description,
        List<Long> selectedPlaceIds,
        String normalizedDestination,
        String country,
        List<String> regions,
        List<String> searchTags,
        Map<String, List<String>> regionAllocation,
        String budgetRange,
        String seasonContext,
        String enrichedContext,
        TransportationHub transportationHub,
        Map<String, String> categorySearchQueries,
        CompanionType companionType, // enrich가 description에서 추론하고, 모르면 UNKNOWN
        Integer partySize,           // 모르면 null이고 비용은 1인 기준
        List<String> ferryOnlyAreas  // 배로만 가는 부속섬의 주소 토큰(예: "우도면"). 후보에서 기본 제외
) {
    /** 동행 유형. 숙소 유형(도미토리 제외)·비용 인원·선택 프롬프트에 쓴다. */
    public enum CompanionType {
        SOLO, COUPLE, FAMILY, FRIENDS, SENIOR, UNKNOWN;

        /** 다인실·파티 문화가 맞지 않는 동행이라 게스트하우스·호스텔을 숙소 후보에서 뺀다. */
        public boolean avoidsDormLodging() {
            return this == COUPLE || this == FAMILY || this == SENIOR;
        }

        public static CompanionType parse(Object raw) {
            if (raw == null) return UNKNOWN;
            try {
                return valueOf(raw.toString().trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return UNKNOWN;
            }
        }
    }

    /** 동행·인원·도선 섬 정보는 1인·제한 없음으로 둔다. */
    public VectorEnrichedInput(String destination, List<String> themes, List<String> categories,
                               String pace, String transportPref, BigDecimal budget,
                               LocalDate startDate, LocalDate endDate, String description,
                               List<Long> selectedPlaceIds, String normalizedDestination, String country,
                               List<String> regions, List<String> searchTags,
                               Map<String, List<String>> regionAllocation, String budgetRange,
                               String seasonContext, String enrichedContext,
                               TransportationHub transportationHub,
                               Map<String, String> categorySearchQueries) {
        this(destination, themes, categories, pace, transportPref, budget, startDate, endDate,
                description, selectedPlaceIds, normalizedDestination, country, regions, searchTags,
                regionAllocation, budgetRange, seasonContext, enrichedContext, transportationHub,
                categorySearchQueries, CompanionType.UNKNOWN, null, List.of());
    }

    /** 요청 화면 값("solo"·"parents" 등) → 동행 유형. 모르면 null. */
    public static CompanionType companionOf(String requestValue) {
        if (requestValue == null) return null;
        return switch (requestValue) {
            case "solo" -> CompanionType.SOLO;
            case "couple" -> CompanionType.COUPLE;
            case "friends" -> CompanionType.FRIENDS;
            case "family" -> CompanionType.FAMILY;
            case "parents" -> CompanionType.SENIOR;
            default -> null;
        };
    }

    /** 인원이 동행에서 정해지는 경우만. 나머지는 null(문장 추론 값 유지). */
    public static Integer defaultPartySize(CompanionType companion) {
        if (companion == CompanionType.SOLO) return 1;
        if (companion == CompanionType.COUPLE) return 2;
        return null;
    }

    /**
     * 사용자가 고른 동행·인원으로 덮어쓴다. 인원을 안 줬는데 동행이 문장 추론과 다르면, 추론 인원은 다른 동행을
     * 전제로 한 값이라 버린다(혼자로 추론된 1명이 가족 비용에 남지 않게).
     */
    public VectorEnrichedInput withCompanion(CompanionType companion, Integer party) {
        Integer resolvedParty = party != null ? party
                : (companion != null && companion != companion() ? null : partySize);
        return new VectorEnrichedInput(destination, themes, categories, pace, transportPref, budget, startDate, endDate,
                description, selectedPlaceIds, normalizedDestination, country, regions, searchTags, regionAllocation,
                budgetRange, seasonContext, enrichedContext, transportationHub, categorySearchQueries,
                companion != null ? companion : companionType, resolvedParty, ferryOnlyAreas);
    }

    public VectorEnrichedInput withTransportPref(String newTransportPref) {
        return new VectorEnrichedInput(destination, themes, categories, pace, newTransportPref, budget, startDate, endDate,
                description, selectedPlaceIds, normalizedDestination, country, regions, searchTags, regionAllocation,
                budgetRange, seasonContext, enrichedContext, transportationHub, categorySearchQueries,
                companionType, partySize, ferryOnlyAreas);
    }

    /**
     * 도시 구간 하나만 찾을 때의 입력. 그 도시의 여행지·일수로 좁힌다.
     *
     * @param blockAllocation 구간 일차 안의 배분 항목, 없으면 null
     */
    public VectorEnrichedInput forCityBlock(List<String> cityRegions, int firstDay, int lastDay,
                                            Map<String, List<String>> blockAllocation) {
        return new VectorEnrichedInput(destination, themes, categories, pace, transportPref, budget,
                startDate.plusDays(firstDay - 1L), startDate.plusDays(lastDay - 1L), description, selectedPlaceIds,
                normalizedDestination, country, cityRegions, searchTags, blockAllocation, budgetRange, seasonContext,
                enrichedContext, transportationHub, categorySearchQueries, companionType, partySize, ferryOnlyAreas);
    }

    /** null-safe 동행 유형. */
    public CompanionType companion() {
        return companionType != null ? companionType : CompanionType.UNKNOWN;
    }
}
