package com.shg.trip.shgtrip.domain.planning.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Haiku가 보강한 사용자 입력.
 * Requirements: 2.1
 */
public record EnrichedInput(
        String destination,
        List<String> themes,
        List<String> categories,
        String pace,
        BigDecimal budget,
        LocalDate startDate,
        LocalDate endDate,
        String description,
        String enrichedContext,   // Haiku가 추가한 여행지 컨텍스트
        List<Long> selectedPlaceIds
) {}
