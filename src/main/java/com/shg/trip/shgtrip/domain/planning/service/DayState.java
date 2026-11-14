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
    /**
     * 영업시간 판정용 요일. 여행 시작일을 모르면 null이고 판정을 건너뛴다(열림 가정).
     */
    java.time.DayOfWeek dayOfWeek;
    /**
     * 사용자가 고른 카테고리를 일정 전체에서 혼자 대표하는 장소 인덱스.
     * 거리 트림에서 보호하지 않으면 사용자가 고른 카테고리가 0개가 된다.
     *
     * <p>itinerary 전체를 봐야 정하는 값이라 day마다 같은 집합을 들고 있다.
     * 대표가 2곳 이상인 카테고리는 하나를 빼도 커버리지가 유지돼 넣지 않는다.
     */
    java.util.Set<Integer> coverageCriticalIndices = java.util.Set.of();
    /**
     * 그날 점심과 저녁 사이에 들르는 카페·디저트({@code ScheduleConfig.cafeStop}일 때만).
     * 짧게 들르는 곳이라 우회 기여만 보면 가장 먼저 잘려 거리 트림에서 보호한다.
     */
    Integer cafeStopIndex;
    /** 전날 묵은 숙소(도착 허브가 없는 날). 거리 예산이 아침 구간을 세는 출발점이며 스케줄할 때마다 다시 정한다. */
    Integer startLodgingIndex;
    /** 첫날 도착 시각(분). 이보다 일찍 시작하지 않는다. 모르면 null. */
    Integer startNotBefore;
    /** 마지막 날 출발 허브에 닿아야 하는 시각(분). 모르면 null(기본 21:00). */
    Integer departureDeadline;
    /** 다른 도시에서 열차·비행기로 넘어와 시작하는 날. 도착 허브가 없다. */
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

    /** 사용자가 준 도착·출발 시각이 있는 날. 이런 날만 시간창 보정이 적용된다. */
    boolean hasUserWindow() {
        return startNotBefore != null || departureDeadline != null;
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
