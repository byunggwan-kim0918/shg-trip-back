package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.TransportationHub;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.centroidOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.coordsOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.nameOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.namesOverlap;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.byIndex;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.*;

/** 좌표 순으로 자른 구간을 날짜에 다시 배분한다(가변 크기 1D DP 분할). */
@Slf4j
final class DayPartitioner {

    private DayPartitioner() {
    }

    /**
     * 선택된 방문지를 <b>좌표로 먼저</b> 날짜에 나눈다. Sonnet의 날짜 배분은 힌트로만 쓴다.
     *
     * <p>반응적 수리 함수들은 히스테리시스로 서로 밀고 당겨 넓게 걸친 날을 풀지 못한다.
     * 세부지역 라벨은 제주에서 사실상 2개뿐이라 프롬프트로 묶게 할 수도 없다.
     *
     * <p>알고리즘(결정론적, LLM 호출 없음):
     * <ol>
     *   <li>{@code pairs}를 union-find로 묶어 같이 움직이는 단위를 만든다</li>
     *   <li>위도·경도 축으로 각각 정렬해 날짜 수만큼 자르고, 조각 내부가 더 뭉친 축을 고른다</li>
     *   <li>균형 k-means로 조각을 다듬고 조각마다 식사 자리를 확보한다</li>
     *   <li>허브가 있으면 허브 기준 최근접 경로로, 없으면 모델의 날짜 순서대로 조각을 날짜에 배정한다</li>
     * </ol>
     *
     * <p>허브·숙소는 대상이 아니다. 좌표가 없는 방문지는 옮길 근거가 없어 원래 날짜에 둔다.
     */
    static void repartitionDaysByGeography(List<DayState> days, List<PlaceCandidate> candidates,
                                           List<List<Integer>> pairs, int maxPerDay, TransportationHub hub) {
        if (days.size() <= 1) return;

        List<Integer> movable = new ArrayList<>();
        Map<Integer, Integer> fixedDayOf = new LinkedHashMap<>();
        for (DayState day : days) {
            for (Integer idx : day.placeIndices) {
                if (coordsOf(byIndex(candidates, idx)) != null) movable.add(idx);
                else fixedDayOf.put(idx, day.dayNumber);
            }
        }
        if (movable.size() < days.size()) return;

        List<List<Integer>> units = groupByPairs(movable, pairs);

        // 퍼짐이 큰 축 하나만 쓰면 경도로 넓지만 남북으로 갈려야 하는 배치를 놓쳐 두 축을 다 재 본다.
        List<double[]> unitCentroids = units.stream().map(u -> centroidOf(u, candidates)).toList();
        List<List<Integer>> chunks = null;
        double bestSpread = Double.MAX_VALUE;
        int bestAxis = -1;
        for (int axis = 0; axis < 2; axis++) {
            final int a = axis;
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < units.size(); i++) order.add(i);
            order.sort(Comparator.comparingDouble(i -> unitCentroids.get(i)[a]));
            List<List<Integer>> candidateChunks =
                    splitIntoChunks(order, units, days.size(), maxPerDay, candidates);
            if (candidateChunks.size() != days.size()) continue;
            double spread = withinChunkSpread(candidateChunks, candidates);
            if (spread < bestSpread) {
                bestSpread = spread;
                chunks = candidateChunks;
                bestAxis = axis;
            }
        }
        if (chunks == null) return;

        // 1D 축 정렬만으로는 2D 묶음이 거칠어 균형 k-means로 조각을 다듬는다.
        chunks = refineChunksBalanced(chunks, units, candidates, days.size(), maxPerDay);
        // 빈 날을 뒤의 수리가 spare로 메우면 지리적 의도가 사라지고 같은 장소가 두 스텝으로 들어간다.
        if (chunks.stream().anyMatch(List::isEmpty)) {
            log.info("좌표 기반 날짜 분할 생략: 균등 분할 시 빈 날이 생김 ({}곳 / {}일)",
                    movable.size(), days.size());
            return;
        }

        // 지리로만 나누면 식당 없는 날이 생겨 식사 불변식이 관광지를 쫓아낸다. 그래서 분할 단계에서 식당도 나눈다.
        ensureMealPerChunk(chunks, candidates, maxPerDay);

        List<double[]> chunkCentroids = chunks.stream().map(c -> centroidOf(c, candidates)).toList();
        // 다른 도시에서 넘어와 시작하는 구간은 도착 허브가 없어 모델의 날짜 순서를 둔다.
        double[] hubAnchor = days.get(0).resumesAfterTransfer ? null : hubAnchorOf(candidates, hub);
        List<List<Integer>> before = days.stream()
                .map(d -> new ArrayList<>(d.placeIndices)).collect(Collectors.toList());

        // 허브가 있으면 첫날이 도착지 근처에서 시작하게 한다. 없으면 근거 없이 날짜를 뒤집지 않도록 모델의 순서를 보존한다.
        List<Integer> chunkOrder = hubAnchor != null
                ? orderChunks(chunkCentroids, hubAnchor)
                : orderChunksByOriginalDays(chunks, before);

        // 분할이 기존 배분보다 실제로 짧을 때만 기록한다. 지면 모델 배분을 둔 채 뒤의 반응적 수리에 맡긴다.
        double beforeKm = estimateTotalTourKm(before, candidates, hubAnchor);

        for (DayState day : days) day.placeIndices.clear();
        for (int i = 0; i < chunkOrder.size(); i++) {
            days.get(i).placeIndices.addAll(chunks.get(chunkOrder.get(i)));
        }
        fixedDayOf.forEach((idx, dayNumber) -> days.stream()
                .filter(d -> d.dayNumber == dayNumber).findFirst()
                .ifPresent(d -> d.placeIndices.add(idx)));

        double afterKm = estimateTotalTourKm(
                days.stream().map(d -> new ArrayList<>(d.placeIndices)).collect(Collectors.toList()),
                candidates, hubAnchor);

        if (afterKm >= beforeKm) {
            for (int i = 0; i < days.size(); i++) {
                days.get(i).placeIndices.clear();
                days.get(i).placeIndices.addAll(before.get(i));
            }
            log.info("좌표 기반 날짜 분할 기각: {}km → {}km로 줄지 않음 (기존 배분 유지)",
                    String.format("%.0f", beforeKm), String.format("%.0f", afterKm));
            return;
        }

        log.info("좌표 기반 날짜 분할: {}일 × {}곳, {}km → {}km (주축={}, 조각내부퍼짐={}km, 허브앵커={})",
                days.size(), movable.size(),
                String.format("%.0f", beforeKm), String.format("%.0f", afterKm),
                bestAxis == 1 ? "경도" : "위도",
                String.format("%.0f", bestSpread), hubAnchor != null ? "있음" : "없음");
    }

    /**
     * 조각을 원래 그 장소들이 있던 날에 배정한다. 겹치는 장소가 가장 많은 짝부터 탐욕 매칭한다.
     *
     * <p>허브가 없으면 날짜 순서를 정할 지리적 근거가 없고, 모델 순서에는 도착·출발 맥락이 들어 있어 그대로 둔다.
     *
     * @return {@code chunkOrder[i]} = i번째 날에 넣을 조각 번호
     */
    private static List<Integer> orderChunksByOriginalDays(List<List<Integer>> chunks,
                                                           List<List<Integer>> originalDays) {
        int n = chunks.size();
        Integer[] assigned = new Integer[n];
        boolean[] chunkUsed = new boolean[n];

        // 동점은 날짜·조각 번호 순으로 깨 결정론적으로 만든다.
        record Match(int overlap, int day, int chunk) {}
        List<Match> matches = new ArrayList<>();
        for (int d = 0; d < n; d++) {
            Set<Integer> dayPlaces = new HashSet<>(d < originalDays.size() ? originalDays.get(d) : List.of());
            for (int k = 0; k < n; k++) {
                int overlap = (int) chunks.get(k).stream().filter(dayPlaces::contains).count();
                matches.add(new Match(overlap, d, k));
            }
        }
        matches.sort(Comparator.comparingInt(Match::overlap).reversed()
                .thenComparingInt(Match::day).thenComparingInt(Match::chunk));

        for (Match m : matches) {
            if (assigned[m.day()] != null || chunkUsed[m.chunk()]) continue;
            assigned[m.day()] = m.chunk();
            chunkUsed[m.chunk()] = true;
        }
        // 겹침이 전혀 없어 비는 자리는 남은 조각을 번호 순으로 채운다.
        int next = 0;
        for (int d = 0; d < n; d++) {
            if (assigned[d] != null) continue;
            while (next < n && chunkUsed[next]) next++;
            assigned[d] = next < n ? next : 0;
            if (next < n) chunkUsed[next] = true;
        }
        return java.util.Arrays.asList(assigned);
    }

    /**
     * 날짜 배분의 총 주행거리 추정. day마다 허브(없으면 첫 장소)에서 최근접 순회한다.
     *
     * <p>두 배분을 <b>같은 방식으로</b> 재서 비교하는 용도라 정확할 필요는 없다. 숙소·허브 배정은 이 단계 뒤라 포함하지 않는다.
     */
    private static double estimateTotalTourKm(List<List<Integer>> dayPlaces,
                                              List<PlaceCandidate> candidates, double[] hubAnchor) {
        double total = 0;
        for (List<Integer> places : dayPlaces) {
            List<double[]> coords = places.stream()
                    .map(i -> coordsOf(byIndex(candidates, i)))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            if (coords.size() < 2) continue;

            double[] at = hubAnchor;
            if (at == null) {
                at = coords.remove(0);
            }
            while (!coords.isEmpty()) {
                int nearest = 0;
                double best = Double.MAX_VALUE;
                for (int i = 0; i < coords.size(); i++) {
                    double d = GeoUtils.haversine(at, coords.get(i));
                    if (d < best) {
                        best = d;
                        nearest = i;
                    }
                }
                total += best;
                at = coords.remove(nearest);
            }
        }
        return total;
    }

    /** {@code pairs}로 연결된 인덱스를 한 단위로 묶는다. union-find는 orderDay와 같은 방식. */
    private static List<List<Integer>> groupByPairs(List<Integer> indices, List<List<Integer>> pairs) {
        Set<Integer> indexSet = new HashSet<>(indices);
        Map<Integer, Integer> parent = new HashMap<>();
        for (Integer idx : indices) parent.put(idx, idx);
        for (List<Integer> pair : pairs) {
            if (pair == null || pair.size() != 2) continue;
            if (indexSet.contains(pair.get(0)) && indexSet.contains(pair.get(1))) {
                RouteOrdering.union(parent, pair.get(0), pair.get(1));
            }
        }
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (Integer idx : indices) {
            groups.computeIfAbsent(RouteOrdering.find(parent, idx), k -> new ArrayList<>()).add(idx);
        }
        return new ArrayList<>(groups.values());
    }

    /**
     * 균형 k-means로 조각을 다듬는다. 조각 크기는 하루 상한 안에서 두고 소속만 바꾼다.
     *
     * <p>1D 스윕은 두 지역이 축과 수직으로 갈릴 때 틀린 묶음을 만들어 스윕 결과는 초기값으로만 쓴다.
     * 용량 제한이 없으면 한 조각에 몰려 빈 날이 생기고, 뒤의 수리가 spare로 메우며 지리적 의도가 사라진다.
     *
     * <p>할당은 최선·차선 조각 거리 차가 큰 단위부터 한다. 선호가 약한 단위를 먼저 넣으면 강한 단위가 자리를 뺏긴다.
     */
    private static List<List<Integer>> refineChunksBalanced(List<List<Integer>> chunks,
                                                            List<List<Integer>> units,
                                                            List<PlaceCandidate> candidates, int dayCount,
                                                            int maxPerDay) {
        // 용량을 초기 조각 크기로 못박으면 균등 분할이 굳는다. 하루 상한만 제약으로 두고 개수는 뒤의 pace quota 수리가 맞춘다.
        int[] capacity = new int[dayCount];
        int totalPlaces = chunks.stream().mapToInt(List::size).sum();
        // 총량이 상한×일수를 넘으면 어느 조각은 넘을 수밖에 없고 초과분은 quota 수리가 spare로 돌린다.
        int cap = Math.max(maxPerDay, (int) Math.ceil((double) totalPlaces / dayCount));
        java.util.Arrays.fill(capacity, cap);
        List<List<Integer>> best = chunks;
        double bestScore = withinChunkSpread(chunks, candidates);

        for (int iteration = 0; iteration < MAX_FIXPOINT_ITERATIONS; iteration++) {
            List<double[]> centroids = best.stream().map(c -> centroidOf(c, candidates)).toList();
            if (centroids.stream().anyMatch(Objects::isNull)) break;

            List<int[]> ranked = new ArrayList<>();   // {unitIdx, bestChunk}
            Map<Integer, Double> regret = new HashMap<>();
            for (int u = 0; u < units.size(); u++) {
                double[] uc = centroidOf(units.get(u), candidates);
                if (uc == null) continue;
                int bestChunk = -1;
                double d1 = Double.MAX_VALUE, d2 = Double.MAX_VALUE;
                for (int k = 0; k < centroids.size(); k++) {
                    double d = GeoUtils.haversine(uc, centroids.get(k));
                    if (d < d1) { d2 = d1; d1 = d; bestChunk = k; }
                    else if (d < d2) { d2 = d; }
                }
                ranked.add(new int[]{u, bestChunk});
                regret.put(u, d2 - d1);
            }
            ranked.sort((a, b) -> Double.compare(regret.get(b[0]), regret.get(a[0])));

            List<List<Integer>> next = new ArrayList<>();
            int[] used = new int[dayCount];
            for (int k = 0; k < dayCount; k++) next.add(new ArrayList<>());
            for (int[] entry : ranked) {
                List<Integer> unit = units.get(entry[0]);
                double[] uc = centroidOf(unit, candidates);
                int target = -1;
                double bestDist = Double.MAX_VALUE;
                for (int k = 0; k < dayCount; k++) {
                    if (used[k] + unit.size() > capacity[k]) continue;
                    double d = GeoUtils.haversine(uc, centroids.get(k));
                    if (d < bestDist) { bestDist = d; target = k; }
                }
                // 들어갈 곳이 없으면 장소를 잃지 않게 가장 여유 있는 조각에 넣는다.
                if (target < 0) {
                    for (int k = 0; k < dayCount; k++) {
                        if (target < 0 || capacity[k] - used[k] > capacity[target] - used[target]) target = k;
                    }
                }
                next.get(target).addAll(unit);
                used[target] += unit.size();
            }

            if (next.stream().anyMatch(List::isEmpty)) break;
            double score = withinChunkSpread(next, candidates);
            if (score >= bestScore - 0.01) break;  // 개선이 멈추면 종료(진동 방지)
            bestScore = score;
            best = next;
        }
        return best;
    }

    /**
     * 분할 품질 지표. 각 조각 안 중심까지 평균 거리의 합(km)이며 작을수록 하루가 좁은 지역에 모인다.
     */
    private static double withinChunkSpread(List<List<Integer>> chunks, List<PlaceCandidate> candidates) {
        double total = 0;
        for (List<Integer> chunk : chunks) {
            double[] centroid = centroidOf(chunk, candidates);
            if (centroid == null) continue;
            double sum = 0;
            int n = 0;
            for (Integer idx : chunk) {
                double[] c = coordsOf(byIndex(candidates, idx));
                if (c == null) continue;
                sum += GeoUtils.haversine(centroid, c);
                n++;
            }
            if (n > 0) total += sum / n;
        }
        return total;
    }

    /**
     * 식당이 없는 조각에 다른 조각의 여분 식당을 넘겨 조각당 최소 1곳을 맞춘다.
     *
     * <p>식당 0개인 날은 관광지가 quota를 채운 상태라 뒤의 식사 불변식이 관광지를 식당으로 교체해 지리 분할을 되돌린다.
     * 넘겨줄 식당은 식당 2곳 이상인 조각에서 받는 조각 중심에 가장 가까운 것을 고른다.
     * {@link ScheduleTuning#MEAL_TRANSFER_MAX_KM}을 넘으면 하루 한가운데 왕복이 생겨 옮기지 않고 spare에 맡긴다.
     */
    private static void ensureMealPerChunk(List<List<Integer>> chunks, List<PlaceCandidate> candidates,
                                           int maxPerDay) {
        for (int i = 0; i < chunks.size(); i++) {
            List<Integer> target = chunks.get(i);
            if (target.isEmpty() || countMeals(target, candidates) > 0) continue;

            double[] targetCenter = centroidOf(target, candidates);
            int bestChunk = -1;
            Integer bestMeal = null;
            double bestDistance = Double.MAX_VALUE;

            for (int j = 0; j < chunks.size(); j++) {
                if (j == i) continue;
                List<Integer> donor = chunks.get(j);
                // 주는 쪽이 식당 0개가 되면 문제를 옮길 뿐이라 2곳 이상일 때만 내준다.
                if (countMeals(donor, candidates) < 2) continue;
                for (Integer idx : donor) {
                    PlaceCandidate c = byIndex(candidates, idx);
                    if (c == null || !c.mealEligible()) continue;
                    double[] cc = coordsOf(c);
                    if (cc == null || targetCenter == null) continue;
                    double d = GeoUtils.haversine(targetCenter, cc);
                    if (d > MEAL_TRANSFER_MAX_KM) continue;
                    if (d < bestDistance) {
                        bestDistance = d;
                        bestChunk = j;
                        bestMeal = idx;
                    }
                }
            }

            if (bestMeal == null) {
                log.info("좌표 기반 분할: 조각 {}에 {}km 안의 여분 식당이 없음 — 식사는 spare로 채운다",
                        i + 1, String.format("%.0f", MEAL_TRANSFER_MAX_KM));
                continue;
            }
            // 자리가 없으면 <b>교환</b>한다. 그냥 옮기면 받는 조각이 상한을 넘어 pace quota 수리가 한 곳을 일정에서 뺀다.
            Integer giveBack = null;
            if (target.size() >= maxPerDay) {
                double[] donorCenter = centroidOf(chunks.get(bestChunk), candidates);
                double nearest = Double.MAX_VALUE;
                for (Integer idx : target) {
                    PlaceCandidate c = byIndex(candidates, idx);
                    if (c == null || c.mealEligible()) continue;  // 식당을 되돌려주면 원점이다
                    double[] cc = coordsOf(c);
                    if (cc == null || donorCenter == null) continue;
                    double d = GeoUtils.haversine(donorCenter, cc);
                    if (d < nearest) {
                        nearest = d;
                        giveBack = idx;
                    }
                }
                if (giveBack == null) {
                    log.info("좌표 기반 분할: 조각 {}이 꽉 찼고 되돌려줄 장소도 없음 — 식사는 spare로 채운다",
                            i + 1);
                    continue;
                }
            }

            chunks.get(bestChunk).remove(bestMeal);
            target.add(bestMeal);
            if (giveBack != null) {
                target.remove(giveBack);
                chunks.get(bestChunk).add(giveBack);
            }
            PlaceCandidate moved = byIndex(candidates, bestMeal);
            log.info("좌표 기반 분할: 조각 {}에 식당 보강 — {} ({}km, 조각 {}에서{})",
                    i + 1, moved != null ? moved.name() : bestMeal,
                    String.format("%.0f", bestDistance), bestChunk + 1,
                    giveBack != null ? ", " + nameOf(candidates, giveBack) + " 와 교환" : "");
        }
    }

    private static long countMeals(List<Integer> indices, List<PlaceCandidate> candidates) {
        return indices.stream()
                .map(i -> byIndex(candidates, i))
                .filter(c -> c != null && c.mealEligible())
                .count();
    }

    /**
     * 축 정렬된 단위 열을 날짜 수만큼의 <b>연속 구간</b>으로 자른다. 구간 내부 퍼짐의 합이 최소인 1D 최적 분절(DP)이다.
     *
     * <p>균등 개수로 자르면 흩어진 장소들이 개수를 맞추려고 한 날에 묶여 그날이 원정이 된다. DP는 구간 크기를 지리가 정하게 둔다.
     * 후보 수십 개 × 날짜 며칠 규모라 O(n²k)는 무시할 수 있다.
     *
     * <p>하루 상한({@code maxPerDay}) 초과는 금지가 아니라 <b>벌점</b>이다. 금지로 두면 총량이 상한×일수를 넘는 입력에서
     * 분할이 실패한다. 초과분은 뒤의 pace quota 수리가 spare로 돌린다.
     */
    private static List<List<Integer>> splitIntoChunks(List<Integer> sortedUnitOrder,
                                                       List<List<Integer>> units, int dayCount,
                                                       int maxPerDay, List<PlaceCandidate> candidates) {
        int n = sortedUnitOrder.size();
        if (n < dayCount) {
            // 단위가 날짜보다 적으면 빈 조각이 생기고 호출부가 분할을 포기한다.
            List<List<Integer>> chunks = new ArrayList<>();
            for (Integer unitPos : sortedUnitOrder) chunks.add(new ArrayList<>(units.get(unitPos)));
            while (chunks.size() < dayCount) chunks.add(new ArrayList<>());
            return chunks;
        }

        // 구간 [i, j) 의 비용: 그 구간 장소들의 중심까지 평균거리 + 상한 초과 벌점.
        double[][] cost = new double[n + 1][n + 1];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j <= n; j++) {
                List<Integer> places = new ArrayList<>();
                for (int u = i; u < j; u++) places.addAll(units.get(sortedUnitOrder.get(u)));
                cost[i][j] = segmentCost(places, candidates, maxPerDay);
            }
        }

        // dp[k][j] = 앞의 j개 단위를 k개 구간으로 나눈 최소 비용
        double[][] dp = new double[dayCount + 1][n + 1];
        int[][] cut = new int[dayCount + 1][n + 1];
        for (double[] row : dp) java.util.Arrays.fill(row, Double.MAX_VALUE / 4);
        dp[0][0] = 0;
        for (int k = 1; k <= dayCount; k++) {
            for (int j = k; j <= n; j++) {          // 구간마다 단위 1개 이상
                for (int i = k - 1; i < j; i++) {
                    double v = dp[k - 1][i] + cost[i][j];
                    if (v < dp[k][j]) {
                        dp[k][j] = v;
                        cut[k][j] = i;
                    }
                }
            }
        }

        // 역추적
        List<List<Integer>> chunks = new ArrayList<>();
        int end = n;
        for (int k = dayCount; k >= 1; k--) {
            int start = cut[k][end];
            List<Integer> chunk = new ArrayList<>();
            for (int u = start; u < end; u++) chunk.addAll(units.get(sortedUnitOrder.get(u)));
            chunks.add(0, chunk);
            end = start;
        }
        return chunks;
    }

    /** 한 구간의 비용. 중심까지 평균거리(km) + 하루 상한 초과분당 가중 벌점. */
    private static double segmentCost(List<Integer> places, List<PlaceCandidate> candidates, int maxPerDay) {
        double[] centroid = centroidOf(places, candidates);
        double spread = 0;
        if (centroid != null) {
            double sum = 0;
            int counted = 0;
            for (Integer idx : places) {
                double[] c = coordsOf(byIndex(candidates, idx));
                if (c == null) continue;
                sum += GeoUtils.haversine(centroid, c);
                counted++;
            }
            if (counted > 0) spread = sum / counted;
        }
        int excess = Math.max(0, places.size() - maxPerDay);
        return spread + excess * CHUNK_OVERFLOW_PENALTY_KM;
    }

    /**
     * 후보 풀의 교통 허브(공항·역·터미널) 좌표. 첫날을 도착지 근처에서 시작하게 하는 앵커다.
     * enrich가 정한 도착 허브와 이름이 맞는 허브를 쓰고, 없거나 안 맞으면 후보 순서상 첫 허브다.
     * 허브가 없으면 null이고 조각 순서는 모델의 날짜 순서를 따른다.
     */
    private static double[] hubAnchorOf(List<PlaceCandidate> candidates, TransportationHub hub) {
        List<PlaceCandidate> hubs = candidates.stream()
                .filter(c -> "TRANSIT_HUB".equals(PlaceCategoryConstants.majorCategory(c.category())))
                .filter(c -> coordsOf(c) != null)
                .toList();
        String name = hub != null ? hub.arrivalHub() : null;
        return hubs.stream()
                .filter(c -> name != null && (namesOverlap(c.name(), name) || namesOverlap(c.nameKo(), name)))
                .findFirst()
                .or(() -> hubs.stream().findFirst())
                .map(CandidateGeo::coordsOf)
                .orElse(null);
    }

    /**
     * 조각(날짜) 순서를 정한다. 허브에서 가장 가까운 조각에서 출발해 최근접 이웃으로 순회하고 2-opt로 다듬는다.
     * 왕복 여행이라 마지막 조각도 허브 근처에서 끝나는 편이 좋다.
     */
    private static List<Integer> orderChunks(List<double[]> chunkCentroids, double[] hubAnchor) {
        if (chunkCentroids.size() <= 2) {
            List<Integer> trivial = new ArrayList<>();
            for (int i = 0; i < chunkCentroids.size(); i++) trivial.add(i);
            // 2개짜리도 허브에서 가까운 쪽이 1일차여야 도착 직후 장거리 이동이 생기지 않는다.
            if (hubAnchor != null && chunkCentroids.size() == 2
                    && chunkCentroids.get(0) != null && chunkCentroids.get(1) != null
                    && GeoUtils.haversine(hubAnchor, chunkCentroids.get(1))
                            < GeoUtils.haversine(hubAnchor, chunkCentroids.get(0))) {
                return List.of(1, 0);
            }
            return trivial;
        }
        int seed = RouteOrdering.nearestNodeTo(chunkCentroids, hubAnchor);
        List<Integer> order = RouteOrdering.nearestNeighborOrder(chunkCentroids, seed);
        // 서사 가중치(highlight/rest)는 day 내부 순서의 개념이라 조각 순서는 거리만으로 정한다.
        List<Intensity> flat = chunkCentroids.stream().map(c -> Intensity.NEUTRAL).toList();
        order = RouteOrdering.twoOpt(order, chunkCentroids, flat);
        return RouteOrdering.orientToAnchors(order, chunkCentroids, hubAnchor, hubAnchor);
    }

}
