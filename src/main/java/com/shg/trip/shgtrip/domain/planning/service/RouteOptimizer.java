package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.planning.dto.AlternativeData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 같은 날 장소들을 좌표 기반 Nearest Neighbor 알고리즘으로 재정렬.
 * AI가 생성한 일정의 동선 효율성을 후처리로 보정한다.
 */
@Slf4j
@Component
public class RouteOptimizer {

    /**
     * 같은 날 step들을 좌표 기반으로 재정렬한 새 리스트를 반환.
     * 시간 정보(startTime, endTime)와 stepOrder를 재정렬 순서에 맞게 재할당한다.
     *
     * @param steps      AI가 생성한 전체 step 리스트
     * @param placeCache 장소명+주소 → Place 엔티티 캐시 (좌표 포함)
     * @return 동선 최적화된 step 리스트
     */
    // 식사 시간대 pinning 기준 (HH:mm 파싱 후 분 단위 비교)
    private static final int LUNCH_START  = 11 * 60 + 30; // 11:30
    private static final int LUNCH_END    = 13 * 60 + 30; // 13:30
    private static final int DINNER_START = 17 * 60 + 30; // 17:30
    private static final int DINNER_END   = 19 * 60 + 30; // 19:30

    public List<StepData> optimize(List<StepData> steps, Map<String, Place> placeCache) {
        if (steps == null || steps.size() <= 1) return steps;

        // 날짜별로 그룹핑
        Map<Integer, List<StepData>> byDay = steps.stream()
                .collect(Collectors.groupingBy(StepData::dayNumber, TreeMap::new, Collectors.toList()));

        List<StepData> result = new ArrayList<>();
        int globalOrder = 1;

        for (Map.Entry<Integer, List<StepData>> entry : byDay.entrySet()) {
            // stepOrder 기준 정렬 보장
            List<StepData> daySteps = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(StepData::stepOrder))
                    .collect(Collectors.toList());

            if (daySteps.size() <= 2) {
                for (StepData s : daySteps) {
                    result.add(withStepOrder(s, globalOrder++));
                }
                continue;
            }

            // 식사 시간대 step은 pin — 재정렬 대상에서 제외
            // pinned: 원래 인덱스 → step
            Map<Integer, StepData> pinnedByIndex = new LinkedHashMap<>();
            List<StepData> movable = new ArrayList<>();

            for (int i = 0; i < daySteps.size(); i++) {
                StepData s = daySteps.get(i);
                if (isMealStep(s)) {
                    pinnedByIndex.put(i, s);
                } else {
                    movable.add(s);
                }
            }

            // movable이 2개 이하면 재정렬 의미 없음
            if (movable.size() <= 2) {
                for (StepData s : daySteps) {
                    result.add(withStepOrder(s, globalOrder++));
                }
                continue;
            }

            // movable만 Nearest Neighbor 재정렬
            List<StepData> reorderedMovable = reorderByNearestNeighbor(movable, placeCache);

            // pinned 자리를 유지하면서 movable 재삽입
            List<StepData> merged = new ArrayList<>(Collections.nCopies(daySteps.size(), null));
            for (Map.Entry<Integer, StepData> e : pinnedByIndex.entrySet()) {
                merged.set(e.getKey(), e.getValue());
            }
            int movableIdx = 0;
            for (int i = 0; i < merged.size(); i++) {
                if (merged.get(i) == null) {
                    merged.set(i, reorderedMovable.get(movableIdx++));
                }
            }

            // 시간 슬롯은 원래 순서 그대로 유지, 교통 정보는 재정렬된 경우 null 처리
            for (int i = 0; i < merged.size(); i++) {
                StepData original = daySteps.get(i);
                StepData placed  = merged.get(i);
                boolean samePlace = placed == original;
                boolean isFirst   = (i == 0);

                result.add(new StepData(
                        globalOrder++,
                        placed.dayNumber(),
                        original.startTime(),   // 시간 슬롯은 원래 위치 기준 유지
                        original.endTime(),
                        placed.place(),
                        placed.alternatives(),
                        // 재정렬됐거나 첫 step이면 교통 정보 null (이전 장소가 바뀌었으므로 무효)
                        (isFirst || !samePlace) ? null : placed.transportationMode(),
                        (isFirst || !samePlace) ? null : placed.transportationDuration(),
                        (isFirst || !samePlace) ? null : placed.transportationDistance(),
                        (isFirst || !samePlace) ? null : placed.transportationCost(),
                        placed.notes(),
                        placed.estimatedCost()
                ));
            }
        }

        log.info("Route optimization complete: {} steps processed", result.size());
        return fillMissingTransportation(result, placeCache);
    }

    /**
     * 재정렬 등으로 비워진(또는 Haiku가 애초에 비워둔) transportation 정보를
     * 좌표 기반 거리 추정으로 채운다. 같은 날 내에서 이전 step과의 거리를
     * Haversine으로 계산해 도보/차량 여부와 시간/비용을 추정한다.
     * 정밀하지 않지만 "정보가 텅 비어있는 것"보다는 명백히 개선.
     */
    private List<StepData> fillMissingTransportation(List<StepData> steps, Map<String, Place> placeCache) {
        List<StepData> result = new ArrayList<>(steps.size());
        StepData prevStep = null;

        for (StepData step : steps) {
            boolean isFirstOfDay = prevStep == null || prevStep.dayNumber() != step.dayNumber();

            if (!isFirstOfDay && step.transportationMode() == null) {
                StepData estimated = estimateTransportation(step, prevStep, placeCache);
                result.add(estimated);
                prevStep = estimated;
            } else {
                result.add(step);
                prevStep = step;
            }
        }
        return result;
    }

    private StepData estimateTransportation(StepData step, StepData prev, Map<String, Place> placeCache) {
        double[] prevCoord = resolveCoords(prev.place(), placeCache);
        double[] curCoord = resolveCoords(step.place(), placeCache);
        if (prevCoord == null || curCoord == null) return step;

        double distanceKm = GeoUtils.haversine(prevCoord, curCoord);
        String mode;
        int durationMin;
        BigDecimal cost;

        if (distanceKm < 1.0) {
            mode = "WALK";
            durationMin = Math.max(1, (int) Math.ceil(distanceKm * 15)); // 도보 약 4km/h
            cost = BigDecimal.ZERO;
        } else {
            mode = "CAR";
            durationMin = (int) Math.ceil(distanceKm / 40 * 60); // 시내 차량 약 40km/h
            cost = BigDecimal.valueOf(distanceKm * 2000).setScale(0, RoundingMode.HALF_UP);
        }

        log.debug("AutoFixer: 교통정보 추정 day={} place={} distance={}km mode={}",
                step.dayNumber(), step.place() != null ? step.place().name() : "?", distanceKm, mode);

        return new StepData(step.stepOrder(), step.dayNumber(), step.startTime(), step.endTime(),
                step.place(), step.alternatives(), mode, durationMin,
                BigDecimal.valueOf(distanceKm).setScale(2, RoundingMode.HALF_UP), cost,
                step.notes(), step.estimatedCost());
    }

    /**
     * 식당 카테고리이고 점심 또는 저녁 시간대에 해당하는 step인지 판별.
     * DB 카테고리는 Foursquare 계층 경로(예: "Dining and Drinking > Restaurant > ...")이므로
     * PlaceCategoryConstants.majorCategory()로 대분류 매칭한다 (구 "맛집" 문자열 비교는
     * 실제 DB 값과 전혀 일치하지 않아 식사 step이 전부 movable로 취급되는 버그였음).
     */
    private boolean isMealStep(StepData s) {
        if (s.place() == null) return false;
        if (!"DINING".equals(PlaceCategoryConstants.majorCategory(s.place().category()))) return false;
        int startMin = parseTimeToMinutes(s.startTime());
        if (startMin < 0) return false;
        return (startMin >= LUNCH_START && startMin <= LUNCH_END)
                || (startMin >= DINNER_START && startMin <= DINNER_END);
    }

    /** "HH:mm" → 분 단위 정수. 파싱 실패 시 -1 반환 */
    private int parseTimeToMinutes(String time) {
        if (time == null || time.length() < 5) return -1;
        try {
            int h = Integer.parseInt(time.substring(0, 2));
            int m = Integer.parseInt(time.substring(3, 5));
            return h * 60 + m;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Nearest Neighbor: 첫 장소에서 시작하여 가장 가까운 미방문 장소를 순서대로 선택.
     */
    private List<StepData> reorderByNearestNeighbor(List<StepData> daySteps, Map<String, Place> placeCache) {
        // 좌표 resolve
        Map<StepData, double[]> coords = new LinkedHashMap<>();
        List<StepData> noCoords = new ArrayList<>();

        for (StepData step : daySteps) {
            double[] latLng = resolveCoords(step.place(), placeCache);
            if (latLng != null) {
                coords.put(step, latLng);
            } else {
                noCoords.add(step);
            }
        }

        // 좌표 있는 장소가 2개 이하면 재정렬 의미 없음
        if (coords.size() <= 2) {
            return daySteps;
        }

        // 현재 순서 대비 재정렬 후 총 거리 비교
        List<StepData> coordSteps = new ArrayList<>(coords.keySet());
        double originalDist = totalDistance(coordSteps, coords);

        // Nearest Neighbor 실행
        List<StepData> optimized = new ArrayList<>();
        Set<StepData> visited = new HashSet<>();

        // 첫 장소는 원래 첫 번째 유지 (호텔 출발 등)
        StepData current = coordSteps.get(0);
        optimized.add(current);
        visited.add(current);

        while (visited.size() < coordSteps.size()) {
            double[] curCoord = coords.get(current);
            StepData nearest = null;
            double minDist = Double.MAX_VALUE;

            for (StepData candidate : coordSteps) {
                if (visited.contains(candidate)) continue;
                double dist = haversine(curCoord, coords.get(candidate));
                if (dist < minDist) {
                    minDist = dist;
                    nearest = candidate;
                }
            }

            if (nearest != null) {
                optimized.add(nearest);
                visited.add(nearest);
                current = nearest;
            }
        }

        double optimizedDist = totalDistance(optimized, coords);

        // 개선이 20% 미만이면 원래 순서 유지 — AI가 의도한 경험 흐름 보존
        if (originalDist > 0 && (originalDist - optimizedDist) / originalDist < 0.20) {
            log.debug("Route optimization skipped for day (improvement < 20%): original={}km, optimized={}km",
                    String.format("%.1f", originalDist), String.format("%.1f", optimizedDist));
            return daySteps;
        }

        log.info("Route optimized: {}km → {}km ({}% improvement)",
                String.format("%.1f", originalDist), String.format("%.1f", optimizedDist),
                String.format("%.0f", (originalDist - optimizedDist) / originalDist * 100));

        // 좌표 없는 장소는 끝에 추가
        optimized.addAll(noCoords);
        return optimized;
    }

    private double[] resolveCoords(PlaceData pd, Map<String, Place> placeCache) {
        if (pd == null) return null;
        String key = placeKey(pd);
        Place place = placeCache.get(key);
        if (place == null || place.getLatitude() == null || place.getLongitude() == null) return null;
        // fallback Place(Google API 실패)는 좌표 0,0 — 유효하지 않으므로 제외
        if (place.getLatitude().compareTo(BigDecimal.ZERO) == 0
                && place.getLongitude().compareTo(BigDecimal.ZERO) == 0) return null;
        return new double[]{place.getLatitude().doubleValue(), place.getLongitude().doubleValue()};
    }

    private String placeKey(PlaceData pd) {
        return pd.name() + "|" + (pd.address() != null ? pd.address() : "");
    }

    private double totalDistance(List<StepData> steps, Map<StepData, double[]> coords) {
        double total = 0;
        for (int i = 1; i < steps.size(); i++) {
            double[] a = coords.get(steps.get(i - 1));
            double[] b = coords.get(steps.get(i));
            if (a != null && b != null) total += GeoUtils.haversine(a, b);
        }
        return total;
    }

    /** Haversine — GeoUtils 위임 */
    private double haversine(double[] a, double[] b) {
        return GeoUtils.haversine(a, b);
    }

    private StepData withStepOrder(StepData s, int order) {
        return new StepData(order, s.dayNumber(), s.startTime(), s.endTime(),
                s.place(), s.alternatives(), s.transportationMode(),
                s.transportationDuration(), s.transportationDistance(),
                s.transportationCost(), s.notes(), s.estimatedCost());
    }

    // ========================================================================================
    // Repair & Schedule: Sonnet의 day 구성(힌트)을 받아 하드제약 위반을 수리하고
    // day 내 순서·시간·교통·대안을 전부 결정론적으로 확정한다 (LLM 재호출 없음).
    // 산출된 StepData.notes는 비어있으며, 이후 Haiku가 비동기로 story를 채운다.
    // ========================================================================================

    private static final Map<String, int[]> PACE_RANGE = Map.of(
            "tight", new int[]{5, 7},
            "normal", new int[]{4, 5},
            "relaxed", new int[]{2, 3}
    );
    // day 내 장소들이 day 중심에서 이 배수 이상 떨어지면 이상치로 보고 인접 day로 재배치한다.
    // walk: 도보/버스로 다닐 만한 거리로 좁게 묶음. car: 차로 이동하므로 넉넉하게 허용.
    private static final Map<String, Double> TRANSPORT_DISTANCE_MULTIPLIER = Map.of(
            "walk", 1.5,
            "car", 3.0,
            "any", 2.0
    );
    private static final int MAX_FIXPOINT_ITERATIONS = 5;
    private static final int DAY_START_MINUTES = 9 * 60; // 09:00
    private static final int DEFAULT_VISIT_MINUTES = 90;
    private static final int DINING_VISIT_MINUTES = 70;

    public List<StepData> repairAndSchedule(SelectionOutput selection, List<PlaceCandidate> candidates, String pace) {
        return repairAndSchedule(selection, candidates, pace, "any", null);
    }

    /**
     * @param startDate 여행 시작일. 제공되면 각 day의 요일을 계산해 정기휴무(closed-day) 장소를
     *                  열려있는 spare로 교체한다. null이면 휴무 회피를 건너뛴다(하위 호환).
     */
    public List<StepData> repairAndSchedule(SelectionOutput selection, List<PlaceCandidate> candidates,
                                             String pace, java.time.LocalDate startDate) {
        return repairAndSchedule(selection, candidates, pace, "any", startDate);
    }

    /**
     * @param transportPref walk(도보/버스 우선) / car(자동차 우선) / any(상관없음). day별 동선의
     *                       지리적 허용 범위(거리 이상치 임계값)에 반영된다.
     */
    public List<StepData> repairAndSchedule(SelectionOutput selection, List<PlaceCandidate> candidates,
                                             String pace, String transportPref, java.time.LocalDate startDate) {
        int[] range = PACE_RANGE.getOrDefault(pace, PACE_RANGE.get("normal"));
        int minPerDay = range[0];
        int maxPerDay = range[1];
        double distanceMultiplier = TRANSPORT_DISTANCE_MULTIPLIER.getOrDefault(transportPref,
                TRANSPORT_DISTANCE_MULTIPLIER.get("any"));

        List<DayState> days = selection.days().stream().map(DayState::new).collect(Collectors.toList());
        List<List<Integer>> pairs = selection.pairs() != null ? selection.pairs() : List.of();
        Deque<Integer> spare = new ArrayDeque<>(selection.spareIndices() != null ? selection.spareIndices() : List.of());

        boolean changed = true;
        int iteration = 0;
        while (changed && iteration < MAX_FIXPOINT_ITERATIONS) {
            changed = false;
            changed |= repairPaceQuota(days, minPerDay, maxPerDay, spare, candidates);
            changed |= repairPairs(days, pairs, maxPerDay);
            changed |= repairDistanceOutliers(days, candidates, maxPerDay, distanceMultiplier);
            iteration++;
        }
        if (iteration >= MAX_FIXPOINT_ITERATIONS && changed) {
            log.warn("Repair fixpoint 미수렴 (최대 {}회 반복 후에도 재위반 존재) — 허브/quota만 보장된 상태로 진행", MAX_FIXPOINT_ITERATIONS);
        }
        // 루프의 마지막 단계(pair/거리이탈 수리)가 quota를 재위반한 채로 루프가 끝날 수 있음
        // (changed=false로 수렴했거나 MAX_FIXPOINT_ITERATIONS에 도달한 시점이 quota 재위반
        // 직후일 수 있음). pace quota는 우선순위 1위 하드 제약이므로 무조건 마지막에 한 번 더 강제.
        repairPaceQuota(days, minPerDay, maxPerDay, spare, candidates);
        // 루프는 quota→pairs→거리이탈 순으로 돌기 때문에, 루프가 끝나는 마지막 반복에서
        // 거리이탈 수리가 pair 멤버를 다른 날로 옮기면 그 뒤로 pair를 다시 합쳐줄 호출이
        // 없어 pair가 깨진 채로 남을 수 있음(중간 반복에서는 다음 회차의 repairPairs가
        // 자동으로 복구하지만, 마지막 반복은 그 다음 회차가 없음). 동일한 "마지막에 한 번 더"
        // 패턴을 pair에도 적용 — pair 이동이 quota를 다시 깰 수 있으므로 quota도 한 번 더.
        if (repairPairs(days, pairs, maxPerDay)) {
            repairPaceQuota(days, minPerDay, maxPerDay, spare, candidates);
        }

        repairHubs(days, candidates);
        repairAccommodationContinuity(days, candidates);
        // 정기휴무 회피: fixpoint 수렴 후 1회(swap이라 count 중립 → 진동 없음, best-effort).
        // pair 멤버는 휴무여도 건너뛴다(대체하면 pair 한쪽이 일정에서 완전히 사라짐 — 정기휴무
        // 회피보다 pair 무결성을 우선).
        repairClosedDayPlaces(days, candidates, spare, startDate, pairs);

        Set<Integer> highlights = selection.highlightIndices() != null
                ? new HashSet<>(selection.highlightIndices()) : Set.of();
        Set<Integer> rests = selection.restIndices() != null
                ? new HashSet<>(selection.restIndices()) : Set.of();

        List<StepData> steps = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            DayState day = days.get(i);
            // startAnchor: 그날을 시작하는 지점 — 도착 허브(첫날) 또는 전날 묵은 숙소(그 외)
            double[] startAnchor = day.arrivalHubIndex != null
                    ? coordsOf(byIndex(candidates, day.arrivalHubIndex))
                    : (i > 0 ? accommodationCoords(days.get(i - 1), candidates) : null);
            // endAnchor: 그날을 마치는 지점 — 그날 숙소 또는 출발 허브(마지막날)
            double[] endAnchor = day.accommodationIndex != null
                    ? coordsOf(byIndex(candidates, day.accommodationIndex))
                    : (day.departureHubIndex != null ? coordsOf(byIndex(candidates, day.departureHubIndex)) : null);

            List<Integer> ordered = orderDay(day, candidates, pairs, highlights, rests, startAnchor, endAnchor);
            steps.addAll(scheduleDay(day, ordered, candidates, spare));
        }

        int order = 1;
        List<StepData> renumbered = new ArrayList<>(steps.size());
        for (StepData s : steps) {
            renumbered.add(withStepOrder(s, order++));
        }

        log.info("RouteOptimizer.repairAndSchedule 완료: {}개 step, {}개 day", renumbered.size(), days.size());
        return renumbered;
    }

    /** 페이스 상한 초과 day는 트림, 하한 미달 day는 spare에서 보충. (pair 인접보다 우선) */
    private boolean repairPaceQuota(List<DayState> days, int minPerDay, int maxPerDay,
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

    /** DINING이 day의 유일한 식사면 보존, 그 외엔 rating 낮은 순으로 제거 대상 선정. */
    private Integer pickTrimCandidate(List<Integer> indices, List<PlaceCandidate> candidates) {
        long diningCount = indices.stream()
                .map(i -> byIndex(candidates, i))
                .filter(c -> c != null && "DINING".equals(PlaceCategoryConstants.majorCategory(c.category())))
                .count();

        return indices.stream()
                .map(i -> byIndex(candidates, i))
                .filter(Objects::nonNull)
                .filter(c -> diningCount > 1 || !"DINING".equals(PlaceCategoryConstants.majorCategory(c.category())))
                .min(Comparator.comparing(c -> c.rating() != null ? c.rating() : BigDecimal.ZERO))
                .map(PlaceCandidate::index)
                .orElse(null);
    }

    /** day 중심과 가까운 spare 후보를 우선 선택. */
    private Integer pickBestFill(DayState day, Deque<Integer> spare, List<PlaceCandidate> candidates) {
        double[] centroid = centroidOf(day.placeIndices, candidates);
        if (centroid == null) return spare.peekFirst();

        return spare.stream()
                .map(i -> byIndex(candidates, i))
                .filter(Objects::nonNull)
                .min(Comparator.comparingDouble(c -> haversine(centroid, coordsOf(c))))
                .map(PlaceCandidate::index)
                .orElse(null);
    }

    /** must_pair_with 두 인덱스가 다른 day에 있으면 한쪽 day로 모은다(quota 여유 있는 쪽 우선, best-effort). */
    private boolean repairPairs(List<DayState> days, List<List<Integer>> pairs, int maxPerDay) {
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
    private boolean repairDistanceOutliers(List<DayState> days, List<PlaceCandidate> candidates, int maxPerDay,
                                            double distanceMultiplier) {
        boolean changed = false;
        for (int i = 0; i < days.size(); i++) {
            DayState day = days.get(i);
            if (day.placeIndices.size() < 3) continue;

            double[] centroid = centroidOf(day.placeIndices, candidates);
            if (centroid == null) continue;

            double avgDist = day.placeIndices.stream()
                    .mapToDouble(idx -> haversine(centroid, coordsOf(byIndex(candidates, idx))))
                    .average().orElse(0);
            if (avgDist <= 0) continue;

            Integer worstIdx = null;
            double worstDist = -1;
            for (Integer idx : day.placeIndices) {
                PlaceCandidate c = byIndex(candidates, idx);
                double[] coord = coordsOf(c);
                if (coord == null) continue;
                double dist = haversine(centroid, coord);
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
                double d = haversine(nCentroid, worstCoord);
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

    /** TRANSIT_HUB 후보를 첫날 도착 / 마지막날 출발에 누락 시 보충. */
    private void repairHubs(List<DayState> days, List<PlaceCandidate> candidates) {
        if (days.isEmpty()) return;
        Set<Integer> used = collectUsedIndices(days);
        DayState first = days.get(0);
        DayState last = days.get(days.size() - 1);

        if (first.arrivalHubIndex == null) {
            Integer hub = findUnusedHub(candidates, used);
            if (hub != null) {
                first.arrivalHubIndex = hub;
                used.add(hub);
            }
        }
        if (last.departureHubIndex == null) {
            Integer hub = findUnusedHub(candidates, used);
            if (hub != null) {
                last.departureHubIndex = hub;
            }
        }
    }

    /** 인접 day가 같은 지역(dominant region)이면 동일 숙소를 유지(매일 호텔 변경 방지). */
    private void repairAccommodationContinuity(List<DayState> days, List<PlaceCandidate> candidates) {
        for (int i = 1; i < days.size(); i++) {
            DayState prev = days.get(i - 1);
            DayState curr = days.get(i);
            if (prev.accommodationIndex == null || curr.accommodationIndex == null) continue;
            if (prev.accommodationIndex.equals(curr.accommodationIndex)) continue;

            String prevRegion = dominantRegion(prev.placeIndices, candidates);
            String currRegion = dominantRegion(curr.placeIndices, candidates);
            if (prevRegion != null && prevRegion.equals(currRegion)) {
                curr.accommodationIndex = prev.accommodationIndex;
            }
        }
    }

    private double[] accommodationCoords(DayState day, List<PlaceCandidate> candidates) {
        return day.accommodationIndex != null ? coordsOf(byIndex(candidates, day.accommodationIndex)) : null;
    }

    private String dominantRegion(List<Integer> indices, List<PlaceCandidate> candidates) {
        return indices.stream()
                .map(i -> byIndex(candidates, i))
                .filter(c -> c != null && c.region() != null)
                .collect(Collectors.groupingBy(PlaceCandidate::region, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * 정기휴무(예: 매주 월요일 휴무) 장소를 그날 열려있는 같은 카테고리 spare로 교체한다.
     * **데이터 품질 존중**: openingHours가 없거나 해당 요일 정보를 파싱하지 못하면 "열림"으로
     * 간주하고 손대지 않는다(고신뢰 "휴무" 신호에만 작동 — "데이터없음=24시간" 오인식 회피).
     * swap이라 day의 장소 수가 변하지 않아 quota를 깨지 않으며 best-effort다(대체 없으면 로그만).
     * must_pair_with 멤버는 교체하지 않는다 — 대체하면 그 장소가 spare로 완전히 빠지면서
     * pair가 "같은 날에 함께"가 아니라 한쪽이 일정에서 통째로 사라지는 형태로 깨진다.
     */
    private void repairClosedDayPlaces(List<DayState> days, List<PlaceCandidate> candidates,
                                        Deque<Integer> spare, java.time.LocalDate startDate,
                                        List<List<Integer>> pairs) {
        if (startDate == null) return;
        Set<Integer> pairedIndices = pairs.stream()
                .filter(p -> p != null && p.size() == 2)
                .flatMap(List::stream)
                .collect(Collectors.toSet());

        for (DayState day : days) {
            java.time.DayOfWeek dow = startDate.plusDays((long) day.dayNumber - 1).getDayOfWeek();
            double[] centroid = centroidOf(day.placeIndices, candidates);

            for (int i = 0; i < day.placeIndices.size(); i++) {
                int idx = day.placeIndices.get(i);
                PlaceCandidate cand = byIndex(candidates, idx);
                if (cand == null || !isClosedOnDay(cand.openingHours(), dow)) continue;
                if (pairedIndices.contains(idx)) {
                    log.warn("정기휴무 장소이나 pair 멤버라 교체 보류: day={}({}) {}", day.dayNumber, dow, cand.name());
                    continue;
                }

                String category = PlaceCategoryConstants.majorCategory(cand.category());
                final double[] anchor = centroid != null ? centroid : coordsOf(cand);
                Integer replacement = spare.stream()
                        .map(si -> byIndex(candidates, si))
                        .filter(Objects::nonNull)
                        .filter(sc -> category.equals(PlaceCategoryConstants.majorCategory(sc.category())))
                        .filter(sc -> !isClosedOnDay(sc.openingHours(), dow))
                        .min(Comparator.comparingDouble(sc -> anchor != null && coordsOf(sc) != null
                                ? haversine(anchor, coordsOf(sc)) : Double.MAX_VALUE))
                        .map(PlaceCandidate::index)
                        .orElse(null);

                if (replacement != null) {
                    spare.remove(replacement);
                    day.placeIndices.set(i, replacement);
                    spare.addLast(idx);
                    log.info("정기휴무 회피: day={}({}) {} → 대체 index={}", day.dayNumber, dow, cand.name(), replacement);
                } else {
                    log.warn("정기휴무 장소이나 대체 spare 없음: day={}({}) {}", day.dayNumber, dow, cand.name());
                }
            }
        }
    }

    /**
     * Google Places weekdayDescriptions(", "로 join된 텍스트)에서 해당 요일이 "휴무"인지 판정.
     * 고신뢰 신호("휴무"/"closed")에만 true 반환. 데이터 없음/요일 미매칭은 false(열림 가정).
     */
    boolean isClosedOnDay(String openingHours, java.time.DayOfWeek dow) {
        if (openingHours == null || openingHours.isBlank()) return false;

        String koName = switch (dow) {
            case MONDAY -> "월요일"; case TUESDAY -> "화요일"; case WEDNESDAY -> "수요일";
            case THURSDAY -> "목요일"; case FRIDAY -> "금요일"; case SATURDAY -> "토요일";
            case SUNDAY -> "일요일";
        };
        String enName = dow.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH).toLowerCase();

        for (String segment : openingHours.split(",")) {
            String s = segment.trim();
            String lower = s.toLowerCase();
            boolean matchesDay = s.contains(koName) || lower.contains(enName);
            if (!matchesDay) continue;
            return s.contains("휴무") || lower.contains("closed");
        }
        return false;
    }

    private DayState findDayContaining(List<DayState> days, int index) {
        return days.stream().filter(d -> d.placeIndices.contains(index)).findFirst().orElse(null);
    }

    private Set<Integer> collectUsedIndices(List<DayState> days) {
        Set<Integer> used = new HashSet<>();
        for (DayState d : days) {
            if (d.arrivalHubIndex != null) used.add(d.arrivalHubIndex);
            used.addAll(d.placeIndices);
            if (d.accommodationIndex != null) used.add(d.accommodationIndex);
            if (d.departureHubIndex != null) used.add(d.departureHubIndex);
        }
        return used;
    }

    /**
     * TRANSIT_HUB 카테고리 중 평점이 가장 높은 후보를 고른다. Foursquare 데이터에는 공항 본체
     * 외에 "Immigration Check"/"Security"/"Gate" 같은 영어 하위 POI가 섞여 있는데, 이런
     * 행정/시설 서브 POI는 대개 리뷰가 거의 없어 rating이 null/낮음 — 그래서 평점 기준 정렬이
     * findFirst()(입력 순서 그대로 픽)보다 실제 공항 본체를 더 안정적으로 골라낸다.
     */
    private Integer findUnusedHub(List<PlaceCandidate> candidates, Set<Integer> used) {
        return candidates.stream()
                .filter(c -> "TRANSIT_HUB".equals(PlaceCategoryConstants.majorCategory(c.category())))
                .filter(c -> !used.contains(c.index()))
                .max(Comparator.comparing(c -> c.rating() != null ? c.rating() : BigDecimal.ZERO))
                .map(PlaceCandidate::index)
                .orElse(null);
    }

    // 서사 흐름 가중치: 거리 단위(km)와 같은 스케일의 "패널티"로 표현해 2-opt 비용함수에 더함.
    // 도심 스팟간 거리(보통 1~5km)보다 작게 잡아, 거리가 명확히 우세하면 거리가 이기고
    // 거리차가 0.5km 내외로 비슷한(타이) 경우에만 흐름이 결정하도록 함.
    private static final double HIGHLIGHT_FIRST_PENALTY_KM = 0.5;
    private static final double REST_AFTER_HIGHLIGHT_BONUS_KM = 0.3;

    private enum Intensity { HIGHLIGHT, REST, NEUTRAL }

    /**
     * day 내 순서를 NN+2-opt로 결정한다. pair는 union-find로 한 노드로 묶어 최적화 후 펼친다
     * (표준 2-opt가 개별 노드 edge-swap이라 pair adjacency를 직접 보장하지 못하므로 필요한 변환).
     * highlight/rest 태그는 거리 비용에 작은 가중치로 더해져, 거리가 비슷한 경우 "기승전결"에
     * 가까운 흐름(절정을 첫 스텝에 두지 않고, 절정 다음에 휴식)을 선호하게 만든다.
     *
     * @param startAnchor 그날을 시작하는 지점의 좌표(도착 허브 또는 전날 묵은 숙소). 첫 방문지가
     *                    여기서 가까운 곳이 되도록 NN 시드를 anchor 인근에서 시작한다.
     * @param endAnchor   그날을 마치는 지점의 좌표(그날 숙소 또는 출발 허브). 경로 전체를 뒤집어도
     *                    총 거리는 동일하므로(symmetric), 두 방향 중 endAnchor에 더 가깝게 끝나는
     *                    쪽을 채택한다.
     */
    private List<Integer> orderDay(DayState day, List<PlaceCandidate> candidates, List<List<Integer>> pairs,
                                    Set<Integer> highlights, Set<Integer> rests,
                                    double[] startAnchor, double[] endAnchor) {
        List<Integer> indices = new ArrayList<>(day.placeIndices);
        if (indices.size() <= 2) return indices;

        Set<Integer> indexSet = new HashSet<>(indices);
        Map<Integer, Integer> parent = new HashMap<>();
        for (Integer idx : indices) parent.put(idx, idx);
        for (List<Integer> pair : pairs) {
            if (pair == null || pair.size() != 2) continue;
            int a = pair.get(0);
            int b = pair.get(1);
            if (indexSet.contains(a) && indexSet.contains(b)) {
                union(parent, a, b);
            }
        }

        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (Integer idx : indices) {
            groups.computeIfAbsent(find(parent, idx), k -> new ArrayList<>()).add(idx);
        }
        List<List<Integer>> nodeGroups = new ArrayList<>(groups.values());
        List<double[]> nodeCoords = new ArrayList<>();
        List<Intensity> nodeIntensity = new ArrayList<>();
        for (List<Integer> g : nodeGroups) {
            nodeCoords.add(centroidOf(g, candidates));
            nodeIntensity.add(groupIntensity(g, highlights, rests));
        }

        int seedIndex = nearestNodeTo(nodeCoords, startAnchor);
        List<Integer> nodeOrder = nearestNeighborOrder(nodeCoords, seedIndex);
        nodeOrder = twoOpt(nodeOrder, nodeCoords, nodeIntensity);
        nodeOrder = orientToAnchors(nodeOrder, nodeCoords, startAnchor, endAnchor);

        List<Integer> result = new ArrayList<>();
        for (Integer nodeIdx : nodeOrder) {
            result.addAll(nodeGroups.get(nodeIdx));
        }
        return result;
    }

    /** anchor와 가장 가까운 노드의 인덱스. anchor가 null이면 0(기존 동작 유지). */
    private int nearestNodeTo(List<double[]> coords, double[] anchor) {
        if (anchor == null) return 0;
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < coords.size(); i++) {
            if (coords.get(i) == null) continue;
            double d = haversine(anchor, coords.get(i));
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * 경로 전체를 뒤집어도 총 거리는 동일하므로(2-opt 결과는 방향에 대해 대칭), 시작/종료
     * anchor에 더 잘 맞는 방향을 고른다 — "전날 숙소 근처에서 시작해 그날 숙소 근처에서 끝나는"
     * 흐름을 보장하기 위함.
     */
    private List<Integer> orientToAnchors(List<Integer> order, List<double[]> coords,
                                           double[] startAnchor, double[] endAnchor) {
        if (order.size() < 2 || (startAnchor == null && endAnchor == null)) return order;

        double[] firstCoord = coords.get(order.get(0));
        double[] lastCoord = coords.get(order.get(order.size() - 1));
        if (firstCoord == null || lastCoord == null) return order;

        double forwardFit = anchorFit(firstCoord, startAnchor) + anchorFit(lastCoord, endAnchor);
        double reversedFit = anchorFit(lastCoord, startAnchor) + anchorFit(firstCoord, endAnchor);

        if (reversedFit < forwardFit - 1e-6) {
            List<Integer> reversed = new ArrayList<>(order);
            Collections.reverse(reversed);
            return reversed;
        }
        return order;
    }

    private double anchorFit(double[] coord, double[] anchor) {
        return anchor == null ? 0.0 : haversine(coord, anchor);
    }

    /** pair로 묶인 그룹 내 하나라도 HIGHLIGHT/REST면 그 등급을 그룹 전체 등급으로 취급. */
    private Intensity groupIntensity(List<Integer> group, Set<Integer> highlights, Set<Integer> rests) {
        if (group.stream().anyMatch(highlights::contains)) return Intensity.HIGHLIGHT;
        if (group.stream().anyMatch(rests::contains)) return Intensity.REST;
        return Intensity.NEUTRAL;
    }

    private int find(Map<Integer, Integer> parent, int x) {
        int p = parent.getOrDefault(x, x);
        if (p != x) {
            p = find(parent, p);
            parent.put(x, p);
        }
        return p;
    }

    private void union(Map<Integer, Integer> parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) parent.put(ra, rb);
    }

    private List<Integer> nearestNeighborOrder(List<double[]> coords, int seedIndex) {
        int n = coords.size();
        List<Integer> order = new ArrayList<>();
        boolean[] visited = new boolean[n];
        int current = seedIndex;
        order.add(current);
        visited[current] = true;
        for (int step = 1; step < n; step++) {
            int nearest = -1;
            double minDist = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (visited[i] || coords.get(i) == null || coords.get(current) == null) continue;
                double d = haversine(coords.get(current), coords.get(i));
                if (d < minDist) {
                    minDist = d;
                    nearest = i;
                }
            }
            if (nearest == -1) {
                for (int i = 0; i < n; i++) if (!visited[i]) { nearest = i; break; }
            }
            order.add(nearest);
            visited[nearest] = true;
            current = nearest;
        }
        return order;
    }

    /**
     * 2-opt 1패스: 개선되는 edge swap이 없을 때까지 반복(작은 N이므로 충분히 빠름).
     * n=3까지는 전체 반전(i=0,j=n-1) 같은 의미 있는 swap이 가능하므로 n&lt;3에서만 스킵한다
     * (n&lt;4 스킵은 relaxed 페이스의 흔한 3-스팟 day에서 서사 흐름 가중치가 전혀 적용되지 않는
     * 버그였음).
     */
    private List<Integer> twoOpt(List<Integer> order, List<double[]> coords, List<Intensity> intensity) {
        int n = order.size();
        if (n < 3) return order;
        List<Integer> best = new ArrayList<>(order);
        boolean improved = true;
        int guard = 0;
        while (improved && guard++ < 50) {
            improved = false;
            for (int i = 0; i < n - 1; i++) {
                for (int j = i + 1; j < n; j++) {
                    List<Integer> candidate = twoOptSwap(best, i, j);
                    if (tourCost(candidate, coords, intensity) < tourCost(best, coords, intensity) - 1e-6) {
                        best = candidate;
                        improved = true;
                    }
                }
            }
        }
        return best;
    }

    private List<Integer> twoOptSwap(List<Integer> order, int i, int j) {
        List<Integer> result = new ArrayList<>(order.subList(0, i));
        List<Integer> middle = new ArrayList<>(order.subList(i, j + 1));
        Collections.reverse(middle);
        result.addAll(middle);
        result.addAll(order.subList(j + 1, order.size()));
        return result;
    }

    private double pathLength(List<Integer> order, List<double[]> coords) {
        double total = 0;
        for (int i = 1; i < order.size(); i++) {
            double[] a = coords.get(order.get(i - 1));
            double[] b = coords.get(order.get(i));
            if (a != null && b != null) total += haversine(a, b);
        }
        return total;
    }

    /**
     * 거리 + 서사 흐름 패널티. 거리가 1차 기준, 흐름은 거리가 비슷할 때만 결정권을 가지도록
     * 작은 가중치로 더한다(하드 제약인 거리·페이스를 흐름이 절대 압도하지 않게 함).
     */
    private double tourCost(List<Integer> order, List<double[]> coords, List<Intensity> intensity) {
        double cost = pathLength(order, coords);
        if (!order.isEmpty() && intensity.get(order.get(0)) == Intensity.HIGHLIGHT) {
            cost += HIGHLIGHT_FIRST_PENALTY_KM;
        }
        for (int i = 1; i < order.size(); i++) {
            if (intensity.get(order.get(i - 1)) == Intensity.HIGHLIGHT && intensity.get(order.get(i)) == Intensity.REST) {
                cost -= REST_AFTER_HIGHLIGHT_BONUS_KM;
            }
        }
        return cost;
    }

    /**
     * 확정된 day 순서에 시간·교통·대안을 부여한다.
     * 식사 역할(점심/저녁)은 메인 placeIndices 중 첫/마지막 DINING으로 식별해 슬롯에 고정한다.
     */
    private List<StepData> scheduleDay(DayState day, List<Integer> orderedMain,
                                        List<PlaceCandidate> candidates, Deque<Integer> spareIndices) {
        List<Integer> full = new ArrayList<>();
        if (day.arrivalHubIndex != null) full.add(day.arrivalHubIndex);
        full.addAll(orderedMain);
        if (day.accommodationIndex != null) full.add(day.accommodationIndex);
        if (day.departureHubIndex != null) full.add(day.departureHubIndex);

        List<Integer> diningInMain = orderedMain.stream()
                .filter(idx -> {
                    PlaceCandidate c = byIndex(candidates, idx);
                    return c != null && "DINING".equals(PlaceCategoryConstants.majorCategory(c.category()));
                })
                .collect(Collectors.toList());
        Integer lunchIdx = diningInMain.isEmpty() ? null : diningInMain.get(0);
        Integer dinnerIdx = diningInMain.size() >= 2 ? diningInMain.get(diningInMain.size() - 1) : null;

        List<StepData> steps = new ArrayList<>();
        int currentMinutes = DAY_START_MINUTES;
        PlaceCandidate prev = null;

        for (int idx : full) {
            PlaceCandidate cand = byIndex(candidates, idx);
            if (cand == null) continue;

            String mode = null;
            Integer durationMin = null;
            BigDecimal distanceKm = null;
            BigDecimal cost = null;

            if (prev != null) {
                double[] a = coordsOf(prev);
                double[] b = coordsOf(cand);
                if (a != null && b != null) {
                    double dist = haversine(a, b);
                    distanceKm = BigDecimal.valueOf(dist).setScale(2, RoundingMode.HALF_UP);
                    if (dist < 1.0) {
                        mode = "WALK";
                        durationMin = Math.max(1, (int) Math.ceil(dist * 15));
                        cost = BigDecimal.ZERO;
                    } else {
                        mode = "CAR";
                        durationMin = (int) Math.ceil(dist / 40 * 60);
                        cost = BigDecimal.valueOf(dist * 2000).setScale(0, RoundingMode.HALF_UP);
                    }
                    currentMinutes += durationMin;
                }
            }

            if (lunchIdx != null && idx == lunchIdx) currentMinutes = Math.max(currentMinutes, LUNCH_START);
            if (dinnerIdx != null && idx == dinnerIdx) currentMinutes = Math.max(currentMinutes, DINNER_START);

            int visitMinutes = "DINING".equals(PlaceCategoryConstants.majorCategory(cand.category()))
                    ? DINING_VISIT_MINUTES : DEFAULT_VISIT_MINUTES;
            int startMin = currentMinutes;
            int endMin = startMin + visitMinutes;

            steps.add(new StepData(
                    0, // stepOrder는 호출부에서 전체 재번호
                    day.dayNumber,
                    formatMinutes(startMin),
                    formatMinutes(endMin),
                    toPlaceData(cand),
                    buildAlternatives(cand, spareIndices, candidates),
                    mode, durationMin, distanceKm, cost,
                    null, // notes(story)는 비동기 Haiku 단계에서 채움
                    estimateCost(cand)
            ));

            currentMinutes = endMin;
            prev = cand;
        }
        return steps;
    }

    /** 같은 대분류 spare 후보 중 거리가 가까운 3개를 대안으로 제시. */
    private List<AlternativeData> buildAlternatives(PlaceCandidate main, Deque<Integer> spareIndices,
                                                      List<PlaceCandidate> candidates) {
        String targetCategory = PlaceCategoryConstants.majorCategory(main.category());
        double[] mainCoord = coordsOf(main);

        return spareIndices.stream()
                .map(i -> byIndex(candidates, i))
                .filter(Objects::nonNull)
                .filter(c -> targetCategory.equals(PlaceCategoryConstants.majorCategory(c.category())))
                .sorted(Comparator.comparingDouble(c -> mainCoord != null && coordsOf(c) != null
                        ? haversine(mainCoord, coordsOf(c)) : Double.MAX_VALUE))
                .limit(3)
                .map(c -> new AlternativeData(c.name(), c.address(), c.category(), c.region(), c.country(),
                        null, estimateCost(c)))
                .collect(Collectors.toList());
    }

    // Google Places priceLevel이 없을 때(흔함 — 특히 소규모 식당/카페)의 카테고리별 기본값.
    // ATTRACTION/TRANSIT_HUB/LODGING/OTHER는 무료인 경우가 흔해 0을 유지하지만, DINING/CAFE는
    // "0원"이 사실상 항상 틀린 값이라(공짜 식당은 없음) 추정치를 넣는 게 더 정직하다.
    private static final Map<String, Long> DEFAULT_COST_BY_CATEGORY = Map.of(
            "DINING", 13000L,
            "CAFE", 6000L
    );

    private BigDecimal estimateCost(PlaceCandidate c) {
        if (c.priceLevel() != null) {
            return BigDecimal.valueOf(c.priceLevel() * 15000L);
        }
        String category = PlaceCategoryConstants.majorCategory(c.category());
        Long fallback = DEFAULT_COST_BY_CATEGORY.get(category);
        return fallback != null ? BigDecimal.valueOf(fallback) : BigDecimal.ZERO;
    }

    private PlaceData toPlaceData(PlaceCandidate c) {
        return new PlaceData(c.name(), c.address(), c.category(), c.region(), c.country());
    }

    private PlaceCandidate byIndex(List<PlaceCandidate> candidates, int index) {
        if (index < 1 || index > candidates.size()) return null;
        return candidates.get(index - 1);
    }

    private double[] coordsOf(PlaceCandidate c) {
        if (c == null || c.latitude() == null || c.longitude() == null) return null;
        return new double[]{c.latitude().doubleValue(), c.longitude().doubleValue()};
    }

    private double[] centroidOf(List<Integer> indices, List<PlaceCandidate> candidates) {
        List<double[]> coords = indices.stream()
                .map(i -> coordsOf(byIndex(candidates, i)))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (coords.isEmpty()) return null;
        double lat = coords.stream().mapToDouble(c -> c[0]).average().orElse(0);
        double lng = coords.stream().mapToDouble(c -> c[1]).average().orElse(0);
        return new double[]{lat, lng};
    }

    private String formatMinutes(int minutes) {
        int h = (minutes / 60) % 24;
        int m = minutes % 60;
        return String.format("%02d:%02d", h, m);
    }

    private static class DayState {
        final int dayNumber;
        Integer arrivalHubIndex;
        final List<Integer> placeIndices;
        Integer accommodationIndex;
        Integer departureHubIndex;

        DayState(SelectionOutput.DayPlan plan) {
            this.dayNumber = plan.dayNumber();
            this.arrivalHubIndex = plan.arrivalHubIndex();
            this.placeIndices = new ArrayList<>(plan.placeIndices() != null ? plan.placeIndices() : List.of());
            this.accommodationIndex = plan.accommodationIndex();
            this.departureHubIndex = plan.departureHubIndex();
        }
    }
}
