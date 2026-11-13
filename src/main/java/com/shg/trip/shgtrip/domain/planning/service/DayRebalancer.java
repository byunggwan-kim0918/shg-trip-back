package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.centroidOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.coordsOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.isDiningCand;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.isVisitableFill;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.nameOf;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.dayBudgets;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.resolveVisitMinutes;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.byIndex;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.*;

/** 날짜 배분 이후 날짜 간 재조정(pace 개수, 짝 유지, 거리 이탈, 클러스터 분리, 재배치, 일과 용량 이월). */
@Slf4j
final class DayRebalancer {

    private DayRebalancer() {
    }

    static void repairUntilStable(PlanState s) {
        double distanceMultiplier = TRANSPORT_DISTANCE_MULTIPLIER.getOrDefault(s.transportPref,
                TRANSPORT_DISTANCE_MULTIPLIER.get("any"));
        boolean changed = true;
        int iteration = 0;
        while (changed && iteration < MAX_FIXPOINT_ITERATIONS) {
            changed = false;
            changed |= repairPaceQuota(s.days, s.minPerDay, s.maxPerDay, s.spare, s.candidates);
            changed |= repairPairs(s.days, s.pairs, s.maxPerDay);
            changed |= repairClusterSplit(s.days, s.candidates, s.maxPerDay, distanceMultiplier, s.pairs);
            changed |= repairDistanceOutliers(s.days, s.candidates, s.maxPerDay, distanceMultiplier);
            // centroid가 가장 가까운 day로 전역 재배정한다. repairClusterSplit은 인접 day·2클러스터만 봐서 전 지역에 흩어진 일정을 못 푼다.
            changed |= rebalanceDaysByGeography(s.days, s.candidates, s.maxPerDay, s.pairs);
            iteration++;
        }
        if (iteration >= MAX_FIXPOINT_ITERATIONS && changed) {
            log.warn("Repair fixpoint 미수렴 (최대 {}회 반복 후에도 재위반 존재) — 허브/quota만 보장된 상태로 진행", MAX_FIXPOINT_ITERATIONS);
        }
        // 루프가 quota를 재위반한 직후에 끝날 수 있어 1순위 제약인 pace quota를 마지막에 한 번 더 강제한다.
        repairPaceQuota(s.days, s.minPerDay, s.maxPerDay, s.spare, s.candidates);
        // 마지막 반복의 거리이탈 수리가 pair를 깨면 복구할 다음 회차가 없어 pair를 한 번 더 합친다.
        // 그 이동이 quota를 다시 깰 수 있어 quota도 다시 맞춘다.
        if (repairPairs(s.days, s.pairs, s.maxPerDay)) {
            repairPaceQuota(s.days, s.minPerDay, s.maxPerDay, s.spare, s.candidates);
        }
    }

    /** pace 상한 초과 day는 트림하고 하한 미달 day는 spare에서 보충한다. pair 인접보다 우선한다. */
    private static boolean repairPaceQuota(List<DayState> days, int minPerDay, int maxPerDay,
                                           Deque<Integer> spare, List<PlaceCandidate> candidates) {
        boolean changed = false;
        for (DayState day : days) {
            while (day.placeIndices.size() > maxPerDay) {
                Integer worst = pickTrimCandidate(day.placeIndices, candidates);
                if (worst == null) break;
                day.placeIndices.remove(worst);
                spare.addLast(worst);
                changed = true;
            }
        }
        for (DayState day : days) {
            while (day.placeIndices.size() < minPerDay && !spare.isEmpty()) {
                Integer fill = pickBestFill(day, spare, candidates);
                if (fill == null) break;
                spare.remove(fill);
                day.placeIndices.add(fill);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 트림 대상을 고른다. day의 유일한 DINING과 keepsPlace 장소는 남긴다.
     * 전부 보호 대상이면 null이고, 호출부는 사용자 선택을 pace 상한보다 우선해 quota 초과를 허용한다.
     */
    private static Integer pickTrimCandidate(List<Integer> indices, List<PlaceCandidate> candidates) {
        long diningCount = indices.stream()
                .map(i -> byIndex(candidates, i))
                .filter(c -> c != null && "DINING".equals(PlaceCategoryConstants.majorCategory(c.category())))
                .count();

        return indices.stream()
                .map(i -> byIndex(candidates, i))
                .filter(Objects::nonNull)
                .filter(c -> !c.keepsPlace())
                .filter(c -> diningCount > 1 || !"DINING".equals(PlaceCategoryConstants.majorCategory(c.category())))
                // 평점이 아니라 인기도 최저를 빼야 리뷰 몇 건의 5.0이 명소보다 살아남지 않는다.
                // 리뷰 수가 전부 미상이면 인기도가 평평해 평점을 2차 기준으로 둔다.
                .min(Comparator.<PlaceCandidate>comparingDouble(PlaceProminence::of)
                        .thenComparing(c -> c.rating() != null ? c.rating() : BigDecimal.ZERO))
                .map(PlaceCandidate::index)
                .orElse(null);
    }

    /**
     * day 중심에 가까운 spare를 고른다. spare에는 대안용 숙소도 있어 isVisitableFill 없이 채우면 숙소가 관광 스텝이 된다.
     * 후보가 없으면 null이고 minPerDay 미달을 허용한다. 짧은 날이 엉뚱한 스텝보다 낫다.
     */
    private static Integer pickBestFill(DayState day, Deque<Integer> spare, List<PlaceCandidate> candidates) {
        double[] centroid = centroidOf(day.placeIndices, candidates);

        return spare.stream()
                .map(i -> byIndex(candidates, i))
                .filter(Objects::nonNull)
                .filter(CandidateTraits::isVisitableFill)
                .filter(c -> coordsOf(c) != null)
                .min(Comparator.comparingDouble(c -> centroid != null
                        ? GeoUtils.haversine(centroid, coordsOf(c)) : 0))
                .map(PlaceCandidate::index)
                .orElse(null);
    }

    /**
     * 하루 일과 용량(테마 반영 시간창)을 넘는 활동을 quota 여유가 있는 다음/이전 day로 이월한다.
     * 옮길 곳이 없으면 그대로 두고 scheduleDay가 저녁 상한에서 트림한다.
     */
    static void repairDayCapacity(List<DayState> days, List<PlaceCandidate> candidates,
                                  ScheduleConfig cfg, int maxPerDay) {
        for (int i = 0; i < days.size(); i++) {
            DayState day = days.get(i);
            List<Integer> activities = day.placeIndices.stream()
                    .filter(idx -> !isDiningCand(byIndex(candidates, idx)))
                    .collect(Collectors.toList());
            int excess = activities.size() - totalActivityCapacity(day, candidates, cfg);
            if (excess <= 0) continue;

            // 옮겨 간 날이 저녁 없는 출발일이면 야경이 오후가 되므로 필수·의도 장소는 이월하지 않는다.
            List<Integer> movable = activities.stream()
                    .filter(idx -> { PlaceCandidate c = byIndex(candidates, idx); return c == null || !c.keepsPlace(); })
                    .collect(Collectors.toList());
            List<Integer> toMove = new ArrayList<>(movable.subList(Math.max(0, movable.size() - excess), movable.size()));
            for (Integer idx : toMove) {
                DayState target = null;
                for (int j : new int[]{i + 1, i - 1}) {
                    if (j < 0 || j >= days.size()) continue;
                    if (days.get(j).placeIndices.size() < maxPerDay) { target = days.get(j); break; }
                }
                if (target != null) {
                    day.placeIndices.remove(idx);
                    target.placeIndices.add(idx);
                    log.info("일과 용량 초과 이월: day={} → day={}", day.dayNumber, target.dayNumber);
                }
            }
        }
    }

    /**
     * 그날 시간창에 들어갈 수 있는 활동 <b>개수</b> 추정. 분 예산을 그날 활동들의 실제 평균
     * 체류시간으로 나눠, 장시간 체류 장소가 많은 day를 과대평가하지 않는다.
     */
    private static int totalActivityCapacity(DayState day, List<PlaceCandidate> candidates, ScheduleConfig cfg) {
        cfg = cfg.forDay(day);
        long dining = day.placeIndices.stream().filter(idx -> isDiningCand(byIndex(candidates, idx))).count();
        int[] budgets = dayBudgets(day, cfg, (int) dining, ARRIVAL_HUB_MINUTES);
        int totalBudget = budgets[0] + budgets[1] + budgets[2];

        double avgNeed = day.placeIndices.stream()
                .map(idx -> byIndex(candidates, idx))
                .filter(Objects::nonNull)
                .filter(c -> !isDiningCand(c))
                .mapToInt(c -> resolveVisitMinutes(c) + INTRA_BUCKET_TRAVEL_MINUTES)
                .average()
                .orElse(ACTIVITY_SLOT_MINUTES);
        return Math.max(1, (int) (totalBudget / Math.max(1, avgNeed)));
    }

    /** must_pair_with 두 인덱스가 다른 day에 있으면 한쪽 day로 모은다(quota 여유 있는 쪽 우선, best-effort). */
    private static boolean repairPairs(List<DayState> days, List<List<Integer>> pairs, int maxPerDay) {
        boolean changed = false;
        for (List<Integer> pair : pairs) {
            if (pair == null || pair.size() != 2) continue;
            int a = pair.get(0);
            int b = pair.get(1);
            DayState dayA = findDayContaining(days, a);
            DayState dayB = findDayContaining(days, b);
            if (dayA == null || dayB == null || dayA == dayB) continue;

            if (dayA.placeIndices.size() < maxPerDay) {
                dayB.placeIndices.remove(Integer.valueOf(b));
                dayA.placeIndices.add(b);
                changed = true;
            } else if (dayB.placeIndices.size() < maxPerDay) {
                dayA.placeIndices.remove(Integer.valueOf(a));
                dayB.placeIndices.add(a);
                changed = true;
            } else {
                log.warn("pair [{}, {}] 통합 불가(양쪽 day quota 가득 참) — best-effort 미해결", a, b);
            }
        }
        return changed;
    }

    /** day 중심에서 과도히 먼 이상치 1개를 인접 day로 재배치(전체 재클러스터링 아님). */
    private static boolean repairDistanceOutliers(List<DayState> days, List<PlaceCandidate> candidates, int maxPerDay,
                                                  double distanceMultiplier) {
        boolean changed = false;
        for (int i = 0; i < days.size(); i++) {
            DayState day = days.get(i);
            if (day.placeIndices.size() < 3) continue;

            double[] centroid = centroidOf(day.placeIndices, candidates);
            if (centroid == null) continue;

            double avgDist = day.placeIndices.stream()
                    .map(idx -> coordsOf(byIndex(candidates, idx)))
                    .filter(Objects::nonNull)
                    .mapToDouble(coord -> GeoUtils.haversine(centroid, coord))
                    .average().orElse(0);
            if (avgDist <= 0) continue;

            Integer worstIdx = null;
            double worstDist = -1;
            for (Integer idx : day.placeIndices) {
                PlaceCandidate c = byIndex(candidates, idx);
                double[] coord = coordsOf(c);
                if (coord == null) continue;
                double dist = GeoUtils.haversine(centroid, coord);
                if (dist > worstDist) {
                    worstDist = dist;
                    worstIdx = idx;
                }
            }
            if (worstIdx == null || worstDist < avgDist * distanceMultiplier) continue;

            DayState better = null;
            double betterDist = worstDist;
            double[] worstCoord = coordsOf(byIndex(candidates, worstIdx));
            for (int j : new int[]{i - 1, i + 1}) {
                if (j < 0 || j >= days.size()) continue;
                DayState neighbor = days.get(j);
                if (neighbor.placeIndices.size() >= maxPerDay) continue;
                double[] nCentroid = centroidOf(neighbor.placeIndices, candidates);
                if (nCentroid == null || worstCoord == null) continue;
                double d = GeoUtils.haversine(nCentroid, worstCoord);
                if (d < betterDist) {
                    betterDist = d;
                    better = neighbor;
                }
            }
            if (better != null) {
                day.placeIndices.remove(worstIdx);
                better.placeIndices.add(worstIdx);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * day가 지리적으로 뚜렷한 2개 클러스터로 갈리면 소수 클러스터 전체를 centroid가 더 가까운 인접 day로 옮긴다.
     * repairDistanceOutliers는 단일 이상치만 잡아 반반으로 쪼개진 day를 놓친다. pair는 쪼개지 않고 허브·숙소는 대상이 아니다.
     */
    private static boolean repairClusterSplit(List<DayState> days, List<PlaceCandidate> candidates,
                                              int maxPerDay, double distanceMultiplier, List<List<Integer>> pairs) {
        boolean changed = false;
        for (int i = 0; i < days.size(); i++) {
            DayState day = days.get(i);
            List<Integer> coordIdx = day.placeIndices.stream()
                    .filter(idx -> coordsOf(byIndex(candidates, idx)) != null)
                    .collect(Collectors.toList());
            if (coordIdx.size() < 3) continue;

            int[] seeds = farthestPair(coordIdx, candidates);
            if (seeds == null) continue;
            double[] cs0 = coordsOf(byIndex(candidates, coordIdx.get(seeds[0])));
            double[] cs1 = coordsOf(byIndex(candidates, coordIdx.get(seeds[1])));

            List<Integer> clusterA = new ArrayList<>();
            List<Integer> clusterB = new ArrayList<>();
            for (Integer idx : coordIdx) {
                double[] c = coordsOf(byIndex(candidates, idx));
                if (GeoUtils.haversine(c, cs0) <= GeoUtils.haversine(c, cs1)) clusterA.add(idx);
                else clusterB.add(idx);
            }
            if (clusterA.isEmpty() || clusterB.isEmpty()) continue;

            double[] ca = centroidOf(clusterA, candidates);
            double[] cb = centroidOf(clusterB, candidates);
            if (ca == null || cb == null) continue;
            double inter = GeoUtils.haversine(ca, cb);
            double intra = Math.max(avgToCentroid(clusterA, ca, candidates),
                    avgToCentroid(clusterB, cb, candidates));
            if (inter < Math.max(MIN_CLUSTER_SPLIT_KM, intra * distanceMultiplier)) continue;

            boolean aMinor = clusterA.size() <= clusterB.size();
            List<Integer> minority = aMinor ? clusterA : clusterB;
            double[] minorityCentroid = aMinor ? ca : cb;
            double[] majorityCentroid = aMinor ? cb : ca;

            // pair가 minority/majority로 갈리면 옮기지 않는다.
            if (splitsPair(minority, day.placeIndices, pairs)) continue;

            DayState target = null;
            double best = Double.MAX_VALUE;
            for (int j : new int[]{i - 1, i + 1}) {
                if (j < 0 || j >= days.size()) continue;
                DayState nb = days.get(j);
                if (nb.placeIndices.size() + minority.size() > maxPerDay) continue;
                double[] nc = centroidOf(nb.placeIndices, candidates);
                if (nc == null) continue;
                double d = GeoUtils.haversine(nc, minorityCentroid);
                if (d < best) { best = d; target = nb; }
            }

            // minority가 같은 day의 majority보다 target에 더 가까울 때만 이동(개선 보장)
            if (target != null && best < GeoUtils.haversine(minorityCentroid, majorityCentroid)) {
                day.placeIndices.removeAll(minority);
                target.placeIndices.addAll(minority);
                changed = true;
                log.info("2-클러스터 day 분리: day={} 소수 클러스터 {}개를 인접일로 이동 (inter={}km)",
                        day.dayNumber, minority.size(), String.format("%.1f", inter));
            }
        }
        return changed;
    }

    /**
     * 전역 지리 재배치(K-means식 1-pass). centroid가 확연히 더 가까운 다른 day가 있으면 방문지를 옮긴다.
     * repairClusterSplit이 인접 day·2클러스터만 보는 한계를 보완한다.
     * <ul>
     *   <li>대상은 placeIndices(방문지)뿐이다. 허브·숙소는 별도 필드다.</li>
     *   <li>다른 day centroid가 {@link ScheduleTuning#REBALANCE_MIN_GAIN_KM} 이상 가깝고 목적 day가 maxPerDay 미만일 때만 옮긴다.
     *       pair 멤버는 옮기지 않는다.</li>
     *   <li>한 번에 장소당 최대 1회 이동하고, fixpoint 루프가 반복 호출하며 수렴한다.</li>
     * </ul>
     */
    private static boolean rebalanceDaysByGeography(List<DayState> days, List<PlaceCandidate> candidates,
                                                    int maxPerDay, List<List<Integer>> pairs) {
        if (days.size() < 2) return false;
        Set<Integer> pairedIndices = pairs.stream()
                .filter(p -> p != null && p.size() == 2)
                .flatMap(List::stream)
                .collect(Collectors.toSet());

        // 한 패스 안에서 기준이 흔들리지 않게 이동 전 centroid를 고정한다.
        List<double[]> centroids = new ArrayList<>();
        for (DayState d : days) centroids.add(centroidOf(d.placeIndices, candidates));

        boolean changed = false;
        for (int i = 0; i < days.size(); i++) {
            DayState day = days.get(i);
            double[] myCentroid = centroids.get(i);
            if (myCentroid == null) continue;

            for (Integer idx : new ArrayList<>(day.placeIndices)) {
                if (pairedIndices.contains(idx)) continue;
                PlaceCandidate cand = byIndex(candidates, idx);
                double[] coord = coordsOf(cand);
                if (coord == null) continue;
                double myDist = GeoUtils.haversine(coord, myCentroid);
                // 끼니가 모자라질 날에서 이미 다 찬 날로 식당을 옮기면 한쪽은 저녁이 없고 한쪽은 네 끼가 된다
                boolean mealNeededHere = cand != null && cand.mealEligible()
                        && mealCount(day, candidates) <= MEALS_PER_DAY;

                int bestDay = -1;
                double bestDist = myDist - REBALANCE_MIN_GAIN_KM; // 이만큼 가까워져야 이동
                for (int j = 0; j < days.size(); j++) {
                    if (j == i) continue;
                    if (days.get(j).placeIndices.size() >= maxPerDay) continue;
                    if (mealNeededHere && mealCount(days.get(j), candidates) >= MEALS_PER_DAY) continue;
                    double[] other = centroids.get(j);
                    if (other == null) continue;
                    double d = GeoUtils.haversine(coord, other);
                    if (d < bestDist) { bestDist = d; bestDay = j; }
                }
                if (bestDay >= 0) {
                    day.placeIndices.remove(idx);
                    days.get(bestDay).placeIndices.add(idx);
                    changed = true;
                    log.info("전역 지리 재배치: day={} → day={} (index={}, {}km 단축)",
                            day.dayNumber, days.get(bestDay).dayNumber, idx,
                            String.format("%.1f", myDist - bestDist));
                }
            }
        }

        // 목적 day가 꽉 차면 이동으로는 못 고쳐 양쪽 모두 centroid에 가까워지는 1:1 교환을 시도한다.
        changed |= swapAcrossDays(days, candidates, centroids, pairedIndices);
        return changed;
    }

    private static long mealCount(DayState day, List<PlaceCandidate> candidates) {
        return day.placeIndices.stream().map(i -> byIndex(candidates, i))
                .filter(c -> c != null && c.mealEligible()).count();
    }

    /**
     * 서로 다른 day의 방문지 하나씩을 맞바꾼다. 각자 상대 day centroid에 더 가까워지고 합산 이득이
     * {@link ScheduleTuning#SWAP_MIN_GAIN_KM} 이상일 때만이다. 개수 중립이라 꽉 찬 day에도 적용된다.
     * pair 멤버·사용자 필수 장소는 빼고, 한 패스에서 장소당 최대 1회 교환한다.
     */
    private static boolean swapAcrossDays(List<DayState> days, List<PlaceCandidate> candidates,
                                          List<double[]> centroids, Set<Integer> pairedIndices) {
        boolean changed = false;
        Set<Integer> swapped = new HashSet<>();

        for (int i = 0; i < days.size(); i++) {
            double[] ci = centroids.get(i);
            if (ci == null) continue;
            for (int j = i + 1; j < days.size(); j++) {
                double[] cj = centroids.get(j);
                if (cj == null) continue;

                Integer bestA = null, bestB = null;
                double bestGain = SWAP_MIN_GAIN_KM;

                for (Integer a : new ArrayList<>(days.get(i).placeIndices)) {
                    if (!isSwappable(a, candidates, pairedIndices, swapped)) continue;
                    double[] ca = coordsOf(byIndex(candidates, a));
                    if (ca == null) continue;
                    for (Integer b : new ArrayList<>(days.get(j).placeIndices)) {
                        if (!isSwappable(b, candidates, pairedIndices, swapped)) continue;
                        double[] cb = coordsOf(byIndex(candidates, b));
                        if (cb == null) continue;

                        double before = GeoUtils.haversine(ca, ci) + GeoUtils.haversine(cb, cj);
                        double after = GeoUtils.haversine(ca, cj) + GeoUtils.haversine(cb, ci);
                        double gain = before - after;
                        // 한쪽을 희생해 합만 맞추는 교환은 받지 않는다.
                        boolean bothImprove = GeoUtils.haversine(ca, cj) < GeoUtils.haversine(ca, ci)
                                && GeoUtils.haversine(cb, ci) < GeoUtils.haversine(cb, cj);
                        if (bothImprove && gain > bestGain) {
                            bestGain = gain;
                            bestA = a;
                            bestB = b;
                        }
                    }
                }

                if (bestA != null) {
                    days.get(i).placeIndices.remove(bestA);
                    days.get(j).placeIndices.remove(bestB);
                    days.get(i).placeIndices.add(bestB);
                    days.get(j).placeIndices.add(bestA);
                    swapped.add(bestA);
                    swapped.add(bestB);
                    changed = true;
                    log.info("day 간 스왑: day={} ↔ day={} ({} ↔ {}, {}km 단축)",
                            days.get(i).dayNumber, days.get(j).dayNumber,
                            nameOf(candidates, bestA), nameOf(candidates, bestB),
                            String.format("%.1f", bestGain));
                }
            }
        }
        return changed;
    }

    private static boolean isSwappable(Integer idx, List<PlaceCandidate> candidates,
                                       Set<Integer> pairedIndices, Set<Integer> swapped) {
        if (pairedIndices.contains(idx) || swapped.contains(idx)) return false;
        PlaceCandidate c = byIndex(candidates, idx);
        return c != null && !c.keepsPlace();
    }

    /** idxList 중 좌표상 가장 먼 두 점의 (리스트 내) 위치를 반환. 유효좌표 없으면 null. */
    private static int[] farthestPair(List<Integer> idxList, List<PlaceCandidate> candidates) {
        int n = idxList.size();
        double max = -1;
        int a = -1, b = -1;
        for (int i = 0; i < n; i++) {
            double[] ci = coordsOf(byIndex(candidates, idxList.get(i)));
            if (ci == null) continue;
            for (int j = i + 1; j < n; j++) {
                double[] cj = coordsOf(byIndex(candidates, idxList.get(j)));
                if (cj == null) continue;
                double d = GeoUtils.haversine(ci, cj);
                if (d > max) { max = d; a = i; b = j; }
            }
        }
        return a >= 0 ? new int[]{a, b} : null;
    }

    private static double avgToCentroid(List<Integer> idxList, double[] centroid, List<PlaceCandidate> candidates) {
        return idxList.stream()
                .map(idx -> coordsOf(byIndex(candidates, idx)))
                .filter(Objects::nonNull)
                .mapToDouble(c -> GeoUtils.haversine(centroid, c))
                .average().orElse(0);
    }

    /** minority를 옮기면 이 day 안에 함께 있던 pair가 갈라지는지. */
    private static boolean splitsPair(List<Integer> minority, List<Integer> dayPlaces, List<List<Integer>> pairs) {
        Set<Integer> minoritySet = new HashSet<>(minority);
        for (List<Integer> p : pairs) {
            if (p == null || p.size() != 2) continue;
            boolean bothInDay = dayPlaces.contains(p.get(0)) && dayPlaces.contains(p.get(1));
            if (bothInDay && (minoritySet.contains(p.get(0)) != minoritySet.contains(p.get(1)))) return true;
        }
        return false;
    }

    private static DayState findDayContaining(List<DayState> days, int index) {
        return days.stream().filter(d -> d.placeIndices.contains(index)).findFirst().orElse(null);
    }
}
