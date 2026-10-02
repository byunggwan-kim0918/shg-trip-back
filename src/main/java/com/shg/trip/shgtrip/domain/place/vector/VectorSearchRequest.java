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
 * @param excludedCategoryPatterns 이 슬롯에서 "명백히 틀린 대분류"를 배제할 category ILIKE 패턴
 *                                 조각(소문자 부분 문자열). 하드 카테고리 필터가 아니라 오적재
 *                                 방어용이며, 비어 있으면 배제하지 않는다.
 * @param center                   좌표 반경 검색의 중심 {lat, lng} — null이면 반경 제한 없음
 * @param radiusKm                 center 기준 허용 반경(km). center가 있을 때만 의미가 있다.
 */
public record VectorSearchRequest(
    float[] queryVector,
    String destination,
    List<String> regions,
    List<String> categories,
    List<String> tags,
    String budgetRange,
    int limit,
    List<String> excludedCategoryPatterns,
    double[] center,
    double radiusKm
) {

    public VectorSearchRequest {
        excludedCategoryPatterns = excludedCategoryPatterns == null ? List.of() : excludedCategoryPatterns;
    }

    /** 대분류 배제 없이 생성하는 기존 호환 생성자. */
    public VectorSearchRequest(float[] queryVector, String destination, List<String> regions,
                               List<String> categories, List<String> tags, String budgetRange, int limit) {
        this(queryVector, destination, regions, categories, tags, budgetRange, limit, List.of(), null, 0);
    }

    /** 좌표 반경 없이 생성하는 기존 호환 생성자. */
    public VectorSearchRequest(float[] queryVector, String destination, List<String> regions,
                               List<String> categories, List<String> tags, String budgetRange, int limit,
                               List<String> excludedCategoryPatterns) {
        this(queryVector, destination, regions, categories, tags, budgetRange, limit,
                excludedCategoryPatterns, null, 0);
    }

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
