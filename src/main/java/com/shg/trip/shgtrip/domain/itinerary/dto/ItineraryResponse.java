package com.shg.trip.shgtrip.domain.itinerary.dto;

import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ItineraryResponse(
        Long id,
        String title,
        String destination,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal totalBudget,
        BigDecimal estimatedCost,
        String coverImage,
        List<String> tags,
        String status,
        List<ItineraryStepResponse> steps
) {
    public static ItineraryResponse from(Itinerary itinerary) {
        return new ItineraryResponse(
                itinerary.getId(),
                itinerary.getTitle(),
                itinerary.getDestination(),
                itinerary.getStartDate(),
                itinerary.getEndDate(),
                itinerary.getTotalBudget(),
                itinerary.getEstimatedCost(),
                itinerary.getCoverImage(),
                itinerary.getTags(),
                itinerary.getStatus().name(),
                itinerary.getSteps().stream()
                        .map(ItineraryStepResponse::from)
                        .toList()
        );
    }
}
