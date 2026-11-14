package com.shg.trip.shgtrip.domain.itinerary.dto;

import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;
import com.shg.trip.shgtrip.domain.place.entity.Place;

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
        /** coverImage의 작성자·라이선스·원본 페이지. 표시 의무가 없는 사진이면 null이다. */
        String coverImageCredit,
        String coverImageLicense,
        String coverImageSourceUrl,
        List<String> tags,
        String status,
        /** OK·NEEDS_ADJUSTMENT. 핵심 품질 기준에 못 미쳐 편집이 필요하면 NEEDS_ADJUSTMENT다. */
        String qualityStatus,
        /** 해소하지 못한 품질 문제 안내. 편집하면 현재 일정 기준으로 다시 만든다. 없으면 빈 목록이다. */
        List<String> qualityNotices,
        List<ItineraryStepResponse> steps
) {
    /**
     * @param cover coverPlaceId로 읽은 장소. 없으면 null. 저장된 coverImage 컬럼은 만료된 주소라 쓰지 않는다.
     */
    public static ItineraryResponse from(Itinerary itinerary, Place cover) {
        boolean hasImage = cover != null && cover.getImageUrl() != null;
        return new ItineraryResponse(
                itinerary.getId(),
                itinerary.getTitle(),
                itinerary.getDestination(),
                itinerary.getStartDate(),
                itinerary.getEndDate(),
                itinerary.getTotalBudget(),
                itinerary.getEstimatedCost(),
                hasImage ? cover.getImageUrl() : null,
                hasImage ? cover.getImageCredit() : null,
                hasImage ? cover.getImageLicense() : null,
                hasImage ? cover.getImageSourceUrl() : null,
                itinerary.getTags(),
                itinerary.getStatus().name(),
                itinerary.getQualityStatus().name(),
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
