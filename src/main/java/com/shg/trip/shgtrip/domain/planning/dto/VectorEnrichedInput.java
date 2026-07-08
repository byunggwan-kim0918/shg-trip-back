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
        Map<String, String> categorySearchQueries
) {}
