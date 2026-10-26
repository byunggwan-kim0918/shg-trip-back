package com.shg.trip.shgtrip.domain.place.vector;

import java.math.BigDecimal;
import java.util.List;

/**
 * 벡터 유사도 검색 결과 DTO.
 *
 * @param placeId         장소 ID
 * @param name            장소명
 * @param address         주소
 * @param category        카테고리
 * @param tags            태그 목록
 * @param region          지역
 * @param country         국가
 * @param latitude        위도
 * @param longitude       경도
 * @param description     장소 설명
 * @param rating          평점
 * @param userRatingCount 리뷰 수(인기도). {@code null}은 미상 — 백필 전이거나 Google 미동기화.
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
    /** Wikidata 언어판 수 — null=미매칭, 0=항목없음, 1+=인기도. 랭킹의 주 신호. */
    Integer wikidataSitelinks,
    double similarityScore
) {
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
