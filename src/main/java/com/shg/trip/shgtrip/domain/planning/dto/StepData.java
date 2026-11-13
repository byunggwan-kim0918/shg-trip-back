package com.shg.trip.shgtrip.domain.planning.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI Tool Use 응답의 일정 단계 스키마.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
) {
    public StepData withStepOrder(int order) {
        return new StepData(order, dayNumber, startTime, endTime, place, alternatives, transportationMode,
                transportationDuration, transportationDistance, transportationCost, notes, estimatedCost);
    }
}
