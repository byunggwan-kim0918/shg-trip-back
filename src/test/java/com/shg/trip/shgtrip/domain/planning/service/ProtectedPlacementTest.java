package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.ScheduleArgs;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.TransportationHub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProtectedPlacementTest {

    private static final int ORAN_OREUM = 8;   // 열안지오름, 모델 1일차
    private static final int BYEOLBANGJIN = 9; // 별방진, 11시 출발이면 마지막 날에서 잘린다

    private final RouteOptimizer routeOptimizer = new RouteOptimizer();

    private List<StepData> schedule(List<Integer> highlights, String arrival, String departure) {
        SelectionOutput base = Itinerary115Fixture.selection();
        SelectionOutput selection = new SelectionOutput(base.concept(), base.days(), base.pairs(), base.spareIndices(),
                highlights, List.of(), List.of());
        ScheduleArgs args = new ScheduleArgs("normal", "car", LocalDate.of(2026, 11, 1),
                List.of("food", "romance"), new TransportationHub("제주국제공항", "제주국제공항", "AIRPORT"),
                false, List.of("restaurant"), false, arrival, departure, null);
        return routeOptimizer.schedule(selection, Itinerary115Fixture.candidates(), args).steps();
    }

    private static StepData stepOf(List<StepData> steps, int index) {
        PlaceCandidate c = Itinerary115Fixture.candidates().get(index - 1);
        return steps.stream().filter(s -> s.place() != null && c.name().equals(s.place().name())).findFirst().orElse(null);
    }

    @Test
    @DisplayName("이른 출발로 마지막 날에서 잘리는 핵심 장소는 다른 날로 옮겨져 일정에 남는다")
    void highlightTrimmedOnDepartureDayIsRestored() {
        assertThat(stepOf(schedule(List.of(), null, "11:00"), BYEOLBANGJIN)).isNull();

        StepData kept = stepOf(schedule(List.of(BYEOLBANGJIN), null, "11:00"), BYEOLBANGJIN);

        assertThat(kept).isNotNull();
        assertThat(kept.dayNumber()).isLessThan(4);
    }

    @Test
    @DisplayName("늦은 도착으로 관광 시간이 없는 날의 핵심 장소는 다른 날로 옮겨져 일정에 남는다")
    void highlightLeavesShortArrivalDay() {
        StepData moved = stepOf(schedule(List.of(ORAN_OREUM), "22:00", null), ORAN_OREUM);

        assertThat(moved).isNotNull();
        assertThat(moved.dayNumber()).isGreaterThan(1);
    }
}
