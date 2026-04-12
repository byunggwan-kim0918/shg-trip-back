package com.shg.trip.shgtrip.domain.itinerary.dto;

import com.shg.trip.shgtrip.domain.place.entity.Place;

import java.math.BigDecimal;

public record PlaceResponse(
        Long id,
        String name,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        String category,
        String region,
        String country,
        BigDecimal rating,
        Integer priceLevel,
        String openingHours,
        String imageUrl
) {
    public static PlaceResponse from(Place place) {
        if (place == null) return null;
        String imageUrl = place.getPhotoReference() != null
                ? "/api/places/" + place.getId() + "/photo"
                : null;
        return new PlaceResponse(
                place.getId(), place.getName(), place.getAddress(),
                place.getLatitude(), place.getLongitude(),
                place.getCategory(), place.getRegion(), place.getCountry(),
                place.getRating(), place.getPriceLevel(),
                place.getOpeningHours(), imageUrl
        );
    }
}
