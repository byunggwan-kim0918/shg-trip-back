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
    double similarityScore
) {}
