package com.shg.trip.shgtrip.domain.planning.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI Tool Use 응답 - 일정 단계 스키마.
 */
public record StepData(
        int stepOrder,
        int dayNumber,
        String startTime,
        String endTime,
        PlaceData place,
        List<AlternativeData> alternatives,
        String transportationMode,
        Integer transportationDuration,
        BigDecimal transportationDistance,
        BigDecimal transportationCost,
        String notes,
        BigDecimal estimatedCost
) {}
