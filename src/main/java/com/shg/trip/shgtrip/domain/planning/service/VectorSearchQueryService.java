package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.embedding.EmbeddingService;
import com.shg.trip.shgtrip.domain.place.vector.PlaceVectorSearchService;
import com.shg.trip.shgtrip.domain.place.vector.VectorSearchRequest;
import com.shg.trip.shgtrip.domain.place.vector.VectorSearchResult;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants.SearchRole;
import com.shg.trip.shgtrip.domain.planning.service.SearchQueryBuilder.SearchSlot;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 벡터 검색 쿼리 오케스트레이션 서비스.
 *
 * VectorEnrichedInput를 입력으로 받아 전체 벡터 검색 파이프라인을 수행한다:
 * 1. SearchQueryBuilder로 슬롯별(역할 5종, 관광은 사용자 카테고리·테마별) 쿼리 생성
 * 2. EmbeddingService.embedBatch로 전 슬롯 쿼리 벡터를 1회 호출로 생성
 * 3. VectorSearchRequest 구성 (슬롯별 "명백히 틀린 대분류" 배제 포함)
 * 4. PlaceVectorSearchService로 검색 실행 (오버페치)
 * 5. 슬롯별 최소 유사도 임계값 적용 + 하이브리드 랭킹 후 슬롯 limit만큼 채택
 * 6. VectorSearchResult → PlaceCandidate 변환 (1-based indexing, 세부지역 부여)
 *
 * 5일+ 여행 시 regionAllocation 기반 지역별 분리 검색을 수행한다.
 */
@Service
public class VectorSearchQueryService {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchQueryService.class);

    private static final int MAX_PER_CATEGORY = 20;
    /** 전체 반환 하한 (days×10의 하한값). */
    private static final int MIN_TOTAL = 30;
    private static final int MAX_TOTAL = 80;

    /**
     * 관광 슬롯 하나당 최소 반환 수 — 쿼리를 잘게 쪼개도 각 유형이 최소 몇 개는 나오도록.
     * 3이면 도시에 따라 FallbackDecider의 관광지 최소치(days) 경계에 걸린다(실측: 오사카가
     * 관광 후보 3개로 fallback). ANN 리콜이 실행마다 조금씩 달라지므로 여유를 둔다.
     */
    private static final int MIN_PER_ATTRACTION_SLOT = 4;

    /**
     * 임계값·하이브리드 랭킹을 Java에서 적용하기 위한 오버페치 배수. SQL은 유사도 순 상위
     * N×배수를 가져오고, 임계 미달 제거 후 재랭킹해 상위 N만 채택한다.
     */
    private static final int OVERFETCH_FACTOR = 3;
    private static final int MAX_OVERFETCH = 60;

    /** 하이브리드 랭킹 가중치 — 유사도 기반에 평점/대분류/라벨 매칭을 소폭 얹는다. */
    private static final double RATING_WEIGHT = 0.10;
    /** 평점이 없는 장소의 중립 사전값(무평점이라고 과도하게 밀려나지 않도록). */
    private static final double NEUTRAL_RATING_RATIO = 0.7;
    private static final double MAJOR_CATEGORY_BONUS = 0.05;
    private static final double LABEL_MATCH_BONUS = 0.03;

    /**
     * 지역 중심점(median)에서 이 거리(km)를 초과하는 후보는 오염 좌표로 보고 제거한다.
     * city/island 규모 region 기준. 제주 실제 반경 ~40km라 정상 장소는 통과, 타지역 오태깅(수백 km)은 제거.
     */
    private static final double MAX_REGION_RADIUS_KM = 100.0;

    /** 안정적 median 산출을 위한 region 그룹 최소 표본 수. 미만이면 과도 필터링 방지로 필터 skip. */
    private static final int MIN_GROUP_FOR_OUTLIER_FILTER = 4;

    private final EmbeddingService embeddingService;
    private final PlaceVectorSearchService placeVectorSearchService;

    public VectorSearchQueryService(EmbeddingService embeddingService,
                                    PlaceVectorSearchService placeVectorSearchService) {
        this.embeddingService = embeddingService;
        this.placeVectorSearchService = placeVectorSearchService;
    }

    /**
     * VectorEnrichedInput 기반으로 벡터 검색을 수행하여 PlaceCandidate 목록을 반환한다.
     * 슬롯별 분리 검색으로 사용자가 고른 카테고리·테마가 후보 풀에 그대로 반영되도록 한다.
     *
     * @param input enrichInput 결과
     * @return 1-based 인덱스가 부여된 후보 장소 목록
     */
    public List<PlaceCandidate> search(VectorEnrichedInput input) {
        int totalLimit = calculateTotalLimit(input);
        List<SearchSlot> slots = SearchQueryBuilder.buildSearchSlots(input);
        Map<String, Integer> slotLimits = buildSlotLimits(slots, totalLimit, calculateTripDays(input));

        List<SearchUnit> units = buildSearchUnits(input, slots);
        int unitsPerSlot = Math.max(1, units.size() / Math.max(1, slots.size()));

        log.info("검색 슬롯 {}개, 실행 단위 {}개, 슬롯별 limit={}", slots.size(), units.size(), slotLimits);

        // A5: 슬롯 수만큼 순차 호출하던 임베딩을 1회 배치 호출로 (슬롯이 늘어도 지연이 늘지 않게)
        List<float[]> queryVectors = embeddingService.embedBatch(
                units.stream().map(SearchUnit::queryText).toList());
        if (queryVectors.size() != units.size()) {
            log.warn("임베딩 배치 결과 수 불일치: units={}, vectors={} — 부족분은 건너뜀",
                    units.size(), queryVectors.size());
        }

        // 임계값·랭킹은 슬롯 단위로 적용한다 — 지역별로 나눠 조회해도 채택 기준은 슬롯 하나로 본다
        Map<String, List<VectorSearchResult>> rawBySlot = new LinkedHashMap<>();

        for (int i = 0; i < units.size() && i < queryVectors.size(); i++) {
            SearchUnit unit = units.get(i);
            SearchSlot slot = unit.slot();
            float[] queryVector = queryVectors.get(i);
            if (queryVector == null || queryVector.length == 0) {
                log.warn("슬롯 '{}' 임베딩 없음 — 건너뜀", slot.key());
                continue;
            }

            int limit = slotLimits.getOrDefault(slot.key(), MIN_PER_ATTRACTION_SLOT);
            int overfetch = Math.min(MAX_OVERFETCH, limit * OVERFETCH_FACTOR);
            int unitLimit = Math.max(1, overfetch / unitsPerSlot);

            List<VectorSearchResult> raw = placeVectorSearchService.search(
                    buildRequest(input, queryVector, unit.filterRegions(), slot, unitLimit));

            // fail-open: 지역 필터가 0건을 만들면 그 필터를 떨어뜨리고 상위 지역으로 재조회한다.
            // 필터 하나가 어긋나 후보 풀이 통째로 비는 것(→ fallback)보다 범위가 넓은 편이 낫다.
            if (raw.isEmpty() && unit.filterRegions() != null) {
                raw = placeVectorSearchService.search(
                        buildRequest(input, queryVector, null, slot, unitLimit));
                log.info("지역 필터 0건 → 상위 지역으로 완화 재조회: 슬롯={}, 필터={}, 재조회={}건",
                        slot.key(), unit.filterRegions(), raw.size());
            }

            rawBySlot.computeIfAbsent(slot.key(), k -> new ArrayList<>()).addAll(raw);
        }

        // 같은 장소가 여러 슬롯에 걸릴 수 있으므로(관광 하위유형끼리 겹침) placeId 기준으로 최고 점수만 유지
        Map<Long, ScoredResult> bestByPlace = new LinkedHashMap<>();
        List<ScoredResult> withoutId = new ArrayList<>();

        for (SearchSlot slot : slots) {
            List<VectorSearchResult> raw = dedupeByPlace(rawBySlot.get(slot.key()));
            if (raw.isEmpty()) continue;

            int limit = slotLimits.getOrDefault(slot.key(), MIN_PER_ATTRACTION_SLOT);
            for (ScoredResult scored : applyThresholdAndRank(slot, raw, limit)) {
                Long placeId = scored.result().placeId();
                if (placeId == null) {
                    withoutId.add(scored);
                    continue;
                }
                ScoredResult existing = bestByPlace.get(placeId);
                if (existing == null || scored.score() > existing.score()) {
                    bestByPlace.put(placeId, scored);
                }
            }
        }

        List<VectorSearchResult> allResults = new ArrayList<>(bestByPlace.size() + withoutId.size());
        bestByPlace.values().forEach(s -> allResults.add(s.result()));
        withoutId.forEach(s -> allResults.add(s.result()));

        log.info("전체 벡터 검색 결과: {}개 장소 반환 (요청 총 limit: {})", allResults.size(), totalLimit);

        List<VectorSearchResult> cleaned = filterGeographicOutliers(allResults);

        return convertToCandidates(cleaned);
    }

    /**
     * 특정 좌표 주변의 숙소를 추가로 검색한다(3차 4번).
     *
     * <p>숙소 슬롯은 여행지 전체를 대상으로 한 번만 검색하므로, 후보가 한쪽에 쏠리면 반대편 day도
     * 그쪽 숙소로 돌아가야 한다(실측: 제주 숙소 후보 4곳이 전부 북부라 서남부 day도 35km 복귀).
     * day 구성이 확정된 뒤 그 day의 centroid를 중심으로 반경 검색을 한 번 더 돌려 보완한다.
     *
     * @param startIndex 이미 부여된 인덱스 다음 번호 — 기존 후보의 인덱스를 건드리지 않도록
     *                   <b>이어서</b> 매긴다(선택 결과가 인덱스를 참조하고 있다).
     * @return 추가 후보(비어 있을 수 있음)
     */
    public List<PlaceCandidate> searchAccommodationsNear(VectorEnrichedInput input, double[] center,
                                                         double radiusKm, int limit, int startIndex) {
        if (center == null || limit <= 0) return List.of();

        SearchSlot slot = SearchQueryBuilder.buildSearchSlots(input).stream()
                .filter(sl -> sl.role() == SearchRole.ACCOMMODATION)
                .findFirst()
                .orElse(null);
        if (slot == null) return List.of();

        List<float[]> vectors = embeddingService.embedBatch(List.of(slot.queryText()));
        if (vectors.isEmpty() || vectors.get(0) == null || vectors.get(0).length == 0) return List.of();

        VectorSearchRequest request = new VectorSearchRequest(
                vectors.get(0), input.country(), input.regions(), null, input.searchTags(),
                input.budgetRange(), limit * OVERFETCH_FACTOR,
                PlaceCategoryConstants.excludedCategoryPatterns(SearchRole.ACCOMMODATION),
                center, radiusKm);

        List<VectorSearchResult> raw = placeVectorSearchService.search(request);
        List<ScoredResult> accepted = applyThresholdAndRank(slot, raw, limit);

        List<PlaceCandidate> result = new ArrayList<>(accepted.size());
        int index = startIndex;
        for (ScoredResult scored : accepted) {
            VectorSearchResult r = scored.result();
            // bounding box 근사라 정확한 거리로 한 번 더 거른다
            if (r.latitude() == null || r.longitude() == null) continue;
            double dist = GeoUtils.haversine(center,
                    new double[]{r.latitude().doubleValue(), r.longitude().doubleValue()});
            if (dist > radiusKm) continue;
            result.add(new PlaceCandidate(
                    index++, r.placeId(), r.name(), r.address(), r.category(), r.tags(),
                    r.region(), r.country(), r.latitude(), r.longitude(), r.description(),
                    r.rating(), r.similarityScore(), null, null, null, null, false, null,
                    PlaceCategoryConstants.extractSubRegion(r.address())));
        }
        log.info("day centroid 기준 숙소 보완 검색: 반경 {}km, 조회={}, 채택={}",
                String.format("%.0f", radiusKm), raw.size(), result.size());
        return result;
    }

    /**
     * 슬롯 최소 유사도 미만을 버리고(A3), 남은 결과를 하이브리드 점수로 재랭킹해(A4) 상위 limit만 채택한다.
     * 반환/탈락 수를 슬롯별로 로그에 남긴다 — 후보가 줄어드는 이유를 사후에 추적할 수 있어야 한다.
     */
    List<ScoredResult> applyThresholdAndRank(SearchSlot slot, List<VectorSearchResult> raw, int limit) {
        double minSimilarity = PlaceCategoryConstants.minSimilarity(slot.role());
        String slotMajor = PlaceCategoryConstants.slotMajorCategory(slot.role());

        List<ScoredResult> kept = new ArrayList<>();
        int dropped = 0;
        for (VectorSearchResult r : raw) {
            if (r.similarityScore() < minSimilarity) {
                dropped++;
                continue;
            }
            kept.add(new ScoredResult(r, hybridScore(r, slotMajor, slot.labelTokens())));
        }
        kept.sort(Comparator.comparingDouble(ScoredResult::score).reversed());
        List<ScoredResult> accepted = kept.size() > limit ? new ArrayList<>(kept.subList(0, limit)) : kept;

        log.info("슬롯 검색: key={}, role={}, 조회={}, 임계탈락={}, 채택={} (임계값={})",
                slot.key(), slot.role(), raw.size(), dropped, accepted.size(),
                String.format("%.2f", minSimilarity));
        return accepted;
    }

    /**
     * 하이브리드 점수 = 유사도 + 평점 정규화 가중 + 대분류 일치 가점 + 라벨(테마·카테고리) 매칭 가점.
     * 임베딩만으로는 대표 명소가 위로 올라오지 못하는 문제를 완화한다.
     */
    private double hybridScore(VectorSearchResult r, String slotMajorCategory, List<String> labelTokens) {
        double score = r.similarityScore();

        double ratingRatio = r.rating() != null
                ? Math.min(1.0, r.rating().doubleValue() / 5.0) : NEUTRAL_RATING_RATIO;
        score += RATING_WEIGHT * ratingRatio;

        if (slotMajorCategory != null
                && slotMajorCategory.equals(PlaceCategoryConstants.majorCategory(r.category()))) {
            score += MAJOR_CATEGORY_BONUS;
        }

        if (matchesLabel(r, labelTokens)) {
            score += LABEL_MATCH_BONUS;
        }
        return score;
    }

    /** 이름·태그에 슬롯 라벨 토큰이 들어 있는지(테마/카테고리 의미 직접 매칭). */
    private boolean matchesLabel(VectorSearchResult r, List<String> labelTokens) {
        if (labelTokens == null || labelTokens.isEmpty()) return false;
        StringBuilder sb = new StringBuilder();
        if (r.name() != null) sb.append(r.name()).append(' ');
        if (r.tags() != null) r.tags().forEach(t -> sb.append(t).append(' '));
        String haystack = sb.toString().toLowerCase();
        if (haystack.isBlank()) return false;
        return labelTokens.stream().anyMatch(haystack::contains);
    }

    /** 검색 결과 + 하이브리드 점수. */
    record ScoredResult(VectorSearchResult result, double score) {}

    /**
     * region 그룹별로 좌표 median 중심점을 구하고, 중심점에서 {@link #MAX_REGION_RADIUS_KM}를 초과하는
     * 후보(예: region='Jeju'로 오태깅됐지만 좌표는 춘천/명동인 오염 레코드)를 제거한다.
     *
     * <p>평균이 아닌 median을 중심점으로 쓰므로 오염 좌표가 소수 섞여도 중심점이 흔들리지 않는다.
     * 그룹 표본이 {@link #MIN_GROUP_FOR_OUTLIER_FILTER} 미만이면 중심점 신뢰도가 낮아 필터를 건너뛴다.
     * 좌표가 없는 후보는 판단을 보류하고 보존한다.
     */
    List<VectorSearchResult> filterGeographicOutliers(List<VectorSearchResult> results) {
        if (results == null || results.isEmpty()) {
            return results;
        }

        Map<String, List<VectorSearchResult>> byRegion = new LinkedHashMap<>();
        for (VectorSearchResult r : results) {
            String key = r.region() == null ? "" : r.region();
            byRegion.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        List<VectorSearchResult> kept = new ArrayList<>(results.size());
        for (Map.Entry<String, List<VectorSearchResult>> entry : byRegion.entrySet()) {
            List<VectorSearchResult> group = entry.getValue();

            List<VectorSearchResult> withCoord = group.stream()
                    .filter(r -> r.latitude() != null && r.longitude() != null)
                    .toList();

            if (withCoord.size() < MIN_GROUP_FOR_OUTLIER_FILTER) {
                kept.addAll(group); // 표본 부족 → 필터 skip
                continue;
            }

            double centerLat = GeoUtils.median(withCoord.stream().map(r -> r.latitude().doubleValue()).toList());
            double centerLng = GeoUtils.median(withCoord.stream().map(r -> r.longitude().doubleValue()).toList());

            for (VectorSearchResult r : group) {
                if (r.latitude() == null || r.longitude() == null) {
                    kept.add(r); // 좌표 없으면 판단 보류
                    continue;
                }
                double dist = GeoUtils.haversine(
                        centerLat, centerLng, r.latitude().doubleValue(), r.longitude().doubleValue());
                if (dist > MAX_REGION_RADIUS_KM) {
                    log.info("좌표 아웃라이어 제거: region='{}', name='{}', 중심점에서 {}km",
                            entry.getKey(), r.name(), String.format("%.1f", dist));
                } else {
                    kept.add(r);
                }
            }
        }
        return kept;
    }

    /**
     * 여행 일수에 따른 총 반환 수를 계산한다.
     * Formula: days * 10, capped at MAX_TOTAL(80), min MIN_TOTAL(30).
     */
    int calculateTotalLimit(VectorEnrichedInput input) {
        long days = calculateTripDays(input);
        int calculated = (int) (days * 10);
        return Math.max(MIN_TOTAL, Math.min(calculated, MAX_TOTAL));
    }

    /**
     * 슬롯별 벡터 검색 limit을 배분한다.
     * 역할별 총량 규칙은 기존과 동일하고(식당 25% / 숙소 10% / 카페 15% / 교통 10% / 관광 나머지),
     * <b>관광 총량만</b> 관광 슬롯 수로 나눠 배분한다(슬롯당 최소 {@link #MIN_PER_ATTRACTION_SLOT}).
     */
    Map<String, Integer> buildSlotLimits(List<SearchSlot> slots, int totalLimit, long days) {
        Map<String, Integer> limits = new HashMap<>();
        boolean hasTransportation = slots.stream().anyMatch(s -> s.role() == SearchRole.TRANSPORTATION);

        List<SearchSlot> attractionSlots = slots.stream()
                .filter(s -> s.role() == SearchRole.ATTRACTION)
                .toList();
        // 교통이 없으면 그 10%를 관광에 추가 (기존 규칙 유지)
        double attractionRatio = hasTransportation ? 0.40 : 0.50;
        int attractionTotal = Math.max((int) (days * 2), (int) (totalLimit * attractionRatio));
        int perAttraction = attractionSlots.isEmpty()
                ? 0
                : Math.max(MIN_PER_ATTRACTION_SLOT, attractionTotal / attractionSlots.size());

        for (SearchSlot slot : slots) {
            int limit = switch (slot.role()) {
                case TRANSPORTATION -> Math.max(2, (int) (totalLimit * 0.1));
                // 하루 2식 + 대안·갭필 여유. 바/베이커리를 슬롯에서 뺀 만큼(A2) 실제 식사 후보로만
                // 채워지므로 days×4를 하한으로 둔다 — 3일 여행 기준 12곳.
                case RESTAURANT -> Math.max((int) (days * 4), (int) (totalLimit * 0.25));
                case ACCOMMODATION -> Math.max(3, (int) (totalLimit * 0.1));
                case CAFE -> Math.max((int) days, (int) (totalLimit * 0.15));
                case ATTRACTION -> perAttraction;
            };
            limits.put(slot.key(), Math.min(limit, MAX_PER_CATEGORY));
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
     * 한 슬롯이 여러 실행 단위로 나뉘어 조회한 결과에서 같은 장소를 하나로 합친다(최고 유사도 유지).
     *
     * <p>세부 지명은 필터가 아니라 쿼리 힌트로만 쓰이므로, 한 도시 안에서 구역을 나눈 여행은
     * 모든 단위가 <b>같은 지역 필터</b>로 조회한다 — 인기 장소가 단위 수만큼 중복으로 돌아온다.
     * 그대로 두면 랭킹 상위 N을 같은 장소가 차지해 후보 풀이 실제보다 얕아지고, 그 얕은 풀이
     * "그날 반경 안에 식당이 없음"으로 이어진다.
     */
    private static List<VectorSearchResult> dedupeByPlace(List<VectorSearchResult> results) {
        if (results == null || results.isEmpty()) return List.of();

        Map<Long, VectorSearchResult> bestById = new LinkedHashMap<>();
        List<VectorSearchResult> withoutId = new ArrayList<>();
        for (VectorSearchResult r : results) {
            if (r.placeId() == null) {
                withoutId.add(r);
                continue;
            }
            VectorSearchResult prev = bestById.get(r.placeId());
            if (prev == null || r.similarityScore() > prev.similarityScore()) {
                bestById.put(r.placeId(), r);
            }
        }
        List<VectorSearchResult> merged = new ArrayList<>(bestById.values());
        merged.addAll(withoutId);
        return merged;
    }

    /**
     * 검색 실행 단위. 지역 분리가 없으면 슬롯당 1개, 있으면 (슬롯 × regionAllocation 엔트리)마다 1개다.
     *
     * @param slot          결과를 묶을 슬롯 — 임계값·랭킹은 이 단위로 적용한다
     * @param queryText     임베딩할 쿼리. 지역 분리 시 필터로 못 쓰는 세부 지명이 뒤에 붙는다
     * @param filterRegions DB {@code place.region} 하드 필터로 쓸 값. null이면 {@code input.regions()}
     */
    private record SearchUnit(SearchSlot slot, String queryText, List<String> filterRegions) {}

    /**
     * 슬롯 목록을 실제 검색 실행 단위로 펼친다.
     *
     * <p>지역 분리(5일+)일 때 regionAllocation 값을 <b>필터 축</b>과 <b>랭킹 축</b>으로 가르는 것이
     * 이 메서드의 핵심이다. DB의 {@code place.region}은 enrich가 주는 영어 상위 도시명(Seoul·Jeju…)
     * 한 축뿐인데, regionAllocation에는 행정동(용담동)·지형(한라산)·경로(올레길)·랜드마크(성산일출봉)가
     * 섞여 온다. 이 값들을 그대로 {@code p.region = ?}에 넣으면 전 슬롯이 0건이 되고, 예외 하나 없이
     * 빈 후보 풀이 만들어진다(실측: 제주 5일 여행이 전 슬롯 조회=0 → fallback → 생성 실패).
     *
     * <p>그래서 {@link VectorEnrichedInput#regions()} 어휘에 실재하는 값만 필터로 승격하고, 나머지는
     * 쿼리 텍스트에 얹어 벡터 유사도로만 반영한다. 서울+부산처럼 값이 그대로 상위 도시명인 진짜
     * 다지역 여행은 종전과 똑같이 지역별 분리 검색이 유지된다.
     */
    private List<SearchUnit> buildSearchUnits(VectorEnrichedInput input, List<SearchSlot> slots) {
        if (!shouldSplitByRegion(input)) {
            return slots.stream()
                    .map(slot -> new SearchUnit(slot, slot.queryText(), null))
                    .toList();
        }

        Set<String> knownRegions = new HashSet<>();
        if (input.regions() != null) {
            input.regions().stream()
                    .filter(r -> r != null && !r.isBlank())
                    .forEach(r -> knownRegions.add(r.trim().toLowerCase()));
        }

        List<SearchUnit> units = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : input.regionAllocation().entrySet()) {
            List<String> values = entry.getValue() != null ? entry.getValue() : List.<String>of();
            List<String> filterRegions = new ArrayList<>();
            List<String> hints = new ArrayList<>();
            for (String value : values) {
                if (value == null || value.isBlank()) continue;
                if (knownRegions.contains(value.trim().toLowerCase())) {
                    filterRegions.add(value.trim());
                } else {
                    hints.add(value.trim());
                }
            }

            log.debug("지역 분리 - 일차: {}, 필터: {}, 유사도 힌트: {}",
                    entry.getKey(), filterRegions, hints);

            String suffix = hints.isEmpty() ? "" : " " + String.join(" ", hints);
            for (SearchSlot slot : slots) {
                units.add(new SearchUnit(slot, slot.queryText() + suffix,
                        filterRegions.isEmpty() ? null : List.copyOf(filterRegions)));
            }
        }
        return units;
    }

    /**
     * VectorSearchRequest를 구성한다.
     * enrichInput 프롬프트에서 country=ISO코드, regions=영어 도시명으로 반환하도록 지정되어 있음.
     * categories 필터는 넘기지 않는다 — 마법사 카테고리 id와 DB category(Foursquare 계층 경로)의
     * 형식이 다르고 오적재도 흔해 하드 필터가 정상 장소를 지운다. 대신 슬롯별 "명백히 틀린
     * 대분류"만 배제하고(소프트), 의미 판별은 벡터 유사도 + 하이브리드 랭킹에 맡긴다.
     */
    private VectorSearchRequest buildRequest(VectorEnrichedInput input,
                                              float[] queryVector,
                                              List<String> regions,
                                              SearchSlot slot,
                                              int limit) {
        List<String> searchRegions = regions != null ? regions : input.regions();

        log.debug("벡터 검색 필터: country='{}', regions={}, slot={}, limit={}",
                input.country(), searchRegions, slot.key(), limit);

        return new VectorSearchRequest(
                queryVector,
                input.country(),
                searchRegions,
                null,
                input.searchTags(),
                input.budgetRange(),
                limit,
                slot.excludedCategoryPatterns()
        );
    }

    /**
     * VectorSearchResult 목록을 PlaceCandidate 목록으로 변환한다.
     * 1-based 연속 인덱싱을 유지하고, 주소에서 세부지역(시/군/구)을 추출해 부여한다(A6).
     * priceLevel, openingHours는 DB에서 별도로 enrichment 단계에서 채운다.
     */
    List<PlaceCandidate> convertToCandidates(List<VectorSearchResult> results) {
        List<PlaceCandidate> candidates = new ArrayList<>(results.size());
        int withSubRegion = 0;
        for (int i = 0; i < results.size(); i++) {
            VectorSearchResult r = results.get(i);
            String subRegion = PlaceCategoryConstants.extractSubRegion(r.address());
            if (subRegion != null) withSubRegion++;
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
                    null,  // openingHours (enrichCandidatesFromDb에서 채움)
                    null,  // recommendedTimeSlots
                    null,  // recommendedDurationMinutes
                    false, // userSelected
                    null,  // admissionFee
                    subRegion
            ));
        }
        if (!results.isEmpty()) {
            log.info("세부지역 추출: {}/{}건 (실패분은 상위 region 사용)", withSubRegion, results.size());
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
