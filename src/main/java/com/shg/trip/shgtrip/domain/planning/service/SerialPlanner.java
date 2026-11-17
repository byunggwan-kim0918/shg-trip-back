package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.ScheduleArgs;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
@Component
public class SerialPlanner {

    // 빈 날에 넣은 장소는 확정 시각에 영업하지 않으면 다시 빠진다.
    private static final int SEED_ATTEMPTS = 3;

    private final InvariantChecker invariantChecker = new InvariantChecker();

    /**
     * @param days      일차 순서의 날짜별 결과
     * @param selection 날짜별로 고른 장소. 직접 고르지 않았으면 받은 선택 그대로다
     * @param anchors   직접 고른 날의 기준 좌표(기준 장소, 없으면 그날 숙소). 일차가 키이고 직접 고르지 않았으면 비어 있다
     */
    public record Result(List<StepData> steps, List<String> unresolved, List<DayReport> days,
                         SelectionOutput selection, Map<Integer, double[]> anchors) {}

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
        final Map<Integer, double[]> anchors = new TreeMap<>();
        // 도시 구간이 바뀌어도 전날 저녁과 다음 날 점심은 이어서 판정된다.
        String lastDinnerKind;
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
        return run(selection, candidates, args, Picks.AS_PLACED, Set.of(), new DaySelection(DaySelector.NONE, 0), true);
    }

    /**
     * 날짜는 직접 정한다. 날마다 기준 장소에서 닿는 범위 안에서 고르고, 요청 의도(highlight·timing)·거점·숙소·예비 장소는
     * 선택 결과의 것을 쓴다.
     *
     * 사용자가 고른 장소, 꼭 하고 싶은 경험으로 맞춘 장소, 선택 단계의 요청 의도 장소({@link PlaceCandidate#keepsPlace()})는
     * 닿는 범위 밖이어도 날짜를 고를 때 먼저 둔다. 휴무일에는 두지 않고, 점심·저녁 자리가 찬 날의 고른 식당은 아침 자리를 맡는다.
     *
     * @param selection 날짜 수가 {@code dayCount}와 다르면 요청 의도와 예약 숙소만 읽고 {@link Picks#IGNORE}로 고른다
     */
    public Result plan(SelectionOutput selection, List<PlaceCandidate> candidates, ScheduleArgs args, int dayCount,
                       Picks picks) {
        return plan(selection, candidates, args, dayCount, picks, DaySelector.NONE, true);
    }

    /** @param selector 날짜를 직접 고를 때 그날 먼저 쓸 장소를 고른다. 받은 날짜대로 확정하면 부르지 않는다 */
    public Result plan(SelectionOutput selection, List<PlaceCandidate> candidates, ScheduleArgs args, int dayCount,
                       Picks picks, DaySelector selector) {
        return plan(selection, candidates, args, dayCount, picks, selector, true);
    }

    /** 결과를 버리고 기준 좌표만 쓸 확정. 의도 반영률을 세는 요약 로그(불변식 요약·의도 시간대·날짜 직접 선택)를 남기지 않는다. */
    public Result planWithoutSummary(SelectionOutput selection, List<PlaceCandidate> candidates, ScheduleArgs args,
                                     int dayCount, Picks picks) {
        return plan(selection, candidates, args, dayCount, picks, DaySelector.NONE, false);
    }

    private Result plan(SelectionOutput selection, List<PlaceCandidate> candidates, ScheduleArgs args, int dayCount,
                        Picks picks, DaySelector selector, boolean summary) {
        // 선택 단계의 의도도 intentProtected로 붙으므로 그 전에 약속 장소를 정한다.
        Set<String> promised = candidates.stream()
                .filter(c -> c.userSelected() || c.intentProtected())
                .map(SerialPlanner::keyOf)
                .collect(Collectors.toSet());
        if (selection.days() != null && selection.days().size() == dayCount) {
            return run(selection, candidates, args, picks, promised, new DaySelection(selector, dayCount), summary);
        }
        log.warn("선택 결과의 날짜 수가 여행 일수와 달라 요청 의도와 예약 숙소만 쓴다: 선택 {}일, 여행 {}일",
                selection.days() == null ? 0 : selection.days().size(), dayCount);
        Integer booked = candidates.stream()
                .filter(c -> c.userSelected() && "LODGING".equals(PlaceCategoryConstants.majorCategory(c.category())))
                .map(PlaceCandidate::index).findFirst().orElse(null);
        List<SelectionOutput.DayPlan> days = IntStream.rangeClosed(1, dayCount)
                .mapToObj(day -> new SelectionOutput.DayPlan(day, null, List.of(), day < dayCount ? booked : null,
                        null))
                .toList();
        return run(new SelectionOutput("", days, List.of(), List.of()), IntentHints.apply(selection, candidates), args,
                Picks.IGNORE, promised, new DaySelection(selector, dayCount), summary);
    }

    private record DaySelection(DaySelector selector, int dayCount) {}

    private Result run(SelectionOutput selection, List<PlaceCandidate> candidates, ScheduleArgs args, Picks picks,
                       Set<String> promised, DaySelection daySelection, boolean summary) {
        TripNotes notes = new TripNotes();
        CityBlockPlanner.BlockScheduler withinCity =
                (blockSelection, blockCandidates, blockArgs, earlier) -> scheduleWithinCity(blockSelection,
                        blockCandidates, blockArgs, earlier, notes, picks, promised, daySelection, summary);
        RouteOptimizer.ScheduleResult result = args.cities() != null
                ? CityBlockPlanner.schedule(selection, candidates, args, withinCity)
                : withinCity.schedule(selection, candidates, args, PlanState.Earlier.NONE);
        SelectionOutput chosen = picks == Picks.AS_PLACED ? selection : chosen(notes, candidates);
        return new Result(result.steps(), result.unresolved(), reports(chosen, candidates, result.steps(), notes),
                chosen, notes.anchors);
    }

    private RouteOptimizer.ScheduleResult scheduleWithinCity(SelectionOutput selection, List<PlaceCandidate> candidates,
                                                             ScheduleArgs args, PlanState.Earlier earlier,
                                                             TripNotes notes, Picks picks, Set<String> promised,
                                                             DaySelection daySelection, boolean summary) {
        boolean choosesDays = picks != Picks.AS_PLACED;
        Set<Integer> promisedHere = candidates.stream().filter(c -> promised.contains(keyOf(c)))
                .map(PlaceCandidate::index).collect(Collectors.toCollection(TreeSet::new));
        List<PlaceCandidate> withIntent = IntentHints.apply(selection, candidates);
        PlanState s = PlanState.of(selection, withIntent, args);
        s.continueFrom(earlier);
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
            s.days.forEach(d -> {
                d.morningOnlyFirst = true;
                d.cafeCap = ScheduleTuning.CAFES_PER_DAY_PICKED;
            });
            DayPicker.prepare(s, args.hub(), preferred, promisedHere);
            s.days.forEach(d -> reserved.addAll(d.placeIndices));
            s.days.forEach(d -> {
                double[] anchor = DayPicker.anchorOf(s, d);
                if (anchor != null) notes.anchors.put(d.dayNumber, anchor);
            });
        } else {
            reserved.addAll(placed);
            SubstitutionRepair.dedupeMainStepsAcrossItinerary(s.days, s.candidates, s.spare);
            LodgingPlanner.assignHubsAndLodging(s.days, s.candidates, args.hub());
        }
        MealPlanner.ensureCafeStops(s.days, s.candidates, s.spare, s.cfg, s.transportPref, s.earlier().brands());
        s.beginScheduling();

        List<StepData> steps = new ArrayList<>();
        CandidateLookup lookup = new CandidateLookup(s.candidates);
        for (int i = 0; i < s.days.size(); i++) {
            DayState day = s.days.get(i);
            Integer previousLodging = i > 0 ? s.days.get(i - 1).accommodationIndex : null;
            if (choosesDays) {
                boolean endsTrip = DayState.endsTrip(s.days, i);
                Set<Integer> today = chooseToday(s, day, previousLodging, endsTrip, preferred, daySelection);
                notes.shortfall.put(day.dayNumber,
                        DayPicker.fill(s, day, previousLodging, endsTrip, today.isEmpty() ? preferred : today,
                                notes.lastDinnerKind));
                reserved.addAll(day.placeIndices);
                notes.picks.put(day.dayNumber, day.placeIndices.stream()
                        .map(idx -> byIndex(s.candidates, idx)).filter(Objects::nonNull)
                        .map(SerialPlanner::keyOf).toList());
            }
            List<StepData> daySteps = completeDay(s, day, previousLodging, notes.reasons, choosesDays);
            steps.addAll(daySteps);
            notes.lastDinnerKind = dinnerKind(daySteps, lookup);
            // 거리 트림과 끼니 교체로 뺀 장소는 사용 표시가 풀린다. 그날에는 다시 쓸 수 있지만 다른 날의 빈자리로 들어가면 안 된다.
            s.usedIndices.addAll(reserved);
        }
        if (!summary) {
            return new RouteOptimizer.ScheduleResult(AlternativeBuilder.pruneUsedAndRenumber(steps), List.of());
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

    /** 선택기가 고른 장소 가운데 그날 허용 후보만 남긴다. 비면 날짜 전체의 선택 장소를 그대로 쓴다. */
    private static Set<Integer> chooseToday(PlanState s, DayState day, Integer previousLodging, boolean endsTrip,
                                            Set<Integer> preferred, DaySelection daySelection) {
        if (daySelection.selector() == DaySelector.NONE) return Set.of();
        DaySelector.DayOptions options = DayPicker.options(s, day, previousLodging, endsTrip, preferred,
                daySelection.dayCount());
        if (options == null) return Set.of();
        Set<Integer> chosen;
        try {
            chosen = daySelection.selector().choose(options);
        } catch (RuntimeException e) {
            // 선택기 실패로 생성을 실패시키지 않는다. 그날은 날짜 직접 선택이 혼자 고른다.
            log.warn("날짜별 선택기 실패 day={}: {}", day.dayNumber, e.getMessage());
            return Set.of();
        }
        Set<Integer> allowed = chosen.stream().filter(options::allows).collect(Collectors.toCollection(TreeSet::new));
        log.info("날짜별 선택기 day={}: 고름 {} / 허용 후보 안 {}", day.dayNumber, chosen.size(), allowed.size());
        return allowed;
    }

    private static String dinnerKind(List<StepData> daySteps, CandidateLookup lookup) {
        return daySteps.stream()
                .filter(st -> st.place() != null && lookup.isMeal(st) && InvariantChecker.inMealWindow(
                        ScheduleTimes.toMinutesOrZero(st.startTime()), ScheduleTuning.DINNER_START,
                        ScheduleTuning.DINNER_END))
                .reduce((first, second) -> second).map(lookup::mealKind).orElse(null);
    }

    /** @param nearbySpareOnly 그날 기준 장소에서 먼 예비 장소를 그날 보충에서 뺀다 */
    private static List<StepData> completeDay(PlanState s, DayState day, Integer previousLodging,
                                              Map<DroppedOn, DropReason> reasons, boolean nearbySpareOnly) {
        Set<Integer> droppedToday = new HashSet<>();
        List<StepData> steps = scheduleDay(s, day, previousLodging, reasons, droppedToday, nearbySpareOnly);
        if (nearbySpareOnly && placeByOpeningHours(s, day)) {
            steps = scheduleDay(s, day, previousLodging, reasons, droppedToday, nearbySpareOnly);
        }
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

    /**
     * 첫 확정 뒤 늦게 여는 낮 볼거리와 영업시간에 걸려 빠진 장소를 영업시간에 맞는 시간대 맨 앞에 고정한다. 경로 순서는 영업시간을
     * 보지 않아 늦게 여는 곳이 아침 첫 자리에 놓이고, 사용자가 고른 곳과 기본 영업시간으로 보는 곳은 하루 확정이 빼지도 않는다.
     *
     * @return 다시 넣거나 시간대를 정한 장소가 있으면 true
     */
    static boolean placeByOpeningHours(PlanState s, DayState day) {
        int morningStart = s.cfg.forDay(day).morningStart;
        boolean placed = false;
        for (Integer idx : day.placeIndices) {
            PlaceCandidate c = byIndex(s.candidates, idx);
            if (c == null || day.openingSlots.containsKey(idx) || !CandidatePolicy.roleOf(c).isVisit()) continue;
            DayState.Slot slot = slotFor(c, day, morningStart);
            if (slot == DayState.Slot.AFTERNOON_FIRST && opensWithin(c, day, slot, morningStart)) {
                day.openingSlots.put(idx, slot);
                placed = true;
            }
        }
        for (DayState.Drop drop : day.dropped()) {
            if (drop.reason() != DropReason.CLOSED_AT_TIME && drop.reason() != DropReason.LATE_START) continue;
            if (day.openingSlots.containsKey(drop.index())) continue;
            PlaceCandidate c = byIndex(s.candidates, drop.index());
            if (c == null) continue;
            DayState.Slot slot = slotFor(c, day, morningStart);
            if (!opensWithin(c, day, slot, morningStart)) continue;
            day.openingSlots.put(drop.index(), slot);
            addToDay(day, drop.index(), s.spare, s.usedIndices);
            placed = true;
        }
        return placed;
    }

    private static DayState.Slot slotFor(PlaceCandidate c, DayState day, int morningStart) {
        if (DayTiming.timePreference(c) == TimePreference.EVENING) return DayState.Slot.EVENING_FIRST;
        List<int[]> ranges = day.dayOfWeek == null ? null
                : OpeningHoursText.rangesFor(DayTiming.openingHoursOf(c), day.dayOfWeek);
        if (ranges == null || ranges.isEmpty()) return DayState.Slot.MORNING_FIRST;
        int opens = ranges.get(0)[0];
        if (opens >= ScheduleTuning.DINNER_START) return DayState.Slot.EVENING_FIRST;
        return opens > morningStart + ScheduleTuning.OPENING_WAIT_LIMIT_MINUTES
                ? DayState.Slot.AFTERNOON_FIRST : DayState.Slot.MORNING_FIRST;
    }

    /** 그 시간대가 시작할 때 열지 않는 곳을 다시 넣으면 다른 장소의 예산만 차지하고 다시 빠진다. */
    private static boolean opensWithin(PlaceCandidate c, DayState day, DayState.Slot slot, int morningStart) {
        if (day.dayOfWeek == null) return true;
        int slotStart = switch (slot) {
            case MORNING_FIRST -> morningStart;
            case AFTERNOON_FIRST -> ScheduleTuning.LUNCH_START + ScheduleTuning.DINING_VISIT_MINUTES;
            case EVENING_FIRST -> ScheduleTuning.DINNER_START + ScheduleTuning.DINING_VISIT_MINUTES;
        };
        Integer openFrom = OpeningHoursText.earliestStart(DayTiming.openingHoursOf(c), day.dayOfWeek, slotStart,
                DayTiming.resolveVisitMinutes(c));
        return openFrom == null || (openFrom >= 0 && openFrom - slotStart <= ScheduleTuning.OPENING_WAIT_LIMIT_MINUTES);
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
