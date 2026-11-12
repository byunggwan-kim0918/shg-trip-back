package com.shg.trip.shgtrip.domain.planning.dto;

import com.shg.trip.shgtrip.domain.place.entity.PlaceAttributes;

import java.math.BigDecimal;
import java.util.List;

/** LLM은 장소를 index 번호로만 참조해 일정을 구성한다. */
public record PlaceCandidate(
        int index,              // 1-based 인덱스 번호
        Long placeId,
        String name,
        String address,         // placeId로 못 찾을 때 name과 함께 저장 조회 키로 쓴다
        String category,
        List<String> tags,
        String region,
        String country,
        BigDecimal latitude,
        BigDecimal longitude,
        String description,
        BigDecimal rating,
        double similarityScore, // cosine 유사도
        Integer priceLevel,     // 가격 수준(1~4). Google 유래라 약관상 삭제돼 현재 전부 null
        String openingHours,    // Google 유래라 약관상 삭제돼 현재 전부 null
        List<String> recommendedTimeSlots, // enrich 배치 산출 추천 시간대(예: "morning"), 없으면 null
        Integer recommendedDurationMinutes, // enrich 배치 산출 권장 체류시간(분), 없으면 null
        boolean userSelected,   // Manual 모드에서 사용자가 직접 고른 필수 방문 장소 (트림/교체 면제)
        Integer admissionFee,   // enrich 배치 산출 입장료(원, 0=무료), 없으면 null
        String subRegion,       // 주소에서 추출한 시/군/구 세부지역, null이면 호출부가 region 사용
        Integer userRatingCount,// Google 리뷰 수. 약관상 삭제돼 현재 전부 null
        Integer wikidataSitelinks, // Wikidata 언어판 수. null=미매칭, 0=항목없음, 1+=인기도
        String placeRole,       // 일정 속 역할(MEAL/CAFE/SIGHT/…/NOT_VISITABLE), null=미보강
        String cuisine,         // 음식 종류(MEAL만), null=미상
        boolean intentProtected,// 요청 의도의 핵심 장소. 거리·교체·유형 트림에서 빼되 영업시간·휴무 가드는 그대로
        String intentTime,      // 요청 의도의 시간대(morning/evening), null=의도 없음
        PlaceAttributes attributes, // 보강 속성, null이면 판정 함수가 카테고리·이름으로 추정
        String nameKo,          // 해외 장소의 한국어 이름, null이면 name을 쓴다. 표시용이라 조회 키로 쓰지 않는다
        Short travelerFame      // 해외 장소의 알려진 정도(0~2, 보강 산출), null=미보강·한국
) {
    public PlaceCandidate(int index, Long placeId, String name, String address,
                          String category, List<String> tags, String region, String country,
                          BigDecimal latitude, BigDecimal longitude, String description,
                          BigDecimal rating, double similarityScore,
                          Integer priceLevel, String openingHours,
                          List<String> recommendedTimeSlots, Integer recommendedDurationMinutes,
                          boolean userSelected, Integer admissionFee, String subRegion,
                          Integer userRatingCount, Integer wikidataSitelinks, String placeRole, String cuisine,
                          boolean intentProtected, String intentTime, PlaceAttributes attributes, String nameKo) {
        this(index, placeId, name, address, category, tags, region, country,
                latitude, longitude, description, rating, similarityScore,
                priceLevel, openingHours, recommendedTimeSlots, recommendedDurationMinutes,
                userSelected, admissionFee, subRegion, userRatingCount, wikidataSitelinks, placeRole, cuisine,
                intentProtected, intentTime, attributes, nameKo, null);
    }

    public PlaceCandidate(int index, Long placeId, String name, String address,
                          String category, List<String> tags, String region, String country,
                          BigDecimal latitude, BigDecimal longitude, String description,
                          BigDecimal rating, double similarityScore,
                          Integer priceLevel, String openingHours,
                          List<String> recommendedTimeSlots, Integer recommendedDurationMinutes,
                          boolean userSelected, Integer admissionFee, String subRegion,
                          Integer userRatingCount, Integer wikidataSitelinks, String placeRole, String cuisine,
                          boolean intentProtected, String intentTime, PlaceAttributes attributes) {
        this(index, placeId, name, address, category, tags, region, country,
                latitude, longitude, description, rating, similarityScore,
                priceLevel, openingHours, recommendedTimeSlots, recommendedDurationMinutes,
                userSelected, admissionFee, subRegion, userRatingCount, wikidataSitelinks, placeRole, cuisine,
                intentProtected, intentTime, attributes, (String) null);
    }

    public PlaceCandidate(int index, Long placeId, String name, String address,
                          String category, List<String> tags, String region, String country,
                          BigDecimal latitude, BigDecimal longitude, String description,
                          BigDecimal rating, double similarityScore,
                          Integer priceLevel, String openingHours,
                          List<String> recommendedTimeSlots, Integer recommendedDurationMinutes,
                          boolean userSelected, Integer admissionFee, String subRegion,
                          Integer userRatingCount, Integer wikidataSitelinks, String placeRole, String cuisine,
                          boolean intentProtected, String intentTime) {
        this(index, placeId, name, address, category, tags, region, country,
                latitude, longitude, description, rating, similarityScore,
                priceLevel, openingHours, recommendedTimeSlots, recommendedDurationMinutes,
                userSelected, admissionFee, subRegion, userRatingCount, wikidataSitelinks, placeRole, cuisine,
                intentProtected, intentTime, null);
    }

    /** 의도는 스케줄 직전(RouteOptimizer)에만 실린다. */
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

    /** 리뷰 수 미상으로 취급한다. */
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

    public PlaceCandidate(int index, Long placeId, String name, String address,
                          String category, List<String> tags, String region, String country,
                          BigDecimal latitude, BigDecimal longitude, String description,
                          BigDecimal rating, double similarityScore) {
        this(index, placeId, name, address, category, tags, region, country,
                latitude, longitude, description, rating, similarityScore, null, null, null);
    }

    public PlaceCandidate(int index, Long placeId, String name, String address,
                          String category, List<String> tags, String region, String country,
                          BigDecimal latitude, BigDecimal longitude, String description,
                          BigDecimal rating, double similarityScore,
                          Integer priceLevel, String openingHours) {
        this(index, placeId, name, address, category, tags, region, country,
                latitude, longitude, description, rating, similarityScore,
                priceLevel, openingHours, null);
    }

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
                intentProtected, intentTime, attributes, nameKo, travelerFame);
    }

    public PlaceCandidate withIntent(boolean protect, String time) {
        return new PlaceCandidate(index, placeId, name, address, category, tags, region, country,
                latitude, longitude, description, rating, similarityScore,
                priceLevel, openingHours, recommendedTimeSlots, recommendedDurationMinutes,
                userSelected, admissionFee, subRegion, userRatingCount, wikidataSitelinks, placeRole, cuisine,
                protect, time, attributes, nameKo, travelerFame);
    }

    /** 인덱스만 바꾼 사본. 의도 필드는 싣지 않는다(의도는 스케줄 직전에만 실린다). */
    public PlaceCandidate reindexed(int newIndex) {
        return new PlaceCandidate(newIndex, placeId, name, address, category, tags, region, country,
                latitude, longitude, description, rating, similarityScore,
                priceLevel, openingHours, recommendedTimeSlots, recommendedDurationMinutes,
                userSelected, admissionFee, subRegion, userRatingCount, wikidataSitelinks, placeRole, cuisine,
                false, null, attributes, nameKo, travelerFame);
    }

    /** DB에서 다시 읽은 속성·영업시간만 바꾼 사본(평가 재채점용). */
    public PlaceCandidate withRefreshedFacts(PlaceAttributes newAttributes, String newOpeningHours) {
        return new PlaceCandidate(index, placeId, name, address, category, tags, region, country,
                latitude, longitude, description, rating, similarityScore,
                priceLevel, newOpeningHours, recommendedTimeSlots, recommendedDurationMinutes,
                userSelected, admissionFee, subRegion, userRatingCount, wikidataSitelinks, placeRole, cuisine,
                intentProtected, intentTime, newAttributes, nameKo, travelerFame);
    }

    /** 사용자 필수이거나 요청 의도의 핵심이면 트림·교체·이월에서 빼지 않는다. */
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
                intentProtected, intentTime, attributes, nameKo, travelerFame);
    }

    /**
     * 점심·저녁 슬롯에 넣어도 되는 "한 끼 식사" 장소인지. 검색·선택 정리·보정·불변식 검사가 모두 이 값을 본다.
     * 복사본마다 플래그를 옮겨 적으면 한 곳만 빠져도 낡은 값이 남아 파생 계산으로 둔다.
     */
    public boolean mealEligible() {
        // 보강 역할은 LLM이 이름·설명까지 보고 정한 값이라 우선한다.
        // 집합 POI(음식거리)는 역할과 무관하게 한 끼 식사 자리가 될 수 없다.
        if (placeRole != null && !placeRole.isBlank()) {
            return "MEAL".equalsIgnoreCase(placeRole.trim())
                    && !com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants
                    .isAggregatePoi(name, category);
        }
        // 카테고리가 총칭이면 이름 추정이 베이커리를 식당으로 본다.
        if (attributes != null && attributes.venue() != null) {
            return "MEAL".equals(attributes.venue())
                    && !com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants
                    .isAggregatePoi(name, category);
        }
        return com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants
                .isMealPlace(name, category, tags);
    }

    /** 화면·스토리에 쓰는 이름. 한국어 이름이 있으면 그것이다. */
    public String displayName() {
        return hasDistinctNameKo() ? nameKo : name;
    }

    /** 선택 프롬프트용 이름. 원문을 함께 줘야 모델이 같은 이름의 다른 장소와 구분한다("센소지 (浅草寺)"). */
    public String promptName() {
        return hasDistinctNameKo() ? nameKo + " (" + name + ")" : name;
    }

    private boolean hasDistinctNameKo() {
        return nameKo != null && !nameKo.isBlank() && !nameKo.equals(name);
    }

    /** 표시·프롬프트용 지역 라벨: 세부지역 우선, 없으면 상위 region. */
    public String displayRegion() {
        return (subRegion != null && !subRegion.isBlank()) ? subRegion : region;
    }
}
