package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.global.util.GeoUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.byIndex;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.toMinutesOrZero;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.DINNER_END;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.DINNER_START;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.LUNCH_START;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.distanceLimits;

/**
 * 확정된 일정이 불변식을 지켰는지 판정만 하고 고치지 않는다.
 * 검사와 수리가 섞이면 검사 항목 하나 늘리는 데도 수리 로직을 건드리게 되고, 검사망에 없는 항목은 조용히 깨진다.
 *
 * <p>검사 항목은 기계적 축(슬롯 충족·거리·종료 시각)에 한정된다.
 * 빈 시간·메뉴 반복·하루 관광지 수 같은 여행자 체감 축은 보지 않는다.
 */
final class InvariantChecker {

    /**
     * 거리는 예산과 같은 직선 기준이다.
     * 요약 로그는 표시값과 헷갈리지 않도록 도로 환산값을 함께 낸다.
     */
    Map<Integer, DayStatus> evaluate(List<DayState> days, List<StepData> steps,
                                     String transportPref, List<PlaceCandidate> candidates, String pace) {
        double[] limits = distanceLimits(transportPref, pace);
        Map<Integer, List<StepData>> byDay = steps.stream()
                .collect(Collectors.groupingBy(StepData::dayNumber, LinkedHashMap::new, Collectors.toList()));

        Map<Integer, DayStatus> result = new LinkedHashMap<>();
        for (int i = 0; i < days.size(); i++) {
            DayState day = days.get(i);
            List<StepData> daySteps = byDay.getOrDefault(day.dayNumber, List.of());
            if (daySteps.isEmpty()) continue;
            boolean lastDay = DayState.endsTrip(days, i);

            double total = 0;
            double maxLeg = 0;
            for (int s = 0; s < daySteps.size(); s++) {
                StepData step = daySteps.get(s);
                if (step.transportationDistance() == null || isOutsideDayBudget(daySteps, s, i == 0 && !day.resumesAfterTransfer, lastDay)) continue;
                // 저장된 이동거리는 도로 환산값이라 예산과 같은 직선 단위로 되돌려 비교한다.
                double straight = step.transportationDistance().doubleValue() / GeoUtils.ROAD_DISTANCE_FACTOR;
                total += straight;
                maxLeg = Math.max(maxLeg, straight);
            }

            // 도착·출발 시각 때문에 끼니 시간대를 못 쓰는 날은 그 끼니를 충족으로 본다
            Predicate<StepData> isMeal = mealJudge(day, candidates);
            result.put(day.dayNumber, new DayStatus(
                    !day.lunchInWindow() || hasMealInWindow(daySteps, LUNCH_START, day.lunchWindowEnd(), isMeal),
                    hasMealInWindow(daySteps, DINNER_START, day.dinnerWindowEnd(), isMeal),
                    (!lastDay || departsAfterDinner(daySteps, candidates, day)) && day.dinnerInWindow(),
                    day.tooShortForSightseeing() || daySteps.stream().anyMatch(st -> st.place() != null
                            && "ATTRACTION".equals(PlaceCategoryConstants.majorCategory(st.place().category()))),
                    day.accommodationIndex != null,
                    !lastDay,
                    total, maxLeg,
                    total <= limits[1] && maxLeg <= limits[0],
                    daySteps.stream().mapToInt(st -> toMinutesOrZero(st.endTime())).max().orElse(0),
                    day.endsEarlyByDeparture(),
                    day.startNotBefore != null && day.startNotBefore >= DINNER_END,
                    missesDeparture(daySteps, candidates, day)));
        }
        return result;
    }

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

    /**
     * 식사 시간창(시작 60분 전까지 허용) 안에 식사 가능한 장소가 있는지.
     */
    static boolean hasMealInWindow(List<StepData> daySteps, int windowStart, int windowEnd) {
        return hasMealInWindow(daySteps, windowStart, windowEnd, InvariantChecker::isMealByName);
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

    /** 그날 방문 목록에서만 찾는다. 전체 후보에서 찾으면 같은 이름의 다른 행이 잡힌다. */
    static Predicate<StepData> mealJudge(DayState day, List<PlaceCandidate> candidates) {
        Map<String, PlaceCandidate> byName = new LinkedHashMap<>();
        if (candidates != null) {
            for (Integer idx : day.placeIndices) {
                PlaceCandidate c = byIndex(candidates, idx);
                if (c != null && c.name() != null) byName.putIfAbsent(c.name(), c);
            }
        }
        return s -> {
            PlaceCandidate c = byName.get(s.place().name());
            return c != null ? c.mealEligible() : isMealByName(s);
        };
    }

    private static boolean isMealByName(StepData s) {
        return PlaceCategoryConstants.isMealPlace(s.place().name(), s.place().category());
    }

    /** 사용자가 준 출발 시각에 맞춰 허브에 닿지 못하는 날. */
    static boolean missesDeparture(List<StepData> daySteps, List<PlaceCandidate> candidates, DayState day) {
        if (day.departureDeadline == null || day.departureHubIndex == null) return false;
        String hubName = candidates != null && byIndex(candidates, day.departureHubIndex) != null
                ? byIndex(candidates, day.departureHubIndex).name() : null;
        return daySteps.stream()
                .filter(s -> s.place() != null && hubName != null && Objects.equals(s.place().name(), hubName))
                .anyMatch(s -> toMinutesOrZero(s.startTime()) > day.departureDeadline);
    }

    static boolean departsAfterDinner(List<StepData> daySteps, List<PlaceCandidate> candidates, DayState day) {
        if (day.departureHubIndex == null) return false;
        String hubName = candidates != null && byIndex(candidates, day.departureHubIndex) != null
                ? byIndex(candidates, day.departureHubIndex).name() : null;
        return daySteps.stream()
                .filter(s -> s.place() != null)
                .filter(s -> hubName != null
                        ? Objects.equals(s.place().name(), hubName)
                        : "TRANSIT_HUB".equals(PlaceCategoryConstants.majorCategory(s.place().category())))
                .anyMatch(s -> toMinutesOrZero(s.startTime()) >= DINNER_END);
    }
}
