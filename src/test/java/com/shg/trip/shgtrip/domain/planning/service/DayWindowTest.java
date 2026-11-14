package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.ItineraryData;
import com.shg.trip.shgtrip.domain.planning.dto.ScheduleArgs;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.TransportationHub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.toMinutesOrZero;
import static org.assertj.core.api.Assertions.assertThat;

class DayWindowTest {

    private final RouteOptimizer routeOptimizer = new RouteOptimizer();

    private List<StepData> schedule(String arrival, String departure) {
        return schedule(arrival, departure, null);
    }

    @Test
    @DisplayName("자정 직전 도착도 허브와 숙소 시각이 겹치지 않아 구조 검증을 통과한다")
    void lateArrivalStaysStructurallyValid() {
        for (String arrival : List.of("23:00", "23:30", "23:50")) {
            List<StepData> steps = schedule(arrival, null, null);

            assertThat(new HardValidator().validate(new ItineraryData("t", "제주", BigDecimal.ZERO, List.of(), steps)).valid())
                    .as(arrival).isTrue();
        }
    }

    private List<StepData> schedule(String arrival, String departure, String country) {
        ScheduleArgs args = new ScheduleArgs("normal", "car", LocalDate.of(2026, 11, 1),
                List.of("food", "romance"), new TransportationHub("제주국제공항", "제주국제공항", "AIRPORT"),
                false, List.of("restaurant"), false, arrival, departure, country);
        return routeOptimizer.schedule(Itinerary115Fixture.selection(), Itinerary115Fixture.candidates(), args).steps();
    }

    private static List<StepData> day(List<StepData> steps, int dayNumber) {
        return steps.stream().filter(s -> s.dayNumber() == dayNumber).toList();
    }

    @Test
    @DisplayName("첫날 15시 도착이면 15시 전에 시작하지 않고 점심을 넣지 않는다")
    void afternoonArrivalStartsLateWithoutLunch() {
        List<StepData> day1 = day(schedule("15:00", null), 1);

        assertThat(day1).allSatisfy(s -> assertThat(toMinutesOrZero(s.startTime())).isGreaterThanOrEqualTo(15 * 60));
        assertThat(InvariantChecker.hasMealInWindow(day1, ScheduleTuning.LUNCH_START, ScheduleTuning.LUNCH_END))
                .isFalse();
    }

    @Test
    @DisplayName("마지막 날 11시 비행기면 출발 허브에 10시까지 닿고 점심·저녁을 넣지 않는다")
    void morningDepartureEndsBeforeCheckIn() {
        List<StepData> steps = schedule(null, "11:00");
        int lastDay = steps.stream().mapToInt(StepData::dayNumber).max().orElseThrow();
        List<StepData> last = day(steps, lastDay);
        StepData hub = last.get(last.size() - 1);

        assertThat(toMinutesOrZero(hub.startTime())).isLessThanOrEqualTo(10 * 60);
        assertThat(InvariantChecker.hasMealInWindow(last, ScheduleTuning.LUNCH_START, ScheduleTuning.LUNCH_END))
                .isFalse();
        assertThat(InvariantChecker.hasMealInWindow(last, ScheduleTuning.DINNER_START, ScheduleTuning.DINNER_END))
                .isFalse();
    }

    @Test
    @DisplayName("마지막 날 장소가 전부 보호 대상이어도 사용자가 준 출발 시각은 지킨다")
    void userDeadlineOverridesProtectedStops() {
        SelectionOutput base = Itinerary115Fixture.selection();
        List<Integer> lastDayPlaces = base.days().get(base.days().size() - 1).placeIndices();
        SelectionOutput protectedLastDay = new SelectionOutput(base.concept(), base.days(), base.pairs(),
                base.spareIndices(), lastDayPlaces, List.of());
        ScheduleArgs args = new ScheduleArgs("normal", "car", LocalDate.of(2026, 11, 1),
                List.of("food", "romance"), new TransportationHub("제주국제공항", "제주국제공항", "AIRPORT"),
                false, List.of("restaurant"), false, null, "11:00", null);

        List<StepData> steps = routeOptimizer.schedule(protectedLastDay, Itinerary115Fixture.candidates(), args).steps();
        int lastDay = steps.stream().mapToInt(StepData::dayNumber).max().orElseThrow();
        List<StepData> last = day(steps, lastDay);

        assertThat(toMinutesOrZero(last.get(last.size() - 1).startTime())).isLessThanOrEqualTo(10 * 60);
    }

    @Test
    @DisplayName("오전 출발로 일찍 끝나는 날은 조기 종료·관광 없음을 위반으로 보지 않는다")
    void noEarlyFinishOnMorningDeparture() {
        ScheduleArgs args = new ScheduleArgs("normal", "car", LocalDate.of(2026, 11, 1),
                List.of("food", "romance"), new TransportationHub("제주국제공항", "제주국제공항", "AIRPORT"),
                false, List.of("restaurant"), false, null, "11:00", null);
        List<String> unresolved = routeOptimizer.schedule(Itinerary115Fixture.selection(),
                Itinerary115Fixture.candidates(), args).unresolved();

        assertThat(unresolved).noneMatch(n -> n.startsWith("day4 ") && (n.contains("조기 종료") || n.contains("관광 없음")));
    }

    @Test
    @DisplayName("점심 무렵(12:30) 도착이면 내려서 먹는 늦은 점심을 넣는다")
    void noonArrivalKeepsLateLunch() {
        List<StepData> day1 = day(schedule("12:30", null), 1);

        assertThat(InvariantChecker.hasMealInWindow(day1, ScheduleTuning.LUNCH_START, 12 * 60 + 30 + 150)).isTrue();
    }

    @Test
    @DisplayName("7시 비행기면 마지막 날을 당겨 시작해 6시까지 공항에 닿는다")
    void earlyFlightReachesHubByDeadline() {
        List<StepData> steps = schedule(null, "07:00");
        int lastDay = steps.stream().mapToInt(StepData::dayNumber).max().orElseThrow();
        List<StepData> last = day(steps, lastDay);

        assertThat(toMinutesOrZero(last.get(last.size() - 1).startTime())).isLessThanOrEqualTo(6 * 60);
    }

    @Test
    @DisplayName("지킬 수 없는 출발 시각(00:30)은 위반 없음으로 넘기지 않고 남은 위반으로 둔다")
    void impossibleDepartureIsReported() {
        ScheduleArgs args = new ScheduleArgs("normal", "car", LocalDate.of(2026, 11, 1),
                List.of("food", "romance"), new TransportationHub("제주국제공항", "제주국제공항", "AIRPORT"),
                false, List.of("restaurant"), false, null, "00:30", null);
        List<String> unresolved = routeOptimizer.schedule(Itinerary115Fixture.selection(),
                Itinerary115Fixture.candidates(), args).unresolved();

        assertThat(unresolved).contains("day4 출발 시각 초과");
    }

    @Test
    @DisplayName("13시 도착이면 늦은 점심 뒤에도 저녁이 저녁 시간대 안에 들어가고 거짓 안내가 없다")
    void earlyAfternoonArrivalKeepsDinnerInWindow() {
        ScheduleArgs args = new ScheduleArgs("normal", "car", LocalDate.of(2026, 11, 1),
                List.of("food", "romance"), new TransportationHub("제주국제공항", "제주국제공항", "AIRPORT"),
                false, List.of("restaurant"), false, "13:00", null, null);
        RouteOptimizer.ScheduleResult r = routeOptimizer.schedule(Itinerary115Fixture.selection(),
                Itinerary115Fixture.candidates(), args);
        List<StepData> day1 = day(r.steps(), 1);

        assertThat(InvariantChecker.hasMealInWindow(day1, ScheduleTuning.DINNER_START, ScheduleTuning.DINNER_END))
                .isTrue();
        assertThat(r.unresolved()).doesNotContain("day1 저녁 없음");
    }

    @Test
    @DisplayName("17:45 도착이면 저녁을 넣고, 밤에 관광을 억지로 끼우지 않는다")
    void eveningArrivalGetsDinnerWithoutNightSightseeing() {
        List<StepData> day1 = day(schedule("17:45", null), 1);

        assertThat(InvariantChecker.hasMealInWindow(day1, ScheduleTuning.DINNER_START, 17 * 60 + 45 + 150)).isTrue();
        assertThat(day1).noneMatch(s -> s.place() != null
                && "ATTRACTION".equals(PlaceCategoryConstants.majorCategory(s.place().category())));
    }

    @Test
    @DisplayName("해외 출발은 국제선 기준으로 두 시간 전에 허브에 닿는다")
    void overseasDepartureUsesLongerBuffer() {
        List<StepData> steps = schedule(null, "13:00", "JP");
        int lastDay = steps.stream().mapToInt(StepData::dayNumber).max().orElseThrow();
        List<StepData> last = day(steps, lastDay);

        assertThat(toMinutesOrZero(last.get(last.size() - 1).startTime())).isLessThanOrEqualTo(11 * 60);
    }
}
