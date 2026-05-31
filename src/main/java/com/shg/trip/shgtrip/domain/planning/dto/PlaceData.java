package com.shg.trip.shgtrip.domain.planning.dto;

/**
 * AI Tool Use 응답 - 장소 스키마.
 * 장소 식별 정보만 포함. 좌표·평점·영업시간·설명은 Google Places API에서 조회.
 */
public record PlaceData(
        String name,
        String address,
        String category,
        String region,
        String country
) {}
