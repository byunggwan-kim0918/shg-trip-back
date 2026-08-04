package com.shg.trip.shgtrip.domain.itinerary.dto;

import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
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
                // (dayNumber, stepOrder)로 정렬 — 초기 로드엔 @OrderBy로 이미 정렬돼 no-op이고,
                // reorder처럼 stepOrder 필드만 mutate된 경우 배열 순서를 실제 순서와 일치시킨다.
                itinerary.getSteps().stream()
                        .sorted(Comparator.comparingInt(ItineraryStep::getDayNumber)
                                .thenComparingInt(ItineraryStep::getStepOrder))
                        .map(ItineraryStepResponse::from)
                        .toList()
        );
    }
}
