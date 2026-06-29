package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.embedding.EmbeddingService;
import com.shg.trip.shgtrip.domain.place.vector.PlaceVectorSearchService;
import com.shg.trip.shgtrip.domain.place.vector.VectorSearchRequest;
import com.shg.trip.shgtrip.domain.place.vector.VectorSearchResult;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 벡터 검색 쿼리 오케스트레이션 서비스.
 *
 * VectorEnrichedInput를 입력으로 받아 전체 벡터 검색 파이프라인을 수행한다:
 * 1. SearchQueryBuilder로 쿼리 텍스트 생성
 * 2. EmbeddingService로 쿼리 벡터 생성
 * 3. VectorSearchRequest 구성 (필터 포함)
 * 4. PlaceVectorSearchService로 검색 실행
 * 5. VectorSearchResult → PlaceCandidate 변환 (1-based indexing)
 *
 * 5일+ 여행 시 regionAllocation 기반 지역별 분리 검색을 수행한다.
 */
@Service
public class VectorSearchQueryService {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchQueryService.class);

    private static final int MIN_PER_CATEGORY = 10;
    private static final int MAX_PER_CATEGORY = 20;
    private static final int MIN_TOTAL = 30;
    private static final int MAX_TOTAL = 80;

    private final EmbeddingService embeddingService;
    private final PlaceVectorSearchService placeVectorSearchService;

    public VectorSearchQueryService(EmbeddingService embeddingService,
                                    PlaceVectorSearchService placeVectorSearchService) {
        this.embeddingService = embeddingService;
        this.placeVectorSearchService = placeVectorSearchService;
    }

    /**
     * VectorEnrichedInput 기반으로 벡터 검색을 수행하여 PlaceCandidate 목록을 반환한다.
     * 카테고리별 분리 검색을 수행하여 균등한 후보 분포를 보장한다.
     *
     * @param input enrichInput 결과
     * @return 1-based 인덱스가 부여된 후보 장소 목록
     */
    public List<PlaceCandidate> search(VectorEnrichedInput input) {
        int totalLimit = calculateTotalLimit(input);
        Map<String, String> categoryQueries = SearchQueryBuilder.buildCategoryQueries(input);
        Map<String, Integer> categoryLimits = buildCategoryLimits(categoryQueries, totalLimit);

        log.debug("카테고리별 검색 limit: {}", categoryLimits);

        List<VectorSearchResult> allResults = new ArrayList<>();

        for (Map.Entry<String, String> entry : categoryQueries.entrySet()) {
            String category = entry.getKey();
            String queryText = entry.getValue();
            int limit = categoryLimits.getOrDefault(category, 10);

            log.debug("카테고리 '{}' 벡터 검색: 쿼리='{}', limit={}", category, queryText, limit);

            float[] queryVector = embeddingService.embed(queryText);

            List<VectorSearchResult> results;
            if (shouldSplitByRegion(input)) {
                results = searchByRegionAndCategory(input, queryVector, category, limit);
            } else {
                VectorSearchRequest request = buildRequest(input, queryVector, null, category, limit);
                results = placeVectorSearchService.search(request);
            }

            allResults.addAll(results);
            log.debug("카테고리 '{}' 검색 완료: {}개 결과", category, results.size());
        }

        log.info("전체 벡터 검색 결과: {}개 장소 반환 (요청 총 limit: {})", allResults.size(), totalLimit);

        return convertToCandidates(allResults);
    }

    /**
     * 여행 일수에 따른 총 반환 수를 계산한다.
     * Formula: days * 20, capped at MAX_TOTAL(80), min MIN_TOTAL(60).
     */
    int calculateTotalLimit(VectorEnrichedInput input) {
        long days = calculateTripDays(input);
        int calculated = (int) (days * 10);
        return Math.max(MIN_TOTAL, Math.min(calculated, MAX_TOTAL));
    }

    /**
     * 카테고리별 벡터 검색 limit을 배분한다.
     * 규칙:
     * - attraction: 40% (days × 2 최소)
     * - restaurant: 25% (days × 3 최소)
     * - accommodation: 10% (3 최소)
     * - transportation: 10% (2 최소, 키 없으면 생략)
     * - cafe: 15% (days 최소)
     *
     * transportation이 없으면 그 10%를 attraction에 추가 배분.
     */
    Map<String, Integer> buildCategoryLimits(Map<String, String> categoryQueries, int totalLimit) {
        long days = Math.max(1, totalLimit / 10);
        Map<String, Integer> limits = new HashMap<>();

        boolean hasTransportation = categoryQueries.containsKey("transportation");

        if (hasTransportation) {
            limits.put("transportation", Math.max(2, (int) (totalLimit * 0.1)));
            limits.put("restaurant", Math.max((int) (days * 3), (int) (totalLimit * 0.25)));
            limits.put("attraction", Math.max((int) (days * 2), (int) (totalLimit * 0.40)));
            limits.put("accommodation", Math.max(3, (int) (totalLimit * 0.1)));
            limits.put("cafe", Math.max((int) days, (int) (totalLimit * 0.15)));
        } else {
            // transportation 없으면 그 10%를 attraction에 추가 (50%)
            limits.put("restaurant", Math.max((int) (days * 3), (int) (totalLimit * 0.25)));
            limits.put("attraction", Math.max((int) (days * 2), (int) (totalLimit * 0.50)));
            limits.put("accommodation", Math.max(3, (int) (totalLimit * 0.1)));
            limits.put("cafe", Math.max((int) days, (int) (totalLimit * 0.15)));
        }

        return limits;
    }

    /**
     * regionAllocation이 존재하고 여행이 5일 이상인 경우 지역별 분리 검색을 수행해야 하는지 확인.
     */
    boolean shouldSplitByRegion(VectorEnrichedInput input) {
        return input.regionAllocation() != null
                && !input.regionAllocation().isEmpty()
                && calculateTripDays(input) >= 5;
    }

    /**
     * 지역별 및 카테고리별 분리 검색을 수행한다.
     * regionAllocation 맵의 각 엔트리(일차범위 → 지역 리스트)마다 해당 카테고리 검색을 수행.
     */
    private List<VectorSearchResult> searchByRegionAndCategory(VectorEnrichedInput input,
                                                              float[] queryVector,
                                                              String category,
                                                              int categoryLimit) {
        Map<String, List<String>> regionAllocation = input.regionAllocation();
        int regionCount = regionAllocation.size();
        int limitPerRegion = Math.max(1, categoryLimit / regionCount);

        List<VectorSearchResult> allResults = new ArrayList<>();

        for (Map.Entry<String, List<String>> entry : regionAllocation.entrySet()) {
            List<String> regions = entry.getValue();
            log.debug("지역·카테고리 검색 - 카테고리: {}, 일차: {}, 지역: {}, limit: {}",
                    category, entry.getKey(), regions, limitPerRegion);

            VectorSearchRequest request = buildRequest(input, queryVector, regions, category, limitPerRegion);
            List<VectorSearchResult> regionResults = placeVectorSearchService.search(request);
            allResults.addAll(regionResults);
        }

        return allResults;
    }

    /**
     * VectorSearchRequest를 구성한다.
     * enrichInput 프롬프트에서 country=ISO코드, regions=영어 도시명으로 반환하도록 지정되어 있음.
     * category: 단일 카테고리 (벡터 검색 시 해당 카테고리로만 필터링)
     */
    private VectorSearchRequest buildRequest(VectorEnrichedInput input,
                                              float[] queryVector,
                                              List<String> regions,
                                              String category,
                                              int limit) {
        List<String> searchRegions = regions != null ? regions : input.regions();
        List<String> categoryFilter = category != null ? List.of(category) : input.categories();

        log.debug("벡터 검색 필터: country='{}', regions={}, category={}, limit={}",
                input.country(), searchRegions, category, limit);

        return new VectorSearchRequest(
                queryVector,
                input.country(),
                searchRegions,
                categoryFilter,
                input.searchTags(),
                input.budgetRange(),
                limit
        );
    }

    /**
     * VectorSearchResult 목록을 PlaceCandidate 목록으로 변환한다.
     * 1-based 연속 인덱싱을 유지한다.
     * priceLevel, openingHours는 DB에서 별도로 enrichment 단계에서 채운다.
     */
    List<PlaceCandidate> convertToCandidates(List<VectorSearchResult> results) {
        List<PlaceCandidate> candidates = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            VectorSearchResult r = results.get(i);
            candidates.add(new PlaceCandidate(
                    i + 1,  // 1-based index
                    r.placeId(),
                    r.name(),
                    r.address(),
                    r.category(),
                    r.tags(),
                    r.region(),
                    r.country(),
                    r.latitude(),
                    r.longitude(),
                    r.description(),
                    r.rating(),
                    r.similarityScore(),
                    null,  // priceLevel (enrichCandidatesFromDb에서 채움)
                    null   // openingHours (enrichCandidatesFromDb에서 채움)
            ));
        }
        return candidates;
    }

    /**
     * 여행 일수를 계산한다.
     */
    private long calculateTripDays(VectorEnrichedInput input) {
        if (input.startDate() == null || input.endDate() == null) {
            return 3; // 기본값
        }
        long days = ChronoUnit.DAYS.between(input.startDate(), input.endDate()) + 1;
        return Math.max(1, days);
    }
}
