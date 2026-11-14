package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.global.util.GeoUtils;

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

    /** 가중치·목표·항목 계산이 달라지면 올린다. 버전이 다른 보고서끼리는 점수를 비교하지 않는다. */
    public static final int VERSION = 1;

    /** 페이스 범위의 가운데(올림). tight 3~5 → 4, normal 2~3 → 3, relaxed 1~2 → 2. */
    static double visitTarget(String pace) {
        int[] sights = ScheduleTuning.sightsPerDay(pace);
        return Math.ceil((sights[0] + sights[1]) / 2.0);
    }

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

    private static final ItineraryRules RULES = new ItineraryRules();

    /** 하드 위반 수로 세는 규칙. 기준선(docs/eval-baseline.md)의 하드 위반과 같은 항목이어야 비교가 이어진다. */
    private static final Set<ItineraryRules.Rule> SCORED_RULES = EnumSet.of(
            ItineraryRules.Rule.LUNCH_MISSING, ItineraryRules.Rule.DINNER_MISSING,
            ItineraryRules.Rule.NOT_ENDING_AT_LODGING, ItineraryRules.Rule.DEPARTURE_LATE,
            ItineraryRules.Rule.DEPARTURE_MISSED, ItineraryRules.Rule.LEG_OVER_CAP,
            ItineraryRules.Rule.CLOSED_DAY, ItineraryRules.Rule.OUTSIDE_HOURS);

    /** @param lastDayHubDeadline 사용자 출발 시각에서 정한 허브 도착 마감(분). 없으면 null(21:00 기준) */
    public record Input(
            List<StepData> steps,
            String pace,
            String transportPref,
            LocalDate startDate,
            List<PlaceCandidate> candidates,
            SelectionOutput selection,
            Integer lastDayHubDeadline
    ) {
        public Input(List<StepData> steps, String pace, String transportPref, LocalDate startDate,
                     List<PlaceCandidate> candidates, SelectionOutput selection) {
            this(steps, pace, transportPref, startDate, candidates, selection, null);
        }
    }

    public ScoreReport score(Input in) {
        List<StepData> steps = in.steps() == null ? List.of() : in.steps();
        String pace = in.pace() != null ? in.pace() : "normal";
        String transport = in.transportPref() != null ? in.transportPref() : "any";
        CandidateLookup lookup = new CandidateLookup(in.candidates());

        TreeMap<Integer, List<StepData>> byDay = steps.stream()
                .sorted(Comparator.comparingInt(StepData::dayNumber).thenComparingInt(StepData::stepOrder))
                .collect(Collectors.groupingBy(StepData::dayNumber, TreeMap::new, Collectors.toList()));
        int dayCount = byDay.size();

        List<String> violations = RULES.evaluate(steps, in.candidates(), new ItineraryRules.Context(
                        in.pace(), in.transportPref(), in.startDate(), 0, in.lastDayHubDeadline()))
                .violations().stream()
                .filter(v -> SCORED_RULES.contains(v.rule()))
                .map(ItineraryRules.Violation::message)
                .toList();
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
        List<ScoreReport.DayScore> dayScores = new ArrayList<>();

        for (Map.Entry<Integer, List<StepData>> entry : byDay.entrySet()) {
            int day = entry.getKey();
            List<StepData> daySteps = entry.getValue();
            boolean first = day == byDay.firstKey();
            boolean last = day == byDay.lastKey();

            double dayKm = 0;
            for (int i = 0; i < daySteps.size(); i++) {
                StepData s = daySteps.get(i);
                if (s.transportationDistance() != null && !InvariantChecker.isOutsideDayBudget(daySteps, i, first, last)) {
                    dayKm += s.transportationDistance().doubleValue() / GeoUtils.ROAD_DISTANCE_FACTOR;
                }
            }
            totalKm += dayKm;
            double travelScore = travelScore(dayKm, limits[1]);
            travelSum += travelScore;

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
            if (!last && lookup.lastActivityEnd(daySteps) < EARLY_FINISH_MINUTES) timeScore *= 0.5;
            timeSum += timeScore;

            long visits = daySteps.stream().filter(s -> lookup.role(s).isVisit()).count();
            totalVisits += (int) visits;
            double target = visitTarget(pace) * (first || last ? 0.5 : 1.0);
            double visitScore = Math.min(1.0, visits / target);
            visitSum += visitScore;
            dayScores.add(new ScoreReport.DayScore(day, (travelScore + timeScore + visitScore) / 3, (int) visits,
                    dayProminence(daySteps, lookup), lookup.lastActivityEnd(daySteps)));
        }

        components.put("travel", dayCount == 0 ? 0 : travelSum / dayCount);
        components.put("timeUse", dayCount == 0 ? 0 : timeSum / dayCount);
        components.put("visits", dayCount == 0 ? 0 : visitSum / dayCount);
        components.put("mealVariety", mealVariety(steps, lookup));
        components.put("lodging", lodgingScore(steps, lookup, dayCount));
        components.put("prominence", prominence(steps, lookup, estimated));
        components.put("intent", intent(in.selection(), in.candidates(), byDay, estimated));

        double total = 0;
        for (Map.Entry<String, Double> w : WEIGHTS.entrySet()) {
            total += w.getValue() * components.getOrDefault(w.getKey(), 0.0);
        }

        metrics.put("days", dayCount);
        metrics.put("visitsPerDay", dayCount == 0 ? 0 : (double) totalVisits / dayCount);
        metrics.put("totalStraightKm", totalKm);
        metrics.put("maxGapMinutes", maxGap);
        metrics.put("longGaps", longGaps);
        metrics.put("lodgings", distinctLodgings(steps, lookup));
        if (in.selection() != null && in.candidates() != null) {
            metrics.put("selectionRetention", selectionRetention(in.selection(), in.candidates(), byDay));
        }

        return new ScoreReport(violations.size(), Math.round(total * 1000) / 10.0, components,
                violations, estimated, metrics, dayScores);
    }

    private static Double dayProminence(List<StepData> daySteps, CandidateLookup lookup) {
        OptionalDouble average = daySteps.stream()
                .filter(s -> lookup.role(s).isVisit())
                .map(lookup::of)
                .filter(Objects::nonNull)
                .mapToDouble(PlaceProminence::of)
                .average();
        return average.isPresent() ? average.getAsDouble() : null;
    }

    /** 하루 상한의 60%까지 만점, 120%면 0점. */
    private static double travelScore(double dayKm, double dailyLimit) {
        if (dailyLimit <= 0) return 1;
        double ratio = dayKm / dailyLimit;
        if (ratio <= 0.6) return 1;
        return Math.max(0, 1 - (ratio - 0.6) / 0.6);
    }

    /** 같은 음식 종류는 일정 전체에서 두 끼까지. */
    private static double mealVariety(List<StepData> steps, CandidateLookup lookup) {
        List<String> kinds = steps.stream()
                .filter(s -> lookup.role(s) == CandidatePolicy.Role.MEAL)
                .map(lookup::mealKind)
                .filter(Objects::nonNull)
                .toList();
        if (kinds.isEmpty()) return 1;
        long excess = kinds.stream().collect(Collectors.groupingBy(k -> k, Collectors.counting()))
                .values().stream().mapToLong(n -> Math.max(0, n - 2)).sum();
        return 1 - (double) excess / kinds.size();
    }

    private static double lodgingScore(List<StepData> steps, CandidateLookup lookup, int dayCount) {
        int nights = Math.max(0, dayCount - 1);
        if (nights == 0) return 1;
        int cap = (int) Math.ceil((double) nights / NIGHTS_PER_LODGING);
        long count = distinctLodgings(steps, lookup);
        if (count == 0) return 0;
        return count <= cap ? 1 : (double) cap / count;
    }

    private static long distinctLodgings(List<StepData> steps, CandidateLookup lookup) {
        return steps.stream()
                .filter(s -> lookup.role(s) == CandidatePolicy.Role.LODGING)
                .map(CandidateLookup::name)
                .distinct().count();
    }

    private static double prominence(List<StepData> steps, CandidateLookup lookup, List<String> estimated) {
        if (lookup.isEmpty()) {
            estimated.add("prominence");
            return 0.5;
        }
        return steps.stream()
                .filter(s -> lookup.role(s).isVisit())
                .map(lookup::of)
                .filter(Objects::nonNull)
                .mapToDouble(PlaceProminence::of)
                .average().orElse(0.5);
    }

    /** timing 시간대 준수와 highlight 유지 비율. 의도가 없으면 1. */
    private static double intent(SelectionOutput selection, List<PlaceCandidate> candidates,
                                 Map<Integer, List<StepData>> byDay, List<String> estimated) {
        if (selection == null || candidates == null) {
            estimated.add("intent");
            return 0.5;
        }
        Map<String, StepData> placed = new HashMap<>();
        byDay.values().forEach(list -> list.forEach(s -> placed.putIfAbsent(CandidateLookup.name(s), s)));

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
                    .map(CandidateLookup::name).collect(Collectors.toSet());
            for (Integer idx : d.placeIndices()) {
                PlaceCandidate c = idx == null ? null : ScheduleTimes.byIndex(candidates, idx);
                if (c == null || !CandidatePolicy.roleOf(c).isVisit()) continue;
                picked++;
                if (namesThatDay.contains(c.name())) sameDay++;
            }
        }
        return picked == 0 ? 1 : (double) sameDay / picked;
    }
}
