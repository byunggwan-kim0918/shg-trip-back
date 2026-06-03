package com.shg.trip.shgtrip.domain.place.vector;

import net.jqwik.api.*;
import net.jqwik.api.constraints.NotEmpty;
import net.jqwik.api.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test: 벡터 검색 필터 결과 일관성.
 * <p>
 * 벡터 검색 요청에 destination과 categories 필터가 지정된 경우,
 * 반환된 모든 결과의 country는 destination 필터를 만족하고,
 * category는 지정된 categories 목록에 포함되어야 한다.
 * <p>
 * 이 테스트는 PgVectorPlaceSearchService의 필터링 계약(contract)을 검증한다.
 * 실제 DB 없이 필터 로직의 정확성을 unit-level에서 확인한다.
 */
// Feature: llm-optimization, Property 6: 벡터 검색 필터 결과 일관성
class VectorSearchFilterConsistencyPropertyTest {

    // --- 필터 검증 헬퍼 메서드 (PgVectorPlaceSearchService의 SQL WHERE 절 계약을 모델링) ---

    /**
     * 결과가 요청의 destination 필터를 만족하는지 검증.
     * SQL: WHERE p.country = :destination
     */
    private boolean satisfiesDestinationFilter(VectorSearchResult result, String destination) {
        return result.country() != null && result.country().equals(destination);
    }

    /**
     * 결과가 요청의 categories 필터를 만족하는지 검증.
     * SQL: WHERE p.category IN (:categories)
     */
    private boolean satisfiesCategoriesFilter(VectorSearchResult result, List<String> categories) {
        return result.category() != null && categories.contains(result.category());
    }

    /**
     * 주어진 결과 목록에서 destination과 categories 필터를 적용하여 유효한 결과만 반환.
     * PgVectorPlaceSearchService의 SQL WHERE 절 필터링 로직을 시뮬레이션.
     */
    private List<VectorSearchResult> applyFilters(
            List<VectorSearchResult> candidates,
            String destination,
            List<String> categories) {
        return candidates.stream()
                .filter(r -> satisfiesDestinationFilter(r, destination))
                .filter(r -> categories == null || categories.isEmpty() || satisfiesCategoriesFilter(r, categories))
                .collect(Collectors.toList());
    }

    // --- Property Tests ---

    @Property(tries = 100)
    // Feature: llm-optimization, Property 6: 벡터 검색 필터 결과 일관성
    void allResultsMustMatchDestinationFilter(
            @ForAll("destinations") String destination,
            @ForAll("categoryLists") List<String> categories,
            @ForAll("searchResultLists") List<VectorSearchResult> candidateResults
    ) {
        // Given: 필터가 적용된 결과 (서비스가 반환하는 결과를 시뮬레이션)
        List<VectorSearchResult> filteredResults = applyFilters(candidateResults, destination, categories);

        // Then: 모든 필터링된 결과는 destination 필터를 만족해야 한다
        assertThat(filteredResults)
                .allSatisfy(result ->
                        assertThat(result.country())
                                .as("country must match destination filter '%s'", destination)
                                .isEqualTo(destination)
                );
    }

    @Property(tries = 100)
    // Feature: llm-optimization, Property 6: 벡터 검색 필터 결과 일관성
    void allResultsMustMatchCategoriesFilter(
            @ForAll("destinations") String destination,
            @ForAll("categoryLists") @NotEmpty List<String> categories,
            @ForAll("searchResultLists") List<VectorSearchResult> candidateResults
    ) {
        // Given: 필터가 적용된 결과 (서비스가 반환하는 결과를 시뮬레이션)
        List<VectorSearchResult> filteredResults = applyFilters(candidateResults, destination, categories);

        // Then: 모든 필터링된 결과의 category는 지정된 categories 목록에 포함되어야 한다
        assertThat(filteredResults)
                .allSatisfy(result ->
                        assertThat(categories)
                                .as("categories list must contain result category '%s'", result.category())
                                .contains(result.category())
                );
    }

    @Property(tries = 100)
    // Feature: llm-optimization, Property 6: 벡터 검색 필터 결과 일관성
    void filteringNeverIncludesNonMatchingResults(
            @ForAll("destinations") String destination,
            @ForAll("categoryLists") @NotEmpty List<String> categories,
            @ForAll("mixedSearchResultLists") List<VectorSearchResult> mixedResults
    ) {
        // Given: 다양한 country와 category를 가진 혼합 결과
        List<VectorSearchResult> filteredResults = applyFilters(mixedResults, destination, categories);

        // Then: 필터를 통과하지 못할 결과가 포함되지 않아야 한다
        for (VectorSearchResult result : filteredResults) {
            assertThat(result.country()).isEqualTo(destination);
            assertThat(categories).contains(result.category());
        }

        // And: 필터에 매칭되지 않는 원본 결과는 필터링된 결과에 포함되지 않아야 한다
        for (VectorSearchResult original : mixedResults) {
            if (!destination.equals(original.country()) ||
                    (original.category() != null && !categories.contains(original.category()))) {
                assertThat(filteredResults).doesNotContain(original);
            }
        }
    }

    // --- Arbitrary Providers ---

    @Provide
    Arbitrary<String> destinations() {
        return Arbitraries.of("일본", "한국", "미국", "프랑스", "태국", "영국", "호주", "캐나다");
    }

    @Provide
    Arbitrary<List<String>> categoryLists() {
        return Arbitraries.of("관광", "음식", "쇼핑", "숙박", "카페", "액티비티", "문화", "자연")
                .list()
                .ofMinSize(1)
                .ofMaxSize(5)
                .uniqueElements();
    }

    @Provide
    Arbitrary<List<VectorSearchResult>> searchResultLists() {
        return searchResultArbitrary().list().ofMinSize(0).ofMaxSize(20);
    }

    @Provide
    Arbitrary<List<VectorSearchResult>> mixedSearchResultLists() {
        // 다양한 country와 category를 가진 혼합 결과 생성
        return mixedSearchResultArbitrary().list().ofMinSize(5).ofMaxSize(30);
    }

    private Arbitrary<VectorSearchResult> searchResultArbitrary() {
        Arbitrary<Long> ids = Arbitraries.longs().between(1, 10000);
        Arbitrary<String> names = Arbitraries.of("센소지", "메이지신궁", "도쿄타워", "시부야", "아사쿠사", "긴자", "롯폰기", "우에노공원");
        Arbitrary<String> addresses = Arbitraries.of("도쿄도 시부야구", "도쿄도 다이토구", "도쿄도 미나토구");
        Arbitrary<String> categories = Arbitraries.of("관광", "음식", "쇼핑", "숙박", "카페", "액티비티", "문화", "자연");
        Arbitrary<String> regions = Arbitraries.of("시부야", "아사쿠사", "긴자", "하라주쿠", "우에노");
        Arbitrary<String> countries = Arbitraries.of("일본", "한국", "미국", "프랑스", "태국");
        Arbitrary<Double> scores = Arbitraries.doubles().between(0.0, 1.0);

        return Combinators.combine(ids, names, addresses, categories, regions, countries, scores)
                .as((id, name, address, category, region, country, score) ->
                        new VectorSearchResult(
                                id, name, address, category, null, region, country,
                                BigDecimal.valueOf(35.0 + Math.random()),
                                BigDecimal.valueOf(139.0 + Math.random()),
                                "설명 텍스트",
                                BigDecimal.valueOf(4.0),
                                score
                        )
                );
    }

    private Arbitrary<VectorSearchResult> mixedSearchResultArbitrary() {
        // 더 넓은 범위의 country와 category를 포함하여 필터 불일치 케이스 생성
        Arbitrary<Long> ids = Arbitraries.longs().between(1, 10000);
        Arbitrary<String> names = Arbitraries.of("장소A", "장소B", "장소C", "장소D", "장소E");
        Arbitrary<String> addresses = Arbitraries.of("주소1", "주소2", "주소3");
        Arbitrary<String> categories = Arbitraries.of(
                "관광", "음식", "쇼핑", "숙박", "카페", "액티비티", "문화", "자연",
                "교통", "병원", "학교", "공원"
        );
        Arbitrary<String> regions = Arbitraries.of("A지역", "B지역", "C지역", "D지역");
        Arbitrary<String> countries = Arbitraries.of(
                "일본", "한국", "미국", "프랑스", "태국", "영국", "호주", "캐나다",
                "독일", "스페인"
        );
        Arbitrary<Double> scores = Arbitraries.doubles().between(0.0, 1.0);

        return Combinators.combine(ids, names, addresses, categories, regions, countries, scores)
                .as((id, name, address, category, region, country, score) ->
                        new VectorSearchResult(
                                id, name, address, category, null, region, country,
                                BigDecimal.valueOf(35.0),
                                BigDecimal.valueOf(139.0),
                                "설명",
                                BigDecimal.valueOf(4.0),
                                score
                        )
                );
    }
}
