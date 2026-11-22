package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;

import java.util.List;
import java.util.function.Predicate;

import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.toMinutesOrZero;

/** 일정 규칙과 저장 변환이 함께 쓰는 스텝 구간 판정. 같은 구간을 두 곳이 다르게 보면 거리 예산과 저장 이동이 갈린다. */
final class InvariantChecker {

    /** 하루 이동 예산에 넣지 않는 구간. 관문 공항 연결과 도시 간 이동은 그날 동선이 아니다. */
    static boolean isOutsideDayBudget(List<StepData> daySteps, int i, boolean firstDay, boolean lastDay) {
        return isIntercityLeg(daySteps.get(i)) || isOverseasHubTransfer(daySteps, i, firstDay, lastDay);
    }

    static boolean isIntercityLeg(StepData step) {
        return step.transportationMode() != null
                && ScheduleTuning.INTERCITY_LEG_MODES.contains(step.transportationMode());
    }

    /**
     * 첫날 해외 도착 허브에서 나오는 구간과 마지막 날 출발 허브로 가는 구간. 해외 관문 공항은 시내에서 30~60km라
     * 이 구간을 거리 예산에 넣으면 모든 해외 일정의 도착·출발일이 거리 초과가 된다. {@link CandidateGeo#budgetCoords}와 기준이 같아야 한다.
     */
    static boolean isOverseasHubTransfer(List<StepData> daySteps, int i, boolean firstDay, boolean lastDay) {
        if (lastDay && i == daySteps.size() - 1 && isOverseasHub(daySteps.get(i))) return true;
        return firstDay && i == 1 && isOverseasHub(daySteps.get(0));
    }

    private static boolean isOverseasHub(StepData step) {
        return step.place() != null
                && PlaceCategoryConstants.isOverseasHub(step.place().country(), step.place().category());
    }

    /** @param isMeal 끼니 장소 판정. 끼니를 고른 쪽({@link PlaceCandidate#mealEligible})과 같아야 한다 */
    static boolean hasMealInWindow(List<StepData> daySteps, int windowStart, int windowEnd,
                                   Predicate<StepData> isMeal) {
        return daySteps.stream().anyMatch(s -> s.place() != null && isMeal.test(s)
                && inMealWindow(toMinutesOrZero(s.startTime()), windowStart, windowEnd));
    }

    /** 끼니 시간창은 시작 60분 전부터 본다. 끼니 유무와 끼니 종류 판정이 같은 창을 써야 한다. */
    static boolean inMealWindow(int start, int windowStart, int windowEnd) {
        return start >= windowStart - 60 && start <= windowEnd;
    }
}
