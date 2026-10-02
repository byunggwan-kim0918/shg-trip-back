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
        Integer admissionFee,   // enrich 배치 산출 입장료(원, 0=무료) — 없으면 null(관광지 비용 산정용)
        String subRegion        // 주소에서 추출한 시/군/구 단위 세부지역 — 없으면 null(호출부가 region 사용)
) {
    /** 세부지역 없이 생성하는 기존 호환 생성자(전체 필드). */
    public PlaceCandidate(int index, Long placeId, String name, String address,
                          String category, List<String> tags, String region, String country,
                          BigDecimal latitude, BigDecimal longitude, String description,
                          BigDecimal rating, double similarityScore,
                          Integer priceLevel, String openingHours,
                          List<String> recommendedTimeSlots, Integer recommendedDurationMinutes,
                          boolean userSelected, Integer admissionFee) {
        this(index, placeId, name, address, category, tags, region, country,
                latitude, longitude, description, rating, similarityScore,
                priceLevel, openingHours, recommendedTimeSlots, recommendedDurationMinutes,
                userSelected, admissionFee, null);
    }

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
                priceLevel, openingHours, recommendedTimeSlots, null, false, null, null);
    }

    /** userSelected 플래그만 켠 사본 (dedupe 대표 선정 시 필수성 전파용). */
    public PlaceCandidate asUserSelected() {
        if (userSelected) return this;
        return new PlaceCandidate(index, placeId, name, address, category, tags, region, country,
                latitude, longitude, description, rating, similarityScore,
                priceLevel, openingHours, recommendedTimeSlots, recommendedDurationMinutes, true,
                admissionFee, subRegion);
    }

    /** 세부지역만 채운 사본. 추출 실패(null)면 원본을 그대로 둔다(호출부가 region으로 폴백). */
    public PlaceCandidate withSubRegion(String newSubRegion) {
        if (newSubRegion == null || newSubRegion.isBlank()) return this;
        return new PlaceCandidate(index, placeId, name, address, category, tags, region, country,
                latitude, longitude, description, rating, similarityScore,
                priceLevel, openingHours, recommendedTimeSlots, recommendedDurationMinutes,
                userSelected, admissionFee, newSubRegion);
    }

    /**
     * 점심·저녁 슬롯에 넣어도 되는 "한 끼 식사" 장소인지(3차 2번).
     *
     * <p>검색 슬롯(SQL 배제)·Sonnet 선택 결과 정리·최종 보정 패스·불변식 검사가 모두 이 한 값을
     * 본다. 생성 시점에 굳혀 복사본에 남기는 대신 파생 계산으로 둔 이유는, PlaceCandidate가
     * 재인덱싱·DB 보강 등으로 여러 번 복사되는데 그때마다 플래그를 옮겨 적으면 한 곳만 빠져도
     * 조용히 낡은 값이 남기 때문이다(이번 라운드의 "집합 POI가 저녁으로 인정" 사고가 정확히
     * 검사 경로마다 기준이 달라서 생겼다).
     */
    public boolean mealEligible() {
        return com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants
                .isMealPlace(name, category, tags);
    }

    /** 표시·프롬프트용 지역 라벨: 세부지역 우선, 없으면 상위 region. */
    public String displayRegion() {
        return (subRegion != null && !subRegion.isBlank()) ? subRegion : region;
    }
}
