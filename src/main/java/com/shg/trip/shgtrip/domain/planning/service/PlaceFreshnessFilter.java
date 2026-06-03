package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceFreshnessResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 벡터 검색 후보 장소들을 freshness 기준으로 분류한다.
 *
 * - FRESH (7일 이내): 기존 데이터 재사용, Google Places API 호출 불필요
 * - STALE (7일 초과) / MISSING (DB 미존재): Google Places API 호출 대상
 *
 * @see com.shg.trip.shgtrip.domain.place.entity.Place#isStale()
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceFreshnessFilter {

    private static final int FRESHNESS_DAYS = 7;

    private final PlaceRepository placeRepository;

    /**
     * 후보 장소들을 freshness 기준으로 fresh/stale로 분류한다.
     *
     * @param candidates 벡터 검색으로 조회된 후보 장소 목록
     * @return fresh(재사용 가능)과 stale(Google API 호출 대상) 분류 결과
     */
    public PlaceFreshnessResult filter(List<PlaceCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new PlaceFreshnessResult(List.of(), List.of());
        }

        // PlaceCandidate에서 non-null placeId 목록 추출
        List<Long> placeIds = candidates.stream()
                .map(PlaceCandidate::placeId)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());

        // DB에서 해당 장소들 조회하여 savedAt 확인
        Map<Long, Place> placeMap = placeRepository.findAllById(placeIds).stream()
                .collect(Collectors.toMap(Place::getId, Function.identity()));

        OffsetDateTime freshnessThreshold = OffsetDateTime.now().minusDays(FRESHNESS_DAYS);

        List<PlaceCandidate> freshPlaces = new ArrayList<>();
        List<PlaceCandidate> stalePlaces = new ArrayList<>();

        for (PlaceCandidate candidate : candidates) {
            if (candidate.placeId() == null) {
                // placeId가 null → MISSING → stale로 분류
                stalePlaces.add(candidate);
                continue;
            }

            Place place = placeMap.get(candidate.placeId());
            if (place == null) {
                // DB에서 찾을 수 없음 → MISSING → stale로 분류
                stalePlaces.add(candidate);
                log.debug("Place not found in DB: placeId={}, name={}", candidate.placeId(), candidate.name());
            } else if (place.getSavedAt() == null || place.getSavedAt().isBefore(freshnessThreshold)) {
                // savedAt이 7일 초과 → STALE
                stalePlaces.add(candidate);
                log.debug("Stale place: placeId={}, name={}, savedAt={}",
                        candidate.placeId(), candidate.name(), place.getSavedAt());
            } else {
                // savedAt이 7일 이내 → FRESH
                freshPlaces.add(candidate);
            }
        }

        log.info("PlaceFreshnessFilter result: fresh={}, stale/missing={} (total candidates={})",
                freshPlaces.size(), stalePlaces.size(), candidates.size());

        return new PlaceFreshnessResult(freshPlaces, stalePlaces);
    }
}
