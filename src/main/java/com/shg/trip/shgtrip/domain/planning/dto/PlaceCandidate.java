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
        String address,         // 주소 — 저장 시 name과 함께 DB Place 조회 키로 쓴다
        String category,
        List<String> tags,
        String region,
        String country,
        BigDecimal latitude,
        BigDecimal longitude,
        String description,
        BigDecimal rating,
        double similarityScore, // cosine 유사도
        Integer priceLevel,     // 가격 수준 (1~4) — Google 유래라 약관상 삭제됨, 현재 전부 null
        String openingHours,    // 영업시간 — Google 유래라 약관상 삭제됨, 현재 전부 null
        List<String> recommendedTimeSlots, // enrich 배치 산출 추천 시간대 (예: "morning") — 없으면 null
        Integer recommendedDurationMinutes, // enrich 배치 산출 권장 체류시간(분) — 없으면 null
        boolean userSelected,   // Manual 모드에서 사용자가 직접 고른 필수 방문 장소 (트림/교체 면제)
        Integer admissionFee,   // enrich 배치 산출 입장료(원, 0=무료) — 없으면 null(관광지 비용 산정용)
        String subRegion,       // 주소에서 추출한 시/군/구 단위 세부지역 — 없으면 null(호출부가 region 사용)
        Integer userRatingCount,// Google 리뷰 수 — 없으면 null. 약관상 삭제돼 현재는 전부 null
        Integer wikidataSitelinks, // Wikidata 언어판 수 — null=미매칭, 0=항목없음, 1+=인기도
        String placeRole,       // 일정 속 역할(보강 산출: MEAL/CAFE/SIGHT/…/NOT_VISITABLE) — null=미보강
        String cuisine,         // 음식 종류(보강 산출, MEAL만) — null=미상
        boolean intentProtected,// 요청 의도의 핵심 장소 — 거리·교체·유형 트림에서 제외(영업시간·휴무 가드는 그대로)
        String intentTime       // 요청 의도의 시간대(morning/evening) — null=의도 없음
) {
    /** 의도 필드 없이 생성하는 호환 생성자. 의도는 스케줄 직전(RouteOptimizer)에만 실린다. */
    public PlaceCandidate(int index, Long placeId, String name, String address,
                          String category, List<String> tags, String region, String country,
                          BigDecimal latitude, BigDecimal longitude, String description,
                          BigDecimal rating, double similarityScore,
                          Integer priceLevel, String openingHours,
                          List<String> recommendedTimeSlots, Integer recommendedDurationMinutes,
                          boolean userSelected, Integer admissionFee, String subRegion,
                          Integer userRatingCount, Integer wikidataSitelinks, String placeRole, String cuisine) {
        this(index, placeId, name, address, category, tags, region, country,
                latitude, longitude, description, rating, similarityScore,
                priceLevel, openingHours, recommendedTimeSlots, recommendedDurationMinutes,
                userSelected, admissionFee, subRegion, userRatingCount, wikidataSitelinks, placeRole, cuisine,
                false, null);
    }

    /** 역할·음식 종류 없이 생성하는 호환 생성자(미보강 취급). */
    public PlaceCandidate(int index, Long placeId, String name, String address,
                          String category, List<String> tags, String region, String country,
                          BigDecimal latitude, BigDecimal longitude, String description,
                          BigDecimal rating, double similarityScore,
                          Integer priceLevel, String openingHours,
                          List<String> recommendedTimeSlots, Integer recommendedDurationMinutes,
                          boolean userSelected, Integer admissionFee, String subRegion,
                          Integer userRatingCount, Integer wikidataSitelinks) {
        this(index, placeId, name, address, category, tags, region, country,
                latitude, longitude, description, rating, similarityScore,
                priceLevel, openingHours, recommendedTimeSlots, recommendedDurationMinutes,
                userSelected, admissionFee, subRegion, userRatingCount, wikidataSitelinks, null, null);
    }

    /** 인기도 신호 없이 생성하는 호환 생성자. 미매칭으로 취급한다. */
    public PlaceCandidate(int index, Long placeId, String name, String address,
                          String category, List<String> tags, String region, String country,
                          BigDecimal latitude, BigDecimal longitude, String description,
                          BigDecimal rating, double similarityScore,
                          Integer priceLevel, String openingHours,
                          List<String> recommendedTimeSlots, Integer recommendedDurationMinutes,
                          boolean userSelected, Integer admissionFee, String subRegion,
                          Integer userRatingCount) {
        this(index, placeId, name, address, category, tags, region, country,
                latitude, longitude, description, rating, similarityScore,
                priceLevel, openingHours, recommendedTimeSlots, recommendedDurationMinutes,
                userSelected, admissionFee, subRegion, userRatingCount, null);
    }

    /** 리뷰 수 없이 생성하는 기존 호환 생성자(전체 필드). 리뷰 수 미상으로 취급한다. */
    public PlaceCandidate(int index, Long placeId, String name, String address,
                          String category, List<String> tags, String region, String country,
                          BigDecimal latitude, BigDecimal longitude, String description,
                          BigDecimal rating, double similarityScore,
                          Integer priceLevel, String openingHours,
                          List<String> recommendedTimeSlots, Integer recommendedDurationMinutes,
                          boolean userSelected, Integer admissionFee, String subRegion) {
        this(index, placeId, name, address, category, tags, region, country,
                latitude, longitude, description, rating, similarityScore,
                priceLevel, openingHours, recommendedTimeSlots, recommendedDurationMinutes,
                userSelected, admissionFee, subRegion, null, null);
    }

    /** 세부지역 없이 생성하는 기존 호환 생성자. */
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
                userSelected, admissionFee, null, null, null);
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
                priceLevel, openingHours, recommendedTimeSlots, null, false, null, null, null, null);
    }

    /** userSelected 플래그만 켠 사본 (dedupe 대표 선정 시 필수성 전파용). */
    public PlaceCandidate asUserSelected() {
        if (userSelected) return this;
        return new PlaceCandidate(index, placeId, name, address, category, tags, region, country,
                latitude, longitude, description, rating, similarityScore,
                priceLevel, openingHours, recommendedTimeSlots, recommendedDurationMinutes, true,
                admissionFee, subRegion, userRatingCount, wikidataSitelinks, placeRole, cuisine,
                intentProtected, intentTime);
    }

    /** 요청 의도를 실은 사본. */
    public PlaceCandidate withIntent(boolean protect, String time) {
        return new PlaceCandidate(index, placeId, name, address, category, tags, region, country,
                latitude, longitude, description, rating, similarityScore,
                priceLevel, openingHours, recommendedTimeSlots, recommendedDurationMinutes,
                userSelected, admissionFee, subRegion, userRatingCount, wikidataSitelinks, placeRole, cuisine,
                protect, time);
    }

    /** 트림·교체·이월에서 빼지 않는 장소인지 — 사용자 필수이거나 요청 의도의 핵심. */
    public boolean keepsPlace() {
        return userSelected || intentProtected;
    }

    /** 세부지역만 채운 사본. 추출 실패(null)면 원본을 그대로 둔다(호출부가 region으로 폴백). */
    public PlaceCandidate withSubRegion(String newSubRegion) {
        if (newSubRegion == null || newSubRegion.isBlank()) return this;
        return new PlaceCandidate(index, placeId, name, address, category, tags, region, country,
                latitude, longitude, description, rating, similarityScore,
                priceLevel, openingHours, recommendedTimeSlots, recommendedDurationMinutes,
                userSelected, admissionFee, newSubRegion, userRatingCount, wikidataSitelinks, placeRole, cuisine,
                intentProtected, intentTime);
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
        // 보강된 역할이 있으면 그것을 따른다(LLM이 이름·설명까지 보고 정한 값). 집합 POI(음식거리)는
        // 역할과 무관하게 한 끼 식사 자리가 될 수 없다.
        if (placeRole != null && !placeRole.isBlank()) {
            return "MEAL".equalsIgnoreCase(placeRole.trim())
                    && !com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants
                    .isAggregatePoi(name, category);
        }
        return com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants
                .isMealPlace(name, category, tags);
    }

    /** 표시·프롬프트용 지역 라벨: 세부지역 우선, 없으면 상위 region. */
    public String displayRegion() {
        return (subRegion != null && !subRegion.isBlank()) ? subRegion : region;
    }
}
