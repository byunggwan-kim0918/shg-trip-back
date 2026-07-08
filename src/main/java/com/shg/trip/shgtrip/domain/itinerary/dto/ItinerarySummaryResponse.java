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
    /**
     * @param coverImage 조회 시점에 coverPlaceId로 해소한 현재 imageUrl(presigned). 없으면 null.
     *                   저장된 coverImage 컬럼(과거의 깨진 프록시 URL)은 사용하지 않는다.
     */
    public static ItinerarySummaryResponse from(Itinerary itinerary, String coverImage) {
        return new ItinerarySummaryResponse(
                itinerary.getId(),
                itinerary.getTitle(),
                itinerary.getDestination(),
                itinerary.getStartDate(),
                itinerary.getEndDate(),
                itinerary.getEstimatedCost(),
                coverImage,
                itinerary.getTags(),
                itinerary.getStatus().name(),
                itinerary.getCreatedAt()
        );
    }
}
