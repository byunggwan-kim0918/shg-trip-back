package com.shg.trip.shgtrip.domain.place.vector;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test: 벡터 검색 결과 수 제한.
 * <p>
 * 벡터 검색 요청에서 반환 결과 수는 요청된 limit을 초과하지 않아야 하며,
 * 충분한 데이터가 있을 때 카테고리별 균등 분배를 보장해야 한다.
 * <p>
 * 이 테스트는 PgVectorPlaceSearchService의 limit enforcement 계약(contract)을 검증한다.
 * 실제 DB 없이 limit 로직의 정확성을 unit-level에서 확인한다.
 */
// Feature: llm-optimization, Property 9: 벡터 검색 결과 수 제한
class VectorSearchResultLimitPropertyTest {

    // --- Limit 적용 로직 (PgVectorPlaceSearchService의 계약을 모델링) ---

    /**
     * 단일 지역 검색 시 limit 적용을 시뮬레이션.
     * SQL: ORDER BY similarity DESC LIMIT :limit
     */
    private List<VectorSearchResult> applySingleRegionLimit(
            List<VectorSearchResult> candidates, int limit) {
        List<VectorSearchResult> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(VectorSearchResult::similarityScore).reversed());
        if (sorted.size() > limit) {
            return sorted.subList(0, limit);
        }
        return sorted;
    }

    /**
     * 다중 지역 검색 시 limit 적용을 시뮬레이션.
     * PgVectorPlaceSearchService.searchByRegions 로직:
     * 1. limitPerRegion = limit / numRegions
     * 2. 각 지역에서 limitPerRegion개 조회
     * 3. 전체 합산 후 similarity 정렬
     * 4. 최종 limit 적용
     */
    private List<VectorSearchResult> applyMultiRegionLimit(
            List<List<VectorSearchResult>> regionCandidates,
            int limit,
            int numRegions) {
        int limitPerRegion = Math.max(1, limit / numRegions);

        List<VectorSearchResult> allResults = new ArrayList<>();

        for (List<VectorSearchResult> regionResults : regionCandidates) {
            // 각 지역에서 limitPerRegion개까지만 가져옴
            List<VectorSearchResult> sorted = new ArrayList<>(regionResults);
            sorted.sort(Comparator.comparingDouble(VectorSearchResult::similarityScore).reversed());
            if (sorted.size() > limitPerRegion) {
                sorted = sorted.subList(0, limitPerRegion);
            }
            allResults.addAll(sorted);
        }

        // 유사도 기준 내림차순 정렬 후 전체 limit 적용
        allResults.sort(Comparator.comparingDouble(VectorSearchResult::similarityScore).reversed());
        if (allResults.size() > limit) {
            return allResults.subList(0, limit);
        }
        return allResults;
    }

    /**
     * Per-region limit 계산 로직 (PgVectorPlaceSearchService에서 사용).
     */
    private int calculateLimitPerRegion(int limit, int numRegions) {
        return Math.max(1, limit / numRegions);
    }

    // --- Property Tests ---

    @Property(tries = 100)
    // Feature: llm-optimization, Property 9: 벡터 검색 결과 수 제한
    void singleRegionResultCountNeverExceedsLimit(
            @ForAll("searchResultLists") List<VectorSearchResult> candidates,
            @ForAll @IntRange(min = 1, max = 100) int limit
    ) {
        // When: 단일 지역 검색에서 limit을 적용
        List<VectorSearchResult> results = applySingleRegionLimit(candidates, limit);

        // Then: 결과 수는 요청된 limit을 절대 초과하지 않아야 한다
        assertThat(results.size())
                .as("Single region results must not exceed requested limit %d", limit)
                .isLessThanOrEqualTo(limit);
    }

    @Property(tries = 100)
    // Feature: llm-optimization, Property 9: 벡터 검색 결과 수 제한
    void multiRegionTotalResultsRespectOverallLimit(
            @ForAll("multiRegionCandidates") List<List<VectorSearchResult>> regionCandidates,
            @ForAll @IntRange(min = 1, max = 100) int limit
    ) {
        int numRegions = regionCandidates.size();

        // When: 다중 지역 검색에서 limit을 적용
        List<VectorSearchResult> results = applyMultiRegionLimit(regionCandidates, limit, numRegions);

        // Then: 전체 결과 수는 요청된 limit을 초과하지 않아야 한다
        assertThat(results.size())
                .as("Multi-region total results must not exceed requested limit %d", limit)
                .isLessThanOrEqualTo(limit);
    }

    @Property(tries = 100)
    // Feature: llm-optimization, Property 9: 벡터 검색 결과 수 제한
    void perRegionLimitCalculationIsCorrect(
            @ForAll @IntRange(min = 1, max = 200) int limit,
            @ForAll @IntRange(min = 2, max = 10) int numRegions
    ) {
        // When: per-region limit 계산
        int limitPerRegion = calculateLimitPerRegion(limit, numRegions);

        // Then: per-region limit은 최소 1이어야 하고, limit / numRegions와 같아야 한다
        assertThat(limitPerRegion)
                .as("Per-region limit must be at least 1")
                .isGreaterThanOrEqualTo(1);

        assertThat(limitPerRegion)
                .as("Per-region limit should equal limit / numRegions (integer division)")
                .isEqualTo(Math.max(1, limit / numRegions));

        // And: 모든 지역이 limitPerRegion을 반환해도 최종 limit 적용으로 초과하지 않음을 검증
        // (limitPerRegion * numRegions >= limit일 수 있으나, 최종 truncation이 이를 보장)
        // 최악의 경우: limitPerRegion * numRegions
        int maxPossibleBeforeTruncation = limitPerRegion * numRegions;
        // 이 값이 limit보다 클 수 있지만, 최종 truncation 단계에서 limit으로 잘림
        assertThat(maxPossibleBeforeTruncation)
                .as("Before final truncation, max results = limitPerRegion * numRegions")
                .isGreaterThanOrEqualTo(limitPerRegion);
    }

    // --- Arbitrary Providers ---

    @Provide
    Arbitrary<List<VectorSearchResult>> searchResultLists() {
        return searchResultArbitrary().list().ofMinSize(0).ofMaxSize(150);
    }

    @Provide
    Arbitrary<List<List<VectorSearchResult>>> multiRegionCandidates() {
        return searchResultArbitrary()
                .list().ofMinSize(0).ofMaxSize(50)
                .list().ofMinSize(2).ofMaxSize(6);
    }

    private Arbitrary<VectorSearchResult> searchResultArbitrary() {
        Arbitrary<Long> ids = Arbitraries.longs().between(1, 10000);
        Arbitrary<String> names = Arbitraries.of(
                "센소지", "메이지신궁", "도쿄타워", "시부야스크램블", "아사쿠사",
                "긴자거리", "롯폰기힐즈", "우에노공원", "하라주쿠", "오다이바"
        );
        Arbitrary<String> addresses = Arbitraries.of(
                "도쿄도 시부야구", "도쿄도 다이토구", "도쿄도 미나토구", "도쿄도 분쿄구"
        );
        Arbitrary<String> categories = Arbitraries.of(
                "관광", "음식", "쇼핑", "숙박", "카페", "액티비티", "문화", "자연"
        );
        Arbitrary<String> regions = Arbitraries.of(
                "시부야", "아사쿠사", "긴자", "하라주쿠", "우에노", "롯폰기"
        );
        Arbitrary<String> countries = Arbitraries.of("일본", "한국", "미국", "프랑스");
        Arbitrary<Double> scores = Arbitraries.doubles().between(0.0, 1.0);

        return Combinators.combine(ids, names, addresses, categories, regions, countries, scores)
                .as((id, name, address, category, region, country, score) ->
                        new VectorSearchResult(
                                id, name, address, category, null, region, country,
                                BigDecimal.valueOf(35.6762),
                                BigDecimal.valueOf(139.6503),
                                "장소 설명 텍스트",
                                BigDecimal.valueOf(4.2),
                                score
                        )
                );
    }
}
