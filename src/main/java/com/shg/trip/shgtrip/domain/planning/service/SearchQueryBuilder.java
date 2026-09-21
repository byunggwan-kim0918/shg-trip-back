package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants.SearchRole;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 벡터 검색용 검색 쿼리 텍스트를 생성하는 유틸리티.
 *
 * <p>슬롯 구조는 고정 5개 역할(교통/식당/카페/숙소/관광)을 유지한다 — RouteOptimizer가 식사·숙소·
 * 허브를 채우는 데 필요한 의도적 구조다. 다만 <b>관광 슬롯만</b> 사용자가 고른 카테고리·테마별로
 * 쿼리를 쪼갠다: 여러 카테고리를 한 문장에 뭉치면 임베딩이 평균화돼(전망대+시장+둘레길 → 아무것도
 * 아닌 벡터) 사용자 입력이 검색에 반영되지 않기 때문이다.
 *
 * <p>쿼리 문구는 코드 템플릿(여행지 + 카테고리/테마 라벨)으로 만들고, Haiku의
 * categorySearchQueries는 보조 문구로만 덧붙인다(LLM이 빠뜨려도 사용자 입력은 반드시 반영되도록).
 */
public final class SearchQueryBuilder {

    private SearchQueryBuilder() {
        // Utility class — no instantiation
    }

    /**
     * 검색 슬롯 하나.
     *
     * @param key         로그·limit 배분용 식별자("attraction:beach", "cafe" 등)
     * @param role        고정 5개 역할 중 하나 (RouteOptimizer가 요구하는 구조)
     * @param queryText   임베딩 대상 쿼리 문장
     * @param labelTokens 이 슬롯의 의미 라벨 토큰 — 이름/태그가 여기 걸리면 랭킹 가점(A4)
     * @param excludedCategoryPatterns 이 슬롯에서 배제할 "명백히 틀린 대분류" 패턴(A2)
     */
    public record SearchSlot(String key, SearchRole role, String queryText, List<String> labelTokens,
                             List<String> excludedCategoryPatterns) {}

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
     * 사용자 입력(카테고리·테마)을 관통하는 검색 슬롯 목록을 만든다.
     *
     * <ul>
     *   <li>transportation: transportationHub 또는 Haiku 쿼리가 있을 때만</li>
     *   <li>restaurant / cafe / accommodation: 각 1개(고정 역할 슬롯)</li>
     *   <li>attraction: 사용자가 고른 관광 하위유형마다 1개 + 검색 의미가 있는 테마마다 1개
     *       + 기본 "대표 관광지" 1개</li>
     * </ul>
     */
    public static List<SearchSlot> buildSearchSlots(VectorEnrichedInput input) {
        if (input == null) {
            throw new IllegalArgumentException("VectorEnrichedInput must not be null");
        }

        Map<String, String> aiQueries = input.categorySearchQueries() != null
                ? input.categorySearchQueries() : Map.of();
        String destination = destinationPrefix(input);
        Set<String> userCategories = normalizedSet(input.categories());
        Set<String> userThemes = normalizedSet(input.themes());

        List<SearchSlot> slots = new ArrayList<>();

        if (aiQueries.containsKey("transportation") || input.transportationHub() != null) {
            slots.add(slot("transportation", SearchRole.TRANSPORTATION, destination,
                    "공항 기차역 버스터미널 여객터미널", aiQueries.get("transportation")));
        }
        slots.add(slot("restaurant", SearchRole.RESTAURANT, destination,
                PlaceCategoryConstants.userCategoryQuery("restaurant"), aiQueries.get("restaurant")));
        slots.add(slot("cafe", SearchRole.CAFE, destination,
                PlaceCategoryConstants.userCategoryQuery("cafe"), aiQueries.get("cafe")));
        slots.add(slot("accommodation", SearchRole.ACCOMMODATION, destination,
                PlaceCategoryConstants.userCategoryQuery("accommodation"), aiQueries.get("accommodation")));

        // 관광 슬롯: 사용자 카테고리별 → 테마별 → 기본 순. 같은 의미가 두 번 들어가지 않도록 key로 dedupe.
        Set<String> attractionKeys = new LinkedHashSet<>();
        for (String categoryId : userCategories) {
            if (!PlaceCategoryConstants.isAttractionSubtype(categoryId)) continue;
            if (!attractionKeys.add("attraction:" + categoryId)) continue;
            slots.add(slot("attraction:" + categoryId, SearchRole.ATTRACTION, destination,
                    PlaceCategoryConstants.userCategoryQuery(categoryId), null, categoryId));
        }
        for (String themeId : userThemes) {
            String themeQuery = PlaceCategoryConstants.themeQuery(themeId);
            if (themeQuery == null) continue;
            if (!attractionKeys.add("attraction:theme:" + themeId)) continue;
            slots.add(slot("attraction:theme:" + themeId, SearchRole.ATTRACTION, destination,
                    themeQuery, null));
        }
        // 기본 관광 쿼리는 항상 1개 — 사용자가 카테고리/테마를 안 골라도 대표 명소는 나와야 한다.
        if (attractionKeys.add("attraction")) {
            slots.add(slot("attraction", SearchRole.ATTRACTION, destination,
                    PlaceCategoryConstants.userCategoryQuery("attraction"), aiQueries.get("attraction")));
        }

        return slots;
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

    /** 슬롯 생성 — 쿼리 문장은 여행지+템플릿+AI 힌트, 라벨 토큰은 템플릿(의미 라벨)만. */
    private static SearchSlot slot(String key, SearchRole role, String destination,
                                   String template, String aiHint) {
        return slot(key, role, destination, template, aiHint, null);
    }

    /** categoryId를 넘기면 그 카테고리에 맞게 배제 패턴을 조정한다(나이트라이프·길거리음식). */
    private static SearchSlot slot(String key, SearchRole role, String destination,
                                   String template, String aiHint, String categoryId) {
        return new SearchSlot(key, role, compose(destination, template, aiHint), labelTokens(template),
                PlaceCategoryConstants.excludedCategoryPatterns(role, categoryId));
    }

    /** 랭킹 가점용 라벨 토큰 — 2글자 이상만(불용 토큰 제거). */
    private static List<String> labelTokens(String template) {
        if (template == null || template.isBlank()) return List.of();
        List<String> tokens = new ArrayList<>();
        for (String t : template.trim().split("\\s+")) {
            String token = t.trim().toLowerCase();
            if (token.length() >= 2 && !tokens.contains(token)) tokens.add(token);
        }
        return List.copyOf(tokens);
    }

    /** 여행지 접두어 — normalizedDestination 우선, 없으면 원본 destination. */
    private static String destinationPrefix(VectorEnrichedInput input) {
        if (input.normalizedDestination() != null && !input.normalizedDestination().isBlank()) {
            return input.normalizedDestination().trim();
        }
        return input.destination() != null ? input.destination().trim() : "";
    }

    /** "여행지 + 코드 템플릿 문구 + (있으면) Haiku 보조 문구"를 공백으로 잇는다. */
    private static String compose(String destination, String template, String aiHint) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder();
        for (String part : List.of(destination, template == null ? "" : template,
                aiHint == null ? "" : aiHint)) {
            for (String token : part.split("\\s+")) {
                String t = token.trim();
                if (t.isEmpty() || seen.putIfAbsent(t, Boolean.TRUE) != null) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(t);
            }
        }
        return sb.toString();
    }

    private static Set<String> normalizedSet(List<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values == null) return result;
        for (String v : values) {
            if (v == null || v.isBlank()) continue;
            result.add(v.trim().toLowerCase());
        }
        return result;
    }
}
