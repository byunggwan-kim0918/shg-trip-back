package com.shg.trip.shgtrip.domain.planning.dto;

import java.util.List;

/**
 * PlaceFreshnessFilter 결과 DTO.
 * 벡터 검색 후보 장소들을 freshness 기준으로 분류한다.
 *
 * @param freshPlaces 7일 이내 → 기존 데이터 재사용 가능
 * @param stalePlaces 7일 초과 또는 DB 미존재 → Google Places API 호출 대상
 */
public record PlaceFreshnessResult(
        List<PlaceCandidate> freshPlaces,
        List<PlaceCandidate> stalePlaces
) {}
