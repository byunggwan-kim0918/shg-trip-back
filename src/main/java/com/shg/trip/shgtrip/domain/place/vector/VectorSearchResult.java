package com.shg.trip.shgtrip.domain.place.vector;

import com.shg.trip.shgtrip.domain.place.entity.PlaceAttributes;

import java.math.BigDecimal;
import java.util.List;

/**
 * @param userRatingCount 리뷰 수(인기도). {@code null}은 미상이다(백필 전이거나 Google 미동기화).
 *                        평점만으로는 리뷰 2~3개짜리 5.0이 명소를 이기므로 랭킹이 함께 본다.
 * @param similarityScore cosine 유사도 점수 (0.0 ~ 1.0)
 */
public record VectorSearchResult(
    Long placeId,
    String name,
    String address,
    String category,
    List<String> tags,
    String region,
    String country,
    BigDecimal latitude,
    BigDecimal longitude,
    String description,
    BigDecimal rating,
    Integer userRatingCount,
    /** Wikidata 언어판 수. null=미매칭, 0=항목없음, 1+=인기도. 랭킹의 주 신호. */
    Integer wikidataSitelinks,
    double similarityScore,
    /** 일정 속 역할(보강 산출). null이면 미보강. */
    String placeRole,
    /** 음식 종류(보강 산출, 식사 장소만). null이면 미상. */
    String cuisine,
    /** Wikidata 항목 id(Q…). null이면 미매칭. 출처별 중복 행 판정에 쓴다. */
    String wikidataId,
    /** 보강 속성. null이면 미보강. */
    PlaceAttributes attributes,
    /** 한국어 표시 이름(해외 장소). null이면 name을 쓴다. */
    String nameKo,
    /** 알려진 정도(해외 장소 보강 산출, 0~2). null이면 미보강이거나 한국 장소다. */
    Short travelerFame,
    /** 관광공사(TourAPI) 관광 정보와 연결된 장소. 국내에서는 언어판 수보다 여행지 여부를 잘 가린다. */
    boolean tourismListed
) {
    /** 관광공사 연결 여부 없이 생성하는 호환 생성자. */
    public VectorSearchResult(Long placeId, String name, String address, String category,
                              List<String> tags, String region, String country,
                              BigDecimal latitude, BigDecimal longitude, String description,
                              BigDecimal rating, Integer userRatingCount, Integer wikidataSitelinks,
                              double similarityScore, String placeRole, String cuisine, String wikidataId,
                              PlaceAttributes attributes, String nameKo, Short travelerFame) {
        this(placeId, name, address, category, tags, region, country, latitude, longitude,
                description, rating, userRatingCount, wikidataSitelinks, similarityScore, placeRole, cuisine,
                wikidataId, attributes, nameKo, travelerFame, false);
    }

    /** 알려진 정도 없이 생성하는 호환 생성자. */
    public VectorSearchResult(Long placeId, String name, String address, String category,
                              List<String> tags, String region, String country,
                              BigDecimal latitude, BigDecimal longitude, String description,
                              BigDecimal rating, Integer userRatingCount, Integer wikidataSitelinks,
                              double similarityScore, String placeRole, String cuisine, String wikidataId,
                              PlaceAttributes attributes, String nameKo) {
        this(placeId, name, address, category, tags, region, country, latitude, longitude,
                description, rating, userRatingCount, wikidataSitelinks, similarityScore, placeRole, cuisine,
                wikidataId, attributes, nameKo, null, false);
    }

    /** 한국어 이름 없이 생성하는 호환 생성자. */
    public VectorSearchResult(Long placeId, String name, String address, String category,
                              List<String> tags, String region, String country,
                              BigDecimal latitude, BigDecimal longitude, String description,
                              BigDecimal rating, Integer userRatingCount, Integer wikidataSitelinks,
                              double similarityScore, String placeRole, String cuisine, String wikidataId,
                              PlaceAttributes attributes) {
        this(placeId, name, address, category, tags, region, country, latitude, longitude,
                description, rating, userRatingCount, wikidataSitelinks, similarityScore, placeRole, cuisine,
                wikidataId, attributes, (String) null);
    }

    /** 속성 없이 생성하는 호환 생성자. */
    public VectorSearchResult(Long placeId, String name, String address, String category,
                              List<String> tags, String region, String country,
                              BigDecimal latitude, BigDecimal longitude, String description,
                              BigDecimal rating, Integer userRatingCount, Integer wikidataSitelinks,
                              double similarityScore, String placeRole, String cuisine, String wikidataId) {
        this(placeId, name, address, category, tags, region, country, latitude, longitude,
                description, rating, userRatingCount, wikidataSitelinks, similarityScore, placeRole, cuisine,
                wikidataId, null);
    }

    /** 항목 id 없이 생성하는 호환 생성자. */
    public VectorSearchResult(Long placeId, String name, String address, String category,
                              List<String> tags, String region, String country,
                              BigDecimal latitude, BigDecimal longitude, String description,
                              BigDecimal rating, Integer userRatingCount, Integer wikidataSitelinks,
                              double similarityScore, String placeRole, String cuisine) {
        this(placeId, name, address, category, tags, region, country, latitude, longitude,
                description, rating, userRatingCount, wikidataSitelinks, similarityScore, placeRole, cuisine, null);
    }

    /** 역할·음식 종류 없이 생성하는 호환 생성자(미보강 취급). */
    public VectorSearchResult(Long placeId, String name, String address, String category,
                              List<String> tags, String region, String country,
                              BigDecimal latitude, BigDecimal longitude, String description,
                              BigDecimal rating, Integer userRatingCount, Integer wikidataSitelinks,
                              double similarityScore) {
        this(placeId, name, address, category, tags, region, country, latitude, longitude,
                description, rating, userRatingCount, wikidataSitelinks, similarityScore, null, null);
    }

    /** sitelinks 없이 생성하는 호환 생성자(미매칭 취급). */
    public VectorSearchResult(Long placeId, String name, String address, String category,
                              List<String> tags, String region, String country,
                              BigDecimal latitude, BigDecimal longitude, String description,
                              BigDecimal rating, Integer userRatingCount, double similarityScore) {
        this(placeId, name, address, category, tags, region, country, latitude, longitude,
                description, rating, userRatingCount, null, similarityScore);
    }
    /**
     * 리뷰 수를 모르는 결과(= {@code userRatingCount == null}). 백필 전 DB 행이 이 상태다.
     * 랭킹은 이 경우를 "무명임을 안다"보다 위인 중립으로 다룬다
     * ({@code VectorSearchQueryService.prominence}).
     */
    public VectorSearchResult(Long placeId, String name, String address, String category,
                              List<String> tags, String region, String country,
                              BigDecimal latitude, BigDecimal longitude, String description,
                              BigDecimal rating, double similarityScore) {
        this(placeId, name, address, category, tags, region, country, latitude, longitude,
                description, rating, null, similarityScore);
    }
}
