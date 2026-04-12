package com.shg.trip.shgtrip.domain.place.client;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Google Places API (New) Text Search 응답 파싱 DTO.
 * 응답 구조: places[].{ id, displayName.text, formattedAddress, location, rating,
 *             priceLevel, regularOpeningHours, photos, googleMapsUri }
 */
public record GooglePlaceDetail(
        String placeId,
        String name,
        String address,
        double lat,
        double lng,
        Double rating,
        Integer priceLevel,
        String openingHours,
        String photoReference,
        String sourceUrl,
        List<String> types
) {
    @SuppressWarnings("unchecked")
    public static GooglePlaceDetail from(Map<String, Object> place) {
        // id (New API: "id" 필드)
        String placeId = (String) place.get("id");

        // displayName.text
        String name = null;
        Map<String, Object> displayName = (Map<String, Object>) place.get("displayName");
        if (displayName != null) {
            name = (String) displayName.get("text");
        }

        String address = (String) place.get("formattedAddress");

        // location.latitude / location.longitude
        double lat = 0.0, lng = 0.0;
        Map<String, Object> location = (Map<String, Object>) place.get("location");
        if (location != null) {
            lat = toDouble(location.get("latitude"));
            lng = toDouble(location.get("longitude"));
        }

        Double rating = toDoubleOrNull(place.get("rating"));

        // priceLevel: New API는 문자열 enum (PRICE_LEVEL_INEXPENSIVE 등)
        Integer priceLevel = parsePriceLevel((String) place.get("priceLevel"));

        // regularOpeningHours.weekdayDescriptions
        String openingHours = null;
        Map<String, Object> hours = (Map<String, Object>) place.get("regularOpeningHours");
        if (hours != null) {
            List<String> weekdayDescriptions = (List<String>) hours.get("weekdayDescriptions");
            if (weekdayDescriptions != null && !weekdayDescriptions.isEmpty()) {
                openingHours = String.join(", ", weekdayDescriptions);
            }
        }

        // photos[0].name → photo reference (New API: "photos[].name" 형식)
        String photoReference = null;
        List<Map<String, Object>> photos = (List<Map<String, Object>>) place.get("photos");
        if (photos != null && !photos.isEmpty()) {
            photoReference = (String) photos.get(0).get("name");
        }

        String sourceUrl = (String) place.get("googleMapsUri");

        // types 필드
        List<String> types = Optional.ofNullable((List<String>) place.get("types"))
                .orElse(List.of());

        return new GooglePlaceDetail(placeId, name, address, lat, lng,
                rating, priceLevel, openingHours, photoReference, sourceUrl, types);
    }

    /**
     * New API priceLevel enum → 1~4 정수 변환.
     * PRICE_LEVEL_FREE=0, PRICE_LEVEL_INEXPENSIVE=1, PRICE_LEVEL_MODERATE=2,
     * PRICE_LEVEL_EXPENSIVE=3, PRICE_LEVEL_VERY_EXPENSIVE=4
     */
    private static Integer parsePriceLevel(String priceLevel) {
        if (priceLevel == null) return null;
        return switch (priceLevel) {
            case "PRICE_LEVEL_FREE" -> 1;
            case "PRICE_LEVEL_INEXPENSIVE" -> 1;
            case "PRICE_LEVEL_MODERATE" -> 2;
            case "PRICE_LEVEL_EXPENSIVE" -> 3;
            case "PRICE_LEVEL_VERY_EXPENSIVE" -> 4;
            default -> null;
        };
    }

    private static double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    private static Double toDoubleOrNull(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        return null;
    }
}
