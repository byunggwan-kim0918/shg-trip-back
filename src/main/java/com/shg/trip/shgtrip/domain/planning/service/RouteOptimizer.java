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

import static com.shg.trip.shgtrip.domain.planning.service.InvariantChecker.departsAfterDinner;
import static com.shg.trip.shgtrip.domain.planning.service.InvariantChecker.hasMealInWindow;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.byIndex;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.formatMinutes;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.toMinutesOrZero;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.*;

/**
 * 같은 날 장소들을 좌표 기반 Nearest Neighbor 알고리즘으로 재정렬.
 * AI가 생성한 일정의 동선 효율성을 후처리로 보정한다.
 */
@Slf4j
@Component
public class RouteOptimizer {

    /** 판정은 검사기에 맡긴다 — 이 클래스는 고치는 일만 한다. */
    private final InvariantChecker invariantChecker = new InvariantChecker();

    /**
     * 같은 날 step들을 좌표 기반으로 재정렬한 새 리스트를 반환.
     * 시간 정보(startTime, endTime)와 stepOrder를 재정렬 순서에 맞게 재할당한다.
     *
     * @param steps      AI가 생성한 전체 step 리스트
     * @param placeCache 장소명+주소 → Place 엔티티 캐시 (좌표 포함)
     * @return 동선 최적화된 step 리스트
     */
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
    // 튜닝 상수는 ScheduleTuning, 위반 판정은 InvariantChecker에 있다.
    // ========================================================================================

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
        return repairAndSchedule(selection, candidates, pace, transportPref, startDate, themes, hub,
                compactMode, List.of());
    }

    /**
     * @param userCategories 사용자가 마법사에서 고른 카테고리 id 목록. 일정에 한 번도 등장하지
     *                       않은 카테고리를 spare에서 보충하는 데 쓴다(C2). 빈 값이면 커버리지
     *                       보충을 건너뛴다.
     */
    public List<StepData> repairAndSchedule(SelectionOutput selection, List<PlaceCandidate> candidates,
                                             String pace, String transportPref, java.time.LocalDate startDate,
                                             List<String> themes, TransportationHub hub,
                                             boolean compactMode, List<String> userCategories) {
        return schedule(selection, candidates, pace, transportPref, startDate, themes, hub,
                compactMode, userCategories).steps();
    }

    /**
     * 확정된 스텝과 함께 <b>끝내 해소하지 못한 품질 문제</b>를 돌려준다.
     *
     * <p>지금까지는 위반을 로그로만 남겨서, 숙소 없는 일정이 그대로 사용자에게 나갔다.
     * graceful degradation의 나머지 절반 — "저장하되 알린다" — 을 위해 호출부가 소비할 수 있는
     * 형태로 내보낸다.
     */
    public ScheduleResult schedule(SelectionOutput selection, List<PlaceCandidate> candidates,
                                   String pace, String transportPref, java.time.LocalDate startDate,
                                   List<String> themes, TransportationHub hub,
                                   boolean compactMode, List<String> userCategories) {
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
        // 같은 세부 유형(폭포만 4개)·경유형(해안도로 7곳) 반복을 상한으로 자르고 다른 유형으로 보충(C1).
        enforceTypeDiversity(days, candidates, spare, pairs);
        // 사용자가 고른 카테고리 중 일정에 0개인 것을 spare에서 보충(C2).
        ensureCategoryCoverage(days, candidates, spare, userCategories, maxPerDay);
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

        // spare 원본 스냅샷 — 최종 불변식 패스에서 day를 다시 스케줄할 때 같은 조건으로 재실행한다.
        List<Integer> spareBaseline = new ArrayList<>(spare);

        List<StepData> steps = scheduleAllDays(days, candidates, pairs, highlights, rests,
                spare, usedIndices, cfg, transportPref, maxPerDay);

        // [최종 불변식 패스] 확정된 스텝을 기준으로 하루 필수 슬롯·연속 대분류·카테고리 커버리지·
        // 유형 상한을 다시 검사한다. 앞 단계의 보정은 전부 "확정 전" 기준이라, scheduleDay의 버킷
        // 용량·거리 예산 트림이 그 결과를 다시 깨뜨릴 수 있다(실측: 커버리지로 넣은 전망대가
        // 트림돼 최종 0개). 고치면 그 day만 다시 스케줄해 가드를 자동으로 재통과시킨다.
        InvariantMemo memo = new InvariantMemo();
        for (int pass = 0; pass < MAX_INVARIANT_PASSES; pass++) {
            // 낮은 우선순위 수리(커버리지·유형 상한)가 높은 우선순위 불변식(식사·거리)을 깨면
            // 되돌리기 위해, 패스 시작 시점의 day 구성과 불변식 충족 상태를 스냅샷으로 잡는다.
            DaySnapshot snapshot = DaySnapshot.of(days);
            Map<Integer, DayStatus> before = invariantChecker.evaluate(days, steps, transportPref, candidates);

            RepairOutcome outcome = enforceFinalInvariants(days, steps, candidates, spare, usedIndices,
                    pace, transportPref, maxPerDay, userCategories, startDate, memo);
            if (!outcome.changed()) break;

            resetSpare(spare, spareBaseline, days);
            steps = scheduleAllDays(days, candidates, pairs, highlights, rests,
                    spare, usedIndices, cfg, transportPref, maxPerDay);

            // 우선순위 역전 검사: 낮은 등급 수리 때문에 식사·거리 불변식이 새로 깨졌으면 그 수리를 취소한다.
            if (outcome.lowestPriorityRepair() != null) {
                Map<Integer, DayStatus> after = invariantChecker.evaluate(days, steps, transportPref, candidates);
                String regression = invariantChecker.findPriorityRegression(before, after);
                if (regression != null) {
                    log.info("우선순위 역전 — 수리 취소: {} (원인: {})", outcome.lowestPriorityRepair(), regression);
                    snapshot.restore(days);
                    memo.rejectAll(outcome.insertedIndices());
                    resetSpare(spare, spareBaseline, days);
                    steps = scheduleAllDays(days, candidates, pairs, highlights, rests,
                            spare, usedIndices, cfg, transportPref, maxPerDay);
                    memo.clearPending();
                    continue;
                }
            }
            // 이번 패스에 넣었는데 거리·시간 가드에 잘린 장소는 "그 day에는 못 넣는다"로 기억한다.
            // 기억하지 않으면 보충 → 트림 → 보충이 매 패스 반복되며 수렴하지 않는다.
            memo.settle(days);
        }
        List<String> notices = invariantChecker.summarize(days, steps, pace, transportPref, candidates, cfg);

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
        return new ScheduleResult(renumbered, notices);
    }

    /**
     * 확정 스텝 + 끝내 해소하지 못한 품질 문제(사용자 안내 문구).
     * notices가 비어 있으면 모든 구조적 불변식을 만족한 일정이다.
     */
    public record ScheduleResult(List<StepData> steps, List<String> notices) {}

    /**
     * [최종 불변식 패스] 확정된 스텝을 기준으로 하루 구성을 검사하고, 위반이 있으면 해당 day의
     * placeIndices를 고쳐 재스케줄이 필요함을 알린다(true 반환).
     *
     * <p>검사 항목:
     * <ol>
     *   <li>점심(11:30~13:30)·저녁(17:30~19:30) DINING 각 1개. 마지막날 저녁은 출발 허브 시각이
     *       19:30 이후일 때만 필수.</li>
     *   <li>관광(ATTRACTION) 최소 1개 — relaxed 페이스는 제외.</li>
     *   <li>하루 종료가 17:00 이전이면 오후 활동 보충.</li>
     *   <li>같은 대분류 {@value ScheduleTuning#MAX_CONSECUTIVE_SAME_MAJOR}개 이상 연속 금지 — 중간을 교체.</li>
     *   <li>사용자 카테고리 커버리지·세부 유형 상한을 <b>최종 산출물 기준으로</b> 재적용.</li>
     * </ol>
     *
     * <p>삽입한 장소가 거리 예산·시간대 가드를 통과하는지는 재스케줄이 판정한다 — 통과하지 못해
     * 트림되면 다음 패스에서 같은 위반이 다시 잡히고, 대체 후보가 없으면 로그만 남기고 끝난다.
     */
    /**
     * 최종 보정 패스를 <b>우선순위 순</b>으로 적용한다(3차 1번).
     *
     * <p>우선순위(높음→낮음): <b>식사 슬롯 &gt; 하루 거리 예산 &gt; 시간대 가드 &gt; 카테고리 커버리지
     * &gt; 유형 상한</b>. 거리 예산·시간대 가드는 재스케줄(enforceDistanceBudget/버킷 배분)이 강제하므로
     * 여기서는 그 둘을 <b>깨뜨리지 않도록</b> 하위 수리를 제한하는 방식으로 지킨다:
     * <ul>
     *   <li>식사 슬롯을 채우고 있는 스텝은 커버리지·유형 상한 교체 대상에서 제외</li>
     *   <li>유형 상한 교체는 같은 대분류 안에서만(관광↔식당, 식당↔카페 교체 금지)</li>
     *   <li>커버리지 삽입·교체는 대상 day centroid 최근접을 고르고, 그 day의 구간/일일 예산을
     *       넘기면 삽입하지 않는다(왕복을 만드느니 미충족 로그가 낫다)</li>
     * </ul>
     * 그래도 재스케줄 결과가 상위 불변식을 깨면 호출부가 스냅샷으로 되돌린다.
     */
    private RepairOutcome enforceFinalInvariants(List<DayState> days, List<StepData> steps,
                                                 List<PlaceCandidate> candidates, Deque<Integer> spare,
                                                 Set<Integer> usedIndices, String pace, String transportPref,
                                                 int maxPerDay, List<String> userCategories,
                                                 java.time.LocalDate startDate, InvariantMemo memo) {
        Map<Integer, List<StepData>> byDay = steps.stream()
                .collect(Collectors.groupingBy(StepData::dayNumber, LinkedHashMap::new, Collectors.toList()));
        boolean changed = false;

        int typeCap = (int) Math.ceil(days.size() / 2.0) + TYPE_CAP_BASE;
        Map<String, Integer> globalTypeCount = countSubTypes(days, candidates);
        java.util.function.Predicate<PlaceCandidate> underTypeCap =
                c -> !isSpecificType(subTypeOf(c)) || globalTypeCount.getOrDefault(subTypeOf(c), 0) < typeCap;

        // ── 1순위: 식사 슬롯 + 숙소 ───────────────────────────────────────
        for (int i = 0; i < days.size(); i++) {
            DayState day = days.get(i);
            List<StepData> daySteps = byDay.getOrDefault(day.dayNumber, List.of());
            if (daySteps.isEmpty()) continue;
            boolean lastDay = i == days.size() - 1;
            changed |= ensureMealSlots(day, daySteps, candidates, spare, usedIndices, maxPerDay,
                    lastDay, startDate, transportPref, memo);
            changed |= ensureAccommodation(day, candidates, lastDay);
        }
        if (changed) return RepairOutcome.highPriority(memo.pendingIndices());

        // ── 3순위: 시간대·구성 가드(관광 최소 1개, 조기 종료) ─────────────
        for (int i = 0; i < days.size(); i++) {
            DayState day = days.get(i);
            List<StepData> daySteps = byDay.getOrDefault(day.dayNumber, List.of());
            if (daySteps.isEmpty()) continue;
            java.util.function.Predicate<PlaceCandidate> fillable =
                    insertableFilter(day, candidates, startDate, transportPref, memo).and(underTypeCap);
            changed |= ensureAttraction(day, daySteps, candidates, spare, usedIndices, maxPerDay, pace,
                    fillable, memo);
            changed |= ensureNotFinishingEarly(day, daySteps, candidates, spare, usedIndices, maxPerDay,
                    fillable, memo);
        }
        if (changed) return RepairOutcome.highPriority(memo.pendingIndices());

        // ── 4순위: 카테고리 커버리지 ──────────────────────────────────────
        if (reapplyCoverageOnFinalSteps(days, steps, candidates, spare, usedIndices,
                userCategories, maxPerDay, startDate, memo, transportPref)) {
            return RepairOutcome.lowPriority("카테고리 커버리지 보충", memo.pendingIndices());
        }

        // ── 5순위: 유형 상한·같은 종류 연속 ───────────────────────────────
        if (reapplyTypeCapOnFinalSteps(days, steps, candidates, spare, usedIndices, startDate, memo,
                transportPref)) {
            return RepairOutcome.lowPriority("세부 유형 상한 교체", memo.pendingIndices());
        }
        for (int i = 0; i < days.size(); i++) {
            DayState day = days.get(i);
            List<StepData> daySteps = byDay.getOrDefault(day.dayNumber, List.of());
            if (daySteps.isEmpty()) continue;
            java.util.function.Predicate<PlaceCandidate> fillable =
                    insertableFilter(day, candidates, startDate, transportPref, memo).and(underTypeCap);
            if (breakConsecutiveSameMajor(day, daySteps, candidates, spare, usedIndices, fillable)) {
                return RepairOutcome.lowPriority("같은 종류 연속 해소", memo.pendingIndices());
            }
        }
        return RepairOutcome.none();
    }



    /**
     * 그 day에 넣어도 되는 후보인지 판정하는 공통 필터 — 정기휴무, 이미 거부된 조합, 동선 반경,
     * 그리고 <b>거리 예산</b>을 모두 본다. 예산 검사가 없으면 커버리지가 왕복 93km짜리 장소를
     * 집어넣고 다음 패스에서 트림되는 왕복이 생긴다(실측 58 day1).
     */
    private java.util.function.Predicate<PlaceCandidate> insertableFilter(
            DayState day, List<PlaceCandidate> candidates, java.time.LocalDate startDate,
            String transportPref, InvariantMemo memo) {
        return insertableFilter(day, candidates, startDate, transportPref, memo, RelaxLevel.L1);
    }


    private java.util.function.Predicate<PlaceCandidate> insertableFilter(
            DayState day, List<PlaceCandidate> candidates, java.time.LocalDate startDate,
            String transportPref, InvariantMemo memo, RelaxLevel level) {
        double[] centroid = centroidOf(day.placeIndices, candidates);
        double radius = altRadiusKm(transportPref) * (level == RelaxLevel.L2 ? 2 : 1);

        java.util.function.Predicate<PlaceCandidate> filter = openOnDayFilter(startDate, day.dayNumber)
                .and(c -> !memo.isRejected(day.dayNumber, c.index()));
        if (level != RelaxLevel.L3) {
            filter = filter
                    .and(c -> centroid == null || coordsOf(c) == null
                            || haversine(centroid, coordsOf(c)) <= radius)
                    .and(c -> !wouldExceedDayBudget(day, c, candidates, transportPref));
        }
        return filter;
    }

    /**
     * 후보를 그 day에 넣었을 때 구간·일일 거리 예산을 넘기는지 추정한다.
     * 삽입 위치는 "우회가 가장 작은 자리"로 가정한다(실제 순서는 재스케줄이 정하므로 하한 추정).
     */
    private boolean wouldExceedDayBudget(DayState day, PlaceCandidate cand, List<PlaceCandidate> candidates,
                                         String transportPref) {
        double[] coord = coordsOf(cand);
        if (coord == null) return false; // 좌표 불명이면 판단 보류

        double[] limits = TRANSPORT_ABS_LIMITS.getOrDefault(transportPref, TRANSPORT_ABS_LIMITS.get("any"));
        List<double[]> path = new ArrayList<>();
        // 도착 허브 → 방문지 → 숙소 → 출발 허브. 허브를 빼고 재면 마지막날 공항 왕복이 통째로
        // 누락돼 "예산 안"으로 오판한다(실측 67 day3: 허브 제외 시 상한 내, 포함 시 초과).
        double[] arrivalHub = day.arrivalHubIndex != null
                ? coordsOf(byIndex(candidates, day.arrivalHubIndex)) : null;
        if (arrivalHub != null) path.add(arrivalHub);
        for (Integer idx : day.placeIndices) {
            double[] c = coordsOf(byIndex(candidates, idx));
            if (c != null) path.add(c);
        }
        double[] hotel = accommodationCoords(day, candidates);
        if (hotel != null) path.add(hotel);
        double[] departureHub = day.departureHubIndex != null
                ? coordsOf(byIndex(candidates, day.departureHubIndex)) : null;
        if (departureHub != null) path.add(departureHub);
        if (path.isEmpty()) return false;

        double current = 0;
        for (int i = 1; i < path.size(); i++) current += haversine(path.get(i - 1), path.get(i));

        // 최소 우회 삽입 비용 + 삽입으로 생기는 최장 구간
        double bestDetour = Double.MAX_VALUE;
        double bestNewLeg = 0;
        for (int i = 0; i <= path.size(); i++) {
            double[] prev = i > 0 ? path.get(i - 1) : null;
            double[] next = i < path.size() ? path.get(i) : null;
            double detour = detourVia(prev, coord, next);
            if (prev != null && next != null) detour -= haversine(prev, next);
            double newLeg = Math.max(prev != null ? haversine(prev, coord) : 0,
                    next != null ? haversine(coord, next) : 0);
            if (detour < bestDetour) {
                bestDetour = detour;
                bestNewLeg = newLeg;
            }
        }
        if (bestDetour == Double.MAX_VALUE) return false;
        return current + bestDetour > limits[1] || bestNewLeg > limits[0];
    }

    /**
     * 숙박일에 숙소가 배정됐는지 확인하고, 없으면 그날 방문지 중심에 가장 가까운 LODGING을 배정한다.
     *
     * <p>앞단(IndexResultMapper.fillMissingAccommodation)이 실패했을 때의 최종 안전망이다. 숙소는
     * 지금까지 불변식 검사 항목이 아니어서 "4일 전부 숙소 없음"이 위반 없이 통과했다(실측 60·66·67).
     * 여기서는 거리·예산 가드를 걸지 않는다 — 숙소는 하루의 종착점이라 "먼 숙소"가 "숙소 없음"보다 낫다.
     */
    private boolean ensureAccommodation(DayState day, List<PlaceCandidate> candidates, boolean lastDay) {
        if (lastDay || day.accommodationIndex != null) return false;

        double[] centroid = centroidOf(day.placeIndices, candidates);
        Set<Integer> visited = new HashSet<>(day.placeIndices);
        PlaceCandidate best = candidates.stream()
                .filter(c -> "LODGING".equals(PlaceCategoryConstants.majorCategory(c.category())))
                .filter(c -> !visited.contains(c.index()))   // 방문지로 쓰인 숙소는 이중 등장 방지
                .min(Comparator.comparingDouble(c -> centroid == null || coordsOf(c) == null
                        ? Double.MAX_VALUE : haversine(centroid, coordsOf(c))))
                .orElse(null);

        if (best == null) {
            log.warn("숙소 보충 실패(LODGING 후보 없음): day={}", day.dayNumber);
            return false;
        }
        day.accommodationIndex = best.index();
        log.info("숙소 보충: day={} {}", day.dayNumber, best.name());
        return true;
    }

    /** 점심·저녁 슬롯에 DINING이 있는지 확인하고, 없으면 근처 식당을 넣는다. */
    private boolean ensureMealSlots(DayState day, List<StepData> daySteps, List<PlaceCandidate> candidates,
                                    Deque<Integer> spare, Set<Integer> usedIndices, int maxPerDay,
                                    boolean lastDay, java.time.LocalDate startDate, String transportPref,
                                    InvariantMemo memo) {
        boolean changed = false;

        if (!hasMealInWindow(daySteps, LUNCH_START, LUNCH_END)) {
            // Collections.singletonList — centroid는 좌표 불명 day에서 null일 수 있고 List.of는 null을 거부한다
            changed |= insertMealWithRelaxation(day, candidates, spare, usedIndices, maxPerDay,
                    Collections.singletonList(centroidOf(day.placeIndices, candidates)),
                    "점심", startDate, transportPref, memo);
        }

        // 마지막날 저녁은 귀가 시각이 늦을 때만 필요하다(출발 허브 도착이 19:30 전이면 저녁을 먹지 않음)
        boolean dinnerRequired = !lastDay || departsAfterDinner(daySteps, candidates, day);
        if (dinnerRequired && !hasMealInWindow(daySteps, DINNER_START, DINNER_END)) {
            // 앵커 우선순위: 숙소 → 출발 허브 → 방문지 중심.
            // 마지막날은 숙소가 없어 centroid로만 찾았는데, 그러면 공항 가는 길목 식당이 후보에
            // 들어오지 않는다(실측 67 day3: 저녁 보충 실패).
            List<double[]> anchors = new ArrayList<>();
            anchors.add(accommodationCoords(day, candidates));
            if (day.departureHubIndex != null) {
                anchors.add(coordsOf(byIndex(candidates, day.departureHubIndex)));
            }
            anchors.add(centroidOf(day.placeIndices, candidates));
            changed |= insertMealWithRelaxation(day, candidates, spare, usedIndices, maxPerDay,
                    anchors, "저녁", startDate, transportPref, memo);
        }
        return changed;
    }

    /**
     * 필수 식사 슬롯을 채운다 — 후보가 없으면 제약을 단계적으로 완화해 재시도한다(L1 → L2 → L3).
     * 구조적 불변식이라 "채우는 것"이 우선이고, 세 단계 모두 실패해야 미충족으로 기록한다.
     */
    private boolean insertMealWithRelaxation(DayState day, List<PlaceCandidate> candidates,
                                             Deque<Integer> spare, Set<Integer> usedIndices, int maxPerDay,
                                             List<double[]> anchors, String slotName,
                                             java.time.LocalDate startDate, String transportPref,
                                             InvariantMemo memo) {
        for (RelaxLevel level : RelaxLevel.values()) {
            java.util.function.Predicate<PlaceCandidate> filter =
                    insertableFilter(day, candidates, startDate, transportPref, memo, level);
            for (double[] anchor : anchors) {
                if (anchor == null) continue;
                if (insertMeal(day, candidates, spare, usedIndices, maxPerDay, anchor, slotName, filter, memo)) {
                    if (level != RelaxLevel.L1) {
                        log.info("{} 슬롯 보충에 제약 완화 {} 적용: day={}", slotName, level, day.dayNumber);
                    }
                    return true;
                }
            }
        }
        logMealFillFailure(day, candidates, usedIndices, slotName);
        return false;
    }



    /**
     * 출발 허브 도착이 {@value ScheduleTuning#DEPARTURE_HUB_LATEST_MINUTES}분(21:00)을 넘을 것으로 추정되면,
     * 보호 대상이 아닌 활동을 <b>뒤에서부터</b> 잘라 귀가를 앞당긴다.
     *
     * <p>거리 예산과는 다른 축이다 — 하루 이동이 상한 안이어도 체류시간이 쌓이면 심야 귀가가 된다
     * (실측 itinerary 67 day3: 직선 83km로 상한 내인데 공항 도착 23:25).
     * 확정 루프 전에 돌려 시간 계산을 한 번만 하고, 최대 3회까지만 시도해 수렴시킨다.
     */
    private void trimSequenceForDepartureTime(DayState day, List<Integer> seq, List<PlaceCandidate> candidates,
                                              Deque<Integer> spareIndices, ScheduleConfig cfg,
                                              PlaceCandidate origin, String transportPref,
                                              Set<Integer> protectedIdx,
                                              Integer breakfastIdx, Integer lunchIdx, Integer dinnerIdx) {
        if (day.departureHubIndex == null) return;

        for (int guard = 0; guard < 3; guard++) {
            int arrival = estimateDepartureHubArrival(day, seq, candidates, cfg, origin, transportPref,
                    breakfastIdx, lunchIdx, dinnerIdx);
            if (arrival <= DEPARTURE_HUB_LATEST_MINUTES) return;

            Integer victim = null;
            for (int i = seq.size() - 1; i >= 0; i--) {
                Integer idx = seq.get(i);
                if (protectedIdx.contains(idx)) continue;
                PlaceCandidate c = byIndex(candidates, idx);
                if (c == null || c.userSelected()) continue;
                victim = idx;
                break;
            }
            if (victim == null) {
                log.info("귀가 시각 초과({}) — 트림 가능한 활동 없음: day={}",
                        formatMinutes(Math.min(arrival, END_OF_DAY_MINUTES)), day.dayNumber);
                return;
            }
            seq.remove(victim);
            day.placeIndices.remove(victim);
            spareIndices.addLast(victim);
            log.info("귀가 시각 초과({} > {}) — 트림: day={} {}",
                    formatMinutes(Math.min(arrival, END_OF_DAY_MINUTES)),
                    formatMinutes(DEPARTURE_HUB_LATEST_MINUTES), day.dayNumber, nameOf(candidates, victim));
        }
    }

    /** 확정 루프와 같은 규칙(체류+이동+식사 슬롯 하한)으로 출발 허브 도착 시각을 추정한다. */
    private int estimateDepartureHubArrival(DayState day, List<Integer> seq, List<PlaceCandidate> candidates,
                                            ScheduleConfig cfg, PlaceCandidate origin, String transportPref,
                                            Integer breakfastIdx, Integer lunchIdx, Integer dinnerIdx) {
        int minutes = cfg.morningStart;
        PlaceCandidate prev = origin;

        for (Integer idx : seq) {
            PlaceCandidate cand = byIndex(candidates, idx);
            if (cand == null) continue;

            TransportLeg leg = computeLeg(prev, cand, transportPref, day.dayNumber);
            if (leg != null) minutes += leg.durationMin();

            if (isSameIndex(breakfastIdx, idx)) minutes = Math.max(minutes, cfg.morningStart);
            if (isSameIndex(lunchIdx, idx)) minutes = Math.max(minutes, LUNCH_START);
            if (isSameIndex(dinnerIdx, idx)) minutes = Math.max(minutes, DINNER_START);

            if (isSameIndex(day.departureHubIndex, idx)) return minutes;

            if (isSameIndex(day.accommodationIndex, idx)
                    || PlaceCategoryConstants.isAccommodation(cand.category())) {
                minutes += ACCOMMODATION_ARRIVAL_MINUTES;
            } else if (isDiningCand(cand)) {
                minutes += DINING_VISIT_MINUTES;
            } else {
                minutes += resolveVisitMinutes(cand);
            }
            prev = cand;
        }
        return minutes;
    }

    /** null-safe 인덱스 비교 — 허브·숙소·식사 슬롯은 미배정(null)일 수 있다. */
    private boolean isSameIndex(Integer slot, int idx) {
        return slot != null && slot == idx;
    }

    /**
     * 야외·주간 장소의 시작 상한. 테마가 정한 하루 종료(eveningCap)에서 역산하며, 어떤 테마에서도
     * 15:00 아래로는 내리지 않는다. 여행지 고유 규칙이 아니라 <b>사용자가 고른 테마에서만</b> 파생된다.
     */
    private int daytimeLatestStart(ScheduleConfig cfg) {
        int cap = cfg != null ? cfg.eveningCap : DEFAULT_EVENING_CAP_MINUTES;
        return Math.max(15 * 60, cap - 180);
    }

    /** 그 스텝이 점심·저녁 슬롯을 채우고 있는지 — 교체·트림 보호 대상 판정용. */
    private boolean isMealSlotStep(StepData step) {
        if (step.place() == null) return false;
        if (!PlaceCategoryConstants.isMealPlace(step.place().name(), step.place().category())) return false;
        int start = toMinutesOrZero(step.startTime());
        return (start >= LUNCH_START - 60 && start <= LUNCH_END)
                || (start >= DINNER_START - 60 && start <= DINNER_END);
    }

    /** 필수 식사 삽입 — pace 상한을 {@value ScheduleTuning#MEAL_QUOTA_SLACK}개까지 넘어도 포함을 우선한다. */
    private boolean insertMeal(DayState day, List<PlaceCandidate> candidates,
                               Deque<Integer> spare, Set<Integer> usedIndices, int maxPerDay,
                               double[] anchor, String slotName,
                               java.util.function.Predicate<PlaceCandidate> openOnDay, InvariantMemo memo) {
        Integer pick = pickNearbyCandidate(candidates, spare, usedIndices, anchor,
                openOnDay.and(PlaceCandidate::mealEligible));
        // 실패 로그는 호출부(insertMealWithRelaxation)가 완화 단계를 전부 소진한 뒤에 남긴다 —
        // 여기서 찍으면 L1 실패마다 "보충 실패"가 나와 실제 결과를 오인하게 된다.
        if (pick == null) return false;

        if (day.placeIndices.size() < maxPerDay + MEAL_QUOTA_SLACK) {
            addToDay(day, pick, spare, usedIndices);
            memo.recordInsert(day.dayNumber, pick);
            log.info("{} 슬롯 보충: day={} {}", slotName, day.dayNumber, nameOf(candidates, pick));
            return true;
        }

        // quota가 꽉 찼다고 끼니를 통째로 빼는 건 여행 일정으로 성립하지 않는다 — 개수를 늘리는
        // 대신 그날 가장 많이 반복된 비식사 장소와 교체한다(pace 상한 유지 + 필수 슬롯 확보).
        Integer victim = mostRepeatedNonMeal(day, candidates);
        if (victim == null) {
            log.info("{} 슬롯 보충 실패(quota 여유 없음·교체 대상 없음): day={}", slotName, day.dayNumber);
            return false;
        }
        replaceInDay(day, victim, pick);
        spare.remove(pick);
        spare.addLast(victim);
        usedIndices.add(pick);
        usedIndices.remove(victim);
        memo.recordInsert(day.dayNumber, pick);
        log.info("{} 슬롯 교체 보충: day={} {} → {}", slotName, day.dayNumber,
                nameOf(candidates, victim), nameOf(candidates, pick));
        return true;
    }

    /**
     * 식사 보충이 후보를 못 찾았을 때 "무엇이 막았는지"를 단계별 잔여 수로 남긴다.
     * "후보 없음"만 찍히면 데이터 부족인지 필터가 과한지 구분할 수 없다.
     */
    private void logMealFillFailure(DayState day, List<PlaceCandidate> candidates,
                                    Set<Integer> usedIndices, String slotName) {
        long meal = candidates.stream().filter(PlaceCandidate::mealEligible).count();
        long unused = candidates.stream()
                .filter(PlaceCandidate::mealEligible)
                .filter(c -> !usedIndices.contains(c.index()))
                .count();
        log.info("{} 슬롯 보충 실패: day={} (식사가능 {}곳 중 미사용 {}곳 — 반경·예산·휴무 필터를 모두 통과한 후보 0)",
                slotName, day.dayNumber, meal, unused);
    }

    /** 그날에서 같은 세부 유형이 가장 많이 반복된 비식사 방문지(사용자 필수 제외). 없으면 null. */
    private Integer mostRepeatedNonMeal(DayState day, List<PlaceCandidate> candidates) {
        Map<String, Integer> counts = new HashMap<>();
        for (Integer idx : day.placeIndices) {
            PlaceCandidate c = byIndex(candidates, idx);
            if (c != null) counts.merge(subTypeOf(c), 1, Integer::sum);
        }
        Integer best = null;
        int bestCount = 0;
        for (Integer idx : day.placeIndices) {
            PlaceCandidate c = byIndex(candidates, idx);
            if (c == null || c.userSelected() || isDiningCand(c)) continue;
            int count = counts.getOrDefault(subTypeOf(c), 0);
            if (count > bestCount) {
                bestCount = count;
                best = idx;
            }
        }
        return best;
    }

    /** 전 일정의 세부 유형별 개수(허브·숙소 제외). */
    private Map<String, Integer> countSubTypes(List<DayState> days, List<PlaceCandidate> candidates) {
        Map<String, Integer> counts = new HashMap<>();
        for (DayState d : days) {
            for (Integer idx : d.placeIndices) {
                PlaceCandidate c = byIndex(candidates, idx);
                if (c != null) counts.merge(subTypeOf(c), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** 관광(ATTRACTION)이 하루도 없으면 보충한다. relaxed 페이스는 검사하지 않는다. */
    private boolean ensureAttraction(DayState day, List<StepData> daySteps, List<PlaceCandidate> candidates,
                                     Deque<Integer> spare, Set<Integer> usedIndices, int maxPerDay,
                                     String pace, java.util.function.Predicate<PlaceCandidate> openOnDay,
                                     InvariantMemo memo) {
        if ("relaxed".equals(pace)) return false;
        boolean has = daySteps.stream().anyMatch(s -> s.place() != null
                && "ATTRACTION".equals(PlaceCategoryConstants.majorCategory(s.place().category())));
        if (has) return false;
        if (day.placeIndices.size() >= maxPerDay) {
            log.info("관광 보충 실패(quota 여유 없음): day={}", day.dayNumber);
            return false;
        }
        Integer pick = pickNearbyCandidate(candidates, spare, usedIndices,
                centroidOf(day.placeIndices, candidates),
                openOnDay.and(c -> "ATTRACTION".equals(PlaceCategoryConstants.majorCategory(c.category()))
                        && !PlaceCategoryConstants.isViaRoute(c.name(), c.category(), c.tags())));
        if (pick == null) {
            log.info("관광 보충 실패(후보 없음): day={}", day.dayNumber);
            return false;
        }
        addToDay(day, pick, spare, usedIndices);
        memo.recordInsert(day.dayNumber, pick);
        log.info("관광 보충: day={} {}", day.dayNumber, nameOf(candidates, pick));
        return true;
    }

    /** 하루가 너무 일찍 끝나면(17:00 이전) 주간 활동을 하나 더 넣는다. */
    private boolean ensureNotFinishingEarly(DayState day, List<StepData> daySteps,
                                            List<PlaceCandidate> candidates, Deque<Integer> spare,
                                            Set<Integer> usedIndices, int maxPerDay,
                                            java.util.function.Predicate<PlaceCandidate> openOnDay,
                                            InvariantMemo memo) {
        int end = daySteps.stream().mapToInt(s -> toMinutesOrZero(s.endTime())).max().orElse(0);
        if (end == 0 || end >= EARLY_FINISH_MINUTES) return false;
        if (day.placeIndices.size() >= maxPerDay) return false;

        Integer pick = pickNearbyCandidate(candidates, spare, usedIndices,
                centroidOf(day.placeIndices, candidates),
                openOnDay.and(c -> isVisitableFill(c) && timePreference(c) != TimePreference.EVENING));
        if (pick == null) {
            log.info("조기 종료({}) 보충 실패(후보 없음): day={}", formatMinutes(end), day.dayNumber);
            return false;
        }
        addToDay(day, pick, spare, usedIndices);
        memo.recordInsert(day.dayNumber, pick);
        log.info("조기 종료({}) 보충: day={} {}", formatMinutes(end), day.dayNumber, nameOf(candidates, pick));
        return true;
    }

    /**
     * 같은 종류가 연속 {@value ScheduleTuning#MAX_CONSECUTIVE_SAME_MAJOR}개 이상이면 연속 구간 중간을 다른
     * 종류의 근접 spare로 교체한다. 교체할 후보가 없으면 그대로 두고 로그만 남긴다.
     *
     * <p>"같은 종류"의 기준은 대분류가 아니라 <b>체감 기준</b>으로 잡는다:
     * <ul>
     *   <li>식음료(DINING/CAFE): 대분류 연속 — 카페 3연속은 그 자체로 단조롭다</li>
     *   <li>관광(ATTRACTION): 세부 유형 연속 — 박물관·해변·공원이 이어지는 건 정상이고,
     *       문제는 "오름 3연속"처럼 같은 유형이 반복될 때다</li>
     * </ul>
     */
    private boolean breakConsecutiveSameMajor(DayState day, List<StepData> daySteps,
                                              List<PlaceCandidate> candidates, Deque<Integer> spare,
                                              Set<Integer> usedIndices,
                                              java.util.function.Predicate<PlaceCandidate> openOnDay) {
        List<StepData> visits = daySteps.stream()
                .filter(s -> s.place() != null)
                .filter(s -> {
                    String m = PlaceCategoryConstants.majorCategory(s.place().category());
                    return !"TRANSIT_HUB".equals(m) && !"LODGING".equals(m);
                })
                .collect(Collectors.toList());
        if (visits.size() < MAX_CONSECUTIVE_SAME_MAJOR) return false;

        int runStart = 0;
        for (int i = 1; i <= visits.size(); i++) {
            String prevKind = consecutiveKind(visits.get(i - 1));
            String curKind = i < visits.size() ? consecutiveKind(visits.get(i)) : null;
            if (curKind != null && curKind.equals(prevKind)) continue;

            int runLength = i - runStart;
            if (runLength >= MAX_CONSECUTIVE_SAME_MAJOR) {
                StepData middle = visits.get(runStart + runLength / 2);
                if (replaceStepWithSameKindBroken(day, middle, prevKind, candidates, spare, usedIndices, openOnDay)) {
                    return true;
                }
                log.info("{} {}개 연속 해소 실패(다른 종류 후보 없음): day={}",
                        prevKind, runLength, day.dayNumber);
            }
            runStart = i;
        }
        return false;
    }

    /** 연속 판정 키: 식음료는 대분류, 관광은 세부 유형. */
    private String consecutiveKind(StepData step) {
        String major = PlaceCategoryConstants.majorCategory(step.place().category());
        if ("DINING".equals(major) || "CAFE".equals(major)) return major;
        return PlaceCategoryConstants.subType(
                step.place().name(), step.place().category(), List.of(), step.place().description());
    }

    /** 스텝 하나를 "연속을 끊는 다른 종류"의 근접 spare로 교체. 사용자 필수 장소는 건드리지 않는다. */
    private boolean replaceStepWithSameKindBroken(DayState day, StepData step, String kind,
                                                  List<PlaceCandidate> candidates, Deque<Integer> spare,
                                                  Set<Integer> usedIndices,
                                                  java.util.function.Predicate<PlaceCandidate> openOnDay) {
        Integer target = indexOfStep(step, day, candidates);
        if (target == null) return false;
        PlaceCandidate targetCand = byIndex(candidates, target);
        if (targetCand == null || targetCand.userSelected()) return false;

        Integer pick = pickNearbyCandidate(candidates, spare, usedIndices, coordsOf(targetCand),
                openOnDay.and(c -> isVisitableFill(c) && !kind.equals(kindOf(c))));
        if (pick == null) return false;

        replaceInDay(day, target, pick);
        spare.remove(pick);
        spare.addLast(target);
        usedIndices.add(pick);
        usedIndices.remove(target);
        log.info("{} 연속 해소: day={} {} → {}", kind, day.dayNumber, targetCand.name(),
                nameOf(candidates, pick));
        return true;
    }

    /** 후보의 연속 판정 키(consecutiveKind와 같은 기준). */
    private String kindOf(PlaceCandidate c) {
        String major = PlaceCategoryConstants.majorCategory(c.category());
        if ("DINING".equals(major) || "CAFE".equals(major)) return major;
        return subTypeOf(c);
    }

    /**
     * 최종 스텝 기준 사용자 카테고리 커버리지 재적용 — 트림으로 사라진 카테고리를 다시 채운다.
     *
     * <p>보호 규칙(3차 1번): 후보는 <b>대상 day centroid 최근접</b>으로 고르고, 그 day의 구간·일일
     * 거리 예산을 넘기면 넣지 않는다. 실측 58 day1에서 제주시 공원을 동쪽 끝 오름으로 바꿔 왕복
     * 93km가 생겼는데, 커버리지 한 칸을 채우려고 하루 동선을 무너뜨리는 건 손해다 —
     * 그럴 바엔 미충족 로그를 남긴다.
     */
    private boolean reapplyCoverageOnFinalSteps(List<DayState> days, List<StepData> steps,
                                                List<PlaceCandidate> candidates, Deque<Integer> spare,
                                                Set<Integer> usedIndices, List<String> userCategories,
                                                int maxPerDay, java.time.LocalDate startDate,
                                                InvariantMemo memo, String transportPref) {
        if (userCategories == null || userCategories.isEmpty()) return false;

        for (String categoryId : userCategories) {
            if (categoryId == null || categoryId.isBlank()) continue;
            boolean covered = steps.stream().anyMatch(s -> s.place() != null
                    && PlaceCategoryConstants.matchesUserCategory(
                            categoryId, s.place().name(), s.place().category(), List.of()));
            if (!covered && "accommodation".equalsIgnoreCase(categoryId.trim())) {
                covered = days.stream().anyMatch(d -> d.accommodationIndex != null);
            }
            if (covered) continue;

            // (day, 후보) 조합을 함께 고른다 — "가장 좋은 후보"가 아니라 "어느 day에 넣었을 때
            // centroid에 가장 가까운가"가 기준이어야 동선이 망가지지 않는다.
            DayState bestDay = null;
            Integer bestPick = null;
            double bestDist = Double.MAX_VALUE;
            for (DayState d : days) {
                if (d.placeIndices.size() >= maxPerDay) continue;
                double[] centroid = centroidOf(d.placeIndices, candidates);
                java.util.function.Predicate<PlaceCandidate> insertable =
                        insertableFilter(d, candidates, startDate, transportPref, memo);
                for (Integer idx : spare) {
                    PlaceCandidate c = byIndex(candidates, idx);
                    if (c == null || !isVisitableFill(c)) continue;
                    if (!PlaceCategoryConstants.matchesUserCategory(
                            categoryId, c.name(), c.category(), c.tags())) continue;
                    if (!insertable.test(c)) continue;
                    double dist = centroid == null || coordsOf(c) == null
                            ? 0 : haversine(centroid, coordsOf(c));
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestDay = d;
                        bestPick = idx;
                    }
                }
            }

            if (bestDay != null) {
                addToDay(bestDay, bestPick, spare, usedIndices);
                memo.recordInsert(bestDay.dayNumber, bestPick);
                log.info("최종 커버리지 보충: {} → day={} {} (centroid에서 {}km)", categoryId,
                        bestDay.dayNumber, nameOf(candidates, bestPick), String.format("%.0f", bestDist));
                return true; // 한 번에 하나씩 — 재스케줄 후 다음 패스에서 나머지를 본다
            }
            // 자리가 없으면 "가장 많이 반복된 유형" 하나와 교체한다 — quota를 늘리지 않으면서
            // 커버리지와 다양성을 동시에 개선하는 유일한 수단(추가가 아니라 대체).
            if (replaceMostRepeatedForCoverage(days, candidates, spare, usedIndices, categoryId, startDate,
                    memo, transportPref, steps)) {
                return true;
            }
            log.info("최종 커버리지 미충족(예산·자리 제약으로 삽입·교체 불가): {}", categoryId);
        }
        return false;
    }

    /**
     * 커버리지 후보를 넣을 자리가 없을 때, 전체 일정에서 가장 많이 반복된 세부 유형의 장소 하나와
     * 맞바꾼다. 식사·허브·숙소·사용자 필수 장소는 교체하지 않고, 반복이 2회 미만이면 교체하지 않는다
     * (다양성을 해치면서까지 커버리지를 채우지는 않는다).
     */
    private boolean replaceMostRepeatedForCoverage(List<DayState> days, List<PlaceCandidate> candidates,
                                                   Deque<Integer> spare, Set<Integer> usedIndices,
                                                   String categoryId, java.time.LocalDate startDate,
                                                   InvariantMemo memo, String transportPref,
                                                   List<StepData> steps) {
        Map<String, Integer> typeCount = countSubTypes(days, candidates);
        // 식사 슬롯을 채우고 있는 장소 이름 — 교체하면 끼니가 사라지므로 대상에서 뺀다.
        Set<String> mealSlotNames = steps.stream()
                .filter(this::isMealSlotStep)
                .map(st -> normalizeName(st.place().name()))
                .collect(Collectors.toSet());

        DayState bestDay = null;
        Integer bestVictim = null;
        Integer bestPick = null;
        int bestCount = 1; // 2회 이상 반복된 유형만 교체 대상

        for (DayState d : days) {
            java.util.function.Predicate<PlaceCandidate> insertable =
                    insertableFilter(d, candidates, startDate, transportPref, memo);
            Integer pick = spare.stream()
                    .map(i -> byIndex(candidates, i))
                    .filter(Objects::nonNull)
                    .filter(this::isVisitableFill)
                    .filter(c -> PlaceCategoryConstants.matchesUserCategory(
                            categoryId, c.name(), c.category(), c.tags()))
                    .filter(insertable)
                    .min(Comparator.comparingDouble(c -> {
                        double[] centroid = centroidOf(d.placeIndices, candidates);
                        return centroid == null || coordsOf(c) == null ? 0 : haversine(centroid, coordsOf(c));
                    }))
                    .map(PlaceCandidate::index)
                    .orElse(null);
            if (pick == null) continue;

            for (Integer idx : d.placeIndices) {
                PlaceCandidate c = byIndex(candidates, idx);
                if (c == null || c.userSelected()) continue;
                if (isDiningCand(c)) continue;                                  // 식사 대분류는 제외
                if (mealSlotNames.contains(normalizeName(c.name()))) continue;   // 식사 슬롯 점유분 제외
                int count = typeCount.getOrDefault(subTypeOf(c), 0);
                if (count > bestCount) {
                    bestCount = count;
                    bestDay = d;
                    bestVictim = idx;
                    bestPick = pick;
                }
            }
        }
        if (bestDay == null) return false;

        replaceInDay(bestDay, bestVictim, bestPick);
        spare.remove(bestPick);
        spare.addLast(bestVictim);
        usedIndices.add(bestPick);
        usedIndices.remove(bestVictim);
        memo.recordInsert(bestDay.dayNumber, bestPick);
        log.info("최종 커버리지 교체 보충: {} → day={} {} → {} ({} {}회 반복)", categoryId, bestDay.dayNumber,
                nameOf(candidates, bestVictim), nameOf(candidates, bestPick),
                subTypeOf(byIndex(candidates, bestVictim)), bestCount);
        return true;
    }

    /**
     * 최종 스텝 기준 세부 유형 상한 재적용 — 상한 초과분을 <b>같은 대분류의</b> 다른 세부 유형으로 교체.
     *
     * <p>보호 규칙(3차 1번): ① 식사 슬롯을 채우는 스텝은 교체하지 않는다(끼니가 사라진다)
     * ② 교체는 같은 대분류 안에서만 — 관광을 식당으로, 식당을 카페로 바꾸면 상위 불변식이 깨진다
     * (실측: day4 저녁 식당 → 카페 교체 후 "저녁 없음", day3 오름 → 식당 교체).
     */
    private boolean reapplyTypeCapOnFinalSteps(List<DayState> days, List<StepData> steps,
                                               List<PlaceCandidate> candidates, Deque<Integer> spare,
                                               Set<Integer> usedIndices, java.time.LocalDate startDate,
                                               InvariantMemo memo, String transportPref) {
        int typeCap = (int) Math.ceil(days.size() / 2.0) + TYPE_CAP_BASE;
        Map<String, Integer> typeCount = new HashMap<>();

        for (StepData step : steps) {
            if (step.place() == null) continue;
            String major = PlaceCategoryConstants.majorCategory(step.place().category());
            if ("TRANSIT_HUB".equals(major) || "LODGING".equals(major)) continue;

            String type = PlaceCategoryConstants.subType(
                    step.place().name(), step.place().category(), List.of(), step.place().description());
            int count = typeCount.merge(type, 1, Integer::sum);
            if (!isSpecificType(type) || count <= typeCap) continue;

            if (isMealSlotStep(step)) {
                log.info("최종 유형 상한 초과({} {}회) — 식사 슬롯이라 교체하지 않음: {}",
                        type, count, step.place().name());
                continue;
            }

            DayState day = findDayContainingStep(days, step, candidates);
            if (day == null) continue;
            if (replaceStepWithDifferentSubType(day, step, type, major, typeCap, typeCount, candidates, spare,
                    usedIndices, insertableFilter(day, candidates, startDate, transportPref, memo), memo)) {
                return true; // 한 번에 하나씩
            }
            log.info("최종 유형 상한 초과({} {}회) 해소 실패(같은 대분류 대체 후보 없음): {}",
                    type, count, step.place().name());
        }
        return false;
    }

    private boolean replaceStepWithDifferentSubType(DayState day, StepData step, String type, String major,
                                                    int typeCap, Map<String, Integer> typeCount,
                                                    List<PlaceCandidate> candidates, Deque<Integer> spare,
                                                    Set<Integer> usedIndices,
                                                    java.util.function.Predicate<PlaceCandidate> insertable,
                                                    InvariantMemo memo) {
        Integer target = indexOfStep(step, day, candidates);
        if (target == null) return false;
        PlaceCandidate targetCand = byIndex(candidates, target);
        if (targetCand == null || targetCand.userSelected()) return false;

        Integer pick = pickNearbyCandidate(candidates, spare, usedIndices, coordsOf(targetCand),
                insertable.and(c -> isVisitableFill(c)
                        // 같은 대분류 안에서만 교체 — 대분류가 바뀌면 식사·관광 구성이 무너진다
                        && major.equals(PlaceCategoryConstants.majorCategory(c.category()))
                        && !type.equals(subTypeOf(c))
                        && typeCount.getOrDefault(subTypeOf(c), 0) < typeCap));
        if (pick == null) return false;

        replaceInDay(day, target, pick);
        spare.remove(pick);
        spare.addLast(target);
        usedIndices.add(pick);
        usedIndices.remove(target);
        memo.recordInsert(day.dayNumber, pick);
        typeCount.merge(type, -1, Integer::sum);
        log.info("최종 유형 상한({} {}회 초과) 교체: day={} {} → {} (대분류 {} 유지)", type, typeCap,
                day.dayNumber, targetCand.name(), nameOf(candidates, pick), major);
        return true;
    }

    /** 스텝(place 이름 기준)에 대응하는 day.placeIndices의 인덱스. 허브·숙소는 대상 아님. */
    private Integer indexOfStep(StepData step, DayState day, List<PlaceCandidate> candidates) {
        if (step.place() == null || step.place().name() == null) return null;
        String name = normalizeName(step.place().name());
        for (Integer idx : day.placeIndices) {
            PlaceCandidate c = byIndex(candidates, idx);
            if (c != null && normalizeName(c.name()).equals(name)) return idx;
        }
        return null;
    }

    private DayState findDayContainingStep(List<DayState> days, StepData step, List<PlaceCandidate> candidates) {
        for (DayState d : days) {
            if (d.dayNumber == step.dayNumber() && indexOfStep(step, d, candidates) != null) return d;
        }
        return null;
    }

    /** anchor에 가장 가까운, 조건을 만족하는 미사용 후보(spare 우선 → 전체 미사용). */
    private Integer pickNearbyCandidate(List<PlaceCandidate> candidates, Deque<Integer> spare,
                                        Set<Integer> usedIndices, double[] anchor,
                                        java.util.function.Predicate<PlaceCandidate> predicate) {
        Integer fromSpare = nearestMatching(spare.stream().map(i -> byIndex(candidates, i)).toList(),
                usedIndices, anchor, predicate);
        if (fromSpare != null) return fromSpare;
        // spare에 없으면 아직 어디에도 쓰이지 않은 후보까지 넓힌다(검색 결과를 끝까지 활용)
        return nearestMatching(candidates, usedIndices, anchor, predicate);
    }

    private Integer nearestMatching(List<PlaceCandidate> pool, Set<Integer> usedIndices, double[] anchor,
                                    java.util.function.Predicate<PlaceCandidate> predicate) {
        return pool.stream()
                .filter(Objects::nonNull)
                .filter(c -> !usedIndices.contains(c.index()))
                .filter(predicate)
                .filter(c -> anchor == null || coordsOf(c) != null)
                .min(Comparator.comparingDouble(c -> anchor == null || coordsOf(c) == null
                        ? Double.MAX_VALUE : haversine(anchor, coordsOf(c))))
                .map(PlaceCandidate::index)
                .orElse(null);
    }





    /** 그날 영업하는 장소만 통과시키는 필터. startDate가 없으면 판단 보류(전부 통과). */
    private java.util.function.Predicate<PlaceCandidate> openOnDayFilter(java.time.LocalDate startDate,
                                                                        int dayNumber) {
        if (startDate == null) return c -> true;
        java.time.DayOfWeek dow = startDate.plusDays(dayNumber - 1L).getDayOfWeek();
        return c -> !isClosedOnDay(c.openingHours(), dow);
    }

    private void addToDay(DayState day, Integer idx, Deque<Integer> spare, Set<Integer> usedIndices) {
        day.placeIndices.add(idx);
        spare.remove(idx);
        usedIndices.add(idx);
    }

    /**
     * 전체 day를 순회하며 순서 확정 → 거리 예산 → 시간 배정을 수행한다. 최종 불변식 패스가
     * 같은 조건으로 다시 부를 수 있도록 부작용을 spare/usedIndices로 한정한 순수 재실행 단위다.
     */
    private List<StepData> scheduleAllDays(List<DayState> days, List<PlaceCandidate> candidates,
                                            List<List<Integer>> pairs, Set<Integer> highlights,
                                            Set<Integer> rests, Deque<Integer> spare,
                                            Set<Integer> usedIndices, ScheduleConfig cfg,
                                            String transportPref, int maxPerDay) {
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
        return steps;
    }

    /**
     * spare를 원본 스냅샷으로 되돌리되, 그 사이 day에 편입된 인덱스는 뺀다.
     * 되돌리지 않으면 이전 패스에서 트림된 장소가 spare에 중복 누적돼 같은 장소가 두 번 채워진다.
     */
    private void resetSpare(Deque<Integer> spare, List<Integer> baseline, List<DayState> days) {
        Set<Integer> used = collectUsedIndices(days);
        spare.clear();
        for (Integer idx : baseline) {
            if (!used.contains(idx) && !spare.contains(idx)) spare.addLast(idx);
        }
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

    /**
     * quota 보충으로 방문 스텝에 넣어도 되는 후보인지(식당/카페/관광지만 허용).
     * 경유형(해안도로·드라이브코스)은 어떤 자동 보충 경로로도 넣지 않는다 — Sonnet이 처음 고른
     * 1개는 유지하되(enforceTypeDiversity의 상한), 코드가 스스로 늘리지는 않는다는 규칙.
     */
    private boolean isVisitableFill(PlaceCandidate c) {
        String major = PlaceCategoryConstants.majorCategory(c.category());
        if (PlaceCategoryConstants.isViaRoute(c.name(), c.category(), c.tags())) return false;
        // 집합 POI(음식거리·먹자골목)도 자동 보충으로 넣지 않는다 — 선택 단계에서 뺀 장소를
        // 최종 패스가 활동으로 되살리면 같은 규칙이 경로마다 다르게 적용되는 셈이다
        // (실측: 선택에서 제외된 "Seogwipo's food streets"가 조기 종료 보충으로 재유입).
        if (PlaceCategoryConstants.isAggregatePoi(c.name(), c.category())) return false;
        // 여객시설(터미널·대합실·선착장)은 카테고리가 관광지로 오적재돼 있어도 방문지가 아니다
        // — 실측: "성산포항 종합여객터미널"이 관광 스텝 90분으로 배치됐다.
        if (PlaceCategoryConstants.isPassengerFacility(c.name())) return false;
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

        // 이동만으로는 "목적 day가 꽉 찬" 경우를 못 고친다(quota가 이미 상한이면 아무도 못 감).
        // 양쪽 모두 centroid에 가까워지는 1:1 교환을 추가로 시도한다(C5).
        changed |= swapAcrossDays(days, candidates, centroids, pairedIndices);
        return changed;
    }

    /**
     * 서로 다른 day의 방문지 하나씩을 맞바꾼다 — 각자 상대 day의 centroid에 더 가까워지고 합산
     * 이득이 {@link #SWAP_MIN_GAIN_KM} 이상일 때만. 개수 중립이라 quota를 깨지 않으므로 꽉 찬
     * day에도 적용된다. pair 멤버·사용자 필수 장소는 제외하고, 한 패스에서 각 장소는 최대 1회 교환.
     */
    private boolean swapAcrossDays(List<DayState> days, List<PlaceCandidate> candidates,
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

                        double before = haversine(ca, ci) + haversine(cb, cj);
                        double after = haversine(ca, cj) + haversine(cb, ci);
                        double gain = before - after;
                        // 양쪽 모두 개선되는 교환만 (한쪽 희생으로 합만 맞추는 교환 금지)
                        boolean bothImprove = haversine(ca, cj) < haversine(ca, ci)
                                && haversine(cb, ci) < haversine(cb, cj);
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

    private boolean isSwappable(Integer idx, List<PlaceCandidate> candidates,
                                Set<Integer> pairedIndices, Set<Integer> swapped) {
        if (pairedIndices.contains(idx) || swapped.contains(idx)) return false;
        PlaceCandidate c = byIndex(candidates, idx);
        return c != null && !c.userSelected();
    }

    private String nameOf(List<PlaceCandidate> candidates, Integer idx) {
        PlaceCandidate c = byIndex(candidates, idx);
        return c != null && c.name() != null ? c.name() : String.valueOf(idx);
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
            if (prevRegion == null || !prevRegion.equals(currRegion)) continue;

            // region은 "Jeju"처럼 섬·광역 단위라 너무 거칠다. 같은 region이어도 day1은 서부,
            // day2는 동부면 숙소를 통일하는 순간 매 구간이 30km대가 된다(실측 itinerary 68 day2
            // 표시 161km). 짐 이동을 아끼자고 하루 동선을 무너뜨리면 손해이므로, 통일했을 때
            // 그날 숙소가 방문지 중심에서 지나치게 멀어지면 그대로 둔다.
            double[] currCentroid = centroidOf(curr.placeIndices, candidates);
            double[] prevHotel = coordsOf(byIndex(candidates, prev.accommodationIndex));
            double[] currHotel = coordsOf(byIndex(candidates, curr.accommodationIndex));
            if (currCentroid != null && prevHotel != null && currHotel != null) {
                double unified = haversine(currCentroid, prevHotel);
                double own = haversine(currCentroid, currHotel);
                if (unified - own > CONTINUITY_MAX_EXTRA_KM) {
                    log.info("숙소 연박 통일 보류: day={} 통일 시 중심에서 {}km(현재 {}km) — 동선 우선",
                            curr.dayNumber, String.format("%.0f", unified), String.format("%.0f", own));
                    continue;
                }
            }
            curr.accommodationIndex = prev.accommodationIndex;
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
                    // 한 끼 식사가 아닌 것(바·디저트·베이커리·간식)과 집합 POI(음식거리)는 식사
                    // 슬롯에서 뺀다 — 검색 슬롯과 같은 목록을 쓴다(PlaceCategoryConstants.isMealPlace).
                    return c != null && c.mealEligible();
                })
                .collect(Collectors.toList());
        List<Integer> meals = selectMeals(diningAll, candidates, spareIndices);
        // 아침 슬롯은 "숙소에서 출발하는 날"에만 성립한다. 도착일(허브로 시작)이나 전날 숙소가
        // 없는 날에 아침을 두면 공항 옆 식당이 10시 아침이 된다(실측 64 day1 10:03).
        boolean allowBreakfast = day.arrivalHubIndex == null && startFrom != null;
        meals = dropBreakfastIfNotAllowed(meals, day, candidates, spareIndices, allowBreakfast);
        // 숙소에서 먼 아침은 그 다음에 버린다(아침부터 원정 방지) — 남은 개수가 버킷 용량을 결정한다.
        meals = dropDistantBreakfast(meals, day, candidates, spareIndices);
        Set<Integer> mealSet = new HashSet<>(diningAll);

        // 비식사 활동을 시간대 적합성(야외=주간만, Bar=저녁만)을 지키며 오전/오후/저녁 버킷에 배분.
        // 버킷 용량 초과·시간대 부적합으로 못 넣는 활동은 spare 트림.
        List<Integer> activities = orderedMain.stream()
                .filter(idx -> !mealSet.contains(idx))
                .collect(Collectors.toList());
        int[] budgets = bucketBudgets(cfg, meals.size() >= 1, meals.size() >= 2, meals.size() >= 3);
        // 도착 허브(공항 도착·수속)는 활동 목록에 없지만 오전 시간을 실제로 먹는다. 빼주지 않으면
        // 오전 예산이 부풀어 활동이 하나 더 들어가고 점심이 창 밖으로 밀린다
        // (실측 71/72 day1: 허브 08:30~10:00 뒤 120분 오름 → 점심 14:03).
        if (day.arrivalHubIndex != null) {
            PlaceCandidate hub = byIndex(candidates, day.arrivalHubIndex);
            if (hub != null) {
                budgets[0] = Math.max(0, budgets[0] - resolveVisitMinutes(hub) - INTRA_BUCKET_TRAVEL_MINUTES);
            }
        }
        // 버킷별 사용 분 누적 — 실제 체류시간으로 채워야 시계와 용량이 어긋나지 않는다
        int[] usedM = {0}, usedA = {0}, usedE = {0};
        List<Integer> morning = new ArrayList<>(), afternoon = new ArrayList<>(), evening = new ArrayList<>();
        for (Integer idx : activities) {
            PlaceCandidate actCand = byIndex(candidates, idx);
            boolean placed = switch (timePreference(actCand)) {
                case DAYTIME -> addIfFitsBudget(morning, budgets[0], usedM, idx, actCand, false)
                        || addIfFitsBudget(afternoon, budgets[1], usedA, idx, actCand, true);
                case EVENING -> addIfFitsBudget(evening, budgets[2], usedE, idx, actCand, false);
                case FLEXIBLE -> addIfFitsBudget(morning, budgets[0], usedM, idx, actCand, false)
                        || addIfFitsBudget(afternoon, budgets[1], usedA, idx, actCand, true)
                        || addIfFitsBudget(evening, budgets[2], usedE, idx, actCand, false);
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

        // 버킷이 확정된 뒤에 식사 슬롯을 배정한다(C3). 버킷 경계를 알아야 "점심이 오전 마지막
        // 활동과 오후 첫 활동 사이에서 우회를 최소화하는 식당"인지 판정할 수 있다 — 경계를 모른 채
        // 그날 중심 최근접으로 고르면 오전(동쪽) → 점심(서쪽) → 오후(동쪽) 왕복이 생긴다.
        MealSlots slots = assignMealSlots(meals, day, candidates, morning, afternoon, startFrom, dayCentroid);
        Integer breakfastIdx = slots.breakfast();
        Integer lunchIdx = slots.lunch();
        Integer dinnerIdx = slots.dinner();

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
        // 전날 숙소(또는 도착 허브)에서 첫 스텝까지의 이동도 그날 주행에 포함해야 한다(C4).
        // 이 leg를 빼고 재던 기존 계산은 "숙소에서 60km 떨어진 곳에서 하루를 시작"하는 day를
        // 상한 이내로 오판했다(실측: 하루 196km).
        PlaceCandidate budgetOrigin = (startFrom != null && !seq.isEmpty() && seq.get(0) != startFrom.index())
                ? startFrom : null;
        trimSequenceOverBudget(day, seq, candidates, spareIndices, transportPref, protectedIdx, budgetOrigin);
        // 귀가 시각 상한 — 거리 예산과 별개다. 상한 내여도 체류·이동이 쌓이면 23시 귀가가 된다.
        trimSequenceForDepartureTime(day, seq, candidates, spareIndices, cfg, budgetOrigin,
                transportPref, protectedIdx, breakfastIdx, lunchIdx, dinnerIdx);

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
            if (isSameIndex(day.departureHubIndex, idx)) {
                // 귀가 허브는 탑승 수속 시간이지 관광이 아니다 — 기본 90분이면 종료가 그만큼 밀린다
                visitMinutes = DEPARTURE_HUB_MINUTES;
            } else if (isMorningDeparture) {
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

            // 시각 가드 — 버킷은 "개수"만 세기 때문에, 정상 배치된 장소도 시계가 밀리면 한밤중에
            // 시작할 수 있다(실측: 해변 20:37, 야외 지질공원 18:13~21:13). 확정된 시각을 보고
            // 한 번 더 거른다. 식사·허브·숙소·사용자 필수 장소는 구조상 빼면 안 되므로 제외.
            boolean fixedSlot = isSameIndex(day.arrivalHubIndex, idx)
                    || isSameIndex(day.departureHubIndex, idx)
                    || isSameIndex(day.accommodationIndex, idx)
                    || isSameIndex(breakfastIdx, idx)
                    || isSameIndex(lunchIdx, idx)
                    || isSameIndex(dinnerIdx, idx);
            if (!fixedSlot && !cand.userSelected()) {
                int latestStart = Math.min(cfg.eveningCap, ABSOLUTE_LATEST_START_MINUTES);
                if (timePreference(cand) == TimePreference.DAYTIME) {
                    latestStart = Math.min(latestStart, daytimeLatestStart(cfg));
                }
                if (startMin > latestStart) {
                    day.placeIndices.remove(Integer.valueOf(idx));
                    spareIndices.addLast(idx);
                    log.info("시각 가드 트림: day={} {} ({} 시작, 상한 {})", day.dayNumber, cand.name(),
                            formatMinutes(startMin), formatMinutes(latestStart));
                    continue;
                }
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

    /**
     * 버킷에 활동을 넣되 <b>분 예산</b>으로 판단한다. 체류시간 + 구간 이동 여유를 누적해,
     * 180분짜리 장소가 110분 한 칸만 차지하던 불일치를 없앤다.
     * 빈 버킷에는 예산을 넘겨도 1개는 넣는다(기존 {@code Math.max(1, ...)} 정신 보존).
     */
    private boolean addIfFitsBudget(List<Integer> bucket, int budgetMinutes, int[] usedMinutes,
                                    Integer idx, PlaceCandidate cand, boolean guaranteeOne) {
        if (budgetMinutes <= 0) return false;
        int need = resolveVisitMinutes(cand) + INTRA_BUCKET_TRAVEL_MINUTES;
        boolean fits = usedMinutes[0] + need <= budgetMinutes;
        // 빈 버킷 1개 보장은 오후에만 준다. 오전·저녁은 뒤에 식사·마감이라는 하드 데드라인이 있어
        // 예산을 넘겨 넣으면 그게 그대로 밀린다(실측 71 day1: 오전에 120분 오름이 들어가 점심이
        // 14:03로 밀려 "점심 없음"). 오후는 가장 넓고 다른 버킷의 폴백 목적지라 하루가 비지 않게 지킨다.
        if (!fits && !(guaranteeOne && bucket.isEmpty())) return false;
        bucket.add(idx);
        usedMinutes[0] += need;
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
            // 데이터가 "저녁 전용"/"주간 전용"이라고 명시할 때만 그대로 따른다. 그래야 야간 조명
            // 명소(제주불빛정원 {evening,night})가 지형 판정에 눌려 낮으로 가지 않는다.
            if (evening && !daytime) return TimePreference.EVENING;
            if (daytime && !evening) return TimePreference.DAYTIME;
            // 섞여 있으면("{morning,afternoon,evening}") 판단을 유보하고 아래 지형 판정으로 넘긴다.
            // enrich가 시간대를 느슨하게 나열하는 경우가 많아 그대로 FLEXIBLE로 두면 해변이
            // 저녁 버킷에 들어간다(실측: 세기알해변 20:37).
        }
        if (PlaceCategoryConstants.isBar(c.category())) return TimePreference.EVENING;
        if (PlaceCategoryConstants.isDaytimeOutdoor(c.category())) return TimePreference.DAYTIME;
        // 총칭 리프라 카테고리로는 못 걸러지는 야외 장소(해변·오름·공원)를 세부 유형으로 판정한다
        // — 실측: "하고수동해변"이 Tourist Attraction이라 19:56 저녁 스텝에 배치됐다.
        if (PlaceCategoryConstants.isDaytimeSubType(subTypeOf(c))) return TimePreference.DAYTIME;
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

            int gap = mealStart - arrivalAtMeal;
            Integer fillIdx = pickGapFiller(prev, mealCand, candidates, spareIndices, usedIndices, transportPref);
            if (fillIdx == null && gap >= LARGE_GAP_MINUTES) {
                // 3시간 이상 비면 spare가 말라도 그냥 두지 않는다 — 아직 어디에도 안 쓰인 후보까지
                // 넓혀 한 번 더 찾는다(반경·시간대 조건은 동일하게 적용).
                fillIdx = pickGapFiller(prev, mealCand, candidates,
                        new ArrayDeque<>(unusedIndices(candidates, usedIndices)), usedIndices, transportPref);
                if (fillIdx != null) {
                    log.info("긴 공백({}분) — 미사용 후보까지 확장해 보충: day={}", gap, day.dayNumber);
                }
            }
            if (fillIdx == null) {
                if (gap >= LARGE_GAP_MINUTES) {
                    log.info("긴 공백({}분) 미충전: day={} (반경·시간대 조건을 만족하는 후보 없음)",
                            gap, day.dayNumber);
                }
                break;
            }
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

    /** 아직 어느 day에도 쓰이지 않은 후보 인덱스. 긴 공백을 채울 때 spare 다음 순번으로 쓴다. */
    private List<Integer> unusedIndices(List<PlaceCandidate> candidates, Set<Integer> usedIndices) {
        return candidates.stream()
                .map(PlaceCandidate::index)
                .filter(i -> !usedIndices.contains(i))
                .collect(Collectors.toList());
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
                .filter(c -> !PlaceCategoryConstants.isViaRoute(c.name(), c.category(), c.tags()))
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
                                        Set<Integer> protectedIdx, PlaceCandidate origin) {
        double[] limits = TRANSPORT_ABS_LIMITS.getOrDefault(transportPref, TRANSPORT_ABS_LIMITS.get("any"));
        double legCap = limits[0];
        double dayCap = limits[1];
        double[] originCoord = coordsOf(origin);

        for (int guard = 0; guard < 2 && seq.size() > 1; guard++) {
            // 하루 실주행 = (출발지 → 첫 스텝) + 스텝 간 이동. 출발지가 있으면 맨 앞에 붙여 함께 잰다.
            List<double[]> coords = new ArrayList<>(seq.size() + 1);
            int offset = 0;
            if (originCoord != null) {
                coords.add(originCoord);
                offset = 1;
            }
            for (Integer i : seq) coords.add(coordsOf(byIndex(candidates, i)));

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
                double gain = removalGain(coords, i + offset);
                if (gain > bestGain) {
                    bestGain = gain;
                    pos = i;
                }
            }
            if (pos < 0) {
                // 전부 보호 대상(식사·허브·숙소)이라 뺄 게 없다 — 제거 대신 "가장 먼 것을 가까운
                // 같은 종류로 교체"를 시도한다. 필수 슬롯을 지키면서 거리만 줄이는 유일한 수단.
                if (replaceFarthestWithNearbySpare(day, seq, candidates, spareIndices, coords, offset)) {
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

    /**
     * 거리 예산 초과인데 트림 가능한 활동이 없을 때, 우회 기여가 가장 큰 스텝(허브·숙소 제외)을
     * 같은 대분류의 가까운 spare로 교체한다. 필수 슬롯(식사)을 없애지 않고 거리만 줄인다.
     *
     * @return 교체가 일어났으면 true
     */
    private boolean replaceFarthestWithNearbySpare(DayState day, List<Integer> seq,
                                                   List<PlaceCandidate> candidates, Deque<Integer> spareIndices,
                                                   List<double[]> coords, int offset) {
        int bestPos = -1;
        double bestGain = 0;
        for (int i = 0; i < seq.size(); i++) {
            Integer idx = seq.get(i);
            // 허브·숙소는 구조적으로 고정 — 교체 대상이 아니다
            String major = PlaceCategoryConstants.majorCategory(byIndex(candidates, idx) != null
                    ? byIndex(candidates, idx).category() : null);
            if ("TRANSIT_HUB".equals(major) || "LODGING".equals(major)) continue;
            PlaceCandidate c = byIndex(candidates, idx);
            if (c != null && c.userSelected()) continue; // 사용자 필수는 교체하지 않는다
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
        Integer replacement = pickCloserSameMajorSpare(seq, candidates, spareIndices, targetCand, prev, next);
        if (replacement == null) return false;

        seq.set(bestPos, replacement);
        replaceInDay(day, target, replacement);
        spareIndices.remove(replacement);
        spareIndices.addLast(target);
        log.info("거리 예산 초과 — 보호 대상 교체: day={} {} → {}", day.dayNumber,
                targetCand.name(), nameOf(candidates, replacement));
        return true;
    }

    /** 같은 대분류 spare 중 prev→후보→next 경유 거리가 현재의 절반 미만인 가장 가까운 후보. */
    private Integer pickCloserSameMajorSpare(List<Integer> seq, List<PlaceCandidate> candidates,
                                             Deque<Integer> spareIndices, PlaceCandidate target,
                                             double[] prev, double[] next) {
        String major = PlaceCategoryConstants.majorCategory(target.category());
        double currentDetour = detourVia(prev, coordsOf(target), next);
        Set<Integer> inSeq = new HashSet<>(seq);
        return spareIndices.stream()
                .map(i -> byIndex(candidates, i))
                .filter(Objects::nonNull)
                .filter(c -> !inSeq.contains(c.index()))
                .filter(c -> major.equals(PlaceCategoryConstants.majorCategory(c.category())))
                .filter(c -> coordsOf(c) != null)
                .filter(c -> detourVia(prev, coordsOf(c), next) < currentDetour * 0.5)
                .min(Comparator.comparingDouble(c -> detourVia(prev, coordsOf(c), next)))
                .map(PlaceCandidate::index)
                .orElse(null);
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
     * 아침 슬롯이 허용되지 않는 날(도착일 등)에 3식이 잡혀 있으면 경로상 첫 식사를 spare로 돌린다.
     * 도착일의 첫 스텝은 관광 또는 점심부터 시작하는 것이 자연스럽다.
     */
    private List<Integer> dropBreakfastIfNotAllowed(List<Integer> meals, DayState day,
                                                    List<PlaceCandidate> candidates,
                                                    Deque<Integer> spareIndices, boolean allowBreakfast) {
        if (allowBreakfast || meals.size() < 3) return meals;

        Integer candidate = meals.get(0);
        PlaceCandidate bc = byIndex(candidates, candidate);
        if (bc != null && bc.userSelected()) return meals; // 사용자 필수 식당은 버리지 않는다

        List<Integer> remaining = new ArrayList<>(meals);
        remaining.remove(candidate);
        day.placeIndices.remove(candidate);
        spareIndices.addLast(candidate);
        log.info("아침 슬롯 미허용(도착일/숙소 출발 아님) — 식사 1건 제외: day={} {}",
                day.dayNumber, bc != null ? bc.name() : candidate);
        return remaining;
    }

    /**
     * 3식일 때 숙소에서 {@link #BREAKFAST_MAX_KM_FROM_ACCOMMODATION}km를 넘는 아침은 슬롯에서
     * 빼고 spare로 돌린다(아침부터 원정 가는 일정 방지). 사용자 필수 식당은 버리지 않는다.
     * 경로 순서상 첫 번째를 아침 후보로 본다 — 슬롯 확정은 버킷 확정 후 assignMealSlots가 한다.
     */
    private List<Integer> dropDistantBreakfast(List<Integer> meals, DayState day,
                                               List<PlaceCandidate> candidates, Deque<Integer> spareIndices) {
        if (meals.size() < 3) return meals;
        double[] hotel = accommodationCoords(day, candidates);
        if (hotel == null) return meals;

        Integer candidate = meals.get(0);
        PlaceCandidate bc = byIndex(candidates, candidate);
        double[] coord = coordsOf(bc);
        if (coord == null || (bc != null && bc.userSelected())) return meals;
        if (haversine(hotel, coord) <= BREAKFAST_MAX_KM_FROM_ACCOMMODATION) return meals;

        List<Integer> remaining = new ArrayList<>(meals);
        remaining.remove(candidate);
        day.placeIndices.remove(candidate);
        spareIndices.addLast(candidate);
        log.info("아침 식사 생략(숙소에서 {}km 초과): day={} {}",
                String.format("%.0f", BREAKFAST_MAX_KM_FROM_ACCOMMODATION), day.dayNumber,
                bc != null ? bc.name() : candidate);
        return remaining;
    }

    /**
     * 확정된 활동 버킷을 기준으로 식사를 아침/점심/저녁 슬롯에 배정한다(C3).
     * <ul>
     *   <li><b>저녁</b>: 그날 숙소에 가장 가까운 식당 — 식후 장거리 귀가를 구조적으로 차단</li>
     *   <li><b>점심</b>: 오전 마지막 활동 → 식당 → 오후 첫 활동 우회가 가장 작은 식당.
     *       버킷이 비어 있으면 하루 시작점/그날 중심을 앵커로 쓴다</li>
     *   <li><b>아침</b>: 남은 하나</li>
     * </ul>
     * 사용자 필수 식당(userSelected)은 재배치 대상에서 제외한다(경로 순서 유지).
     */
    MealSlots assignMealSlots(List<Integer> meals, DayState day, List<PlaceCandidate> candidates,
                              List<Integer> morning, List<Integer> afternoon,
                              PlaceCandidate startFrom, double[] dayCentroid) {
        if (meals.isEmpty()) return new MealSlots(null, null, null);
        if (meals.size() == 1) return new MealSlots(null, meals.get(0), null);

        List<Integer> remaining = new ArrayList<>(meals);

        double[] hotel = accommodationCoords(day, candidates);
        Integer dinner = hotel != null
                ? nearestMeal(remaining, candidates, hotel)
                : remaining.get(remaining.size() - 1);
        remaining.remove(dinner);

        // 점심 앵커: 오전 마지막 활동 → (점심) → 오후 첫 활동
        double[] from = lastCoord(morning, candidates);
        if (from == null) from = coordsOf(startFrom);
        if (from == null) from = dayCentroid;
        double[] to = firstCoord(afternoon, candidates);
        if (to == null) to = coordsOf(byIndex(candidates, dinner));
        if (to == null) to = dayCentroid;

        Integer lunch = minDetourMeal(remaining, candidates, from, to);
        remaining.remove(lunch);

        Integer breakfast = remaining.isEmpty() ? null : remaining.get(0);
        return new MealSlots(breakfast, lunch, dinner);
    }

    /** from→식당→to 우회가 가장 작은 식사. 사용자 필수 식당은 재배치 대상에서 제외한다. */
    private Integer minDetourMeal(List<Integer> meals, List<PlaceCandidate> candidates,
                                  double[] from, double[] to) {
        Integer best = null;
        double bestDetour = Double.MAX_VALUE;
        for (Integer m : meals) {
            PlaceCandidate c = byIndex(candidates, m);
            if (c != null && c.userSelected()) continue;
            double[] coord = coordsOf(c);
            if (coord == null) continue;
            double detour = detourVia(from, coord, to);
            if (detour < bestDetour) {
                bestDetour = detour;
                best = m;
            }
        }
        return best != null ? best : meals.get(0);
    }

    /** anchor에 가장 가까운 식사 후보. 사용자 필수 식당은 재배치 대상에서 제외한다. */
    private Integer nearestMeal(List<Integer> meals, List<PlaceCandidate> candidates, double[] anchor) {
        Integer nearest = null;
        double best = Double.MAX_VALUE;
        for (Integer m : meals) {
            PlaceCandidate c = byIndex(candidates, m);
            if (c != null && c.userSelected()) continue; // 사용자 선택은 원 위치 유지
            double[] coord = coordsOf(c);
            if (coord == null) continue;
            double d = haversine(coord, anchor);
            if (d < best) {
                best = d;
                nearest = m;
            }
        }
        // 전부 userSelected거나 좌표 불명이면 경로 순서를 그대로 따른다.
        return nearest != null ? nearest : meals.get(meals.size() - 1);
    }

    private double[] lastCoord(List<Integer> bucket, List<PlaceCandidate> candidates) {
        for (int i = bucket.size() - 1; i >= 0; i--) {
            double[] c = coordsOf(byIndex(candidates, bucket.get(i)));
            if (c != null) return c;
        }
        return null;
    }

    private double[] firstCoord(List<Integer> bucket, List<PlaceCandidate> candidates) {
        for (Integer idx : bucket) {
            double[] c = coordsOf(byIndex(candidates, idx));
            if (c != null) return c;
        }
        return null;
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
        int[] budgets = bucketBudgets(cfg, hasLunch, hasDinner, hasBreakfast);
        return new int[]{
                Math.max(0, budgets[0] / ACTIVITY_SLOT_MINUTES),
                Math.max(budgets[1] > 0 ? 1 : 0, budgets[1] / ACTIVITY_SLOT_MINUTES),
                Math.max(0, budgets[2] / ACTIVITY_SLOT_MINUTES)};
    }

    /**
     * 오전/오후/저녁 시간창의 <b>분 단위 예산</b>.
     *
     * <p>예전엔 여기서 바로 "개수"를 냈는데, 활동 1개를 {@value ScheduleTuning#ACTIVITY_SLOT_MINUTES}분으로 고정
     * 가정하는 바람에 체류 180분짜리 장소도 1칸만 차지했다. 그 결과 버킷은 안 넘쳤는데 시계는
     * 3시간 밀려 야외 장소가 한밤중에 시작됐다(실측 67 day3: 국가지질공원 18:13~21:13).
     * 실제 체류시간으로 채우도록 분을 그대로 돌려준다.
     */
    private int[] bucketBudgets(ScheduleConfig cfg, boolean hasLunch, boolean hasDinner, boolean hasBreakfast) {
        int densityMinutes = cfg.densityDelta * ACTIVITY_SLOT_MINUTES;
        int morningStart = cfg.morningStart + (hasBreakfast ? DINING_VISIT_MINUTES : 0);
        if (hasLunch && hasDinner) {
            int budgetM = Math.max(0, LUNCH_START - morningStart);
            int budgetA = Math.max(ACTIVITY_SLOT_MINUTES,
                    DINNER_START - (LUNCH_START + DINING_VISIT_MINUTES) + densityMinutes);
            int budgetE = Math.max(0, cfg.eveningCap - (DINNER_START + DINING_VISIT_MINUTES));
            return new int[]{budgetM, budgetA, budgetE};
        } else if (hasLunch) { // 저녁 없음 → 오후가 상한까지 확장
            int budgetM = Math.max(0, LUNCH_START - morningStart);
            int budgetA = Math.max(ACTIVITY_SLOT_MINUTES,
                    cfg.eveningCap - (LUNCH_START + DINING_VISIT_MINUTES) + densityMinutes);
            return new int[]{budgetM, budgetA, 0};
        } else { // 식사 없음 → 하루 전체를 한 버킷으로
            int budgetAll = Math.max(ACTIVITY_SLOT_MINUTES, cfg.eveningCap - morningStart + densityMinutes);
            return new int[]{budgetAll, 0, 0};
        }
    }

    /**
     * 세부 유형 반복 상한(C1). 같은 세부 유형(카테고리 경로의 마지막 토큰)이 전체 일정에서
     * {@code ceil(days/2) + 1}회를 넘으면 초과분을 spare로 보내고, 그 자리를 아직 쓰이지 않은
     * 다른 유형의 spare로 보충한다.
     *
     * <p>추가로 경유형([경유] — 해안도로·드라이브코스·둘레길)은 전체 일정 1개로 제한한다.
     * 이들은 leafCategory가 "Tourist Attraction"으로 같아 유형 상한에 걸리지 않지만, "90분 머무는
     * 방문지"가 아니라 지나가는 구간이라 반복되면 일정이 무의미해진다(실측: 한 일정에 해안도로 7곳).
     *
     * <p>사용자 필수 장소(userSelected)는 상한 계산에는 포함하되 제거 대상에서는 제외한다.
     */
    void enforceTypeDiversity(List<DayState> days, List<PlaceCandidate> candidates, Deque<Integer> spare,
                              List<List<Integer>> pairs) {
        int typeCap = (int) Math.ceil(days.size() / 2.0) + TYPE_CAP_BASE;
        Set<Integer> pairedIndices = pairs == null ? Set.of() : pairs.stream()
                .filter(p -> p != null && p.size() == 2)
                .flatMap(List::stream)
                .collect(Collectors.toSet());
        Map<String, Integer> typeCount = new HashMap<>();
        int viaCount = 0;

        for (DayState day : days) {
            for (Integer idx : new ArrayList<>(day.placeIndices)) {
                PlaceCandidate c = byIndex(candidates, idx);
                if (c == null) continue;

                boolean via = PlaceCategoryConstants.isViaRoute(c.name(), c.category(), c.tags());
                // 총칭 리프("Tourist Attraction")는 이름·태그로 세부 유형을 추론해 상한 키로 쓴다
                // — 추론이 없으면 오름 3연속이 "같은 유형"으로 인식되지 않아 상한이 무력해진다.
                String type = subTypeOf(c);
                int count = typeCount.getOrDefault(type, 0);

                boolean overType = isSpecificType(type) && count >= typeCap;
                boolean overVia = via && viaCount >= VIA_ROUTE_CAP;
                if (!overType && !overVia) {
                    typeCount.put(type, count + 1);
                    if (via) viaCount++;
                    continue;
                }
                // 사용자 필수·pair 멤버는 상한을 넘겨도 유지(포함·pair 무결성이 우선)
                if (c.userSelected() || pairedIndices.contains(idx)) {
                    typeCount.put(type, count + 1);
                    if (via) viaCount++;
                    continue;
                }

                day.placeIndices.remove(idx);
                spare.addLast(idx);
                log.info("유형 다양성 상한 초과 — 트림: day={} {} ({})", day.dayNumber, c.name(),
                        overVia ? "경유형 상한 " + VIA_ROUTE_CAP : type + " " + typeCap + "회 초과");

                Integer replacement = pickDiverseSpare(day, candidates, spare, typeCount, typeCap);
                if (replacement != null) {
                    day.placeIndices.add(replacement);
                    spare.remove(replacement);
                    PlaceCandidate rc = byIndex(candidates, replacement);
                    typeCount.merge(subTypeOf(rc), 1, Integer::sum);
                    log.info("유형 다양성 보충: day={} {}", day.dayNumber, rc.name());
                }
            }
        }
    }

    /**
     * 유형 상한을 적용할 만큼 구체적인 리프인지. "Tourist Attraction"/"Landmark"처럼 대분류와
     * 다름없는 총칭 리프는 제외한다(그 범주 안에 서로 다른 종류의 명소가 전부 들어오기 때문).
     */
    private boolean isSpecificType(String type) {
        return !PlaceCategoryConstants.isGenericLeaf(type);
    }

    /**
     * 유형 상한·시간대 버킷·대안 매칭이 공유하는 세부 유형 키(2번 항목). 총칭 리프는
     * 이름·태그·설명으로 추론된 유형이 돌아온다.
     */
    private String subTypeOf(PlaceCandidate c) {
        if (c == null) return "";
        return PlaceCategoryConstants.subType(c.name(), c.category(), c.tags(), c.description());
    }

    /** 상한에 걸리지 않은 다른 유형의 spare 중, 그날 중심에서 가장 가까운 방문 가능 후보. */
    private Integer pickDiverseSpare(DayState day, List<PlaceCandidate> candidates, Deque<Integer> spare,
                                     Map<String, Integer> typeCount, int typeCap) {
        double[] centroid = centroidOf(day.placeIndices, candidates);
        Set<Integer> used = collectUsedIndicesIncludingSpareUse(day);
        return spare.stream()
                .map(i -> byIndex(candidates, i))
                .filter(Objects::nonNull)
                .filter(this::isVisitableFill)
                .filter(c -> !used.contains(c.index()))
                .filter(c -> !PlaceCategoryConstants.isViaRoute(c.name(), c.category(), c.tags()))
                .filter(c -> typeCount.getOrDefault(subTypeOf(c), 0) < typeCap)
                .filter(c -> centroid == null || coordsOf(c) != null)
                .min(Comparator.comparingDouble(c -> centroid == null ? 0
                        : haversine(centroid, coordsOf(c))))
                .map(PlaceCandidate::index)
                .orElse(null);
    }

    private Set<Integer> collectUsedIndicesIncludingSpareUse(DayState day) {
        return new HashSet<>(day.placeIndices);
    }

    /**
     * 사용자 카테고리 커버리지 보충(C2). 사용자가 고른 카테고리 중 일정에 한 번도 등장하지 않은
     * 것이 있으면, spare에서 그 카테고리에 해당하는 후보를 찾아 quota 여유가 있는 day에 넣는다.
     * 후보가 없으면 로그만 남긴다 — 없는 유형을 억지로 만들어내지 않는다.
     */
    void ensureCategoryCoverage(List<DayState> days, List<PlaceCandidate> candidates, Deque<Integer> spare,
                                List<String> userCategories, int maxPerDay) {
        if (userCategories == null || userCategories.isEmpty()) return;

        for (String categoryId : userCategories) {
            if (categoryId == null || categoryId.isBlank()) continue;

            boolean covered = days.stream()
                    .flatMap(d -> d.placeIndices.stream())
                    .map(i -> byIndex(candidates, i))
                    .filter(Objects::nonNull)
                    .anyMatch(c -> PlaceCategoryConstants.matchesUserCategory(
                            categoryId, c.name(), c.category(), c.tags()));
            // 숙소는 accommodationIndex로 별도 배정되므로 placeIndices에 없어도 커버된 것으로 본다
            if (!covered && "accommodation".equalsIgnoreCase(categoryId.trim())) {
                covered = days.stream().anyMatch(d -> d.accommodationIndex != null);
            }
            if (covered) continue;

            Integer pick = spare.stream()
                    .map(i -> byIndex(candidates, i))
                    .filter(Objects::nonNull)
                    .filter(this::isVisitableFill)
                    .filter(c -> PlaceCategoryConstants.matchesUserCategory(
                            categoryId, c.name(), c.category(), c.tags()))
                    .max(Comparator.comparing(c -> c.rating() != null ? c.rating() : BigDecimal.ZERO))
                    .map(PlaceCandidate::index)
                    .orElse(null);

            if (pick == null) {
                log.info("카테고리 커버리지 미충족(후보 없음): {}", categoryId);
                continue;
            }

            DayState target = pickDayForCoverage(days, candidates, byIndex(candidates, pick), maxPerDay);
            if (target == null) {
                log.info("카테고리 커버리지 미충족(모든 day가 quota 상한): {}", categoryId);
                continue;
            }
            target.placeIndices.add(pick);
            spare.remove(pick);
            log.info("카테고리 커버리지 보충: {} → day={} {}", categoryId, target.dayNumber,
                    nameOf(candidates, pick));
        }
    }

    /** quota 여유가 있는 day 중 centroid가 가장 가까운 곳. 전부 꽉 찼으면 null. */
    private DayState pickDayForCoverage(List<DayState> days, List<PlaceCandidate> candidates,
                                        PlaceCandidate cand, int maxPerDay) {
        double[] target = coordsOf(cand);
        DayState best = null;
        double bestDist = Double.MAX_VALUE;
        for (DayState d : days) {
            if (d.placeIndices.size() >= maxPerDay) continue;
            double[] centroid = centroidOf(d.placeIndices, candidates);
            double dist = (target == null || centroid == null) ? d.placeIndices.size() : haversine(target, centroid);
            if (dist < bestDist) {
                bestDist = dist;
                best = d;
            }
        }
        return best;
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

    /**
     * 그날 시간창에 들어갈 수 있는 활동 <b>개수</b> 추정. 분 예산을 그날 활동들의 실제 평균
     * 체류시간으로 나눠, 장시간 체류 장소가 많은 day를 과대평가하지 않는다.
     */
    private int totalActivityCapacity(DayState day, List<PlaceCandidate> candidates, ScheduleConfig cfg) {
        long dining = day.placeIndices.stream().filter(idx -> isDiningCand(byIndex(candidates, idx))).count();
        int[] budgets = bucketBudgets(cfg, dining >= 1, dining >= 2, dining >= 3);
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

    /**
     * 테마 기반 하루 시간창 설정(C6). 사용자가 고른 테마 전부를 {@link #THEME_SCHEDULES}에서 찾아
     * "가장 이른 시작 · 가장 늦은 상한 · 밀도 보정 합"으로 병합한다. 표에 없는 값(미지의 테마)은
     * 무시하고, 아무 테마도 매칭되지 않으면 기본값(09:00~22:00)을 쓴다.
     */
    ScheduleConfig scheduleConfig(List<String> themes) {
        if (themes == null || themes.isEmpty()) {
            return new ScheduleConfig(DAY_START_MINUTES, DEFAULT_EVENING_CAP_MINUTES, 0, false);
        }

        int morningStart = Integer.MAX_VALUE;
        int eveningCap = Integer.MIN_VALUE;
        int densityDelta = 0;
        boolean indoorPreferred = false;
        boolean matched = false;

        for (String theme : themes) {
            if (theme == null || theme.isBlank()) continue;
            ThemeSchedule ts = THEME_SCHEDULES.get(theme.trim().toLowerCase());
            if (ts == null) continue;
            matched = true;
            morningStart = Math.min(morningStart, ts.morningStart());
            eveningCap = Math.max(eveningCap, ts.eveningCap());
            densityDelta += ts.densityDelta();
            indoorPreferred |= ts.indoorPreferred();
        }

        if (!matched) {
            return new ScheduleConfig(DAY_START_MINUTES, DEFAULT_EVENING_CAP_MINUTES, 0, false);
        }
        // 여러 테마를 고르면 밀도 보정이 합산돼 극단으로 갈 수 있어 ±1로 묶는다.
        densityDelta = Math.max(-1, Math.min(1, densityDelta));
        return new ScheduleConfig(
                Math.max(MIN_MORNING_START_MINUTES, morningStart),
                Math.min(MAX_EVENING_CAP_MINUTES, eveningCap),
                densityDelta, indoorPreferred);
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
        // subRegion/description은 저장에 쓰이지 않고 story 생성(Haiku) 컨텍스트로만 전달된다.
        return new PlaceData(c.name(), c.address(), c.category(), c.region(), c.country(),
                c.subRegion(), c.description());
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

}
