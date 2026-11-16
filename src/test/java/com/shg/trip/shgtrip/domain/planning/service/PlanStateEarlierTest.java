package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.AlternativeData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 도시 구간마다 새로 만드는 확정 상태가 앞 구간에서 정한 것을 이어 받는지. */
class PlanStateEarlierTest {

    private static final String MEAL = "Dining and Drinking > Restaurant";

    private final List<PlaceCandidate> pool = List.of(
            place(1, "본가국밥 서면점"),
            place(2, "바다 횟집"),
            place(3, "항구 횟집"));

    @Test
    @DisplayName("앞 구간 확정 결과에서 브랜드·끼니 종류·대안 키를 모은다")
    void collectsFromEarlierSteps() {
        StepData lunch = step(1, "본가국밥 서면점", List.of(new AlternativeData("항구 횟집", "주소", MEAL, "Busan", "KR",
                null, null, 3L, null)));
        StepData dinner = step(2, "바다 횟집", List.of());

        PlanState.Earlier earlier = PlanState.Earlier.NONE.plus(List.of(lunch, dinner), new CandidateLookup(pool), 2);

        assertThat(earlier.brands()).contains("본가국밥", "바다횟집");
        assertThat(earlier.mealKinds()).containsEntry("raw_fish", 1).containsEntry("gukbap", 1);
        assertThat(earlier.alternativeKeys()).containsExactly("id:3");
        assertThat(earlier.daysBefore()).isEqualTo(2);
    }

    @Test
    @DisplayName("끼니 종류 상한은 앞 구간까지 지난 일수로, 수는 앞 구간까지 더해 센다")
    void capsMealKindsOverTheWholeTrip() {
        PlanState s = state();
        int blockCap = s.typeCap(pool.get(1));
        s.continueFrom(new PlanState.Earlier(Set.of("본가국밥"), Set.of("id:9"), java.util.Map.of("raw_fish", 2), 8));
        s.beginScheduling();

        assertThat(blockCap).isEqualTo(ScheduleTuning.mealKindTripCap(2));
        assertThat(s.typeCap(pool.get(1))).isEqualTo(ScheduleTuning.mealKindTripCap(10));
        assertThat(s.typeCounts()).containsEntry("raw_fish", 2);
        assertThat(s.usedBrands()).contains("본가국밥");
        AlternativeBuilder.TripView view = s.alternativesFor(s.days.get(0), new HashSet<>());
        assertThat(view.shownElsewhere()).contains("id:9");
        assertThat(view.mealKindCap()).isEqualTo(ScheduleTuning.mealKindTripCap(10));
    }

    @Test
    @DisplayName("하루 보충의 종류 상한도 앞 도시 구간에서 먹은 끼니를 센다")
    void finalRepairCountsEarlierMealKinds() {
        PlanState fresh = state();
        fresh.beginScheduling();
        PlanState after = state();
        after.continueFrom(new PlanState.Earlier(Set.of(), Set.of(), java.util.Map.of("raw_fish", 2), 0));
        after.beginScheduling();

        assertThat(FinalRepair.Pass.of(fresh, List.of(), new InvariantMemo()).underTypeCap().test(pool.get(1))).isTrue();
        assertThat(FinalRepair.Pass.of(after, List.of(), new InvariantMemo()).underTypeCap().test(pool.get(1))).isFalse();
    }

    private PlanState state() {
        return PlanState.of(new SelectionOutput("", List.of(new SelectionOutput.DayPlan(1, null, List.of(), null, null),
                new SelectionOutput.DayPlan(2, null, List.of(), null, null)), List.of(), List.of()), pool,
                ScheduleTestArgs.of("normal", "car", LocalDate.of(2026, 12, 1), List.of(), null, false, List.of()));
    }

    private static StepData step(int order, String name, List<AlternativeData> alternatives) {
        PlaceData place = new PlaceData(name, "주소", MEAL, "Busan", "KR", null, null, (long) order, null);
        return new StepData(order, 1, "12:00", "13:00", place, alternatives, null, null, null, null, null, null);
    }

    private static PlaceCandidate place(int index, String name) {
        return new PlaceCandidate(index, (long) index, name, "부산 해운대구 테스트로 " + index, MEAL, List.of(), "Busan",
                "KR", BigDecimal.valueOf(35.1 + index * 0.001), BigDecimal.valueOf(129.03), null, null, 0.8, null, null,
                null, 60, false, null, null, null, null, "MEAL", null, false, null);
    }
}
