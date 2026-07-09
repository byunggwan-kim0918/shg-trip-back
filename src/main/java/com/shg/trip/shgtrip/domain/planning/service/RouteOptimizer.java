package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.planning.dto.AlternativeData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.TransportationHub;
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
    // day가 지리적으로 두 덩어리로 갈릴 때, 두 서브클러스터 중심 거리가 이 값(그리고 내부 spread ×
    // 이동수단 배수) 이상이면 "2-클러스터 day"로 보고 소수 클러스터를 인접일로 옮긴다. 도심 밀집 day를
    // 잘못 쪼개지 않도록 하한을 둔다.
    private static final double MIN_CLUSTER_SPLIT_KM = 10.0;
    private static final int DAY_START_MINUTES = 9 * 60; // 09:00
    private static final int DEFAULT_VISIT_MINUTES = 90;
    private static final int DINING_VISIT_MINUTES = 70;
    // 한 destination 내 하루 이동으로는 비현실적인 구간거리 임계값. 초과 시 불량 좌표로 보고
    // 해당 leg의 교통정보를 비운다(시간 누적 폭주 → 새벽시간 wrap 방지).
    private static final double MAX_REASONABLE_LEG_KM = 200.0;
    // 하루 시각 표기의 하한/상한(분). 누적 시간이 자정을 넘겨 02:16처럼 wrap되는 것을 막는다.
    private static final int END_OF_DAY_MINUTES = 23 * 60 + 59; // 23:59
    // 도보(walk) 선호 시, 익일 첫 방문지가 전날 숙소에서 이 거리를 넘으면 "숙소에서 아침 출발"로
    // 보정한다(전날 숙소를 그날 첫 스텝으로 prepend). car/any는 어느 정도 떨어져도 허용.
    private static final double WALK_CONTINUITY_THRESHOLD_KM = 2.0;
    // 아침에 숙소에서 출발하는 스텝의 체류 시간(분). 실제 관광 체류(90분)와 달리 짧게 잡는다.
    private static final int MORNING_DEPARTURE_MINUTES = 30;
    // A-1 시간 배분: 활동 1개가 차지하는 대략 시간(체류 90 + 이동 추정 ~20). 시간창 용량 산정용.
    private static final int ACTIVITY_SLOT_MINUTES = 110;
    private static final int DEFAULT_EVENING_CAP_MINUTES = 22 * 60; // 22:00
    private static final int LATE_EVENING_CAP_MINUTES = 23 * 60;    // 야경/야시장류 테마
    private static final int EARLY_MORNING_START_MINUTES = 8 * 60;  // 일출/등산류 테마
    private static final Set<String> NIGHT_THEME_KEYWORDS = Set.of(
            "야경", "야시장", "나이트", "클럽", "포차", "술", "nightlife", "bar", "pub", "night");
    private static final Set<String> EARLY_THEME_KEYWORDS = Set.of(
            "일출", "새벽", "등산", "하이킹", "트레킹", "sunrise", "hiking", "trekking");
    // 거리 절대 상한 [단일 구간 km, 일일 총주행 km]. TRANSPORT_DISTANCE_MULTIPLIER는 day 중심
    // 상대 기준이라 이미 흩어진 day일수록 허용치가 커지는 자기무력화 문제가 있어(실측: 하루 190km,
    // 저녁 67km 카페 원정), 상대 기준과 별개로 절대 상한을 둔다.
    private static final Map<String, double[]> TRANSPORT_ABS_LIMITS = Map.of(
            "walk", new double[]{8, 30},
            "car", new double[]{50, 150},
            "any", new double[]{40, 120}
    );
    // 전역 지리 재배치(rebalanceDaysByGeography): 방문지가 다른 day centroid에 이 값 이상
    // 가까워질 때만 이동한다. 미세 차이로 장소가 날짜 사이를 진동하는 것을 막는 히스테리시스.
    private static final double REBALANCE_MIN_GAIN_KM = 15.0;
    // 저녁(dinner 이후) 활동이 숙소-저녁식당 거리보다 이만큼 이상 숙소에서 멀어지면 spare로 뺀다.
    private static final double EVENING_AWAY_TOLERANCE_KM = 5.0;
    // 하루를 마치고 숙소에 도착하는 스텝의 체류 시간(분). 관광 체류(90분)로 잡으면 "23:45 체크인"
    // 같은 어색한 시간이 나온다.
    private static final int ACCOMMODATION_ARRIVAL_MINUTES = 30;
    // 도로거리 환산(GeoUtils.ROAD_DISTANCE_FACTOR)은 이동시간·비용·표시거리에 적용되지만, 거리
    // "예산"(TRANSPORT_ABS_LIMITS 등)·클러스터 판정은 직선 기준 임계값이라 적용하지 않는다(이중 보정 방지).
    // 식사 슬롯 전 빈 시간이 이 값 이상이면 spare에서 활동을 삽입해 메운다.
    // ACTIVITY_SLOT_MINUTES(110, 버킷 용량 산정용)와 분리 — 실측에서 98/107분 공백이 미발동됐다.
    private static final int GAP_FILL_THRESHOLD_MINUTES = 90;

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
        return repairAndSchedule(selection, candidates, pace, transportPref, startDate, List.of());
    }

    /**
     * @param themes 여행 테마. 시간대 상한/시작을 가볍게 조정한다(야경류→저녁 상한 완화,
     *               일출/등산류→아침 시작 당김). null/빈 값이면 기본값(09:00~22:00).
     */
    public List<StepData> repairAndSchedule(SelectionOutput selection, List<PlaceCandidate> candidates,
                                             String pace, String transportPref, java.time.LocalDate startDate,
                                             List<String> themes) {
        return repairAndSchedule(selection, candidates, pace, transportPref, startDate, themes, null, false);
    }

    /**
     * @param hub enrich 단계가 결정한 도착/출발 허브 이름. 제공되면 이름 매칭으로 허브 후보를
     *            결정론적으로 교정한다(공항 본체 vs "Immigration Check" 같은 부속시설 POI가
     *            rating 동률일 때 순서 운에 좌우되는 문제 방지). null이면 기존 동작.
     * @param compactMode 후보 풀 품질이 낮을 때(유효 관광지 부족) 하한 quota를 완화해,
     *                    엉뚱한 카테고리로 억지로 채우는 대신 컴팩트한 일정을 허용한다.
     */
    public List<StepData> repairAndSchedule(SelectionOutput selection, List<PlaceCandidate> candidates,
                                             String pace, String transportPref, java.time.LocalDate startDate,
                                             List<String> themes, TransportationHub hub,
                                             boolean compactMode) {
        int[] range = PACE_RANGE.getOrDefault(pace, PACE_RANGE.get("normal"));
        int minPerDay = compactMode ? Math.min(range[0], 3) : range[0];
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
            changed |= repairClusterSplit(days, candidates, maxPerDay, distanceMultiplier, pairs);
            changed |= repairDistanceOutliers(days, candidates, maxPerDay, distanceMultiplier);
            // 전역 지리 재배치: 각 방문지를 centroid가 가장 가까운 day로 재배정(인접 day 제약 없음).
            // repairClusterSplit이 "인접 day로만·2클러스터만" 보는 한계로 제주처럼 전 지역이
            // 흩어진 일정에서 하루 200km가 남던 문제를 K-means식 전역 재할당으로 잡는다.
            changed |= rebalanceDaysByGeography(days, candidates, maxPerDay, pairs);
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

        // Sonnet이 오분류 데이터(예: "흰여울문화마을=Bus Station")로 지정한 허브를 일반 방문지로
        // 강등한 뒤 repairHubs가 진짜 허브로 다시 채우도록 한다(A-0-3).
        sanitizeSuspiciousHubs(days, candidates);
        repairHubs(days, candidates);
        // enrich가 지정한 허브 이름("제주국제공항")과 이름 매칭으로 허브를 결정론적으로 교정.
        // rating만으로는 공항 본체와 "Immigration Check" 같은 부속시설 POI가 동률일 수 있다.
        repairNamedHubs(days, candidates, hub);
        // 숙소를 그날 방문지 중심에 가장 가까운 후보로 교체 — Sonnet이 고른 숙소가 동선과 무관해
        // 매일 숙소↔방문지를 장거리 왕복하는 문제(실측: 저녁 66km/100분 귀가)의 근본 완화.
        // continuity 앞에 두어, 짧은 여행은 각 day 최적화 후 continuity가 동일 지역을 통일한다.
        repairAccommodationByProximity(days, candidates);
        repairAccommodationContinuity(days, candidates);
        // 정기휴무 회피: fixpoint 수렴 후 1회(swap이라 count 중립 → 진동 없음, best-effort).
        // pair 멤버는 휴무여도 건너뛴다(대체하면 pair 한쪽이 일정에서 완전히 사라짐 — 정기휴무
        // 회피보다 pair 무결성을 우선).
        repairClosedDayPlaces(days, candidates, spare, startDate, pairs);
        // 동일 장소가 메인 스텝에 두 번 이상 나오지 않게 정리(A-0-2) — 허브/숙소는 대상 아님.
        dedupeMainStepsAcrossItinerary(days, candidates, spare);
        // 하루 일과 용량(테마 반영 시간창) 초과 활동을 인접 day로 이월(A-1) — 실패분은 scheduleDay가 트림.
        ScheduleConfig cfg = scheduleConfig(themes);
        repairDayCapacity(days, candidates, cfg, maxPerDay);

        Set<Integer> highlights = selection.highlightIndices() != null
                ? new HashSet<>(selection.highlightIndices()) : Set.of();
        Set<Integer> rests = selection.restIndices() != null
                ? new HashSet<>(selection.restIndices()) : Set.of();

        // 대안 생성 시 "이미 일정에 쓰인 장소"를 재사용하지 않도록 최종 확정된 사용 인덱스 집합.
        Set<Integer> usedIndices = collectUsedIndices(days);
        // Sonnet이 프롬프트를 어겨 메인과 spare에 같은 인덱스를 중복 기재하면, 일정에 이미 있는
        // 장소가 다른 스텝의 대안으로 재등장한다(대안 선택 시 같은 곳 2회 방문). 여기서 정리한다.
        spare.removeAll(usedIndices);

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
            ordered = applyWalkContinuity(ordered, day, days, i, candidates, transportPref);
            // 절대 거리 상한(단일 구간/일일 총주행) 초과분을 가까운 spare로 교체하거나 제거.
            ordered = enforceDistanceBudget(day, ordered, candidates, spare, usedIndices, transportPref);
            double[] dayCentroid = centroidOf(day.placeIndices, candidates);
            // 하루의 출발지(전날 숙소) — 첫 스텝의 이동정보와 도착시각(09:00 출발 + 이동시간)에 쓴다.
            PlaceCandidate startFrom = (i > 0 && day.arrivalHubIndex == null
                    && days.get(i - 1).accommodationIndex != null)
                    ? byIndex(candidates, days.get(i - 1).accommodationIndex) : null;
            steps.addAll(scheduleDay(day, ordered, candidates, spare, cfg,
                    dayCentroid, usedIndices, transportPref, startFrom, maxPerDay));
        }

        // 대안은 day 순서대로 생성되므로, 뒤쪽 day의 갭 필/거리 교체로 나중에 본일정에 편입된
        // 장소가 앞쪽 day의 대안에 이미 들어가 있을 수 있다(실측: 3일차 갭 필로 들어간 카페가
        // 1일차 카페의 대안으로 잔존 — 스왑 시 같은 곳 2회 방문). 최종 스텝 기준으로 걸러낸다.
        Set<String> usedPlaceKeys = steps.stream()
                .map(StepData::place)
                .filter(Objects::nonNull)
                .map(p -> placeKey(p.name(), p.address()))
                .collect(Collectors.toSet());
        List<StepData> deduped = new ArrayList<>(steps.size());
        for (StepData s : steps) {
            deduped.add(withoutUsedAlternatives(s, usedPlaceKeys));
        }
        steps = deduped;

        int order = 1;
        List<StepData> renumbered = new ArrayList<>(steps.size());
        for (StepData s : steps) {
            renumbered.add(withStepOrder(s, order++));
        }

        log.info("RouteOptimizer.repairAndSchedule 완료: {}개 step, {}개 day", renumbered.size(), days.size());
        return renumbered;
    }

    private StepData withoutUsedAlternatives(StepData s, Set<String> usedPlaceKeys) {
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

    /** 장소 동일성 키(name+address 정규화) — DB places의 (name,address) 유니크 키와 같은 기준. */
    private String placeKey(String name, String address) {
        return normalizeName(name) + "|" + normalizeName(address);
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

    /**
     * DINING이 day의 유일한 식사면 보존, 사용자 필수 장소(userSelected)는 절대 트림하지 않고,
     * 그 외엔 rating 낮은 순으로 제거 대상 선정. 전부 보호 대상이면 null — 호출부가 quota
     * 초과를 허용한다(사용자 선택 존중이 pace 상한보다 우선).
     */
    private Integer pickTrimCandidate(List<Integer> indices, List<PlaceCandidate> candidates) {
        long diningCount = indices.stream()
                .map(i -> byIndex(candidates, i))
                .filter(c -> c != null && "DINING".equals(PlaceCategoryConstants.majorCategory(c.category())))
                .count();

        return indices.stream()
                .map(i -> byIndex(candidates, i))
                .filter(Objects::nonNull)
                .filter(c -> !c.userSelected())
                .filter(c -> diningCount > 1 || !"DINING".equals(PlaceCategoryConstants.majorCategory(c.category())))
                .min(Comparator.comparing(c -> c.rating() != null ? c.rating() : BigDecimal.ZERO))
                .map(PlaceCandidate::index)
                .orElse(null);
    }

    /**
     * day 중심과 가까운 spare 후보를 우선 선택. 방문지로 부적합한 대분류(숙소/교통허브/OTHER)는
     * 채우지 않는다 — select-places 프롬프트가 spare에 숙소를 "대안 제시용"으로 포함시키므로,
     * 가드 없이 거리만 보고 채우면 게스트하우스가 관광 스텝으로 들어오는 사고가 난다(실측 발생).
     * 채울 후보가 없으면 null을 반환해 minPerDay 미달을 허용한다(짧은 날이 엉뚱한 스텝보다 낫다).
     */
    private Integer pickBestFill(DayState day, Deque<Integer> spare, List<PlaceCandidate> candidates) {
        double[] centroid = centroidOf(day.placeIndices, candidates);

        return spare.stream()
                .map(i -> byIndex(candidates, i))
                .filter(Objects::nonNull)
                .filter(this::isVisitableFill)
                .filter(c -> coordsOf(c) != null) // (0,0)/좌표 불명 후보는 거리 비교 불가 — 제외
                .min(Comparator.comparingDouble(c -> centroid != null
                        ? haversine(centroid, coordsOf(c)) : 0))
                .map(PlaceCandidate::index)
                .orElse(null);
    }

    /** quota 보충으로 방문 스텝에 넣어도 되는 대분류인지(식당/카페/관광지만 허용). */
    private boolean isVisitableFill(PlaceCandidate c) {
        String major = PlaceCategoryConstants.majorCategory(c.category());
        return "DINING".equals(major) || "CAFE".equals(major) || "ATTRACTION".equals(major);
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
                    .map(idx -> coordsOf(byIndex(candidates, idx)))
                    .filter(Objects::nonNull) // (0,0)/좌표 불명은 평균 계산에서 제외
                    .mapToDouble(coord -> haversine(centroid, coord))
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

    /**
     * day가 지리적으로 뚜렷한 2개 클러스터로 갈리면(동↔서처럼), 소수 클러스터 전체를 centroid가
     * 더 가까운 인접 day로 옮긴다(A-2). 기존 repairDistanceOutliers는 "단일 점이 평균거리 3배"만
     * 잡아 반반으로 쪼개진 day를 놓치는데, 이 패스는 그룹 단위로 재배치해 오후 공백·왕복 동선의
     * 근본 원인(먼 두 지역을 한 날에 배정)을 해소한다. pair는 쪼개지 않고, 허브/숙소는 대상 아니다.
     */
    private boolean repairClusterSplit(List<DayState> days, List<PlaceCandidate> candidates,
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
                if (haversine(c, cs0) <= haversine(c, cs1)) clusterA.add(idx);
                else clusterB.add(idx);
            }
            if (clusterA.isEmpty() || clusterB.isEmpty()) continue;

            double[] ca = centroidOf(clusterA, candidates);
            double[] cb = centroidOf(clusterB, candidates);
            if (ca == null || cb == null) continue;
            double inter = haversine(ca, cb);
            double intra = Math.max(avgToCentroid(clusterA, ca, candidates),
                    avgToCentroid(clusterB, cb, candidates));
            if (inter < Math.max(MIN_CLUSTER_SPLIT_KM, intra * distanceMultiplier)) continue;

            boolean aMinor = clusterA.size() <= clusterB.size();
            List<Integer> minority = aMinor ? clusterA : clusterB;
            double[] minorityCentroid = aMinor ? ca : cb;
            double[] majorityCentroid = aMinor ? cb : ca;

            // pair가 이 day 안에서 minority/majority로 갈리면 이동 보류(pair 분리 방지)
            if (splitsPair(minority, day.placeIndices, pairs)) continue;

            DayState target = null;
            double best = Double.MAX_VALUE;
            for (int j : new int[]{i - 1, i + 1}) {
                if (j < 0 || j >= days.size()) continue;
                DayState nb = days.get(j);
                if (nb.placeIndices.size() + minority.size() > maxPerDay) continue;
                double[] nc = centroidOf(nb.placeIndices, candidates);
                if (nc == null) continue;
                double d = haversine(nc, minorityCentroid);
                if (d < best) { best = d; target = nb; }
            }

            // minority가 같은 day의 majority보다 target에 더 가까울 때만 이동(개선 보장)
            if (target != null && best < haversine(minorityCentroid, majorityCentroid)) {
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
     * 전역 지리 재배치(K-means식 1-pass): 각 방문지를 현재 배정된 day보다 centroid가 확연히
     * 더 가까운 다른 day가 있으면 그 day로 옮긴다. repairClusterSplit이 "인접 day·2클러스터"만
     * 보는 한계를 보완해, 전 지역이 흩어진 일정에서 각 장소를 지리적으로 맞는 날로 모은다.
     * <ul>
     *   <li>대상: placeIndices(방문지)만. 허브·숙소는 별도 필드라 제외.</li>
     *   <li>이동 조건: 다른 day centroid가 현재 day centroid보다 {@link #REBALANCE_MIN_GAIN_KM}
     *       이상 가깝고, 목적 day가 maxPerDay 미만일 때만. pair 멤버는 옮기지 않는다(pair 분리 방지).</li>
     *   <li>한 번에 장소당 최대 1회 이동(fixpoint 루프가 반복 호출하며 수렴).</li>
     * </ul>
     */
    private boolean rebalanceDaysByGeography(List<DayState> days, List<PlaceCandidate> candidates,
                                             int maxPerDay, List<List<Integer>> pairs) {
        if (days.size() < 2) return false;
        Set<Integer> pairedIndices = pairs.stream()
                .filter(p -> p != null && p.size() == 2)
                .flatMap(List::stream)
                .collect(Collectors.toSet());

        // day별 centroid 사전 계산(이동 전 스냅샷 — 한 패스 내에서 일관 기준 유지)
        List<double[]> centroids = new ArrayList<>();
        for (DayState d : days) centroids.add(centroidOf(d.placeIndices, candidates));

        boolean changed = false;
        for (int i = 0; i < days.size(); i++) {
            DayState day = days.get(i);
            double[] myCentroid = centroids.get(i);
            if (myCentroid == null) continue;

            for (Integer idx : new ArrayList<>(day.placeIndices)) {
                if (pairedIndices.contains(idx)) continue;
                double[] coord = coordsOf(byIndex(candidates, idx));
                if (coord == null) continue;
                double myDist = haversine(coord, myCentroid);

                int bestDay = -1;
                double bestDist = myDist - REBALANCE_MIN_GAIN_KM; // 이만큼 가까워져야 이동
                for (int j = 0; j < days.size(); j++) {
                    if (j == i) continue;
                    if (days.get(j).placeIndices.size() >= maxPerDay) continue;
                    double[] other = centroids.get(j);
                    if (other == null) continue;
                    double d = haversine(coord, other);
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
        return changed;
    }

    /** idxList 중 좌표상 가장 먼 두 점의 (리스트 내) 위치를 반환. 유효좌표 없으면 null. */
    private int[] farthestPair(List<Integer> idxList, List<PlaceCandidate> candidates) {
        int n = idxList.size();
        double max = -1;
        int a = -1, b = -1;
        for (int i = 0; i < n; i++) {
            double[] ci = coordsOf(byIndex(candidates, idxList.get(i)));
            if (ci == null) continue;
            for (int j = i + 1; j < n; j++) {
                double[] cj = coordsOf(byIndex(candidates, idxList.get(j)));
                if (cj == null) continue;
                double d = haversine(ci, cj);
                if (d > max) { max = d; a = i; b = j; }
            }
        }
        return a >= 0 ? new int[]{a, b} : null;
    }

    private double avgToCentroid(List<Integer> idxList, double[] centroid, List<PlaceCandidate> candidates) {
        return idxList.stream()
                .map(idx -> coordsOf(byIndex(candidates, idx)))
                .filter(Objects::nonNull)
                .mapToDouble(c -> haversine(centroid, c))
                .average().orElse(0);
    }

    /** minority를 옮기면 이 day 안에 함께 있던 pair가 갈라지는지. */
    private boolean splitsPair(List<Integer> minority, List<Integer> dayPlaces, List<List<Integer>> pairs) {
        Set<Integer> minoritySet = new HashSet<>(minority);
        for (List<Integer> p : pairs) {
            if (p == null || p.size() != 2) continue;
            boolean bothInDay = dayPlaces.contains(p.get(0)) && dayPlaces.contains(p.get(1));
            if (bothInDay && (minoritySet.contains(p.get(0)) != minoritySet.contains(p.get(1)))) return true;
        }
        return false;
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

    /**
     * enrich 단계가 지정한 허브 이름(예: "제주국제공항")과 후보 이름을 매칭해 도착/출발 허브를
     * 결정론적으로 교정한다. rating 기준만으로는 공항 본체(제주국제공항 4.4)와 부속시설
     * ("Jeju International Airport Immigration Check" 4.4)이 동률이라 스트림 순서 운에
     * 좌우되는 문제가 있었다(실측). 이름 일치 > 부속시설 아님 > rating 순으로 고른다.
     */
    private void repairNamedHubs(List<DayState> days, List<PlaceCandidate> candidates, TransportationHub hub) {
        if (hub == null || days.isEmpty()) return;
        DayState first = days.get(0);
        DayState last = days.get(days.size() - 1);

        Integer arrival = pickNamedHub(candidates, hub.arrivalHub(), first.arrivalHubIndex);
        if (arrival != null) first.arrivalHubIndex = arrival;
        Integer departure = pickNamedHub(candidates, hub.departureHub(), last.departureHubIndex);
        if (departure != null) last.departureHubIndex = departure;
    }

    /** 현재 선택보다 hubScore가 확실히 높은 허브 후보 인덱스를 반환. 개선 없으면 null. */
    private Integer pickNamedHub(List<PlaceCandidate> candidates, String hubName, Integer current) {
        PlaceCandidate best = candidates.stream()
                .filter(c -> "TRANSIT_HUB".equals(PlaceCategoryConstants.majorCategory(c.category())))
                .filter(c -> PlaceCategoryConstants.hasTransitNameSignal(c.name()))
                .max(Comparator.comparingDouble(c -> hubScore(c, hubName)))
                .orElse(null);
        if (best == null) return null;
        if (current != null) {
            if (best.index() == current) return null;
            PlaceCandidate cur = byIndex(candidates, current);
            if (cur != null && hubScore(cur, hubName) >= hubScore(best, hubName)) return null;
            log.info("허브 이름 매칭 교정: {} → {}", cur != null ? cur.name() : current, best.name());
        }
        return best.index();
    }

    /** 허브 적합도: enrich 허브 이름 일치(+1000) > 부속시설 아님(+500) > rating. */
    private double hubScore(PlaceCandidate c, String hubName) {
        double score = 0;
        if (hubName != null && !hubName.isBlank() && c.name() != null) {
            String a = normalizeName(c.name());
            String b = normalizeName(hubName);
            if (!a.isEmpty() && !b.isEmpty() && (a.contains(b) || b.contains(a))) score += 1000;
        }
        if (!PlaceCategoryConstants.isHubSubFacility(c.name())) score += 500;
        if (c.rating() != null) score += c.rating().doubleValue();
        return score;
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.toLowerCase().replaceAll("\\s+", "");
    }

    /**
     * 각 day의 숙소를 그날 방문지 centroid에 가장 가까운 LODGING 후보로 교체한다. Sonnet이
     * 동선과 무관하게 고른 숙소가 매일 방문지에서 멀리 떨어져 장거리 왕복을 유발하는 문제를
     * 완화한다. 후보는 전체 candidates의 모든 LODGING(검색 시 항상 몇 개 포함). userSelected
     * 숙소는 사용자 의사이므로 교체하지 않는다. 유효 좌표가 없으면 원본 유지.
     */
    private void repairAccommodationByProximity(List<DayState> days, List<PlaceCandidate> candidates) {
        // 이미 방문지(placeIndices)로 쓰인 LODGING은 후보에서 제외 — 같은 장소가 "방문+숙소"로
        // 이중 등장하는 걸 막는다(dedupeMainStepsAcrossItinerary는 숙소 인덱스를 검사하지 않음).
        Set<Integer> visitedIndices = new HashSet<>();
        for (DayState d : days) visitedIndices.addAll(d.placeIndices);

        List<PlaceCandidate> lodgings = candidates.stream()
                .filter(c -> "LODGING".equals(PlaceCategoryConstants.majorCategory(c.category())))
                .filter(c -> coordsOf(c) != null)
                .filter(c -> !visitedIndices.contains(c.index()))
                .collect(Collectors.toList());
        if (lodgings.isEmpty()) return;

        for (DayState day : days) {
            if (day.accommodationIndex == null) continue; // 귀가일
            PlaceCandidate current = byIndex(candidates, day.accommodationIndex);
            if (current != null && current.userSelected()) continue; // 사용자 선택 존중

            double[] centroid = centroidOf(day.placeIndices, candidates);
            if (centroid == null) continue;

            PlaceCandidate nearest = null;
            double best = Double.MAX_VALUE;
            for (PlaceCandidate lodging : lodgings) {
                double d = haversine(centroid, coordsOf(lodging));
                if (d < best) {
                    best = d;
                    nearest = lodging;
                }
            }
            if (nearest != null && nearest.index() != day.accommodationIndex) {
                double curDist = current != null && coordsOf(current) != null
                        ? haversine(centroid, coordsOf(current)) : Double.MAX_VALUE;
                // 의미 있게 가까워질 때만 교체(5km 이상 개선) — 미세 차이로 숙소가 흔들리지 않게
                if (curDist - best >= 5.0) {
                    log.info("숙소 동선 최적화: day={} {} → {} ({}km 단축)", day.dayNumber,
                            current != null ? current.name() : day.accommodationIndex, nearest.name(),
                            String.format("%.1f", curDist - best));
                    day.accommodationIndex = nearest.index();
                }
            }
        }
    }

    /**
     * 짧은 여행(≤3일, 즉 ≤2박3일)에서만 인접 day가 같은 지역이면 동일 숙소를 강제한다(매일 호텔
     * 변경 방지). 3박4일 이상 긴 여행은 "지역이 바뀌면 여러 숙소"라는 LLM의 의도적 선택을 존중해
     * 덮어쓰지 않는다(E). 숙소 변경 여부는 select-places 프롬프트가 안내한다.
     */
    private void repairAccommodationContinuity(List<DayState> days, List<PlaceCandidate> candidates) {
        if (days.size() > 3) return;
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

    /**
     * Sonnet이 지정한 도착/출발 허브가 이름에 허브 신호(공항/역/터미널/항구)가 없으면 일반
     * 방문지로 강등한다(A-0-3). 오적재 데이터("흰여울문화마을=Bus Station")가 도착 지점으로
     * 쓰여 스텝 첫머리에 반복 배치되는 사고를 막는다. 강등된 장소는 placeIndices로 옮겨 보존하고,
     * 이후 repairHubs가 진짜 허브 후보가 있으면 다시 채운다.
     */
    private void sanitizeSuspiciousHubs(List<DayState> days, List<PlaceCandidate> candidates) {
        for (DayState day : days) {
            day.arrivalHubIndex = demoteIfNotHub(day.arrivalHubIndex, day, candidates);
            day.departureHubIndex = demoteIfNotHub(day.departureHubIndex, day, candidates);
        }
    }

    private Integer demoteIfNotHub(Integer hubIndex, DayState day, List<PlaceCandidate> candidates) {
        if (hubIndex == null) return null;
        PlaceCandidate c = byIndex(candidates, hubIndex);
        if (c == null) return hubIndex;
        if (PlaceCategoryConstants.hasTransitNameSignal(c.name())) return hubIndex; // 진짜 허브 유지
        if (!day.placeIndices.contains(hubIndex)) day.placeIndices.add(hubIndex);
        log.info("허브 오분류 강등: day={} {} → 일반 방문지", day.dayNumber, c.name());
        return null;
    }

    /**
     * 한 itinerary 내 동일 장소(placeId, 없으면 candidate index)가 메인 스텝에 두 번 이상
     * 나오지 않게 한다(A-0-2). 중복은 제거하고 같은 대분류·미사용 spare로 보충(없으면 그냥 제거 —
     * 중복 유지보다 나음). 숙소(날짜별 재사용 정상)·허브는 대상이 아니다.
     */
    private void dedupeMainStepsAcrossItinerary(List<DayState> days, List<PlaceCandidate> candidates,
                                                Deque<Integer> spare) {
        Set<Integer> seenIndices = new HashSet<>();
        Set<Long> seenPlaceIds = new HashSet<>();
        for (DayState day : days) {
            List<Integer> deduped = new ArrayList<>();
            for (Integer idx : day.placeIndices) {
                PlaceCandidate c = byIndex(candidates, idx);
                Long pid = c != null ? c.placeId() : null;
                boolean dup = seenIndices.contains(idx) || (pid != null && seenPlaceIds.contains(pid));
                if (dup) {
                    Integer fill = pickDistinctSpare(day, candidates, spare, seenIndices, seenPlaceIds, c);
                    if (fill != null) {
                        deduped.add(fill);
                        seenIndices.add(fill);
                        PlaceCandidate fc = byIndex(candidates, fill);
                        if (fc != null && fc.placeId() != null) seenPlaceIds.add(fc.placeId());
                    } else {
                        log.info("메인 스텝 중복 제거(보충 실패): day={} {}", day.dayNumber,
                                c != null ? c.name() : idx);
                    }
                } else {
                    deduped.add(idx);
                    seenIndices.add(idx);
                    if (pid != null) seenPlaceIds.add(pid);
                }
            }
            day.placeIndices.clear();
            day.placeIndices.addAll(deduped);
        }
    }

    /** 중복 스텝 보충용: 같은 대분류·미사용(index/placeId) spare 중 day 중심에서 가까운 하나. */
    private Integer pickDistinctSpare(DayState day, List<PlaceCandidate> candidates, Deque<Integer> spare,
                                      Set<Integer> seenIndices, Set<Long> seenPlaceIds, PlaceCandidate like) {
        String cat = like != null ? PlaceCategoryConstants.majorCategory(like.category()) : null;
        double[] centroid = centroidOf(day.placeIndices, candidates);
        Integer chosen = spare.stream()
                .filter(si -> !seenIndices.contains(si))
                .map(si -> byIndex(candidates, si))
                .filter(Objects::nonNull)
                .filter(sc -> sc.placeId() == null || !seenPlaceIds.contains(sc.placeId()))
                .filter(sc -> cat == null || cat.equals(PlaceCategoryConstants.majorCategory(sc.category())))
                .min(Comparator.comparingDouble(sc -> centroid != null && coordsOf(sc) != null
                        ? haversine(centroid, coordsOf(sc)) : Double.MAX_VALUE))
                .map(PlaceCandidate::index)
                .orElse(null);
        if (chosen != null) spare.remove(chosen);
        return chosen;
    }

    /**
     * 도보(walk) 선호일 때 익일 첫 방문지가 전날 숙소에서 도보권({@link #WALK_CONTINUITY_THRESHOLD_KM})을
     * 벗어나면, 전날 숙소를 그날 첫 스텝으로 prepend 하여 "숙소에서 아침 출발" 흐름을 보장한다.
     * car/any 선호는 차량 이동을 전제로 하므로 보정하지 않는다(현행 동선 유지).
     * 첫날(i==0), 도착 허브가 있는 날, 좌표 불명, 이미 첫 스텝이 그 숙소인 경우는 보정 대상이 아니다.
     */
    private List<Integer> applyWalkContinuity(List<Integer> ordered, DayState day, List<DayState> days,
                                               int dayIdx, List<PlaceCandidate> candidates, String transportPref) {
        if (!"walk".equals(transportPref) || dayIdx == 0 || ordered.isEmpty()
                || day.arrivalHubIndex != null) {
            return ordered;
        }
        Integer prevAccom = days.get(dayIdx - 1).accommodationIndex;
        if (prevAccom == null || prevAccom.equals(ordered.get(0))) return ordered;

        double[] prevAccomCoord = coordsOf(byIndex(candidates, prevAccom));
        double[] firstCoord = coordsOf(byIndex(candidates, ordered.get(0)));
        if (prevAccomCoord == null || firstCoord == null) return ordered;
        if (haversine(prevAccomCoord, firstCoord) <= WALK_CONTINUITY_THRESHOLD_KM) return ordered;

        log.info("walk 연속성 보정: day={} 첫 방문지가 전날 숙소에서 도보권 밖 — 숙소(idx={})에서 출발하도록 조정",
                day.dayNumber, prevAccom);
        List<Integer> adjusted = new ArrayList<>(ordered.size() + 1);
        adjusted.add(prevAccom);
        adjusted.addAll(ordered);
        return adjusted;
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
                if (pairedIndices.contains(idx) || cand.userSelected()) {
                    log.warn("정기휴무 장소이나 {} 멤버라 교체 보류: day={}({}) {}",
                            cand.userSelected() ? "사용자 필수" : "pair", day.dayNumber, dow, cand.name());
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
                // 이름에 실제 허브 신호(공항/역/터미널/항구)가 있는 것만 — "흰여울문화마을"이
                // "Bus Station"으로 오적재돼 도착 허브로 쓰이는 사고 방지(A-0-3).
                .filter(c -> PlaceCategoryConstants.hasTransitNameSignal(c.name()))
                .filter(c -> !used.contains(c.index()))
                // 부속시설(심사대/체크인/주차)보다 허브 본체 우선, 그 다음 rating.
                .max(Comparator
                        .comparingInt((PlaceCandidate c) -> PlaceCategoryConstants.isHubSubFacility(c.name()) ? 0 : 1)
                        .thenComparing(c -> c.rating() != null ? c.rating() : BigDecimal.ZERO))
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
     * 확정된 day 순서에 시간·교통·대안을 부여한다(A-1 재타이밍).
     *
     * <p>기존 "경로 순서대로 시간 누적 + 점심/저녁만 고정"은 저녁 식당이 경로상 앞이면 그 뒤
     * 활동이 전부 저녁 이후로 밀려 오후 공백·심야 폭주를 낳았다. 대신 여기서는:
     * <ul>
     *   <li>DINING 개수로 아침/점심/저녁 슬롯을 판정(1→점심, 2→점심+저녁, 3→아침+점심+저녁,
     *       4번째+ 는 spare로 트림). 기존 "첫=점심/마지막=저녁"은 브런치를 점심으로 오판했음.</li>
     *   <li>비식사 활동을 상대순서를 지키며 오전/오후/저녁 시간창 용량만큼 채운다. 저녁 상한을
     *       넘는 초과분은 spare로(대개 repairDayCapacity가 인접일로 선이월).</li>
     * </ul>
     */
    private List<StepData> scheduleDay(DayState day, List<Integer> orderedMain,
                                        List<PlaceCandidate> candidates, Deque<Integer> spareIndices,
                                        ScheduleConfig cfg, double[] dayCentroid,
                                        Set<Integer> usedIndices, String transportPref,
                                        PlaceCandidate startFrom, int maxPerDay) {
        // 식사 식별(순서 유지). Bar 계열은 식사가 아닌 저녁 활동으로 취급한다(점심 슬롯에 맥주바가
        // 배정되는 사고 방지). 4번째+ DINING은 트림 → spare.
        List<Integer> diningAll = orderedMain.stream()
                .filter(idx -> {
                    PlaceCandidate c = byIndex(candidates, idx);
                    return isDiningCand(c) && !PlaceCategoryConstants.isBar(c.category());
                })
                .collect(Collectors.toList());
        List<Integer> meals = selectMeals(diningAll, candidates, spareIndices);

        // 저녁은 경로순이 아니라 "숙소에 가장 가까운 식당"으로 — 저녁 식사 후 숙소 반대편으로
        // 장거리 이동하는 패턴(실측: 저녁 왕복 65km)을 구조적으로 줄인다.
        meals = preferDinnerNearAccommodation(meals, day, candidates);

        Integer breakfastIdx = null, lunchIdx = null, dinnerIdx = null;
        if (meals.size() == 1) {
            lunchIdx = meals.get(0);
        } else if (meals.size() == 2) {
            lunchIdx = meals.get(0);
            dinnerIdx = meals.get(1);
        } else if (meals.size() >= 3) {
            breakfastIdx = meals.get(0);
            lunchIdx = meals.get(1);
            dinnerIdx = meals.get(2);
        }
        Set<Integer> mealSet = new HashSet<>(diningAll);

        // 비식사 활동을 시간대 적합성(야외=주간만, Bar=저녁만)을 지키며 오전/오후/저녁 버킷에 배분.
        // 버킷 용량 초과·시간대 부적합으로 못 넣는 활동은 spare 트림.
        List<Integer> activities = orderedMain.stream()
                .filter(idx -> !mealSet.contains(idx))
                .collect(Collectors.toList());
        int[] caps = bucketCaps(cfg, lunchIdx != null, dinnerIdx != null, breakfastIdx != null);
        List<Integer> morning = new ArrayList<>(), afternoon = new ArrayList<>(), evening = new ArrayList<>();
        for (Integer idx : activities) {
            PlaceCandidate actCand = byIndex(candidates, idx);
            boolean placed = switch (timePreference(actCand)) {
                case DAYTIME -> addIfBelowCap(morning, caps[0], idx) || addIfBelowCap(afternoon, caps[1], idx);
                case EVENING -> addIfBelowCap(evening, caps[2], idx);
                case FLEXIBLE -> addIfBelowCap(morning, caps[0], idx)
                        || addIfBelowCap(afternoon, caps[1], idx)
                        || addIfBelowCap(evening, caps[2], idx);
            };
            if (!placed) {
                // 사용자 필수 장소는 버킷 용량에 밀려도 spare로 빼지 않는다 — 오후에 강제 편입
                // (시각은 23:59 클램프가 안전망, 포함이 시간 밀림보다 우선).
                if (actCand != null && actCand.userSelected()) {
                    afternoon.add(idx);
                } else {
                    spareIndices.addLast(idx);
                }
            }
        }

        // 동선 보완: 저녁 버킷이 비면 저녁 식당이 숙소 직전이 되는데, 숙소에 더 가까운 오후 활동이
        // 있으면 그 하나를 저녁(마지막)으로 옮겨 "그날을 숙소 근처에서 마무리"(orderDay orientToAnchors
        // 취지)를 유지한다. 식후 산책 성격이라 자연스럽고 저녁→숙소 왕복 거리를 줄인다.
        endDayNearAccommodation(day, candidates, afternoon, evening, dinnerIdx);
        // 저녁 활동이 숙소-저녁식당 거리보다 확연히 멀면 spare로 — "저녁 먹고 심야 장거리 원정" 방지.
        filterEveningAwayFromAccommodation(day, candidates, evening, dinnerIdx, spareIndices);

        // 최종 시퀀스: 허브 → 아침 → 오전활동 → 점심 → 오후활동 → 저녁 → 저녁활동 → 숙소 → 출발허브
        List<Integer> seq = new ArrayList<>();
        if (day.arrivalHubIndex != null) seq.add(day.arrivalHubIndex);
        if (breakfastIdx != null) seq.add(breakfastIdx);
        seq.addAll(morning);
        if (lunchIdx != null) seq.add(lunchIdx);
        seq.addAll(afternoon);
        if (dinnerIdx != null) seq.add(dinnerIdx);
        seq.addAll(evening);
        if (day.accommodationIndex != null) seq.add(day.accommodationIndex);
        if (day.departureHubIndex != null) seq.add(day.departureHubIndex);

        // 식사 슬롯 재배열로 순서가 orderDay의 경로순과 달라졌으므로, 확정 시퀀스 기준으로
        // 거리 예산을 한 번 더 검사한다(실측: 경로순으로는 통과했지만 재배열 후 149km > 상한).
        // 사용자 필수 장소(userSelected)도 보호 — 거리 위반이어도 포함이 우선.
        Set<Integer> protectedIdx = new HashSet<>();
        if (breakfastIdx != null) protectedIdx.add(breakfastIdx);
        if (lunchIdx != null) protectedIdx.add(lunchIdx);
        if (dinnerIdx != null) protectedIdx.add(dinnerIdx);
        if (day.arrivalHubIndex != null) protectedIdx.add(day.arrivalHubIndex);
        if (day.accommodationIndex != null) protectedIdx.add(day.accommodationIndex);
        if (day.departureHubIndex != null) protectedIdx.add(day.departureHubIndex);
        for (Integer idx : seq) {
            PlaceCandidate c = byIndex(candidates, idx);
            if (c != null && c.userSelected()) protectedIdx.add(idx);
        }
        trimSequenceOverBudget(day, seq, candidates, spareIndices, transportPref, protectedIdx);

        List<StepData> steps = new ArrayList<>();
        int currentMinutes = cfg.morningStart;
        // 하루의 출발지(전날 숙소): 첫 스텝에도 이동정보를 채우고, 첫 장소 도착시각을
        // "09:00 숙소 출발 + 이동시간"으로 잡는다. 없으면 기존처럼 09:00에 첫 장소에서 시작.
        // (walk 연속성 보정으로 숙소가 이미 첫 스텝이면 중복 적용하지 않는다.)
        PlaceCandidate prev = (startFrom != null && !seq.isEmpty() && seq.get(0) != startFrom.index())
                ? startFrom : null;
        int gapFilledCount = 0; // 갭 보충이 pace 상한(maxPerDay)을 다시 깨지 않도록 카운트

        for (int idx : seq) {
            PlaceCandidate cand = byIndex(candidates, idx);
            if (cand == null) continue;

            // 식사 슬롯 직전 빈 시간이 활동 1개 분량 이상이면 spare에서 근처 활동을 삽입해 메운다.
            if ((lunchIdx != null && idx == lunchIdx) || (dinnerIdx != null && idx == dinnerIdx)) {
                int mealStart = (lunchIdx != null && idx == lunchIdx) ? LUNCH_START : DINNER_START;
                GapFill fill = fillGapBeforeMeal(day, candidates, spareIndices, usedIndices,
                        prev, currentMinutes, mealStart, cand, dayCentroid, transportPref,
                        maxPerDay - day.placeIndices.size() - gapFilledCount);
                gapFilledCount += fill.steps().size();
                steps.addAll(fill.steps());
                prev = fill.prev();
                currentMinutes = fill.minutes();
            }

            TransportLeg leg = computeLeg(prev, cand, transportPref, day.dayNumber);
            if (leg != null) currentMinutes += leg.durationMin();

            // 식사 슬롯 고정
            if (breakfastIdx != null && idx == breakfastIdx) currentMinutes = Math.max(currentMinutes, cfg.morningStart);
            if (lunchIdx != null && idx == lunchIdx) currentMinutes = Math.max(currentMinutes, LUNCH_START);
            if (dinnerIdx != null && idx == dinnerIdx) currentMinutes = Math.max(currentMinutes, DINNER_START);

            boolean isMorningDeparture = prev == null && day.arrivalHubIndex == null
                    && PlaceCategoryConstants.isAccommodation(cand.category());
            int visitMinutes;
            if (isMorningDeparture) {
                visitMinutes = MORNING_DEPARTURE_MINUTES;
            } else if (PlaceCategoryConstants.isAccommodation(cand.category())) {
                visitMinutes = ACCOMMODATION_ARRIVAL_MINUTES; // 하루 마무리 체크인 — 90분 잡으면 "23:45 종료"가 됨
            } else if (isDiningCand(cand)) {
                visitMinutes = DINING_VISIT_MINUTES;
            } else {
                visitMinutes = resolveVisitMinutes(cand);
            }
            // 자정 근처 클램프 안전장치: 누적 시각이 23:59에 닿으면 start와 end가 같은 값으로
            // 클램프돼(둘 다 23:59) HardValidator의 endTime>startTime 검증이 깨진다.
            // start는 최소 1분의 체류가 남는 지점까지로 제한해 end>start를 항상 보장한다.
            int startMin = Math.min(currentMinutes, END_OF_DAY_MINUTES - 1);
            if (!steps.isEmpty()) {
                int lastEnd = toMinutesOrZero(steps.get(steps.size() - 1).endTime());
                startMin = Math.max(startMin, Math.min(lastEnd, END_OF_DAY_MINUTES - 1));
            }
            int endMin = Math.min(startMin + visitMinutes, END_OF_DAY_MINUTES);
            if (endMin <= startMin) endMin = startMin + 1;

            steps.add(buildStep(day, cand, startMin, endMin, leg,
                    buildAlternatives(cand, dayCentroid, spareIndices, usedIndices, candidates, transportPref)));

            currentMinutes = Math.max(currentMinutes, endMin);
            prev = cand;
        }
        return steps;
    }

    private int toMinutesOrZero(String hhmm) {
        if (hhmm == null || !hhmm.contains(":")) return 0;
        String[] p = hhmm.split(":");
        try {
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private StepData buildStep(DayState day, PlaceCandidate cand, int startMin, int endMin,
                               TransportLeg leg, List<AlternativeData> alternatives) {
        return new StepData(
                0, // stepOrder는 호출부에서 전체 재번호
                day.dayNumber,
                formatMinutes(startMin),
                formatMinutes(endMin),
                toPlaceData(cand),
                alternatives,
                leg != null ? leg.mode() : null,
                leg != null ? leg.durationMin() : null,
                leg != null ? leg.distanceKm() : null,
                leg != null ? leg.cost() : null,
                null, // notes(story)는 비동기 Haiku 단계에서 채움
                estimateCost(cand)
        );
    }

    private record TransportLeg(String mode, int durationMin, BigDecimal distanceKm, BigDecimal cost) {}

    /**
     * 두 장소 사이 이동 추정. 계산은 {@link GeoUtils#estimateLeg}에 위임하고(대안 선택 재계산
     * 경로 ItineraryService와 동일 공식 공유 — 표시 어긋남 방지), 비현실 구간(200km 초과)은
     * 여기서 로그만 남기고 null 반환한다.
     */
    private TransportLeg computeLeg(PlaceCandidate prev, PlaceCandidate cand, String transportPref, int dayNumber) {
        if (prev == null || cand == null) return null;
        double[] a = coordsOf(prev);
        double[] b = coordsOf(cand);
        if (a == null || b == null) return null;
        GeoUtils.TransportLeg leg = GeoUtils.estimateLeg(a, b, transportPref, MAX_REASONABLE_LEG_KM);
        if (leg == null) {
            log.warn("RouteOptimizer: 비현실적 구간거리 {}km (day={}, {} → {}) — 교통정보 생략",
                    String.format("%.1f", haversine(a, b)), dayNumber, prev.name(), cand.name());
            return null;
        }
        return new TransportLeg(leg.mode(), leg.durationMin(), leg.distanceKm(), leg.cost());
    }

    private boolean addIfBelowCap(List<Integer> bucket, int cap, Integer idx) {
        if (bucket.size() >= cap) return false;
        bucket.add(idx);
        return true;
    }

    /**
     * 관광 스텝 체류시간: enrich 배치가 채운 권장 체류시간(분)이 있으면 30~180으로 클램프해
     * 사용하고, 없으면 카테고리 휴리스틱(등산 150/해변·시장 60/기타 90). 일괄 90분이
     * 등산형 오름(실소요 3h+)과 해변 산책을 구분 못 해 일정 시각 신뢰를 깨던 문제의 해소.
     */
    private int resolveVisitMinutes(PlaceCandidate cand) {
        if (cand == null) return DEFAULT_VISIT_MINUTES;
        Integer recommended = cand.recommendedDurationMinutes();
        if (recommended != null && recommended > 0) {
            return Math.max(30, Math.min(180, recommended));
        }
        return PlaceCategoryConstants.heuristicVisitMinutes(cand.category());
    }

    private enum TimePreference { DAYTIME, EVENING, FLEXIBLE }

    /**
     * 장소의 적합 시간대. enrich 배치가 채운 recommended_time_slots가 있으면 그것을 우선하고,
     * 없으면 카테고리 휴리스틱(야외 관광지=주간, Bar=저녁)으로 판정한다. 야간 골프장(19:24 방문)
     * 같은 시간대 부적합 배치를 막는다.
     */
    private TimePreference timePreference(PlaceCandidate c) {
        if (c == null) return TimePreference.FLEXIBLE;
        List<String> slots = c.recommendedTimeSlots();
        if (slots != null && !slots.isEmpty()) {
            boolean evening = slots.stream().anyMatch(s ->
                    containsAny(s, "저녁", "밤", "야간", "심야", "evening", "night"));
            boolean daytime = slots.stream().anyMatch(s ->
                    containsAny(s, "아침", "오전", "오후", "낮", "주간", "morning", "afternoon", "daytime"));
            if (evening && !daytime) return TimePreference.EVENING;
            if (daytime && !evening) return TimePreference.DAYTIME;
            return TimePreference.FLEXIBLE;
        }
        if (PlaceCategoryConstants.isBar(c.category())) return TimePreference.EVENING;
        if (PlaceCategoryConstants.isDaytimeOutdoor(c.category())) return TimePreference.DAYTIME;
        return TimePreference.FLEXIBLE;
    }

    private boolean containsAny(String s, String... keywords) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        for (String k : keywords) {
            if (lower.contains(k)) return true;
        }
        return false;
    }

    /**
     * 저녁(dinner 이후) 활동이 "숙소-저녁식당 거리 + 허용치"보다 숙소에서 멀면 spare로 뺀다.
     * 실측: 저녁 식사 후 67km(101분)를 운전해 20:21에 카페에 도착하는 일정이 생성됐다 —
     * 저녁 시간대는 숙소로 수렴하는 방향만 허용한다.
     */
    private void filterEveningAwayFromAccommodation(DayState day, List<PlaceCandidate> candidates,
                                                    List<Integer> evening, Integer dinnerIdx,
                                                    Deque<Integer> spareIndices) {
        if (evening.isEmpty() || day.accommodationIndex == null) return;
        double[] hotel = coordsOf(byIndex(candidates, day.accommodationIndex));
        if (hotel == null) return;

        double baseline = EVENING_AWAY_TOLERANCE_KM;
        if (dinnerIdx != null) {
            double[] d = coordsOf(byIndex(candidates, dinnerIdx));
            if (d != null) baseline = Math.max(baseline, haversine(d, hotel));
        }
        double allowed = baseline + EVENING_AWAY_TOLERANCE_KM;

        Iterator<Integer> it = evening.iterator();
        while (it.hasNext()) {
            Integer idx = it.next();
            PlaceCandidate c = byIndex(candidates, idx);
            if (c != null && c.userSelected()) continue; // 사용자 필수 장소는 거리로 빼지 않음
            double[] coord = coordsOf(c);
            if (coord != null && haversine(coord, hotel) > allowed) {
                log.info("저녁 활동이 숙소에서 과도히 멂({}km > {}km) — spare 전환: day={} {}",
                        String.format("%.1f", haversine(coord, hotel)), String.format("%.1f", allowed),
                        day.dayNumber, c != null ? c.name() : idx);
                it.remove();
                spareIndices.addLast(idx);
            }
        }
    }

    private record GapFill(List<StepData> steps, PlaceCandidate prev, int minutes) {}

    /**
     * 식사 슬롯 시작까지의 빈 시간이 활동 1개 분량({@link #ACTIVITY_SLOT_MINUTES}) 이상이면
     * spare에서 경로(현위치→식당)에서 크게 벗어나지 않는 주간 적합 활동(관광지/카페)을 골라
     * 사이에 삽입한다. 실측: 식사 슬롯 고정 점프 때문에 2.8~4.8시간 공백이 그대로 노출됐다.
     * 채울 후보가 없으면 갭을 그대로 둔다(프론트가 자유시간으로 표시).
     */
    private GapFill fillGapBeforeMeal(DayState day, List<PlaceCandidate> candidates,
                                      Deque<Integer> spareIndices, Set<Integer> usedIndices,
                                      PlaceCandidate prev, int currentMinutes, int mealStart,
                                      PlaceCandidate mealCand, double[] dayCentroid, String transportPref,
                                      int remainingQuota) {
        List<StepData> inserted = new ArrayList<>();
        int guard = 0;
        while (guard++ < 3 && inserted.size() < remainingQuota) {
            TransportLeg toMeal = computeLeg(prev, mealCand, transportPref, day.dayNumber);
            int arrivalAtMeal = currentMinutes + (toMeal != null ? toMeal.durationMin() : 0);
            if (mealStart - arrivalAtMeal < GAP_FILL_THRESHOLD_MINUTES) break;

            Integer fillIdx = pickGapFiller(prev, mealCand, candidates, spareIndices, usedIndices, transportPref);
            if (fillIdx == null) break;
            spareIndices.remove(fillIdx);
            usedIndices.add(fillIdx);

            PlaceCandidate fill = byIndex(candidates, fillIdx);
            TransportLeg leg = computeLeg(prev, fill, transportPref, day.dayNumber);
            if (leg != null) currentMinutes += leg.durationMin();
            int startMin = currentMinutes;
            int endMin = startMin + resolveVisitMinutes(fill);
            inserted.add(buildStep(day, fill, startMin, endMin, leg,
                    buildAlternatives(fill, dayCentroid, spareIndices, usedIndices, candidates, transportPref)));
            log.info("빈 시간대 보충: day={} {} 삽입 (식사 슬롯까지 {}분 공백)",
                    day.dayNumber, fill.name(), mealStart - arrivalAtMeal);
            currentMinutes = endMin;
            prev = fill;
        }
        return new GapFill(inserted, prev, currentMinutes);
    }

    /** 갭 보충 후보: 관광지/카페, 주간 적합, 경로 우회(현위치→후보→식당)가 최소인 spare. */
    private Integer pickGapFiller(PlaceCandidate prev, PlaceCandidate mealCand,
                                  List<PlaceCandidate> candidates, Deque<Integer> spareIndices,
                                  Set<Integer> usedIndices, String transportPref) {
        double[] from = prev != null ? coordsOf(prev) : null;
        double[] to = coordsOf(mealCand);
        double radius = altRadiusKm(transportPref);
        return spareIndices.stream()
                .filter(i -> !usedIndices.contains(i))
                .map(i -> byIndex(candidates, i))
                .filter(Objects::nonNull)
                .filter(c -> {
                    String major = PlaceCategoryConstants.majorCategory(c.category());
                    return "ATTRACTION".equals(major) || "CAFE".equals(major);
                })
                .filter(c -> timePreference(c) != TimePreference.EVENING)
                .filter(c -> coordsOf(c) != null)
                .filter(c -> from == null || haversine(from, coordsOf(c)) <= radius)
                .min(Comparator.comparingDouble(c -> detourVia(from, coordsOf(c), to)))
                .map(PlaceCandidate::index)
                .orElse(null);
    }

    /** from→via→to 경유 거리(양끝 null 허용). via가 null이면 최댓값(선택 배제). */
    private double detourVia(double[] from, double[] via, double[] to) {
        if (via == null) return Double.MAX_VALUE;
        double d = 0;
        if (from != null) d += haversine(from, via);
        if (to != null) d += haversine(via, to);
        return d;
    }

    /**
     * 절대 거리 상한 강제: 단일 구간이 legCap을 넘거나 일일 총주행이 dayCap을 넘으면 우회 기여가
     * 가장 큰 장소를 같은 대분류의 가까운 spare로 교체하고, 교체 불가면 제거한다(그날의 유일한
     * 식사는 제거하지 않음). 상대 임계값(repairDistanceOutliers)은 day 중심 평균거리 배수라
     * 이미 흩어진 day일수록 허용치가 커져 무력화되는 문제(실측: 하루 190km)를 보완한다.
     */
    private List<Integer> enforceDistanceBudget(DayState day, List<Integer> ordered,
                                                List<PlaceCandidate> candidates, Deque<Integer> spare,
                                                Set<Integer> usedIndices, String transportPref) {
        double[] limits = TRANSPORT_ABS_LIMITS.getOrDefault(transportPref, TRANSPORT_ABS_LIMITS.get("any"));
        double legCap = limits[0];
        double dayCap = limits[1];
        List<Integer> result = new ArrayList<>(ordered);

        // 사용자 필수 장소는 거리 예산 위반이어도 제거/교체하지 않는다(포함 > 동선 — 사용자 결정
        // 존중). 초과분은 프론트 이동요약 배지가 사실대로 표시한다.
        Set<Integer> protectedIdx = ordered.stream()
                .filter(i -> {
                    PlaceCandidate c = byIndex(candidates, i);
                    return c != null && c.userSelected();
                })
                .collect(Collectors.toSet());

        for (int guard = 0; guard < 3 && result.size() >= 2; guard++) {
            int worstPos = worstDetourPosition(result, candidates, legCap, dayCap, protectedIdx);
            if (worstPos < 0) break;
            Integer idx = result.get(worstPos);
            // applyWalkContinuity가 prepend한 전날 숙소(placeIndices 밖 anchor)는 건드리지
            // 않는다 — 제거하면 usedIndices/spare 정합성이 깨지고 quota가 부풀 수 있다.
            if (!day.placeIndices.contains(idx)) break;
            PlaceCandidate cur = byIndex(candidates, idx);

            Integer swap = pickBudgetSwap(result, worstPos, candidates, spare, usedIndices);
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
     * 확정 시퀀스(식사 재배열 후)가 거리 예산(단일 구간/일일 총주행)을 초과하면, 보호 대상
     * (식사·허브·숙소)이 아닌 활동 중 우회 기여가 가장 큰 것을 spare로 트림한다.
     * usedIndices에서는 빼지 않는다 — 같은 실행의 갭 필/대안이 방금 트림한 원거리 장소를
     * 다시 끌어오지 않게 하기 위함(멀어서 뺐는데 대안으로 재등장하면 모순).
     */
    private void trimSequenceOverBudget(DayState day, List<Integer> seq, List<PlaceCandidate> candidates,
                                        Deque<Integer> spareIndices, String transportPref,
                                        Set<Integer> protectedIdx) {
        double[] limits = TRANSPORT_ABS_LIMITS.getOrDefault(transportPref, TRANSPORT_ABS_LIMITS.get("any"));
        double legCap = limits[0];
        double dayCap = limits[1];

        for (int guard = 0; guard < 2 && seq.size() > 1; guard++) {
            List<double[]> coords = seq.stream()
                    .map(i -> coordsOf(byIndex(candidates, i)))
                    .collect(Collectors.toList());
            double total = 0;
            double worstLeg = 0;
            for (int i = 1; i < coords.size(); i++) {
                double[] a = coords.get(i - 1);
                double[] b = coords.get(i);
                if (a == null || b == null) continue;
                double d = haversine(a, b);
                total += d;
                worstLeg = Math.max(worstLeg, d);
            }
            if (total <= dayCap && worstLeg <= legCap) break;

            int pos = -1;
            double bestGain = 0;
            for (int i = 0; i < seq.size(); i++) {
                if (protectedIdx.contains(seq.get(i))) continue;
                double gain = removalGain(coords, i);
                if (gain > bestGain) {
                    bestGain = gain;
                    pos = i;
                }
            }
            if (pos < 0) break; // 트림 가능한 활동 없음(전부 보호 대상) — 구조 유지 우선

            Integer idx = seq.remove(pos);
            day.placeIndices.remove(idx);
            spareIndices.addLast(idx);
            PlaceCandidate c = byIndex(candidates, idx);
            log.info("확정 시퀀스 거리 예산 초과({}km/{}km) — 트림: day={} {}",
                    String.format("%.0f", total), String.format("%.0f", dayCap),
                    day.dayNumber, c != null ? c.name() : idx);
        }
    }

    /**
     * 상한 위반이 없으면 -1, 있으면 제거 시 총거리 감소가 가장 큰 위치를 반환.
     * protectedIdx(사용자 필수 장소 등)는 제거 후보에서 제외한다.
     */
    private int worstDetourPosition(List<Integer> order, List<PlaceCandidate> candidates,
                                    double legCap, double dayCap, Set<Integer> protectedIdx) {
        List<double[]> coords = order.stream()
                .map(i -> coordsOf(byIndex(candidates, i)))
                .collect(Collectors.toList());
        double total = 0;
        double worstLeg = 0;
        for (int i = 1; i < coords.size(); i++) {
            double[] a = coords.get(i - 1);
            double[] b = coords.get(i);
            if (a == null || b == null) continue;
            double d = haversine(a, b);
            total += d;
            worstLeg = Math.max(worstLeg, d);
        }
        if (total <= dayCap && worstLeg <= legCap) return -1;

        int worstPos = -1;
        double worstGain = 0;
        for (int i = 0; i < coords.size(); i++) {
            if (protectedIdx.contains(order.get(i))) continue;
            double gain = removalGain(coords, i);
            if (gain > worstGain) {
                worstGain = gain;
                worstPos = i;
            }
        }
        return worstPos;
    }

    /** i번째 장소를 경로에서 뺄 때 줄어드는 거리. */
    private double removalGain(List<double[]> coords, int i) {
        double[] me = coords.get(i);
        if (me == null) return 0;
        double[] prev = i > 0 ? coords.get(i - 1) : null;
        double[] next = i < coords.size() - 1 ? coords.get(i + 1) : null;
        double gain = 0;
        if (prev != null) gain += haversine(prev, me);
        if (next != null) gain += haversine(me, next);
        if (prev != null && next != null) gain -= haversine(prev, next);
        return gain;
    }

    /** 같은 대분류 spare 중 이웃 경유 거리가 현재의 절반 미만으로 줄어드는 가장 가까운 후보. */
    private Integer pickBudgetSwap(List<Integer> order, int pos, List<PlaceCandidate> candidates,
                                   Deque<Integer> spare, Set<Integer> usedIndices) {
        PlaceCandidate cur = byIndex(candidates, order.get(pos));
        if (cur == null) return null;
        String major = PlaceCategoryConstants.majorCategory(cur.category());
        double[] prev = pos > 0 ? coordsOf(byIndex(candidates, order.get(pos - 1))) : null;
        double[] next = pos < order.size() - 1 ? coordsOf(byIndex(candidates, order.get(pos + 1))) : null;
        if (prev == null && next == null) return null;
        double currentDetour = detourVia(prev, coordsOf(cur), next);

        return spare.stream()
                .filter(i -> !usedIndices.contains(i))
                .map(i -> byIndex(candidates, i))
                .filter(Objects::nonNull)
                .filter(this::isVisitableFill)
                .filter(c -> major.equals(PlaceCategoryConstants.majorCategory(c.category())))
                .filter(c -> coordsOf(c) != null)
                .filter(c -> detourVia(prev, coordsOf(c), next) < currentDetour * 0.5)
                .min(Comparator.comparingDouble(c -> detourVia(prev, coordsOf(c), next)))
                .map(PlaceCandidate::index)
                .orElse(null);
    }

    /** 그날의 유일한 식사(DINING)는 거리 때문에 제거하지 않는다. */
    private boolean isRemovableForBudget(Integer idx, List<Integer> order, List<PlaceCandidate> candidates) {
        PlaceCandidate c = byIndex(candidates, idx);
        if (c == null || !isDiningCand(c)) return true;
        long dining = order.stream()
                .map(i -> byIndex(candidates, i))
                .filter(this::isDiningCand)
                .count();
        return dining > 1;
    }

    private void replaceInDay(DayState day, Integer from, Integer to) {
        int i = day.placeIndices.indexOf(from);
        if (i >= 0) day.placeIndices.set(i, to);
        else day.placeIndices.add(to);
    }

    private boolean isDiningCand(PlaceCandidate c) {
        return c != null && "DINING".equals(PlaceCategoryConstants.majorCategory(c.category()));
    }

    /**
     * 하루 식사 3회 상한 적용. 4개 초과분은 spare로 트림하되, 사용자 필수 식당(userSelected)을
     * 우선 보존한다(보존 목록 내에서는 경로 순서 유지 — 아침/점심/저녁 슬롯 매핑이 경로순 기반).
     */
    private List<Integer> selectMeals(List<Integer> diningAll, List<PlaceCandidate> candidates,
                                      Deque<Integer> spareIndices) {
        if (diningAll.size() <= 3) return new ArrayList<>(diningAll);

        Set<Integer> keep = new LinkedHashSet<>();
        for (Integer idx : diningAll) {
            PlaceCandidate c = byIndex(candidates, idx);
            if (c != null && c.userSelected() && keep.size() < 3) keep.add(idx);
        }
        for (Integer idx : diningAll) {
            if (keep.size() >= 3) break;
            keep.add(idx);
        }

        List<Integer> meals = new ArrayList<>();
        for (Integer idx : diningAll) {
            if (keep.contains(idx)) meals.add(idx);
            else spareIndices.addLast(idx);
        }
        return meals;
    }

    /**
     * 식사가 2개 이상이고 그날 숙소 좌표가 유효하면, 숙소에 가장 가까운 식당을 마지막(=저녁)으로
     * 재배치한다. 좌표 불명이거나 이미 마지막이면 무변경.
     */
    private List<Integer> preferDinnerNearAccommodation(List<Integer> meals, DayState day,
                                                        List<PlaceCandidate> candidates) {
        // 2식일 때만 재배치한다. 3식([아침,점심,저녁])에서 아침 성격 식당이 숙소 최근접이라고
        // 저녁으로 밀면 시간대가 어긋나므로 손대지 않는다.
        if (meals.size() != 2 || day.accommodationIndex == null) return meals;
        double[] hotel = coordsOf(byIndex(candidates, day.accommodationIndex));
        if (hotel == null) return meals;

        Integer nearest = null;
        double best = Double.MAX_VALUE;
        for (Integer m : meals) {
            double[] c = coordsOf(byIndex(candidates, m));
            if (c == null) continue;
            double d = haversine(c, hotel);
            if (d < best) {
                best = d;
                nearest = m;
            }
        }
        if (nearest == null || nearest.equals(meals.get(meals.size() - 1))) return meals;

        List<Integer> reordered = new ArrayList<>(meals);
        reordered.remove(nearest);
        reordered.add(nearest);
        return reordered;
    }

    /**
     * 저녁 활동이 없어 저녁 식당이 숙소 직전이 될 때, 숙소에 더 가까운 오후 활동 하나를 저녁으로
     * 옮겨 그날의 마지막 스텝이 숙소 근처가 되게 한다(저녁→숙소 이동거리 단축). 조건 미충족 시 무동작.
     */
    private void endDayNearAccommodation(DayState day, List<PlaceCandidate> candidates,
                                         List<Integer> afternoon, List<Integer> evening, Integer dinnerIdx) {
        if (!evening.isEmpty() || dinnerIdx == null || day.accommodationIndex == null || afternoon.isEmpty()) return;
        double[] hotel = coordsOf(byIndex(candidates, day.accommodationIndex));
        double[] dinnerCoord = coordsOf(byIndex(candidates, dinnerIdx));
        if (hotel == null || dinnerCoord == null) return;

        Integer nearest = null;
        double best = haversine(dinnerCoord, hotel); // 저녁 식당보다 숙소에 더 가까운 오후 활동만 대상
        for (Integer idx : afternoon) {
            // 야외/주간 성격 장소(해변·골프 등)는 저녁으로 옮기지 않는다 — 버킷 배분의
            // 시간대 적합성 가드를 이 이동이 우회하는 실측 사례(해변 19시대 배치)가 있었다.
            if (timePreference(byIndex(candidates, idx)) == TimePreference.DAYTIME) continue;
            double[] c = coordsOf(byIndex(candidates, idx));
            if (c == null) continue;
            double d = haversine(c, hotel);
            if (d < best) { best = d; nearest = idx; }
        }
        if (nearest != null) {
            afternoon.remove(nearest);
            evening.add(nearest);
        }
    }

    /**
     * 오전/오후/저녁 시간창에 담을 수 있는 활동 개수 상한. 활동 1개 ≈ 체류+이동
     * {@link #ACTIVITY_SLOT_MINUTES}분으로 잡는다. 테마 상한(cfg.eveningCap)이 넓으면 저녁이 늘어난다.
     */
    private int[] bucketCaps(ScheduleConfig cfg, boolean hasLunch, boolean hasDinner, boolean hasBreakfast) {
        int slot = ACTIVITY_SLOT_MINUTES;
        int morningStart = cfg.morningStart + (hasBreakfast ? DINING_VISIT_MINUTES : 0);
        if (hasLunch && hasDinner) {
            int capM = Math.max(0, (LUNCH_START - morningStart) / slot);
            int capA = Math.max(1, (DINNER_START - (LUNCH_START + DINING_VISIT_MINUTES)) / slot);
            int capE = Math.max(0, (cfg.eveningCap - (DINNER_START + DINING_VISIT_MINUTES)) / slot);
            return new int[]{capM, capA, capE};
        } else if (hasLunch) { // 저녁 없음 → 오후가 상한까지 확장
            int capM = Math.max(0, (LUNCH_START - morningStart) / slot);
            int capA = Math.max(1, (cfg.eveningCap - (LUNCH_START + DINING_VISIT_MINUTES)) / slot);
            return new int[]{capM, capA, 0};
        } else { // 식사 없음 → 하루 전체를 한 버킷으로
            int capAll = Math.max(1, (cfg.eveningCap - morningStart) / slot);
            return new int[]{capAll, 0, 0};
        }
    }

    /**
     * 하루 일과 용량(테마 반영 시간창)을 초과하는 활동을 인접 day로 이월한다(A-1). quota 여유가
     * 있는 다음/이전 day로 옮기고, 옮길 곳이 없으면 그대로 두어 scheduleDay가 저녁 상한에서 트림한다.
     */
    private void repairDayCapacity(List<DayState> days, List<PlaceCandidate> candidates,
                                   ScheduleConfig cfg, int maxPerDay) {
        for (int i = 0; i < days.size(); i++) {
            DayState day = days.get(i);
            List<Integer> activities = day.placeIndices.stream()
                    .filter(idx -> !isDiningCand(byIndex(candidates, idx)))
                    .collect(Collectors.toList());
            int excess = activities.size() - totalActivityCapacity(day, candidates, cfg);
            if (excess <= 0) continue;

            List<Integer> toMove = new ArrayList<>(activities.subList(activities.size() - excess, activities.size()));
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

    private int totalActivityCapacity(DayState day, List<PlaceCandidate> candidates, ScheduleConfig cfg) {
        long dining = day.placeIndices.stream().filter(idx -> isDiningCand(byIndex(candidates, idx))).count();
        int[] caps = bucketCaps(cfg, dining >= 1, dining >= 2, dining >= 3);
        return caps[0] + caps[1] + caps[2];
    }

    /** 테마 기반 하루 시작/저녁 상한 설정. */
    private ScheduleConfig scheduleConfig(List<String> themes) {
        String joined = themes == null ? "" : String.join(" ", themes).toLowerCase();
        int morningStart = EARLY_THEME_KEYWORDS.stream().anyMatch(joined::contains)
                ? EARLY_MORNING_START_MINUTES : DAY_START_MINUTES;
        int eveningCap = NIGHT_THEME_KEYWORDS.stream().anyMatch(joined::contains)
                ? LATE_EVENING_CAP_MINUTES : DEFAULT_EVENING_CAP_MINUTES;
        return new ScheduleConfig(morningStart, eveningCap);
    }

    private static final class ScheduleConfig {
        final int morningStart;
        final int eveningCap;
        ScheduleConfig(int morningStart, int eveningCap) {
            this.morningStart = morningStart;
            this.eveningCap = eveningCap;
        }
    }

    /**
     * 메인 스텝의 대안 3개를 만든다(C 재설계). "같은 종류·주변·중복 없이" 원칙:
     * <ul>
     *   <li><b>같은 종류</b>: 대분류 일치 필수 + 세부 카테고리/tags 겹침 가점(버거집↔맥주바 방지).</li>
     *   <li><b>주변</b>: 메인 좌표(없으면 그날 centroid) 기준 이동수단별 반경 내만. 반경 밖 제외.</li>
     *   <li><b>중복 없이</b>: 이미 일정에 쓰인 장소 제외 + placeId/좌표 키로 대안끼리 dedup.</li>
     * </ul>
     * 소스는 Sonnet이 큐레이션한 spare를 우선(가점)하고, 부족하면 미사용 후보에서 채운다.
     */
    private List<AlternativeData> buildAlternatives(PlaceCandidate main, double[] dayCentroid,
                                                    Deque<Integer> spareIndices, Set<Integer> usedIndices,
                                                    List<PlaceCandidate> allCandidates, String transportPref) {
        String targetCat = PlaceCategoryConstants.majorCategory(main.category());
        double[] anchor = coordsOf(main) != null ? coordsOf(main) : dayCentroid;
        double radiusKm = altRadiusKm(transportPref);
        Set<Integer> spareSet = new HashSet<>(spareIndices);

        // 같은 대분류 · 미사용 · 자기 자신 아님. 일정에 이미 쓰인 장소는 spare에 중복 기재돼
        // 있어도 대안에서 제외한다(대안 선택 시 같은 곳을 2회 방문하게 되는 실측 버그).
        List<PlaceCandidate> base = allCandidates.stream()
                .filter(c -> c.index() != main.index())
                .filter(c -> !usedIndices.contains(c.index()))
                .filter(c -> targetCat.equals(PlaceCategoryConstants.majorCategory(c.category())))
                .collect(Collectors.toList());

        Set<String> seen = new HashSet<>();
        seen.add(altKey(main)); // 메인과 동일 장소는 제외
        List<AlternativeData> result = new ArrayList<>();

        // 1차: 반경 이내(주변 우선). 2차: 3개 미만이면 반경×2까지만 완화 — 그래도 못 채우면
        // 대안 1~2개로 정직하게 둔다(반경 무제한 폴백은 60km 밖 식당이 대안으로 나오는 원인이었음).
        addAlternatives(result, seen, base.stream()
                .filter(c -> withinRadius(anchor, c, radiusKm)).collect(Collectors.toList()),
                main, anchor, spareSet);
        if (result.size() < 3) {
            addAlternatives(result, seen, base.stream()
                    .filter(c -> withinRadius(anchor, c, radiusKm * 2)).collect(Collectors.toList()),
                    main, anchor, spareSet);
        }
        return result;
    }

    /** score 내림차순으로 dedup하며 최대 3개까지 result에 채운다. */
    private void addAlternatives(List<AlternativeData> result, Set<String> seen, List<PlaceCandidate> pool,
                                 PlaceCandidate main, double[] anchor, Set<Integer> spareSet) {
        pool.stream()
                .sorted(Comparator.comparingDouble(c -> -altScore(c, main, anchor, spareSet)))
                .forEach(c -> {
                    if (result.size() >= 3 || !seen.add(altKey(c))) return;
                    result.add(new AlternativeData(c.name(), c.address(), c.category(), c.region(),
                            c.country(), null, estimateCost(c)));
                });
    }

    /** 이동수단별 대안 허용 반경. walk는 좁게, car는 넓게. */
    private double altRadiusKm(String transportPref) {
        if ("walk".equals(transportPref)) return 3.0;
        if ("car".equals(transportPref)) return 30.0;
        return 15.0;
    }

    /** anchor 판단 불가면 보존, 후보 좌표 없으면 근접 판정 불가로 제외("주변만" 원칙). */
    private boolean withinRadius(double[] anchor, PlaceCandidate c, double radiusKm) {
        if (anchor == null) return true;
        double[] cc = coordsOf(c);
        if (cc == null) return false;
        return haversine(anchor, cc) <= radiusKm;
    }

    /** 대안 점수(높을수록 우선): spare 큐레이션 > 세부 카테고리/tags 유사 > rating > 근접. */
    private double altScore(PlaceCandidate c, PlaceCandidate main, double[] anchor, Set<Integer> spareSet) {
        double score = 0;
        if (spareSet.contains(c.index())) score += 100;
        score += subCategorySimilarity(c, main) * 20;
        if (c.rating() != null) score += c.rating().doubleValue();
        double[] cc = coordsOf(c);
        if (anchor != null && cc != null) score -= haversine(anchor, cc) * 0.5;
        return score;
    }

    private double subCategorySimilarity(PlaceCandidate a, PlaceCandidate b) {
        double s = 0;
        String la = leafCategory(a.category());
        String lb = leafCategory(b.category());
        if (la != null && la.equalsIgnoreCase(lb)) s += 1.0;
        if (a.tags() != null && b.tags() != null) {
            Set<String> shared = new HashSet<>(a.tags());
            shared.retainAll(new HashSet<>(b.tags()));
            s += Math.min(2, shared.size()) * 0.5;
        }
        return s;
    }

    private String leafCategory(String cat) {
        if (cat == null) return null;
        int i = cat.lastIndexOf('>');
        return (i >= 0 ? cat.substring(i + 1) : cat).trim();
    }

    /** 대안 dedup 키: placeId 우선, 없으면 유효좌표(4자리), 없으면 정규화 이름. */
    private String altKey(PlaceCandidate c) {
        if (c.placeId() != null) return "id:" + c.placeId();
        double[] cc = coordsOf(c);
        if (cc != null) return "geo:" + Math.round(cc[0] * 10000) + "|" + Math.round(cc[1] * 10000);
        return "name:" + (c.name() == null ? "" : c.name().toLowerCase().replaceAll("\\s+", ""));
    }

    // priceLevel(1~4) 단가는 카테고리별로 다르다. 기존 "priceLevel × 15,000원 일괄"은 카페
    // 30,000원, 우물 입장료 30,000원 같은 왜곡을 만들었다(실측). priceLevel이 없을 때(흔함 —
    // 특히 소규모 식당/카페)는 카테고리 기본값을 쓴다. "0원"은 식당·카페·숙소에선 사실상 항상
    // 틀린 값이라(공짜 숙박·식당은 없음) 추정치를 넣는 게 예산 감에 더 정직하다.
    private static final long DINING_WON_PER_PRICE_LEVEL = 12000L;
    private static final long CAFE_WON_PER_PRICE_LEVEL = 5000L;
    private static final long PAID_ATTRACTION_DEFAULT_WON = 10000L;
    private static final long DINING_DEFAULT_WON = 13000L;
    private static final long CAFE_DEFAULT_WON = 6000L;
    // 숙소 1박(1실) 추정가. priceLevel(1~4)이 있으면 등급×단가, 없으면 숙소 유형별 기본값.
    // 숙소는 여행 예산의 최대 항목이라 0원으로 두면 예산 표시가 무의미해진다(실측: 3박 0원).
    private static final long LODGING_WON_PER_PRICE_LEVEL = 60000L; // pl1=6만 ~ pl4=24만
    private static final long LODGING_HOSTEL_DEFAULT_WON = 50000L;
    private static final long LODGING_RESORT_DEFAULT_WON = 200000L;
    private static final long LODGING_HOTEL_DEFAULT_WON = 120000L;  // 그 외 숙소 기본

    private BigDecimal estimateCost(PlaceCandidate c) {
        String category = PlaceCategoryConstants.majorCategory(c.category());
        Integer pl = c.priceLevel();
        return switch (category) {
            case "DINING" -> BigDecimal.valueOf(pl != null ? pl * DINING_WON_PER_PRICE_LEVEL : DINING_DEFAULT_WON);
            case "CAFE" -> BigDecimal.valueOf(pl != null ? pl * CAFE_WON_PER_PRICE_LEVEL : CAFE_DEFAULT_WON);
            // 관광지 입장료: enrich가 채운 admissionFee 우선(무료=0, 유료=실제 입장료). 없으면
            // priceLevel을 "유료 시설" 신호로만 쓴다(대부분 자연 명소는 무료라 0이 기본).
            case "ATTRACTION" -> BigDecimal.valueOf(c.admissionFee() != null
                    ? c.admissionFee() : (pl != null ? PAID_ATTRACTION_DEFAULT_WON : 0L));
            case "LODGING" -> BigDecimal.valueOf(estimateLodgingCost(c.category(), pl));
            default -> BigDecimal.ZERO; // TRANSIT_HUB/OTHER는 스텝 비용 개념 없음
        };
    }

    /** 숙소 1박 추정가: priceLevel 우선, 없으면 유형(hostel/resort/그 외)별 기본값. */
    private long estimateLodgingCost(String category, Integer priceLevel) {
        if (priceLevel != null) return priceLevel * LODGING_WON_PER_PRICE_LEVEL;
        String lower = category == null ? "" : category.toLowerCase();
        if (lower.contains("hostel") || lower.contains("guest")) return LODGING_HOSTEL_DEFAULT_WON;
        if (lower.contains("resort") || lower.contains("pool villa") || lower.contains("villa")) {
            return LODGING_RESORT_DEFAULT_WON;
        }
        return LODGING_HOTEL_DEFAULT_WON;
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
        // fallback Place(Google API 실패)는 좌표 (0,0) — 유효하지 않으므로 제외.
        // resolveCoords(PlaceData)와 동일 정책. 이 메서드를 쓰는 scheduleDay/centroidOf/
        // buildAlternatives/anchor 계산 전반에서 (0,0)이 거리계산에 끼어드는 것을 막는다.
        if (c.latitude().compareTo(BigDecimal.ZERO) == 0 && c.longitude().compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
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
        // 누적 시각이 자정을 넘으면 %24 wrap으로 26:16→02:16처럼 시간이 역행해 보인다.
        // 일과 종료(23:59)로 클램프해 같은 날 안에서 시각이 단조증가하도록 보장한다.
        int clamped = minutes;
        if (clamped > END_OF_DAY_MINUTES) {
            log.warn("RouteOptimizer: 일과 시간 초과({}분) — {}로 클램프", minutes, "23:59");
            clamped = END_OF_DAY_MINUTES;
        }
        int h = clamped / 60;
        int m = clamped % 60;
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
