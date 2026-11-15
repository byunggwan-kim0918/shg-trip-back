package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.eval.TraceFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/** {@link SerialPlanner} 출력 전체를 {@link RouteOptimizerGoldenMasterTest}와 같은 형식의 스냅샷으로 고정한다. */
class SerialPlannerGoldenMasterTest {

    private final SerialPlanner planner = new SerialPlanner();

    @Test
    @DisplayName("운영 기록 91(부산 5박 6일, 커플·여유·자동차)의 날짜별 확정 출력이 스냅샷과 일치한다")
    void busan91MatchesSnapshot() throws IOException {
        assertMatchesSnapshot("serial-busan-91-relaxed-couple", Itinerary91Fixture.load());
    }

    @Test
    @DisplayName("서울·부산을 옮겨 다니는 5박 6일(도보)의 날짜별 확정 출력이 스냅샷과 일치한다")
    void seoulBusanMatchesSnapshot() throws IOException {
        assertMatchesSnapshot("serial-seoul-busan-6d-city-blocks", JoinedSeoulBusanFixture.load());
    }

    @Test
    @DisplayName("운영 기록 91의 선택 장소를 먼저 쓰며 날짜를 직접 정한 일정이 스냅샷과 일치한다")
    void busan91GuidedMatchesSnapshot() throws IOException {
        assertPlanMatchesSnapshot("guided-busan-91-relaxed-couple", Itinerary91Fixture.load(),
                SerialPlanner.Picks.PREFER);
    }

    @Test
    @DisplayName("서울·부산 여러 도시의 선택 장소를 먼저 쓰며 날짜를 직접 정한 일정이 스냅샷과 일치한다")
    void seoulBusanGuidedMatchesSnapshot() throws IOException {
        assertPlanMatchesSnapshot("guided-seoul-busan-6d-city-blocks", JoinedSeoulBusanFixture.load(),
                SerialPlanner.Picks.PREFER);
    }

    @Test
    @DisplayName("운영 기록 91의 요청 의도만 따라 날짜를 직접 정한 일정이 스냅샷과 일치한다")
    void busan91PickedMatchesSnapshot() throws IOException {
        assertPlanMatchesSnapshot("picked-busan-91-relaxed-couple", Itinerary91Fixture.load(),
                SerialPlanner.Picks.IGNORE);
    }

    @Test
    @DisplayName("서울·부산 여러 도시의 요청 의도만 따라 날짜를 직접 정한 일정이 스냅샷과 일치한다")
    void seoulBusanPickedMatchesSnapshot() throws IOException {
        assertPlanMatchesSnapshot("picked-seoul-busan-6d-city-blocks", JoinedSeoulBusanFixture.load(),
                SerialPlanner.Picks.IGNORE);
    }

    private void assertPlanMatchesSnapshot(String name, TraceFile trace, SerialPlanner.Picks picks)
            throws IOException {
        SerialPlanner.Result result = planner.plan(trace.selection(), trace.candidates(), trace.args(),
                trace.dayCount(), picks);
        RouteOptimizerGoldenMasterTest.assertMatchesSnapshot(name,
                new RouteOptimizer.ScheduleResult(result.steps(), result.unresolved()));
    }

    private void assertMatchesSnapshot(String name, TraceFile trace) throws IOException {
        SerialPlanner.Result result = planner.schedule(trace.selection(), trace.candidates(), trace.args());
        RouteOptimizerGoldenMasterTest.assertMatchesSnapshot(name,
                new RouteOptimizer.ScheduleResult(result.steps(), result.unresolved()));
    }
}
