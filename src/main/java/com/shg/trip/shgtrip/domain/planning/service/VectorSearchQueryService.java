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

    private static final int MIN_PER_CATEGORY = 15;
    private static final int MAX_PER_CATEGORY = 20;
    private static final int MIN_TOTAL = 60;
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
     *
     * @param input enrichInput 결과
     * @return 1-based 인덱스가 부여된 후보 장소 목록
     */
    public List<PlaceCandidate> search(VectorEnrichedInput input) {
        String queryText = SearchQueryBuilder.buildSearchQuery(input);
        log.debug("벡터 검색 쿼리 텍스트: {}", queryText);

        float[] queryVector = embeddingService.embed(queryText);

        int totalLimit = calculateTotalLimit(input);

        List<VectorSearchResult> results;

        if (shouldSplitByRegion(input)) {
            results = searchByRegion(input, queryVector, totalLimit);
        } else {
            VectorSearchRequest request = buildRequest(input, queryVector, null, totalLimit);
            results = placeVectorSearchService.search(request);
        }

        log.info("벡터 검색 결과: {}개 장소 반환 (요청 limit: {})", results.size(), totalLimit);

        return convertToCandidates(results);
    }

    /**
     * 여행 일수에 따른 총 반환 수를 계산한다.
     * Formula: days * 20, capped at MAX_TOTAL(80), min MIN_TOTAL(60).
     */
    int calculateTotalLimit(VectorEnrichedInput input) {
        long days = calculateTripDays(input);
        int calculated = (int) (days * 20);
        return Math.max(MIN_TOTAL, Math.min(calculated, MAX_TOTAL));
    }

    /**
     * 카테고리당 반환 수를 계산한다 (15~20개).
     * 현재는 단일 쿼리로 categories IN 필터를 적용하여 DB에서 자연 분배되도록 하고 있음.
     * 향후 카테고리별 균등 분배가 필요한 경우 per-category 쿼리로 전환 시 사용.
     */
    int calculatePerCategoryLimit(VectorEnrichedInput input, int totalLimit) {
        List<String> categories = input.categories();
        if (categories == null || categories.isEmpty()) {
            return totalLimit;
        }
        int perCategory = totalLimit / categories.size();
        return Math.max(MIN_PER_CATEGORY, Math.min(perCategory, MAX_PER_CATEGORY));
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
     * 지역별 분리 검색을 수행하고 결과를 합친다.
     */
    private List<VectorSearchResult> searchByRegion(VectorEnrichedInput input,
                                                     float[] queryVector,
                                                     int totalLimit) {
        Map<String, List<String>> regionAllocation = input.regionAllocation();
        int regionCount = regionAllocation.size();
        int limitPerRegion = totalLimit / regionCount;

        List<VectorSearchResult> allResults = new ArrayList<>();

        for (Map.Entry<String, List<String>> entry : regionAllocation.entrySet()) {
            List<String> regions = entry.getValue();
            log.debug("지역별 검색 - 일차: {}, 지역: {}, limit: {}",
                    entry.getKey(), regions, limitPerRegion);

            VectorSearchRequest request = buildRequest(input, queryVector, regions, limitPerRegion);
            List<VectorSearchResult> regionResults = placeVectorSearchService.search(request);
            allResults.addAll(regionResults);
        }

        return allResults;
    }

    /**
     * VectorSearchRequest를 구성한다.
     * enrichInput 프롬프트에서 country=ISO코드, regions=영어 도시명으로 반환하도록 지정되어 있음.
     */
    private VectorSearchRequest buildRequest(VectorEnrichedInput input,
                                              float[] queryVector,
                                              List<String> regions,
                                              int limit) {
        List<String> searchRegions = regions != null ? regions : input.regions();

        log.debug("벡터 검색 필터: country='{}', regions={}", input.country(), searchRegions);

        return new VectorSearchRequest(
                queryVector,
                input.country(),
                searchRegions,
                input.categories(),
                input.searchTags(),
                input.budgetRange(),
                limit
        );
    }

    /**
     * VectorSearchResult 목록을 PlaceCandidate 목록으로 변환한다.
     * 1-based 연속 인덱싱을 유지한다.
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
                    r.similarityScore()
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
