package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.global.util.GeoUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.coordsOf;

/**
 * 구간 상한({@link ItineraryRules.Rule#LEG_OVER_CAP})을 넘긴 구간을 후보 풀로 나눈다. 한쪽 끝을 일정에 쓰지 않은 같은 역할 후보로
 * 바꾸면 앞뒤 구간이 모두 상한 안에 드는 구간은 고르기에 달린 것이고, 그런 후보가 없는 구간은 풀이 좁아서 생긴 것이다.
 */
public final class OverCapLegs {

    private OverCapLegs() {
    }

    /**
     * @param avoidable   바꿔 넣을 후보가 풀에 있던 구간 수. 영업시간과 끼니 종류는 보지 않아 실제보다 많게 센다
     * @param poolLimited 그런 후보가 없던 구간 수
     * @param requested   두 수 가운데 한쪽 끝이 요청 의도 장소인 구간 수
     */
    public record Count(int avoidable, int poolLimited, int requested) {}

    /** @param requested 요청 의도 장소의 후보 번호 */
    public static Count count(List<StepData> steps, List<PlaceCandidate> candidates, String transportPref,
                              String pace, Set<Integer> requested) {
        double cap = ScheduleTuning.distanceLimits(transportPref, pace)[0];
        CandidateLookup lookup = new CandidateLookup(candidates);
        Set<Integer> used = steps.stream().map(lookup::of).filter(Objects::nonNull).map(PlaceCandidate::index)
                .collect(Collectors.toSet());
        // 선택 단계가 고를 수 있는 후보로 본다. 보충 기준(주소 규칙)으로 보면 고를 수 있던 식당을 없는 것으로 센다.
        List<PlaceCandidate> unused = candidates.stream()
                .filter(c -> !used.contains(c.index()) && coordsOf(c) != null && CandidateTraits.isSelectable(c))
                .toList();
        Map<Integer, List<StepData>> byDay = steps.stream().sorted(Comparator.comparingInt(StepData::stepOrder))
                .collect(Collectors.groupingBy(StepData::dayNumber, TreeMap::new, Collectors.toList()));
        List<Integer> dayNumbers = new ArrayList<>(byDay.keySet());

        int avoidable = 0;
        int poolLimited = 0;
        int touchingRequest = 0;
        StepData previousDayEnd = null;
        for (int d = 0; d < dayNumbers.size(); d++) {
            List<StepData> daySteps = byDay.get(dayNumbers.get(d));
            for (int i = 0; i < daySteps.size(); i++) {
                StepData step = daySteps.get(i);
                if (step.transportationDistance() == null || InvariantChecker.isOutsideDayBudget(daySteps, i,
                        d == 0, d == dayNumbers.size() - 1)) {
                    continue;
                }
                if (step.transportationDistance().doubleValue() / GeoUtils.ROAD_DISTANCE_FACTOR <= cap) continue;
                StepData from = i > 0 ? daySteps.get(i - 1) : previousDayEnd;
                StepData beforeFrom = i > 1 ? daySteps.get(i - 2) : i == 1 ? previousDayEnd : null;
                StepData afterTo = i + 1 < daySteps.size() ? daySteps.get(i + 1) : null;
                boolean swappable = hasSwap(lookup.of(step), unused, cap, lookup, from, afterTo)
                        || (i > 0 && hasSwap(lookup.of(from), unused, cap, lookup, beforeFrom, step));
                if (swappable) avoidable++;
                else poolLimited++;
                if (isRequested(lookup.of(step), requested) || isRequested(from == null ? null : lookup.of(from),
                        requested)) {
                    touchingRequest++;
                }
            }
            previousDayEnd = daySteps.get(daySteps.size() - 1);
        }
        return new Count(avoidable, poolLimited, touchingRequest);
    }

    private static boolean isRequested(PlaceCandidate c, Set<Integer> requested) {
        return c != null && requested.contains(c.index());
    }

    // 바꿔 넣을 후보는 앞뒤 이웃 모두에서 상한 안이어야 한다. 한쪽만 보면 반대쪽 구간이 새로 상한을 넘는다.
    private static boolean hasSwap(PlaceCandidate end, List<PlaceCandidate> unused, double cap,
                                   CandidateLookup lookup, StepData... neighbors) {
        if (end == null || !CandidatePolicy.roleOf(end).isFillable()) return false;
        List<double[]> around = Arrays.stream(neighbors).filter(Objects::nonNull).map(lookup::of)
                .filter(Objects::nonNull).map(CandidateGeo::coordsOf).filter(Objects::nonNull).toList();
        if (around.isEmpty()) return false;
        CandidatePolicy.Role role = CandidatePolicy.roleOf(end);
        return unused.stream()
                .filter(c -> sameUse(CandidatePolicy.roleOf(c), role))
                .anyMatch(c -> around.stream().allMatch(n -> GeoUtils.haversine(n, coordsOf(c)) <= cap));
    }

    private static boolean sameUse(CandidatePolicy.Role a, CandidatePolicy.Role b) {
        return a == b || (a.isVisit() && b.isVisit());
    }
}
