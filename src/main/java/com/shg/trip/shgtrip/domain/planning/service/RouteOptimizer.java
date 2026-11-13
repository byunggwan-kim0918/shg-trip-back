package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.ScheduleArgs;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.centroidOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.coordsOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.placeKey;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.addToDay;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.collectUsedIndices;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.openOnDayFilter;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.byIndex;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.*;

/**
 * AI가 고른 장소의 날짜 배분·순서·시각을 결정론적으로 확정한다. 판정은 InvariantChecker에 맡기고 여기서는 수리만 한다.
 */
@Slf4j
@Component
public class RouteOptimizer {

    private final InvariantChecker invariantChecker = new InvariantChecker();

    // ========================================================================================
    // Repair & Schedule: Sonnet의 날짜별 구성을 힌트로 받아 하드 제약을 수리하고 순서·시각·교통·대안을 확정한다.
    // LLM을 다시 부르지 않으며 notes는 비워 두고 Haiku가 나중에 채운다.
    // ========================================================================================

    /**
     * 확정된 스텝과 함께 끝내 해소하지 못한 품질 문제를 돌려준다. 호출부는 일정을 저장하되 이 문제를 사용자에게 알린다.
     */
    public ScheduleResult schedule(SelectionOutput selection, List<PlaceCandidate> candidates, ScheduleArgs args) {
        if (args.cities() != null) {
            return CityBlockPlanner.schedule(selection, candidates, args, this::scheduleWithinCity);
        }
        return scheduleWithinCity(selection, candidates, args);
    }

    private ScheduleResult scheduleWithinCity(SelectionOutput selection, List<PlaceCandidate> candidates,
                                              ScheduleArgs args) {
        List<PlaceCandidate> withIntent = IntentHints.apply(selection, candidates);
        ScheduleResult result = scheduleCandidates(selection, withIntent, args);
        IntentHints.logOutcome(withIntent, result.steps());
        return result;
    }

    /** 첫날 시작 시각(분). 도착 시각이 없으면 null. */
    public static Integer firstDayStart(ScheduleArgs args) {
        return ScheduleTimes.firstDayStart(args);
    }

    /** 마지막 날 출발 허브 도착 기한(분). 출발 시각이 없으면 null. 해외는 국제선 체크인만큼 더 이르다. */
    public static Integer departureHubDeadline(ScheduleArgs args) {
        return ScheduleTimes.departureHubDeadline(args);
    }

    private ScheduleResult scheduleCandidates(SelectionOutput selection, List<PlaceCandidate> candidates,
                                              ScheduleArgs args) {
        PlanState s = PlanState.of(selection, candidates, args);

        // 날짜 배분을 좌표로 먼저 정한다. 아래 반응적 수리는 모델이 정한 날짜를 사후에 밀고 당길 뿐이라 넓게 걸친 날을 되돌리지 못한다.
        // 1D DP로 지리적으로 끊기는 지점에서 가변 크기로 자르고 하루 상한은 벌점으로만 둔다. 개수는 뒤의 pace quota 수리가 맞춘다.
        // 분할이 총이동을 줄이지 못하면 기각하고 모델의 배분을 쓴다(estimateTotalTourKm).
        DayPartitioner.repartitionDaysByGeography(s.days, s.candidates, s.pairs, s.maxPerDay, args.hub());
        DayRebalancer.repairUntilStable(s);
        LodgingPlanner.assignHubsAndLodging(s.days, s.candidates, args.hub());
        repairComposition(s);
        settleDayContents(s);

        s.beginScheduling();
        Map<Integer, Integer> protectedHome = protectedHomes(s.days, s.candidates);
        List<StepData> steps = DayScheduler.scheduleAll(s);
        steps = restoreDroppedProtected(s, steps, protectedHome);
        steps = enforceFinalInvariants(s, steps);
        List<String> notices = invariantChecker.summarize(s.days, steps, s.pace, s.transportPref, s.candidates, s.cfg);

        List<StepData> finalSteps = pruneAlternativesAndRenumber(steps);
        log.info("RouteOptimizer.schedule 완료: {}개 step, {}개 day", finalSteps.size(), s.days.size());
        return new ScheduleResult(finalSteps, notices);
    }

    /**
     * 확정 스텝 + 끝내 해소하지 못한 품질 문제(사용자 안내 문구).
     * notices가 비어 있으면 모든 구조적 불변식을 만족한 일정이다.
     */
    public record ScheduleResult(List<StepData> steps, List<String> notices) {}

    private static void repairComposition(PlanState s) {
        // swap이라 개수 중립이어서 fixpoint 수렴 뒤 1회만 돈다.
        SubstitutionRepair.repairClosedDayPlaces(s.days, s.candidates, s.spare, s.startDate, s.pairs);
        // 허브·숙소는 중복 정리 대상이 아니다.
        SubstitutionRepair.dedupeMainStepsAcrossItinerary(s.days, s.candidates, s.spare);
        // 같은 세부 유형·경유형 반복을 상한으로 자르고 다른 유형으로 보충한다.
        DiversityRepair.enforceTypeDiversity(s.days, s.candidates, s.spare, s.pairs);
        // 사용자가 고른 카테고리 중 일정에 0개인 것을 spare에서 보충한다.
        DiversityRepair.ensureCategoryCoverage(s.days, s.candidates, s.spare, s.userCategories, s.maxPerDay);
        // 보충해 넣은 것을 뒤의 거리 트림이 다시 빼지 않게, 각 카테고리의 유일 대표를 표시한다.
        DiversityRepair.markCoverageCritical(s.days, s.candidates, s.userCategories);
    }

    private static void settleDayContents(PlanState s) {
        // 하루 용량을 넘는 활동을 인접 day로 이월한다. 실패분은 scheduleDay가 트림한다.
        DayRebalancer.repairDayCapacity(s.days, s.candidates, s.cfg, s.maxPerDay);
        // 카페 스톱은 이월 뒤에 정한다. 앞에서 정하면 방금 맨 뒤에 붙인 카페가 이월 1순위가 되어
        // 원래 날은 카페가 0개, 옮겨 간 날은 오후 고정이 풀린 카페가 된다.
        MealPlanner.ensureCafeStops(s.days, s.candidates, s.spare, s.cfg, s.transportPref);
        IntentHints.moveEveningOffDepartureDay(s.days, s.candidates);
    }

    /**
     * 앞 보정은 확정 전 기준이라 scheduleDay의 버킷 용량·거리 트림이 다시 깰 수 있다.
     * 확정 스텝으로 {@link FinalRepair}를 우선순위 순으로 돌리고, 고친 것이 있으면 일정 전체를 다시 스케줄한다.
     */
    private List<StepData> enforceFinalInvariants(PlanState s, List<StepData> steps) {
        InvariantMemo memo = new InvariantMemo();
        for (int pass = 0; pass < MAX_INVARIANT_PASSES; pass++) {
            // 낮은 우선순위 수리가 식사·거리 불변식을 깨면 되돌릴 수 있게 패스 시작 상태를 잡아 둔다.
            PlanState.Checkpoint checkpoint = s.checkpoint();
            Map<Integer, DayStatus> before = evaluate(s, steps);

            RepairOutcome outcome = FinalRepair.applyFirst(s, steps, memo);
            if (!outcome.changed()) break;

            s.resetSpare();
            steps = DayScheduler.scheduleAll(s);

            // 우선순위 역전 검사: 낮은 등급 수리 때문에 식사·거리 불변식이 새로 깨졌으면 그 수리를 취소한다.
            if (outcome.lowestPriorityRepair() != null) {
                Map<Integer, DayStatus> after = evaluate(s, steps);
                String regression = invariantChecker.findPriorityRegression(before, after);
                if (regression != null) {
                    log.info("우선순위 역전 — 수리 취소: {} (원인: {})", outcome.lowestPriorityRepair(), regression);
                    s.restore(checkpoint);
                    memo.rejectAll(outcome.insertedIndices());
                    steps = DayScheduler.scheduleAll(s);
                    memo.clearPending();
                    continue;
                }
            }
            // 이번 패스에 넣었는데 거리·시간 가드에 잘린 장소는 "그 day에는 못 넣는다"로 기억한다.
            // 기억하지 않으면 보충 → 트림 → 보충이 매 패스 반복되며 수렴하지 않는다.
            memo.settle(s.days);
        }
        return steps;
    }

    private Map<Integer, DayStatus> evaluate(PlanState s, List<StepData> steps) {
        return invariantChecker.evaluate(s.days, steps, s.transportPref, s.candidates, s.pace);
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
    private List<StepData> restoreDroppedProtected(PlanState s, List<StepData> steps,
                                                   Map<Integer, Integer> protectedHome) {
        int tries = 0;
        for (Map.Entry<Integer, Integer> entry : protectedHome.entrySet()) {
            int idx = entry.getKey();
            PlaceCandidate c = byIndex(s.candidates, idx);
            double[] at = coordsOf(c);
            if (at == null || collectUsedIndices(s.days).contains(idx)) continue;
            List<DayState> targets = s.days.stream()
                    .filter(d -> d.dayNumber != entry.getValue() && !d.tooShortForSightseeing())
                    .filter(d -> openOnDayFilter(s.startDate, d.dayNumber).test(c))
                    .sorted(Comparator.comparingDouble(d -> {
                        double[] center = centroidOf(d.placeIndices, s.candidates);
                        return center == null ? Double.MAX_VALUE : GeoUtils.haversine(center, at);
                    }))
                    .toList();
            Map<Integer, DayStatus> before = evaluate(s, steps);
            Set<Integer> keptBefore = placedProtected(s.days, protectedHome);
            for (DayState target : targets) {
                if (tries++ >= PROTECTED_RESTORE_MAX_TRIES) return steps;
                PlanState.Checkpoint checkpoint = s.checkpoint();
                addToDay(target, idx, s.spare, s.usedIndices);
                s.resetSpare();
                List<StepData> tried = DayScheduler.scheduleAll(s);
                String regression = !target.placeIndices.contains(idx) ? "다시 잘림"
                        : !placedProtected(s.days, protectedHome).containsAll(keptBefore) ? "다른 핵심 장소 잘림"
                        : invariantChecker.findPriorityRegression(before, evaluate(s, tried));
                if (regression == null) {
                    log.info("잘린 핵심 장소 복원: day={} → day={} {}", entry.getValue(), target.dayNumber, c.name());
                    steps = tried;
                    break;
                }
                log.info("핵심 장소 복원 보류: day={} {} ({})", target.dayNumber, c.name(), regression);
                s.restore(checkpoint);
            }
        }
        return steps;
    }

    private static Set<Integer> placedProtected(List<DayState> days, Map<Integer, Integer> protectedHome) {
        Set<Integer> placed = collectUsedIndices(days);
        placed.retainAll(protectedHome.keySet());
        return placed;
    }

    private static List<StepData> pruneAlternativesAndRenumber(List<StepData> steps) {
        // 대안은 day 순서대로 생성돼 뒤쪽 day 보충으로 본일정에 들어간 장소가 앞쪽 대안에 남을 수 있다.
        // 최종 스텝 기준으로 걸러낸다.
        Set<String> usedPlaceKeys = steps.stream()
                .map(StepData::place)
                .filter(Objects::nonNull)
                .map(p -> placeKey(p.name(), p.address()))
                .collect(Collectors.toSet());
        int order = 1;
        List<StepData> renumbered = new ArrayList<>(steps.size());
        for (StepData step : steps) {
            renumbered.add(AlternativeBuilder.withoutUsedAlternatives(step, usedPlaceKeys).withStepOrder(order++));
        }
        return renumbered;
    }
}
