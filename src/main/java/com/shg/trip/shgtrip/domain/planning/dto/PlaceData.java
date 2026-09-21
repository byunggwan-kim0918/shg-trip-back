package com.shg.trip.shgtrip.domain.planning.dto;

/**
 * AI Tool Use 응답 - 장소 스키마.
 * 장소 식별 정보만 포함. 좌표·평점·영업시간은 Google Places API에서 조회.
 *
 * @param subRegion   주소에서 추출한 시/군/구 세부지역 — story 작성 컨텍스트용(없으면 null)
 * @param description 장소 한 줄 설명 — story 작성 컨텍스트용(없으면 null)
 */
public record PlaceData(
        String name,
        String address,
        String category,
        String region,
        String country,
        String subRegion,
        String description
) {
    /** 세부지역·설명 없이 생성하는 기존 호환 생성자. */
    public PlaceData(String name, String address, String category, String region, String country) {
        this(name, address, category, region, country, null, null);
    }
}
