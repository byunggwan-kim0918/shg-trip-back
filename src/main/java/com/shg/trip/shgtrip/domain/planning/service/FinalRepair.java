package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import lombok.extern.slf4j.Slf4j;

import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.centroidOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.isVisitableFill;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.nameOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.subTypeOf;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.timePreference;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.addToDay;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.insertableFilter;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.pickNearbyCandidate;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.formatMinutes;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.toMinutesOrZero;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.EARLY_FINISH_MINUTES;

/**
 * 확정 스텝으로 하루 구성을 검사해 위반 day의 placeIndices를 고친다. 삽입이 거리·시간 가드를 통과하는지는 재스케줄이 판정한다.
 *
 * <p>선언 순서가 우선순위이고 한 패스에서는 처음으로 무언가를 고친 수리에서 멈춘다. 전체 우선순위(높음→낮음)는
 * <b>식사 슬롯 &gt; 하루 거리 예산 &gt; 시간대 가드 &gt; 카테고리 커버리지 &gt; 유형 상한</b>이고,
 * 거리·시간대는 재스케줄이 강제하므로 하위 수리가 그 둘을 깨지 않게 제한한다.
 * <ul>
 *   <li>식사 슬롯을 채우는 스텝은 커버리지·유형 상한 교체 대상에서 뺀다</li>
 *   <li>유형 상한 교체는 같은 대분류 안에서만 한다</li>
 *   <li>커버리지 삽입은 day centroid 최근접을 고르고 구간/일일 예산을 넘기면 넣지 않는다</li>
 * </ul>
 * 그래도 상위 불변식이 깨지면 호출부가 체크포인트로 되돌린다.
 */
@Slf4j
enum FinalRepair {

    MEALS_AND_LODGING {
        @Override
        RepairOutcome apply(PlanState s, Pass pass) {
            boolean changed = false;
            for (int i = 0; i < s.days.size(); i++) {
                DayState day = s.days.get(i);
                List<StepData> daySteps = pass.stepsOf(day);
                if (daySteps.isEmpty()) continue;
                boolean lastDay = DayState.endsTrip(s.days, i);
                changed |= MealPlanner.ensureMealSlots(day, daySteps, s.candidates, s.spare, s.usedIndices,
                        s.maxPerDay, lastDay, s.startDate, s.transportPref, pass.memo(), s.cfg, pass.underTypeCap());
                changed |= LodgingPlanner.ensureAccommodation(day, s.candidates, lastDay);
            }
            return changed ? RepairOutcome.highPriority(pass.memo().pendingIndices()) : RepairOutcome.none();
        }
    },

    /** 시간대·구성 가드(관광 최소 1개, 조기 종료). */
    DAY_COMPOSITION {
        @Override
        RepairOutcome apply(PlanState s, Pass pass) {
            boolean changed = false;
            for (DayState day : s.days) {
                List<StepData> daySteps = pass.stepsOf(day);
                if (daySteps.isEmpty()) continue;
                Predicate<PlaceCandidate> fillable = pass.fillable(s, day);
                changed |= ensureAttraction(day, daySteps, s.candidates, s.spare, s.usedIndices, s.maxPerDay,
                        s.pace, fillable, pass.memo(), pass.lookup());
                changed |= ensureNotFinishingEarly(day, daySteps, s.candidates, s.spare, s.usedIndices,
                        s.maxPerDay, fillable.and(VarietyLedger.admitsOnDay(day, s.candidates)), pass.memo());
            }
            return changed ? RepairOutcome.highPriority(pass.memo().pendingIndices()) : RepairOutcome.none();
        }
    },

    CATEGORY_COVERAGE {
        @Override
        RepairOutcome apply(PlanState s, Pass pass) {
            if (DiversityRepair.reapplyCoverageOnFinalSteps(s.days, pass.steps(), s.candidates, s.spare,
                    s.usedIndices, s.userCategories, s.maxPerDay, s.startDate, pass.memo(), s.transportPref, s.cfg)) {
                return RepairOutcome.lowPriority("카테고리 커버리지 보충", pass.memo().pendingIndices());
            }
            return RepairOutcome.none();
        }
    },

    /** 유형 상한·같은 종류 연속. */
    TYPE_VARIETY {
        @Override
        RepairOutcome apply(PlanState s, Pass pass) {
            if (DiversityRepair.reapplyTypeCapOnFinalSteps(s.days, pass.steps(), s.candidates, s.spare,
                    s.usedIndices, s.startDate, pass.memo(), s.transportPref, s.cfg)) {
                return RepairOutcome.lowPriority("세부 유형 상한 교체", pass.memo().pendingIndices());
            }
            for (DayState day : s.days) {
                List<StepData> daySteps = pass.stepsOf(day);
                if (daySteps.isEmpty()) continue;
                if (DiversityRepair.breakConsecutiveSameMajor(day, daySteps, s.candidates, s.spare, s.usedIndices,
                        pass.fillable(s, day))) {
                    return RepairOutcome.lowPriority("같은 종류 연속 해소", pass.memo().pendingIndices());
                }
            }
            return RepairOutcome.none();
        }
    };

    abstract RepairOutcome apply(PlanState s, Pass pass);

    /** 우선순위 순으로 돌려 처음으로 무언가를 고친 수리의 결과. 아무것도 고치지 않았으면 none. */
    static RepairOutcome applyFirst(PlanState s, List<StepData> steps, InvariantMemo memo) {
        Pass pass = Pass.of(s, steps, memo);
        for (FinalRepair repair : values()) {
            RepairOutcome outcome = repair.apply(s, pass);
            if (outcome.changed()) return outcome;
        }
        return RepairOutcome.none();
    }

    /** 한 패스의 수리들이 같이 보는 확정 스텝과 유형 상한. 유형 수는 패스 시작 시점 기준이다. */
    record Pass(List<StepData> steps, Map<Integer, List<StepData>> byDay,
                Predicate<PlaceCandidate> underTypeCap, InvariantMemo memo, CandidateLookup lookup) {

        static Pass of(PlanState s, List<StepData> steps, InvariantMemo memo) {
            Map<Integer, List<StepData>> byDay = steps.stream()
                    .collect(Collectors.groupingBy(StepData::dayNumber, LinkedHashMap::new, Collectors.toList()));
            Map<String, Integer> globalTypeCount = DiversityRepair.countSubTypes(s.days, s.candidates);
            Predicate<PlaceCandidate> underTypeCap =
                    c -> !DiversityRepair.isSpecificType(subTypeOf(c))
                            || globalTypeCount.getOrDefault(subTypeOf(c), 0) < DiversityRepair.typeCapFor(c, s.days.size());
            return new Pass(steps, byDay, underTypeCap, memo, new CandidateLookup(s.candidates));
        }

        List<StepData> stepsOf(DayState day) {
            return byDay.getOrDefault(day.dayNumber, List.of());
        }

        Predicate<PlaceCandidate> fillable(PlanState s, DayState day) {
            return insertableFilter(day, s.candidates, s.startDate, s.transportPref, memo, s.cfg).and(underTypeCap);
        }
    }

    /**
     * 관광(ATTRACTION)이 하루도 없으면 보충한다. relaxed는 관광 수를 줄인 것이지 먹기만 하는 날을 고른 것이 아니라서
     * 식사·카페가 아닌 방문이 하나도 없을 때만 보충한다.
     */
    private static boolean ensureAttraction(DayState day, List<StepData> daySteps, List<PlaceCandidate> candidates,
                                            Deque<Integer> spare, Set<Integer> usedIndices, int maxPerDay,
                                            String pace, Predicate<PlaceCandidate> openOnDay,
                                            InvariantMemo memo, CandidateLookup lookup) {
        if (day.tooShortForSightseeing()) return false;
        boolean has = "relaxed".equals(pace)
                ? daySteps.stream().anyMatch(s -> s.place() != null && lookup.role(s).isVisit())
                : daySteps.stream().anyMatch(s -> s.place() != null
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
    private static boolean ensureNotFinishingEarly(DayState day, List<StepData> daySteps,
                                                   List<PlaceCandidate> candidates, Deque<Integer> spare,
                                                   Set<Integer> usedIndices, int maxPerDay,
                                                   Predicate<PlaceCandidate> openOnDay,
                                                   InvariantMemo memo) {
        int end = daySteps.stream()
                .filter(FinalRepair::isActivityStep)
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
}
