package com.shg.trip.shgtrip.domain.place.vector;

import java.util.List;

/**
 * 벡터 유사도 검색 요청 DTO.
 *
 * @param queryVector   검색 쿼리 임베딩 벡터 (1536 dimensions)
 * @param destination   country + region 필터 (예: "일본")
 * @param regions       지역 목록 필터 (5일+ 여행 시 지역별 분리 조회용)
 * @param categories    카테고리 필터 목록
 * @param tags          태그 필터 목록
 * @param budgetRange   예산 범위 필터 (LOW, MEDIUM, HIGH, LUXURY) — 선택적
 * @param limit         반환 결과 수 제한 (기본 80)
 */
public record VectorSearchRequest(
    float[] queryVector,
    String destination,
    List<String> regions,
    List<String> categories,
    List<String> tags,
    String budgetRange,
    int limit
) {

    /**
     * 기본 limit(80)을 적용하는 팩토리 메서드.
     */
    public static VectorSearchRequest of(
            float[] queryVector,
            String destination,
            List<String> regions,
            List<String> categories,
            List<String> tags,
            String budgetRange) {
        return new VectorSearchRequest(queryVector, destination, regions, categories, tags, budgetRange, 80);
    }
}
