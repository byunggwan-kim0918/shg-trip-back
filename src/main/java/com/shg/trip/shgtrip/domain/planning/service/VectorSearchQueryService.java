package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.place.embedding.EmbeddingService;
import com.shg.trip.shgtrip.domain.place.vector.PlaceVectorSearchService;
import com.shg.trip.shgtrip.domain.place.vector.VectorSearchRequest;
import com.shg.trip.shgtrip.domain.place.vector.VectorSearchResult;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.TripCities;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 슬롯별 벡터 검색 → 최소 유사도 임계값 → 하이브리드 랭킹 → 1-based PlaceCandidate 변환.
 * 5일 이상 여행은 regionAllocation 기반 지역별 분리 검색을 한다.
 */
@Service
public class VectorSearchQueryService {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchQueryService.class);

    private static final int MAX_PER_CATEGORY = 20;
    /** 전체 반환 하한(days×10의 하한값). */
    private static final int MIN_TOTAL = 30;
    private static final int MAX_TOTAL = 80;

    /**
     * 관광 슬롯 하나당 최소 반환 수. 3이면 도시에 따라 FallbackDecider의 관광지 최소치 경계에 걸리고,
     * ANN 리콜이 실행마다 달라지므로 여유를 둔다.
     */
    private static final int MIN_PER_ATTRACTION_SLOT = 4;

    /**
     * 대표 명소 슬롯에 일수 몫(하루 2곳)보다 더 두는 자리. 도시의 대표 명소는 일수와 상관없이 여럿이라
     * 일수 몫만 두면 언어판 수 상위 몇 곳이 자리를 다 쓰고 그다음 대표 명소가 후보에 오르지 못한다.
     */
    private static final int REPRESENTATIVE_RESERVE = 4;

    /**
     * 식사·카페 슬롯에서 한 세부 유형이 가질 수 있는 최소 자리 수. 유형이 둘뿐인 소도시에서
     * 다양화가 후보를 말리지 않게 limit/4보다 작아도 보장한다.
     */
    private static final int MIN_PER_SUB_TYPE = 2;

    /**
     * 임계값·하이브리드 랭킹을 Java에서 적용하기 위한 오버페치 배수. SQL은 유사도 순 상위
     * N×배수를 가져오고, 임계 미달 제거 후 재랭킹해 상위 N만 채택한다.
     */
    private static final int OVERFETCH_FACTOR = 3;
    private static final int MAX_OVERFETCH = 60;

    /**
     * 식사·카페 슬롯의 오버페치. 유사도 상위 N×3은 한 종류로 쏠리고 다양화는 창 안에서만 고를 수 있어,
     * 창을 넓혀야 다양화가 작동한다.
     */
    private static final int DIVERSITY_OVERFETCH_FACTOR = 10;
    private static final int DIVERSITY_MAX_OVERFETCH = 200;

    /**
     * 하이브리드 랭킹의 인기도 가중치. 제주 관광지 기준으로 리뷰 없는 장소가 채택권 밖으로 밀리는 값이다.
     * 0.35 이상이면 유사도 차이를 뒤집어 주제 적합성이 깨진다.
     */
    private static final double RATING_WEIGHT = 0.15;
    /** 리뷰 데이터 커버리지가 0일 때, 즉 인기도를 전혀 판단할 수 없을 때의 중립값. */
    private static final double NEUTRAL_RATING_RATIO = 0.7;
    /** 커버리지가 100%일 때 "리뷰 수 미상"에 매기는 값. 사실상 리뷰가 없는 곳으로 본다. */
    private static final double UNKNOWN_FLOOR_RATIO = 0.15;
    /**
     * 리뷰 수 포화 기준. 이 수를 넘으면 인기도 가점이 더 늘지 않는다.
     * 선형으로 쓰면 초대형 명소 하나가 다른 모든 후보를 눌러 일정이 단조로워져 로그를 쓴다.
     */
    private static final int REVIEW_SATURATION_COUNT = 10_000;
    private static final double MAJOR_CATEGORY_BONUS = 0.05;
    /**
     * 관광 슬롯에서 관광공사 등록 장소에 주는 가점. 국내 명소는 언어판 수가 낮은 곳이 많아 유사도가 비슷한
     * 비관광 시설(볼링장·체험 공간)에 밀린다. 식당은 등록된 곳이 드물어 주지 않는다.
     */
    private static final double TOURISM_LISTED_BONUS = 0.05;
    private static final double LABEL_MATCH_BONUS = 0.03;

    /**
     * 지역 중심점(median)에서 이 거리(km)를 초과하는 후보는 오염 좌표로 보고 제거한다.
     * city/island 규모 region 기준. 제주 반경이 ~40km라 정상 장소는 통과하고 타지역 오태깅(수백 km)은 걸린다.
     */
    private static final double MAX_REGION_RADIUS_KM = 100.0;

    /** region 그룹 최소 표본 수. 미만이면 median을 믿을 수 없어 필터를 건너뛴다. */
    private static final int MIN_GROUP_FOR_OUTLIER_FILTER = 4;

    private final EmbeddingService embeddingService;
    private final PlaceVectorSearchService placeVectorSearchService;
    private final Destinations destinations;

    public VectorSearchQueryService(EmbeddingService embeddingService,
                                    PlaceVectorSearchService placeVectorSearchService,
                                    Destinations destinations) {
        this.embeddingService = embeddingService;
        this.placeVectorSearchService = placeVectorSearchService;
        this.destinations = destinations;
    }

    /** @return 1-based 인덱스가 부여된 후보 장소 목록 */
    public List<PlaceCandidate> search(VectorEnrichedInput input) {
        return search(input, TripCityDetector.detect(input.regions(), input.regionAllocation(),
                calculateTripDays(input), destinations, input.transportationHub()));
    }

    /**
     * @param cities 먼 도시를 오가는 여행이면 도시 순서와 일차별 도시, 아니면 null. 두 도시를 한 번에 찾으면 상위 결과가
     *               한 도시로 쏠려 도시마다 그 일수만큼 따로 찾아 합친다.
     */
    public List<PlaceCandidate> search(VectorEnrichedInput input, TripCities cities) {
        if (cities == null || input.startDate() == null) return searchWithin(input);
        List<PlaceCandidate> merged = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (CityBlockPlanner.Block block : CityBlockPlanner.blocksOf(cities)) {
            List<String> cityRegions = input.regions().stream()
                    .filter(r -> block.city().equals(cityOfRegion(cities, r)))
                    .toList();
            List<PlaceCandidate> found = searchWithin(input.forCityBlock(
                    cityRegions.isEmpty() ? List.of(block.city()) : cityRegions, block.firstDay(), block.lastDay(),
                    TripCityDetector.allocationWithin(input.regionAllocation(), block.firstDay(), block.lastDay())));
            found.stream().filter(c -> c.placeId() == null || seen.add(c.placeId())).forEach(merged::add);
            log.info("도시별 검색: {} {}~{}일차 {}곳", block.city(), block.firstDay(), block.lastDay(), found.size());
        }
        return reindex(merged);
    }

    /** 도시로 따로 두지 않은 근교·인접 여행지는 가장 가까운 도시 구간에서 찾는다. */
    private String cityOfRegion(TripCities cities, String region) {
        return destinations.find(region)
                .map(d -> cities.cityOf(d.lat(), d.lng()))
                .orElse(destinations.rootOf(region));
    }

    private List<PlaceCandidate> searchWithin(VectorEnrichedInput input) {
        int totalLimit = calculateTotalLimit(input);
        List<SearchSlot> slots = SearchQueryBuilder.buildSearchSlots(input);
        Map<String, Integer> slotLimits = buildSlotLimits(slots, totalLimit, calculateTripDays(input));

        List<SearchUnit> units = withBaseQueryUnits(buildSearchUnits(input, slots), slots);
        Map<String, Long> unitsPerSlot = units.stream()
                .collect(Collectors.groupingBy(u -> u.slot().key(), Collectors.counting()));

        log.info("검색 슬롯 {}개, 실행 단위 {}개, 슬롯별 limit={}", slots.size(), units.size(), slotLimits);

        // 슬롯이 늘어도 지연이 늘지 않게 임베딩은 1회 배치 호출로 한다.
        long embedStart = System.nanoTime();
        List<float[]> queryVectors = embeddingService.embedBatch(
                units.stream().map(SearchUnit::queryText).toList());
        long embedMs = elapsedMs(embedStart);
        Map<String, Long> dbMsBySlot = new LinkedHashMap<>();
        if (queryVectors.size() != units.size()) {
            log.warn("임베딩 배치 결과 수 불일치: units={}, vectors={} — 부족분은 건너뜀",
                    units.size(), queryVectors.size());
        }

        // 지역별로 나눠 조회해도 임계값·랭킹은 슬롯 단위로 적용한다.
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
            int overfetch = overfetchFor(slot.role(), limit);
            int unitLimit = Math.max(1, overfetch / unitsPerSlot.get(slot.key()).intValue());

            long queryStart = System.nanoTime();
            List<VectorSearchResult> raw = placeVectorSearchService.search(
                    buildRequest(input, queryVector, unit.filterRegions(), slot, unitLimit, unit.seedPopularity()));

            // fail-open: 지역 필터가 0건이면 필터를 떨어뜨리고 상위 지역으로 재조회한다. 후보 풀이 비는 것보다 낫다.
            if (raw.isEmpty() && unit.filterRegions() != null) {
                raw = placeVectorSearchService.search(
                        buildRequest(input, queryVector, null, slot, unitLimit, unit.seedPopularity()));
                log.info("지역 필터 0건 → 상위 지역으로 완화 재조회: 슬롯={}, 필터={}, 재조회={}건",
                        slot.key(), unit.filterRegions(), raw.size());
            }

            rawBySlot.computeIfAbsent(slot.key(), k -> new ArrayList<>()).addAll(raw);
            dbMsBySlot.merge(slot.key(), elapsedMs(queryStart), Long::sum);
        }
        log.info("검색 시간: 임베딩 {}ms, DB 합계 {}ms, 슬롯별 {}", embedMs,
                dbMsBySlot.values().stream().mapToLong(Long::longValue).sum(), dbMsBySlot);

        // 관광 하위유형끼리 같은 장소가 걸리므로 placeId 기준으로 최고 점수만 유지한다.
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

        List<ScoredResult> merged = new ArrayList<>(bestByPlace.values());
        merged.addAll(withoutId);
        List<VectorSearchResult> allResults = keepBestPerWikidataItem(merged);

        log.info("전체 벡터 검색 결과: {}개 장소 반환 (요청 총 limit: {})", allResults.size(), totalLimit);

        List<VectorSearchResult> cleaned = new ArrayList<>(filterGeographicOutliers(allResults));
        cleaned.addAll(namedPlaces(input, attractionVector(units, queryVectors), cleaned));
        cleaned.addAll(landmarkMeals(input, units, queryVectors, cleaned));

        // 일정 자리에 들어갈 수 없는 장소는 풀에 두면 Sonnet이나 보충 경로가 집어 든다. 판정은 CandidatePolicy 한 곳이다.
        List<PlaceCandidate> candidates = convertToCandidates(cleaned);
        List<PlaceCandidate> visitable = candidates.stream()
                .filter(c -> CandidatePolicy.roleOf(c) != CandidatePolicy.Role.NOT_VISITABLE)
                .toList();
        if (visitable.size() < candidates.size()) {
            log.info("비방문 역할 후보 제외: {}개 → {}개 ({})", candidates.size(), visitable.size(),
                    candidates.stream().filter(c -> CandidatePolicy.roleOf(c) == CandidatePolicy.Role.NOT_VISITABLE)
                            .map(PlaceCandidate::name).limit(8).toList());
        }
        return reindex(visitable);
    }

    /**
     * 숙소 슬롯은 여행지 전체에서 한 번만 검색해 후보가 한쪽에 쏠릴 수 있어, day centroid 주변을 다시 찾는다.
     *
     * @param startIndex 이미 부여된 인덱스 다음 번호. 선택 결과가 기존 인덱스를 참조하므로 이어서 매긴다.
     * @return 추가 후보(비어 있을 수 있음)
     */
    public List<PlaceCandidate> searchAccommodationsNear(VectorEnrichedInput input, double[] center,
                                                         double radiusKm, int limit, int startIndex) {
        return searchNear(input, SearchRole.ACCOMMODATION, center, radiusKm, limit, startIndex);
    }

    /**
     * 검색 풀은 여행지 전체를 한 번 훑은 결과라 하루 동선 근처에 빈 시간을 채울 장소가 부족하다.
     * day 구성이 정해진 뒤 그날 중심 근처에서 역할별 예비 후보를 붙인다.
     *
     * @param startIndex 이미 부여된 인덱스 다음 번호. 기존 인덱스를 건드리지 않도록 이어서 매긴다.
     */
    public List<PlaceCandidate> searchNear(VectorEnrichedInput input, SearchRole role, double[] center,
                                           double radiusKm, int limit, int startIndex) {
        if (center == null || limit <= 0) return List.of();
        SearchSlot slot = slotFor(input, role);
        float[] vector = slot != null ? embedOne(slot.queryText()) : null;
        if (vector == null) return List.of();
        return searchNearWith(input, role, slot, vector, center, radiusKm, limit, startIndex);
    }

    /**
     * 날짜 수만큼 임베딩을 부르면 생성 시간이 늘어나므로 질의 임베딩은 한 번만 한다.
     * 결과 인덱스는 호출부가 다시 매긴다.
     */
    public List<List<PlaceCandidate>> searchNearEach(VectorEnrichedInput input, SearchRole role,
                                                     List<double[]> centers, double radiusKm, int limit) {
        SearchSlot slot = slotFor(input, role);
        float[] vector = slot != null ? embedOne(slot.queryText()) : null;
        List<List<PlaceCandidate>> out = new ArrayList<>(centers.size());
        for (double[] center : centers) {
            out.add(vector == null || center == null || limit <= 0 ? List.of()
                    : searchNearWith(input, role, slot, vector, center, radiusKm, limit, 1));
        }
        return out;
    }

    private SearchSlot slotFor(VectorEnrichedInput input, SearchRole role) {
        return SearchQueryBuilder.buildSearchSlots(input).stream()
                .filter(sl -> sl.role() == role)
                .findFirst()
                .orElse(null);
    }

    private float[] embedOne(String text) {
        long start = System.nanoTime();
        List<float[]> vectors = embeddingService.embedBatch(List.of(text));
        log.info("보완 검색 임베딩 {}ms", elapsedMs(start));
        if (vectors.isEmpty() || vectors.get(0) == null || vectors.get(0).length == 0) return null;
        return vectors.get(0);
    }

    private List<PlaceCandidate> searchNearWith(VectorEnrichedInput input, SearchRole role, SearchSlot slot,
                                                float[] vector, double[] center, double radiusKm,
                                                int limit, int startIndex) {
        VectorSearchRequest request = new VectorSearchRequest(
                vector, input.country(), input.regions(), null, input.searchTags(),
                input.budgetRange(), overfetchFor(role, limit),
                PlaceCategoryConstants.excludedCategoryPatterns(role),
                center, radiusKm, role == SearchRole.ATTRACTION);

        long queryStart = System.nanoTime();
        List<VectorSearchResult> raw = placeVectorSearchService.search(request);
        long queryMs = elapsedMs(queryStart);
        List<ScoredResult> accepted = applyThresholdAndRank(slot, raw, limit);

        List<PlaceCandidate> result = new ArrayList<>(accepted.size());
        int index = startIndex;
        for (ScoredResult scored : accepted) {
            VectorSearchResult r = scored.result();
            // bounding box 근사라 정확한 거리로 한 번 더 거른다.
            if (r.latitude() == null || r.longitude() == null) continue;
            double dist = GeoUtils.haversine(center,
                    new double[]{r.latitude().doubleValue(), r.longitude().doubleValue()});
            if (dist > radiusKm) continue;
            PlaceCandidate c = new PlaceCandidate(
                    index, r.placeId(), r.name(), r.address(), r.category(), r.tags(),
                    r.region(), r.country(), r.latitude(), r.longitude(), r.description(),
                    r.rating(), r.similarityScore(), null, null, null, null, false, null,
                    PlaceCategoryConstants.extractSubRegion(r.address()), r.userRatingCount(),
                    r.wikidataSitelinks(), r.placeRole(), r.cuisine(), false, null, r.attributes(), r.nameKo(),
                    r.travelerFame());
            if (CandidatePolicy.roleOf(c) == CandidatePolicy.Role.NOT_VISITABLE) continue;
            result.add(c);
            index++;
        }
        log.info("day centroid 기준 {} 보완 검색: 반경 {}km, 조회={}, 채택={}, DB {}ms",
                role, String.format("%.0f", radiusKm), raw.size(), result.size(), queryMs);
        return result;
    }

    /**
     * 슬롯 최소 유사도 미만을 버리고 하이브리드 점수로 재랭킹해 상위 limit만 채택한다.
     * 후보가 줄어든 이유를 사후에 추적하도록 반환/탈락 수를 슬롯별로 로그에 남긴다.
     */
    List<ScoredResult> applyThresholdAndRank(SearchSlot slot, List<VectorSearchResult> raw, int limit) {
        double minSimilarity = PlaceCategoryConstants.minSimilarity(slot.role());
        String slotMajor = PlaceCategoryConstants.slotMajorCategory(slot.role());

        List<VectorSearchResult> passed = new ArrayList<>();
        int dropped = 0;
        for (VectorSearchResult r : raw) {
            if (r.similarityScore() < thresholdFor(r, slotMajor, minSimilarity, exemptsIconic(slot))) {
                dropped++;
                continue;
            }
            passed.add(r);
        }

        double neutral = neutralProminence(passed);
        List<ScoredResult> kept = new ArrayList<>(passed.size());
        for (VectorSearchResult r : passed) {
            kept.add(new ScoredResult(r, hybridScore(r, slotMajor, slot.labelTokens(), neutral)));
        }
        kept.sort(Comparator.comparingDouble(ScoredResult::score).reversed());
        // 대표 관광지 슬롯은 유사도 순이면 일반어 질의와 이름이 겹치는 무명 장소가 이겨 인기도 순으로 고른다.
        // 테마별 슬롯은 주제 적합성이 우선이라 유사도 중심 점수를 쓴다.
        if (REPRESENTATIVE_SLOT_KEY.equals(slot.key())) {
            double n0 = neutral;
            kept.sort(Comparator.<ScoredResult>comparingDouble(sr -> prominence(sr.result(), n0)).reversed()
                    .thenComparing(Comparator.comparingDouble(ScoredResult::score).reversed()));
        }
        kept = dropSameWikidataItem(kept);
        List<ScoredResult> accepted = isDiversitySlot(slot.role())
                ? selectWithSubTypeDiversity(kept, limit)
                : kept.size() > limit ? new ArrayList<>(kept.subList(0, limit)) : kept;

        log.info("슬롯 검색: key={}, role={}, 조회={}, 임계탈락={}, 채택={} (임계값={}, 인기도미상 중립={})",
                slot.key(), slot.role(), raw.size(), dropped, accepted.size(),
                String.format("%.2f", minSimilarity), String.format("%.2f", neutral));
        return accepted;
    }

    /** 지역 대표 명소를 뽑는 기본 관광 슬롯 키(SearchQueryBuilder가 항상 1개 만든다). */
    static final String REPRESENTATIVE_SLOT_KEY = "attraction";

    /** 인기 명소로 보는 Wikidata 언어판 수 하한. 이 이상이면 유사도 임계값에서 면제한다. */
    static final int ICONIC_SITELINKS = 3;
    /** 대분류가 슬롯과 같을 때 낮춰 주는 임계값 폭. */
    static final double SAME_MAJOR_THRESHOLD_RELIEF = 0.10;

    /**
     * 후보별 최소 유사도. 슬롯 임계값을 기본으로 하되 두 경우를 완화한다.
     * <ul>
     *   <li>대분류가 슬롯과 같은 인기 명소(언어판 {@value #ICONIC_SITELINKS}개 이상)는 면제한다. 일반어 질의와
     *       고유 명소의 유사도는 원래 낮아서 유사도로 자르면 대표 명소가 빠진다.
     *       경기장·대학 같은 행사 시설({@link PlaceCategoryConstants#isEventVenue})은 면제하지 않는다.</li>
     *   <li>대분류가 슬롯과 같은 후보는 {@value #SAME_MAJOR_THRESHOLD_RELIEF}만큼 낮춘다. 보강 전 외국어 이름 행은
     *       한국어 질의와의 유사도가 낮아 전부 탈락한다.</li>
     * </ul>
     */
    private static double thresholdFor(VectorSearchResult r, String slotMajor, double minSimilarity,
                                       boolean exemptIconic) {
        boolean sameMajor = slotMajor != null && slotMajor.equals(PlaceCategoryConstants.majorCategory(r.category()));
        if (!sameMajor) return minSimilarity;
        if (exemptIconic && r.wikidataSitelinks() != null && r.wikidataSitelinks() >= ICONIC_SITELINKS
                && !PlaceCategoryConstants.isEventVenue(r.name(), r.category())) {
            return Double.NEGATIVE_INFINITY;
        }
        return minSimilarity - SAME_MAJOR_THRESHOLD_RELIEF;
    }

    /**
     * 인기 명소 면제를 적용할 슬롯. 테마 슬롯("attraction:theme:*")은 주제 적합성이 핵심이라
     * 유명해도 주제와 무관하면 뺀다.
     */
    private static boolean exemptsIconic(SearchSlot slot) {
        if (slot.role() != SearchRole.ATTRACTION) return false;
        String key = slot.key();
        return REPRESENTATIVE_SLOT_KEY.equals(key) || (key.startsWith("attraction:") && !key.startsWith("attraction:theme:"));
    }

    /**
     * 같은 Wikidata 항목에 붙은 출처별 중복 행 중 순위가 높은 행만 남긴다.
     * 줄이지 않으면 인기도 순 대표 슬롯을 한 장소가 여러 자리 차지한다.
     */
    private static List<ScoredResult> dropSameWikidataItem(List<ScoredResult> ranked) {
        Set<String> seen = new HashSet<>();
        List<ScoredResult> out = new ArrayList<>(ranked.size());
        for (ScoredResult s : ranked) {
            String qid = s.result().wikidataId();
            if (qid == null || seen.add(qid)) out.add(s);
        }
        return out;
    }

    /**
     * 슬롯 안에서는 dropSameWikidataItem이 거르지만, 같은 장소의 다른 행이 서로 다른 슬롯에 걸리면 둘 다 남는다.
     * 항목별로 점수가 가장 높은 행만 남기고, 남는 행의 순서는 그대로 둔다.
     */
    static List<VectorSearchResult> keepBestPerWikidataItem(List<ScoredResult> merged) {
        Map<String, ScoredResult> best = new HashMap<>();
        for (ScoredResult s : merged) {
            String qid = s.result().wikidataId();
            if (qid == null) continue;
            best.merge(qid, s, (a, b) -> b.score() > a.score() ? b : a);
        }
        List<VectorSearchResult> out = new ArrayList<>(merged.size());
        for (ScoredResult s : merged) {
            String qid = s.result().wikidataId();
            if (qid == null || best.get(qid) == s) out.add(s.result());
        }
        return out;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private int overfetchFor(SearchRole role, int limit) {
        return isDiversitySlot(role)
                ? Math.min(DIVERSITY_MAX_OVERFETCH, limit * DIVERSITY_OVERFETCH_FACTOR)
                : Math.min(MAX_OVERFETCH, limit * OVERFETCH_FACTOR);
    }

    /** 유형 상한({@link CandidateTraits#subTypeOf})과 같은 키를 쓴다. */
    private static String mealTypeKey(VectorSearchResult r) {
        return PlaceCategoryConstants.mealKind(r.name(), r.nameKo(), r.category(), r.tags(), r.description(),
                r.cuisine());
    }

    /** 끼니·카페는 종류가 반복되면 티가 나서 세부 유형 쏠림을 막는 슬롯. */
    private boolean isDiversitySlot(SearchRole role) {
        return role == SearchRole.RESTAURANT || role == SearchRole.CAFE;
    }

    /**
     * 식사·카페 슬롯에서 세부 유형 상한을 걸어 뽑는다. 유사도 상위가 한 종류로 쏠리면 조립 단계 유형 상한은
     * 대체 후보가 없어 만족할 수 없으므로 윈도에서 고친다. limit이 남으면 밀어둔 후보를 점수 순으로 되돌려 후보 수는 줄이지 않는다.
     */
    private List<ScoredResult> selectWithSubTypeDiversity(List<ScoredResult> ranked, int limit) {
        if (ranked.size() <= limit) return ranked;
        int perType = Math.max(MIN_PER_SUB_TYPE, (int) Math.ceil(limit / 4.0));
        // 묶음 상한만 두면 해산물 자리가 전부 조개구이일 수 있어 요리 종류는 먼저 그 절반까지만 받는다.
        // 남은 묶음 자리를 점수 순 되돌림에 바로 넘기면 쏠린 종류가 다시 차지해 같은 묶음의 요리부터 채운다.
        int perDish = Math.max(1, perType / 2);
        Map<String, Integer> taken = new HashMap<>();
        Map<String, Integer> takenGroups = new HashMap<>();
        List<ScoredResult> picked = new ArrayList<>(limit);
        List<ScoredResult> deferred = new ArrayList<>(ranked);

        for (boolean dishFirst : List.of(true, false)) {
            List<ScoredResult> next = new ArrayList<>();
            for (ScoredResult s : deferred) {
                String type = mealTypeKey(s.result());
                String group = PlaceCategoryConstants.mealGroup(type);
                int typeCap = dishFirst && !type.equals(group) ? perDish : perType;
                if (picked.size() >= limit || taken.getOrDefault(type, 0) >= typeCap
                        || takenGroups.getOrDefault(group, 0) >= perType) {
                    next.add(s);
                    continue;
                }
                taken.merge(type, 1, Integer::sum);
                takenGroups.merge(group, 1, Integer::sum);
                picked.add(s);
            }
            deferred = next;
        }
        for (ScoredResult s : deferred) {
            if (picked.size() >= limit) break;
            picked.add(s);
        }
        log.info("세부유형 다양화: limit={}, 유형당 상한={}(요리 {}), 채택 유형={}, 묶음={}", limit, perType, perDish, taken,
                takenGroups);
        return picked;
    }

    /**
     * "인기도 미상"에 매길 값을 이 슬롯 후보의 Wikidata 매칭 비율로 정한다. 고정값은 높으면 백필된 지역에서
     * 인기도 신호가 묻히고, 낮으면 미매칭 지역 명소가 바닥에 깔린다.
     * 커버리지 0% → {@value #NEUTRAL_RATING_RATIO}, 100% → 0.15로 선형 보간한다.
     */
    private double neutralProminence(List<VectorSearchResult> candidates) {
        if (candidates.isEmpty()) return NEUTRAL_RATING_RATIO;
        long known = candidates.stream().filter(r -> sitelinksOf(r) != null).count();
        double coverage = (double) known / candidates.size();
        return NEUTRAL_RATING_RATIO - (NEUTRAL_RATING_RATIO - UNKNOWN_FLOOR_RATIO) * coverage;
    }

    /**
     * 하이브리드 점수 = 유사도 + 인기도 가중 + 대분류 일치 가점 + 라벨(테마·카테고리) 매칭 가점.
     * 임베딩만으로는 대표 명소가 위로 올라오지 못한다.
     */
    private double hybridScore(VectorSearchResult r, String slotMajorCategory,
                               List<String> labelTokens, double neutralProminence) {
        double score = r.similarityScore();

        score += RATING_WEIGHT * prominence(r, neutralProminence);

        if (slotMajorCategory != null
                && slotMajorCategory.equals(PlaceCategoryConstants.majorCategory(r.category()))) {
            score += MAJOR_CATEGORY_BONUS;
        }

        if (matchesLabel(r, labelTokens)) {
            score += LABEL_MATCH_BONUS;
        }
        if (r.tourismListed() && "ATTRACTION".equals(slotMajorCategory)) {
            score += TOURISM_LISTED_BONUS;
        }
        return score;
    }

    /**
     * 장소의 인기도(0.0~1.0). 충전 경로와 같은 기준을 쓰도록 계산은 {@link PlaceProminence}에 두고,
     * 여기서는 미매칭만 슬롯 커버리지 기반 중립값으로 바꾼다.
     */
    private double prominence(VectorSearchResult r, double neutralProminence) {
        Integer sitelinks = sitelinksOf(r);
        if (sitelinks == null && r.userRatingCount() == null) {
            return neutralProminence;
        }
        return PlaceProminence.of(r.name(), r.category(), r.rating(), r.userRatingCount(), sitelinks);
    }

    private static Integer sitelinksOf(VectorSearchResult r) {
        return PlaceProminence.effectiveSitelinks(r.wikidataSitelinks(), r.travelerFame());
    }

    private boolean matchesLabel(VectorSearchResult r, List<String> labelTokens) {
        if (labelTokens == null || labelTokens.isEmpty()) return false;
        StringBuilder sb = new StringBuilder();
        if (r.name() != null) sb.append(r.name()).append(' ');
        if (r.tags() != null) r.tags().forEach(t -> sb.append(t).append(' '));
        String haystack = sb.toString().toLowerCase();
        if (haystack.isBlank()) return false;
        return labelTokens.stream().anyMatch(haystack::contains);
    }

    record ScoredResult(VectorSearchResult result, double score) {}

    /**
     * region 그룹별 좌표 median에서 {@link #MAX_REGION_RADIUS_KM}를 넘는 오태깅 후보를 제거한다.
     * 오염 좌표가 소수 섞여도 중심점이 흔들리지 않게 평균이 아닌 median을 쓴다. 좌표가 없는 후보는 보존한다.
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
                kept.addAll(group);
                continue;
            }

            double centerLat = GeoUtils.median(withCoord.stream().map(r -> r.latitude().doubleValue()).toList());
            double centerLng = GeoUtils.median(withCoord.stream().map(r -> r.longitude().doubleValue()).toList());

            for (VectorSearchResult r : group) {
                if (r.latitude() == null || r.longitude() == null) {
                    kept.add(r);
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

    int calculateTotalLimit(VectorEnrichedInput input) {
        long days = calculateTripDays(input);
        int calculated = (int) (days * 10);
        return Math.max(MIN_TOTAL, Math.min(calculated, MAX_TOTAL));
    }

    /**
     * 슬롯별 벡터 검색 limit을 배분한다(식당 25% / 숙소 10% / 카페 15% / 교통 10% / 관광 나머지).
     * 관광 총량만 관광 슬롯 수로 나눈다(슬롯당 최소 {@link #MIN_PER_ATTRACTION_SLOT}).
     */
    Map<String, Integer> buildSlotLimits(List<SearchSlot> slots, int totalLimit, long days) {
        Map<String, Integer> limits = new HashMap<>();
        boolean hasTransportation = slots.stream().anyMatch(s -> s.role() == SearchRole.TRANSPORTATION);

        List<SearchSlot> attractionSlots = slots.stream()
                .filter(s -> s.role() == SearchRole.ATTRACTION)
                .toList();
        // 교통이 없으면 그 10%를 관광에 더한다.
        double attractionRatio = hasTransportation ? 0.40 : 0.50;
        // 관광은 하루 2곳 + 빈 시간 채우기·교체 여유로 최소 days×4다. 더 적으면 긴 공백을 채울 장소가 없다.
        int attractionTotal = Math.max((int) (days * 4), (int) (totalLimit * attractionRatio));
        // 시장은 지역에 몇 곳뿐이라 큰 몫을 주면 유사도 낮은 상점으로 채워지고 다른 관광 슬롯 몫이 준다.
        boolean hasMarketSlot = attractionSlots.stream().anyMatch(sl -> sl.key().equals("attraction:market"));
        int marketLimit = (int) Math.max(2, days);
        int sharedSlots = attractionSlots.size() - (hasMarketSlot ? 1 : 0);
        int perAttraction = sharedSlots <= 0
                ? 0
                : Math.max(MIN_PER_ATTRACTION_SLOT, (attractionTotal - (hasMarketSlot ? marketLimit : 0)) / sharedSlots);

        for (SearchSlot slot : slots) {
            int limit = switch (slot.role()) {
                case TRANSPORTATION -> Math.max(2, (int) (totalLimit * 0.1));
                // 하루 2식 + 대안·갭필 여유로 days×4를 하한으로 둔다.
                case RESTAURANT -> Math.max((int) (days * 4), (int) (totalLimit * 0.25));
                // 숙소는 day별 방문지 중심 최근접을 고르므로 후보가 적으면 한쪽에 몰려 매일 장거리 귀가가 된다.
                case ACCOMMODATION -> Math.max(6, (int) (totalLimit * 0.1));
                // 카페 스톱(하루 1곳) + 대안·빈 시간 채우기 여유.
                case CAFE -> Math.max((int) (days * 2), (int) (totalLimit * 0.15));
                case ATTRACTION -> slot.key().equals("attraction:market") ? marketLimit
                        // 대표 명소 슬롯은 테마 슬롯과 균등 분할하면 너무 적어 하루 2곳 몫에 여유를 더해 보장한다.
                        : slot.key().equals(REPRESENTATIVE_SLOT_KEY)
                        ? Math.max(perAttraction, (int) (days * 2) + REPRESENTATIVE_RESERVE)
                        : perAttraction;
            };
            limits.put(slot.key(), Math.min(limit, MAX_PER_CATEGORY));
        }
        return limits;
    }

    boolean shouldSplitByRegion(VectorEnrichedInput input) {
        return input.regionAllocation() != null
                && !input.regionAllocation().isEmpty()
                && calculateTripDays(input) >= 5;
    }

    /**
     * 한 슬롯을 여러 실행 단위로 나눠 조회한 결과에서 같은 장소를 하나로 합친다(최고 유사도 유지).
     * 한 도시 안에서 구역을 나누면 모든 단위가 같은 지역 필터로 조회해, 합치지 않으면 상위 N을 같은 장소가 차지한다.
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

    private static final int NAMED_SCAN_LIMIT = 300;
    private static final int NAMED_MAX = 5;
    /** 이름의 이 비율 이상이 요청 문장에 있어야 그 장소를 말한 것으로 본다. "해운대 블루라인파크"가 "해운대"로 걸리지 않는다. */
    private static final double NAMED_PREFIX_RATIO = 0.4;

    /**
     * 요청 문장에 이름이 나온 명소. 일반어 질의 임베딩과 인기도 상위 갈래 어느 쪽에도 안 잡히면
     * 사용자가 말한 곳이 후보에 없어 선택 단계가 고를 수 없다.
     */
    private List<VectorSearchResult> namedPlaces(VectorEnrichedInput input, float[] vector,
                                                 List<VectorSearchResult> already) {
        if (input.description() == null || input.description().isBlank() || vector == null) return List.of();
        String text = compact(input.description());
        String destination = compact(input.destination()) + "|" + compact(input.normalizedDestination());
        Set<Long> known = already.stream().map(VectorSearchResult::placeId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // 식당 "광안리유가횟집"이 있다고 광안리해수욕장을 말한 게 해결되지는 않는다.
        List<String> knownNames = already.stream()
                .filter(r -> "ATTRACTION".equals(PlaceCategoryConstants.majorCategory(r.category())))
                .flatMap(r -> java.util.stream.Stream.of(placeName(r.name()), placeName(r.nameKo())))
                .filter(n -> !n.isEmpty())
                .toList();
        Set<String> knownQids = already.stream().map(VectorSearchResult::wikidataId).filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, VectorSearchResult> byMention = new LinkedHashMap<>();
        for (VectorSearchResult r : placeVectorSearchService.findPopular(vector, input.country(), input.regions(),
                NAMED_SCAN_LIMIT)) {
            if (!"ATTRACTION".equals(PlaceCategoryConstants.majorCategory(r.category()))
                    || PlaceCategoryConstants.isAdministrativeZone(r.name())) continue;
            String mention = mentionOf(r, text);
            if (mention == null || destination.contains(mention)) continue;
            // 언어판 내림차순이라 같은 이름을 먼저 가진 쪽이 대표 명소다.
            byMention.putIfAbsent(mention, r);
        }
        // "광안리"가 걸렸으면 "광안"으로 걸린 광안대교는 사용자가 말한 곳이 아니다.
        Set<String> mentions = Set.copyOf(byMention.keySet());
        byMention.keySet().removeIf(m -> mentions.stream()
                .anyMatch(other -> other.length() > m.length() && other.startsWith(m)));
        byMention.keySet().removeIf(m -> knownNames.stream().anyMatch(n -> n.startsWith(m)));
        List<VectorSearchResult> out = byMention.values().stream()
                .filter(r -> !known.contains(r.placeId()) && !knownQids.contains(r.wikidataId()))
                .limit(NAMED_MAX)
                .toList();
        if (!out.isEmpty()) {
            log.info("요청 문장에 나온 명소 추가: {}", out.stream().map(VectorSearchResult::name).toList());
        }
        return out;
    }

    /** 해외 명소는 원문 이름("浅草寺")이 한국어 문장에 나오지 않아 한국어 이름("센소지")으로도 본다. */
    private static String mentionOf(VectorSearchResult r, String text) {
        String byName = mentionedPrefix(placeName(r.name()), text);
        if (byName != null || r.nameKo() == null) return byName;
        return mentionedPrefix(placeName(r.nameKo()), text);
    }

    /** 이름의 앞부분 중 문장에 나오는 가장 긴 것. 너무 짧으면 null. */
    static String mentionedPrefix(String name, String text) {
        int min = Math.max(2, (int) Math.ceil(name.length() * NAMED_PREFIX_RATIO));
        for (int len = name.length(); len >= min; len--) {
            if (text.contains(name.substring(0, len))) return name.substring(0, len);
        }
        return null;
    }

    /** 병기 주석("불국사 (佛國寺)")을 떼야 앞부분이 문장의 "불국사"와 맞는다. */
    private static String placeName(String name) {
        return compact(name == null ? null : name.replaceAll("\\(.*?\\)|\\[.*?]", ""));
    }

    private static String compact(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /** 근교 명소 한 곳마다 따로 찾는 식당·카페 수. 그날 점심·카페를 명소 안에서 고를 만큼이다. */
    private static final Map<SearchRole, Integer> LANDMARK_DINING_LIMIT = Map.of(
            SearchRole.RESTAURANT, 3, SearchRole.CAFE, 1);

    /**
     * 요청한 근교 명소(바나힐·바다링) 안의 식당·카페. 여행지 전체 검색에서는 상위 도시 식당에 밀려 0곳이 되어
     * 그날 점심을 도시까지 내려가 먹는다.
     */
    private List<VectorSearchResult> landmarkMeals(VectorEnrichedInput input, List<SearchUnit> units,
                                                   List<float[]> vectors, List<VectorSearchResult> already) {
        if (input.regions() == null) return List.of();
        List<String> landmarks = input.regions().stream()
                .map(destinations::find)
                .flatMap(java.util.Optional::stream)
                .filter(d -> d.parent() != null && !d.isCity())
                .map(Destination::name)
                .distinct()
                .toList();
        if (landmarks.isEmpty()) return List.of();

        Set<Long> known = already.stream().map(VectorSearchResult::placeId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        List<VectorSearchResult> out = new ArrayList<>();
        Set<SearchRole> searched = new HashSet<>();
        for (int i = 0; i < units.size() && i < vectors.size(); i++) {
            SearchSlot slot = units.get(i).slot();
            Integer limit = LANDMARK_DINING_LIMIT.get(slot.role());
            if (limit == null || vectors.get(i) == null || !searched.add(slot.role())) continue;
            for (String landmark : landmarks) {
                List<VectorSearchResult> raw = placeVectorSearchService.search(
                        buildRequest(input, vectors.get(i), List.of(landmark), slot, overfetchFor(slot.role(), limit), true));
                for (ScoredResult scored : applyThresholdAndRank(slot, dedupeByPlace(raw), limit)) {
                    Long id = scored.result().placeId();
                    if (id != null && known.add(id)) out.add(scored.result());
                }
            }
        }
        if (!out.isEmpty()) {
            log.info("근교 명소 식당·카페 추가 {}: {}", landmarks,
                    out.stream().map(VectorSearchResult::name).toList());
        }
        return out;
    }

    private static float[] attractionVector(List<SearchUnit> units, List<float[]> vectors) {
        for (int i = 0; i < units.size() && i < vectors.size(); i++) {
            if (units.get(i).slot().role() == SearchRole.ATTRACTION && vectors.get(i) != null) return vectors.get(i);
        }
        return vectors.isEmpty() ? null : vectors.get(0);
    }

    /**
     * 검색 실행 단위. 지역 분리가 없으면 슬롯당 1개, 있으면 (슬롯 × regionAllocation 엔트리)마다 1개다.
     *
     * @param slot          결과를 묶을 슬롯. 임계값·랭킹은 이 단위로 적용한다
     * @param queryText     임베딩할 쿼리. 지역 분리 시 필터로 못 쓰는 세부 지명이 뒤에 붙는다
     * @param filterRegions DB {@code place.region} 하드 필터로 쓸 값. null이면 {@code input.regions()}
     */
    private record SearchUnit(SearchSlot slot, String queryText, List<String> filterRegions, boolean seedPopularity) {
        SearchUnit(SearchSlot slot, String queryText, List<String> filterRegions) {
            this(slot, queryText, filterRegions, true);
        }
    }

    /**
     * 슬롯 목록을 실제 검색 실행 단위로 펼친다. {@code place.region}은 영어 상위 도시명뿐인데 regionAllocation에는
     * 행정동·지형·랜드마크가 섞여 와서, 그대로 필터에 넣으면 전 슬롯이 조용히 0건이 된다.
     * {@link VectorEnrichedInput#regions()}에 실재하는 값만 필터로 쓰고 나머지는 쿼리 텍스트에 얹는다.
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
     * 식사·카페·대표 관광지 슬롯은 AI 힌트 없는 기본 쿼리로도 찾는다. 힌트가 한 동네·한 음식으로 좁으면
     * ("광안리 해산물 조개구이") 슬롯 후보 전체가 그쪽으로 쏠리고, 슬롯 limit 안의 다양화로는 되돌릴 수 없다.
     */
    private static List<SearchUnit> withBaseQueryUnits(List<SearchUnit> units, List<SearchSlot> slots) {
        List<SearchUnit> out = new ArrayList<>(units);
        for (SearchSlot slot : slots) {
            boolean needsBaseQuery = slot.role() == SearchRole.RESTAURANT || slot.role() == SearchRole.CAFE
                    || REPRESENTATIVE_SLOT_KEY.equals(slot.key());
            if (needsBaseQuery && !slot.baseQueryText().equals(slot.queryText())) {
                // 인기도 갈래는 질의와 무관해 힌트 단위가 이미 가져온다
                out.add(new SearchUnit(slot, slot.baseQueryText(), null, false));
            }
        }
        return out;
    }

    /**
     * enrichInput 프롬프트가 country=ISO코드, regions=영어 도시명으로 반환하도록 지정한다.
     * 마법사 카테고리 id와 DB category는 형식이 다르고 오적재가 흔해 categories 하드 필터는 넘기지 않는다.
     */
    private VectorSearchRequest buildRequest(VectorEnrichedInput input,
                                              float[] queryVector,
                                              List<String> regions,
                                              SearchSlot slot,
                                              int limit,
                                              boolean seedPopularity) {
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
                slot.excludedCategoryPatterns(),
                null,
                0,
                seedPopularity && slot.role() == SearchRole.ATTRACTION
        );
    }

    /** 중간 후보를 뺀 뒤 1-based 연속 인덱스로 다시 매긴다. */
    private static List<PlaceCandidate> reindex(List<PlaceCandidate> candidates) {
        List<PlaceCandidate> out = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            PlaceCandidate c = candidates.get(i);
            out.add(c.index() == i + 1 ? c : c.reindexed(i + 1));
        }
        return out;
    }

    /**
     * 1-based 연속 인덱싱을 유지하고 주소에서 세부지역(시/군/구)을 추출해 부여한다.
     * priceLevel, openingHours는 이후 enrichment 단계에서 채운다.
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
                    subRegion,
                    r.userRatingCount(),
                    r.wikidataSitelinks(),
                    r.placeRole(),
                    r.cuisine(),
                    false,
                    null,
                    r.attributes(),
                    r.nameKo(),
                    r.travelerFame()
            ));
        }
        if (!results.isEmpty()) {
            log.info("세부지역 추출: {}/{}건 (실패분은 상위 region 사용)", withSubRegion, results.size());
        }
        return candidates;
    }

    private long calculateTripDays(VectorEnrichedInput input) {
        if (input.startDate() == null || input.endDate() == null) {
            return 3; // 기본값
        }
        long days = ChronoUnit.DAYS.between(input.startDate(), input.endDate()) + 1;
        return Math.max(1, days);
    }
}
