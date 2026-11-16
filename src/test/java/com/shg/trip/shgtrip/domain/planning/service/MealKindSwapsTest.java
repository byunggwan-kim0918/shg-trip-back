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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MealKindSwapsTest {

    private static final String SIGHT = "Landmarks and Outdoors > Historic and Protected Site";
    private static final String MEAL = "Dining and Drinking > Restaurant > Asian Restaurant > Korean Restaurant";

    @Test
    @DisplayName("같은 날 점심·저녁이 같은 종류인데 근처에 안 쓴 다른 종류 식당이 있으면 피할 수 있던 것으로 센다")
    void sameDayKindWithNearbyOtherKindIsAvoidable() {
        List<PlaceCandidate> pool = pool();
        pool.add(place(4, "바다횟집", MEAL, "MEAL", 37.5790, 126.9790));

        MealKindSwaps.Count count = MealKindSwaps.count(gukbapTwice(), pool, "walk", "normal", null);

        assertThat(count.sameDayAvoidable()).isEqualTo(1);
        assertThat(count.sameDayPoolLimited()).isZero();
    }

    @Test
    @DisplayName("다른 종류 식당이 없거나 구간 상한 밖이면 풀이 좁아서 생긴 것으로 센다")
    void sameDayKindWithoutReachableOtherKindIsPoolLimited() {
        List<PlaceCandidate> pool = pool();
        pool.add(place(4, "먼 바다횟집", MEAL, "MEAL", 37.4000, 127.1500));

        MealKindSwaps.Count count = MealKindSwaps.count(gukbapTwice(), pool, "walk", "normal", null);

        assertThat(count.sameDayAvoidable()).isZero();
        assertThat(count.sameDayPoolLimited()).isEqualTo(1);
    }

    @Test
    @DisplayName("센 수는 판정의 같은 날·연속 끼니 종류 수와 같고, 여행 전체는 상한을 넘긴 끼니 수와 같다")
    void countsMatchTheRules() {
        for (TraceFile trace : List.of(Itinerary91Fixture.load(), JoinedSeoulBusanFixture.load())) {
            List<StepData> steps = new RouteOptimizer().schedule(trace.selection(), trace.candidates(), trace.args())
                    .steps();
            ItineraryRules.Verdict verdict = new ItineraryRules().evaluate(steps, trace.candidates(),
                    new ItineraryRules.Context(trace.args().pace(), trace.args().transportPref(),
                            trace.args().startDate(), trace.dayCount(), null));
            Map<ItineraryRules.Rule, Long> byRule = verdict.countByRule();
            long overCap = verdict.violations().stream()
                    .filter(v -> v.rule() == ItineraryRules.Rule.MEAL_KIND_REPEATED)
                    .mapToLong(v -> v.evidence().size() - ScheduleTuning.mealKindTripCap(trace.dayCount()))
                    .sum();

            MealKindSwaps.Count count = MealKindSwaps.count(steps, trace.candidates(), trace.args().transportPref(),
                    trace.args().pace(), trace.args().startDate());

            assertThat((long) count.sameDayAvoidable() + count.sameDayPoolLimited())
                    .isEqualTo(byRule.getOrDefault(ItineraryRules.Rule.SAME_DAY_MEAL_KIND, 0L));
            assertThat((long) count.consecutiveAvoidable() + count.consecutivePoolLimited())
                    .isEqualTo(byRule.getOrDefault(ItineraryRules.Rule.CONSECUTIVE_MEAL_KIND, 0L));
            assertThat((long) count.repeatedAvoidable() + count.repeatedPoolLimited()).isEqualTo(overCap);
        }
    }

    /** 궁을 사이에 두고 점심·저녁이 모두 국밥인 하루. */
    private static List<StepData> gukbapTwice() {
        return List.of(step(1, "할매국밥", MEAL, "11:30", "12:40"), step(2, "궁", SIGHT, "13:00", "15:00"),
                step(3, "명가국밥", MEAL, "17:30", "18:40"));
    }

    private static List<PlaceCandidate> pool() {
        List<PlaceCandidate> pool = new ArrayList<>();
        pool.add(place(1, "할매국밥", MEAL, "MEAL", 37.5800, 126.9800));
        pool.add(place(2, "궁", SIGHT, "SIGHT", 37.5796, 126.9770));
        pool.add(place(3, "명가국밥", MEAL, "MEAL", 37.5810, 126.9810));
        return pool;
    }

    private static StepData step(int order, String name, String category, String start, String end) {
        return new StepData(order, 1, start, end, new PlaceData(name, null, category, "Seoul", "KR"),
                List.of(), null, null, null, null, null, null);
    }

    private static PlaceCandidate place(int index, String name, String category, String role, double lat,
                                        double lng) {
        return new PlaceCandidate(index, (long) index, name, "서울특별시 종로구 율곡로 " + index, category, List.of(),
                "Seoul", "KR", BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null, null, 0.8, null, null, null, 60,
                false, null, null, null, null, role, null, false, null);
    }
}
