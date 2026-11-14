package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.AlternativeData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 끼니 보충·날짜 간 교환·대안이 그날 구성 장부를 어떤 순서로 쓰는지 고정한다. */
class MealVarietyPathsTest {

    private static final String RESTAURANT = "Dining and Drinking > Restaurant";
    private static final String BEACH = "Landmarks and Outdoors > Beach";

    private final List<PlaceCandidate> candidates = List.of(
            place(1, "광안횟집", RESTAURANT, "MEAL", "SEAFOOD", 35.153, 129.118),
            place(2, "해운대해수욕장", BEACH, "SIGHT", null, 35.158, 129.160),
            place(3, "황금조개구이", RESTAURANT, "MEAL", "SEAFOOD", 35.155, 129.120),
            place(4, "바다식당", RESTAURANT, "MEAL", "SEAFOOD", 35.154, 129.119),
            place(5, "합천돼지국밥", RESTAURANT, "MEAL", "MEAT", 35.160, 129.130),
            place(6, "토속초당순두부", RESTAURANT, "MEAL", "KOREAN_SET", 35.157, 129.125),
            place(7, "광안리해수욕장", BEACH, "SIGHT", null, 35.153, 129.119),
            place(8, "그옛날 초당순두부", RESTAURANT, "MEAL", "KOREAN_SET", 35.156, 129.124));

    @Test
    @DisplayName("저녁 보충은 그날 다른 묶음 → 다른 종류 → 반복 감수 순으로 고른다")
    void dinnerFillPrefersOtherGroupThenOtherKind() {
        assertThat(fillDinner(List.of(3, 4, 5))).isEqualTo(5);
        assertThat(fillDinner(List.of(3, 4))).isEqualTo(3);
        assertThat(fillDinner(List.of(4))).isEqualTo(4);
    }

    @Test
    @DisplayName("날짜 간 교환은 같은 역할끼리만, 받는 날에 같은 종류 끼니가 생기지 않을 때만 허용한다")
    void swapKeepsDayComposition() {
        DayState a = day(1, 1, 2);
        DayState b = day(2, 6, 7);

        assertThat(DayRebalancer.keepsDayComposition(2, 7, a, b, candidates)).isTrue();
        assertThat(DayRebalancer.keepsDayComposition(1, 6, a, b, candidates)).isTrue();
        assertThat(DayRebalancer.keepsDayComposition(2, 6, a, b, candidates)).isFalse();

        assertThat(DayRebalancer.keepsDayComposition(1, 6, day(3, 1, 8), b, candidates)).isFalse();
    }

    @Test
    @DisplayName("대안은 그날 다른 끼니와 겹치는 식당을 뒤로 미뤄, 다른 후보가 셋이면 빠진다")
    void alternativesDemoteSameKindOfDay() {
        DayState day = day(1, 1, 2, 6);

        List<String> names = AlternativeBuilder.buildAlternatives(candidates.get(5), null,
                        new ArrayDeque<>(List.of(8)), new HashSet<>(List.of(1, 2, 6)), candidates, "car", new HashSet<>(),
                        VarietyLedger.admitsReplacing(day, 6, candidates)).stream()
                .map(AlternativeData::name).toList();

        assertThat(names).containsExactlyInAnyOrder("황금조개구이", "합천돼지국밥", "그옛날 초당순두부");
    }

    private Integer fillDinner(List<Integer> spare) {
        DayState day = day(1, 1, 2);
        List<StepData> steps = List.of(step(1, "10:00", "11:00", 2), step(2, "11:30", "12:40", 1));
        HashSet<Integer> used = new HashSet<>(List.of(1, 2, 3, 4, 5, 6, 7, 8));
        used.removeAll(spare);
        MealPlanner.ensureMealSlots(day, steps, candidates, new ArrayDeque<>(spare), used, 6,
                false, null, "car", new InvariantMemo(), new ScheduleConfig(9 * 60, 22 * 60, 0, false, "normal"),
                c -> true);
        List<Integer> added = new ArrayList<>(day.placeIndices);
        added.removeAll(List.of(1, 2));
        return added.isEmpty() ? null : added.get(0);
    }

    private StepData step(int order, String start, String end, int index) {
        PlaceCandidate c = candidates.get(index - 1);
        return new StepData(order, 1, start, end, new PlaceData(c.name(), c.address(), c.category(), c.region(),
                c.country(), null, null, c.placeId(), null), List.of(), null, null, null, null, null, null);
    }

    private static DayState day(int dayNumber, Integer... places) {
        return new DayState(new SelectionOutput.DayPlan(dayNumber, null, new ArrayList<>(List.of(places)), null, null));
    }

    private static PlaceCandidate place(int index, String name, String category, String role, String cuisine,
                                        double lat, double lng) {
        return new PlaceCandidate(index, (long) index, name, "부산 수영구 테스트로 " + index, category, List.of(), "Busan",
                "KR", BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null, null, 0.9, null, null,
                null, 60, false, null, null, null, null, role, cuisine, false, null);
    }
}
