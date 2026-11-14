package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.budgetCoords;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.coordsOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.detourVia;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.isSubstituteFor;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.nameOf;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.cafeStopProtected;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.coverageProtected;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.replaceInDay;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.byIndex;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.*;

/** 하루 이동거리 예산을 넘는 날의 트림과 더 가까운 후보로의 교체. */
@Slf4j
final class DistanceBudget {

    private DistanceBudget() {
    }

    /**
     * 절대 거리 상한 강제. 단일 구간이 legCap을 넘거나 일일 총주행이 dayCap을 넘으면 우회 기여가 가장 큰 장소를
     * 같은 대분류의 가까운 spare로 교체하고, 불가하면 제거한다(그날 유일한 식사는 제거하지 않음).
     * 상대 임계값(repairDistanceOutliers)은 흩어진 day일수록 허용치가 커져 무력해진다.
     */
    static List<Integer> enforceDistanceBudget(DayState day, List<Integer> ordered,
                                               List<PlaceCandidate> candidates, Deque<Integer> spare,
                                               Set<Integer> usedIndices, String transportPref,
                                               ScheduleConfig cfg) {
        double[] limits = distanceLimits(transportPref, cfg == null ? null : cfg.pace);
        double legCap = limits[0];
        double dayCap = limits[1];
        List<Integer> result = new ArrayList<>(ordered);

        // 사용자 필수 장소는 거리 예산 위반이어도 제거·교체하지 않는다. 초과분은 프론트 이동요약 배지가 그대로 보여 준다.
        Set<Integer> protectedIdx = ordered.stream()
                .filter(i -> {
                    PlaceCandidate c = byIndex(candidates, i);
                    return c != null && c.keepsPlace();
                })
                .collect(Collectors.toCollection(HashSet::new));
        // 카테고리 유일 대표도 trimSequenceOverBudget과 같은 기준으로 보호한다.
        protectedIdx.addAll(coverageProtected(day, candidates, transportPref, cfg));
        protectedIdx.addAll(cafeStopProtected(day, candidates, transportPref, cfg));

        for (int guard = 0; guard < 3 && result.size() >= 2; guard++) {
            int worstPos = worstDetourPosition(result, candidates, legCap, dayCap, protectedIdx);
            if (worstPos < 0) break;
            Integer idx = result.get(worstPos);
            // applyWalkContinuity가 붙인 전날 숙소는 placeIndices 밖 anchor라 제거하면 usedIndices/spare 정합성이 깨진다.
            if (!day.placeIndices.contains(idx)) break;
            PlaceCandidate cur = byIndex(candidates, idx);

            Integer swap = pickBudgetSwap(day, result, worstPos, candidates, spare, usedIndices);
            if (swap != null) {
                spare.remove(swap);
                spare.addLast(idx);
                usedIndices.remove(idx);
                usedIndices.add(swap);
                result.set(worstPos, swap);
                replaceInDay(day, idx, swap);
                log.info("거리 상한 초과 — 근처 후보로 교체: day={} {} → {}", day.dayNumber,
                        cur != null ? cur.name() : idx, byIndex(candidates, swap).name());
            } else if (isRemovableForBudget(idx, result, candidates)) {
                result.remove(worstPos);
                day.placeIndices.remove(idx);
                usedIndices.remove(idx);
                spare.addLast(idx);
                log.info("거리 상한 초과 — 제거: day={} {}", day.dayNumber, cur != null ? cur.name() : idx);
            } else {
                break;
            }
        }
        return result;
    }

    /**
     * 확정 시퀀스가 거리 예산(단일 구간/일일 총주행)을 넘으면 보호 대상이 아닌 활동 중 위반을 줄이는 것을 spare로 트림한다.
     * usedIndices에서는 빼지 않아야 같은 실행의 갭 필·대안이 멀어서 뺀 장소를 다시 끌어오지 않는다.
     *
     * @param pinnedMeals 아침·점심·저녁으로 확정된 인덱스. 교체하면 새 장소가 끼니 시각 고정을 잃어
     *                    최후 수단 교체에서도 건드리지 않는다.
     */
    static void trimSequenceOverBudget(DayState day, List<Integer> seq, List<PlaceCandidate> candidates,
                                       Deque<Integer> spareIndices, String transportPref,
                                       Set<Integer> protectedIdx, PlaceCandidate origin,
                                       ScheduleConfig cfg, Set<Integer> pinnedMeals, Set<Integer> usedIndices) {
        double[] limits = distanceLimits(transportPref, cfg == null ? null : cfg.pace);
        double legCap = limits[0];
        double dayCap = limits[1];
        double[] originCoord = budgetCoords(origin);

        for (int guard = 0; guard < 2 && seq.size() > 1; guard++) {
            // 하루 실주행 = (출발지 → 첫 스텝) + 스텝 간 이동.
            List<double[]> coords = new ArrayList<>(seq.size() + 1);
            int offset = 0;
            if (originCoord != null) {
                coords.add(originCoord);
                offset = 1;
            }
            // 해외 관문은 null 좌표로 두어 위치는 유지하고 그 구간만 합계에서 뺀다.
            for (Integer i : seq) {
                PlaceCandidate c = byIndex(candidates, i);
                boolean hub = i.equals(day.arrivalHubIndex) || i.equals(day.departureHubIndex);
                coords.add(hub ? budgetCoords(c) : coordsOf(c));
            }

            double total = 0;
            double worstLeg = 0;
            for (int i = 1; i < coords.size(); i++) {
                double[] a = coords.get(i - 1);
                double[] b = coords.get(i);
                if (a == null || b == null) continue;
                double d = GeoUtils.haversine(a, b);
                total += d;
                worstLeg = Math.max(worstLeg, d);
            }
            if (total <= dayCap && worstLeg <= legCap) break;

            boolean anyRemovable = seq.stream().anyMatch(i -> !protectedIdx.contains(i));
            // 위반 중인 지표를 실제로 줄이는 트림만 한다. 절감이 미미하거나 위반 구간과 무관한 장소를 빼면 일정만 빈다.
            int pos = violationReducingRemoval(coords, offset, seq, protectedIdx,
                    total, worstLeg, legCap, dayCap);
            if (anyRemovable && pos < 0) {
                // 교체 함수는 전부 보호 대상일 때의 최후 수단이라 여기서 부르면 위반을 못 줄이는 교체가 잦아진다.
                log.info("거리 예산 초과({}km/{}km, 최대구간 {}km)지만 트림해도 위반이 줄지 않는다: day={}",
                        String.format("%.0f", total), String.format("%.0f", dayCap),
                        String.format("%.0f", worstLeg), day.dayNumber);
                break;
            }
            if (pos < 0) {
                // 전부 보호 대상이면 제거 대신 가장 먼 것을 가까운 같은 종류로 교체해 필수 슬롯을 지키며 거리만 줄인다.
                if (replaceFarthestWithNearbySpare(day, seq, candidates, spareIndices, coords, offset, pinnedMeals,
                        usedIndices)) {
                    continue;
                }
                log.info("거리 예산 초과({}km/{}km)인데 트림·교체 모두 불가(전부 보호 대상): day={}",
                        String.format("%.0f", total), String.format("%.0f", dayCap), day.dayNumber);
                break;
            }

            Integer idx = seq.remove(pos);
            day.placeIndices.remove(idx);
            spareIndices.addLast(idx);
            PlaceCandidate c = byIndex(candidates, idx);
            log.info("확정 시퀀스 거리 예산 초과({}km/{}km) — 트림: day={} {}",
                    String.format("%.0f", total), String.format("%.0f", dayCap),
                    day.dayNumber, c != null ? c.name() : idx);
        }
    }

    /** 시퀀스에서 가장 유명한 방문지(언어판 {@link ScheduleTuning#ICONIC_PROTECT_SITELINKS} 이상). 없으면 null. */
    static Integer mostIconicInSequence(List<Integer> seq, List<PlaceCandidate> candidates) {
        Integer best = null;
        int bestLinks = ScheduleTuning.ICONIC_PROTECT_SITELINKS - 1;
        for (Integer idx : seq) {
            PlaceCandidate c = byIndex(candidates, idx);
            if (c == null || c.wikidataSitelinks() == null || !CandidatePolicy.roleOf(c).isVisit()) continue;
            if (c.wikidataSitelinks() > bestLinks) {
                bestLinks = c.wikidataSitelinks();
                best = idx;
            }
        }
        return best;
    }

    /**
     * 거리 예산 초과인데 트림 가능한 활동이 없을 때, 우회 기여가 가장 큰 스텝(허브·숙소 제외)을
     * 같은 대분류의 가까운 spare로 교체한다. 필수 슬롯(식사)을 없애지 않고 거리만 줄인다.
     *
     * @return 교체가 일어났으면 true
     */
    private static boolean replaceFarthestWithNearbySpare(DayState day, List<Integer> seq,
                                                          List<PlaceCandidate> candidates, Deque<Integer> spareIndices,
                                                          List<double[]> coords, int offset, Set<Integer> pinnedMeals,
                                                          Set<Integer> usedIndices) {
        int bestPos = -1;
        double bestGain = 0;
        for (int i = 0; i < seq.size(); i++) {
            Integer idx = seq.get(i);
            // 허브·숙소는 구조적으로 고정이다.
            String major = PlaceCategoryConstants.majorCategory(byIndex(candidates, idx) != null
                    ? byIndex(candidates, idx).category() : null);
            if ("TRANSIT_HUB".equals(major) || "LODGING".equals(major)) continue;
            PlaceCandidate c = byIndex(candidates, idx);
            if (c != null && c.keepsPlace()) continue; // 필수·의도 장소는 교체하지 않는다
            if (pinnedMeals.contains(idx)) continue;     // 끼니 고정을 잃으므로 교체하지 않는다
            double gain = removalGain(coords, i + offset);
            if (gain > bestGain) {
                bestGain = gain;
                bestPos = i;
            }
        }
        if (bestPos < 0) return false;

        Integer target = seq.get(bestPos);
        PlaceCandidate targetCand = byIndex(candidates, target);
        if (targetCand == null) return false;

        // 교체 후보: 같은 대분류 + 앞뒤 이웃 경유 거리가 현재의 절반 미만
        double[] prev = bestPos + offset - 1 >= 0 ? coords.get(bestPos + offset - 1) : null;
        double[] next = bestPos + offset + 1 < coords.size() ? coords.get(bestPos + offset + 1) : null;
        Integer replacement = pickCloserSameMajorSpare(day, seq, candidates, spareIndices, targetCand, prev, next);
        if (replacement == null) return false;

        seq.set(bestPos, replacement);
        replaceInDay(day, target, replacement);
        spareIndices.remove(replacement);
        spareIndices.addLast(target);
        // 다른 날의 빈 시간 채우기·대안이 같은 곳을 다시 고르지 않게 사용 표시를 옮긴다.
        usedIndices.add(replacement);
        log.info("거리 예산 초과 — 보호 대상 교체: day={} {} → {}", day.dayNumber,
                targetCand.name(), nameOf(candidates, replacement));
        return true;
    }

    /** 같은 대분류 spare 중 prev→후보→next 경유 거리가 현재의 절반 미만인 가장 가까운 후보. */
    private static Integer pickCloserSameMajorSpare(DayState day, List<Integer> seq, List<PlaceCandidate> candidates,
                                                    Deque<Integer> spareIndices, PlaceCandidate target,
                                                    double[] prev, double[] next) {
        double currentDetour = detourVia(prev, coordsOf(target), next);
        Predicate<PlaceCandidate> variety = VarietyLedger.admitsReplacing(day, target.index(), candidates);
        Set<Integer> inSeq = new HashSet<>(seq);
        return spareIndices.stream()
                .map(i -> byIndex(candidates, i))
                .filter(Objects::nonNull)
                .filter(c -> !inSeq.contains(c.index()))
                .filter(c -> isSubstituteFor(target, c))
                .filter(c -> coordsOf(c) != null)
                .filter(c -> detourVia(prev, coordsOf(c), next) < currentDetour * 0.5)
                .min(Comparator.<PlaceCandidate, Boolean>comparing(c -> !variety.test(c))
                        .thenComparingDouble(c -> detourVia(prev, coordsOf(c), next)))
                .map(PlaceCandidate::index)
                .orElse(null);
    }

    /**
     * 상한 위반이 없으면 -1, 있으면 제거 시 총거리 감소가 가장 큰 위치를 반환.
     * protectedIdx(사용자 필수 장소 등)는 제거 후보에서 제외한다.
     */
    private static int worstDetourPosition(List<Integer> order, List<PlaceCandidate> candidates,
                                           double legCap, double dayCap, Set<Integer> protectedIdx) {
        // 이 패스는 식사를 보호하지 않아 출발지 구간까지 재면 식당 제거 → 식사 보충 → 제거 churn이 생긴다.
        // 출발지 구간은 식사를 보호하는 trimSequenceOverBudget이 잰다.
        List<double[]> coords = order.stream()
                .map(i -> coordsOf(byIndex(candidates, i)))
                .collect(Collectors.toList());
        int offset = 0;
        double total = 0;
        double worstLeg = 0;
        for (int i = 1; i < coords.size(); i++) {
            double[] a = coords.get(i - 1);
            double[] b = coords.get(i);
            if (a == null || b == null) continue;
            double d = GeoUtils.haversine(a, b);
            total += d;
            worstLeg = Math.max(worstLeg, d);
        }
        if (total <= dayCap && worstLeg <= legCap) return -1;
        return violationReducingRemoval(coords, offset, order, protectedIdx, total, worstLeg, legCap, dayCap);
    }

    /**
     * 거리 예산 위반을 <b>실제로 줄이는</b> 제거 위치 중 절감이 가장 큰 것. 없으면 -1.
     *
     * <p>위반이 하루 총주행이면 {@value ScheduleTuning#MIN_TRIM_GAIN_KM}km 이상 줄여야 하고, 단일 구간이면 최대 구간이 실제로 짧아져야 한다.
     * 제거 이득(removalGain)만 보고 고르면 위반 구간과 무관한 장소가 빠져 그 구간은 남고 일정만 빈다.
     *
     * @param coords {@code offset}이 1이면 맨 앞이 출발지 좌표다(제거 대상 아님)
     */
    private static int violationReducingRemoval(List<double[]> coords, int offset, List<Integer> seq,
                                                Set<Integer> protectedIdx, double total, double worstLeg,
                                                double legCap, double dayCap) {
        boolean totalOver = total > dayCap;
        boolean legOver = worstLeg > legCap;
        int pos = -1;
        double bestGain = 0;
        for (int i = 0; i < seq.size(); i++) {
            if (protectedIdx.contains(seq.get(i))) continue;
            double gain = removalGain(coords, i + offset);
            boolean reducesTotal = totalOver && gain >= MIN_TRIM_GAIN_KM;
            boolean reducesLeg = legOver && maxLegWithout(coords, i + offset) < worstLeg - 1.0;
            if (!reducesTotal && !reducesLeg) continue;
            if (gain > bestGain) {
                bestGain = gain;
                pos = i;
            }
        }
        return pos;
    }

    /** {@code skip}번째 좌표를 뺀 경로의 최대 구간 거리. */
    private static double maxLegWithout(List<double[]> coords, int skip) {
        double worst = 0;
        double[] prev = null;
        for (int i = 0; i < coords.size(); i++) {
            if (i == skip) continue;
            double[] cur = coords.get(i);
            if (cur == null) continue;
            if (prev != null) worst = Math.max(worst, GeoUtils.haversine(prev, cur));
            prev = cur;
        }
        return worst;
    }

    /** i번째 장소를 경로에서 뺄 때 줄어드는 거리. */
    private static double removalGain(List<double[]> coords, int i) {
        double[] me = coords.get(i);
        if (me == null) return 0;
        double[] prev = i > 0 ? coords.get(i - 1) : null;
        double[] next = i < coords.size() - 1 ? coords.get(i + 1) : null;
        double gain = 0;
        if (prev != null) gain += GeoUtils.haversine(prev, me);
        if (next != null) gain += GeoUtils.haversine(me, next);
        if (prev != null && next != null) gain -= GeoUtils.haversine(prev, next);
        return gain;
    }

    /** 같은 대분류 spare 중 이웃 경유 거리가 현재의 절반 미만으로 줄어드는 가장 가까운 후보. */
    private static Integer pickBudgetSwap(DayState day, List<Integer> order, int pos, List<PlaceCandidate> candidates,
                                          Deque<Integer> spare, Set<Integer> usedIndices) {
        PlaceCandidate cur = byIndex(candidates, order.get(pos));
        if (cur == null) return null;
        double[] prev = pos > 0 ? coordsOf(byIndex(candidates, order.get(pos - 1))) : null;
        double[] next = pos < order.size() - 1 ? coordsOf(byIndex(candidates, order.get(pos + 1))) : null;
        if (prev == null && next == null) return null;
        double currentDetour = detourVia(prev, coordsOf(cur), next);
        Predicate<PlaceCandidate> variety = VarietyLedger.admitsReplacing(day, cur.index(), candidates);

        return spare.stream()
                .filter(i -> !usedIndices.contains(i))
                .map(i -> byIndex(candidates, i))
                .filter(Objects::nonNull)
                .filter(c -> isSubstituteFor(cur, c))
                .filter(c -> coordsOf(c) != null)
                .filter(c -> detourVia(prev, coordsOf(c), next) < currentDetour * 0.5)
                .min(Comparator.<PlaceCandidate, Boolean>comparing(c -> !variety.test(c))
                        .thenComparingDouble(c -> detourVia(prev, coordsOf(c), next)))
                .map(PlaceCandidate::index)
                .orElse(null);
    }

    /** 그날의 유일한 식사(DINING)는 거리 때문에 제거하지 않는다. */
    private static boolean isRemovableForBudget(Integer idx, List<Integer> order, List<PlaceCandidate> candidates) {
        PlaceCandidate c = byIndex(candidates, idx);
        if (c == null || !c.mealEligible()) return true;
        long dining = order.stream()
                .map(i -> byIndex(candidates, i))
                .filter(x -> x != null && x.mealEligible())
                .count();
        return dining > 1;
    }
}
