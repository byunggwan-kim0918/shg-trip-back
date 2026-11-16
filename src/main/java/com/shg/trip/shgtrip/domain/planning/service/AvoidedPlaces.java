package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** 사용자가 피하겠다고 한 장소. 두 생성 경로가 같은 판정({@link PlaceIdentity})으로 뺀다. 사용자가 고른 장소는 남긴다. */
@Slf4j
final class AvoidedPlaces {

    static final AvoidedPlaces NONE = new AvoidedPlaces(List.of());

    private final List<PlaceCandidate> places;

    private AvoidedPlaces(List<PlaceCandidate> places) {
        this.places = List.copyOf(places);
    }

    static AvoidedPlaces of(List<PlaceCandidate> places) {
        return places.isEmpty() ? NONE : new AvoidedPlaces(places);
    }

    /** 요청한 행에 같은 Wikidata 항목의 출처별 행을 더한다. 출처별 행은 이름·좌표가 달라 이름 판정만으로는 놓친다. */
    static AvoidedPlaces load(PlaceRepository placeRepository, List<Long> avoidPlaceIds) {
        List<Long> ids = avoidPlaceIds == null ? List.of()
                : avoidPlaceIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return NONE;
        List<Place> found = placeRepository.findAllById(ids);
        // 도착·출발 거점 후보가 사라지면 공항·역 없이 하루가 시작된다.
        List<Place> places = found.stream()
                .filter(p -> !"TRANSIT_HUB".equals(PlaceCategoryConstants.majorCategory(p.getCategory())))
                .toList();
        List<PlaceCandidate> avoided = new ArrayList<>(places.stream().map(p -> PlaceCandidates.of(p, 0)).toList());
        // 인기도 매칭이 근처 가게("무의도 쌈밥")에 섬의 항목 id를 붙이기도 해서 관광지의 항목 id만 믿는다.
        List<Place> sights = places.stream().filter(p -> isSight(p) && p.getWikidataId() != null).toList();
        int siblings = 0;
        if (!sights.isEmpty()) {
            Set<String> qids = sights.stream().map(Place::getWikidataId).collect(Collectors.toSet());
            Set<String> regions = sights.stream().map(Place::getRegion).filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            for (Place p : placeRepository.findActiveByWikidataIds(qids, regions)) {
                if (ids.contains(p.getId()) || !isSight(p)) continue;
                avoided.add(PlaceCandidates.of(p, 0));
                siblings++;
            }
        }
        // 같은 곳으로 합쳐진 행은 후보에 나오지 않고 대표 행이 대신 나온다. 딸린 일부를 피한다고 큰 장소까지 피하지는 않는다.
        List<Long> representatives = places.stream()
                .map(Place::samePlaceRepresentativeId)
                .filter(id -> id != null && !ids.contains(id))
                .distinct()
                .toList();
        if (!representatives.isEmpty()) {
            placeRepository.findAllById(representatives).forEach(p -> avoided.add(PlaceCandidates.of(p, 0)));
        }
        log.info("피할 장소 id {}개 → 판정 행 {}개(같은 Wikidata 항목 {}개, 같은 곳 대표 {}개, 교통 거점 {}개 제외)",
                ids.size(), avoided.size(), siblings, representatives.size(), found.size() - places.size());
        return of(avoided);
    }

    private static boolean isSight(Place p) {
        return "ATTRACTION".equals(PlaceCategoryConstants.majorCategory(p.getCategory()));
    }

    List<PlaceCandidate> places() {
        return places;
    }

    List<PlaceCandidate> exclude(List<PlaceCandidate> candidates) {
        if (places.isEmpty()) return candidates;
        List<PlaceCandidate> kept = new ArrayList<>(candidates.size());
        for (PlaceCandidate c : candidates) {
            if (c.userSelected() || !matchedAndLogged(c)) kept.add(c);
        }
        return kept;
    }

    boolean blocksName(String name) {
        return places.stream().anyMatch(a -> PlaceIdentity.sameByName(name, a));
    }

    private boolean matchedAndLogged(PlaceCandidate c) {
        for (PlaceCandidate a : places) {
            Optional<PlaceIdentity.Basis> basis = PlaceIdentity.sameAs(c, a);
            if (basis.isPresent()) {
                log.info("피할 장소 제외: {}(id={}) ← 피할 장소 id={}, 근거 {}", c.name(), c.placeId(), a.placeId(), basis.get());
                return true;
            }
        }
        return false;
    }
}
