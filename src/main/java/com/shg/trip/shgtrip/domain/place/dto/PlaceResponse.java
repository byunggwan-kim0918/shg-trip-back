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
        String imageSourceUrl,
        /** 영업시간이 공식 출처(관광공사)에서 왔는지. 아니면 화면이 미확인으로 보인다. */
        boolean hoursConfirmed
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
                place.getImageSourceUrl(),
                "official".equals(place.getOpeningHoursSource()) && place.getOpeningHours() != null
        );
    }
}
