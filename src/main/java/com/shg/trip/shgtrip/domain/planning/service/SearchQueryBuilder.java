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
 * 슬롯은 RouteOptimizer가 식사·숙소·허브를 채우는 데 필요한 고정 5개 역할(교통/식당/카페/숙소/관광)이다.
 * 관광 슬롯만 카테고리·테마별로 쪼갠다. 한 문장에 뭉치면 임베딩이 평균화돼 사용자 입력이 검색에 안 잡힌다.
 *
 * <p>LLM이 빠뜨려도 사용자 입력이 반영되도록 쿼리는 코드 템플릿으로 만들고,
 * Haiku의 categorySearchQueries는 보조 문구로만 덧붙인다.
 */
public final class SearchQueryBuilder {

    /** 커플·가족 여행의 숙소 슬롯 기본 질의. 다인실 대신 독립 객실 숙소로 향하게 한다. */
    static final String COUPLE_FAMILY_LODGING_QUERY = "펜션 호텔 리조트 독채 숙소";

    private SearchQueryBuilder() {
    }

    /**
     * @param key         로그·limit 배분용 식별자("attraction:beach", "cafe" 등)
     * @param role        고정 5개 역할 중 하나 (RouteOptimizer가 요구하는 구조)
     * @param queryText   임베딩 대상 쿼리 문장
     * @param labelTokens 이 슬롯의 의미 라벨 토큰. 이름·태그가 여기 걸리면 랭킹 가점
     * @param excludedCategoryPatterns 이 슬롯에서 배제할 "명백히 틀린 대분류" 패턴
     * @param baseQueryText AI 힌트를 뺀 쿼리 문장. 힌트가 없으면 queryText와 같다
     */
    public record SearchSlot(String key, SearchRole role, String queryText, List<String> labelTokens,
                             List<String> excludedCategoryPatterns, String baseQueryText) {
        public SearchSlot(String key, SearchRole role, String queryText, List<String> labelTokens,
                          List<String> excludedCategoryPatterns) {
            this(key, role, queryText, labelTokens, excludedCategoryPatterns, queryText);
        }
    }

    /**
     * @param input enrichInput 결과 (normalizedDestination, searchTags 필수)
     * @return 비어있지 않은 검색 쿼리 텍스트
     * @throws IllegalArgumentException normalizedDestination이 blank이고 searchTags가 모두 blank인 경우
     */
    public static String buildSearchQuery(VectorEnrichedInput input) {
        if (input == null) {
            throw new IllegalArgumentException("VectorEnrichedInput must not be null");
        }

        List<String> parts = new ArrayList<>();

        String destination = input.normalizedDestination();
        if (destination != null && !destination.isBlank()) {
            parts.add(destination.trim());
        }

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
        // 커플·가족은 게스트하우스를 결국 걸러낸다.
        // 기본 질의("호텔 리조트 게스트하우스")로 찾으면 슬롯 자리를 게스트하우스가 먹는다.
        String lodgingQuery = input.companion().avoidsDormLodging()
                ? COUPLE_FAMILY_LODGING_QUERY : PlaceCategoryConstants.userCategoryQuery("accommodation");
        slots.add(slot("accommodation", SearchRole.ACCOMMODATION, destination,
                lodgingQuery, aiQueries.get("accommodation")));

        // 같은 의미가 두 번 들어가지 않도록 key로 dedupe한다.
        Set<String> attractionKeys = new LinkedHashSet<>();
        // 로컬 시장은 미식 여행의 기본 코스라 "시장"을 고르지 않아도 후보로 띄운다.
        // 넣을지는 선택 단계가 정한다. 영업일 데이터가 없는 오일장을 강제로 넣으면 장이 안 서는 날 보내게 된다.
        if (userThemes.contains("food") && !userCategories.contains("market")) {
            attractionKeys.add("attraction:market");
            slots.add(slot("attraction:market", SearchRole.ATTRACTION, destination,
                    PlaceCategoryConstants.userCategoryQuery("market"), null, "market"));
        }
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
        // 사용자가 카테고리·테마를 안 골라도 대표 명소는 나와야 한다.
        if (attractionKeys.add("attraction")) {
            slots.add(slot("attraction", SearchRole.ATTRACTION, destination,
                    PlaceCategoryConstants.userCategoryQuery("attraction"), aiQueries.get("attraction")));
        }

        return slots;
    }

    /**
     * categorySearchQueries가 없으면 searchTags 단일 쿼리를 "attraction" 키로 감싼다(하위 호환).
     *
     * @param input enrichInput 결과 (categorySearchQueries 사용)
     * @return 카테고리별 쿼리 맵 (key: category, value: query text)
     */
    public static Map<String, String> buildCategoryQueries(VectorEnrichedInput input) {
        if (input == null) {
            throw new IllegalArgumentException("VectorEnrichedInput must not be null");
        }

        Map<String, String> queries = new HashMap<>();

        Map<String, String> categoryQueries = input.categorySearchQueries();
        if (categoryQueries != null && !categoryQueries.isEmpty()) {
            queries.putAll(categoryQueries);
            return queries;
        }

        String fallbackQuery = buildSearchQuery(input);
        queries.put("attraction", fallbackQuery);

        return queries;
    }

    /** 라벨 토큰은 AI 힌트 없이 템플릿(의미 라벨)에서만 뽑는다. */
    private static SearchSlot slot(String key, SearchRole role, String destination,
                                   String template, String aiHint) {
        return slot(key, role, destination, template, aiHint, null);
    }

    /** categoryId를 넘기면 그 카테고리에 맞게 배제 패턴을 조정한다(나이트라이프·길거리음식). */
    private static SearchSlot slot(String key, SearchRole role, String destination,
                                   String template, String aiHint, String categoryId) {
        return new SearchSlot(key, role, compose(destination, template, aiHint), labelTokens(template),
                PlaceCategoryConstants.excludedCategoryPatterns(role, categoryId), compose(destination, template, null));
    }

    /** 1글자 토큰은 불용어로 보고 버린다. */
    private static List<String> labelTokens(String template) {
        if (template == null || template.isBlank()) return List.of();
        List<String> tokens = new ArrayList<>();
        for (String t : template.trim().split("\\s+")) {
            String token = t.trim().toLowerCase();
            if (token.length() >= 2 && !tokens.contains(token)) tokens.add(token);
        }
        return List.copyOf(tokens);
    }

    private static String destinationPrefix(VectorEnrichedInput input) {
        if (input.normalizedDestination() != null && !input.normalizedDestination().isBlank()) {
            return input.normalizedDestination().trim();
        }
        return input.destination() != null ? input.destination().trim() : "";
    }

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
