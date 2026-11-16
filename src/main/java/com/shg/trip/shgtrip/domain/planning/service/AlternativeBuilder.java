package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.AlternativeData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.global.util.GeoUtils;

import java.util.*;
import java.util.stream.Collectors;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.coordsOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.isSubstituteFor;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.placeKey;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.subTypeOf;
import static com.shg.trip.shgtrip.domain.planning.service.CostEstimator.estimateCost;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.*;

/** 스텝별 대안 장소. */
final class AlternativeBuilder {

    private AlternativeBuilder() {
    }

    /** 대안이 이보다 적으면 다른 날에 나온 장소도 다시 쓴다. 선택지가 하나뿐인 스텝보다 반복이 낫다. */
    static final int MIN_ALTERNATIVES = 2;
    private static final int MAX_ALTERNATIVES = 3;

    /**
     * 여행 전체 기준의 대안 조건.
     *
     * @param shownElsewhere 다른 날 대안으로 이미 나온 장소 키
     * @param mealKindCounts 일정에 쓴 끼니의 종류별 수
     * @param mealKindCap    여행 전체 같은 종류 끼니 상한
     * @param brands         일정에 쓴 식음료 가게의 브랜드(지점명을 뗀 이름)
     */
    record TripView(Set<String> shownElsewhere, Map<String, Integer> mealKindCounts, int mealKindCap,
                    Set<String> brands) {

        static final TripView NONE = new TripView(Set.of(), Map.of(), Integer.MAX_VALUE, Set.of());

        boolean prefers(PlaceCandidate replaced, PlaceCandidate c) {
            if (c.mealEligible()) {
                String kind = CandidatePolicy.cuisineOf(c);
                if (CandidateLookup.isSpecificKind(kind)) {
                    int count = mealKindCounts.getOrDefault(kind, 0)
                            - (replaced.mealEligible() && kind.equals(CandidatePolicy.cuisineOf(replaced)) ? 1 : 0);
                    if (count >= mealKindCap) return false;
                }
            }
            return !CandidateTraits.repeatsBrand(brands, c)
                    || CandidateTraits.sameBrand(CandidateTraits.diningBrand(c), CandidateTraits.diningBrand(replaced));
        }
    }

    private static final java.util.regex.Pattern BRANCH_TOKEN =
            java.util.regex.Pattern.compile(".*(본점|지점|[0-9]+호점|점|店)");
    private static final java.util.regex.Pattern MIDDLE_DOTS = java.util.regex.Pattern.compile("[・·]");
    private static final java.util.regex.Pattern BRACKETED_BRANCH =
            java.util.regex.Pattern.compile("[\\s\u3000]*[(（][^()（）]*[店점][)）]$");
    private static final java.util.regex.Pattern SPACES = java.util.regex.Pattern.compile("[\\s\u3000]+");

    /** 대안은 날짜 순서대로 만들어 뒷날에 본일정으로 들어간 장소가 앞날 대안에 남는다. */
    static List<StepData> pruneUsedAndRenumber(List<StepData> steps) {
        Set<String> usedPlaceKeys = steps.stream()
                .map(StepData::place)
                .filter(Objects::nonNull)
                .map(p -> placeKey(p.name(), p.address()))
                .collect(Collectors.toSet());
        int order = 1;
        List<StepData> renumbered = new ArrayList<>(steps.size());
        for (StepData step : steps) {
            renumbered.add(withoutUsedAlternatives(step, usedPlaceKeys).withStepOrder(order++));
        }
        return renumbered;
    }

    static StepData withoutUsedAlternatives(StepData s, Set<String> usedPlaceKeys) {
        if (s.alternatives() == null || s.alternatives().isEmpty()) return s;
        List<AlternativeData> filtered = s.alternatives().stream()
                .filter(a -> !usedPlaceKeys.contains(placeKey(a.name(), a.address())))
                .collect(Collectors.toList());
        if (filtered.size() == s.alternatives().size()) return s;
        return new StepData(s.stepOrder(), s.dayNumber(), s.startTime(), s.endTime(),
                s.place(), filtered, s.transportationMode(),
                s.transportationDuration(), s.transportationDistance(),
                s.transportationCost(), s.notes(), s.estimatedCost());
    }

    /**
     * 메인 스텝의 대안 3개를 만든다. "같은 종류·주변·중복 없이" 원칙:
     * <ul>
     *   <li><b>같은 종류</b>: 같은 역할 필수 + 세부 카테고리/tags 겹침 가점(버거집↔맥주바 방지).</li>
     *   <li><b>주변</b>: 메인 좌표(없으면 그날 centroid) 기준 이동수단별 반경 내만.</li>
     *   <li><b>중복 없이</b>: 이미 일정에 쓰인 장소 제외 + placeId/좌표 키로 대안끼리 dedup.</li>
     *   <li><b>그날 구성</b>: {@code variety}를 통과하지 못하는 후보(그날 다른 끼니와 같은 종류 등)는 뒤로 미룬다.
     *       빼면 해산물만 있는 지역에서 대안이 사라진다.</li>
     * </ul>
     * Sonnet이 큐레이션한 spare를 가점으로 우대하고, 부족하면 미사용 후보에서 채운다.
     */
    static List<AlternativeData> buildAlternatives(PlaceCandidate main, double[] dayCentroid,
                                                   Deque<Integer> spareIndices, Set<Integer> usedIndices,
                                                   List<PlaceCandidate> allCandidates, String transportPref,
                                                   Set<String> dayAltKeys,
                                                   java.util.function.Predicate<PlaceCandidate> variety,
                                                   TripView trip) {
        double[] anchor = coordsOf(main) != null ? coordsOf(main) : dayCentroid;
        double radiusKm = altRadiusKm(transportPref);
        Set<Integer> spareSet = new HashSet<>(spareIndices);

        // 일정에 이미 쓰인 장소는 spare에 중복 기재돼 있어도 대안에서 빼야 같은 곳을 두 번 방문하지 않는다.
        List<PlaceCandidate> base = allCandidates.stream()
                .filter(c -> c.index() != main.index())
                .filter(c -> !usedIndices.contains(c.index()))
                // 같은 대분류로만 보면 점심 대안으로 빵집·바가 나와 같은 역할만 대안으로 둔다.
                .filter(c -> isSubstituteFor(main, c))
                .collect(Collectors.toList());

        // 호출마다 새로 만들면 스텝이 달라도 같은 3곳이 반복돼 중복 방지 집합을 day 단위로 공유한다.
        Set<String> seen = dayAltKeys != null ? dayAltKeys : new HashSet<>();
        seen.add(altKey(main));
        List<AlternativeData> result = new ArrayList<>();

        java.util.function.Predicate<PlaceCandidate> preferred = variety.and(c -> trip.prefers(main, c));
        // 연박하는 숙소는 밤마다 같은 대안이 나와야 해서 다른 날 대안과 겹쳐도 그대로 둔다.
        boolean lodging = "LODGING".equals(PlaceCategoryConstants.majorCategory(main.category()));
        // 3개 미만이면 반경×2까지만 완화하고, 그래도 못 채우면 대안 1~2개로 둔다. 무제한 폴백은 먼 식당을 대안으로 낸다.
        for (boolean repeatAllowed : List.of(false, true)) {
            int limit = repeatAllowed ? MIN_ALTERNATIVES : MAX_ALTERNATIVES;
            List<PlaceCandidate> pool = base.stream()
                    .filter(c -> repeatAllowed || lodging || !trip.shownElsewhere().contains(altKey(c)))
                    .toList();
            addAlternatives(result, seen, pool.stream().filter(c -> withinRadius(anchor, c, radiusKm)).toList(),
                    main, anchor, spareSet, preferred, limit);
            addAlternatives(result, seen, pool.stream().filter(c -> withinRadius(anchor, c, radiusKm * 2)).toList(),
                    main, anchor, spareSet, preferred, limit);
        }
        return result;
    }

    /** score 내림차순으로 dedup하며 {@code limit}개까지 result에 채운다. */
    private static void addAlternatives(List<AlternativeData> result, Set<String> seen, List<PlaceCandidate> pool,
                                        PlaceCandidate main, double[] anchor, Set<Integer> spareSet,
                                        java.util.function.Predicate<PlaceCandidate> variety, int limit) {
        if (result.size() >= limit) return;
        Set<Integer> demoted = pool.stream().filter(variety.negate()).map(PlaceCandidate::index)
                .collect(Collectors.toSet());
        Map<Integer, Double> scores = pool.stream().collect(Collectors.toMap(PlaceCandidate::index,
                c -> altScore(c, main, anchor, spareSet), (a, b) -> a));
        pool.stream()
                .sorted(Comparator.<PlaceCandidate, Boolean>comparing(c -> demoted.contains(c.index()))
                        .thenComparingDouble(c -> -scores.get(c.index())))
                .forEach(c -> {
                    if (result.size() >= limit || !seen.add(altKey(c))) return;
                    result.add(new AlternativeData(c.name(), c.address(), c.category(), c.region(),
                            c.country(), null, estimateCost(c), c.placeId(), c.nameKo()));
                });
    }

    /** 이동수단별 대안 허용 반경. walk는 좁게, car는 넓게. */
    static double altRadiusKm(String transportPref) {
        if ("walk".equals(transportPref)) return 3.0;
        if ("car".equals(transportPref)) return 30.0;
        return 15.0;
    }

    /** anchor 판단 불가면 보존, 후보 좌표 없으면 근접 판정 불가로 제외("주변만" 원칙). */
    private static boolean withinRadius(double[] anchor, PlaceCandidate c, double radiusKm) {
        if (anchor == null) return true;
        double[] cc = coordsOf(c);
        if (cc == null) return false;
        return GeoUtils.haversine(anchor, cc) <= radiusKm;
    }

    /** 대안 점수(높을수록 우선): spare 큐레이션 > 세부 카테고리/tags 유사 > rating > 근접. */
    private static double altScore(PlaceCandidate c, PlaceCandidate main, double[] anchor, Set<Integer> spareSet) {
        double score = 0;
        // spare 가점이 거리 항을 압도하면 스텝이 달라도 대안 순위가 같아진다. 30km 거리 차이에는 뒤집히는 크기로 둔다.
        if (spareSet.contains(c.index())) score += SPARE_CURATION_BONUS;
        score += subCategorySimilarity(c, main) * 20;
        // 평점 원값(0~5)을 쓰면 리뷰 2건의 5.0이 리뷰 1만건의 4.3을 이긴다. 인기도(0~1)에
        // 같은 영향력(×5)을 줘 "실제로 가는 곳"이 대안 위로 오게 한다.
        score += PlaceProminence.of(c) * 5;
        // 같은 체인점은 다른 선택지가 아니다.
        if (isSameChain(c.name(), main.name())) score -= SAME_CHAIN_PENALTY;
        double[] cc = coordsOf(c);
        if (anchor != null && cc != null) score -= GeoUtils.haversine(anchor, cc) * 0.5;
        return score;
    }

    /**
     * 두 상호가 같은 체인인지. 지점명을 떼고 남은 몸통이 한쪽을 포함하는지로 판정한다.
     * "고기굽는남자 서면점"과 "고기굽는남자"는 사용자에게 같은 가게다.
     */
    private static boolean isSameChain(String a, String b) {
        if (a == null || b == null) return false;
        String na = stripBranchSuffix(a);
        String nb = stripBranchSuffix(b);
        if (na.isEmpty() || nb.isEmpty()) return false;
        return na.equals(nb) || na.contains(nb) || nb.contains(na);
    }

    /** 지점 표기를 떼어 체인 몸통만 남긴다("흑본오겹 함덕점", "... 2호점", "星巴克(人民广场店)", "一蘭 渋谷店"). */
    static String stripBranchSuffix(String name) {
        // 일본 상호는 지점명 앞에 전각 공백을 쓰고 같은 체인도 가운뎃점을 넣었다 뺐다 한다.
        String n = BRACKETED_BRANCH.matcher(MIDDLE_DOTS.matcher(name.strip()).replaceAll("")).replaceAll("");
        String[] tokens = SPACES.split(n);
        if (tokens.length > 1 && BRANCH_TOKEN.matcher(tokens[tokens.length - 1]).matches()) {
            return String.join("", java.util.Arrays.copyOf(tokens, tokens.length - 1));
        }
        // 공백 없이 붙은 "흑본오겹함덕점"은 몸통 경계를 알 수 없어 그대로 둔다. 몸통만 있는 쪽과는 포함 관계로 맞는다.
        return String.join("", tokens);
    }

    private static double subCategorySimilarity(PlaceCandidate a, PlaceCandidate b) {
        double s = 0;
        // 총칭 리프끼리는 전부 "같은 종류"로 보여 해변의 대안으로 오름이 나오므로 세부 유형으로 본다
        String la = subTypeOf(a);
        String lb = subTypeOf(b);
        if (la != null && !la.isBlank() && la.equalsIgnoreCase(lb)) s += 1.0;
        if (a.tags() != null && b.tags() != null) {
            Set<String> shared = new HashSet<>(a.tags());
            shared.retainAll(new HashSet<>(b.tags()));
            s += Math.min(2, shared.size()) * 0.5;
        }
        return s;
    }

    /** 확정된 스텝의 대안 키. 장소 id가 없으면 후보 쪽 키와 맞출 수 없어 null이다. */
    static String altKey(AlternativeData a) {
        return a.placeId() == null ? null : "id:" + a.placeId();
    }

    /** 대안 dedup 키: placeId 우선, 없으면 유효좌표(4자리), 없으면 정규화 이름. */
    static String altKey(PlaceCandidate c) {
        if (c.placeId() != null) return "id:" + c.placeId();
        double[] cc = coordsOf(c);
        if (cc != null) return "geo:" + Math.round(cc[0] * 10000) + "|" + Math.round(cc[1] * 10000);
        return "name:" + (c.name() == null ? "" : c.name().toLowerCase().replaceAll("\\s+", ""));
    }
}
