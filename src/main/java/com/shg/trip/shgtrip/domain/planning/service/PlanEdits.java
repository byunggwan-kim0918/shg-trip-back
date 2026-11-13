package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.accommodationCoords;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.budgetCoords;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.centroidOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.coordsOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.detourVia;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.isClosedOnDay;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.isOpenDuring;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.byIndex;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.*;

/** 수리 단계들이 같이 쓰는 날짜별 장소 목록 조회·편집과 보충 후보 선택. */
@Slf4j
final class PlanEdits {

    private PlanEdits() {
    }

    /**
     * 그 day에 넣어도 되는 후보 필터. 거리 예산을 보지 않으면 커버리지가 먼 장소를 넣고 다음 패스에 트림하는 왕복이 생긴다.
     */
    static java.util.function.Predicate<PlaceCandidate> insertableFilter(
            DayState day, List<PlaceCandidate> candidates, java.time.LocalDate startDate,
            String transportPref, InvariantMemo memo, ScheduleConfig cfg) {
        return insertableFilter(day, candidates, startDate, transportPref, memo, RelaxLevel.L1, cfg);
    }

    static java.util.function.Predicate<PlaceCandidate> insertableFilter(
            DayState day, List<PlaceCandidate> candidates, java.time.LocalDate startDate,
            String transportPref, InvariantMemo memo, RelaxLevel level, ScheduleConfig cfg) {
        return insertableFilter(day, candidates, startDate, transportPref, memo, level, null, cfg);
    }

    /**
     * @param slotStart 넣을 자리의 시작 시각(분). 주면 그 시각에 영업하는 후보만 통과한다.
     *                  null이면 요일 휴무만 본다.
     */
    static java.util.function.Predicate<PlaceCandidate> insertableFilter(
            DayState day, List<PlaceCandidate> candidates, java.time.LocalDate startDate,
            String transportPref, InvariantMemo memo, RelaxLevel level, Integer slotStart,
            ScheduleConfig cfg) {
        double[] centroid = centroidOf(day.placeIndices, candidates);
        double radius = AlternativeBuilder.altRadiusKm(transportPref) * (level == RelaxLevel.L2 ? 2 : 1);

        java.util.function.Predicate<PlaceCandidate> filter = openOnDayFilter(startDate, day.dayNumber)
                .and(c -> !memo.isRejected(day.dayNumber, c.index()));
        // 영업시간은 L3에서도 유지한다. 풀면 완화 끝에 닫힌 가게가 들어온다.
        if (slotStart != null) {
            filter = filter.and(c -> isOpenDuring(c, day.dayOfWeek, slotStart,
                    slotStart + DINING_VISIT_MINUTES));
        }
        if (level != RelaxLevel.L3) {
            filter = filter
                    .and(c -> centroid == null || coordsOf(c) == null
                            || GeoUtils.haversine(centroid, coordsOf(c)) <= radius)
                    .and(c -> !wouldExceedDayBudget(day, c, candidates, transportPref, cfg));
        }
        return filter;
    }

    /**
     * 후보를 넣었을 때 구간·일일 거리 예산을 넘기는지 추정한다. 실제 순서는 재스케줄이 정하므로 우회가 가장 작은 자리를 가정한 하한 추정이다.
     */
    static boolean wouldExceedDayBudget(DayState day, PlaceCandidate cand, List<PlaceCandidate> candidates,
                                        String transportPref, ScheduleConfig cfg) {
        double[] coord = coordsOf(cand);
        if (coord == null) return false;

        double[] limits = distanceLimits(transportPref, cfg == null ? null : cfg.pace);
        List<double[]> path = new ArrayList<>();
        // 국내 허브를 빼고 재면 마지막 날 공항 왕복이 빠져 예산 안으로 오판한다.
        double[] arrivalHub = day.arrivalHubIndex != null
                ? budgetCoords(byIndex(candidates, day.arrivalHubIndex)) : null;
        if (arrivalHub != null) path.add(arrivalHub);
        for (Integer idx : day.placeIndices) {
            double[] c = coordsOf(byIndex(candidates, idx));
            if (c != null) path.add(c);
        }
        double[] hotel = accommodationCoords(day, candidates);
        if (hotel != null) path.add(hotel);
        double[] departureHub = day.departureHubIndex != null
                ? budgetCoords(byIndex(candidates, day.departureHubIndex)) : null;
        if (departureHub != null) path.add(departureHub);
        if (path.isEmpty()) return false;

        double current = 0;
        for (int i = 1; i < path.size(); i++) current += GeoUtils.haversine(path.get(i - 1), path.get(i));

        double bestDetour = Double.MAX_VALUE;
        double bestNewLeg = 0;
        for (int i = 0; i <= path.size(); i++) {
            double[] prev = i > 0 ? path.get(i - 1) : null;
            double[] next = i < path.size() ? path.get(i) : null;
            double detour = detourVia(prev, coord, next);
            if (prev != null && next != null) detour -= GeoUtils.haversine(prev, next);
            double newLeg = Math.max(prev != null ? GeoUtils.haversine(prev, coord) : 0,
                    next != null ? GeoUtils.haversine(coord, next) : 0);
            if (detour < bestDetour) {
                bestDetour = detour;
                bestNewLeg = newLeg;
            }
        }
        if (bestDetour == Double.MAX_VALUE) return false;

        // 이미 상한을 넘은 day를 절대 총량으로 판정하면 어떤 후보도 통과하지 못해 하루가 빈다.
        // 넘은 상태에서는 이 삽입이 얼마나 더 늘리는지만 본다.
        double dayCap = Math.max(limits[1], current + OVER_BUDGET_INSERT_SLACK_KM);
        return current + bestDetour > dayCap || bestNewLeg > limits[0];
    }

    /** 허브·숙소·식사 슬롯은 미배정(null)일 수 있다. */
    static boolean isSameIndex(Integer slot, int idx) {
        return slot != null && slot == idx;
    }

    /** anchor에 가장 가까운, 조건을 만족하는 미사용 후보(spare 우선 → 전체 미사용). */
    static Integer pickNearbyCandidate(List<PlaceCandidate> candidates, Deque<Integer> spare,
                                       Set<Integer> usedIndices, double[] anchor,
                                       java.util.function.Predicate<PlaceCandidate> predicate) {
        Integer fromSpare = nearestMatching(spare.stream().map(i -> byIndex(candidates, i)).toList(),
                usedIndices, anchor, predicate);
        if (fromSpare != null) return fromSpare;
        // spare에 없으면 아직 어디에도 쓰이지 않은 후보까지 넓힌다(검색 결과를 끝까지 활용)
        return nearestMatching(candidates, usedIndices, anchor, predicate);
    }

    private static Integer nearestMatching(List<PlaceCandidate> pool, Set<Integer> usedIndices, double[] anchor,
                                           java.util.function.Predicate<PlaceCandidate> predicate) {
        return pool.stream()
                .filter(Objects::nonNull)
                .filter(c -> !usedIndices.contains(c.index()))
                .filter(predicate)
                .filter(c -> anchor == null || coordsOf(c) != null)
                .min(Comparator.comparingDouble(c -> anchor == null || coordsOf(c) == null
                        ? Double.MAX_VALUE : GeoUtils.haversine(anchor, coordsOf(c))))
                .map(PlaceCandidate::index)
                .orElse(null);
    }

    /** 그날 영업하는 장소만 통과시키는 필터. startDate가 없으면 판단 보류(전부 통과). */
    static java.util.function.Predicate<PlaceCandidate> openOnDayFilter(java.time.LocalDate startDate,
                                                                        int dayNumber) {
        if (startDate == null) return c -> true;
        java.time.DayOfWeek dow = startDate.plusDays(dayNumber - 1L).getDayOfWeek();
        return c -> !isClosedOnDay(c.openingHours(), dow);
    }

    static void addToDay(DayState day, Integer idx, Deque<Integer> spare, Set<Integer> usedIndices) {
        day.placeIndices.add(idx);
        spare.remove(idx);
        usedIndices.add(idx);
    }

    /**
     * 커버리지 유일 대표 중 day 중심에서 가까운 것만 보호한다. 무조건 보호하면 먼 장소 하나가 하루 동선을 망친다.
     *
     * <p>상한은 이동수단별 하루 예산 × {@value ScheduleTuning#COVERAGE_YIELD_FRACTION}이다. 고정 km로는 도보와 차량을 같은 자로 잴 수 없다.
     * 양보한 카테고리는 최종 커버리지 패스가 다시 시도하고, 실패하면 사용자 안내로 노출된다.
     */
    static Set<Integer> coverageProtected(DayState day, List<PlaceCandidate> candidates,
                                          String transportPref, ScheduleConfig cfg) {
        if (day.coverageCriticalIndices.isEmpty()) return Set.of();
        double[] limits = distanceLimits(transportPref, cfg == null ? null : cfg.pace);
        double maxFromCenter = limits[1] * COVERAGE_YIELD_FRACTION;
        double[] center = centroidOf(day.placeIndices, candidates);
        if (center == null) return day.coverageCriticalIndices;

        Set<Integer> keep = new HashSet<>();
        for (Integer idx : day.coverageCriticalIndices) {
            double[] cc = coordsOf(byIndex(candidates, idx));
            if (cc == null) continue;
            if (GeoUtils.haversine(center, cc) <= maxFromCenter) {
                keep.add(idx);
            } else {
                PlaceCandidate c = byIndex(candidates, idx);
                log.info("커버리지 보호 양보: {} 가 day 중심에서 {}km (상한 {}km) — 거리 트림에 맡긴다",
                        c != null ? c.name() : idx,
                        String.format("%.0f", GeoUtils.haversine(center, cc)),
                        String.format("%.0f", maxFromCenter));
            }
        }
        return keep;
    }

    /** 카페 스톱 보호. 커버리지 대표와 같은 기준(day 중심에서 하루 예산의 일정 비율 이내)만 보호한다. */
    static Set<Integer> cafeStopProtected(DayState day, List<PlaceCandidate> candidates,
                                          String transportPref, ScheduleConfig cfg) {
        if (cfg == null || !cfg.cafeStop || day.cafeStopIndex == null
                || !day.placeIndices.contains(day.cafeStopIndex)) {
            return Set.of();
        }
        double maxFromCenter = cafeStopRadiusKm(transportPref, cfg);
        double[] center = centroidOf(day.placeIndices, candidates);
        double[] cc = coordsOf(byIndex(candidates, day.cafeStopIndex));
        if (center == null || cc == null || GeoUtils.haversine(center, cc) > maxFromCenter) return Set.of();
        return Set.of(day.cafeStopIndex);
    }

    static double cafeStopRadiusKm(String transportPref, ScheduleConfig cfg) {
        return Math.min(CAFE_STOP_MAX_FROM_CENTER_KM,
                distanceLimits(transportPref, cfg.pace)[1] * COVERAGE_YIELD_FRACTION);
    }

    static Set<Integer> collectUsedIndices(List<DayState> days) {
        Set<Integer> used = new HashSet<>();
        for (DayState d : days) {
            if (d.arrivalHubIndex != null) used.add(d.arrivalHubIndex);
            used.addAll(d.placeIndices);
            if (d.accommodationIndex != null) used.add(d.accommodationIndex);
            if (d.departureHubIndex != null) used.add(d.departureHubIndex);
        }
        return used;
    }

    /** 아직 어느 day에도 쓰이지 않은 후보 인덱스. 긴 공백을 채울 때 spare 다음 순번으로 쓴다. */
    static List<Integer> unusedIndices(List<PlaceCandidate> candidates, Set<Integer> usedIndices) {
        return candidates.stream()
                .map(PlaceCandidate::index)
                .filter(i -> !usedIndices.contains(i))
                .collect(Collectors.toList());
    }

    static void replaceInDay(DayState day, Integer from, Integer to) {
        int i = day.placeIndices.indexOf(from);
        if (i >= 0) day.placeIndices.set(i, to);
        else day.placeIndices.add(to);
        // 표시도 같이 옮긴다. 안 옮기면 카페 스톱·커버리지 대표가 바뀌는 순간 오후 고정과 거리 트림
        // 보호가 조용히 사라진다.
        if (from != null && from.equals(day.cafeStopIndex)) day.cafeStopIndex = to;
        if (from != null && day.coverageCriticalIndices.contains(from)) {
            Set<Integer> moved = new HashSet<>(day.coverageCriticalIndices);
            moved.remove(from);
            moved.add(to);
            day.coverageCriticalIndices = moved;
        }
    }
}
