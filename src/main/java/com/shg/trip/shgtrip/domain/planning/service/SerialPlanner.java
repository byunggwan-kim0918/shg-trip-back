package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.ScheduleArgs;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.coordsOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.placeKey;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.addToDay;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.openOnDayFilter;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.pickNearbyCandidate;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.byIndex;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.MAX_INVARIANT_PASSES;

/**
 * 날짜별로 고른 장소를 그날 안에서만 확정한다. {@link RouteOptimizer}와 달리 날짜 사이로 장소를 옮기거나
 * 유형·카테고리를 맞추지 않고, 하루를 확정한 뒤 끼니·숙소·관광이 빠졌을 때만 그날에 채운다.
 * {@link #plan}은 날짜를 {@link DayPicker}로 직접 정한다.
 */
@Slf4j
public class SerialPlanner {

    // 빈 날에 넣은 장소는 확정 시각에 영업하지 않으면 다시 빠진다.
    private static final int SEED_ATTEMPTS = 3;

    private final InvariantChecker invariantChecker = new InvariantChecker();

    /**
     * @param days      일차 순서의 날짜별 결과
     * @param selection 날짜별로 고른 장소. 직접 고르지 않았으면 받은 선택 그대로다
     */
    public record Result(List<StepData> steps, List<String> unresolved, List<DayReport> days,
                         SelectionOutput selection) {}

    /**
     * @param picked    그날에 고른 장소 수(선택 단계 또는 직접 선택)
     * @param dropped   고른 장소 가운데 그날 일정에 남지 않은 것
     * @param filled    어느 날에도 고르지 않았는데 하루 확정이 그날에 넣은 장소 수(숙소·이동 거점 제외)
     * @param shortfall 직접 고른 날에 하루 관광 하한에서 모자란 수. 닿는 범위·시간 예산·유형 반복·휴무로 고를 곳이 없던 것이다
     */
    public record DayReport(int dayNumber, int picked, List<Dropped> dropped, int filled, int shortfall) {}

    public record Dropped(String name, DropReason reason) {}

    private record DroppedOn(int dayNumber, String placeKey) {}

    // 도시 구간마다 후보 번호가 달라 장소는 키로 적는다.
    private static final class TripNotes {
        final Map<DroppedOn, DropReason> reasons = new HashMap<>();
        final Map<Integer, List<String>> picks = new TreeMap<>();
        final Map<Integer, Integer> shortfall = new HashMap<>();
    }

    /** 선택 단계가 날짜별로 놓은 장소를 어떻게 쓸지. */
    public enum Picks {
        /** 놓은 날 그대로 확정한다. */
        AS_PLACED,
        /** 날짜는 직접 정하고, 같은 거리 안에서는 선택 단계가 고른 장소를 먼저 고른다. */
        PREFER,
        /** 날짜는 직접 정하고, 선택 단계가 고른 장소는 따로 대우하지 않는다. */
        IGNORE
    }

    public Result schedule(SelectionOutput selection, List<PlaceCandidate> candidates, ScheduleArgs args) {
        return run(selection, candidates, args, Picks.AS_PLACED);
    }

    /**
     * 날짜는 직접 정한다. 날마다 기준 장소에서 닿는 범위 안에서 고르고, 요청 의도(highlight·timing)·거점·숙소·예비 장소는
     * 선택 결과의 것을 쓴다.
     *
     * @param selection 날짜 수가 {@code dayCount}와 다르면 요청 의도만 읽고 {@link Picks#IGNORE}로 고른다
     */
    public Result plan(SelectionOutput selection, List<PlaceCandidate> candidates, ScheduleArgs args, int dayCount,
                       Picks picks) {
        if (selection.days() != null && selection.days().size() == dayCount) {
            return run(selection, candidates, args, picks);
        }
        log.warn("선택 결과의 날짜 수가 여행 일수와 달라 요청 의도만 쓴다: 선택 {}일, 여행 {}일",
                selection.days() == null ? 0 : selection.days().size(), dayCount);
        List<SelectionOutput.DayPlan> days = IntStream.rangeClosed(1, dayCount)
                .mapToObj(day -> new SelectionOutput.DayPlan(day, null, List.of(), null, null)).toList();
        return run(new SelectionOutput("", days, List.of(), List.of()), IntentHints.apply(selection, candidates), args,
                Picks.IGNORE);
    }

    private Result run(SelectionOutput selection, List<PlaceCandidate> candidates, ScheduleArgs args, Picks picks) {
        TripNotes notes = new TripNotes();
        CityBlockPlanner.BlockScheduler withinCity =
                (blockSelection, blockCandidates, blockArgs) -> scheduleWithinCity(blockSelection, blockCandidates,
                        blockArgs, notes, picks);
        RouteOptimizer.ScheduleResult result = args.cities() != null
                ? CityBlockPlanner.schedule(selection, candidates, args, withinCity)
                : withinCity.schedule(selection, candidates, args);
        SelectionOutput chosen = picks == Picks.AS_PLACED ? selection : chosen(notes, candidates);
        return new Result(result.steps(), result.unresolved(), reports(chosen, candidates, result.steps(), notes),
                chosen);
    }

    private RouteOptimizer.ScheduleResult scheduleWithinCity(SelectionOutput selection, List<PlaceCandidate> candidates,
                                                             ScheduleArgs args, TripNotes notes, Picks picks) {
        boolean choosesDays = picks != Picks.AS_PLACED;
        List<PlaceCandidate> withIntent = IntentHints.apply(selection, candidates);
        PlanState s = PlanState.of(selection, withIntent, args);
        Set<Integer> placed = s.days.stream().flatMap(d -> d.placeIndices.stream()).filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<Integer> preferred = picks == Picks.PREFER ? placed : Set.of();
        // 하루를 마칠 때마다 다시 사용 중으로 잡을 장소.
        Set<Integer> reserved = new HashSet<>();
        if (choosesDays) {
            s.days.forEach(d -> d.placeIndices.clear());
            // 빈 시간 채우기는 예비 목록만 본다. 직접 고르지 못한 선택 장소도 거기에 있어야 채우기에 쓰인다.
            s.spare.removeAll(preferred);
            s.spare.addAll(preferred);
            DayPicker.prepare(s, args.hub(), preferred);
            s.days.forEach(d -> reserved.addAll(d.placeIndices));
        } else {
            reserved.addAll(placed);
            SubstitutionRepair.dedupeMainStepsAcrossItinerary(s.days, s.candidates, s.spare);
            LodgingPlanner.assignHubsAndLodging(s.days, s.candidates, args.hub());
        }
        MealPlanner.ensureCafeStops(s.days, s.candidates, s.spare, s.cfg, s.transportPref);
        s.beginScheduling();

        List<StepData> steps = new ArrayList<>();
        for (int i = 0; i < s.days.size(); i++) {
            DayState day = s.days.get(i);
            Integer previousLodging = i > 0 ? s.days.get(i - 1).accommodationIndex : null;
            if (choosesDays) {
                notes.shortfall.put(day.dayNumber,
                        DayPicker.fill(s, day, previousLodging, DayState.endsTrip(s.days, i), preferred));
                reserved.addAll(day.placeIndices);
                notes.picks.put(day.dayNumber, day.placeIndices.stream()
                        .map(idx -> byIndex(s.candidates, idx)).filter(Objects::nonNull)
                        .map(SerialPlanner::keyOf).toList());
            }
            steps.addAll(completeDay(s, day, previousLodging, notes.reasons, choosesDays));
            // 거리 트림과 끼니 교체로 뺀 장소는 사용 표시가 풀린다. 그날에는 다시 쓸 수 있지만 다른 날의 빈자리로 들어가면 안 된다.
            s.usedIndices.addAll(reserved);
        }
        if (choosesDays) {
            Set<Integer> finalPlaces = s.days.stream().flatMap(d -> d.placeIndices.stream()).collect(Collectors.toSet());
            log.info("날짜 직접 선택: {} / 선택 단계가 놓은 장소 {}곳 중 {}곳 반영", picks, placed.size(),
                    placed.stream().filter(finalPlaces::contains).count());
        }
        List<String> unresolved = invariantChecker.summarize(s.days, steps, s.pace, s.transportPref, s.candidates,
                s.cfg);
        IntentHints.logOutcome(withIntent, steps);
        return new RouteOptimizer.ScheduleResult(AlternativeBuilder.pruneUsedAndRenumber(steps), unresolved);
    }

    /** @param nearbySpareOnly 그날 기준 장소에서 먼 예비 장소를 그날 보충에서 뺀다 */
    private static List<StepData> completeDay(PlanState s, DayState day, Integer previousLodging,
                                              Map<DroppedOn, DropReason> reasons, boolean nearbySpareOnly) {
        Set<Integer> droppedToday = new HashSet<>();
        List<StepData> steps = scheduleDay(s, day, previousLodging, reasons, droppedToday, nearbySpareOnly);
        for (int attempt = 0; attempt < SEED_ATTEMPTS && seedEmptyDay(s, day, previousLodging, droppedToday);
             attempt++) {
            steps = scheduleDay(s, day, previousLodging, reasons, droppedToday, nearbySpareOnly);
        }
        InvariantMemo memo = new InvariantMemo();
        for (int pass = 0; pass < MAX_INVARIANT_PASSES && FinalRepair.fillDay(s, day, steps, memo); pass++) {
            steps = scheduleDay(s, day, previousLodging, reasons, droppedToday, nearbySpareOnly);
            memo.settle(s.days);
        }
        return steps;
    }

    private static List<StepData> scheduleDay(PlanState s, DayState day, Integer previousLodging,
                                              Map<DroppedOn, DropReason> reasons, Set<Integer> droppedToday,
                                              boolean nearbySpareOnly) {
        s.resetSpare();
        if (nearbySpareOnly) DayPicker.keepSpareNearBase(s, day);
        DayScheduler.DayResult result = DayScheduler.scheduleOne(s, day, previousLodging);
        for (DayState.Drop drop : result.dropped()) {
            droppedToday.add(drop.index());
            PlaceCandidate place = byIndex(s.candidates, drop.index());
            if (place != null) reasons.putIfAbsent(new DroppedOn(day.dayNumber, keyOf(place)), drop.reason());
        }
        return result.steps();
    }

    /**
     * 장소가 하나도 없는 날을 그날 거점에서 가까운 장소 하나로 시작한다. 관광지·식당이 없는 날은 끼니와 관광 보충이 건너뛴다.
     *
     * @param droppedToday 이 날 확정에서 이미 빠진 장소. 다시 넣어도 같은 이유로 빠진다
     */
    private static boolean seedEmptyDay(PlanState s, DayState day, Integer previousLodging,
                                        Set<Integer> droppedToday) {
        if (!day.placeIndices.isEmpty() || day.tooShortForSightseeing()) return false;
        double[] anchor = Stream.of(day.accommodationIndex, day.departureHubIndex, previousLodging, day.arrivalHubIndex)
                .filter(Objects::nonNull)
                .map(idx -> coordsOf(byIndex(s.candidates, idx)))
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
        // 여행이 끝나는 날은 저녁에 갈 곳으로 시작하면 출발 시각을 넘긴다.
        boolean endsTrip = DayState.endsTrip(s.days, s.days.indexOf(day));
        Predicate<PlaceCandidate> visitable = openOnDayFilter(s.startDate, day.dayNumber)
                .and(CandidateTraits::isVisitableFill)
                .and(c -> !droppedToday.contains(c.index()))
                .and(c -> !endsTrip || DayTiming.timePreference(c) != TimePreference.EVENING);
        Integer seed = pickNearbyCandidate(s.candidates, s.spare, s.usedIndices, anchor, visitable
                .and(c -> "ATTRACTION".equals(PlaceCategoryConstants.majorCategory(c.category()))));
        if (seed == null) seed = pickNearbyCandidate(s.candidates, s.spare, s.usedIndices, anchor, visitable);
        if (seed == null) return false;
        addToDay(day, seed, s.spare, s.usedIndices);
        return true;
    }

    private static SelectionOutput chosen(TripNotes notes, List<PlaceCandidate> candidates) {
        Map<String, Integer> indexOf = new HashMap<>();
        candidates.forEach(c -> indexOf.putIfAbsent(keyOf(c), c.index()));
        List<SelectionOutput.DayPlan> days = notes.picks.entrySet().stream()
                .map(e -> new SelectionOutput.DayPlan(e.getKey(), null,
                        e.getValue().stream().map(indexOf::get).filter(Objects::nonNull).toList(), null, null))
                .toList();
        return new SelectionOutput("", days, List.of(), List.of());
    }

    // 선택 단계가 고른 날을 기준으로 센다. 도시 구간이 다른 날로 옮긴 장소도 고른 날에서는 빠진 것이다.
    private static List<DayReport> reports(SelectionOutput selection, List<PlaceCandidate> candidates,
                                           List<StepData> steps, TripNotes notes) {
        CandidateLookup lookup = new CandidateLookup(candidates);
        Map<Integer, Set<PlaceCandidate>> pickedByDay = new TreeMap<>();
        for (SelectionOutput.DayPlan plan : selection.days()) {
            pickedByDay.put(plan.dayNumber(), plan.placeIndices() == null ? Set.of() : plan.placeIndices().stream()
                    .map(idx -> idx == null ? null : byIndex(candidates, idx))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
        }
        Set<String> pickedAnyDay = pickedByDay.values().stream().flatMap(Set::stream).map(SerialPlanner::keyOf)
                .collect(Collectors.toSet());

        List<DayReport> reports = new ArrayList<>();
        pickedByDay.forEach((dayNumber, picked) -> {
            List<StepData> daySteps = steps.stream().filter(st -> st.dayNumber() == dayNumber).toList();
            Set<String> placed = daySteps.stream().map(SerialPlanner::keyOf).collect(Collectors.toSet());
            List<Dropped> dropped = picked.stream()
                    .filter(c -> !placed.contains(keyOf(c)))
                    .map(c -> new Dropped(c.name(),
                            notes.reasons.getOrDefault(new DroppedOn(dayNumber, keyOf(c)), DropReason.OTHER)))
                    .toList();
            int filled = (int) daySteps.stream()
                    .filter(st -> !pickedAnyDay.contains(keyOf(st)))
                    .filter(st -> !Set.of(CandidatePolicy.Role.LODGING, CandidatePolicy.Role.TRANSIT)
                            .contains(lookup.role(st)))
                    .count();
            reports.add(new DayReport(dayNumber, picked.size(), dropped, filled,
                    notes.shortfall.getOrDefault(dayNumber, 0)));
        });
        return reports;
    }

    // 주소 없는 체인은 지점마다 이름이 같아 장소 id가 있으면 id로 구분한다.
    private static String keyOf(PlaceCandidate c) {
        return keyOf(c.placeId(), c.name(), c.address());
    }

    private static String keyOf(StepData step) {
        return keyOf(step.place().placeId(), step.place().name(), step.place().address());
    }

    private static String keyOf(Long placeId, String name, String address) {
        return placeId != null ? "id:" + placeId : placeKey(name, address);
    }
}
