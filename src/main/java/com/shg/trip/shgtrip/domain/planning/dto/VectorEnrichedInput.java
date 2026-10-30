package com.shg.trip.shgtrip.domain.planning.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 벡터 검색 기반 파이프라인용 보강 입력 결과.
 * 기존 EnrichedInput 필드 + 벡터 검색용 구조화된 힌트를 포함한다.
 *
 * Haiku 1회 호출로 입력 정규화, 현실성 검증, 검색 힌트 생성을 수행한 결과를 담는다.
 */
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
        CompanionType companionType, // 동행 유형 — enrich가 description에서 추론. 모르면 UNKNOWN
        Integer partySize,           // 인원 — 모르면 null(비용은 1인 기준)
        List<String> ferryOnlyAreas  // 배로만 가는 부속섬의 주소 토큰(예: "우도면") — 후보에서 기본 제외
) {
    /** 동행 유형. 숙소 유형(도미토리 제외)·비용 인원·선택 프롬프트에 쓴다. */
    public enum CompanionType {
        SOLO, COUPLE, FAMILY, FRIENDS, UNKNOWN;

        /** 다인실·파티 문화가 맞지 않는 동행 — 게스트하우스·호스텔을 숙소 후보에서 뺀다. */
        public boolean avoidsDormLodging() {
            return this == COUPLE || this == FAMILY;
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

    /** 동행·인원·도선 섬 정보 없이 생성하는 호환 생성자. 기존 동작(1인·제한 없음)과 같다. */
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

    /** null-safe 동행 유형. */
    public CompanionType companion() {
        return companionType != null ? companionType : CompanionType.UNKNOWN;
    }
}
