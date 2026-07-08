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
            changed |= repairClusterSplit(days, candidates, maxPerDay, distanceMultiplier, pairs);
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

        // Sonnet이 오분류 데이터(예: "흰여울문화마을=Bus Station")로 지정한 허브를 일반 방문지로
        // 강등한 뒤 repairHubs가 진짜 허브로 다시 채우도록 한다(A-0-3).
        sanitizeSuspiciousHubs(days, candidates);
        repairHubs(days, candidates);
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
            double[] dayCentroid = centroidOf(day.placeIndices, candidates);
            steps.addAll(scheduleDay(day, ordered, candidates, spare, cfg,
                    dayCentroid, usedIndices, transportPref));
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
                .filter(c -> coordsOf(c) != null) // (0,0)/좌표 불명 후보는 거리 비교 불가 — 제외
                .min(Comparator.comparingDouble(c -> haversine(centroid, coordsOf(c))))
                .map(PlaceCandidate::index)
                .orElse(spare.peekFirst());
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
                // 이름에 실제 허브 신호(공항/역/터미널/항구)가 있는 것만 — "흰여울문화마을"이
                // "Bus Station"으로 오적재돼 도착 허브로 쓰이는 사고 방지(A-0-3).
                .filter(c -> PlaceCategoryConstants.hasTransitNameSignal(c.name()))
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
                                        Set<Integer> usedIndices, String transportPref) {
        // 식사 식별(순서 유지). 4번째+ DINING은 트림 → spare.
        List<Integer> diningAll = orderedMain.stream()
                .filter(idx -> isDiningCand(byIndex(candidates, idx)))
                .collect(Collectors.toList());
        List<Integer> meals = diningAll.size() > 3 ? new ArrayList<>(diningAll.subList(0, 3)) : diningAll;
        for (int k = 3; k < diningAll.size(); k++) spareIndices.addLast(diningAll.get(k));

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

        // 비식사 활동을 오전/오후/저녁 버킷에 순서대로 배분(용량 초과는 spare 트림).
        List<Integer> activities = orderedMain.stream()
                .filter(idx -> !mealSet.contains(idx))
                .collect(Collectors.toList());
        int[] caps = bucketCaps(cfg, lunchIdx != null, dinnerIdx != null, breakfastIdx != null);
        List<Integer> morning = new ArrayList<>(), afternoon = new ArrayList<>(), evening = new ArrayList<>();
        for (Integer idx : activities) {
            if (morning.size() < caps[0]) morning.add(idx);
            else if (afternoon.size() < caps[1]) afternoon.add(idx);
            else if (evening.size() < caps[2]) evening.add(idx);
            else spareIndices.addLast(idx);
        }

        // 동선 보완: 저녁 버킷이 비면 저녁 식당이 숙소 직전이 되는데, 숙소에 더 가까운 오후 활동이
        // 있으면 그 하나를 저녁(마지막)으로 옮겨 "그날을 숙소 근처에서 마무리"(orderDay orientToAnchors
        // 취지)를 유지한다. 식후 산책 성격이라 자연스럽고 저녁→숙소 왕복 거리를 줄인다.
        endDayNearAccommodation(day, candidates, afternoon, evening, dinnerIdx);

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

        List<StepData> steps = new ArrayList<>();
        int currentMinutes = cfg.morningStart;
        PlaceCandidate prev = null;

        for (int idx : seq) {
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
                    if (dist > MAX_REASONABLE_LEG_KM) {
                        log.warn("RouteOptimizer: 비현실적 구간거리 {}km (day={}, {} → {}) — 교통정보 생략",
                                String.format("%.1f", dist), day.dayNumber, prev.name(), cand.name());
                    } else {
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
            }

            // 식사 슬롯 고정
            if (breakfastIdx != null && idx == breakfastIdx) currentMinutes = Math.max(currentMinutes, cfg.morningStart);
            if (lunchIdx != null && idx == lunchIdx) currentMinutes = Math.max(currentMinutes, LUNCH_START);
            if (dinnerIdx != null && idx == dinnerIdx) currentMinutes = Math.max(currentMinutes, DINNER_START);

            boolean isMorningDeparture = prev == null && day.arrivalHubIndex == null
                    && PlaceCategoryConstants.isAccommodation(cand.category());
            int visitMinutes = isMorningDeparture ? MORNING_DEPARTURE_MINUTES
                    : isDiningCand(cand) ? DINING_VISIT_MINUTES : DEFAULT_VISIT_MINUTES;
            int startMin = currentMinutes;
            int endMin = startMin + visitMinutes;

            steps.add(new StepData(
                    0, // stepOrder는 호출부에서 전체 재번호
                    day.dayNumber,
                    formatMinutes(startMin),
                    formatMinutes(endMin),
                    toPlaceData(cand),
                    buildAlternatives(cand, dayCentroid, spareIndices, usedIndices, candidates, transportPref),
                    mode, durationMin, distanceKm, cost,
                    null, // notes(story)는 비동기 Haiku 단계에서 채움
                    estimateCost(cand)
            ));

            currentMinutes = endMin;
            prev = cand;
        }
        return steps;
    }

    private boolean isDiningCand(PlaceCandidate c) {
        return c != null && "DINING".equals(PlaceCategoryConstants.majorCategory(c.category()));
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

        // 같은 대분류 · 미사용(또는 spare) · 자기 자신 아님
        List<PlaceCandidate> base = allCandidates.stream()
                .filter(c -> c.index() != main.index())
                .filter(c -> spareSet.contains(c.index()) || !usedIndices.contains(c.index()))
                .filter(c -> targetCat.equals(PlaceCategoryConstants.majorCategory(c.category())))
                .collect(Collectors.toList());

        Set<String> seen = new HashSet<>();
        seen.add(altKey(main)); // 메인과 동일 장소는 제외
        List<AlternativeData> result = new ArrayList<>();

        // 1차: 반경 이내(주변 우선). 2차: 3개 미만이면 반경 밖·무좌표까지 채운다("대안 없음" 회피).
        addAlternatives(result, seen, base.stream()
                .filter(c -> withinRadius(anchor, c, radiusKm)).collect(Collectors.toList()),
                main, anchor, spareSet);
        if (result.size() < 3) {
            addAlternatives(result, seen, base, main, anchor, spareSet);
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
