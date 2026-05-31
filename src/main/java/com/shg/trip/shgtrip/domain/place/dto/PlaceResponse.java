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
        OffsetDateTime savedAt
) {
    public static PlaceResponse from(Place place) {
        String imageUrl = place.getPhotoReference() != null
                ? "/api/places/" + place.getId() + "/photo"
                : null;
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
                imageUrl,
                place.getSavedAt()
        );
    }
}
