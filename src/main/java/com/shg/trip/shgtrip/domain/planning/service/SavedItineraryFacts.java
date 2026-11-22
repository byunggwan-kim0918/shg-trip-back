package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;
import com.shg.trip.shgtrip.domain.place.destination.KrwRates;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceFactsReader;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.global.util.GeoUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 저장된 일정(스텝·장소 엔티티)을 판정 입력으로 바꾼다. 저장 관문·편집 재판정·재채점이 같은 변환을 써야
 * 생성 직후와 편집 뒤의 판정이 갈리지 않는다.
 */
public final class SavedItineraryFacts {

    private SavedItineraryFacts() {}

    public static List<StepData> steps(Itinerary itinerary) {
        return steps(itinerary, null, null, null, null, null);
    }

    private static List<StepData> steps(Itinerary itinerary, ItineraryStep target, Place replacement,
                                        GeoUtils.TransportLeg incoming, ItineraryStep next,
                                        GeoUtils.TransportLeg outgoing) {
        return itinerary.getSteps().stream()
                .sorted(Comparator.comparingInt(ItineraryStep::getDayNumber)
                        .thenComparingInt(ItineraryStep::getStepOrder))
                .map(s -> {
                    Place p = s == target ? replacement : s.getPlace();
                    GeoUtils.TransportLeg leg = s == target ? incoming : s == next ? outgoing : null;
                    PlaceData place = p == null ? null
                            : new PlaceData(p.getName(), p.getAddress(), p.getCategory(), p.getRegion(), p.getCountry(),
                                    null, null, p.getId(), p.getNameKo());
                    return leg == null
                            ? new StepData(s.getStepOrder(), s.getDayNumber(), s.getStartTime(), s.getEndTime(), place,
                                    List.of(), s.getTransportationMode(), s.getTransportationDuration(),
                                    s.getTransportationDistance(), s.getTransportationCost(), s.getNotes(),
                                    s.getEstimatedCost())
                            : new StepData(s.getStepOrder(), s.getDayNumber(), s.getStartTime(), s.getEndTime(), place,
                                    List.of(), leg.mode(), leg.durationMin(), leg.distanceKm(), leg.cost(), s.getNotes(),
                                    s.getEstimatedCost());
                })
                .toList();
    }

    /**
     * 저장된 두 장소 사이 이동 추정. 편집과 저장 변환이 같은 식을 써야 같은 구간이 화면마다 다르게 보이지 않는다.
     *
     * @return 좌표가 없거나(0,0 포함) 하루 안에 오갈 거리가 아니면 null
     */
    public static GeoUtils.TransportLeg legBetween(Place from, Place to) {
        if (from == null || to == null) return null;
        if (from.getLatitude() == null || to.getLatitude() == null) return null;
        if (GeoUtils.isZeroCoord(from.getLatitude(), from.getLongitude())
                || GeoUtils.isZeroCoord(to.getLatitude(), to.getLongitude())) return null;
        boolean hubTransfer = PlaceCategoryConstants.isOverseasHub(from.getCountry(), from.getCategory())
                || PlaceCategoryConstants.isOverseasHub(to.getCountry(), to.getCategory());
        return GeoUtils.estimateLeg(
                new double[]{from.getLatitude().doubleValue(), from.getLongitude().doubleValue()},
                new double[]{to.getLatitude().doubleValue(), to.getLongitude().doubleValue()},
                "any", ScheduleTuning.maxLegKm(hubTransfer), hubTransfer);
    }

    /** 스텝 장소의 현재 DB 사실. 생성 때 후보도 같은 DB 값이라 스텝 판정은 생성 직후와 같다. */
    public static List<PlaceCandidate> candidates(Itinerary itinerary) {
        return candidates(itinerary, null);
    }

    private static List<PlaceCandidate> candidates(Itinerary itinerary, Place extra) {
        List<PlaceCandidate> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (ItineraryStep s : itinerary.getSteps()) {
            Place p = s.getPlace();
            if (p != null && seen.add(p.getId())) out.add(toCandidate(p, out.size() + 1));
        }
        if (extra != null && seen.add(extra.getId())) out.add(toCandidate(extra, out.size() + 1));
        return out;
    }

    /** 저장된 대안의 한 줄 설명. 교체로 스텝 장소가 바뀌어도 지금 스텝 장소를 기준으로 다시 만든다. */
    public static String alternativeNote(Place alternative, Place current) {
        if (alternative == null) return null;
        return AlternativeNotes.describe(toCandidate(alternative, 0), current == null ? null : toCandidate(current, 0));
    }

    /** 스텝 장소의 한 줄 설명. 숙소·교통 거점은 종류로 설명할 것이 없어 null이다. */
    public static String placeNote(Place place) {
        if (place == null) return null;
        String major = PlaceCategoryConstants.majorCategory(place.getCategory());
        if ("LODGING".equals(major) || "TRANSIT_HUB".equals(major)) return null;
        return AlternativeNotes.summary(toCandidate(place, 0));
    }

    static PlaceCandidate candidateOf(Place p) {
        return toCandidate(p, 0);
    }

    private static PlaceCandidate toCandidate(Place p, int index) {
        return new PlaceCandidate(index, p.getId(), p.getName(), p.getAddress(), p.getCategory(), p.getTags(),
                p.getRegion(), p.getCountry(), p.getLatitude(), p.getLongitude(), p.getDescription(),
                p.getRating(), 0, p.getPriceLevel(), p.getOpeningHours(), p.getRecommendedTimeSlots(),
                p.getRecommendedDurationMinutes(), false, KrwRates.admissionWon(p), null, p.getUserRatingCount(),
                p.getWikidataSitelinks(), p.getPlaceRole(), p.getCuisine(), false, null, p.getAttributes(),
                p.getNameKo(), p.getTravelerFame());
    }

    /** @param facts 스텝 장소의 사실. 저장 관문과 생성 중 판정이 같은 사실을 봐야 판정이 갈리지 않는다 */
    public static ItineraryRules.Verdict evaluate(Itinerary itinerary, ItineraryRules.Context ctx,
                                                  Map<Long, PlaceFactsReader.PlaceFact> facts) {
        return new ItineraryRules().evaluate(steps(itinerary), candidates(itinerary), ctx, facts);
    }

    /**
     * 스텝 하나의 장소를 바꾼 일정의 판정. 교체가 다시 재는 두 구간은 넘긴 값으로 둔다.
     *
     * @param incoming 바뀐 스텝으로 오는 구간. null이면 저장된 값
     * @param next     바뀐 스텝 다음의 같은 날 스텝. 없으면 null
     * @param outgoing {@code next}로 가는 구간. null이면 저장된 값
     */
    public static ItineraryRules.Verdict evaluateSwap(Itinerary itinerary, ItineraryStep target, Place replacement,
                                                      GeoUtils.TransportLeg incoming, ItineraryStep next,
                                                      GeoUtils.TransportLeg outgoing, ItineraryRules.Context ctx,
                                                      Map<Long, PlaceFactsReader.PlaceFact> facts) {
        return new ItineraryRules().evaluate(steps(itinerary, target, replacement, incoming, next, outgoing),
                candidates(itinerary, replacement), ctx, facts);
    }
}
