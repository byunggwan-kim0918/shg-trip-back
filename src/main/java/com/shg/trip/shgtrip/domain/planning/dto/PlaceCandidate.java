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
        String openingHours,    // Google Places 영업시간
        List<String> recommendedTimeSlots, // enrich 배치 산출 추천 시간대 (예: "morning") — 없으면 null
        Integer recommendedDurationMinutes, // enrich 배치 산출 권장 체류시간(분) — 없으면 null
        boolean userSelected,   // Manual 모드에서 사용자가 직접 고른 필수 방문 장소 (트림/교체 면제)
        Integer admissionFee    // enrich 배치 산출 입장료(원, 0=무료) — 없으면 null(관광지 비용 산정용)
) {
    /**
     * priceLevel, openingHours 없이 생성하는 기존 호환 생성자.
     */
    public PlaceCandidate(int index, Long placeId, String name, String address,
                          String category, List<String> tags, String region, String country,
                          BigDecimal latitude, BigDecimal longitude, String description,
                          BigDecimal rating, double similarityScore) {
        this(index, placeId, name, address, category, tags, region, country,
                latitude, longitude, description, rating, similarityScore, null, null, null);
    }

    /**
     * recommendedTimeSlots 없이 생성하는 기존 호환 생성자.
     */
    public PlaceCandidate(int index, Long placeId, String name, String address,
                          String category, List<String> tags, String region, String country,
                          BigDecimal latitude, BigDecimal longitude, String description,
                          BigDecimal rating, double similarityScore,
                          Integer priceLevel, String openingHours) {
        this(index, placeId, name, address, category, tags, region, country,
                latitude, longitude, description, rating, similarityScore,
                priceLevel, openingHours, null);
    }

    /**
     * recommendedDurationMinutes/userSelected 없이 생성하는 기존 호환 생성자.
     */
    public PlaceCandidate(int index, Long placeId, String name, String address,
                          String category, List<String> tags, String region, String country,
                          BigDecimal latitude, BigDecimal longitude, String description,
                          BigDecimal rating, double similarityScore,
                          Integer priceLevel, String openingHours,
                          List<String> recommendedTimeSlots) {
        this(index, placeId, name, address, category, tags, region, country,
                latitude, longitude, description, rating, similarityScore,
                priceLevel, openingHours, recommendedTimeSlots, null, false, null);
    }

    /** userSelected 플래그만 켠 사본 (dedupe 대표 선정 시 필수성 전파용). */
    public PlaceCandidate asUserSelected() {
        if (userSelected) return this;
        return new PlaceCandidate(index, placeId, name, address, category, tags, region, country,
                latitude, longitude, description, rating, similarityScore,
                priceLevel, openingHours, recommendedTimeSlots, recommendedDurationMinutes, true, admissionFee);
    }
}
