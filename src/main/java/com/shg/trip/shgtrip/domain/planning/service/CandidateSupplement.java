package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.destination.KrwRates;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 검색 결과 후보를 DB의 현재 값으로 채운다. 검색 결과에는 영업시간·입장료·보강 속성이 없다. */
@Component
@RequiredArgsConstructor
public class CandidateSupplement {

    private final PlaceRepository placeRepository;

    List<PlaceCandidate> enrichFromDb(List<PlaceCandidate> candidates) {
        List<Long> placeIds = candidates.stream()
                .map(PlaceCandidate::placeId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        if (placeIds.isEmpty()) return candidates;

        Map<Long, Place> placeMap = placeRepository.findAllById(placeIds).stream()
                .collect(Collectors.toMap(Place::getId, Function.identity()));

        return candidates.stream().map(c -> {
            Place place = c.placeId() != null ? placeMap.get(c.placeId()) : null;
            if (place == null) return c;
            // 저장 단계가 id로 못 찾으면 (name,address)로 조회하므로 프롬프트·스텝·저장이 같은 DB 값을 봐야 한다.
            return new PlaceCandidate(
                    c.index(), c.placeId(),
                    place.getName() != null ? place.getName() : c.name(),
                    place.getAddress() != null ? place.getAddress() : c.address(),
                    c.category(),
                    c.tags(), c.region(), c.country(), c.latitude(), c.longitude(),
                    place.getDescription() != null ? place.getDescription() : c.description(),
                    place.getRating() != null ? place.getRating() : c.rating(),
                    c.similarityScore(),
                    place.getPriceLevel(),
                    place.getOpeningHours(),
                    place.getRecommendedTimeSlots(),
                    place.getRecommendedDurationMinutes(),
                    c.userSelected(),
                    KrwRates.admissionWon(place),
                    subRegionOrKeep(place.getAddress(), c.region(), c.subRegion()),
                    place.getUserRatingCount() != null ? place.getUserRatingCount() : c.userRatingCount(),
                    place.getWikidataSitelinks() != null
                            ? place.getWikidataSitelinks() : c.wikidataSitelinks(),
                    place.getPlaceRole() != null ? place.getPlaceRole() : c.placeRole(),
                    place.getCuisine() != null ? place.getCuisine() : c.cuisine(),
                    false, null,
                    place.getAttributes() != null ? place.getAttributes() : c.attributes(),
                    place.getNameKo() != null ? place.getNameKo() : c.nameKo(),
                    place.getTravelerFame() != null ? place.getTravelerFame() : c.travelerFame()
            );
        }).collect(Collectors.toList());
    }

    private static String subRegionOrKeep(String address, String region, String current) {
        if (KoreanAddressRegion.contradicts(address, region)) return null;
        String extracted = PlaceCategoryConstants.subRegionOf(address, region);
        return extracted != null ? extracted : current;
    }
}
