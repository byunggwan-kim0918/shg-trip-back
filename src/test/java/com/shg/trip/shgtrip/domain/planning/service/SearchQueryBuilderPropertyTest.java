package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import net.jqwik.api.*;
import net.jqwik.api.constraints.NotBlank;
import net.jqwik.api.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Feature: llm-optimization, Property 8: 검색 쿼리 생성 비공백
class SearchQueryBuilderPropertyTest {

    /**
     * Property 8: 검색 쿼리 생성 비공백
     *
     * For any 유효한 VectorEnrichedInput (normalizedDestination과 searchTags가 존재)에 대해,
     * 생성된 검색 쿼리 텍스트는 비어있지 않고 normalizedDestination 또는 searchTags의
     * 주요 키워드를 포함해야 한다.
     *
     */
    @Property(tries = 100)
    void searchQueryIsNonBlankAndContainsKeywords(
            @ForAll("validDestinations") String normalizedDestination,
            @ForAll("validSearchTags") List<String> searchTags
    ) {
        // Arrange: Build a valid VectorEnrichedInput with given destination and tags
        VectorEnrichedInput input = createEnrichedInput(normalizedDestination, searchTags);

        // Act: Build search query
        String query = SearchQueryBuilder.buildSearchQuery(input);

        // Assert 1: Query must be non-null and non-blank
        assertThat(query).isNotNull();
        assertThat(query).isNotBlank();

        // Assert 2: Query must contain normalizedDestination OR at least one searchTag keyword
        boolean containsDestination = query.contains(normalizedDestination.trim());
        boolean containsAnyTag = searchTags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .anyMatch(tag -> query.contains(tag.trim()));

        assertThat(containsDestination || containsAnyTag)
                .as("Query '%s' must contain destination '%s' or at least one tag from %s",
                        query, normalizedDestination, searchTags)
                .isTrue();
    }

    /**
     * Property 8 (supplementary): When normalizedDestination is non-blank,
     * the query always contains the destination.
     *
     */
    @Property(tries = 100)
    void searchQueryAlwaysContainsNonBlankDestination(
            @ForAll("validDestinations") String normalizedDestination,
            @ForAll("validSearchTags") List<String> searchTags
    ) {
        // Arrange
        VectorEnrichedInput input = createEnrichedInput(normalizedDestination, searchTags);

        // Act
        String query = SearchQueryBuilder.buildSearchQuery(input);

        // Assert: Query must be non-empty
        assertThat(query).isNotBlank();
    }

    /**
     * Property 8 (supplementary): When searchTags has at least one non-blank tag,
     * the query contains at least one of those tags.
     *
     */
    @Property(tries = 100)
    void searchQueryContainsAtLeastOneNonBlankTag(
            @ForAll("validDestinations") String normalizedDestination,
            @ForAll("nonBlankTagList") List<String> searchTags
    ) {
        // Arrange
        VectorEnrichedInput input = createEnrichedInput(normalizedDestination, searchTags);

        // Act
        String query = SearchQueryBuilder.buildSearchQuery(input);

        // Assert: At least one tag must appear in the query
        boolean containsAnyTag = searchTags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .anyMatch(tag -> query.contains(tag.trim()));
        assertThat(containsAnyTag)
                .as("Query '%s' must contain at least one tag from %s", query, searchTags)
                .isTrue();
    }

    // --- Arbitrary Providers ---

    @Provide
    Arbitrary<String> validDestinations() {
        return Arbitraries.of(
                "도쿄", "오사카", "파리", "런던", "뉴욕",
                "방콕", "싱가포르", "로마", "바르셀로나", "서울",
                "교토", "후쿠오카", "호놀룰루", "시드니", "베를린"
        );
    }

    @Provide
    Arbitrary<List<String>> validSearchTags() {
        Arbitrary<String> tagArbitrary = Arbitraries.of(
                "맛집", "쇼핑", "관광", "자연", "힐링",
                "역사", "야경", "카페", "액티비티", "해변",
                "문화", "예술", "사진", "가족", "로맨틱"
        );
        return tagArbitrary.list().ofMinSize(1).ofMaxSize(10);
    }

    @Provide
    Arbitrary<List<String>> nonBlankTagList() {
        Arbitrary<String> tagArbitrary = Arbitraries.of(
                "맛집", "쇼핑", "관광", "자연", "힐링",
                "역사", "야경", "카페", "액티비티", "해변"
        );
        return tagArbitrary.list().ofMinSize(1).ofMaxSize(8);
    }

    // --- Helper ---

    private VectorEnrichedInput createEnrichedInput(String normalizedDestination, List<String> searchTags) {
        return new VectorEnrichedInput(
                "원래 입력",                          // destination
                List.of("관광", "맛집"),               // themes
                List.of("관광", "음식"),               // categories
                "normal",                              // pace
                "any",                                  // transportPref
                BigDecimal.valueOf(1000000),           // budget
                LocalDate.of(2026, 8, 1),             // startDate
                LocalDate.of(2026, 8, 5),             // endDate
                "여행 설명",                           // description
                List.of(),                             // selectedPlaceIds
                normalizedDestination,                 // normalizedDestination
                "일본",                                // country
                List.of("시부야", "아사쿠사"),          // regions
                searchTags,                            // searchTags
                Map.of("1-2", List.of("시부야")),      // regionAllocation
                "MEDIUM",                              // budgetRange
                "여름 시즌",                           // seasonContext
                "도쿄 여행 컨텍스트",                   // enrichedContext
                null,                                  // transportationHub
                null                                   // categorySearchQueries
        );
    }
}
