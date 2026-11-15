package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.destination.KrwRates;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;

/** DB 장소를 후보로 바꾼다. 위치 인자가 많아 경로마다 따로 만들면 필드가 늘 때 값이 한 칸씩 밀린다. */
final class PlaceCandidates {

    private PlaceCandidates() {}

    static PlaceCandidate of(Place place, int index) {
        return new PlaceCandidate(
                index, place.getId(), place.getName(), place.getAddress(),
                place.getCategory(), place.getTags(), place.getRegion(), place.getCountry(),
                place.getLatitude(), place.getLongitude(), place.getDescription(),
                place.getRating(), 0.0, null, place.getOpeningHours(),
                place.getRecommendedTimeSlots(), place.getRecommendedDurationMinutes(),
                false, KrwRates.admissionWon(place),
                PlaceCategoryConstants.subRegionOf(place.getAddress(), place.getRegion()),
                place.getUserRatingCount(), place.getWikidataSitelinks(),
                place.getPlaceRole(), place.getCuisine(), false, null, place.getAttributes(), place.getNameKo(),
                place.getTravelerFame());
    }
}
