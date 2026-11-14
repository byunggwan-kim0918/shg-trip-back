package com.shg.trip.shgtrip.domain.itinerary.dto;

import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.place.entity.Place;

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
        /** coverImage의 작성자·라이선스·원본 페이지. 표시 의무가 없는 사진이면 null이다. */
        String coverImageCredit,
        String coverImageLicense,
        String coverImageSourceUrl,
        List<String> tags,
        String status,
        /** OK·NEEDS_ADJUSTMENT */
        String qualityStatus,
        OffsetDateTime createdAt
) {
    /**
     * @param cover 조회 시점에 coverPlaceId로 읽은 장소. 없으면 null.
     *              저장된 coverImage 컬럼은 깨진 프록시 URL이라 사용하지 않는다.
     */
    public static ItinerarySummaryResponse from(Itinerary itinerary, Place cover) {
        boolean hasImage = cover != null && cover.getImageUrl() != null;
        return new ItinerarySummaryResponse(
                itinerary.getId(),
                itinerary.getTitle(),
                itinerary.getDestination(),
                itinerary.getStartDate(),
                itinerary.getEndDate(),
                itinerary.getEstimatedCost(),
                hasImage ? cover.getImageUrl() : null,
                hasImage ? cover.getImageCredit() : null,
                hasImage ? cover.getImageLicense() : null,
                hasImage ? cover.getImageSourceUrl() : null,
                itinerary.getTags(),
                itinerary.getStatus().name(),
                itinerary.getQualityStatus().name(),
                itinerary.getCreatedAt()
        );
    }
}
