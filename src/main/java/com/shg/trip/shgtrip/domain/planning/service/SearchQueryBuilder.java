package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 벡터 검색용 검색 쿼리 텍스트를 생성하는 유틸리티.
 *
 * VectorEnrichedInput의 normalizedDestination과 searchTags를 결합하여
 * 임베딩 변환에 사용할 검색 쿼리 텍스트를 만든다.
 */
public final class SearchQueryBuilder {

    private SearchQueryBuilder() {
        // Utility class — no instantiation
    }

    /**
     * VectorEnrichedInput에서 벡터 검색용 쿼리 텍스트를 생성한다.
     *
     * 결합 규칙:
     * 1. normalizedDestination을 가장 앞에 배치 (지역 중심 검색)
     * 2. searchTags를 공백으로 연결하여 뒤에 추가 (의미 보강)
     *
     * @param input enrichInput 결과 (normalizedDestination, searchTags 필수)
     * @return 비어있지 않은 검색 쿼리 텍스트
     * @throws IllegalArgumentException normalizedDestination이 blank이고 searchTags가 모두 blank인 경우
     */
    public static String buildSearchQuery(VectorEnrichedInput input) {
        if (input == null) {
            throw new IllegalArgumentException("VectorEnrichedInput must not be null");
        }

        List<String> parts = new ArrayList<>();

        // normalizedDestination 추가
        String destination = input.normalizedDestination();
        if (destination != null && !destination.isBlank()) {
            parts.add(destination.trim());
        }

        // searchTags 추가
        List<String> tags = input.searchTags();
        if (tags != null) {
            for (String tag : tags) {
                if (tag != null && !tag.isBlank()) {
                    parts.add(tag.trim());
                }
            }
        }

        String result = String.join(" ", parts);
        if (result.isBlank()) {
            throw new IllegalArgumentException(
                    "Cannot build search query: both normalizedDestination and searchTags are blank");
        }

        return result;
    }

    /**
     * VectorEnrichedInput에서 카테고리별 벡터 검색용 쿼리 맵을 생성한다.
     *
     * 규칙:
     * - categorySearchQueries가 있으면 그대로 사용
     * - 없으면 기존 searchTags 기반 단일 쿼리를 "attraction" 키로 래핑 (하위 호환)
     * - transportation 키가 없으면 해당 슬롯 생략
     *
     * @param input enrichInput 결과 (categorySearchQueries 사용)
     * @return 카테고리별 쿼리 맵 (key: category, value: query text)
     */
    public static Map<String, String> buildCategoryQueries(VectorEnrichedInput input) {
        if (input == null) {
            throw new IllegalArgumentException("VectorEnrichedInput must not be null");
        }

        Map<String, String> queries = new HashMap<>();

        // Haiku에서 생성한 categorySearchQueries가 있으면 사용
        Map<String, String> categoryQueries = input.categorySearchQueries();
        if (categoryQueries != null && !categoryQueries.isEmpty()) {
            queries.putAll(categoryQueries);
            return queries;
        }

        // 폴백: 기존 searchTags 기반 단일 쿼리를 "attraction"으로 래핑
        String fallbackQuery = buildSearchQuery(input);
        queries.put("attraction", fallbackQuery);

        return queries;
    }
}
