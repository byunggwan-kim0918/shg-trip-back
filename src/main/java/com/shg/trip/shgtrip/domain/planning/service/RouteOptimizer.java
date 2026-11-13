package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.planning.dto.AlternativeData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.ScheduleArgs;
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
 * AI가 고른 장소의 날짜 배분·순서·시각을 결정론적으로 확정한다. 판정은 InvariantChecker에 맡기고 여기서는 수리만 한다.
 */
@Slf4j
@Component
public class RouteOptimizer {

    private final InvariantChecker invariantChecker = new InvariantChecker();

    private static final Set<String> INTENT_TIMES = Set.of("morning", "evening");

    /**
     * 같은 날 step을 좌표 기반으로 재정렬한다. 시간 슬롯은 원래 자리에 두고 장소만 옮긴다.
     *
     * @param placeCache "장소명|주소" → Place 캐시 (좌표 출처)
     */
    public List<StepData> optimize(List<StepData> steps, Map<String, Place> placeCache) {
        if (steps == null || steps.size() <= 1) return steps;

        Map<Integer, List<StepData>> byDay = steps.stream()
                .collect(Collectors.groupingBy(StepData::dayNumber, TreeMap::new, Collectors.toList()));

        List<StepData> result = new ArrayList<>();
        int globalOrder = 1;

        for (Map.Entry<Integer, List<StepData>> entry : byDay.entrySet()) {
            List<StepData> daySteps = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(StepData::stepOrder))
                    .collect(Collectors.toList());

            if (daySteps.size() <= 2) {
                for (StepData s : daySteps) {
                    result.add(withStepOrder(s, globalOrder++));
                }
                continue;
            }

            // 식사 step은 시간대에 묶여 있어 재정렬하지 않는다.
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

            if (movable.size() <= 2) {
                for (StepData s : daySteps) {
                    result.add(withStepOrder(s, globalOrder++));
                }
                continue;
            }

            List<StepData> reorderedMovable = reorderByNearestNeighbor(movable, placeCache);

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

            // 앞 장소가 바뀐 step의 교통 정보는 무효라서 비운다.
            for (int i = 0; i < merged.size(); i++) {
                StepData original = daySteps.get(i);
                StepData placed  = merged.get(i);
                boolean samePlace = placed == original;
                boolean isFirst   = (i == 0);

                result.add(new StepData(
                        globalOrder++,
                        placed.dayNumber(),
                        original.startTime(),
                        original.endTime(),
                        placed.place(),
                        placed.alternatives(),
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

    /** 비어 있는 교통 정보를 Haversine 거리로 대략 추정해 채운다. */
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
     * DB 카테고리는 Foursquare 계층 경로라서 문자열 비교 대신 대분류로 식당을 판정한다.
     */
    private boolean isMealStep(StepData s) {
        if (s.place() == null) return false;
        if (!"DINING".equals(PlaceCategoryConstants.majorCategory(s.place().category()))) return false;
        int startMin = parseTimeToMinutes(s.startTime());
        if (startMin < 0) return false;
        return (startMin >= LUNCH_START && startMin <= LUNCH_END)
                || (startMin >= DINNER_START && startMin <= DINNER_END);
    }

    /** "HH:mm" → 분. 파싱 실패 시 -1. */
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

    private List<StepData> reorderByNearestNeighbor(List<StepData> daySteps, Map<String, Place> placeCache) {
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

        if (coords.size() <= 2) {
            return daySteps;
        }

        List<StepData> coordSteps = new ArrayList<>(coords.keySet());
        double originalDist = totalDistance(coordSteps, coords);

        List<StepData> optimized = new ArrayList<>();
        Set<StepData> visited = new HashSet<>();

        // 첫 장소(숙소 출발 등)는 고정한다.
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

        // 개선이 20% 미만이면 AI가 의도한 경험 흐름을 보존한다.
        if (originalDist > 0 && (originalDist - optimizedDist) / originalDist < 0.20) {
            log.debug("Route optimization skipped for day (improvement < 20%): original={}km, optimized={}km",
                    String.format("%.1f", originalDist), String.format("%.1f", optimizedDist));
            return daySteps;
        }

        log.info("Route optimized: {}km → {}km ({}% improvement)",
                String.format("%.1f", originalDist), String.format("%.1f", optimizedDist),
                String.format("%.0f", (originalDist - optimizedDist) / originalDist * 100));

        optimized.addAll(noCoords);
        return optimized;
    }

    private double[] resolveCoords(PlaceData pd, Map<String, Place> placeCache) {
        if (pd == null) return null;
        String key = placeKey(pd);
        Place place = placeCache.get(key);
        if (place == null || place.getLatitude() == null || place.getLongitude() == null) return null;
        // DB에 남아 있는 좌표 (0,0) fallback 행은 좌표 없음으로 본다.
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
    // Repair & Schedule: Sonnet의 날짜별 구성을 힌트로 받아 하드 제약을 수리하고 순서·시각·교통·대안을 확정한다.
    // LLM을 다시 부르지 않으며 notes는 비워 두고 Haiku가 나중에 채운다.
    // ========================================================================================

    public List<StepData> repairAndSchedule(SelectionOutput selection, List<PlaceCandidate> candidates, String pace) {
        return repairAndSchedule(selection, candidates, pace, "any", null);
    }

    /**
     * @param startDate 여행 시작일. 요일로 정기휴무 장소를 열려 있는 spare로 교체한다. null이면 휴무 회피를 건너뛴다.
     */
    public List<StepData> repairAndSchedule(SelectionOutput selection, List<PlaceCandidate> candidates,
                                             String pace, java.time.LocalDate startDate) {
        return repairAndSchedule(selection, candidates, pace, "any", startDate);
    }

    /**
     * @param transportPref walk / car / any. 하루 동선의 거리 상한 배수에 반영된다.
     */
    public List<StepData> repairAndSchedule(SelectionOutput selection, List<PlaceCandidate> candidates,
                                             String pace, String transportPref, java.time.LocalDate startDate) {
        return repairAndSchedule(selection, candidates, pace, transportPref, startDate, List.of());
    }

    /**
     * @param themes 여행 테마. 야경류는 저녁 상한을 늦추고 일출·등산류는 아침 시작을 당긴다.
     *               null/빈 값이면 기본값(09:00~22:00).
     */
    public List<StepData> repairAndSchedule(SelectionOutput selection, List<PlaceCandidate> candidates,
                                             String pace, String transportPref, java.time.LocalDate startDate,
                                             List<String> themes) {
        return repairAndSchedule(selection, candidates, pace, transportPref, startDate, themes, null, false);
    }

    /**
     * @param hub enrich가 정한 도착/출발 허브 이름. 공항 본체와 부속시설 POI가 rating 동률이어도 이름으로 허브를 고른다.
     *            null이면 이름 교정을 건너뛴다.
     * @param compactMode 유효 관광지가 부족한 풀이면 하한 quota를 완화해 엉뚱한 카테고리로 채우지 않는다.
     */
    public List<StepData> repairAndSchedule(SelectionOutput selection, List<PlaceCandidate> candidates,
                                             String pace, String transportPref, java.time.LocalDate startDate,
                                             List<String> themes, TransportationHub hub,
                                             boolean compactMode) {
        return repairAndSchedule(selection, candidates, pace, transportPref, startDate, themes, hub,
                compactMode, List.of());
    }

    /**
     * @param userCategories 생성 요청의 카테고리 id. 일정에 한 번도 없는 카테고리를 spare에서 보충한다.
     *                       빈 값이면 커버리지 보충을 건너뛴다.
     */
    public List<StepData> repairAndSchedule(SelectionOutput selection, List<PlaceCandidate> candidates,
                                             String pace, String transportPref, java.time.LocalDate startDate,
                                             List<String> themes, TransportationHub hub,
                                             boolean compactMode, List<String> userCategories) {
        return schedule(selection, candidates, pace, transportPref, startDate, themes, hub,
                compactMode, userCategories).steps();
    }

    /**
     * 확정된 스텝과 함께 끝내 해소하지 못한 품질 문제를 돌려준다. 호출부는 일정을 저장하되 이 문제를 사용자에게 알린다.
     */
    public ScheduleResult schedule(SelectionOutput selection, List<PlaceCandidate> candidates,
                                   String pace, String transportPref, java.time.LocalDate startDate,
                                   List<String> themes, TransportationHub hub,
                                   boolean compactMode, List<String> userCategories) {
        return schedule(selection, candidates, pace, transportPref, startDate, themes, hub,
                compactMode, userCategories, false);
    }

    public ScheduleResult schedule(SelectionOutput selection, List<PlaceCandidate> candidates, ScheduleArgs args) {
        if (args.cities() != null) {
            return CityBlockPlanner.schedule(selection, candidates, args, this::scheduleWithinCity);
        }
        return scheduleWithinCity(selection, candidates, args);
    }

    private ScheduleResult scheduleWithinCity(SelectionOutput selection, List<PlaceCandidate> candidates,
                                              ScheduleArgs args) {
        return schedule(selection, candidates, args.pace(), args.transportPref(), args.startDate(),
                args.themes(), args.hub(), args.compact(), args.categories(), args.couple(),
                firstDayStart(args), departureHubDeadline(args), args.resumesAfterTransfer(), args.continuesAfter());
    }

    /** 첫날 시작 시각(분). 도착 시각이 없으면 null. */
    public static Integer firstDayStart(ScheduleArgs args) {
        return ScheduleTimes.toMinutesOrNull(args.arrivalTime());
    }

    /** 마지막 날 출발 허브 도착 기한(분). 출발 시각이 없으면 null. 해외는 국제선 체크인만큼 더 이르다. */
    public static Integer departureHubDeadline(ScheduleArgs args) {
        Integer departure = ScheduleTimes.toMinutesOrNull(args.departureTime());
        if (departure == null) return null;
        boolean overseas = args.country() != null && !"KR".equalsIgnoreCase(args.country());
        int buffer = overseas ? OVERSEAS_DEPARTURE_CHECKIN_BUFFER_MINUTES : DEPARTURE_CHECKIN_BUFFER_MINUTES;
        return Math.max(0, departure - buffer);
    }

    /**
     * @param couple 커플 여행이면 테마와 무관하게 매일 오후 카페 스톱을 넣는다 ({@link ScheduleConfig#cafeStop}).
     */
    public ScheduleResult schedule(SelectionOutput selection, List<PlaceCandidate> candidates,
                                   String pace, String transportPref, java.time.LocalDate startDate,
                                   List<String> themes, TransportationHub hub,
                                   boolean compactMode, List<String> userCategories, boolean couple) {
        return schedule(selection, candidates, pace, transportPref, startDate, themes, hub, compactMode,
                userCategories, couple, null, null, false, false);
    }

    private ScheduleResult schedule(SelectionOutput selection, List<PlaceCandidate> candidates,
                                    String pace, String transportPref, java.time.LocalDate startDate,
                                    List<String> themes, TransportationHub hub,
                                    boolean compactMode, List<String> userCategories, boolean couple,
                                    Integer firstDayStart, Integer lastDayDeadline,
                                    boolean resumesAfterTransfer, boolean continuesAfter) {
        List<PlaceCandidate> withIntent = applyIntentHints(selection, candidates);
        ScheduleResult result = scheduleCandidates(selection, withIntent, pace, transportPref, startDate,
                themes, hub, compactMode, userCategories, couple, firstDayStart, lastDayDeadline,
                resumesAfterTransfer, continuesAfter);
        logIntentOutcome(withIntent, result.steps());
        return result;
    }

    /**
     * Sonnet이 표시한 요청 의도(timing·highlight)를 후보에 싣는다. 스케줄러는 요청 문장을 모르므로 이게 없으면 야경 명소가 낮에 놓인다.
     * <ul>
     *   <li>timing → {@link PlaceCandidate#intentTime()}. {@link #timePreference}가 데이터보다 먼저 읽는다</li>
     *   <li>timing·highlight → {@link PlaceCandidate#intentProtected()}. 거리·교체·유형 트림에서 빠지지만
     *       userSelected와 달리 영업시간·휴무·시각·귀가 가드는 그대로 받는다</li>
     * </ul>
     * 본선택 장소만, 식당 제외, 날짜별로 timing → highlight 순서로 {@code INTENT_PROTECT_PER_DAY}곳까지 보호한다.
     */
    List<PlaceCandidate> applyIntentHints(SelectionOutput selection, List<PlaceCandidate> candidates) {
        if (selection.days() == null) return candidates;
        Map<Integer, String> timing = new HashMap<>();
        if (selection.timing() != null) {
            for (SelectionOutput.TimingHint h : selection.timing()) {
                if (h == null || h.when() == null) continue;
                String when = h.when().trim().toLowerCase(Locale.ROOT);
                if (INTENT_TIMES.contains(when)) timing.putIfAbsent(h.index(), when);
            }
        }
        Set<Integer> highlights = selection.highlightIndices() != null
                ? new HashSet<>(selection.highlightIndices()) : Set.of();
        if (timing.isEmpty() && highlights.isEmpty()) return candidates;

        Map<Integer, String> timeOf = new HashMap<>();
        Set<Integer> protect = new HashSet<>();
        for (SelectionOutput.DayPlan d : selection.days()) {
            if (d.placeIndices() == null) continue;
            List<Integer> ranked = new ArrayList<>();
            d.placeIndices().stream().filter(timing::containsKey).forEach(ranked::add);
            d.placeIndices().stream().filter(i -> highlights.contains(i) && !timing.containsKey(i)).forEach(ranked::add);
            int kept = 0;
            for (Integer idx : ranked) {
                PlaceCandidate c = byIndex(candidates, idx);
                if (c == null || c.mealEligible()) continue;
                if (timing.containsKey(idx)) timeOf.put(idx, timing.get(idx));
                if (c.userSelected()) continue; // 이미 보호돼 있어 상한을 쓰지 않는다
                if (kept < INTENT_PROTECT_PER_DAY) {
                    protect.add(idx);
                    kept++;
                }
            }
        }
        if (timeOf.isEmpty() && protect.isEmpty()) return candidates;

        List<PlaceCandidate> out = new ArrayList<>(candidates.size());
        for (PlaceCandidate c : candidates) {
            boolean p = c != null && protect.contains(c.index());
            String t = c != null ? timeOf.get(c.index()) : null;
            out.add(p || t != null ? c.withIntent(p, t) : c);
        }
        log.info("요청 의도 반영: 시간대 {} / 보호 {}",
                timeOf.entrySet().stream().map(e -> nameOf(candidates, e.getKey()) + "→" + e.getValue()).toList(),
                protect.stream().map(i -> nameOf(candidates, i)).toList());
        return out;
    }

    /** 시간 의도 장소의 최종 배치를 남긴다. 어긋나면 WARN이며 의도 반영률 집계에 쓴다. */
    private void logIntentOutcome(List<PlaceCandidate> candidates, List<StepData> steps) {
        for (PlaceCandidate c : candidates) {
            if (c == null || c.intentTime() == null) continue;
            String key = placeKey(c.name(), c.address());
            StepData placed = steps.stream()
                    .filter(s -> s.place() != null && key.equals(placeKey(s.place().name(), s.place().address())))
                    .findFirst().orElse(null);
            if (placed == null) {
                log.warn("의도 시간대 미반영: {} 요청={} → 일정에서 빠짐", c.name(), c.intentTime());
                continue;
            }
            int start = toMinutesOrZero(placed.startTime());
            boolean ok = "evening".equals(c.intentTime()) ? start >= EVENING_ACTIVITY_EARLIEST_START : start < LUNCH_START;
            if (ok) {
                log.info("의도 시간대 반영: {} 요청={} → day{} {}", c.name(), c.intentTime(),
                        placed.dayNumber(), placed.startTime());
            } else {
                log.warn("의도 시간대 미반영: {} 요청={} → day{} {}", c.name(), c.intentTime(),
                        placed.dayNumber(), placed.startTime());
            }
        }
    }

    private ScheduleResult scheduleCandidates(SelectionOutput selection, List<PlaceCandidate> candidates,
                                              String pace, String transportPref, java.time.LocalDate startDate,
                                              List<String> themes, TransportationHub hub,
                                              boolean compactMode, List<String> userCategories, boolean couple,
                                              Integer firstDayStart, Integer lastDayDeadline,
                                              boolean resumesAfterTransfer, boolean continuesAfter) {
        int[] range = paceRange(pace);
        int minPerDay = compactMode ? Math.min(range[0], 3) : range[0];
        int maxPerDay = range[1];
        double distanceMultiplier = TRANSPORT_DISTANCE_MULTIPLIER.getOrDefault(transportPref,
                TRANSPORT_DISTANCE_MULTIPLIER.get("any"));

        List<DayState> days = selection.days().stream().map(DayState::new).collect(Collectors.toList());
        // 영업시간 판정이 스케줄 깊숙이 퍼져 있어 startDate를 끌고 내려가는 대신 day가 자기 요일을 갖는다.
        // startDate가 없으면 null로 남고 판정을 건너뛴다.
        if (startDate != null) {
            days.forEach(d -> d.dayOfWeek = startDate.plusDays(d.dayNumber - 1L).getDayOfWeek());
        }
        if (!days.isEmpty()) {
            days.get(0).startNotBefore = firstDayStart;
            days.get(0).resumesAfterTransfer = resumesAfterTransfer;
            days.get(days.size() - 1).departureDeadline = lastDayDeadline;
            days.get(days.size() - 1).continuesAfter = continuesAfter;
        }
        List<List<Integer>> pairs = selection.pairs() != null ? selection.pairs() : List.of();
        Deque<Integer> spare = new ArrayDeque<>(selection.spareIndices() != null ? selection.spareIndices() : List.of());

        // 날짜 배분을 좌표로 먼저 정한다. 아래 반응적 수리는 모델이 정한 날짜를 사후에 밀고 당길 뿐이라 넓게 걸친 날을 되돌리지 못한다.
        // 1D DP로 지리적으로 끊기는 지점에서 가변 크기로 자르고 하루 상한은 벌점으로만 둔다. 개수는 뒤의 pace quota 수리가 맞춘다.
        // 분할이 총이동을 줄이지 못하면 기각하고 모델의 배분을 쓴다(estimateTotalTourKm).
        repartitionDaysByGeography(days, candidates, pairs, maxPerDay, hub);

        boolean changed = true;
        int iteration = 0;
        while (changed && iteration < MAX_FIXPOINT_ITERATIONS) {
            changed = false;
            changed |= repairPaceQuota(days, minPerDay, maxPerDay, spare, candidates);
            changed |= repairPairs(days, pairs, maxPerDay);
            changed |= repairClusterSplit(days, candidates, maxPerDay, distanceMultiplier, pairs);
            changed |= repairDistanceOutliers(days, candidates, maxPerDay, distanceMultiplier);
            // centroid가 가장 가까운 day로 전역 재배정한다. repairClusterSplit은 인접 day·2클러스터만 봐서 전 지역에 흩어진 일정을 못 푼다.
            changed |= rebalanceDaysByGeography(days, candidates, maxPerDay, pairs);
            iteration++;
        }
        if (iteration >= MAX_FIXPOINT_ITERATIONS && changed) {
            log.warn("Repair fixpoint 미수렴 (최대 {}회 반복 후에도 재위반 존재) — 허브/quota만 보장된 상태로 진행", MAX_FIXPOINT_ITERATIONS);
        }
        // 루프가 quota를 재위반한 직후에 끝날 수 있어 1순위 제약인 pace quota를 마지막에 한 번 더 강제한다.
        repairPaceQuota(days, minPerDay, maxPerDay, spare, candidates);
        // 마지막 반복의 거리이탈 수리가 pair를 깨면 복구할 다음 회차가 없어 pair를 한 번 더 합친다.
        // 그 이동이 quota를 다시 깰 수 있어 quota도 다시 맞춘다.
        if (repairPairs(days, pairs, maxPerDay)) {
            repairPaceQuota(days, minPerDay, maxPerDay, spare, candidates);
        }

        // 오분류 데이터로 허브가 된 일반 장소를 먼저 강등해야 repairHubs가 진짜 허브로 채운다.
        sanitizeSuspiciousHubs(days, candidates);
        repairHubs(days, candidates);
        // rating만으로는 공항 본체와 부속시설 POI가 동률일 수 있어 enrich가 정한 허브 이름으로 교정한다.
        repairNamedHubs(days, candidates, hub);
        // Sonnet 숙소는 동선과 무관해 매일 장거리 귀가가 생기므로 방문지 중심에 가까운 숙소로 바꾼다.
        // continuity 앞에 둬야 짧은 여행에서 각 day 최적화 뒤 continuity가 같은 지역으로 통일한다.
        repairAccommodationByProximity(days, candidates);
        repairAccommodationContinuity(days, candidates);
        enforceLodgingCountCap(days, candidates);
        // swap이라 개수 중립이어서 fixpoint 수렴 뒤 1회만 돈다.
        repairClosedDayPlaces(days, candidates, spare, startDate, pairs);
        // 허브·숙소는 중복 정리 대상이 아니다.
        dedupeMainStepsAcrossItinerary(days, candidates, spare);
        // 같은 세부 유형·경유형 반복을 상한으로 자르고 다른 유형으로 보충한다.
        enforceTypeDiversity(days, candidates, spare, pairs);
        // 사용자가 고른 카테고리 중 일정에 0개인 것을 spare에서 보충한다.
        ensureCategoryCoverage(days, candidates, spare, userCategories, maxPerDay);
        // 보충해 넣은 것을 뒤의 거리 트림이 다시 빼지 않게, 각 카테고리의 유일 대표를 표시한다.
        markCoverageCritical(days, candidates, userCategories);
        // 맛집·로맨틱 테마나 커플이면 마지막 날을 빼고 매일 오후 카페·디저트 1곳을 넣는다.
        ScheduleConfig cfg = scheduleConfig(themes, pace).withCafeStop(couple || hasCafeTheme(themes));
        // 하루 용량을 넘는 활동을 인접 day로 이월한다. 실패분은 scheduleDay가 트림한다.
        repairDayCapacity(days, candidates, cfg, maxPerDay);
        // 카페 스톱은 이월 뒤에 정한다. 앞에서 정하면 방금 맨 뒤에 붙인 카페가 이월 1순위가 되어
        // 원래 날은 카페가 0개, 옮겨 간 날은 오후 고정이 풀린 카페가 된다.
        ensureCafeStops(days, candidates, spare, cfg, transportPref);
        moveEveningIntentOffDepartureDay(days, candidates);

        Set<Integer> highlights = selection.highlightIndices() != null
                ? new HashSet<>(selection.highlightIndices()) : Set.of();
        Set<Integer> rests = selection.restIndices() != null
                ? new HashSet<>(selection.restIndices()) : Set.of();

        Set<Integer> usedIndices = collectUsedIndices(days);
        // Sonnet이 메인과 spare에 같은 인덱스를 적으면 일정 장소가 다른 스텝의 대안으로 재등장한다.
        spare.removeAll(usedIndices);

        // 최종 불변식 패스가 day를 다시 스케줄할 때 같은 spare 조건으로 돌린다.
        List<Integer> spareBaseline = new ArrayList<>(spare);
        Map<Integer, Integer> protectedHome = protectedHomes(days, candidates);

        List<StepData> steps = scheduleAllDays(days, candidates, pairs, highlights, rests,
                spare, usedIndices, cfg, transportPref, maxPerDay);
        steps = restoreDroppedProtected(days, steps, candidates, protectedHome, pairs, highlights, rests,
                spare, spareBaseline, usedIndices, cfg, transportPref, maxPerDay, pace, startDate);

        // [최종 불변식 패스] 앞 보정은 확정 전 기준이라 scheduleDay의 버킷 용량·거리 트림이 다시 깰 수 있다.
        // 확정 스텝으로 필수 슬롯·대분류·커버리지·유형 상한을 재검사하고 고친 day만 다시 스케줄한다.
        InvariantMemo memo = new InvariantMemo();
        for (int pass = 0; pass < MAX_INVARIANT_PASSES; pass++) {
            // 낮은 우선순위 수리가 식사·거리 불변식을 깨면 되돌릴 수 있게 패스 시작 상태를 잡아 둔다.
            DaySnapshot snapshot = DaySnapshot.of(days);
            Set<Integer> usedSnapshot = new HashSet<>(usedIndices);
            Map<Integer, DayStatus> before = invariantChecker.evaluate(days, steps, transportPref, candidates, pace);

            RepairOutcome outcome = enforceFinalInvariants(days, steps, candidates, spare, usedIndices,
                    pace, transportPref, maxPerDay, userCategories, startDate, memo, cfg);
            if (!outcome.changed()) break;

            resetSpare(spare, spareBaseline, days);
            steps = scheduleAllDays(days, candidates, pairs, highlights, rests,
                    spare, usedIndices, cfg, transportPref, maxPerDay);

            // 우선순위 역전 검사: 낮은 등급 수리 때문에 식사·거리 불변식이 새로 깨졌으면 그 수리를 취소한다.
            if (outcome.lowestPriorityRepair() != null) {
                Map<Integer, DayStatus> after = invariantChecker.evaluate(days, steps, transportPref, candidates, pace);
                String regression = invariantChecker.findPriorityRegression(before, after);
                if (regression != null) {
                    log.info("우선순위 역전 — 수리 취소: {} (원인: {})", outcome.lowestPriorityRepair(), regression);
                    snapshot.restore(days);
                    // 사용 표시도 되돌려야 교체로 빠졌다 되살아난 장소가 다른 날에 또 들어가지 않는다.
                    usedIndices.clear();
                    usedIndices.addAll(usedSnapshot);
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

        // 대안은 day 순서대로 생성돼 뒤쪽 day 보충으로 본일정에 들어간 장소가 앞쪽 대안에 남을 수 있다.
        // 최종 스텝 기준으로 걸러낸다.
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
     * 확정 스텝으로 하루 구성을 검사해 위반 day의 placeIndices를 고친다. 삽입이 거리·시간 가드를 통과하는지는 재스케줄이 판정한다.
     *
     * <p>우선순위(높음→낮음): <b>식사 슬롯 &gt; 하루 거리 예산 &gt; 시간대 가드 &gt; 카테고리 커버리지
     * &gt; 유형 상한</b>. 거리·시간대는 재스케줄이 강제하므로 하위 수리가 그 둘을 깨지 않게 제한한다.
     * <ul>
     *   <li>식사 슬롯을 채우는 스텝은 커버리지·유형 상한 교체 대상에서 뺀다</li>
     *   <li>유형 상한 교체는 같은 대분류 안에서만 한다</li>
     *   <li>커버리지 삽입은 day centroid 최근접을 고르고 구간/일일 예산을 넘기면 넣지 않는다</li>
     * </ul>
     * 그래도 상위 불변식이 깨지면 호출부가 스냅샷으로 되돌린다.
     */
    private RepairOutcome enforceFinalInvariants(List<DayState> days, List<StepData> steps,
                                                 List<PlaceCandidate> candidates, Deque<Integer> spare,
                                                 Set<Integer> usedIndices, String pace, String transportPref,
                                                 int maxPerDay, List<String> userCategories,
                                                 java.time.LocalDate startDate, InvariantMemo memo, ScheduleConfig cfg) {
        Map<Integer, List<StepData>> byDay = steps.stream()
                .collect(Collectors.groupingBy(StepData::dayNumber, LinkedHashMap::new, Collectors.toList()));
        boolean changed = false;

        Map<String, Integer> globalTypeCount = countSubTypes(days, candidates);
        java.util.function.Predicate<PlaceCandidate> underTypeCap =
                c -> !isSpecificType(subTypeOf(c))
                        || globalTypeCount.getOrDefault(subTypeOf(c), 0) < typeCapFor(c, days.size());

        // ── 1순위: 식사 슬롯 + 숙소 ───────────────────────────────────────
        for (int i = 0; i < days.size(); i++) {
            DayState day = days.get(i);
            List<StepData> daySteps = byDay.getOrDefault(day.dayNumber, List.of());
            if (daySteps.isEmpty()) continue;
            boolean lastDay = DayState.endsTrip(days, i);
            changed |= ensureMealSlots(day, daySteps, candidates, spare, usedIndices, maxPerDay,
                    lastDay, startDate, transportPref, memo, cfg, underTypeCap);
            changed |= ensureAccommodation(day, candidates, lastDay);
        }
        if (changed) return RepairOutcome.highPriority(memo.pendingIndices());

        // ── 3순위: 시간대·구성 가드(관광 최소 1개, 조기 종료) ─────────────
        for (int i = 0; i < days.size(); i++) {
            DayState day = days.get(i);
            List<StepData> daySteps = byDay.getOrDefault(day.dayNumber, List.of());
            if (daySteps.isEmpty()) continue;
            java.util.function.Predicate<PlaceCandidate> fillable =
                    insertableFilter(day, candidates, startDate, transportPref, memo, cfg).and(underTypeCap);
            changed |= ensureAttraction(day, daySteps, candidates, spare, usedIndices, maxPerDay, pace,
                    fillable, memo);
            changed |= ensureNotFinishingEarly(day, daySteps, candidates, spare, usedIndices, maxPerDay,
                    fillable, memo);
        }
        if (changed) return RepairOutcome.highPriority(memo.pendingIndices());

        // ── 4순위: 카테고리 커버리지 ──────────────────────────────────────
        if (reapplyCoverageOnFinalSteps(days, steps, candidates, spare, usedIndices,
                userCategories, maxPerDay, startDate, memo, transportPref, cfg)) {
            return RepairOutcome.lowPriority("카테고리 커버리지 보충", memo.pendingIndices());
        }

        // ── 5순위: 유형 상한·같은 종류 연속 ───────────────────────────────
        if (reapplyTypeCapOnFinalSteps(days, steps, candidates, spare, usedIndices, startDate, memo,
                transportPref, cfg)) {
            return RepairOutcome.lowPriority("세부 유형 상한 교체", memo.pendingIndices());
        }
        for (int i = 0; i < days.size(); i++) {
            DayState day = days.get(i);
            List<StepData> daySteps = byDay.getOrDefault(day.dayNumber, List.of());
            if (daySteps.isEmpty()) continue;
            java.util.function.Predicate<PlaceCandidate> fillable =
                    insertableFilter(day, candidates, startDate, transportPref, memo, cfg).and(underTypeCap);
            if (breakConsecutiveSameMajor(day, daySteps, candidates, spare, usedIndices, fillable)) {
                return RepairOutcome.lowPriority("같은 종류 연속 해소", memo.pendingIndices());
            }
        }
        return RepairOutcome.none();
    }



    /** 일정에 넣기 전 보호 장소(사용자 선택·요청 의도의 핵심)의 날. 끼니는 끼니 수리가 따로 채워 뺀다. */
    private static Map<Integer, Integer> protectedHomes(List<DayState> days, List<PlaceCandidate> candidates) {
        Map<Integer, Integer> home = new LinkedHashMap<>();
        for (DayState day : days) {
            for (Integer idx : day.placeIndices) {
                PlaceCandidate c = byIndex(candidates, idx);
                if (c != null && c.keepsPlace() && !c.mealEligible()) home.put(idx, day.dayNumber);
            }
        }
        return home;
    }

    /**
     * 첫 스케줄에서 시각·출발·영업시간 가드에 잘린 보호 장소를 다른 날에 넣어 본다. 장소와 가까운 날부터 넣고 다시
     * 스케줄해, 장소가 남고 다른 보호 장소를 잃지 않고 식사·거리 불변식이 깨지지 않는 첫 날을 쓴다. 맞는 날이 없으면
     * 잘린 채 둔다. 시도마다 일정 전체를 다시 스케줄해 {@value ScheduleTuning#PROTECTED_RESTORE_MAX_TRIES}번에서 멈춘다.
     */
    private List<StepData> restoreDroppedProtected(List<DayState> days, List<StepData> steps,
                                                   List<PlaceCandidate> candidates, Map<Integer, Integer> protectedHome,
                                                   List<List<Integer>> pairs, Set<Integer> highlights, Set<Integer> rests,
                                                   Deque<Integer> spare, List<Integer> spareBaseline,
                                                   Set<Integer> usedIndices, ScheduleConfig cfg, String transportPref,
                                                   int maxPerDay, String pace, java.time.LocalDate startDate) {
        int tries = 0;
        for (Map.Entry<Integer, Integer> entry : protectedHome.entrySet()) {
            int idx = entry.getKey();
            PlaceCandidate c = byIndex(candidates, idx);
            double[] at = coordsOf(c);
            if (at == null || collectUsedIndices(days).contains(idx)) continue;
            List<DayState> targets = days.stream()
                    .filter(d -> d.dayNumber != entry.getValue() && !d.tooShortForSightseeing())
                    .filter(d -> openOnDayFilter(startDate, d.dayNumber).test(c))
                    .sorted(Comparator.comparingDouble(d -> {
                        double[] center = centroidOf(d.placeIndices, candidates);
                        return center == null ? Double.MAX_VALUE : haversine(center, at);
                    }))
                    .toList();
            Map<Integer, DayStatus> before = invariantChecker.evaluate(days, steps, transportPref, candidates, pace);
            Set<Integer> keptBefore = placedProtected(days, protectedHome);
            for (DayState target : targets) {
                if (tries++ >= PROTECTED_RESTORE_MAX_TRIES) return steps;
                DaySnapshot snapshot = DaySnapshot.of(days);
                Set<Integer> usedSnapshot = new HashSet<>(usedIndices);
                addToDay(target, idx, spare, usedIndices);
                resetSpare(spare, spareBaseline, days);
                List<StepData> tried = scheduleAllDays(days, candidates, pairs, highlights, rests,
                        spare, usedIndices, cfg, transportPref, maxPerDay);
                String regression = !target.placeIndices.contains(idx) ? "다시 잘림"
                        : !placedProtected(days, protectedHome).containsAll(keptBefore) ? "다른 핵심 장소 잘림"
                        : invariantChecker.findPriorityRegression(before,
                        invariantChecker.evaluate(days, tried, transportPref, candidates, pace));
                if (regression == null) {
                    log.info("잘린 핵심 장소 복원: day={} → day={} {}", entry.getValue(), target.dayNumber, c.name());
                    steps = tried;
                    break;
                }
                log.info("핵심 장소 복원 보류: day={} {} ({})", target.dayNumber, c.name(), regression);
                snapshot.restore(days);
                usedIndices.clear();
                usedIndices.addAll(usedSnapshot);
                resetSpare(spare, spareBaseline, days);
            }
        }
        return steps;
    }

    private Set<Integer> placedProtected(List<DayState> days, Map<Integer, Integer> protectedHome) {
        Set<Integer> placed = collectUsedIndices(days);
        placed.retainAll(protectedHome.keySet());
        return placed;
    }

    /**
     * 그 day에 넣어도 되는 후보 필터. 거리 예산을 보지 않으면 커버리지가 먼 장소를 넣고 다음 패스에 트림하는 왕복이 생긴다.
     */
    private java.util.function.Predicate<PlaceCandidate> insertableFilter(
            DayState day, List<PlaceCandidate> candidates, java.time.LocalDate startDate,
            String transportPref, InvariantMemo memo, ScheduleConfig cfg) {
        return insertableFilter(day, candidates, startDate, transportPref, memo, RelaxLevel.L1, cfg);
    }


    private java.util.function.Predicate<PlaceCandidate> insertableFilter(
            DayState day, List<PlaceCandidate> candidates, java.time.LocalDate startDate,
            String transportPref, InvariantMemo memo, RelaxLevel level, ScheduleConfig cfg) {
        return insertableFilter(day, candidates, startDate, transportPref, memo, level, null, cfg);
    }

    /**
     * @param slotStart 넣을 자리의 시작 시각(분). 주면 그 시각에 영업하는 후보만 통과한다.
     *                  null이면 요일 휴무만 본다.
     */
    private java.util.function.Predicate<PlaceCandidate> insertableFilter(
            DayState day, List<PlaceCandidate> candidates, java.time.LocalDate startDate,
            String transportPref, InvariantMemo memo, RelaxLevel level, Integer slotStart,
            ScheduleConfig cfg) {
        double[] centroid = centroidOf(day.placeIndices, candidates);
        double radius = altRadiusKm(transportPref) * (level == RelaxLevel.L2 ? 2 : 1);

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
                            || haversine(centroid, coordsOf(c)) <= radius)
                    .and(c -> !wouldExceedDayBudget(day, c, candidates, transportPref, cfg));
        }
        return filter;
    }

    /**
     * 후보를 넣었을 때 구간·일일 거리 예산을 넘기는지 추정한다. 실제 순서는 재스케줄이 정하므로 우회가 가장 작은 자리를 가정한 하한 추정이다.
     */
    private boolean wouldExceedDayBudget(DayState day, PlaceCandidate cand, List<PlaceCandidate> candidates,
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
        for (int i = 1; i < path.size(); i++) current += haversine(path.get(i - 1), path.get(i));

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

        // 이미 상한을 넘은 day를 절대 총량으로 판정하면 어떤 후보도 통과하지 못해 하루가 빈다.
        // 넘은 상태에서는 이 삽입이 얼마나 더 늘리는지만 본다.
        double dayCap = Math.max(limits[1], current + OVER_BUDGET_INSERT_SLACK_KM);
        return current + bestDetour > dayCap || bestNewLeg > limits[0];
    }

    /**
     * 숙박일에 숙소가 없으면 방문지 중심에 가장 가까운 LODGING을 넣는다. IndexResultMapper.fillMissingAccommodation의 안전망이다.
     * 숙소는 하루의 종착점이라 먼 숙소가 없는 것보다 나아서 거리·예산 가드를 걸지 않는다.
     */
    private boolean ensureAccommodation(DayState day, List<PlaceCandidate> candidates, boolean lastDay) {
        if (lastDay || day.accommodationIndex != null) return false;

        double[] centroid = centroidOf(day.placeIndices, candidates);
        Set<Integer> visited = new HashSet<>(day.placeIndices);
        PlaceCandidate best = candidates.stream()
                .filter(c -> "LODGING".equals(PlaceCategoryConstants.majorCategory(c.category())))
                .filter(c -> PlaceCategoryConstants.isVisitableEntity(c.name(), c.address(), c.country()))
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

    private boolean ensureMealSlots(DayState day, List<StepData> daySteps, List<PlaceCandidate> candidates,
                                    Deque<Integer> spare, Set<Integer> usedIndices, int maxPerDay,
                                    boolean lastDay, java.time.LocalDate startDate, String transportPref,
                                    InvariantMemo memo, ScheduleConfig cfg,
                                    java.util.function.Predicate<PlaceCandidate> underTypeCap) {
        boolean changed = false;
        java.util.function.Predicate<StepData> isMeal = InvariantChecker.mealJudge(day, candidates);

        if (day.lunchInWindow() && !hasMealInWindow(daySteps, LUNCH_START, day.lunchWindowEnd(), isMeal)) {
            // centroid는 좌표 불명 day에서 null일 수 있고 List.of는 null을 거부해 singletonList를 쓴다.
            changed |= insertMealWithRelaxation(day, candidates, spare, usedIndices, maxPerDay,
                    Collections.singletonList(centroidOf(day.placeIndices, candidates)),
                    "점심", startDate, transportPref, memo, cfg, underTypeCap);
        }

        // 마지막날 저녁은 귀가 시각이 늦을 때만 필요하다(출발 허브 도착이 19:30 전이면 저녁을 먹지 않음)
        boolean dinnerRequired = (!lastDay || departsAfterDinner(daySteps, candidates, day)) && day.dinnerInWindow();
        if (dinnerRequired && !hasMealInWindow(daySteps, DINNER_START, day.dinnerWindowEnd(), isMeal)) {
            // 앵커는 숙소 → 출발 허브 → 방문지 중심 순이다. 마지막 날은 숙소가 없어 허브 앵커가 없으면 공항 길목 식당을 못 찾는다.
            List<double[]> anchors = new ArrayList<>();
            anchors.add(accommodationCoords(day, candidates));
            if (day.departureHubIndex != null) {
                anchors.add(coordsOf(byIndex(candidates, day.departureHubIndex)));
            }
            anchors.add(centroidOf(day.placeIndices, candidates));
            changed |= insertMealWithRelaxation(day, candidates, spare, usedIndices, maxPerDay,
                    anchors, "저녁", startDate, transportPref, memo, cfg, underTypeCap);
        }
        return changed;
    }

    /**
     * 필수 식사 슬롯을 L1 → L2 → L3로 제약을 완화하며 채운다. 세 단계 모두 실패해야 미충족으로 기록한다.
     */
    private boolean insertMealWithRelaxation(DayState day, List<PlaceCandidate> candidates,
                                             Deque<Integer> spare, Set<Integer> usedIndices, int maxPerDay,
                                             List<double[]> anchors, String slotName,
                                             java.time.LocalDate startDate, String transportPref,
                                             InvariantMemo memo, ScheduleConfig cfg,
                                             java.util.function.Predicate<PlaceCandidate> underTypeCap) {
        // 보충할 자리의 시각을 알려줘야 "그 시각에 여는 식당"만 후보가 된다.
        Integer slotStart = "저녁".equals(slotName) ? DINNER_START : LUNCH_START;
        for (RelaxLevel level : RelaxLevel.values()) {
            java.util.function.Predicate<PlaceCandidate> filter =
                    insertableFilter(day, candidates, startDate, transportPref, memo, level, slotStart, cfg);
            // 같은 완화 단계 안에서도 음식 종류 상한을 지키는 식당을 먼저 찾는다. 없으면 상한을 넘겨서라도 끼니를 채운다.
            for (java.util.function.Predicate<PlaceCandidate> attempt
                    : List.of(filter.and(underTypeCap), filter)) {
                for (double[] anchor : anchors) {
                    if (anchor == null) continue;
                    if (insertMeal(day, candidates, spare, usedIndices, maxPerDay, anchor, slotName, attempt, memo)) {
                        if (level != RelaxLevel.L1) {
                            log.info("{} 슬롯 보충에 제약 완화 {} 적용: day={}", slotName, level, day.dayNumber);
                        }
                        return true;
                    }
                }
            }
        }
        logMealFillFailure(day, candidates, usedIndices, slotName);
        return false;
    }



    /**
     * 출발 허브 도착이 기한(기본 {@value ScheduleTuning#DEPARTURE_HUB_LATEST_MINUTES}분)을 넘을 것으로 보이면
     * 보호 대상이 아닌 활동을 <b>뒤에서부터</b> 잘라 귀가를 앞당긴다.
     *
     * <p>거리 예산과는 다른 축이다. 이동이 상한 안이어도 체류시간이 쌓이면 심야 귀가가 된다.
     */
    private void trimSequenceForDepartureTime(DayState day, List<Integer> seq, List<PlaceCandidate> candidates,
                                              Deque<Integer> spareIndices, ScheduleConfig cfg,
                                              PlaceCandidate origin, String transportPref,
                                              Set<Integer> protectedIdx,
                                              Integer breakfastIdx, Integer lunchIdx, Integer dinnerIdx) {
        if (day.departureHubIndex == null) return;

        boolean userDeadline = day.departureDeadline != null;
        int attempts = userDeadline ? seq.size() : 3;
        for (int guard = 0; guard < attempts; guard++) {
            int arrival = estimateDepartureHubArrival(day, seq, candidates, cfg, origin, transportPref,
                    breakfastIdx, lunchIdx, dinnerIdx);
            if (arrival <= departureDeadline(day)) return;

            Integer victim = null;
            for (int i = seq.size() - 1; i >= 0; i--) {
                Integer idx = seq.get(i);
                if (protectedIdx.contains(idx)) continue;
                PlaceCandidate c = byIndex(candidates, idx);
                if (c == null || c.userSelected()) continue;
                victim = idx;
                break;
            }
            // 사용자가 준 출발 시각(비행기·기차)은 넘길 수 없어 대표 명소·식사 보호보다 앞선다.
            // 기본 마감(21:00)일 때도 보호 장소는 자르고 끼니만 남긴다. 저녁 뒤 활동을 지키면 공항에 23시에 닿는다.
            if (victim == null) {
                for (int i = seq.size() - 1; i >= 0; i--) {
                    Integer idx = seq.get(i);
                    if (isSameIndex(day.departureHubIndex, idx) || isSameIndex(day.arrivalHubIndex, idx)) continue;
                    if (!userDeadline && (isSameIndex(breakfastIdx, idx) || isSameIndex(lunchIdx, idx)
                            || isSameIndex(dinnerIdx, idx))) continue;
                    PlaceCandidate c = byIndex(candidates, idx);
                    if (c == null || c.userSelected()) continue;
                    victim = idx;
                    break;
                }
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
                    formatMinutes(departureDeadline(day)), day.dayNumber, nameOf(candidates, victim));
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

    /**
     * 갭 충전에 남은 자리 수 = pace 상한 − 그날 장소 수 − 이미 채운 수. 식사도 장소 수에 들어간다.
     *
     * <p>{@code LARGE_GAP_MINUTES} 이상 빈 날만 상한을 넘겨 하루 1곳까지 채운다({@code fillGapBeforeMeal}).
     */
    private int gapFillQuota(DayState day, int maxPerDay, int alreadyFilled) {
        return maxPerDay - day.placeIndices.size() - alreadyFilled;
    }

    /** 허브·숙소·식사 슬롯은 미배정(null)일 수 있다. */
    private boolean isSameIndex(Integer slot, int idx) {
        return slot != null && slot == idx;
    }

    /**
     * 야외·주간 장소의 시작 상한. 테마의 eveningCap에서 역산하고 15:00 아래로는 내리지 않는다.
     * 여행지 규칙이 아니라 <b>사용자가 고른 테마에서만</b> 파생된다.
     */
    private int daytimeLatestStart(ScheduleConfig cfg) {
        int cap = cfg != null ? cfg.eveningCap : DEFAULT_EVENING_CAP_MINUTES;
        return Math.max(15 * 60, cap - 180);
    }

    /** 그 스텝이 점심·저녁 슬롯을 채우는지. 교체·트림 보호 판정에 쓴다. */
    private boolean isMealSlotStep(StepData step, List<DayState> days, List<PlaceCandidate> candidates) {
        if (step.place() == null) return false;
        PlaceCandidate c = candidateOfStep(days, step, candidates);
        boolean meal = c != null ? c.mealEligible()
                : PlaceCategoryConstants.isMealPlace(step.place().name(), step.place().category());
        if (!meal) return false;
        int start = toMinutesOrZero(step.startTime());
        return (start >= LUNCH_START - 60 && start <= LUNCH_END)
                || (start >= DINNER_START - 60 && start <= DINNER_END);
    }

    /** 필수 식사 삽입. pace 상한을 {@value ScheduleTuning#MEAL_QUOTA_SLACK}개까지 넘겨도 끼니를 우선한다. */
    private boolean insertMeal(DayState day, List<PlaceCandidate> candidates,
                               Deque<Integer> spare, Set<Integer> usedIndices, int maxPerDay,
                               double[] anchor, String slotName,
                               java.util.function.Predicate<PlaceCandidate> openOnDay, InvariantMemo memo) {
        Integer pick = pickNearbyCandidate(candidates, spare, usedIndices, anchor,
                openOnDay.and(PlaceCandidate::mealEligible)
                        .and(c -> PlaceCategoryConstants.isVisitableEntity(c.name(), c.address(), c.country())));
        // 실패 로그는 완화 단계를 전부 소진한 호출부가 남긴다. 여기서 찍으면 L1 실패마다 보충 실패로 보인다.
        if (pick == null) return false;

        if (day.placeIndices.size() < maxPerDay + MEAL_QUOTA_SLACK) {
            addToDay(day, pick, spare, usedIndices);
            memo.recordInsert(day.dayNumber, pick);
            log.info("{} 슬롯 보충: day={} {}", slotName, day.dayNumber, nameOf(candidates, pick));
            return true;
        }

        // quota가 꽉 차도 끼니는 빼지 않는다. 개수를 늘리는 대신 그날 가장 많이 반복된 비식사 장소와 교체한다.
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
            if (c == null || c.keepsPlace() || c.mealEligible()) continue;
            // 카페 스톱·카테고리 유일 대표는 끼니 자리를 만들려고 빼지 않는다.
            if (idx.equals(day.cafeStopIndex) || day.coverageCriticalIndices.contains(idx)) continue;
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
        if ("relaxed".equals(pace) || day.tooShortForSightseeing()) return false;
        boolean has = daySteps.stream().anyMatch(s -> s.place() != null
                && "ATTRACTION".equals(PlaceCategoryConstants.majorCategory(s.place().category())));
        if (has) return false;
        if (day.placeIndices.size() >= maxPerDay) {
            log.info("관광 보충 실패(quota 여유 없음): day={}", day.dayNumber);
            return false;
        }
        // 공용 게이트(isVisitableFill)가 없으면 행정 구역 POI가 관광 스텝으로 들어온다.
        Integer pick = pickNearbyCandidate(candidates, spare, usedIndices,
                centroidOf(day.placeIndices, candidates),
                openOnDay.and(c -> "ATTRACTION".equals(PlaceCategoryConstants.majorCategory(c.category()))
                        && isVisitableFill(c)));
        if (pick == null) {
            log.info("관광 보충 실패(후보 없음): day={}", day.dayNumber);
            return false;
        }
        addToDay(day, pick, spare, usedIndices);
        memo.recordInsert(day.dayNumber, pick);
        log.info("관광 보충: day={} {}", day.dayNumber, nameOf(candidates, pick));
        return true;
    }

    /** 숙소 체크인·교통허브가 아닌 스텝. 하루 종료 판정에 쓴다. */
    private static boolean isActivityStep(StepData step) {
        if (step == null || step.place() == null) return false;
        String major = PlaceCategoryConstants.majorCategory(step.place().category());
        return !"LODGING".equals(major) && !"TRANSIT_HUB".equals(major);
    }

    /**
     * 마지막 <b>활동</b>이 {@code EARLY_FINISH_MINUTES} 전에 끝나면 주간 활동을 하나 더 넣는다.
     *
     * <p>마지막 스텝 기준이면 17:30 저녁과 숙소 체크인 때문에 항상 17:00 이후로 보여 이 경로가 돌지 않는다.
     */
    private boolean ensureNotFinishingEarly(DayState day, List<StepData> daySteps,
                                            List<PlaceCandidate> candidates, Deque<Integer> spare,
                                            Set<Integer> usedIndices, int maxPerDay,
                                            java.util.function.Predicate<PlaceCandidate> openOnDay,
                                            InvariantMemo memo) {
        int end = daySteps.stream()
                .filter(RouteOptimizer::isActivityStep)
                .mapToInt(s -> toMinutesOrZero(s.endTime()))
                .max().orElse(0);
        if (end == 0 || end >= EARLY_FINISH_MINUTES || day.endsEarlyByDeparture()) return false;
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
     * <p>"같은 종류"는 대분류가 아니라 <b>체감 기준</b>이다.
     * <ul>
     *   <li>식음료(DINING/CAFE): 대분류 연속. 카페 3연속은 그 자체로 단조롭다</li>
     *   <li>관광(ATTRACTION): 세부 유형 연속. 박물관·해변·공원이 이어지는 건 정상이고 "오름 3연속"이 문제다</li>
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
            String prevKind = consecutiveKind(visits.get(i - 1), day, candidates);
            String curKind = i < visits.size() ? consecutiveKind(visits.get(i), day, candidates) : null;
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

    /** 연속 판정 키: 식음료는 대분류, 관광은 세부 유형(보강 속성 우선). kindOf와 기준이 같아야 한다. */
    private String consecutiveKind(StepData step, DayState day, List<PlaceCandidate> candidates) {
        String major = PlaceCategoryConstants.majorCategory(step.place().category());
        if ("DINING".equals(major) || "CAFE".equals(major)) return major;
        Integer idx = indexOfStep(step, day, candidates);
        PlaceCandidate c = idx == null ? null : byIndex(candidates, idx);
        String byAttribute = c == null ? null : PlaceCategoryConstants.attributeSubTypeKey(c.attributes());
        if (byAttribute != null) return byAttribute;
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
        if (targetCand == null || targetCand.keepsPlace()) return false;

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
     * 최종 스텝 기준으로 트림에 사라진 사용자 카테고리를 다시 채운다.
     *
     * <p>후보는 대상 day centroid 최근접으로 고르고 구간·일일 거리 예산을 넘기면 넣지 않는다.
     * 커버리지 한 칸 때문에 하루 동선을 무너뜨리느니 미충족 로그를 남긴다.
     */
    private boolean reapplyCoverageOnFinalSteps(List<DayState> days, List<StepData> steps,
                                                List<PlaceCandidate> candidates, Deque<Integer> spare,
                                                Set<Integer> usedIndices, List<String> userCategories,
                                                int maxPerDay, java.time.LocalDate startDate,
                                                InvariantMemo memo, String transportPref, ScheduleConfig cfg) {
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

            // (day, 후보)를 함께 골라 centroid에 가장 가까운 조합을 택해야 동선이 망가지지 않는다.
            DayState bestDay = null;
            Integer bestPick = null;
            double bestDist = Double.MAX_VALUE;
            // spare에 그 카테고리가 없으면 미사용 후보까지 넓힌다. 거리 예산·시각 가드는 insertable이 지킨다.
            List<Integer> pool = new ArrayList<>(spare);
            boolean spareHasCategory = pool.stream()
                    .map(idx -> byIndex(candidates, idx))
                    .filter(Objects::nonNull)
                    .anyMatch(c -> PlaceCategoryConstants.matchesUserCategory(
                            categoryId, c.name(), c.category(), c.tags()));
            if (!spareHasCategory) {
                pool.addAll(unusedIndices(candidates, usedIndices));
            }
            for (DayState d : days) {
                if (d.placeIndices.size() >= maxPerDay) continue;
                double[] centroid = centroidOf(d.placeIndices, candidates);
                java.util.function.Predicate<PlaceCandidate> insertable =
                        insertableFilter(d, candidates, startDate, transportPref, memo, cfg);
                for (Integer idx : pool) {
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
                return true; // 한 번에 하나씩 넣고 나머지는 재스케줄 뒤 다음 패스에서 본다
            }
            // 자리가 없으면 가장 많이 반복된 유형 하나와 교체해 quota를 늘리지 않고 커버리지를 채운다.
            if (replaceMostRepeatedForCoverage(days, candidates, spare, usedIndices, categoryId, startDate,
                    memo, transportPref, steps, cfg)) {
                return true;
            }
            log.info("최종 커버리지 미충족(예산·자리 제약으로 삽입·교체 불가): {}", categoryId);
        }
        return false;
    }

    /**
     * 커버리지 후보를 넣을 자리가 없을 때 가장 많이 반복된 세부 유형의 장소 하나와 맞바꾼다.
     * 식사·허브·숙소·사용자 필수 장소와 반복 2회 미만 유형은 교체하지 않는다.
     */
    private boolean replaceMostRepeatedForCoverage(List<DayState> days, List<PlaceCandidate> candidates,
                                                   Deque<Integer> spare, Set<Integer> usedIndices,
                                                   String categoryId, java.time.LocalDate startDate,
                                                   InvariantMemo memo, String transportPref,
                                                   List<StepData> steps, ScheduleConfig cfg) {
        Map<String, Integer> typeCount = countSubTypes(days, candidates);
        // 식사 슬롯 장소는 교체하면 끼니가 사라져 대상에서 뺀다.
        Set<String> mealSlotNames = steps.stream()
                .filter(st -> isMealSlotStep(st, days, candidates))
                .map(st -> normalizeName(st.place().name()))
                .collect(Collectors.toSet());

        DayState bestDay = null;
        Integer bestVictim = null;
        Integer bestPick = null;
        int bestCount = 1; // 2회 이상 반복된 유형만 교체 대상

        for (DayState d : days) {
            java.util.function.Predicate<PlaceCandidate> insertable =
                    insertableFilter(d, candidates, startDate, transportPref, memo, cfg);
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
                if (c == null || c.keepsPlace()) continue;
                if (c.mealEligible()) continue;
                if (mealSlotNames.contains(normalizeName(c.name()))) continue;
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
     * 최종 스텝 기준으로 세부 유형 상한 초과분을 <b>같은 대분류의</b> 다른 세부 유형으로 교체한다.
     * 대분류가 바뀌면 관광이 식당이 되거나 저녁이 카페가 되어 상위 불변식이 깨진다.
     */
    private boolean reapplyTypeCapOnFinalSteps(List<DayState> days, List<StepData> steps,
                                               List<PlaceCandidate> candidates, Deque<Integer> spare,
                                               Set<Integer> usedIndices, java.time.LocalDate startDate,
                                               InvariantMemo memo, String transportPref, ScheduleConfig cfg) {
        Map<String, Integer> typeCount = new HashMap<>();

        for (StepData step : steps) {
            if (step.place() == null) continue;
            String major = PlaceCategoryConstants.majorCategory(step.place().category());
            if ("TRANSIT_HUB".equals(major) || "LODGING".equals(major)) continue;

            // 스텝(PlaceData)에는 태그가 없어 후보로 되짚어야 태그로만 드러나는 종류도 상한에 걸린다.
            PlaceCandidate stepCand = candidateOfStep(days, step, candidates);
            String type = stepCand != null ? subTypeOf(stepCand) : PlaceCategoryConstants.mealKind(
                    step.place().name(), step.place().nameKo(), step.place().category(), List.of(),
                    step.place().description(), null);
            int typeCap = typeCapFor(stepCand, days.size());
            int count = typeCount.merge(type, 1, Integer::sum);
            if (!isSpecificType(type) || count <= typeCap) continue;

            // 식사 슬롯도 교체 대상이다. DINING 대분류에는 카페·바도 있어 식사 슬롯이면 대체 후보도 식사 장소여야 한다.
            boolean mealSlot = isMealSlotStep(step, days, candidates);

            DayState day = findDayContainingStep(days, step, candidates);
            if (day == null) continue;
            if (replaceStepWithDifferentSubType(day, step, type, major, days.size(), typeCount, candidates, spare,
                    usedIndices, insertableFilter(day, candidates, startDate, transportPref, memo, cfg), mealSlot, memo)) {
                return true; // 한 번에 하나씩
            }
            log.info("최종 유형 상한 초과({} {}회) 해소 실패(같은 대분류 대체 후보 없음): {}",
                    type, count, step.place().name());
        }

        // 전체 상한을 지켜도 같은 날 점심·저녁이 같은 종류일 수 있어 저녁을 바꾼다.
        Map<Integer, List<StepData>> byDay = steps.stream()
                .collect(Collectors.groupingBy(StepData::dayNumber, LinkedHashMap::new, Collectors.toList()));
        for (DayState day : days) {
            List<StepData> meals = byDay.getOrDefault(day.dayNumber, List.of()).stream()
                    .filter(st -> isMealSlotStep(st, days, candidates))
                    .toList();
            if (meals.size() < 2) continue;
            StepData lunch = meals.get(0);
            StepData dinner = meals.get(meals.size() - 1);
            PlaceCandidate lc = candidateOfStep(days, lunch, candidates);
            PlaceCandidate dc = candidateOfStep(days, dinner, candidates);
            if (lc == null || dc == null) continue;
            String type = subTypeOf(dc);
            if (!isSpecificType(type) || !type.equals(subTypeOf(lc))) continue;
            if (replaceStepWithDifferentSubType(day, dinner, type, "DINING", days.size(), typeCount, candidates,
                    spare, usedIndices, insertableFilter(day, candidates, startDate, transportPref, memo, cfg),
                    true, memo)) {
                return true;
            }
            log.info("같은 날 점심·저녁 같은 종류({}) 해소 실패(대체 식당 없음): day={}", type, day.dayNumber);
        }
        return false;
    }

    /** 스텝에 대응하는 후보(그 스텝이 속한 day 기준). 허브·숙소·찾지 못한 경우 null. */
    private PlaceCandidate candidateOfStep(List<DayState> days, StepData step, List<PlaceCandidate> candidates) {
        DayState day = findDayContainingStep(days, step, candidates);
        if (day == null) return null;
        Integer idx = indexOfStep(step, day, candidates);
        return idx != null ? byIndex(candidates, idx) : null;
    }

    /**
     * @param mustBeMeal 식사 슬롯 교체면 true. DINING에는 카페·바도 들어오므로 이 조건이 없으면 점심이 카페로 바뀐다.
     */
    private boolean replaceStepWithDifferentSubType(DayState day, StepData step, String type, String major,
                                                    int dayCount, Map<String, Integer> typeCount,
                                                    List<PlaceCandidate> candidates, Deque<Integer> spare,
                                                    Set<Integer> usedIndices,
                                                    java.util.function.Predicate<PlaceCandidate> insertable,
                                                    boolean mustBeMeal,
                                                    InvariantMemo memo) {
        Integer target = indexOfStep(step, day, candidates);
        if (target == null) return false;
        PlaceCandidate targetCand = byIndex(candidates, target);
        if (targetCand == null || targetCand.keepsPlace()) return false;

        Integer pick = pickNearbyCandidate(candidates, spare, usedIndices, coordsOf(targetCand),
                insertable.and(c -> isVisitableFill(c)
 // 대분류가 바뀌면 식사·관광 구성이 무너진다
                        && major.equals(PlaceCategoryConstants.majorCategory(c.category()))
                        && (!mustBeMeal || c.mealEligible())
                        && !type.equals(subTypeOf(c))
                        && typeCount.getOrDefault(subTypeOf(c), 0) < typeCapFor(c, dayCount)));
        if (pick == null) return false;

        replaceInDay(day, target, pick);
        spare.remove(pick);
        spare.addLast(target);
        usedIndices.add(pick);
        usedIndices.remove(target);
        memo.recordInsert(day.dayNumber, pick);
        typeCount.merge(type, -1, Integer::sum);
        typeCount.merge(subTypeOf(byIndex(candidates, pick)), 1, Integer::sum);
        log.info("최종 유형 교체({}): day={} {} → {} (대분류 {} 유지)", type,
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
            // 첫날은 도착 허브, 그 외는 전날 묵은 숙소에서 시작한다.
            double[] startAnchor = day.arrivalHubIndex != null
                    ? coordsOf(byIndex(candidates, day.arrivalHubIndex))
                    : (i > 0 ? accommodationCoords(days.get(i - 1), candidates) : null);
            // 그날 숙소 또는 마지막 날 출발 허브에서 끝난다.
            double[] endAnchor = day.accommodationIndex != null
                    ? coordsOf(byIndex(candidates, day.accommodationIndex))
                    : (day.departureHubIndex != null ? coordsOf(byIndex(candidates, day.departureHubIndex)) : null);

            List<Integer> ordered = orderDay(day, candidates, pairs, highlights, rests, startAnchor, endAnchor);
            ordered = applyWalkContinuity(ordered, day, days, i, candidates, transportPref);
            ordered = enforceDistanceBudget(day, ordered, candidates, spare, usedIndices, transportPref, cfg);
            double[] dayCentroid = centroidOf(day.placeIndices, candidates);
            // 첫 스텝의 이동 정보와 도착 시각은 전날 숙소를 출발지로 계산한다.
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

    /** DB places의 (name,address) 유니크 키와 같은 기준으로 정규화한 장소 동일성 키. */
    private String placeKey(String name, String address) {
        return normalizeName(name) + "|" + normalizeName(address);
    }

    /** pace 상한 초과 day는 트림하고 하한 미달 day는 spare에서 보충한다. pair 인접보다 우선한다. */
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
     * 트림 대상을 고른다. day의 유일한 DINING과 keepsPlace 장소는 남긴다.
     * 전부 보호 대상이면 null이고, 호출부는 사용자 선택을 pace 상한보다 우선해 quota 초과를 허용한다.
     */
    private Integer pickTrimCandidate(List<Integer> indices, List<PlaceCandidate> candidates) {
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
    private Integer pickBestFill(DayState day, Deque<Integer> spare, List<PlaceCandidate> candidates) {
        double[] centroid = centroidOf(day.placeIndices, candidates);

        return spare.stream()
                .map(i -> byIndex(candidates, i))
                .filter(Objects::nonNull)
                .filter(this::isVisitableFill)
                .filter(c -> coordsOf(c) != null)
                .min(Comparator.comparingDouble(c -> centroid != null
                        ? haversine(centroid, coordsOf(c)) : 0))
                .map(PlaceCandidate::index)
                .orElse(null);
    }

    /**
     * 자동 보충으로 방문 스텝에 넣어도 되는 후보인지. 경유형은 Sonnet이 고른 것만 두고 코드가 늘리지 않는다.
     */
    private boolean isVisitableFill(PlaceCandidate c) {
        // 역할 판정은 CandidatePolicy 한 곳에서 한다. 경로마다 따로 거르면 한 경로로 비방문지가 샌다.
        if (!CandidatePolicy.roleOf(c).isFillable()) return false;
        // 행정 지정 구역·주소 불명 레코드는 어떤 보충 경로로도 넣지 않는다(공용 게이트).
        if (!PlaceCategoryConstants.isVisitableEntity(c.name(), c.address(), c.country())) return false;
        if (PlaceCategoryConstants.isViaRoute(c.attributes(), c.name(), c.category(), c.tags())) return false;
        // 선택 단계에서 뺀 집합 POI를 최종 패스가 활동으로 되살리지 않게 자동 보충에서도 뺀다.
        if (PlaceCategoryConstants.isAggregatePoi(c.name(), c.category())) return false;
        // 여객시설은 카테고리가 관광지로 오적재돼 있어도 방문지가 아니다.
        if (PlaceCategoryConstants.isPassengerFacility(c.name())) return false;
        // 약한 분류는 언어판·알려진 정도로 명소임이 드러날 때만 보충한다(상하이 타워는 "Structure"다).
        if (PlaceCategoryConstants.isWeakFill(c.category())
                && !isNotable(PlaceProminence.effectiveSitelinks(c.wikidataSitelinks(), c.travelerFame()))) {
            return false;
        }
        // 긴 등산은 하루 계획의 중심이라 코드가 빈자리 메우기로 넣지 않는다.
        return !isLongHike(c);
    }

    private static boolean isNotable(Integer sitelinks) {
        return sitelinks != null && sitelinks >= WEAK_FILL_NOTABLE_SITELINKS;
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
                    .filter(Objects::nonNull)
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
    void repartitionDaysByGeography(List<DayState> days, List<PlaceCandidate> candidates,
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
    private List<Integer> orderChunksByOriginalDays(List<List<Integer>> chunks,
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
    private double estimateTotalTourKm(List<List<Integer>> dayPlaces,
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
                    double d = haversine(at, coords.get(i));
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

    /**
     * 커버리지 유일 대표 중 day 중심에서 가까운 것만 보호한다. 무조건 보호하면 먼 장소 하나가 하루 동선을 망친다.
     *
     * <p>상한은 이동수단별 하루 예산 × {@value ScheduleTuning#COVERAGE_YIELD_FRACTION}이다. 고정 km로는 도보와 차량을 같은 자로 잴 수 없다.
     * 양보한 카테고리는 최종 커버리지 패스가 다시 시도하고, 실패하면 사용자 안내로 노출된다.
     */
    private Set<Integer> coverageProtected(DayState day, List<PlaceCandidate> candidates,
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
            if (haversine(center, cc) <= maxFromCenter) {
                keep.add(idx);
            } else {
                PlaceCandidate c = byIndex(candidates, idx);
                log.info("커버리지 보호 양보: {} 가 day 중심에서 {}km (상한 {}km) — 거리 트림에 맡긴다",
                        c != null ? c.name() : idx,
                        String.format("%.0f", haversine(center, cc)),
                        String.format("%.0f", maxFromCenter));
            }
        }
        return keep;
    }

    /** 카페 스톱 보호. 커버리지 대표와 같은 기준(day 중심에서 하루 예산의 일정 비율 이내)만 보호한다. */
    private Set<Integer> cafeStopProtected(DayState day, List<PlaceCandidate> candidates,
                                           String transportPref, ScheduleConfig cfg) {
        if (cfg == null || !cfg.cafeStop || day.cafeStopIndex == null
                || !day.placeIndices.contains(day.cafeStopIndex)) {
            return Set.of();
        }
        double maxFromCenter = cafeStopRadiusKm(transportPref, cfg);
        double[] center = centroidOf(day.placeIndices, candidates);
        double[] cc = coordsOf(byIndex(candidates, day.cafeStopIndex));
        if (center == null || cc == null || haversine(center, cc) > maxFromCenter) return Set.of();
        return Set.of(day.cafeStopIndex);
    }

    private double cafeStopRadiusKm(String transportPref, ScheduleConfig cfg) {
        return Math.min(CAFE_STOP_MAX_FROM_CENTER_KM,
                distanceLimits(transportPref, cfg.pace)[1] * COVERAGE_YIELD_FRACTION);
    }

    private static boolean hasCafeTheme(List<String> themes) {
        return themes != null && themes.stream()
                .anyMatch(t -> t != null && CAFE_STOP_THEMES.contains(t.trim().toLowerCase()));
    }

    /** 카페·디저트형 장소({@link CandidatePolicy#roleOf}가 CAFE). */
    private boolean isCafeLike(PlaceCandidate c) {
        return c != null && CandidatePolicy.roleOf(c) == CandidatePolicy.Role.CAFE;
    }

    /**
     * 마지막 날을 뺀 매일 오후에 들를 카페·디저트 1곳을 정한다({@link ScheduleConfig#cafeStop}일 때).
     * 그날 카페형 장소가 있으면 표시만 하고, 없으면 spare → 미사용 후보 순으로 중심 근처 인기 장소를 넣는다.
     * 카페는 60분 남짓이라 식사처럼 하루 장소 수 상한을 넘을 수 있다.
     */
    private void ensureCafeStops(List<DayState> days, List<PlaceCandidate> candidates, Deque<Integer> spare,
                                 ScheduleConfig cfg, String transportPref) {
        if (!cfg.cafeStop) return;
        Set<Integer> used = collectUsedIndices(days);
        double maxFromCenter = cafeStopRadiusKm(transportPref, cfg);

        for (int i = 0; i < days.size(); i++) {
            if (DayState.endsTrip(days, i)) continue;
            DayState day = days.get(i);
            Integer existing = day.placeIndices.stream()
                    .filter(idx -> isCafeLike(byIndex(candidates, idx)))
                    .findFirst().orElse(null);
            if (existing != null) {
                day.cafeStopIndex = existing;
                continue;
            }
            double[] center = centroidOf(day.placeIndices, candidates);
            if (center == null) continue;

            java.util.function.Predicate<PlaceCandidate> eligible = c -> isCafeLike(c) && isVisitableFill(c)
                    && !used.contains(c.index()) && coordsOf(c) != null
                    && haversine(center, coordsOf(c)) <= maxFromCenter;
            Comparator<PlaceCandidate> score = Comparator.comparingDouble(c ->
                    PlaceProminence.of(c) - haversine(center, coordsOf(c)) * GAP_FILL_DETOUR_WEIGHT);
            Integer pick = spare.stream().map(idx -> byIndex(candidates, idx))
                    .filter(Objects::nonNull).filter(eligible).max(score)
                    .or(() -> candidates.stream().filter(eligible).max(score))
                    .map(PlaceCandidate::index).orElse(null);
            if (pick == null) {
                log.info("카페 스톱 후보 없음: day={} (중심 {}km 이내 카페·디저트 없음)",
                        day.dayNumber, String.format("%.0f", maxFromCenter));
                continue;
            }
            day.placeIndices.add(pick);
            spare.remove(pick);
            used.add(pick);
            day.cafeStopIndex = pick;
            log.info("카페 스톱: day={} {}", day.dayNumber, nameOf(candidates, pick));
        }
    }

    /**
     * 사용자 카테고리 중 일정 안 대표가 딱 1곳인 인덱스를 각 day에 심는다.
     *
     * <p>거리 트림이 이 인덱스를 빼면 최종 커버리지 패스는 예산이 꽉 차 되돌릴 수 없어 트림 전에 보호 표시를 한다.
     * 대표가 2곳 이상이면 하나를 빼도 커버리지가 남아 표시하지 않는다. 숙소는 accommodationIndex로 따로 배정된다.
     */
    private void markCoverageCritical(List<DayState> days, List<PlaceCandidate> candidates,
                                      List<String> userCategories) {
        if (userCategories == null || userCategories.isEmpty()) return;

        Set<Integer> critical = new HashSet<>();
        for (String categoryId : userCategories) {
            if (categoryId == null || categoryId.isBlank()) continue;
            if ("accommodation".equalsIgnoreCase(categoryId.trim())) continue;

            List<Integer> holders = days.stream()
                    .flatMap(d -> d.placeIndices.stream())
                    .filter(i -> {
                        PlaceCandidate c = byIndex(candidates, i);
                        return c != null && PlaceCategoryConstants.matchesUserCategory(
                                categoryId, c.name(), c.category(), c.tags());
                    })
                    .distinct()
                    .toList();
            if (holders.size() == 1) critical.add(holders.get(0));
        }

        for (DayState day : days) day.coverageCriticalIndices = critical;
        if (!critical.isEmpty()) {
            log.info("커버리지 유일 대표 보호: {}곳 (거리 트림 제외 대상)", critical.size());
        }
    }

    /** {@code pairs}로 연결된 인덱스를 한 단위로 묶는다. union-find는 orderDay와 같은 방식. */
    private List<List<Integer>> groupByPairs(List<Integer> indices, List<List<Integer>> pairs) {
        Set<Integer> indexSet = new HashSet<>(indices);
        Map<Integer, Integer> parent = new HashMap<>();
        for (Integer idx : indices) parent.put(idx, idx);
        for (List<Integer> pair : pairs) {
            if (pair == null || pair.size() != 2) continue;
            if (indexSet.contains(pair.get(0)) && indexSet.contains(pair.get(1))) {
                union(parent, pair.get(0), pair.get(1));
            }
        }
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (Integer idx : indices) {
            groups.computeIfAbsent(find(parent, idx), k -> new ArrayList<>()).add(idx);
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
    private List<List<Integer>> refineChunksBalanced(List<List<Integer>> chunks,
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
                    double d = haversine(uc, centroids.get(k));
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
                    double d = haversine(uc, centroids.get(k));
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
    private double withinChunkSpread(List<List<Integer>> chunks, List<PlaceCandidate> candidates) {
        double total = 0;
        for (List<Integer> chunk : chunks) {
            double[] centroid = centroidOf(chunk, candidates);
            if (centroid == null) continue;
            double sum = 0;
            int n = 0;
            for (Integer idx : chunk) {
                double[] c = coordsOf(byIndex(candidates, idx));
                if (c == null) continue;
                sum += haversine(centroid, c);
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
    private void ensureMealPerChunk(List<List<Integer>> chunks, List<PlaceCandidate> candidates,
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
                    double d = haversine(targetCenter, cc);
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
                    double d = haversine(donorCenter, cc);
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

    private long countMeals(List<Integer> indices, List<PlaceCandidate> candidates) {
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
    private List<List<Integer>> splitIntoChunks(List<Integer> sortedUnitOrder,
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
    private double segmentCost(List<Integer> places, List<PlaceCandidate> candidates, int maxPerDay) {
        double[] centroid = centroidOf(places, candidates);
        double spread = 0;
        if (centroid != null) {
            double sum = 0;
            int counted = 0;
            for (Integer idx : places) {
                double[] c = coordsOf(byIndex(candidates, idx));
                if (c == null) continue;
                sum += haversine(centroid, c);
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
    private double[] hubAnchorOf(List<PlaceCandidate> candidates, TransportationHub hub) {
        List<PlaceCandidate> hubs = candidates.stream()
                .filter(c -> "TRANSIT_HUB".equals(PlaceCategoryConstants.majorCategory(c.category())))
                .filter(c -> coordsOf(c) != null)
                .toList();
        String name = hub != null ? hub.arrivalHub() : null;
        return hubs.stream()
                .filter(c -> name != null && (namesOverlap(c.name(), name) || namesOverlap(c.nameKo(), name)))
                .findFirst()
                .or(() -> hubs.stream().findFirst())
                .map(this::coordsOf)
                .orElse(null);
    }

    /**
     * 조각(날짜) 순서를 정한다. 허브에서 가장 가까운 조각에서 출발해 최근접 이웃으로 순회하고 2-opt로 다듬는다.
     * 왕복 여행이라 마지막 조각도 허브 근처에서 끝나는 편이 좋다.
     */
    private List<Integer> orderChunks(List<double[]> chunkCentroids, double[] hubAnchor) {
        if (chunkCentroids.size() <= 2) {
            List<Integer> trivial = new ArrayList<>();
            for (int i = 0; i < chunkCentroids.size(); i++) trivial.add(i);
            // 2개짜리도 허브에서 가까운 쪽이 1일차여야 도착 직후 장거리 이동이 생기지 않는다.
            if (hubAnchor != null && chunkCentroids.size() == 2
                    && chunkCentroids.get(0) != null && chunkCentroids.get(1) != null
                    && haversine(hubAnchor, chunkCentroids.get(1))
                            < haversine(hubAnchor, chunkCentroids.get(0))) {
                return List.of(1, 0);
            }
            return trivial;
        }
        int seed = nearestNodeTo(chunkCentroids, hubAnchor);
        List<Integer> order = nearestNeighborOrder(chunkCentroids, seed);
        // 서사 가중치(highlight/rest)는 day 내부 순서의 개념이라 조각 순서는 거리만으로 정한다.
        List<Intensity> flat = chunkCentroids.stream().map(c -> Intensity.NEUTRAL).toList();
        order = twoOpt(order, chunkCentroids, flat);
        return orientToAnchors(order, chunkCentroids, hubAnchor, hubAnchor);
    }

    /**
     * day가 지리적으로 뚜렷한 2개 클러스터로 갈리면 소수 클러스터 전체를 centroid가 더 가까운 인접 day로 옮긴다.
     * repairDistanceOutliers는 단일 이상치만 잡아 반반으로 쪼개진 day를 놓친다. pair는 쪼개지 않고 허브·숙소는 대상이 아니다.
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
     * 전역 지리 재배치(K-means식 1-pass). centroid가 확연히 더 가까운 다른 day가 있으면 방문지를 옮긴다.
     * repairClusterSplit이 인접 day·2클러스터만 보는 한계를 보완한다.
     * <ul>
     *   <li>대상은 placeIndices(방문지)뿐이다. 허브·숙소는 별도 필드다.</li>
     *   <li>다른 day centroid가 {@link #REBALANCE_MIN_GAIN_KM} 이상 가깝고 목적 day가 maxPerDay 미만일 때만 옮긴다.
     *       pair 멤버는 옮기지 않는다.</li>
     *   <li>한 번에 장소당 최대 1회 이동하고, fixpoint 루프가 반복 호출하며 수렴한다.</li>
     * </ul>
     */
    private boolean rebalanceDaysByGeography(List<DayState> days, List<PlaceCandidate> candidates,
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
                double myDist = haversine(coord, myCentroid);
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
     * {@link #SWAP_MIN_GAIN_KM} 이상일 때만이다. 개수 중립이라 꽉 찬 day에도 적용된다.
     * pair 멤버·사용자 필수 장소는 빼고, 한 패스에서 장소당 최대 1회 교환한다.
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
                        // 한쪽을 희생해 합만 맞추는 교환은 받지 않는다.
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
        return c != null && !c.keepsPlace();
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

        if (first.arrivalHubIndex == null && !first.resumesAfterTransfer) {
            Integer hub = findUnusedHub(candidates, used);
            if (hub != null) {
                first.arrivalHubIndex = hub;
                used.add(hub);
            }
        }
        if (last.departureHubIndex == null && !last.continuesAfter) {
            Integer hub = findUnusedHub(candidates, used);
            if (hub != null) {
                last.departureHubIndex = hub;
            }
        }
    }

    /**
     * enrich가 지정한 허브 이름으로 도착/출발 허브를 교정한다. rating만으로는 공항 본체와 부속시설 POI가 동률일 수 있다.
     * 이름 일치 > 부속시설 아님 > rating 순으로 고른다.
     */
    private void repairNamedHubs(List<DayState> days, List<PlaceCandidate> candidates, TransportationHub hub) {
        if (hub == null || days.isEmpty()) return;
        DayState first = days.get(0);
        DayState last = days.get(days.size() - 1);

        Integer arrival = first.resumesAfterTransfer ? null
                : pickNamedHub(candidates, hub.arrivalHub(), first.arrivalHubIndex);
        if (arrival != null) first.arrivalHubIndex = arrival;
        Integer departure = last.continuesAfter ? null
                : pickNamedHub(candidates, hub.departureHub(), last.departureHubIndex);
        if (departure != null) last.departureHubIndex = departure;
    }

    /** 현재 선택보다 hubScore가 확실히 높은 허브 후보 인덱스를 반환. 개선 없으면 null. */
    private Integer pickNamedHub(List<PlaceCandidate> candidates, String hubName, Integer current) {
        PlaceCandidate best = candidates.stream()
                .filter(c -> "TRANSIT_HUB".equals(PlaceCategoryConstants.majorCategory(c.category())))
                .filter(this::hasTransitName)
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

    /** 해외 허브는 원문 이름("成田国際空港")에 한국어 허브 신호가 없어 한국어 이름도 본다. */
    private boolean hasTransitName(PlaceCandidate c) {
        return PlaceCategoryConstants.hasTransitNameSignal(c.name())
                || PlaceCategoryConstants.hasTransitNameSignal(c.nameKo());
    }

    private static boolean isHubSubFacility(PlaceCandidate c) {
        return PlaceCategoryConstants.isHubSubFacility(c.name()) || PlaceCategoryConstants.isHubSubFacility(c.nameKo());
    }

    /** 허브 적합도: enrich 허브 이름 일치(+1000, 원문·한국어 이름) > 부속시설 아님(+500) > rating. */
    private double hubScore(PlaceCandidate c, String hubName) {
        double score = 0;
        if (hubName != null && !hubName.isBlank()
                && (namesOverlap(c.name(), hubName) || namesOverlap(c.nameKo(), hubName))) {
            score += 1000;
        }
        if (!isHubSubFacility(c)) score += 500;
        if (c.rating() != null) score += c.rating().doubleValue();
        return score;
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.toLowerCase().replaceAll("\\s+", "");
    }

    private boolean namesOverlap(String placeName, String hubName) {
        String a = normalizeName(placeName);
        String b = normalizeName(hubName);
        return !a.isEmpty() && !b.isEmpty() && (a.contains(b) || b.contains(a));
    }

    /**
     * 각 day 숙소를 그날 방문지 centroid에 가장 가까운 LODGING으로 교체한다. Sonnet 숙소는 동선과 무관해 장거리 귀가를 만든다.
     * userSelected 숙소는 교체하지 않고, 유효 좌표가 없으면 원본을 둔다.
     */
    private void repairAccommodationByProximity(List<DayState> days, List<PlaceCandidate> candidates) {
        // 방문지로 쓰인 LODGING을 빼야 같은 장소가 방문+숙소로 이중 등장하지 않는다.
        // dedupeMainStepsAcrossItinerary는 숙소 인덱스를 보지 않는다.
        Set<Integer> visitedIndices = new HashSet<>();
        for (DayState d : days) visitedIndices.addAll(d.placeIndices);

        List<PlaceCandidate> lodgings = candidates.stream()
                .filter(c -> "LODGING".equals(PlaceCategoryConstants.majorCategory(c.category())))
                .filter(c -> coordsOf(c) != null)
                .filter(c -> !visitedIndices.contains(c.index()))
                .collect(Collectors.toList());
        if (lodgings.isEmpty()) return;

        for (int i = 0; i < days.size(); i++) {
            DayState day = days.get(i);
            if (day.accommodationIndex == null) continue; // 귀가일
            PlaceCandidate current = byIndex(candidates, day.accommodationIndex);
            if (current != null && current.userSelected()) continue;

            double[] centroid = centroidOf(day.placeIndices, candidates);
            if (centroid == null) continue;

            // 그 숙소에서 아침에 출발하는 다음 날. 교체 손익을 그 날까지 함께 재야 한다.
            List<Integer> nextDayPlaces = i + 1 < days.size()
                    ? days.get(i + 1).placeIndices : List.of();

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
                double sameDayGain = curDist - best;

                // 하루씩만 보면 숙소를 당긴 대가를 다음 날 거리 예산에서 꺼내 써 다음 날 관광지가 트림된다.
                // 그래서 다음 날 첫 구간 증가분을 빼고, 그 대용값은 그날 장소 중 숙소에서 가장 가까운 곳까지의 거리로 둔다.
                double nextDayPenalty = nearestPlaceDistance(coordsOf(nearest), nextDayPlaces, candidates)
                        - nearestPlaceDistance(
                                current != null ? coordsOf(current) : null, nextDayPlaces, candidates);
                double netGain = sameDayGain - nextDayPenalty;

                if (netGain >= ACCOMMODATION_SWAP_MIN_GAIN_KM) {
                    log.info("숙소 동선 최적화: day={} {} → {} (당일 {}km 단축, 다음날 {}km 증가, 순이득 {}km)",
                            day.dayNumber,
                            current != null ? current.name() : day.accommodationIndex, nearest.name(),
                            String.format("%.1f", sameDayGain),
                            String.format("%.1f", nextDayPenalty),
                            String.format("%.1f", netGain));
                    day.accommodationIndex = nearest.index();
                } else if (sameDayGain >= ACCOMMODATION_SWAP_MIN_GAIN_KM) {
                    log.info("숙소 교체 보류: day={} 당일은 {}km 짧아지지만 다음날 첫 구간이 {}km 늘어 순이득 {}km",
                            day.dayNumber, String.format("%.1f", sameDayGain),
                            String.format("%.1f", nextDayPenalty), String.format("%.1f", netGain));
                }
            }
        }
    }

    /**
     * 좌표 {@code from}에서 주어진 방문지들 중 <b>가장 가까운 곳</b>까지의 거리(km).
     * 방문지가 없거나 좌표를 모르면 0이라 없는 날에 벌점을 주지 않는다.
     */
    private double nearestPlaceDistance(double[] from, List<Integer> placeIndices,
                                        List<PlaceCandidate> candidates) {
        if (from == null || placeIndices == null || placeIndices.isEmpty()) return 0.0;
        double best = Double.MAX_VALUE;
        for (Integer idx : placeIndices) {
            double[] cc = coordsOf(byIndex(candidates, idx));
            if (cc == null) continue;
            best = Math.min(best, haversine(from, cc));
        }
        return best == Double.MAX_VALUE ? 0.0 : best;
    }

    /**
     * 인접 day가 같은 지역이면 숙소를 통일한다.
     *
     * <p>짐을 싸는 비용은 거리로 환산되지 않아 거리 기준 보정은 보지 못한다. 그래서 날짜 수와 무관하게 연박을 기본으로 두고
     * 거리 손해가 {@link ScheduleTuning#CONTINUITY_MAX_EXTRA_KM}을 넘을 때만 바꾼다.
     */
    private void repairAccommodationContinuity(List<DayState> days, List<PlaceCandidate> candidates) {
        for (int i = 1; i < days.size(); i++) {
            DayState prev = days.get(i - 1);
            DayState curr = days.get(i);
            if (prev.accommodationIndex == null || curr.accommodationIndex == null) continue;
            if (prev.accommodationIndex.equals(curr.accommodationIndex)) continue;

            String prevRegion = dominantRegion(prev.placeIndices, candidates);
            String currRegion = dominantRegion(curr.placeIndices, candidates);
            if (prevRegion == null || !prevRegion.equals(currRegion)) continue;

            // region은 섬·광역 단위라 거칠어 같은 region이어도 동서로 갈린 날을 통일하면 매 구간이 길어진다.
            // 통일했을 때 숙소가 그날 중심에서 지나치게 멀어지면 그대로 둔다.
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

    /**
     * 숙소 종류 수에 상한을 둔다. {@link #repairAccommodationContinuity}는 인접 두 day만 봐서 합계를 통제하지 못한다.
     *
     * <p>숙박 {@value ScheduleTuning#NIGHTS_PER_LODGING}박마다 1곳(3박 → 2곳, 5박 → 3곳, 1~2박 → 1곳)이다.
     * 초과하면 통일 손해가 가장 작은 날부터 직전 날 숙소로 합쳐 가장 싼 조합이 남는다.
     */
    private void enforceLodgingCountCap(List<DayState> days, List<PlaceCandidate> candidates) {
        int nights = days.isEmpty() ? 0 : days.size() - (days.get(days.size() - 1).continuesAfter ? 0 : 1);
        if (nights <= 0) return;
        int cap = Math.max(1, (int) Math.ceil((double) nights / NIGHTS_PER_LODGING));

        while (distinctLodgings(days).size() > cap) {
            int bestDay = -1;
            double bestPenalty = Double.MAX_VALUE;
            for (int i = 1; i < days.size(); i++) {
                DayState prev = days.get(i - 1);
                DayState curr = days.get(i);
                if (prev.accommodationIndex == null || curr.accommodationIndex == null) continue;
                if (prev.accommodationIndex.equals(curr.accommodationIndex)) continue;
                PlaceCandidate chosen = byIndex(candidates, curr.accommodationIndex);
                if (chosen != null && chosen.userSelected()) continue; // 사용자가 고른 숙소는 바꾸지 않는다
                double penalty = unifyPenaltyKm(curr, prev, candidates);
                if (penalty < bestPenalty) {
                    bestPenalty = penalty;
                    bestDay = i;
                }
            }
            if (bestDay < 0) break; // 합칠 수 있는 인접 쌍이 없다

            DayState curr = days.get(bestDay);
            PlaceCandidate from = byIndex(candidates, curr.accommodationIndex);
            curr.accommodationIndex = days.get(bestDay - 1).accommodationIndex;
            log.info("숙소 수 상한({}박 → {}곳) 강제: day={} {} → {} (통일 손해 {}km)",
                    nights, cap, curr.dayNumber, from != null ? from.name() : "?",
                    nameOf(candidates, curr.accommodationIndex),
                    bestPenalty == Double.MAX_VALUE ? "?" : String.format("%.0f", bestPenalty));
        }
    }

    /**
     * 출발 허브 체류(분). 차량 여행이면 탑승 수속에 <b>렌터카 반납</b>을 더한다.
     *
     * <p>공항이 마지막 스텝이라 이 값이 사용자가 보는 일정 종료 시각을 정한다. 짧게 잡으면 종료가 실제보다 이르게 보인다.
     */
    private int departureHubMinutes(String transportPref) {
        return "car".equalsIgnoreCase(transportPref)
                ? DEPARTURE_HUB_MINUTES + RENTAL_RETURN_MINUTES
                : DEPARTURE_HUB_MINUTES;
    }

    private Set<Integer> distinctLodgings(List<DayState> days) {
        Set<Integer> distinct = new HashSet<>();
        for (DayState d : days) {
            if (d.accommodationIndex != null) distinct.add(d.accommodationIndex);
        }
        return distinct;
    }

    /**
     * {@code curr}를 {@code prev}의 숙소로 합쳤을 때 늘어나는 "방문지 중심 ↔ 숙소" 거리(km).
     * 좌표를 모르면 최댓값(가장 나중에 합침).
     */
    private double unifyPenaltyKm(DayState curr, DayState prev, List<PlaceCandidate> candidates) {
        double[] centroid = centroidOf(curr.placeIndices, candidates);
        double[] prevHotel = coordsOf(byIndex(candidates, prev.accommodationIndex));
        double[] currHotel = coordsOf(byIndex(candidates, curr.accommodationIndex));
        if (centroid == null || prevHotel == null || currHotel == null) return Double.MAX_VALUE;
        return haversine(centroid, prevHotel) - haversine(centroid, currHotel);
    }

    private double[] accommodationCoords(DayState day, List<PlaceCandidate> candidates) {
        return day.accommodationIndex != null ? coordsOf(byIndex(candidates, day.accommodationIndex)) : null;
    }

    /**
     * Sonnet이 지정한 허브 이름에 허브 신호(공항/역/터미널/항구)가 없으면 일반 방문지로 강등한다.
     * 오적재 데이터가 도착 지점으로 쓰이는 것을 막는다. 강등된 장소는 placeIndices로 보존하고 repairHubs가 진짜 허브로 다시 채운다.
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
        if (hasTransitName(c)) return hubIndex;
        if (!day.placeIndices.contains(hubIndex)) day.placeIndices.add(hubIndex);
        log.info("허브 오분류 강등: day={} {} → 일반 방문지", day.dayNumber, c.name());
        return null;
    }

    /**
     * 같은 장소(placeId, 없으면 candidate index)가 메인 스텝에 두 번 나오지 않게 한다.
     * 중복은 같은 대분류 미사용 spare로 바꾸고 없으면 그냥 뺀다. 숙소·허브는 대상이 아니다.
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
        double[] centroid = centroidOf(day.placeIndices, candidates);
        Integer chosen = spare.stream()
                .filter(si -> !seenIndices.contains(si))
                .map(si -> byIndex(candidates, si))
                .filter(Objects::nonNull)
                .filter(sc -> sc.placeId() == null || !seenPlaceIds.contains(sc.placeId()))
                .filter(sc -> like == null || isSubstituteFor(like, sc))
                .min(Comparator.comparingDouble(sc -> centroid != null && coordsOf(sc) != null
                        ? haversine(centroid, coordsOf(sc)) : Double.MAX_VALUE))
                .map(PlaceCandidate::index)
                .orElse(null);
        if (chosen != null) spare.remove(chosen);
        return chosen;
    }

    /**
     * 도보 선호일 때 익일 첫 방문지가 전날 숙소에서 도보권({@link #WALK_CONTINUITY_THRESHOLD_KM})을 벗어나면
     * 전날 숙소를 그날 첫 스텝으로 붙여 숙소에서 아침 출발하게 한다. car/any는 차량 이동을 전제로 해 보정하지 않는다.
     * 첫날, 도착 허브가 있는 날, 좌표 불명, 이미 첫 스텝이 그 숙소인 경우는 대상이 아니다.
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
     * 정기휴무 장소를 그날 열려 있는 같은 카테고리 spare로 교체한다.
     * openingHours가 없거나 그 요일을 파싱하지 못하면 열림으로 보고, 고신뢰 휴무 신호에만 작동한다.
     * swap이라 장소 수가 변하지 않으며 대체가 없으면 로그만 남긴다.
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
                // 포함 여부는 사용자의 판단이라 휴무여도 빼지 않는다.
                if (cand.userSelected()) {
                    log.warn("정기휴무 장소이나 사용자 필수라 교체 보류: day={}({}) {}",
                            day.dayNumber, dow, cand.name());
                    continue;
                }
                // pair 멤버도 교체한다. 짝 배치가 풀리는 것보다 휴무인 곳에 보내는 게 나쁘다.
                // 자리 치환이라 짝의 다른 쪽은 그날에 그대로 남는다.
                if (pairedIndices.contains(idx)) {
                    log.info("정기휴무 pair 멤버 교체 진행(인접 배치 포기): day={}({}) {}",
                            day.dayNumber, dow, cand.name());
                }

                final double[] anchor = centroid != null ? centroid : coordsOf(cand);
                Integer replacement = spare.stream()
                        .map(si -> byIndex(candidates, si))
                        .filter(Objects::nonNull)
                        .filter(sc -> isSubstituteFor(cand, sc))
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
     * 그 요일이 휴무인지. 고신뢰 신호("휴무"/"closed")에만 true이고 데이터 없음·요일 미매칭·판독 실패는 false(열림 가정).
     *
     * <p>여는/닫는 시각 판정과 같은 파서({@link OpeningHoursText})를 써야 "휴무는 아닌데 그 시각엔 닫혀 있다"를 일관되게 판단한다.
     */
    boolean isClosedOnDay(String openingHours, java.time.DayOfWeek dow) {
        return OpeningHoursText.isClosedOnDay(openingHours, dow);
    }

    /**
     * {@code [startMin, endMin]} 동안 영업하는지. 요일을 모르거나 판독할 수 없으면 true(열림 가정).
     *
     * <p>{@link #isClosedOnDay}가 요일 단위라면 이쪽은 <b>시각 단위</b>다. 늦게 여는 식당이 점심에 배정되는 것은 요일 판정으로 걸리지 않는다.
     */
    private boolean isOpenDuring(PlaceCandidate c, java.time.DayOfWeek dow, int startMin, int endMin) {
        if (c == null || dow == null) return true;
        return OpeningHoursText.isOpenDuring(c.openingHours(), dow, startMin, endMin);
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
     * TRANSIT_HUB 중 허브 본체를 고른다. Foursquare에는 심사대·게이트 같은 부속 POI가 섞여 있고
     * 대개 rating이 없거나 낮아 본체 우선 후 rating 순으로 고르면 입력 순서보다 안정적이다.
     */
    private Integer findUnusedHub(List<PlaceCandidate> candidates, Set<Integer> used) {
        return candidates.stream()
                .filter(c -> "TRANSIT_HUB".equals(PlaceCategoryConstants.majorCategory(c.category())))
                // 오적재된 비허브가 도착 허브로 쓰이지 않게 이름에 허브 신호가 있는 것만 받는다.
                .filter(this::hasTransitName)
                .filter(c -> !used.contains(c.index()))
                .max(Comparator
                        .comparingInt((PlaceCandidate c) -> isHubSubFacility(c) ? 0 : 1)
                        .thenComparing(c -> c.rating() != null ? c.rating() : BigDecimal.ZERO))
                .map(PlaceCandidate::index)
                .orElse(null);
    }


    /**
     * day 내 순서를 NN+2-opt로 정한다. 표준 2-opt는 pair 인접을 보장하지 못해 pair를 union-find로 한 노드로 묶었다 펼친다.
     * highlight/rest 태그는 작은 가중치로 더해져, 거리가 비슷하면 절정을 첫 스텝에 두지 않고 절정 뒤에 휴식을 두는 흐름을 고른다.
     *
     * @param startAnchor 그날 시작 지점 좌표(도착 허브 또는 전날 숙소). NN 시드를 이 근처에서 고른다.
     * @param endAnchor   그날 종료 지점 좌표(그날 숙소 또는 출발 허브). 뒤집어도 총 거리는 같아 여기에 더 가깝게 끝나는 방향을 고른다.
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

    /** anchor와 가장 가까운 노드의 인덱스. anchor가 null이면 0. */
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
     * 경로를 뒤집어도 총 거리는 같으므로 전날 숙소 근처에서 시작해 그날 숙소 근처에서 끝나는 방향을 고른다.
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
     * 2-opt: 개선되는 edge swap이 없을 때까지 반복한다. n=3에서도 전체 반전이 의미 있어 서사 가중치가 적용되도록
     * n&lt;3에서만 건너뛴다.
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
     *
     * <p>경로 순서대로 시간을 누적하면 저녁 식당이 경로상 앞일 때 뒤 활동이 전부 저녁 이후로 밀린다. 그래서
     * <ul>
     *   <li>DINING 개수로 슬롯을 정한다(1→점심, 2→점심+저녁, 3→아침+점심+저녁, 4번째부터 spare).
     *       첫=점심/마지막=저녁으로 정하면 브런치를 점심으로 오판한다.</li>
     *   <li>비식사 활동은 상대 순서를 지키며 오전/오후/저녁 시간창 용량만큼 채우고 초과분은 spare로 보낸다.</li>
     * </ul>
     */
    private List<StepData> scheduleDay(DayState day, List<Integer> orderedMain,
                                        List<PlaceCandidate> candidates, Deque<Integer> spareIndices,
                                        ScheduleConfig cfg, double[] dayCentroid,
                                        Set<Integer> usedIndices, String transportPref,
                                        PlaceCandidate startFrom, int maxPerDay) {
        cfg = withDepartureLegLead(day, cfgFor(day, cfg), candidates, startFrom, transportPref);
        List<Integer> diningAll = orderedMain.stream()
                .filter(idx -> {
                    PlaceCandidate c = byIndex(candidates, idx);
                    // 바·디저트·베이커리·간식과 집합 POI는 한 끼가 아니라 식사 슬롯에서 뺀다. 검색 슬롯과 같은 기준(isMealPlace)이다.
                    return c != null && c.mealEligible();
                })
                .collect(Collectors.toList());
        List<Integer> meals = selectMeals(diningAll, candidates, spareIndices);
        // spare로 보낸 장소를 그날 목록에 남기면 쿼터를 점유해 빈 시간 채우기가 시도되지 않는다.
        for (Integer idx : diningAll) {
            if (!meals.contains(idx)) day.placeIndices.remove(idx);
        }
        // 아침 슬롯은 숙소에서 출발하는 날에만 성립한다. 도착일에 두면 공항 옆 식당이 늦은 아침이 된다.
        boolean allowBreakfast = day.arrivalHubIndex == null && startFrom != null
                && cfg.morningStart <= BREAKFAST_LATEST_START;
        meals = dropBreakfastIfNotAllowed(meals, day, candidates, spareIndices, allowBreakfast);
        // 숙소에서 먼 아침을 버려 아침부터 원정을 막는다. 남은 개수가 버킷 용량을 정한다.
        meals = dropDistantBreakfast(meals, day, candidates, spareIndices);
        Set<Integer> mealSet = new HashSet<>(diningAll);

        // 비식사 활동을 시간대 적합성(야외=주간, Bar=저녁)을 지키며 오전/오후/저녁 버킷에 나누고, 못 넣는 활동은 spare로 트림한다.
        List<Integer> activities = orderedMain.stream()
                .filter(idx -> !mealSet.contains(idx))
                .collect(Collectors.toList());
        int[] budgets = dayBudgets(day, cfg, meals.size(), arrivalLeadMinutes(day, orderedMain, candidates, transportPref));
        // 도착 허브는 활동 목록에 없지만 오전 시간을 먹는다. 빼지 않으면 오전 예산이 부풀어 점심이 창 밖으로 밀린다.
        if (day.arrivalHubIndex != null) {
            PlaceCandidate hub = byIndex(candidates, day.arrivalHubIndex);
            if (hub != null) {
                budgets[0] = Math.max(0, budgets[0] - resolveVisitMinutes(hub) - INTRA_BUCKET_TRAVEL_MINUTES);
            }
        }
        // 버킷은 실제 체류시간으로 채워야 시계와 용량이 어긋나지 않는다.
        int[] usedM = {0}, usedA = {0}, usedE = {0};
        List<Integer> morning = new ArrayList<>(), afternoon = new ArrayList<>(), evening = new ArrayList<>();
        for (Integer idx : activities) {
            PlaceCandidate actCand = byIndex(candidates, idx);
            // 카페 스톱은 점심과 저녁 사이(오후)에 둔다. 오전 버킷부터 채우면 아침 9시 카페가 된다.
            if (cfg.cafeStop && isSameIndex(day.cafeStopIndex, idx)) {
                // 늦게 도착한 날은 오후가 짧아 카페까지 넣으면 저녁이 시간대 밖으로 밀린다
                if (day.hasUserWindow() && !afternoon.isEmpty()
                        && usedA[0] + resolveVisitMinutes(actCand) + INTRA_BUCKET_TRAVEL_MINUTES > budgets[1]) {
                    day.placeIndices.remove(idx);
                    spareIndices.addLast(idx);
                    log.info("시간대 예산 초과로 카페 생략: day={} {}", day.dayNumber, nameOf(candidates, idx));
                    continue;
                }
                afternoon.add(idx);
                usedA[0] += resolveVisitMinutes(actCand) + INTRA_BUCKET_TRAVEL_MINUTES;
                continue;
            }
            boolean placed = switch (timePreference(actCand)) {
                case DAYTIME -> addIfFitsBudget(morning, budgets[0], usedM, idx, actCand, false)
                        || addIfFitsBudget(afternoon, budgets[1], usedA, idx, actCand, true);
                case EVENING -> addIfFitsBudget(evening, budgets[2], usedE, idx, actCand, false);
                // 긴 등산은 오전에만 두고, 오전 예산보다 길어도 빈 오전에는 넣는다. 점심이 밀려도 오후 등산보다 현실적이다.
                case MORNING_ONLY -> addIfFitsBudget(morning, budgets[0], usedM, idx, actCand, true);
                case FLEXIBLE -> addIfFitsBudget(morning, budgets[0], usedM, idx, actCand, false)
                        || addIfFitsBudget(afternoon, budgets[1], usedA, idx, actCand, true)
                        || addIfFitsBudget(evening, budgets[2], usedE, idx, actCand, false);
            };
            if (!placed) {
                // 사용자 필수 장소는 버킷 용량에 밀려도 강제 편입한다. 시각은 23:59 클램프가 안전망이다.
                if (actCand != null && actCand.keepsPlace()) {
                    // 오후에 몰아넣으면 야경 명소가 낮에 가므로 시간대가 정해진 장소는 그 버킷으로 보낸다.
                    switch (timePreference(actCand)) {
                        case EVENING -> evening.add(idx);
                        case MORNING_ONLY -> morning.add(idx);
                        default -> afternoon.add(idx);
                    }
                } else {
                    day.placeIndices.remove(idx);
                    spareIndices.addLast(idx);
                    log.info("시간대 예산 초과로 제외: day={} {} ({}, {}분)", day.dayNumber,
                            actCand != null ? actCand.name() : idx, timePreference(actCand),
                            resolveVisitMinutes(actCand));
                }
            }
        }

        // 버킷 경계를 알아야 점심이 오전·오후 활동 사이 우회를 최소화하는지 판정할 수 있어 버킷 확정 뒤에 식사 슬롯을 정한다.
        // 그날 중심 최근접으로 고르면 오전 → 점심 → 오후가 동서로 왕복한다.
        MealSlots slots = assignMealSlots(meals, day, candidates, morning, afternoon, startFrom,
                dayCentroid, spareIndices, cfg.morningStart);
        Integer breakfastIdx = slots.breakfast();
        Integer lunchIdx = slots.lunch();
        Integer dinnerIdx = slots.dinner();
        // assignMealSlots는 저녁·점심을 먼저 고르고 남은 것을 아침으로 둬, 실제 아침이 dropDistantBreakfast 검사를
        // 거치지 않았을 수 있다. 그래서 슬롯 확정 뒤 다시 본다.
        if (breakfastIdx != null && !isReasonableBreakfast(day, byIndex(candidates, breakfastIdx),
                startFrom, candidates, transportPref, cfg.morningStart)) {
            day.placeIndices.remove(breakfastIdx);
            spareIndices.addLast(breakfastIdx);
            log.info("아침 식사 생략(숙소에서 멀거나 점심에 붙음): day={} {}", day.dayNumber,
                    nameOf(candidates, breakfastIdx));
            breakfastIdx = null;
        }

        // 저녁 버킷이 비면 저녁 식당이 숙소 직전이 된다. 숙소에 더 가까운 오후 활동이 있으면 저녁 뒤로 옮겨 숙소 근처에서 하루를 마친다.
        endDayNearAccommodation(day, candidates, afternoon, evening, dinnerIdx);
        // 저녁 활동이 숙소-저녁식당 거리보다 확연히 멀면 spare로 보내 저녁 뒤 장거리 원정을 막는다.
        filterEveningAwayFromAccommodation(day, candidates, evening, dinnerIdx, spareIndices);

        // 먹는 스텝이 연달아 붙으면 "빵집 나와서 7분 뒤 횟집"이 된다. 버킷 안의 연속도,
        // 버킷 마지막(바로 뒤가 끼니)도 같은 문제라 한 규칙으로 본다.
        separateFoodSteps(day, morning, candidates, lunchIdx != null, "점심");
        separateFoodSteps(day, afternoon, candidates, dinnerIdx != null, "저녁");
        separateFoodSteps(day, evening, candidates, false, null);

        // 최종 시퀀스: 허브 → 아침 → 오전활동 → 점심 → 오후활동 → 저녁 → 저녁활동 → 숙소 → 출발허브
        List<Integer> seq = new ArrayList<>();
        if (day.arrivalHubIndex != null) seq.add(day.arrivalHubIndex);
        if (breakfastIdx != null) seq.add(breakfastIdx);
        seq.addAll(morning);
        if (lunchIdx != null) seq.add(lunchIdx);
        seq.addAll(afternoon);
        if (dinnerIdx != null) seq.add(dinnerIdx);
        seq.addAll(evening);
        // 도보 연속성 보정이 전날 숙소를 첫 스텝으로 붙이면 같은 인덱스가 이미 있어,
        // 무조건 덧붙이면 같은 장소가 두 스텝이 되고 숙박비도 두 번 계산된다.
        if (day.accommodationIndex != null && !seq.contains(day.accommodationIndex)) {
            seq.add(day.accommodationIndex);
        }
        if (day.departureHubIndex != null && !seq.contains(day.departureHubIndex)) {
            seq.add(day.departureHubIndex);
        }

        // 식사 슬롯 재배열로 순서가 orderDay 경로순과 달라져 확정 시퀀스로 거리 예산을 다시 검사한다.
        // 식사·허브·숙소·사용자 필수 장소는 거리 위반이어도 포함이 우선이다.
        Set<Integer> protectedIdx = new HashSet<>();
        if (breakfastIdx != null) protectedIdx.add(breakfastIdx);
        if (lunchIdx != null) protectedIdx.add(lunchIdx);
        if (dinnerIdx != null) protectedIdx.add(dinnerIdx);
        if (day.arrivalHubIndex != null) protectedIdx.add(day.arrivalHubIndex);
        if (day.accommodationIndex != null) protectedIdx.add(day.accommodationIndex);
        if (day.departureHubIndex != null) protectedIdx.add(day.departureHubIndex);
        for (Integer idx : seq) {
            PlaceCandidate c = byIndex(candidates, idx);
            if (c != null && c.keepsPlace()) protectedIdx.add(idx);
        }
        // 카테고리 유일 대표도 보호한다. 빼면 그 카테고리가 0개가 되고, 예산을 크게 깨면 coverageProtected가 양보한다.
        protectedIdx.addAll(coverageProtected(day, candidates, transportPref, cfg));
        protectedIdx.addAll(cafeStopProtected(day, candidates, transportPref, cfg));
        Integer iconic = mostIconicInSequence(seq, candidates);
        if (iconic != null) protectedIdx.add(iconic);
        // 전날 숙소(또는 도착 허브)에서 첫 스텝까지의 이동을 빼면 먼 곳에서 시작하는 날을 상한 이내로 오판한다.
        PlaceCandidate budgetOrigin = (startFrom != null && !seq.isEmpty() && seq.get(0) != startFrom.index())
                ? startFrom : null;
        Set<Integer> pinnedMeals = new HashSet<>();
        if (breakfastIdx != null) pinnedMeals.add(breakfastIdx);
        if (lunchIdx != null) pinnedMeals.add(lunchIdx);
        if (dinnerIdx != null) pinnedMeals.add(dinnerIdx);
        // 트림만 막으면 먼 장소를 가까운 spare로 교체하는 경로가 대표 명소를 대신 빼서 교체에서도 고정한다.
        if (iconic != null) pinnedMeals.add(iconic);
        trimSequenceOverBudget(day, seq, candidates, spareIndices, transportPref, protectedIdx, budgetOrigin, cfg,
                pinnedMeals, usedIndices);
        // 귀가 시각 상한은 거리 예산과 별개다. 상한 내여도 체류·이동이 쌓이면 심야 귀가가 된다.
        trimSequenceForDepartureTime(day, seq, candidates, spareIndices, cfg, budgetOrigin,
                transportPref, protectedIdx, breakfastIdx, lunchIdx, dinnerIdx);

        List<StepData> steps = new ArrayList<>();
        // 같은 날 안에서 대안이 겹치지 않도록 스텝 간에 공유하는 집합.
        Set<String> dayAltKeys = new HashSet<>();
        int currentMinutes = cfg.morningStart;
        // 전날 숙소를 출발지로 둬야 첫 스텝에도 이동 정보가 붙고 첫 도착이 출발 + 이동시간이 된다.
        // walk 보정으로 숙소가 이미 첫 스텝이면 중복 적용하지 않는다.
        PlaceCandidate prev = (startFrom != null && !seq.isEmpty() && seq.get(0) != startFrom.index())
                ? startFrom : null;
        int[] overQuotaUsed = {0}; // pace 초과 채움은 하루 1곳까지

        for (int idx : seq) {
            PlaceCandidate cand = byIndex(candidates, idx);
            if (cand == null) continue;

            // 식사 슬롯 직전 빈 시간이 활동 1개 분량 이상이면 spare에서 근처 활동을 삽입해 메운다.
            if ((lunchIdx != null && idx == lunchIdx) || (dinnerIdx != null && idx == dinnerIdx)) {
                int mealStart = (lunchIdx != null && idx == lunchIdx) ? LUNCH_START : DINNER_START;
                GapFill fill = fillGapBeforeMeal(day, candidates, spareIndices, usedIndices,
                        prev, currentMinutes, mealStart, cand, dayCentroid, transportPref,
                        // 채운 장소는 day.placeIndices에 들어가므로 이미 센 것으로 친다(0을 넘긴다).
                        gapFillQuota(day, maxPerDay, 0), dayAltKeys, cfg, overQuotaUsed);
                steps.addAll(fill.steps());
                prev = fill.prev();
                currentMinutes = fill.minutes();
            }

            TransportLeg leg = computeLeg(prev, cand, transportPref, day.dayNumber);
            if (leg != null) currentMinutes += leg.durationMin();

            if (breakfastIdx != null && idx == breakfastIdx) currentMinutes = Math.max(currentMinutes, cfg.morningStart);
            if (lunchIdx != null && idx == lunchIdx) currentMinutes = Math.max(currentMinutes, LUNCH_START);
            if (dinnerIdx != null && idx == dinnerIdx) currentMinutes = Math.max(currentMinutes, DINNER_START);
            if (!cand.mealEligible() && timePreference(cand) == TimePreference.EVENING) {
                currentMinutes = Math.max(currentMinutes, EVENING_ACTIVITY_EARLIEST_START);
            }

            boolean isMorningDeparture = prev == null && day.arrivalHubIndex == null
                    && PlaceCategoryConstants.isAccommodation(cand.category());
            int visitMinutes;
            if (isSameIndex(day.departureHubIndex, idx)) {
                // 귀가 허브는 탑승 수속 시간이라 관광 기본값을 쓰면 종료가 그만큼 밀린다.
                visitMinutes = departureHubMinutes(transportPref);
            } else if (isMorningDeparture) {
                visitMinutes = MORNING_DEPARTURE_MINUTES;
            } else if (PlaceCategoryConstants.isAccommodation(cand.category())) {
                visitMinutes = ACCOMMODATION_ARRIVAL_MINUTES; // 체크인을 관광 체류로 잡으면 종료가 심야로 밀린다
            } else if (isDiningCand(cand)) {
                visitMinutes = DINING_VISIT_MINUTES;
            } else {
                visitMinutes = resolveVisitMinutes(cand);
            }
            // 누적 시각이 23:59에 닿으면 start와 end가 같아져 HardValidator의 endTime>startTime이 깨진다.
            // start를 최소 1분 체류가 남는 지점까지로 제한한다.
            int startMin = Math.min(currentMinutes, END_OF_DAY_MINUTES - 1);
            if (!steps.isEmpty()) {
                int lastEnd = toMinutesOrZero(steps.get(steps.size() - 1).endTime());
                startMin = Math.max(startMin, Math.min(lastEnd, END_OF_DAY_MINUTES - 1));
            }

            // 버킷은 분 예산만 세서 시계가 밀리면 정상 배치된 장소도 한밤중에 시작할 수 있어 확정 시각으로 다시 거른다.
            // 식사·허브·숙소·사용자 필수 장소는 구조상 빼지 않는다.
            boolean fixedSlot = isSameIndex(day.arrivalHubIndex, idx)
                    || isSameIndex(day.departureHubIndex, idx)
                    || isSameIndex(day.accommodationIndex, idx)
                    || isSameIndex(breakfastIdx, idx)
                    || isSameIndex(lunchIdx, idx)
                    || isSameIndex(dinnerIdx, idx);
            if (!fixedSlot && !cand.userSelected()) {
                // 확정 시각에 영업 중인지 본다. 숙소 영업시간은 프런트 운영 시간이라 걸면 저녁 체크인이 막혀 fixedSlot은 제외한다.
                Integer openFrom = OpeningHoursText.earliestStart(
                        cand.openingHours(), day.dayOfWeek, startMin, visitMinutes);
                if (openFrom != null && (openFrom < 0 || openFrom - startMin > OPENING_WAIT_LIMIT_MINUTES)) {
                    day.placeIndices.remove(Integer.valueOf(idx));
                    spareIndices.addLast(idx);
                    log.info("영업시간 가드 트림: day={}({}) {} ({} 시작 예정, 영업 [{}])",
                            day.dayNumber, day.dayOfWeek, cand.name(), formatMinutes(startMin),
                            cand.openingHours() == null ? "?" : cand.openingHours());
                    continue;
                }
                if (openFrom != null && openFrom > startMin) {
                    // 개장까지 대기가 한도 안이면 버리지 않고 시작을 늦춘다.
                    log.info("개장 대기로 시작 시각 조정: day={}({}) {} {} → {}",
                            day.dayNumber, day.dayOfWeek, cand.name(),
                            formatMinutes(startMin), formatMinutes(openFrom));
                    startMin = openFrom;
                    currentMinutes = Math.max(currentMinutes, startMin);
                }
                int latestStart = Math.min(cfg.eveningCap, ABSOLUTE_LATEST_START_MINUTES);
                TimePreference pref = timePreference(cand);
                if (pref == TimePreference.DAYTIME) {
                    latestStart = Math.min(latestStart, daytimeLatestStart(cfg));
                } else if (pref == TimePreference.MORNING_ONLY) {
                    latestStart = Math.min(latestStart, LONG_HIKE_LATEST_START_MINUTES);
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
                    buildAlternatives(cand, dayCentroid, spareIndices, usedIndices, candidates,
                            transportPref, dayAltKeys)));

            currentMinutes = Math.max(currentMinutes, endMin);
            prev = cand;
        }
        return dropAdjacentDuplicates(steps, day);
    }

    /**
     * 같은 장소가 <b>바로 연달아</b> 두 스텝으로 들어간 경우를 제거한다(출력 경계 안전망).
     *
     * <p>버킷·식사 슬롯·갭 보충·숙소 배정이 각자 시퀀스에 장소를 넣어 같은 인덱스를 집을 수 있다.
     * 인접 중복은 항상 오류라 마지막에 한 번 더 거른다. 떨어진 같은 장소(아침 숙소 출발 → 밤 체크인)는 정상이다.
     */
    private List<StepData> dropAdjacentDuplicates(List<StepData> steps, DayState day) {
        List<StepData> result = new ArrayList<>(steps.size());
        String prevKey = null;
        for (StepData s : steps) {
            String key = s.place() != null ? placeKey(s.place().name(), s.place().address()) : null;
            if (key != null && key.equals(prevKey)) {
                log.info("인접 중복 스텝 제거: day={} {}", day.dayNumber, s.place().name());
                continue;
            }
            result.add(s);
            prevKey = key;
        }
        return result;
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
     * 두 장소 사이 이동 추정. 대안 선택 재계산 경로(ItineraryService)와 표시가 어긋나지 않게 {@link GeoUtils#estimateLeg} 공식을 공유한다.
     * 비현실 구간(200km 초과)은 로그만 남기고 null을 반환한다.
     */
    private TransportLeg computeLeg(PlaceCandidate prev, PlaceCandidate cand, String transportPref, int dayNumber) {
        if (prev == null || cand == null) return null;
        double[] a = coordsOf(prev);
        double[] b = coordsOf(cand);
        if (a == null || b == null) return null;
        boolean hubTransfer = PlaceCategoryConstants.isOverseasHub(prev.country(), prev.category())
                || PlaceCategoryConstants.isOverseasHub(cand.country(), cand.category());
        GeoUtils.TransportLeg leg = GeoUtils.estimateLeg(a, b, transportPref, MAX_REASONABLE_LEG_KM, hubTransfer);
        if (leg == null) {
            log.warn("RouteOptimizer: 비현실적 구간거리 {}km (day={}, {} → {}) — 교통정보 생략",
                    String.format("%.1f", haversine(a, b)), dayNumber, prev.name(), cand.name());
            return null;
        }
        return new TransportLeg(leg.mode(), leg.durationMin(), leg.distanceKm(), leg.cost());
    }

    /**
     * 버킷에 활동을 <b>분 예산</b>(체류시간 + 구간 이동 여유)으로 넣는다. guaranteeOne이면 빈 버킷에는 예산을 넘겨도 1개 넣는다.
     */
    private boolean addIfFitsBudget(List<Integer> bucket, int budgetMinutes, int[] usedMinutes,
                                    Integer idx, PlaceCandidate cand, boolean guaranteeOne) {
        if (budgetMinutes <= 0) return false;
        int need = resolveVisitMinutes(cand) + INTRA_BUCKET_TRAVEL_MINUTES;
        boolean fits = usedMinutes[0] + need <= budgetMinutes;
        // 빈 버킷 1개 보장은 오후에만 준다. 오전·저녁은 뒤에 식사·마감 데드라인이 있어 넘겨 넣으면 그대로 밀린다.
        // 오후는 다른 버킷의 폴백 목적지라 하루가 비지 않게 지킨다.
        if (!fits && !(guaranteeOne && bucket.isEmpty())) return false;
        bucket.add(idx);
        usedMinutes[0] += need;
        return true;
    }

    /**
     * 관광 스텝 체류시간(분). 권장 체류시간이 있으면 30~180(긴 등산은 LONG_HIKE_MAX_MINUTES)으로 클램프하고,
     * 없으면 {@link PlaceCategoryConstants#heuristicVisitMinutes}를 쓴다. 일괄 90분이면 등산형 오름과 해변 산책이 구분되지 않는다.
     */
    private int resolveVisitMinutes(PlaceCandidate cand) {
        if (cand == null) return DEFAULT_VISIT_MINUTES;
        Integer recommended = cand.recommendedDurationMinutes();
        if (recommended != null && recommended > 0) {
            int max = isLongHike(cand) ? LONG_HIKE_MAX_MINUTES : 180;
            return Math.max(30, Math.min(max, recommended));
        }
        return PlaceCategoryConstants.heuristicVisitMinutes(cand.name(), cand.category());
    }

    /**
     * 긴 등산: 등산·트레킹 성격이면서 체류가 {@value ScheduleTuning#LONG_HIKE_MIN_MINUTES}분 이상.
     * 오전 시작만 허용하고 자동 보충 경로로는 넣지 않는다(Sonnet·사용자가 고른 경우만).
     */
    private boolean isLongHike(PlaceCandidate c) {
        return isHikeOfAtLeast(c, LONG_HIKE_MIN_MINUTES);
    }

    private boolean isHikeOfAtLeast(PlaceCandidate c, int minMinutes) {
        if (c == null || !PlaceCategoryConstants.isHikingPlace(c.attributes(), c.name(), c.tags())) return false;
        Integer recommended = c.recommendedDurationMinutes();
        int minutes = recommended != null && recommended > 0
                ? recommended
                : PlaceCategoryConstants.heuristicVisitMinutes(c.name(), c.category());
        return minutes >= minMinutes;
    }


    /**
     * 장소의 적합 시간대. 요청 의도 > recommended_time_slots > 카테고리 휴리스틱(야외 관광지=주간, Bar=저녁) 순으로 판정한다.
     */
    private TimePreference timePreference(PlaceCandidate c) {
        if (c == null) return TimePreference.FLEXIBLE;
        // "야경" 요청의 전망대는 낮 명소가 아니라 요청 의도가 데이터·지형 판정보다 우선한다.
        if ("evening".equals(c.intentTime())) return TimePreference.EVENING;
        if ("morning".equals(c.intentTime())) return TimePreference.MORNING_ONLY;
        if (isHikeOfAtLeast(c, ScheduleTuning.MORNING_ONLY_HIKE_MIN_MINUTES)) return TimePreference.MORNING_ONLY;
        List<String> slots = c.recommendedTimeSlots();
        if (slots != null && !slots.isEmpty()) {
            boolean evening = slots.stream().anyMatch(s ->
                    containsAny(s, "저녁", "밤", "야간", "심야", "evening", "night"));
            boolean daytime = slots.stream().anyMatch(s ->
                    containsAny(s, "아침", "오전", "오후", "낮", "주간", "morning", "afternoon", "daytime"));
            // 데이터가 저녁 전용/주간 전용이라고 명시할 때만 따른다. 그래야 야간 조명 명소가 지형 판정에 눌려 낮으로 가지 않는다.
            if (evening && !daytime) return TimePreference.EVENING;
            if (daytime && !evening) return TimePreference.DAYTIME;
            // 섞여 있으면 지형 판정으로 넘긴다. enrich가 시간대를 느슨하게 나열해 FLEXIBLE로 두면 해변이 저녁 버킷에 들어간다.
        }
        if (PlaceCategoryConstants.isBar(c.category())) return TimePreference.EVENING;
        if (PlaceCategoryConstants.isDaytimeOutdoor(c.category())) return TimePreference.DAYTIME;
        // 총칭 리프 카테고리로는 못 거르는 야외 장소(해변·오름·공원)를 세부 유형으로 판정한다.
        if (PlaceCategoryConstants.isDaytimeSubType(subTypeOf(c))) return TimePreference.DAYTIME;
        // 시간대 데이터가 없는 야외·유적 명소를 FLEXIBLE로 두면 저녁 식사 뒤 사적지 방문이 생겨 낮으로 본다.
        // 실내 문화시설은 저녁에도 가므로 그대로 두고, 야경 명소는 위의 보강 데이터로 판정된다.
        if ((slots == null || slots.isEmpty()) && c.category() != null
                && c.category().toLowerCase().contains("landmarks and outdoors")) {
            return TimePreference.DAYTIME;
        }
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
     * 저녁 이후 활동이 숙소에서 "숙소-저녁식당 거리 + 허용치"보다 멀면 spare로 뺀다. 저녁 시간대는 숙소로 수렴하는 방향만 허용한다.
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
            if (c != null && c.keepsPlace()) continue; // 필수·의도 장소는 거리로 빼지 않음
            double[] coord = coordsOf(c);
            if (coord != null && haversine(coord, hotel) > allowed) {
                log.info("저녁 활동이 숙소에서 과도히 멂({}km > {}km) — spare 전환: day={} {}",
                        String.format("%.1f", haversine(coord, hotel)), String.format("%.1f", allowed),
                        day.dayNumber, c != null ? c.name() : idx);
                it.remove();
                day.placeIndices.remove(idx);
                spareIndices.addLast(idx);
            }
        }
    }


    /**
     * 식사 슬롯 시작까지 빈 시간이 {@code GAP_FILL_THRESHOLD_MINUTES} 이상이면 현위치→식당 경로에서 크게 벗어나지 않는
     * 주간 활동(관광지/카페)을 사이에 넣는다. 후보가 없으면 갭을 두고 프론트가 자유시간으로 표시한다.
     */
    private GapFill fillGapBeforeMeal(DayState day, List<PlaceCandidate> candidates,
                                      Deque<Integer> spareIndices, Set<Integer> usedIndices,
                                      PlaceCandidate prev, int currentMinutes, int mealStart,
                                      PlaceCandidate mealCand, double[] dayCentroid, String transportPref,
                                      int remainingQuota, Set<String> dayAltKeys, ScheduleConfig cfg,
                                      int[] overQuotaUsed) {
        List<StepData> inserted = new ArrayList<>();
        int guard = 0;
        while (guard++ < 3) {
            TransportLeg toMeal = computeLeg(prev, mealCand, transportPref, day.dayNumber);
            int arrivalAtMeal = currentMinutes + (toMeal != null ? toMeal.durationMin() : 0);
            if (mealStart - arrivalAtMeal < GAP_FILL_THRESHOLD_MINUTES) break;

            int gap = mealStart - arrivalAtMeal;
            // 식사도 장소 수에 들어가 상한 안에서는 오후가 통째로 빌 수 있어, pace 상한이 찼어도 긴 공백은 1곳까지 넣는다.
            // relaxed는 사용자가 여유를 고른 것이라 쉬어 가는 카페만 넣는다.
            boolean overQuota = inserted.size() >= remainingQuota;
            // 호출 단위로 세면 하루 2곳이 넘어가므로 pace 초과 채움은 점심 앞·저녁 앞을 합쳐 하루 1곳까지다.
            if (overQuota && !(gap >= LARGE_GAP_MINUTES && inserted.isEmpty() && overQuotaUsed[0] == 0)) break;
            boolean cafeOnly = overQuota && "relaxed".equals(cfg != null ? cfg.pace : null);
            Integer fillIdx = pickGapFiller(prev, mealCand, candidates, spareIndices, usedIndices,
                    transportPref, 1.0, null, 0, cfg, cafeOnly, day.dayOfWeek);
            if (fillIdx == null && gap >= LARGE_GAP_MINUTES) {
                // 긴 공백은 spare가 말라도 미사용 후보까지 넓히고 반경도 완화한다. 몇 시간 비우느니 조금 더 달리는 게 낫다.
                // 거리 예산은 지킨다. 반경만 풀면 하루 주행 상한을 깨고 다음 패스에 트림되는 churn이 생긴다.
                fillIdx = pickGapFiller(prev, mealCand, candidates,
                        new ArrayDeque<>(unusedIndices(candidates, usedIndices)), usedIndices,
                        transportPref, LARGE_GAP_RADIUS_MULTIPLIER, day,
                        LARGE_GAP_MAX_EXTRA_DETOUR_KM, cfg, cafeOnly, day.dayOfWeek);
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
            // day 상태에도 기록해야 유형 상한·교체·재스케줄이 이 장소를 보고 같은 종류를 또 넣지 않는다.
            if (!day.placeIndices.contains(fillIdx)) day.placeIndices.add(fillIdx);
            if (overQuota) overQuotaUsed[0]++;

            PlaceCandidate fill = byIndex(candidates, fillIdx);
            TransportLeg leg = computeLeg(prev, fill, transportPref, day.dayNumber);
            if (leg != null) currentMinutes += leg.durationMin();
            int startMin = currentMinutes;
            int endMin = startMin + resolveVisitMinutes(fill);
            inserted.add(buildStep(day, fill, startMin, endMin, leg,
                    buildAlternatives(fill, dayCentroid, spareIndices, usedIndices, candidates,
                            transportPref, dayAltKeys)));
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

    /**
     * 갭 보충 후보: 주간 적합 관광지/카페 중 인기도에서 우회 비용을 뺀 점수가 가장 높은 spare.
     *
     * @param radiusMultiplier 반경 완화 배수. 공백이 길 때만 1보다 큰 값을 준다.
     * @param budgetDay        거리 예산을 검사할 day. {@code null}이면 검사하지 않는다.
     *                         반경을 완화할 때 넘기지 않으면 하루 주행 상한을 깨고 다음 패스에 트림되는 churn이 생긴다.
     * @param maxExtraDetourKm 경로에서 벗어나도 되는 추가 거리(km). 0 이하면 제한 없음.
     *                         반경만 완화하면 공백 대신 긴 운전이 되어 함께 쓴다.
     * @param cafeOnly         카페만 허용(relaxed 페이스에서 pace 상한을 넘겨 채울 때).
     */
    private Integer pickGapFiller(PlaceCandidate prev, PlaceCandidate mealCand,
                                  List<PlaceCandidate> candidates, Deque<Integer> spareIndices,
                                  Set<Integer> usedIndices, String transportPref,
                                  double radiusMultiplier, DayState budgetDay,
                                  double maxExtraDetourKm, ScheduleConfig cfg, boolean cafeOnly,
                                  java.time.DayOfWeek dow) {
        double[] from = prev != null ? coordsOf(prev) : null;
        double[] to = coordsOf(mealCand);
        double radius = altRadiusKm(transportPref) * radiusMultiplier;
        // 우회 상한의 기준선은 현위치에서 식당까지의 직선거리다.
        double directKm = (from != null && to != null) ? haversine(from, to) : 0.0;
        return spareIndices.stream()
                .filter(i -> !usedIndices.contains(i))
                .map(i -> byIndex(candidates, i))
                .filter(Objects::nonNull)
                .filter(c -> {
                    // 카페형에는 식당으로 적재된 베이커리·디저트도 든다(isCafeLike).
                    if (cafeOnly) return isCafeLike(c);
                    return "ATTRACTION".equals(PlaceCategoryConstants.majorCategory(c.category())) || isCafeLike(c);
                })
                // 역할·구역·경유·집합POI·여객시설·긴 등산을 다른 보충 경로와 같은 공용 게이트로 본다.
                .filter(this::isVisitableFill)
                .filter(c -> dow == null || !isClosedOnDay(c.openingHours(), dow))
                .filter(c -> timePreference(c) != TimePreference.EVENING)
                .filter(c -> coordsOf(c) != null)
                .filter(c -> from == null || haversine(from, coordsOf(c)) <= radius)
                .filter(c -> budgetDay == null
                        || !wouldExceedDayBudget(budgetDay, c, candidates, transportPref, cfg))
                .filter(c -> maxExtraDetourKm <= 0
                        || detourVia(from, coordsOf(c), to) - directKm <= maxExtraDetourKm)
                // 우회거리만 보면 가장 가까운 무명 spare가 이긴다. 반경·예산·우회 상한이 이미 지리를 제한해 그 안에서는 인기도가 정한다.
                .max(Comparator.<PlaceCandidate>comparingDouble(c ->
                                PlaceProminence.of(c) - detourVia(from, coordsOf(c), to) * GAP_FILL_DETOUR_WEIGHT)
                        .thenComparing(c -> c.rating() != null ? c.rating() : BigDecimal.ZERO))
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
     * 절대 거리 상한 강제. 단일 구간이 legCap을 넘거나 일일 총주행이 dayCap을 넘으면 우회 기여가 가장 큰 장소를
     * 같은 대분류의 가까운 spare로 교체하고, 불가하면 제거한다(그날 유일한 식사는 제거하지 않음).
     * 상대 임계값(repairDistanceOutliers)은 흩어진 day일수록 허용치가 커져 무력해진다.
     */
    private List<Integer> enforceDistanceBudget(DayState day, List<Integer> ordered,
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
     * 확정 시퀀스가 거리 예산(단일 구간/일일 총주행)을 넘으면 보호 대상이 아닌 활동 중 위반을 줄이는 것을 spare로 트림한다.
     * usedIndices에서는 빼지 않아야 같은 실행의 갭 필·대안이 멀어서 뺀 장소를 다시 끌어오지 않는다.
     *
     * @param pinnedMeals 아침·점심·저녁으로 확정된 인덱스. 교체하면 새 장소가 끼니 시각 고정을 잃어
     *                    최후 수단 교체에서도 건드리지 않는다.
     */
    private void trimSequenceOverBudget(DayState day, List<Integer> seq, List<PlaceCandidate> candidates,
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
                double d = haversine(a, b);
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
    private Integer mostIconicInSequence(List<Integer> seq, List<PlaceCandidate> candidates) {
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
    private boolean replaceFarthestWithNearbySpare(DayState day, List<Integer> seq,
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
        Integer replacement = pickCloserSameMajorSpare(seq, candidates, spareIndices, targetCand, prev, next);
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
    private Integer pickCloserSameMajorSpare(List<Integer> seq, List<PlaceCandidate> candidates,
                                             Deque<Integer> spareIndices, PlaceCandidate target,
                                             double[] prev, double[] next) {
        double currentDetour = detourVia(prev, coordsOf(target), next);
        Set<Integer> inSeq = new HashSet<>(seq);
        return spareIndices.stream()
                .map(i -> byIndex(candidates, i))
                .filter(Objects::nonNull)
                .filter(c -> !inSeq.contains(c.index()))
                .filter(c -> isSubstituteFor(target, c))
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
            double d = haversine(a, b);
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
    private int violationReducingRemoval(List<double[]> coords, int offset, List<Integer> seq,
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
    private double maxLegWithout(List<double[]> coords, int skip) {
        double worst = 0;
        double[] prev = null;
        for (int i = 0; i < coords.size(); i++) {
            if (i == skip) continue;
            double[] cur = coords.get(i);
            if (cur == null) continue;
            if (prev != null) worst = Math.max(worst, haversine(prev, cur));
            prev = cur;
        }
        return worst;
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
        double[] prev = pos > 0 ? coordsOf(byIndex(candidates, order.get(pos - 1))) : null;
        double[] next = pos < order.size() - 1 ? coordsOf(byIndex(candidates, order.get(pos + 1))) : null;
        if (prev == null && next == null) return null;
        double currentDetour = detourVia(prev, coordsOf(cur), next);

        return spare.stream()
                .filter(i -> !usedIndices.contains(i))
                .map(i -> byIndex(candidates, i))
                .filter(Objects::nonNull)
                .filter(c -> isSubstituteFor(cur, c))
                .filter(c -> coordsOf(c) != null)
                .filter(c -> detourVia(prev, coordsOf(c), next) < currentDetour * 0.5)
                .min(Comparator.comparingDouble(c -> detourVia(prev, coordsOf(c), next)))
                .map(PlaceCandidate::index)
                .orElse(null);
    }

    /** 그날의 유일한 식사(DINING)는 거리 때문에 제거하지 않는다. */
    private boolean isRemovableForBudget(Integer idx, List<Integer> order, List<PlaceCandidate> candidates) {
        PlaceCandidate c = byIndex(candidates, idx);
        if (c == null || !c.mealEligible()) return true;
        long dining = order.stream()
                .map(i -> byIndex(candidates, i))
                .filter(x -> x != null && x.mealEligible())
                .count();
        return dining > 1;
    }

    private void replaceInDay(DayState day, Integer from, Integer to) {
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

    /**
     * {@code c}가 {@code target} 자리를 대신할 수 있는지. 같은 역할(식사↔식사, 카페↔카페, 관광 계열끼리, 숙소↔숙소)이면서
     * 방문 가능해야 한다. 교체·대안·휴무 회피·거리 교체가 모두 이 기준을 쓴다.
     */
    private boolean isSubstituteFor(PlaceCandidate target, PlaceCandidate c) {
        if (target == null || c == null || !CandidatePolicy.sameRole(target, c)) return false;
        if (CandidatePolicy.roleOf(c) == CandidatePolicy.Role.LODGING) {
            return PlaceCategoryConstants.isVisitableEntity(c.name(), c.address(), c.country());
        }
        return isVisitableFill(c) && (!target.mealEligible() || c.mealEligible());
    }

    /** 끼니(DINING)든 카페·베이커리(CAFE)든 먹는 스텝이 연달아 붙으면 양쪽 다 망친다. */
    private boolean isFoodCand(PlaceCandidate c) {
        if (c == null) return false;
        String major = PlaceCategoryConstants.majorCategory(c.category());
        return "DINING".equals(major) || "CAFE".equals(major);
    }

    /**
     * 활동 버킷에서 먹는 스텝이 연달아 붙지 않게 자리를 바꾼다.
     *
     * <p>버킷 안에서 먹는 스텝이 인접한 경우와, 버킷 마지막이 먹는 스텝이고 바로 뒤가 끼니인 경우를 같은 규칙으로 본다.
     * 버킷 순서가 경로순이라 가까운 가게끼리 붙고, 끼니가 {@code LUNCH_START}/{@code DINNER_START}에 핀되며 간격이 사라진다.
     *
     * <p>뒤쪽의 먹는 스텝을 더 뒤의 먹지 않는 활동과 맞바꾸고, 상대가 없으면 스텝을 버리지 않고 둔다.
     *
     * @param mealFollows 이 버킷 바로 뒤에 끼니 슬롯이 오는지. 그러면 마지막 자리도 경계다
     */
    private void separateFoodSteps(DayState day, List<Integer> bucket, List<PlaceCandidate> candidates,
                                   boolean mealFollows, String mealLabel) {
        if (bucket == null || bucket.size() < 2) return;

        int last = bucket.size() - 1;

        // ① 끼니 경계를 먼저 비우고 그 자리는 ②의 교환 대상에서 뺀다. 두 패스가 서로를 되돌리면 같은 쌍을 무한히 맞바꾼다.
        int forbidden = -1;
        if (mealFollows) {
            PlaceCandidate tail = byIndex(candidates, bucket.get(last));
            if (isFoodCand(tail)
                    && swapWithLaterActivity(day, bucket, candidates, last, tail, mealLabel + " 직전", -1)) {
                forbidden = last;
            }
        }

        // ② 버킷 안의 인접 쌍. 경계에서 뺀 먹는 스텝이 다른 먹는 스텝 옆으로 갈 수 있어 경계만으로는 부족하다.
        for (int i = 1; i < bucket.size(); i++) {
            if (i == forbidden) continue;
            PlaceCandidate curr = byIndex(candidates, bucket.get(i));
            if (!isFoodCand(curr) || !isFoodCand(byIndex(candidates, bucket.get(i - 1)))) continue;
            swapWithLaterActivity(day, bucket, candidates, i, curr, "직전 스텝도 먹는 곳", forbidden);
        }
    }

    /**
     * {@code pos}의 먹는 스텝을 뒤쪽(없으면 앞쪽)의 먹지 않는 활동과 맞바꾼다.
     * 숙소·허브는 상대가 아니다({@link #isSwappableActivity}).
     *
     * @param forbidden 교환 대상에서 뺄 자리(없으면 -1). 이미 비워 둔 끼니 경계를 다시 먹는 스텝으로 채우지 않는다.
     */
    private boolean swapWithLaterActivity(DayState day, List<Integer> bucket,
                                          List<PlaceCandidate> candidates, int pos,
                                          PlaceCandidate moving, String reason, int forbidden) {
        for (int j = bucket.size() - 1; j >= 0; j--) {
            if (j == pos || j == forbidden) continue;
            PlaceCandidate c = byIndex(candidates, bucket.get(j));
            if (c == null || isFoodCand(c) || !isSwappableActivity(day, bucket.get(j), c)) continue;
            Collections.swap(bucket, pos, j);
            log.info("먹는 스텝 분리: day={} {} ({}) ↔ {}",
                    day.dayNumber, moving.name(), reason, c.name());
            return true;
        }
        return false;
    }

    /**
     * 버킷 안에서 자리를 바꿔도 되는 활동인지. 숙소·허브는 제외한다.
     *
     * <p>도보 연속성 보정이 전날 숙소를 활동 버킷에 넣을 수 있어, 거르지 않으면 숙소가 끼니 직전으로 끌려가
     * 체크인 뒤 장거리 저녁이 된다.
     */
    private boolean isSwappableActivity(DayState day, Integer idx, PlaceCandidate c) {
        if (isSameIndex(day.accommodationIndex, idx)
                || isSameIndex(day.arrivalHubIndex, idx)
                || isSameIndex(day.departureHubIndex, idx)) {
            return false;
        }
        String major = PlaceCategoryConstants.majorCategory(c.category());
        return !"LODGING".equals(major) && !"TRANSIT_HUB".equals(major);
    }

    /** 첫날 도착·마지막 날 출발을 반영한 그날의 시작 시각과 저녁 상한. */
    private static ScheduleConfig cfgFor(DayState day, ScheduleConfig cfg) {
        int start = day.startNotBefore != null
                ? Math.max(EARLIEST_DAY_START_MINUTES, day.startNotBefore) : cfg.morningStart;
        // 새벽 출발이면 기본 시작 시각으로는 마감을 지킬 수 없다
        if (day.startNotBefore == null && day.departureDeadline != null
                && day.departureDeadline - EARLY_DEPARTURE_LEAD_MINUTES < start) {
            start = Math.max(EARLIEST_DAY_START_MINUTES, day.departureDeadline - EARLY_DEPARTURE_LEAD_MINUTES);
        }
        int cap = day.departureDeadline != null ? Math.min(cfg.eveningCap, day.departureDeadline) : cfg.eveningCap;
        return cfg.withWindow(start, Math.max(start, cap));
    }

    /**
     * 숙소에서 출발 허브까지가 출발 기한 앞 여유보다 길면 그만큼 일찍 시작한다. 고정 여유
     * ({@link ScheduleTuning#EARLY_DEPARTURE_LEAD_MINUTES})만 두면 먼 숙소에서는 기한을 넘긴다.
     */
    private ScheduleConfig withDepartureLegLead(DayState day, ScheduleConfig cfg, List<PlaceCandidate> candidates,
                                                PlaceCandidate startFrom, String transportPref) {
        if (day.startNotBefore != null || day.departureDeadline == null || day.departureHubIndex == null
                || startFrom == null) {
            return cfg;
        }
        TransportLeg leg = computeLeg(startFrom, byIndex(candidates, day.departureHubIndex), transportPref,
                day.dayNumber);
        if (leg == null) return cfg;
        int latestStart = day.departureDeadline - leg.durationMin();
        if (cfg.morningStart <= latestStart) return cfg;
        int start = Math.max(EARLIEST_DAY_START_MINUTES, latestStart);
        return cfg.withWindow(start, Math.max(start, cfg.eveningCap));
    }

    private static int departureDeadline(DayState day) {
        return day.departureDeadline != null
                ? Math.min(day.departureDeadline, DEPARTURE_HUB_LATEST_MINUTES) : DEPARTURE_HUB_LATEST_MINUTES;
    }

    private boolean isDiningCand(PlaceCandidate c) {
        return c != null && "DINING".equals(PlaceCategoryConstants.majorCategory(c.category()));
    }

    /**
     * 하루 식사 3회 상한. 초과분은 spare로 트림하되 userSelected 식당을 우선 남긴다.
     * 슬롯 매핑이 경로순 기반이라 남긴 목록은 경로 순서를 유지한다.
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
     * 3식일 때 숙소에서 {@link #BREAKFAST_MAX_KM_FROM_ACCOMMODATION}km를 넘는 아침은 spare로 돌려 아침부터 원정을 막는다.
     * 사용자 필수 식당은 버리지 않는다. 슬롯 확정 전이라 경로상 첫 식사를 아침 후보로 본다.
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
     * 확정된 아침이 성립하는지. 출발지(전날 숙소, 없으면 그날 숙소)에서
     * {@value ScheduleTuning#BREAKFAST_MAX_KM_FROM_ACCOMMODATION}km 이내이고, 식사가 끝난 뒤
     * 점심까지 {@value ScheduleTuning#BREAKFAST_MIN_GAP_BEFORE_LUNCH_MINUTES}분 이상 남아야 한다.
     */
    private boolean isReasonableBreakfast(DayState day, PlaceCandidate breakfast, PlaceCandidate startFrom,
                                          List<PlaceCandidate> candidates, String transportPref,
                                          int morningStart) {
        if (breakfast == null || breakfast.userSelected()) return true;
        double[] origin = startFrom != null ? coordsOf(startFrom) : accommodationCoords(day, candidates);
        double[] coord = coordsOf(breakfast);
        if (origin == null || coord == null) return true;
        if (haversine(origin, coord) > BREAKFAST_MAX_KM_FROM_ACCOMMODATION) return false;
        TransportLeg leg = computeLeg(startFrom, breakfast, transportPref, day.dayNumber);
        int end = morningStart + (leg != null ? leg.durationMin() : 0) + DINING_VISIT_MINUTES;
        return end <= LUNCH_START - BREAKFAST_MIN_GAP_BEFORE_LUNCH_MINUTES;
    }

    /**
     * 확정된 활동 버킷을 기준으로 식사를 아침/점심/저녁 슬롯에 배정한다.
     * <ul>
     *   <li><b>저녁</b>: 그날 숙소에 가장 가까운 식당. 식후 장거리 귀가를 구조적으로 막는다</li>
     *   <li><b>점심</b>: 오전 마지막 활동 → 식당 → 오후 첫 활동 우회가 가장 작은 식당.
     *       버킷이 비어 있으면 하루 시작점/그날 중심을 앵커로 쓴다</li>
     *   <li><b>아침</b>: 남은 하나</li>
     * </ul>
     * 사용자 필수 식당(userSelected)은 재배치 대상에서 제외한다(경로 순서 유지).
     */
    MealSlots assignMealSlots(List<Integer> meals, DayState day, List<PlaceCandidate> candidates,
                              List<Integer> morning, List<Integer> afternoon,
                              PlaceCandidate startFrom, double[] dayCentroid,
                              Deque<Integer> spareIndices, int breakfastStart) {
        if (meals.isEmpty()) return new MealSlots(null, null, null);

        java.time.DayOfWeek dow = day.dayOfWeek;
        List<Integer> remaining = new ArrayList<>(meals);

        // 슬롯별로 그 시각에 영업하는 후보만 남겨, 늦게 여는 식당은 쫓아내지 않고도 저녁 자리를 찾는다.
        List<Integer> lunchCapable = openAtSlot(remaining, candidates, dow, LUNCH_START);
        List<Integer> dinnerPool = openAtSlot(remaining, candidates, dow, DINNER_START);
        // 점심 가능한 식당이 하나뿐이면 저녁으로 가져갈 때 점심이 비어 저녁 후보에서 뺀다.
        if (lunchCapable.size() == 1 && dinnerPool.size() > 1) dinnerPool.remove(lunchCapable.get(0));

        // ── 저녁: 숙소에 가장 가까운 식당(식후 장거리 귀가를 구조적으로 차단) ──
        Integer dinner = null;
        if (day.dinnerInWindow() && remaining.size() > 1 && !dinnerPool.isEmpty()) {
            double[] hotel = accommodationCoords(day, candidates);
            dinner = hotel != null
                    ? nearestMeal(dinnerPool, candidates, hotel)
                    : dinnerPool.get(dinnerPool.size() - 1);
            remaining.remove(dinner);
        }

        // ── 점심: 오전 마지막 활동 → 식당 → 오후 첫 활동 우회가 가장 작은 식당 ──
        Integer lunch = null;
        List<Integer> lunchPool = day.lunchInWindow() ? openAtSlot(remaining, candidates, dow, LUNCH_START) : List.of();
        if (!lunchPool.isEmpty()) {
            double[] from = lastCoord(morning, candidates);
            if (from == null) from = coordsOf(startFrom);
            if (from == null) from = dayCentroid;
            double[] to = firstCoord(afternoon, candidates);
            // 영업시간에 맞는 식당이 없으면 저녁은 null로 남고 ensureMealSlots가 채우므로 앵커로 쓸 때 null을 확인한다.
            if (to == null && dinner != null) to = coordsOf(byIndex(candidates, dinner));
            if (to == null) to = dayCentroid;

            lunch = minDetourMeal(lunchPool, candidates, from, to);
            remaining.remove(lunch);
        }

        // 한 끼뿐이라 위에서 저녁을 건너뛰었는데 그 한 끼가 점심에 닫혀 있으면, 저녁으로 돌린다.
        if (dinner == null && day.dinnerInWindow() && !remaining.isEmpty()) {
            List<Integer> late = openAtSlot(remaining, candidates, dow, DINNER_START);
            if (!late.isEmpty()) {
                double[] hotel = accommodationCoords(day, candidates);
                dinner = hotel != null ? nearestMeal(late, candidates, hotel) : late.get(late.size() - 1);
                remaining.remove(dinner);
            }
        }

        // ── 아침: 남은 하나. 그 시각에 닫혀 있으면 두지 않는다 ──
        Integer breakfast = null;
        for (Integer idx : new ArrayList<>(remaining)) {
            if (breakfast != null) break;
            if (isOpenDuring(byIndex(candidates, idx), dow, breakfastStart,
                    breakfastStart + DINING_VISIT_MINUTES)) {
                breakfast = idx;
                remaining.remove(idx);
            }
        }

        // 어느 슬롯에도 못 들어간 식당을 두면 placeIndices에만 남는 유령이 된다.
        // spare로 돌리면 ensureMealSlots가 그 시각에 열린 식당을 찾고, 못 찾으면 안내가 나간다.
        for (Integer idx : remaining) {
            day.placeIndices.remove(Integer.valueOf(idx));
            spareIndices.addLast(idx);
            PlaceCandidate c = byIndex(candidates, idx);
            log.info("식사 영업시간 불일치 — spare 전환: day={}({}) {} [{}]", day.dayNumber, dow,
                    c != null ? c.name() : idx, c != null ? c.openingHours() : "?");
        }
        return new MealSlots(breakfast, lunch, dinner);
    }

    /**
     * 그 시각에 식사({@value ScheduleTuning#DINING_VISIT_MINUTES}분)가 가능한 후보만 남긴다.
     * 요일을 모르거나 영업시간을 판독할 수 없으면 전부 통과한다(열림 가정).
     */
    private List<Integer> openAtSlot(List<Integer> meals, List<PlaceCandidate> candidates,
                                     java.time.DayOfWeek dow, int slotStart) {
        return meals.stream()
                .filter(m -> isOpenDuring(byIndex(candidates, m), dow, slotStart,
                        slotStart + DINING_VISIT_MINUTES))
                .collect(Collectors.toList());
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
            if (c != null && c.userSelected()) continue;
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
            // 도보 보정으로 활동 버킷에 섞인 그날 숙소를 저녁으로 옮기면 밤 체크인과 겹쳐 같은 호텔이 두 스텝이 된다.
            if (isSameIndex(day.accommodationIndex, idx)) continue;
            // 야외/주간 장소를 저녁으로 옮기면 버킷 배분의 시간대 가드를 우회한다.
            TimePreference pref = timePreference(byIndex(candidates, idx));
            if (pref == TimePreference.DAYTIME || pref == TimePreference.MORNING_ONLY) continue;
            // 이 이동은 버킷 배분 뒤라 여기서 보지 않으면 영업시간 판정을 우회해 저녁에 닫힌 곳이 스텝이 된다.
            PlaceCandidate moving = byIndex(candidates, idx);
            int eveningStart = DINNER_START + DINING_VISIT_MINUTES;
            if (!isOpenDuring(moving, day.dayOfWeek, eveningStart,
                    eveningStart + resolveVisitMinutes(moving))) continue;
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
     * <p>활동 1개를 {@value ScheduleTuning#ACTIVITY_SLOT_MINUTES}분으로 고정해 개수로 세면 긴 체류 장소도 1칸만 차지해
     * 버킷은 안 넘치는데 시계가 밀린다. 실제 체류시간으로 채우도록 분을 그대로 돌려준다.
     */
    private int[] bucketBudgets(ScheduleConfig cfg, boolean hasLunch, boolean hasDinner, boolean hasBreakfast) {
        int densityMinutes = cfg.densityDelta * ACTIVITY_SLOT_MINUTES;
        int morningStart = cfg.morningStart + (hasBreakfast ? DINING_VISIT_MINUTES : 0);
        // 점심·저녁은 시간대 시작에 고정되지만, 그보다 늦게 하루가 시작하면 그 뒤로 밀린다
        int lunchEnd = Math.max(LUNCH_START, morningStart) + DINING_VISIT_MINUTES;
        int dinnerEnd = Math.max(DINNER_START, morningStart) + DINING_VISIT_MINUTES;
        if (hasLunch && hasDinner) {
            int budgetM = Math.max(0, LUNCH_START - morningStart);
            int budgetA = Math.max(ACTIVITY_SLOT_MINUTES, DINNER_START - lunchEnd + densityMinutes);
            int budgetE = Math.max(0, cfg.eveningCap - dinnerEnd);
            return new int[]{budgetM, budgetA, budgetE};
        } else if (hasLunch) { // 저녁 없음 → 오후가 상한까지 확장
            int budgetM = Math.max(0, LUNCH_START - morningStart);
            int budgetA = Math.max(ACTIVITY_SLOT_MINUTES, cfg.eveningCap - lunchEnd + densityMinutes);
            return new int[]{budgetM, budgetA, 0};
        } else if (hasDinner) { // 점심 없이 저녁만(오후 도착) → 저녁 전까지가 오후
            int budgetA = Math.max(0, DINNER_START - morningStart + densityMinutes);
            int budgetE = Math.max(0, cfg.eveningCap - dinnerEnd);
            return new int[]{0, budgetA, budgetE};
        } else { // 식사 없음 → 하루 전체를 한 버킷으로
            int budgetAll = Math.max(ACTIVITY_SLOT_MINUTES, cfg.eveningCap - morningStart + densityMinutes);
            return new int[]{budgetAll, 0, 0};
        }
    }

    /**
     * 도착 허브 체류 + 허브에서 그날 첫 장소까지 이동. 도착이 늦은 날은 이 시간이 예산에서 바로 빠진다
     * (제주공항 → 서귀포처럼 한 시간 이동이면 오후 예산의 절반이 사라진다).
     */
    private int arrivalLeadMinutes(DayState day, List<Integer> orderedMain, List<PlaceCandidate> candidates,
                                   String transportPref) {
        if (day.startNotBefore == null || day.arrivalHubIndex == null) return 0;
        PlaceCandidate hub = byIndex(candidates, day.arrivalHubIndex);
        PlaceCandidate first = orderedMain.stream()
                .filter(idx -> !isSameIndex(day.arrivalHubIndex, idx))
                .map(idx -> byIndex(candidates, idx))
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
        TransportLeg leg = hub != null && first != null ? computeLeg(hub, first, transportPref, day.dayNumber) : null;
        return ARRIVAL_HUB_MINUTES + (leg != null ? leg.durationMin() : 0);
    }

    /**
     * 그날 쓸 수 있는 끼니로 예산을 잡는다. 사용자 시간창이 없으면 끼니 수만 본다.
     * 시간창이 있으면 범위 밖 끼니를 세지 않고, 도착 허브에서 빠져나오는 시간을 앞쪽 버킷에서 뺀다.
     */
    private int[] dayBudgets(DayState day, ScheduleConfig cfg, int mealCount, int arrivalLeadMinutes) {
        if (!day.hasUserWindow()) {
            return bucketBudgets(cfg, mealCount >= 1, mealCount >= 2, mealCount >= 3);
        }
        boolean lunch = day.lunchInWindow() && mealCount >= 1;
        boolean dinner = day.dinnerInWindow() && mealCount >= (lunch ? 2 : 1);
        int[] budgets = bucketBudgets(cfg, lunch, dinner, mealCount >= 3);
        if (day.startNotBefore != null && day.arrivalHubIndex != null) {
            int left = arrivalLeadMinutes;
            for (int i = 0; i < budgets.length && left > 0; i++) {
                int used = Math.min(budgets[i], left);
                budgets[i] -= used;
                left -= used;
            }
        }
        return budgets;
    }

    /**
     * 세부 유형 반복 상한. 같은 세부 유형이 전체 일정에서 {@link #typeCapFor} 상한을 넘으면 초과분을 spare로 보내고
     * 쓰이지 않은 다른 유형의 spare로 보충한다.
     *
     * <p>경유형(해안도로·드라이브코스·둘레길)은 리프가 총칭이라 유형 상한에 걸리지 않지만 지나가는 구간이라
     * 반복되면 일정이 무의미해져 전체 {@code VIA_ROUTE_CAP}개로 제한한다.
     *
     * <p>사용자 필수·pair 장소는 상한 계산에는 포함하되 빼지 않는다. 상한을 넘는 유형 안에서는 <b>인기도가 낮은 쪽부터</b> 뺀다.
     * 날짜순으로 세면 뒷날의 대표 명소가 잘린다.
     */
    void enforceTypeDiversity(List<DayState> days, List<PlaceCandidate> candidates, Deque<Integer> spare,
                              List<List<Integer>> pairs) {
        Set<Integer> pairedIndices = pairs == null ? Set.of() : pairs.stream()
                .filter(p -> p != null && p.size() == 2)
                .flatMap(List::stream)
                .collect(Collectors.toSet());

        // 총칭 리프는 이름·태그로 세부 유형을 추론해 상한 키로 쓴다. 추론이 없으면 오름 3연속이 같은 유형으로 잡히지 않는다.
        Map<String, List<Integer>> byType = new LinkedHashMap<>();
        List<Integer> vias = new ArrayList<>();
        for (DayState day : days) {
            for (Integer idx : day.placeIndices) {
                PlaceCandidate c = byIndex(candidates, idx);
                if (c == null) continue;
                String type = subTypeOf(c);
                if (isSpecificType(type)) byType.computeIfAbsent(type, k -> new ArrayList<>()).add(idx);
                if (PlaceCategoryConstants.isViaRoute(c.attributes(), c.name(), c.category(), c.tags())) vias.add(idx);
            }
        }
        Map<Integer, String> trimReason = new LinkedHashMap<>();
        byType.forEach((type, members) -> {
            int cap = typeCapFor(byIndex(candidates, members.get(0)), days.size());
            selectOverCap(members, cap, candidates, pairedIndices)
                    .forEach(i -> trimReason.putIfAbsent(i, type + " " + cap + "회 초과"));
        });
        selectOverCap(vias, VIA_ROUTE_CAP, candidates, pairedIndices)
                .forEach(i -> trimReason.putIfAbsent(i, "경유형 상한 " + VIA_ROUTE_CAP));
        if (trimReason.isEmpty()) return;

        Map<String, Integer> typeCount = new HashMap<>();
        for (DayState day : days) {
            for (Integer idx : day.placeIndices) {
                if (!trimReason.containsKey(idx)) typeCount.merge(subTypeOf(byIndex(candidates, idx)), 1, Integer::sum);
            }
        }
        for (DayState day : days) {
            for (Integer idx : new ArrayList<>(day.placeIndices)) {
                String reason = trimReason.get(idx);
                if (reason == null) continue;
                day.placeIndices.remove(idx);
                spare.addLast(idx);
                log.info("유형 다양성 상한 초과 — 트림: day={} {} ({})", day.dayNumber,
                        nameOf(candidates, idx), reason);

                Integer replacement = pickDiverseSpare(day, candidates, spare, typeCount, days.size());
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
     * 상한을 넘는 묶음에서 뺄 인덱스. 사용자 필수·pair 멤버는 상한 계산에는 포함하되 빼지 않고
     * (포함·pair 무결성이 우선), 나머지 중 인기도가 낮은 것부터 고른다.
     */
    private List<Integer> selectOverCap(List<Integer> members, int cap, List<PlaceCandidate> candidates,
                                        Set<Integer> pairedIndices) {
        if (members.size() <= cap) return List.of();
        List<Integer> removable = new ArrayList<>();
        int fixed = 0;
        for (Integer idx : members) {
            PlaceCandidate c = byIndex(candidates, idx);
            if (c != null && (c.keepsPlace() || pairedIndices.contains(idx))) fixed++;
            else removable.add(idx);
        }
        removable.sort(Comparator.<Integer>comparingDouble(i -> PlaceProminence.of(byIndex(candidates, i)))
                .reversed());
        int keep = Math.max(0, cap - fixed);
        return keep >= removable.size() ? List.of() : removable.subList(keep, removable.size());
    }

    /**
     * 세부 유형 반복 상한. 관광·카페는 {@code ceil(days/2) + TYPE_CAP_BASE}, 한 끼 식사는 {@code max(2, ceil(days/2))}다.
     * 식사는 선택 프롬프트의 "같은 음식 3끼 이상 금지"에 맞춰 더 좁게 잡는다.
     */
    private int typeCapFor(PlaceCandidate c, int dayCount) {
        int half = (int) Math.ceil(dayCount / 2.0);
        if (c != null && c.mealEligible()) return Math.max(2, half);
        return half + TYPE_CAP_BASE;
    }

    /**
     * 유형 상한을 적용할 만큼 구체적인 리프인지. "Tourist Attraction"/"Landmark"처럼 대분류와
     * 다름없는 총칭 리프는 제외한다(그 범주 안에 서로 다른 종류의 명소가 전부 들어오기 때문).
     */
    private boolean isSpecificType(String type) {
        return !PlaceCategoryConstants.isGenericLeaf(type);
    }

    /**
     * 유형 상한·시간대 버킷·대안 매칭이 공유하는 세부 유형 키. 총칭 리프는 이름·태그·설명으로 추론된 유형이 돌아온다.
     */
    private String subTypeOf(PlaceCandidate c) {
        if (c == null) return "";
        // 식사는 검색 다양화와 같은 종류 키(보강된 종류 우선)로 센다. 두 곳이 다르게 세면 상한이 엇나간다.
        if (c.mealEligible()) return CandidatePolicy.cuisineOf(c);
        String byAttribute = PlaceCategoryConstants.attributeSubTypeKey(c.attributes());
        if (byAttribute != null) return byAttribute;
        return PlaceCategoryConstants.subType(c.name(), c.category(), c.tags(), c.description());
    }

    /** 상한에 걸리지 않은 다른 유형의 spare 중, 그날 중심에서 가장 가까운 방문 가능 후보. */
    private Integer pickDiverseSpare(DayState day, List<PlaceCandidate> candidates, Deque<Integer> spare,
                                     Map<String, Integer> typeCount, int dayCount) {
        double[] centroid = centroidOf(day.placeIndices, candidates);
        Set<Integer> used = collectUsedIndicesIncludingSpareUse(day);
        return spare.stream()
                .map(i -> byIndex(candidates, i))
                .filter(Objects::nonNull)
                .filter(this::isVisitableFill)
                .filter(c -> !used.contains(c.index()))
                .filter(c -> !PlaceCategoryConstants.isViaRoute(c.attributes(), c.name(), c.category(), c.tags()))
                .filter(c -> typeCount.getOrDefault(subTypeOf(c), 0) < typeCapFor(c, dayCount))
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
     * 사용자 카테고리 중 일정에 한 번도 없는 것을 spare에서 찾아 quota 여유가 있는 day에 넣는다.
     * 없는 유형을 억지로 만들지 않고, 후보가 없으면 로그만 남긴다.
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
                    .max(Comparator.<PlaceCandidate>comparingDouble(PlaceProminence::of)
                            .thenComparing(c -> c.rating() != null ? c.rating() : BigDecimal.ZERO))
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
     * 출발일(귀가 허브가 있는 날)에는 저녁 식사도 저녁 시간도 없어 그날의 저녁 의도 장소를 전날로 옮긴다.
     * 두면 출발 시각 트림에 잘리거나, 남더라도 야경을 보지 못하고 떠난다.
     */
    private void moveEveningIntentOffDepartureDay(List<DayState> days, List<PlaceCandidate> candidates) {
        for (int i = 1; i < days.size(); i++) {
            DayState day = days.get(i);
            if (day.departureHubIndex == null) continue;
            DayState prev = days.get(i - 1);
            for (Integer idx : new ArrayList<>(day.placeIndices)) {
                PlaceCandidate c = byIndex(candidates, idx);
                if (c == null || c.userSelected() || !"evening".equals(c.intentTime())) continue;
                day.placeIndices.remove(idx);
                prev.placeIndices.add(idx);
                log.info("저녁 의도 장소를 출발일에서 전날로 이동: day={} → day={} {}",
                        day.dayNumber, prev.dayNumber, c.name());
            }
        }
    }

    /**
     * 하루 일과 용량(테마 반영 시간창)을 넘는 활동을 quota 여유가 있는 다음/이전 day로 이월한다.
     * 옮길 곳이 없으면 그대로 두고 scheduleDay가 저녁 상한에서 트림한다.
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
    private int totalActivityCapacity(DayState day, List<PlaceCandidate> candidates, ScheduleConfig cfg) {
        cfg = cfgFor(day, cfg);
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

    /**
     * 테마 기반 하루 시간창 설정. 고른 테마 전부를 {@link #THEME_SCHEDULES}에서 찾아
     * "가장 이른 시작 · 가장 늦은 상한 · 밀도 보정 합"으로 병합한다. 미지의 테마는 무시하고,
     * 아무 테마도 매칭되지 않으면 기본값(09:00~22:00)을 쓴다.
     */
    ScheduleConfig scheduleConfig(List<String> themes, String pace) {
        if (themes == null || themes.isEmpty()) {
            return new ScheduleConfig(DAY_START_MINUTES, DEFAULT_EVENING_CAP_MINUTES, 0, false, pace);
        }

        int morningStart = Integer.MAX_VALUE;
        int eveningCap = Integer.MIN_VALUE;
        int densityDelta = 0;
        boolean indoorPreferred = false;
        boolean matched = false;
        List<String> unmatched = new ArrayList<>();

        for (String theme : themes) {
            if (theme == null || theme.isBlank()) continue;
            ThemeSchedule ts = THEME_SCHEDULES.get(theme.trim().toLowerCase());
            if (ts == null) {
                unmatched.add(theme.trim());
                continue;
            }
            matched = true;
            morningStart = Math.min(morningStart, ts.morningStart());
            eveningCap = Math.max(eveningCap, ts.eveningCap());
            densityDelta += ts.densityDelta();
            indoorPreferred |= ts.indoorPreferred();
        }

        // 조용히 기본값으로 떨어지면 테마 시간창 누락을 아무도 모른다.
        // 슬러그가 생성 요청 계약(WizardOptionIds)이라 한글 별칭을 두면 같은 테마의 어휘가 두 벌이 된다.
        if (!unmatched.isEmpty()) {
            log.warn("테마 시간창 미매칭 {} — 기본 시간창으로 처리한다 (THEME_SCHEDULES 키는 영문 슬러그)",
                    unmatched);
        }

        if (!matched) {
            return new ScheduleConfig(DAY_START_MINUTES, DEFAULT_EVENING_CAP_MINUTES, 0, false, pace);
        }
        // 여러 테마를 고르면 밀도 보정이 합산돼 극단으로 갈 수 있어 ±1로 묶는다.
        densityDelta = Math.max(-1, Math.min(1, densityDelta));
        return new ScheduleConfig(
                Math.max(MIN_MORNING_START_MINUTES, morningStart),
                Math.min(MAX_EVENING_CAP_MINUTES, eveningCap),
                densityDelta, indoorPreferred, pace);
    }


    /**
     * 메인 스텝의 대안 3개를 만든다. "같은 종류·주변·중복 없이" 원칙:
     * <ul>
     *   <li><b>같은 종류</b>: 같은 역할 필수 + 세부 카테고리/tags 겹침 가점(버거집↔맥주바 방지).</li>
     *   <li><b>주변</b>: 메인 좌표(없으면 그날 centroid) 기준 이동수단별 반경 내만.</li>
     *   <li><b>중복 없이</b>: 이미 일정에 쓰인 장소 제외 + placeId/좌표 키로 대안끼리 dedup.</li>
     * </ul>
     * Sonnet이 큐레이션한 spare를 가점으로 우대하고, 부족하면 미사용 후보에서 채운다.
     */
    private List<AlternativeData> buildAlternatives(PlaceCandidate main, double[] dayCentroid,
                                                    Deque<Integer> spareIndices, Set<Integer> usedIndices,
                                                    List<PlaceCandidate> allCandidates, String transportPref,
                                                    Set<String> dayAltKeys) {
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

        // 3개 미만이면 반경×2까지만 완화하고, 그래도 못 채우면 대안 1~2개로 둔다. 무제한 폴백은 먼 식당을 대안으로 낸다.
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
                            c.country(), null, estimateCost(c), c.placeId(), c.nameKo()));
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
        // spare 가점이 거리 항을 압도하면 스텝이 달라도 대안 순위가 같아진다. 30km 거리 차이에는 뒤집히는 크기로 둔다.
        if (spareSet.contains(c.index())) score += SPARE_CURATION_BONUS;
        score += subCategorySimilarity(c, main) * 20;
        // 평점 원값(0~5)을 쓰면 리뷰 2건의 5.0이 리뷰 1만건의 4.3을 이긴다. 인기도(0~1)에
        // 같은 영향력(×5)을 줘 "실제로 가는 곳"이 대안 위로 오게 한다.
        score += PlaceProminence.of(c) * 5;
        // 같은 체인점은 다른 선택지가 아니다.
        if (isSameChain(c.name(), main.name())) score -= SAME_CHAIN_PENALTY;
        double[] cc = coordsOf(c);
        if (anchor != null && cc != null) score -= haversine(anchor, cc) * 0.5;
        return score;
    }

    /**
     * 두 상호가 같은 체인인지. 지점명을 떼고 남은 몸통이 한쪽을 포함하는지로 판정한다.
     * "고기굽는남자 서면점"과 "고기굽는남자"는 사용자에게 같은 가게다.
     */
    private boolean isSameChain(String a, String b) {
        if (a == null || b == null) return false;
        String na = stripBranchSuffix(a);
        String nb = stripBranchSuffix(b);
        if (na.isEmpty() || nb.isEmpty()) return false;
        return na.equals(nb) || na.contains(nb) || nb.contains(na);
    }

    /** 지점 표기를 떼어 체인 몸통만 남긴다("흑본오겹 함덕점", "... 2호점", "星巴克(人民广场店)", "一蘭 渋谷店"). */
    static String stripBranchSuffix(String name) {
        String n = name.strip().replaceAll("\\s*[(（][^()（）]*[店점][)）]$", "");
        String[] tokens = n.split("\\s+");
        if (tokens.length > 1 && BRANCH_TOKEN.matcher(tokens[tokens.length - 1]).matches()) {
            return String.join("", java.util.Arrays.copyOf(tokens, tokens.length - 1));
        }
        // 공백 없이 붙은 "흑본오겹함덕점"은 몸통 경계를 알 수 없어 그대로 둔다. 몸통만 있는 쪽과는 포함 관계로 맞는다.
        return String.join("", tokens);
    }

    private static final java.util.regex.Pattern BRANCH_TOKEN =
            java.util.regex.Pattern.compile(".*(본점|지점|[0-9]+호점|점|店)");

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
            case "LODGING" -> BigDecimal.valueOf(estimateLodgingCost(c, pl));
            default -> BigDecimal.ZERO; // TRANSIT_HUB/OTHER는 스텝 비용 개념 없음
        };
    }

    /** 숙소 1박 추정가: priceLevel 우선, 없으면 유형(hostel/resort/그 외)별 기본값. 유형은 보강 속성 우선. */
    private long estimateLodgingCost(PlaceCandidate c, Integer priceLevel) {
        if (priceLevel != null) return priceLevel * LODGING_WON_PER_PRICE_LEVEL;
        String type = c.attributes() == null ? null : c.attributes().lodgingType();
        if (type != null) {
            return switch (type) {
                case "HOSTEL", "GUESTHOUSE" -> LODGING_HOSTEL_DEFAULT_WON;
                case "RESORT" -> LODGING_RESORT_DEFAULT_WON;
                default -> LODGING_HOTEL_DEFAULT_WON;
            };
        }
        String lower = c.category() == null ? "" : c.category().toLowerCase();
        if (lower.contains("hostel") || lower.contains("guest")) return LODGING_HOSTEL_DEFAULT_WON;
        if (lower.contains("resort") || lower.contains("pool villa") || lower.contains("villa")) {
            return LODGING_RESORT_DEFAULT_WON;
        }
        return LODGING_HOTEL_DEFAULT_WON;
    }

    private PlaceData toPlaceData(PlaceCandidate c) {
        // subRegion/description은 저장에 쓰이지 않고 story 생성(Haiku) 컨텍스트로만 전달된다.
        return new PlaceData(c.name(), c.address(), c.category(), c.region(), c.country(),
                c.subRegion(), c.description(), c.placeId(), c.nameKo());
    }

    /** 하루 거리 예산에 넣을 좌표. 해외 관문은 null이다({@link InvariantChecker#isOverseasHubTransfer}와 같은 기준). */
    private double[] budgetCoords(PlaceCandidate c) {
        return c != null && PlaceCategoryConstants.isOverseasHub(c.country(), c.category()) ? null : coordsOf(c);
    }

    private double[] coordsOf(PlaceCandidate c) {
        if (c == null || c.latitude() == null || c.longitude() == null) return null;
        // DB에 남아 있는 좌표 (0,0) fallback 행은 resolveCoords와 같은 정책으로 좌표 없음으로 본다.
        // 그래야 scheduleDay·centroidOf·대안 계산의 거리에 (0,0)이 끼지 않는다.
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
