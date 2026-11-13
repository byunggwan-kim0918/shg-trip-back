package com.shg.trip.shgtrip.domain.place.dto;

import com.shg.trip.shgtrip.domain.place.entity.Place;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PlaceResponse(
        Long id,
        String name,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        String category,
        String description,
        BigDecimal rating,
        Integer priceLevel,
        String openingHours,
        String imageUrl,
        String region,
        OffsetDateTime savedAt,
        String nameKo,
        /** 사진 작성자·라이선스·원본 페이지. 표시 의무가 없는 사진(TourAPI)은 null이다. */
        String imageCredit,
        String imageLicense,
        String imageSourceUrl
) {
    public static PlaceResponse from(Place place) {
        return new PlaceResponse(
                place.getId(),
                place.getName(),
                place.getAddress(),
                place.getLatitude(),
                place.getLongitude(),
                place.getCategory(),
                place.getDescription(),
                place.getRating(),
                place.getPriceLevel(),
                place.getOpeningHours(),
                place.getImageUrl(),
                place.getRegion(),
                place.getSavedAt(),
                place.getNameKo(),
                place.getImageCredit(),
                place.getImageLicense(),
                place.getImageSourceUrl()
        );
    }
}
