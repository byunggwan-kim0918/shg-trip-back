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
        BigDecimal estimatedCost,
        TransportRoute transportationRoute
) {
    /** @param transportationRoute 이동 구간 근거. 거리 추정만 했거나 이동이 없으면 null */
    public StepData(int stepOrder, int dayNumber, String startTime, String endTime, PlaceData place,
                    List<AlternativeData> alternatives, String transportationMode, Integer transportationDuration,
                    BigDecimal transportationDistance, BigDecimal transportationCost, String notes,
                    BigDecimal estimatedCost) {
        this(stepOrder, dayNumber, startTime, endTime, place, alternatives, transportationMode,
                transportationDuration, transportationDistance, transportationCost, notes, estimatedCost, null);
    }

    public StepData withStepOrder(int order) {
        return new StepData(order, dayNumber, startTime, endTime, place, alternatives, transportationMode,
                transportationDuration, transportationDistance, transportationCost, notes, estimatedCost,
                transportationRoute);
    }

    public StepData withNotes(String newNotes) {
        return new StepData(stepOrder, dayNumber, startTime, endTime, place, alternatives, transportationMode,
                transportationDuration, transportationDistance, transportationCost, newNotes, estimatedCost,
                transportationRoute);
    }

    public StepData withPlace(PlaceData newPlace) {
        return new StepData(stepOrder, dayNumber, startTime, endTime, newPlace, alternatives, transportationMode,
                transportationDuration, transportationDistance, transportationCost, notes, estimatedCost,
                transportationRoute);
    }

}
