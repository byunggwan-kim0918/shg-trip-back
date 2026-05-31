package com.shg.trip.shgtrip.domain.itinerary.dto;

import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;

import java.math.BigDecimal;
import java.util.List;

public record ItineraryStepResponse(
        Long id,
        Integer stepOrder,
        Integer dayNumber,
        String startTime,
        String endTime,
        PlaceResponse place,
        String transportationMode,
        Integer transportationDuration,
        BigDecimal transportationDistance,
        BigDecimal transportationCost,
        String notes,
        String userNotes,
        BigDecimal estimatedCost,
        List<AlternativeOptionResponse> alternatives
) {
    public static ItineraryStepResponse from(ItineraryStep step) {
        return new ItineraryStepResponse(
                step.getId(),
                step.getStepOrder(),
                step.getDayNumber(),
                step.getStartTime(),
                step.getEndTime(),
                PlaceResponse.from(step.getPlace()),
                step.getTransportationMode(),
                step.getTransportationDuration(),
                step.getTransportationDistance(),
                step.getTransportationCost(),
                step.getNotes(),
                step.getUserNotes(),
                step.getEstimatedCost(),
                step.getAlternatives().stream()
                        .map(AlternativeOptionResponse::from)
                        .toList()
        );
    }
}
