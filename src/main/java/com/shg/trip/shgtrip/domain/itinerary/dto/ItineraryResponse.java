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
        /** 생성 시 해소하지 못한 품질 문제 안내. 없으면 빈 목록이다. */
        List<String> qualityNotices,
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
                itinerary.getQualityNotices() != null ? itinerary.getQualityNotices() : List.of(),
                // reorder는 stepOrder 필드만 바꾸므로 배열 순서를 실제 순서에 맞춰 다시 정렬한다.
                itinerary.getSteps().stream()
                        .sorted(Comparator.comparingInt(ItineraryStep::getDayNumber)
                                .thenComparingInt(ItineraryStep::getStepOrder))
                        .map(ItineraryStepResponse::from)
                        .toList()
        );
    }
}
