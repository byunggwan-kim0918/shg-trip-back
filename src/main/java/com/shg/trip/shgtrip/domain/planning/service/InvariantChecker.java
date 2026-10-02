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
import java.util.stream.Collectors;

import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.byIndex;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.formatMinutes;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.toMinutesOrZero;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.DEFAULT_EVENING_CAP_MINUTES;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.DINNER_END;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.DINNER_START;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.EARLY_FINISH_MINUTES;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.LUNCH_END;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.LUNCH_START;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.TRANSPORT_ABS_LIMITS;

/**
 * 확정된 일정이 불변식을 지켰는지 <b>판정만</b> 하는 클래스. 고치지는 않는다.
 *
 * <p>검사와 수리가 한 클래스에 있으면 검사 항목 하나를 늘리는 데도 4천 줄짜리 수리 로직을 건드리게
 * 된다. 실제로 그래서 "숙소 배정 여부"가 오랫동안 검사 항목에 없었고, 4일 전부 숙소가 없는 일정이
 * "위반 없음"으로 통과했다(실측 itinerary 60). <b>검사망에 없는 항목은 조용히 깨진다.</b>
 *
 * <p>{@link RouteOptimizer}와 떨어져 있어 확정된 스텝만 있으면 단독으로 돌릴 수 있다 — 저장된
 * 일정을 일괄 재검사해 품질 지표를 뽑는 용도로도 쓸 수 있다.
 *
 * <p>현재 검사 항목은 <b>기계적 축</b>(슬롯 충족·거리·종료 시각)에 한정된다. 빈 시간, 메뉴 반복,
 * 하루 관광지 수, 숙소 연박 같은 <b>여행자 체감 축</b>은 아직 없다.
 */
@Slf4j
final class InvariantChecker {

    /**
     * 확정 스텝을 기준으로 day별 불변식 상태를 계산한다. 거리는 <b>직선 기준</b>(예산과 같은 단위)이며,
     * 요약 로그는 표시값과 헷갈리지 않도록 도로 환산값을 함께 낸다.
     */
    Map<Integer, DayStatus> evaluate(List<DayState> days, List<StepData> steps,
                                     String transportPref, List<PlaceCandidate> candidates) {
        double[] limits = TRANSPORT_ABS_LIMITS.getOrDefault(transportPref, TRANSPORT_ABS_LIMITS.get("any"));
        Map<Integer, List<StepData>> byDay = steps.stream()
                .collect(Collectors.groupingBy(StepData::dayNumber, LinkedHashMap::new, Collectors.toList()));

        Map<Integer, DayStatus> result = new LinkedHashMap<>();
        for (int i = 0; i < days.size(); i++) {
            DayState day = days.get(i);
            List<StepData> daySteps = byDay.getOrDefault(day.dayNumber, List.of());
            if (daySteps.isEmpty()) continue;
            boolean lastDay = i == days.size() - 1;

            double total = 0;
            double maxLeg = 0;
            for (StepData step : daySteps) {
                if (step.transportationDistance() == null) continue;
                // 저장된 이동거리는 도로 환산값 — 예산과 같은 직선 단위로 되돌려 비교한다.
                double straight = step.transportationDistance().doubleValue() / GeoUtils.ROAD_DISTANCE_FACTOR;
                total += straight;
                maxLeg = Math.max(maxLeg, straight);
            }

            result.put(day.dayNumber, new DayStatus(
                    hasMealInWindow(daySteps, LUNCH_START, LUNCH_END),
                    hasMealInWindow(daySteps, DINNER_START, DINNER_END),
                    !lastDay || departsAfterDinner(daySteps, candidates, day),
                    daySteps.stream().anyMatch(st -> st.place() != null
                            && "ATTRACTION".equals(PlaceCategoryConstants.majorCategory(st.place().category()))),
                    day.accommodationIndex != null,
                    !lastDay,
                    total, maxLeg,
                    total <= limits[1] && maxLeg <= limits[0],
                    daySteps.stream().mapToInt(st -> toMinutesOrZero(st.endTime())).max().orElse(0)));
        }
        return result;
    }

    /**
     * 우선순위 역전 탐지: 이전에 충족하던 상위 불변식(식사 슬롯 → 하루 거리 예산)이 깨졌으면
     * 그 사유를 문자열로 반환한다. 없으면 null. 하위 불변식(커버리지·유형 상한) 악화는 보지 않는다.
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
     * 최종 결과를 day별 한 줄로 요약해 로그에 남기고, 끝내 해소하지 못한 위반을 <b>사용자 안내
     * 문구</b>로 돌려준다. 흩어진 보정 로그만으로는 "결국 어떤 일정이 나왔는지"를 읽을 수 없어
     * 루프 종료 후 단일 요약을 남긴다.
     *
     * @return 남은 위반의 안내 문구. 비어 있으면 모든 구조적 불변식을 만족한 일정이다
     */
    List<String> summarize(List<DayState> days, List<StepData> steps, String pace,
                           String transportPref, List<PlaceCandidate> candidates, ScheduleConfig cfg) {
        Map<Integer, DayStatus> status = evaluate(days, steps, transportPref, candidates);
        double[] limits = TRANSPORT_ABS_LIMITS.getOrDefault(transportPref, TRANSPORT_ABS_LIMITS.get("any"));
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
            if (st.endMinutes() > 0 && st.endMinutes() < EARLY_FINISH_MINUTES) {
                violations.add("조기 종료(" + formatMinutes(st.endMinutes()) + ")");
                notices.add(dayNumber + "일차 일정이 " + formatMinutes(st.endMinutes()) + "에 일찍 끝납니다.");
            }
            if (st.endMinutes() > eveningCap) {
                violations.add("늦은 종료(" + formatMinutes(st.endMinutes()) + ")");
                notices.add(dayNumber + "일차 일정이 " + formatMinutes(st.endMinutes()) + "까지 이어집니다.");
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
     *
     * <p>수리 경로({@link RouteOptimizer})도 같은 판정을 써야 한다 — 수리기와 검사기가 "점심이 있다"를
     * 다르게 정의하면 수리가 끝난 뒤에도 검사가 계속 위반을 잡는 무한 루프가 된다.
     */
    static boolean hasMealInWindow(List<StepData> daySteps, int windowStart, int windowEnd) {
        return daySteps.stream().anyMatch(s -> {
            if (s.place() == null) return false;
            if (!PlaceCategoryConstants.isMealPlace(s.place().name(), s.place().category())) return false;
            int start = toMinutesOrZero(s.startTime());
            return start >= windowStart - 60 && start <= windowEnd;
        });
    }

    /**
     * 마지막날 저녁 식사가 필요한지 — 출발 허브 스텝 시각이 저녁 시간대를 넘기면 필요하다.
     * candidates가 없으면 대분류로 허브 스텝을 찾는다(로그 경로에서 후보 목록 없이 호출됨).
     */
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
