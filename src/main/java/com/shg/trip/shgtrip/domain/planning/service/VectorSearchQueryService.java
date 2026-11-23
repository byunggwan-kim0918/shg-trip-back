package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.embedding.EmbeddingService;
import com.shg.trip.shgtrip.domain.place.name.PlaceNames;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 기준 좌표 둘레의 역할별 벡터 검색 → 최소 유사도 임계값 → 하이브리드 랭킹 → PlaceCandidate 변환.
 * 질의 문구는 역할별 대표 슬롯이다({@link SearchQueryBuilder}).
 */
@Service
public class VectorSearchQueryService {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchQueryService.class);

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
    private static final double MAJOR_CATEGORY_BONUS = 0.05;
    /**
     * 관광 슬롯에서 관광공사 등록 장소에 주는 가점. 국내 명소는 언어판 수가 낮은 곳이 많아 유사도가 비슷한
     * 비관광 시설(볼링장·체험 공간)에 밀린다. 식당은 등록된 곳이 드물어 주지 않는다.
     */
    private static final double TOURISM_LISTED_BONUS = 0.05;
    private static final double LABEL_MATCH_BONUS = 0.03;

    private final EmbeddingService embeddingService;
    private final PlaceVectorSearchService placeVectorSearchService;

    public VectorSearchQueryService(EmbeddingService embeddingService,
                                    PlaceVectorSearchService placeVectorSearchService) {
        this.embeddingService = embeddingService;
        this.placeVectorSearchService = placeVectorSearchService;
    }

    /** 근처 검색을 여러 번 하는 계획 한 건이 역할별 질의 임베딩을 한 번만 하도록 묶는다. */
    public NearSearch nearSearch(VectorEnrichedInput input) {
        return new NearSearch(input);
    }

    /** 계획 한 건 안에서만 쓴다. 여러 스레드가 나눠 쓰지 않는다. */
    public final class NearSearch {
        private final VectorEnrichedInput input;
        private final Map<SearchRole, Prepared> prepared = new EnumMap<>(SearchRole.class);

        private NearSearch(VectorEnrichedInput input) {
            this.input = input;
        }

        public List<PlaceCandidate> search(SearchRole role, double[] center, double radiusKm, int limit,
                                           int startIndex) {
            if (center == null || limit <= 0) return List.of();
            Prepared p = prepared.computeIfAbsent(role, r -> {
                SearchSlot slot = slotFor(input, r);
                return new Prepared(slot, slot == null ? null : embedOne(slot.queryText()));
            });
            if (p.vector() == null) return List.of();
            return searchNearWith(input, role, p.slot(), p.vector(), center, radiusKm, limit, startIndex);
        }
    }

    private record Prepared(SearchSlot slot, float[] vector) {
    }

    // 관광은 카테고리·테마별 문구(시장 등)가 대표 문구보다 앞에 올 수 있어, 근처 보완은 대표 문구로 찾는다.
    private SearchSlot slotFor(VectorEnrichedInput input, SearchRole role) {
        List<SearchSlot> slots = SearchQueryBuilder.buildSearchSlots(input).stream()
                .filter(sl -> sl.role() == role)
                .toList();
        return slots.stream().filter(sl -> REPRESENTATIVE_SLOT_KEY.equals(sl.key())).findFirst()
                .orElse(slots.isEmpty() ? null : slots.get(0));
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
        List<ScoredResult> accepted = applyThresholdAndRank(slot, raw, limit,
                PlaceRestrictions.of(input.restrictions()));

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
                    PlaceCategoryConstants.subRegionOf(r.address(), r.region()), r.userRatingCount(),
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
        return applyThresholdAndRank(slot, raw, limit, PlaceRestrictions.NONE);
    }

    List<ScoredResult> applyThresholdAndRank(SearchSlot slot, List<VectorSearchResult> raw, int limit,
                                             PlaceRestrictions restrictions) {
        return applyThresholdAndRank(slot, raw, limit, restrictions, restrictions.twinKeys(raw));
    }

    /**
     * 못 먹는 음식·피할 활동은 자르기 전에 빼야 그 자리가 다른 후보로 채워진다.
     * twinKeys는 걸린 행의 이름 키({@link PlaceRestrictions#twinKeys})이고, 이름이 같은 행도 함께 뺀다.
     */
    List<ScoredResult> applyThresholdAndRank(SearchSlot slot, List<VectorSearchResult> raw, int limit,
                                             PlaceRestrictions restrictions, Set<String> twinKeys) {
        double minSimilarity = PlaceCategoryConstants.minSimilarity(slot.role());
        String slotMajor = PlaceCategoryConstants.slotMajorCategory(slot.role());

        List<VectorSearchResult> passed = new ArrayList<>();
        int dropped = 0;
        List<String> restricted = new ArrayList<>();
        for (VectorSearchResult r : raw) {
            if (r.similarityScore() < thresholdFor(r, slotMajor, minSimilarity, exemptsIconic(slot))) {
                dropped++;
                continue;
            }
            if (restrictions.violatedBy(r) != null || restrictions.sharesName(r, twinKeys)) {
                restricted.add(r.name());
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
        // 종류를 모르는 식당은 못 먹는 음식일 수 있어 아는 식당이 모자랄 때만 쓴다.
        if (!restrictions.isEmpty()) {
            kept.sort(Comparator.comparing((ScoredResult sr) -> restrictions.isUnknownFood(sr.result())));
        }
        kept = dropSameWikidataItem(kept);
        List<ScoredResult> accepted = isDiversitySlot(slot.role())
                ? selectWithSubTypeDiversity(kept, limit)
                : kept.size() > limit ? new ArrayList<>(kept.subList(0, limit)) : kept;

        log.info("슬롯 검색: key={}, role={}, 조회={}, 임계탈락={}, 조건제외={}, 채택={} (임계값={}, 인기도미상 중립={})",
                slot.key(), slot.role(), raw.size(), dropped, restricted.size(), accepted.size(),
                String.format("%.2f", minSimilarity), String.format("%.2f", neutral));
        if (!restricted.isEmpty()) {
            log.info("슬롯 {} 피할 조건 제외 {}곳: {}", slot.key(), restricted.size(),
                    restricted.subList(0, Math.min(8, restricted.size())));
        }
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

}
