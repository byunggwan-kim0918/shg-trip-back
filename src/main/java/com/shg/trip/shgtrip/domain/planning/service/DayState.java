package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import java.util.ArrayList;
import java.util.List;

class DayState {
    final int dayNumber;
    Integer arrivalHubIndex;
    final List<Integer> placeIndices;
    Integer accommodationIndex;
    Integer departureHubIndex;
    /** 첫날 도착 시각(분). 이보다 일찍 시작하지 않는다. 모르면 null. */
    Integer startNotBefore;
    /** 마지막 날 출발 허브에 닿아야 하는 시각(분). 모르면 null(기본 21:00). */
    Integer departureDeadline;
    /** 다른 도시에서 열차·비행기·차로 넘어와 시작하는 날. 도착 허브가 없다. */
    boolean resumesAfterTransfer;
    /** 다음 도시로 넘어가기 전날. 구간의 마지막 날이어도 숙소·저녁이 필요하고 출발 허브가 없다. */
    boolean continuesAfter;

    DayState(SelectionOutput.DayPlan plan) {
        this.dayNumber = plan.dayNumber();
        this.arrivalHubIndex = plan.arrivalHubIndex();
        this.placeIndices = new ArrayList<>(plan.placeIndices() != null ? plan.placeIndices() : List.of());
        this.accommodationIndex = plan.accommodationIndex();
        this.departureHubIndex = plan.departureHubIndex();
    }

    /** 점심 시간대가 끝난 뒤 도착하거나 끝나기 전에 떠나는 날이면 false. 그날 점심을 요구하지 않는다. */
    boolean lunchInWindow() {
        return ScheduleTuning.lunchInWindow(startNotBefore, departureDeadline);
    }

    /** 점심 무렵 도착이면 내려서 먹는 늦은 점심도 점심으로 본다. */
    int lunchWindowEnd() {
        return lateMealEnd(ScheduleTuning.LUNCH_END);
    }

    int dinnerWindowEnd() {
        return lateMealEnd(ScheduleTuning.DINNER_END);
    }

    private int lateMealEnd(int windowEnd) {
        return startNotBefore == null ? windowEnd
                : Math.max(windowEnd, startNotBefore + ScheduleTuning.LATE_MEAL_WINDOW_MINUTES);
    }

    /** 출발 시각 때문에 일찍 끝나는 날. 사용자가 정한 일정이라 조기 종료로 보지 않는다. */
    boolean endsEarlyByDeparture() {
        return departureDeadline != null && departureDeadline < ScheduleTuning.EARLY_FINISH_MINUTES;
    }

    /** 도착부터 출발까지가 그날 끼니를 빼면 관광 하나 넣기에도 짧은 날. */
    boolean tooShortForSightseeing() {
        return ScheduleTuning.tooShortForSightseeing(
                ScheduleTuning.sightseeingWindowMinutes(startNotBefore, departureDeadline));
    }

    boolean dinnerInWindow() {
        return ScheduleTuning.dinnerInWindow(startNotBefore, departureDeadline);
    }

    /** 여행이 끝나는 날. 다음 도시로 이어지는 구간의 마지막 날은 아니다. */
    static boolean endsTrip(List<DayState> days, int i) {
        return i == days.size() - 1 && !days.get(i).continuesAfter;
    }
}
