package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;
import com.shg.trip.shgtrip.domain.place.destination.KrwRates;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 저장된 일정(스텝·장소 엔티티)을 판정 입력으로 바꾼다. 저장 관문·편집 재판정·재채점이 같은 변환을 써야
 * 생성 직후와 편집 뒤의 판정이 갈리지 않는다.
 */
public final class SavedItineraryFacts {

    private SavedItineraryFacts() {}

    public static List<StepData> steps(Itinerary itinerary) {
        return itinerary.getSteps().stream()
                .sorted(Comparator.comparingInt(ItineraryStep::getDayNumber)
                        .thenComparingInt(ItineraryStep::getStepOrder))
                .map(s -> {
                    Place p = s.getPlace();
                    PlaceData place = p == null ? null
                            : new PlaceData(p.getName(), p.getAddress(), p.getCategory(), p.getRegion(), p.getCountry(),
                                    null, null, p.getId(), p.getNameKo());
                    return new StepData(s.getStepOrder(), s.getDayNumber(), s.getStartTime(), s.getEndTime(), place,
                            List.of(), s.getTransportationMode(), s.getTransportationDuration(),
                            s.getTransportationDistance(), s.getTransportationCost(), s.getNotes(),
                            s.getEstimatedCost());
                })
                .toList();
    }

    /** 스텝 장소의 현재 DB 사실. 생성 때 후보도 같은 DB 값이라 스텝 판정은 생성 직후와 같다. */
    public static List<PlaceCandidate> candidates(Itinerary itinerary) {
        List<PlaceCandidate> out = new ArrayList<>();
        int index = 1;
        Set<Long> seen = new HashSet<>();
        for (ItineraryStep s : itinerary.getSteps()) {
            Place p = s.getPlace();
            if (p == null || !seen.add(p.getId())) continue;
            out.add(new PlaceCandidate(index++, p.getId(), p.getName(), p.getAddress(), p.getCategory(), p.getTags(),
                    p.getRegion(), p.getCountry(), p.getLatitude(), p.getLongitude(), p.getDescription(),
                    p.getRating(), 0, p.getPriceLevel(), p.getOpeningHours(), p.getRecommendedTimeSlots(),
                    p.getRecommendedDurationMinutes(), false, KrwRates.admissionWon(p), null, p.getUserRatingCount(),
                    p.getWikidataSitelinks(), p.getPlaceRole(), p.getCuisine(), false, null, p.getAttributes(),
                    p.getNameKo(), p.getTravelerFame()));
        }
        return out;
    }

    public static ItineraryRules.Verdict evaluate(Itinerary itinerary, ItineraryRules.Context ctx) {
        return new ItineraryRules().evaluate(steps(itinerary), candidates(itinerary), ctx);
    }
}
