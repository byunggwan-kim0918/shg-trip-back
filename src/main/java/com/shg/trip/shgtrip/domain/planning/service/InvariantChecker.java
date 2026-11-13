package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.byIndex;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.formatMinutes;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.toMinutesOrZero;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.DEFAULT_EVENING_CAP_MINUTES;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.DINNER_END;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.DINNER_START;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.EARLY_FINISH_MINUTES;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.LARGE_GAP_MINUTES;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.LUNCH_END;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.LUNCH_START;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.distanceLimits;

/**
 * 확정된 일정이 불변식을 지켰는지 판정만 하고 고치지 않는다.
 * 검사와 수리가 섞이면 검사 항목 하나 늘리는 데도 수리 로직을 건드리게 되고, 검사망에 없는 항목은 조용히 깨진다.
 *
 * <p>검사 항목은 기계적 축(슬롯 충족·거리·종료 시각)에 한정된다.
 * 빈 시간·메뉴 반복·하루 관광지 수 같은 여행자 체감 축은 보지 않는다.
 */
@Slf4j
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

    /** 하루 이동 예산에 넣지 않는 구간. 관문 공항 연결과 도시 간 열차·비행기는 그날 동선이 아니다. */
    static boolean isOutsideDayBudget(List<StepData> daySteps, int i, boolean firstDay, boolean lastDay) {
        return isIntercityLeg(daySteps.get(i)) || isOverseasHubTransfer(daySteps, i, firstDay, lastDay);
    }

    static boolean isIntercityLeg(StepData step) {
        return step.transportationMode() != null
                && ScheduleTuning.INTERCITY_LEG_MODES.contains(step.transportationMode());
    }

    /**
     * 첫날 해외 도착 허브에서 나오는 구간과 마지막 날 출발 허브로 가는 구간. 해외 관문 공항은 시내에서 30~60km라
     * 이 구간을 거리 예산에 넣으면 모든 해외 일정의 도착·출발일이 거리 초과가 된다. RouteOptimizer 거리 예산과 기준이 같아야 한다.
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
     * 이전에 충족하던 상위 불변식(식사 슬롯 → 하루 거리 예산)이 깨졌으면 그 사유를, 아니면 null을 반환한다.
     * 하위 불변식(커버리지·유형 상한) 악화는 보지 않는다.
     */
    String findPriorityRegression(Map<Integer, DayStatus> before, Map<Integer, DayStatus> after) {
        for (Map.Entry<Integer, DayStatus> entry : after.entrySet()) {
            DayStatus prev = before.get(entry.getKey());
            if (prev == null) continue;
            DayStatus now = entry.getValue();
            if (prev.lunch() && !now.lunch()) return "day" + entry.getKey() + " 점심 소실";
            if (prev.dinner() && !now.dinner() && now.dinnerRequired()) return "day" + entry.getKey() + " 저녁 소실";
            if (prev.lodging() && !now.lodging() && now.lodgingRequired()) return "day" + entry.getKey() + " 숙소 소실";
            if (prev.withinBudget() && !now.withinBudget()) {
                return "day" + entry.getKey() + " 거리 예산 초과(" + String.format("%.0f", now.totalKm()) + "km)";
            }
            if (prev.attraction() && !now.attraction()) return "day" + entry.getKey() + " 관광 소실";
        }
        return null;
    }

    /**
     * 흩어진 보정 로그만으로는 최종 일정을 읽을 수 없어 루프 종료 후 day별 한 줄 요약을 남긴다.
     *
     * @return 남은 위반의 안내 문구. 비어 있으면 모든 구조적 불변식을 만족한 일정이다
     */
    List<String> summarize(List<DayState> days, List<StepData> steps, String pace,
                           String transportPref, List<PlaceCandidate> candidates, ScheduleConfig cfg) {
        Map<Integer, DayStatus> status = evaluate(days, steps, transportPref, candidates, pace);
        double[] limits = distanceLimits(transportPref, pace);
        int eveningCap = cfg != null ? cfg.eveningCap : DEFAULT_EVENING_CAP_MINUTES;
        int violationDays = 0;
        List<String> notices = new ArrayList<>();

        for (Map.Entry<Integer, DayStatus> entry : status.entrySet()) {
            DayStatus st = entry.getValue();
            List<String> violations = new ArrayList<>();
            int dayNumber = entry.getKey();
            if (!st.lunch()) {
                violations.add("점심 없음");
                notices.add(dayNumber + "일차에 점심 식사를 넣지 못했어요. 주변 식당 데이터가 부족합니다.");
            }
            if (st.dinnerRequired() && !st.dinner()) {
                violations.add("저녁 없음");
                notices.add(dayNumber + "일차에 저녁 식사를 넣지 못했어요. 주변 식당 데이터가 부족합니다.");
            }
            if (st.lodgingRequired() && !st.lodging()) {
                violations.add("숙소 없음");
                notices.add(dayNumber + "일차 숙소를 찾지 못했어요. 직접 추가해 주세요.");
            }
            if (!"relaxed".equals(pace) && !st.attraction()) {
                violations.add("관광 없음");
                notices.add(dayNumber + "일차에 넣을 만한 관광지를 찾지 못했어요.");
            }
            if (!st.withinBudget()) {
                violations.add("거리 초과");
                notices.add(dayNumber + "일차 이동이 "
                        + String.format("%.0f", st.totalKm() * GeoUtils.ROAD_DISTANCE_FACTOR)
                        + "km로 다소 깁니다.");
            }
            if (st.endMinutes() > 0 && st.endMinutes() < EARLY_FINISH_MINUTES && !st.endsEarlyByDeparture()) {
                violations.add("조기 종료(" + formatMinutes(st.endMinutes()) + ")");
                notices.add(dayNumber + "일차 일정이 " + formatMinutes(st.endMinutes()) + "에 일찍 끝납니다.");
            }
            if (st.missedDeparture()) {
                violations.add("출발 시각 초과");
                notices.add(dayNumber + "일차 출발 시각에 맞추기 어려워요. 출발 시간을 다시 확인해 주세요.");
            }
            if (st.endMinutes() > eveningCap && !st.lateByArrival()) {
                violations.add("늦은 종료(" + formatMinutes(st.endMinutes()) + ")");
                notices.add(dayNumber + "일차 일정이 " + formatMinutes(st.endMinutes()) + "까지 이어집니다.");
            }
            // 고정된 식사 슬롯 사이에 먼 곳을 억지로 끼우면 "앉아서 3시간"이 "2.5시간 운전"이 된다.
            // 그래서 메우지 못한 긴 공백은 자유시간으로 알린다.
            int gap = maxIntraDayGap(steps, dayNumber);
            if (gap >= LARGE_GAP_MINUTES) {
                violations.add("긴 공백(" + gap + "분)");
                notices.add(dayNumber + "일차 중간에 " + (gap / 60) + "시간 " + (gap % 60)
                        + "분 비는 시간이 있어요. 주변에 넣을 만한 곳을 찾지 못했습니다.");
            }
            if (!violations.isEmpty()) violationDays++;

            log.info("불변식 요약 day={}: 점심={} 저녁={}{} 숙소={}{} 관광={} 거리={}km(표시 {}km, 상한 {}km) 최대구간={}km 종료={} | {}",
                    entry.getKey(),
                    st.lunch() ? "O" : "X",
                    st.dinner() ? "O" : "X",
                    st.dinnerRequired() ? "" : "(불필요)",
                    st.lodging() ? "O" : "X",
                    st.lodgingRequired() ? "" : "(불필요)",
                    st.attraction() ? "O" : "X",
                    String.format("%.0f", st.totalKm()),
                    String.format("%.0f", st.totalKm() * GeoUtils.ROAD_DISTANCE_FACTOR),
                    String.format("%.0f", limits[1]),
                    String.format("%.0f", st.maxLegKm()),
                    formatMinutes(st.endMinutes()),
                    violations.isEmpty() ? "위반 없음" : String.join(", ", violations));
        }
        if (violationDays > 0) {
            log.warn("불변식 미해소 day {}개 / 전체 {}개", violationDays, status.size());
        }
        return notices;
    }

    /**
     * 식사 시간창(시작 60분 전까지 허용) 안에 식사 가능한 장소가 있는지.
     * {@link RouteOptimizer}와 정의가 다르면 수리 뒤에도 검사가 위반을 계속 잡아 무한 루프가 된다.
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

    /**
     * 그 day에서 연속한 두 스텝 사이의 최대 공백(분). 스텝이 1개 이하면 0.
     */
    static int maxIntraDayGap(List<StepData> steps, int dayNumber) {
        List<StepData> daySteps = steps.stream()
                .filter(s -> s.dayNumber() == dayNumber)
                .sorted(java.util.Comparator.comparingInt(s -> toMinutesOrZero(s.startTime())))
                .toList();
        int max = 0;
        for (int i = 1; i < daySteps.size(); i++) {
            int gap = toMinutesOrZero(daySteps.get(i).startTime())
                    - toMinutesOrZero(daySteps.get(i - 1).endTime());
            if (gap > max) max = gap;
        }
        return max;
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
