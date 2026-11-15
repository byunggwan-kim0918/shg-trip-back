package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.eval.TraceFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OverCapLegsTest {

    private static final String SIGHT = "Landmarks and Outdoors > Historic and Protected Site";
    private static final String MEAL = "Dining and Drinking > Restaurant > Asian Restaurant > Korean Restaurant";

    @Test
    @DisplayName("상한을 넘긴 구간은 가까운 안 쓴 식당이 풀에 있으면 피할 수 있던 것으로 센다")
    void legWithANearbyUnusedMealIsAvoidable() {
        List<PlaceCandidate> pool = pool();
        pool.add(place(3, "가까운 식당", MEAL, "MEAL", 37.5800, 126.9780));

        assertThat(OverCapLegs.count(farLunch(), pool, "walk", "normal", Set.of())).isEqualTo(new OverCapLegs.Count(1, 0, 0));
    }

    @Test
    @DisplayName("바꿔 넣을 후보가 풀에 없으면 풀이 좁아서 생긴 것으로 세고, 요청 의도 장소에 닿으면 따로 센다")
    void legWithoutAnySwapIsPoolLimited() {
        assertThat(OverCapLegs.count(farLunch(), pool(), "walk", "normal", Set.of(2)))
                .isEqualTo(new OverCapLegs.Count(0, 1, 1));
    }

    @Test
    @DisplayName("가까운 식당이 다음 장소에서 상한 밖이면 바꿔도 구간 초과가 남아 피할 수 없는 것으로 센다")
    void swapMustFitBothNeighbors() {
        List<PlaceCandidate> pool = pool();
        pool.add(place(3, "가까운 식당", MEAL, "MEAL", 37.5800, 126.9780));
        pool.add(place(4, "먼 전망대", SIGHT, "SIGHT", 37.4810, 127.0510));
        List<StepData> steps = new ArrayList<>(farLunch());
        steps.add(step(3, "먼 전망대", SIGHT, 0.2));

        assertThat(OverCapLegs.count(steps, pool, "walk", "normal", Set.of())).isEqualTo(new OverCapLegs.Count(0, 1, 0));
    }

    @Test
    @DisplayName("센 구간 수는 판정의 구간 상한 초과 수와 같다")
    void countsTheSameLegsAsTheRules() {
        for (TraceFile trace : List.of(Itinerary91Fixture.load(), JoinedSeoulBusanFixture.load())) {
            List<StepData> steps = new RouteOptimizer().schedule(trace.selection(), trace.candidates(), trace.args())
                    .steps();
            long byRules = new ItineraryRules().evaluate(steps, trace.candidates(), new ItineraryRules.Context(
                            trace.args().pace(), trace.args().transportPref(), trace.args().startDate(),
                            trace.dayCount(), null))
                    .countByRule().getOrDefault(ItineraryRules.Rule.LEG_OVER_CAP, 0L);

            OverCapLegs.Count count = OverCapLegs.count(steps, trace.candidates(), trace.args().transportPref(),
                    trace.args().pace(), Set.of());

            assertThat((long) count.avoidable() + count.poolLimited()).isEqualTo(byRules);
        }
    }

    @Test
    @DisplayName("상한 안의 구간은 세지 않는다")
    void legsWithinTheCapAreNotCounted() {
        List<StepData> steps = List.of(step(1, "궁", SIGHT, null), step(2, "먼 식당", MEAL, 5.0));

        assertThat(OverCapLegs.count(steps, pool(), "walk", "normal", Set.of())).isEqualTo(new OverCapLegs.Count(0, 0, 0));
    }

    /** 궁에서 도로 15km 떨어진 식당으로 가는 도보 일정. 도보 구간 상한은 직선 8km다. */
    private static List<StepData> farLunch() {
        return List.of(step(1, "궁", SIGHT, null), step(2, "먼 식당", MEAL, 15.0));
    }

    private static List<PlaceCandidate> pool() {
        List<PlaceCandidate> pool = new ArrayList<>();
        pool.add(place(1, "궁", SIGHT, "SIGHT", 37.5796, 126.9770));
        pool.add(place(2, "먼 식당", MEAL, "MEAL", 37.4800, 127.0500));
        return pool;
    }

    private static StepData step(int order, String name, String category, Double roadKm) {
        return new StepData(order, 1, "09:00", "10:00", new PlaceData(name, null, category, "Seoul", "KR"),
                List.of(), roadKm == null ? null : "WALK", roadKm == null ? null : 10,
                roadKm == null ? null : BigDecimal.valueOf(roadKm), null, null, null);
    }

    private static PlaceCandidate place(int index, String name, String category, String role, double lat,
                                        double lng) {
        return new PlaceCandidate(index, (long) index, name, "서울특별시 종로구 율곡로 " + index, category, List.of(),
                "Seoul", "KR", BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null, null, 0.8, null, null, null, 60,
                false, null, null, null, null, role, null, false, null);
    }
}
