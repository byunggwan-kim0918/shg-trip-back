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
    @DisplayName("운영 기록 91의 후보 풀과 요청 의도로 날마다 직접 고른 일정이 스냅샷과 일치한다")
    void busan91PickedMatchesSnapshot() throws IOException {
        TraceFile trace = Itinerary91Fixture.load();
        SerialPlanner.Result result = planner.plan(trace.selection(), trace.candidates(), trace.args(), trace.dayCount());
        RouteOptimizerGoldenMasterTest.assertMatchesSnapshot("picked-busan-91-relaxed-couple",
                new RouteOptimizer.ScheduleResult(result.steps(), result.unresolved()));
    }

    @Test
    @DisplayName("서울·부산 여러 도시의 후보 풀과 요청 의도로 날마다 직접 고른 일정이 스냅샷과 일치한다")
    void seoulBusanPickedMatchesSnapshot() throws IOException {
        TraceFile trace = JoinedSeoulBusanFixture.load();
        SerialPlanner.Result result = planner.plan(trace.selection(), trace.candidates(), trace.args(), trace.dayCount());
        RouteOptimizerGoldenMasterTest.assertMatchesSnapshot("picked-seoul-busan-6d-city-blocks",
                new RouteOptimizer.ScheduleResult(result.steps(), result.unresolved()));
    }

    private void assertMatchesSnapshot(String name, TraceFile trace) throws IOException {
        SerialPlanner.Result result = planner.schedule(trace.selection(), trace.candidates(), trace.args());
        RouteOptimizerGoldenMasterTest.assertMatchesSnapshot(name,
                new RouteOptimizer.ScheduleResult(result.steps(), result.unresolved()));
    }
}
