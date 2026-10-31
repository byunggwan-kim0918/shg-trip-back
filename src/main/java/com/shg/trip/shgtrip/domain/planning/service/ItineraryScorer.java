package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.global.util.GeoUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.toMinutesOrZero;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.*;

/**
 * 스텝만 보고 채점한다. 저장된 일정엔 DayState·선택 결과가 없어서다.
 * 후보·선택이 없으면 영업시간·인기도·의도 항목은 중립값으로 두고 estimated에 남긴다.
 */
public final class ItineraryScorer {

    /** 임시값. PACE_RANGE는 식사까지 센 개수라 그대로 못 쓴다. */
    static final Map<String, Double> VISIT_TARGET = Map.of("tight", 4.0, "normal", 3.0, "relaxed", 2.0);

    /** 이보다 짧은 틈은 대기·주차로 본다. */
    static final int IDLE_GRACE_MINUTES = 30;
    static final int IDLE_ZERO_SCORE_MINUTES = 240;
    static final int LONG_GAP_MINUTES = 180;

    private static final Map<String, Double> WEIGHTS = Map.of(
            "travel", 0.20,
            "timeUse", 0.20,
            "visits", 0.20,
            "mealVariety", 0.10,
            "lodging", 0.10,
            "prominence", 0.10,
            "intent", 0.10);

    public record Input(
            List<StepData> steps,
            String pace,
            String transportPref,
            LocalDate startDate,
            List<PlaceCandidate> candidates,
            SelectionOutput selection
    ) {}

    public ScoreReport score(Input in) {
        List<StepData> steps = in.steps() == null ? List.of() : in.steps();
        String pace = in.pace() != null ? in.pace() : "normal";
        String transport = in.transportPref() != null ? in.transportPref() : "any";
        Map<String, PlaceCandidate> byName = in.candidates() == null ? Map.of()
                : in.candidates().stream().filter(c -> c.name() != null)
                .collect(Collectors.toMap(PlaceCandidate::name, c -> c, (a, b) -> a));

        TreeMap<Integer, List<StepData>> byDay = steps.stream()
                .sorted(Comparator.comparingInt(StepData::dayNumber).thenComparingInt(StepData::stepOrder))
                .collect(Collectors.groupingBy(StepData::dayNumber, TreeMap::new, Collectors.toList()));
        int dayCount = byDay.size();

        List<String> violations = new ArrayList<>();
        List<String> estimated = new ArrayList<>();
        // 모르면 any(가장 좁은 상한)로 본다. 자동차 여행이면 구간 상한 위반이 과하게 잡힌다
        if (in.pace() == null) estimated.add("pace");
        if (in.transportPref() == null) estimated.add("transportPref");
        Map<String, Double> components = new LinkedHashMap<>();
        Map<String, Number> metrics = new LinkedHashMap<>();

        double[] limits = distanceLimits(transport, pace);
        double travelSum = 0, timeSum = 0, visitSum = 0;
        int totalVisits = 0, maxGap = 0, longGaps = 0;
        double totalKm = 0;

        for (Map.Entry<Integer, List<StepData>> entry : byDay.entrySet()) {
            int day = entry.getKey();
            List<StepData> daySteps = entry.getValue();
            boolean first = day == byDay.firstKey();
            boolean last = day == byDay.lastKey();

            checkHard(day, daySteps, first, last, limits, in.startDate(), byName, violations);

            double dayKm = 0;
            for (StepData s : daySteps) {
                if (s.transportationDistance() != null) {
                    dayKm += s.transportationDistance().doubleValue() / GeoUtils.ROAD_DISTANCE_FACTOR;
                }
            }
            totalKm += dayKm;
            travelSum += travelScore(dayKm, limits[1]);

            int idle = 0;
            for (int i = 1; i < daySteps.size(); i++) {
                StepData prev = daySteps.get(i - 1);
                StepData next = daySteps.get(i);
                int travel = next.transportationDuration() != null ? next.transportationDuration() : 0;
                int gap = toMinutesOrZero(next.startTime()) - toMinutesOrZero(prev.endTime()) - travel;
                maxGap = Math.max(maxGap, gap);
                if (gap >= LONG_GAP_MINUTES) longGaps++;
                idle += Math.max(0, gap - IDLE_GRACE_MINUTES);
            }
            double timeScore = 1 - Math.min(1.0, (double) idle / IDLE_ZERO_SCORE_MINUTES);
            if (!last && lastActivityEnd(daySteps, byName) < EARLY_FINISH_MINUTES) timeScore *= 0.5;
            timeSum += timeScore;

            long visits = daySteps.stream().filter(s -> roleOf(s, byName).isVisit()).count();
            totalVisits += (int) visits;
            double target = VISIT_TARGET.getOrDefault(pace, 3.0) * (first || last ? 0.5 : 1.0);
            visitSum += Math.min(1.0, visits / target);
        }

        components.put("travel", dayCount == 0 ? 0 : travelSum / dayCount);
        components.put("timeUse", dayCount == 0 ? 0 : timeSum / dayCount);
        components.put("visits", dayCount == 0 ? 0 : visitSum / dayCount);
        components.put("mealVariety", mealVariety(steps, byName));
        components.put("lodging", lodgingScore(steps, byName, dayCount));
        components.put("prominence", prominence(steps, byName, estimated));
        components.put("intent", intent(in.selection(), in.candidates(), byDay, byName, estimated));

        double total = 0;
        for (Map.Entry<String, Double> w : WEIGHTS.entrySet()) {
            total += w.getValue() * components.getOrDefault(w.getKey(), 0.0);
        }

        metrics.put("days", dayCount);
        metrics.put("visitsPerDay", dayCount == 0 ? 0 : (double) totalVisits / dayCount);
        metrics.put("totalStraightKm", totalKm);
        metrics.put("maxGapMinutes", maxGap);
        metrics.put("longGaps", longGaps);
        metrics.put("lodgings", distinctLodgings(steps, byName));
        if (in.selection() != null && in.candidates() != null) {
            metrics.put("selectionRetention", selectionRetention(in.selection(), in.candidates(), byDay));
        }

        return new ScoreReport(violations.size(), Math.round(total * 1000) / 10.0, components,
                violations, estimated, metrics);
    }

    private void checkHard(int day, List<StepData> daySteps, boolean first, boolean last, double[] limits,
                           LocalDate startDate, Map<String, PlaceCandidate> byName, List<String> violations) {
        int dayStart = toMinutesOrZero(daySteps.get(0).startTime());
        int dayEnd = toMinutesOrZero(daySteps.get(daySteps.size() - 1).endTime());

        // 오후 도착·오전 출발인 날은 그 끼니를 요구하지 않는다
        if (dayStart <= LUNCH_START && dayEnd >= LUNCH_END
                && !InvariantChecker.hasMealInWindow(daySteps, LUNCH_START, LUNCH_END)) {
            violations.add("day" + day + " 점심 없음");
        }
        if (dayStart <= DINNER_START && dayEnd >= DINNER_END
                && !InvariantChecker.hasMealInWindow(daySteps, DINNER_START, DINNER_END)) {
            violations.add("day" + day + " 저녁 없음");
        }
        if (!last && roleOf(daySteps.get(daySteps.size() - 1), byName) != CandidatePolicy.Role.LODGING) {
            violations.add("day" + day + " 숙소로 끝나지 않음");
        }
        if (last && dayEnd > DEPARTURE_HUB_LATEST_MINUTES) {
            violations.add("day" + day + " 출발 마감 초과");
        }
        for (StepData s : daySteps) {
            if (s.transportationDistance() != null
                    && s.transportationDistance().doubleValue() / GeoUtils.ROAD_DISTANCE_FACTOR > limits[0]) {
                violations.add("day" + day + " 구간 상한 초과(" + name(s) + ")");
            }
        }
        if (startDate == null) return;
        DayOfWeek dow = startDate.plusDays(day - 1L).getDayOfWeek();
        for (StepData s : daySteps) {
            PlaceCandidate c = byName.get(name(s));
            if (c == null || c.openingHours() == null || c.openingHours().isBlank()) continue;
            CandidatePolicy.Role role = CandidatePolicy.roleOf(c);
            if (role == CandidatePolicy.Role.LODGING || role == CandidatePolicy.Role.TRANSIT) continue;
            if (OpeningHoursText.isClosedOnDay(c.openingHours(), dow)) {
                violations.add("day" + day + " 휴무일 방문(" + c.name() + ")");
            } else if (!OpeningHoursText.isOpenDuring(c.openingHours(), dow,
                    toMinutesOrZero(s.startTime()), toMinutesOrZero(s.endTime()))) {
                violations.add("day" + day + " 영업시간 밖(" + c.name() + ")");
            }
        }
    }

    /** 하루 상한의 60%까지 만점, 120%면 0점. */
    private static double travelScore(double dayKm, double dailyLimit) {
        if (dailyLimit <= 0) return 1;
        double ratio = dayKm / dailyLimit;
        if (ratio <= 0.6) return 1;
        return Math.max(0, 1 - (ratio - 0.6) / 0.6);
    }

    private static int lastActivityEnd(List<StepData> daySteps, Map<String, PlaceCandidate> byName) {
        return daySteps.stream()
                .filter(s -> {
                    CandidatePolicy.Role r = roleOf(s, byName);
                    return r != CandidatePolicy.Role.LODGING && r != CandidatePolicy.Role.TRANSIT;
                })
                .mapToInt(s -> toMinutesOrZero(s.endTime()))
                .max().orElse(0);
    }

    /** 같은 음식 종류는 일정 전체에서 두 끼까지. */
    private static double mealVariety(List<StepData> steps, Map<String, PlaceCandidate> byName) {
        List<String> kinds = steps.stream()
                .filter(s -> roleOf(s, byName) == CandidatePolicy.Role.MEAL)
                .map(s -> {
                    PlaceCandidate c = byName.get(name(s));
                    return c != null
                            ? PlaceCategoryConstants.mealKind(c.name(), c.category(), c.tags(), c.description(), c.cuisine())
                            : PlaceCategoryConstants.mealKind(name(s), category(s), null, null, null);
                })
                .filter(Objects::nonNull)
                .toList();
        if (kinds.isEmpty()) return 1;
        long excess = kinds.stream().collect(Collectors.groupingBy(k -> k, Collectors.counting()))
                .values().stream().mapToLong(n -> Math.max(0, n - 2)).sum();
        return 1 - (double) excess / kinds.size();
    }

    private static double lodgingScore(List<StepData> steps, Map<String, PlaceCandidate> byName, int dayCount) {
        int nights = Math.max(0, dayCount - 1);
        if (nights == 0) return 1;
        int cap = (int) Math.ceil((double) nights / NIGHTS_PER_LODGING);
        long count = distinctLodgings(steps, byName);
        if (count == 0) return 0;
        return count <= cap ? 1 : (double) cap / count;
    }

    private static long distinctLodgings(List<StepData> steps, Map<String, PlaceCandidate> byName) {
        return steps.stream()
                .filter(s -> roleOf(s, byName) == CandidatePolicy.Role.LODGING)
                .map(ItineraryScorer::name)
                .distinct().count();
    }

    private static double prominence(List<StepData> steps, Map<String, PlaceCandidate> byName,
                                     List<String> estimated) {
        if (byName.isEmpty()) {
            estimated.add("prominence");
            return 0.5;
        }
        return steps.stream()
                .filter(s -> roleOf(s, byName).isVisit())
                .map(s -> byName.get(name(s)))
                .filter(Objects::nonNull)
                .mapToDouble(PlaceProminence::of)
                .average().orElse(0.5);
    }

    /** timing 시간대 준수와 highlight 유지 비율. 의도가 없으면 1. */
    private static double intent(SelectionOutput selection, List<PlaceCandidate> candidates,
                                 Map<Integer, List<StepData>> byDay, Map<String, PlaceCandidate> byName,
                                 List<String> estimated) {
        if (selection == null || candidates == null) {
            estimated.add("intent");
            return 0.5;
        }
        Map<String, StepData> placed = new HashMap<>();
        byDay.values().forEach(list -> list.forEach(s -> placed.putIfAbsent(name(s), s)));

        int asked = 0, kept = 0;
        if (selection.timing() != null) {
            for (SelectionOutput.TimingHint t : selection.timing()) {
                PlaceCandidate c = ScheduleTimes.byIndex(candidates, t.index());
                if (c == null) continue;
                asked++;
                StepData s = placed.get(c.name());
                if (s == null) continue;
                int start = toMinutesOrZero(s.startTime());
                boolean ok = "evening".equals(t.when()) ? start >= EVENING_ACTIVITY_EARLIEST_START
                        : start < LUNCH_START;
                if (ok) kept++;
            }
        }
        if (selection.highlightIndices() != null) {
            for (Integer idx : selection.highlightIndices()) {
                PlaceCandidate c = idx == null ? null : ScheduleTimes.byIndex(candidates, idx);
                if (c == null) continue;
                asked++;
                if (placed.containsKey(c.name())) kept++;
            }
        }
        return asked == 0 ? 1 : (double) kept / asked;
    }

    /** Sonnet이 고른 방문지 중 같은 날에 남은 비율. */
    static double selectionRetention(SelectionOutput selection, List<PlaceCandidate> candidates,
                                     Map<Integer, List<StepData>> byDay) {
        if (selection.days() == null) return 0;
        int picked = 0, sameDay = 0;
        for (SelectionOutput.DayPlan d : selection.days()) {
            if (d.placeIndices() == null) continue;
            Set<String> namesThatDay = byDay.getOrDefault(d.dayNumber(), List.of()).stream()
                    .map(ItineraryScorer::name).collect(Collectors.toSet());
            for (Integer idx : d.placeIndices()) {
                PlaceCandidate c = idx == null ? null : ScheduleTimes.byIndex(candidates, idx);
                if (c == null || !CandidatePolicy.roleOf(c).isVisit()) continue;
                picked++;
                if (namesThatDay.contains(c.name())) sameDay++;
            }
        }
        return picked == 0 ? 1 : (double) sameDay / picked;
    }

    private static CandidatePolicy.Role roleOf(StepData s, Map<String, PlaceCandidate> byName) {
        PlaceCandidate c = byName.get(name(s));
        if (c != null) return CandidatePolicy.roleOf(c);
        return CandidatePolicy.estimateRole(name(s), category(s), null);
    }

    private static String name(StepData s) {
        return s.place() != null && s.place().name() != null ? s.place().name() : "";
    }

    private static String category(StepData s) {
        return s.place() != null ? s.place().category() : null;
    }
}
