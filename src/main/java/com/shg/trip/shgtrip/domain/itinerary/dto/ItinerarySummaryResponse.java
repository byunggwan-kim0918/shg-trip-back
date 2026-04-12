package com.shg.trip.shgtrip.domain.itinerary.dto;

import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record ItinerarySummaryResponse(
        Long id,
        String title,
        String destination,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal estimatedCost,
        String coverImage,
        List<String> tags,
        String status,
        OffsetDateTime createdAt
) {
    public static ItinerarySummaryResponse from(Itinerary itinerary) {
        return new ItinerarySummaryResponse(
                itinerary.getId(),
                itinerary.getTitle(),
                itinerary.getDestination(),
                itinerary.getStartDate(),
                itinerary.getEndDate(),
                itinerary.getEstimatedCost(),
                itinerary.getCoverImage(),
                itinerary.getTags(),
                itinerary.getStatus().name(),
                itinerary.getCreatedAt()
        );
    }
}
