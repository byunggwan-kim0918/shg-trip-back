package com.shg.trip.shgtrip.domain.planning.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 벡터 검색 후보 장소 DTO.
 * 인덱스 번호를 포함하여 LLM이 인덱스 기반으로 일정을 구성할 수 있도록 한다.
 */
public record PlaceCandidate(
        int index,              // 1-based 인덱스 번호
        Long placeId,           // DB id
        String name,
        String address,         // 주소 (Google Places 매핑 시 활용)
        String category,
        List<String> tags,
        String region,
        String country,
        BigDecimal latitude,
        BigDecimal longitude,
        String description,
        BigDecimal rating,
        double similarityScore, // cosine 유사도
        Integer priceLevel,     // Google Places 가격 수준 (1~4)
        String openingHours     // Google Places 영업시간
) {
    /**
     * priceLevel, openingHours 없이 생성하는 기존 호환 생성자.
     */
    public PlaceCandidate(int index, Long placeId, String name, String address,
                          String category, List<String> tags, String region, String country,
                          BigDecimal latitude, BigDecimal longitude, String description,
                          BigDecimal rating, double similarityScore) {
        this(index, placeId, name, address, category, tags, region, country,
                latitude, longitude, description, rating, similarityScore, null, null);
    }
}
