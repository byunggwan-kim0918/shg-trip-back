package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GapFillerTest {

    private static final String RESTAURANT = "Dining and Drinking > Restaurant";
    private static final String CAFE = "Dining and Drinking > Cafe, Coffee, and Tea House";
    private static final String PARK = "Landmarks and Outdoors > Park";

    /** 점심(1)과 저녁(2)은 1km 거리. 3은 북쪽 9km의 유명 명소라 우회가 15km를 넘는다. */
    private final List<PlaceCandidate> candidates = List.of(
            place(1, "점심 식당", RESTAURANT, "MEAL", 37.5000, 127.0000, null),
            place(2, "저녁 식당", RESTAURANT, "MEAL", 37.5000, 127.0100, null),
            place(3, "북쪽 명소", PARK, "SIGHT", 37.5810, 127.0050, 40),
            place(4, "골목 공원", PARK, "SIGHT", 37.5030, 127.0050, null),
            place(5, "카페 하나", CAFE, "CAFE", 37.5010, 127.0020, null),
            place(6, "카페 둘", CAFE, "CAFE", 37.5010, 127.0030, null),
            place(7, "카페 셋", CAFE, "CAFE", 37.5020, 127.0040, null));

    @Test
    @DisplayName("다음 식당까지 가는 길에서 15km 넘게 벗어나는 곳은 더 유명해도 공백에 넣지 않는다")
    void skipsFarDetourEvenIfFamous() {
        List<String> inserted = fill(day(1, 2), List.of(3, 4), "normal", 2);

        assertThat(inserted).contains("골목 공원").doesNotContain("북쪽 명소");
    }

    @Test
    @DisplayName("relaxed 초과 채움은 카페를 먼저 넣는다")
    void relaxedPrefersCafe() {
        assertThat(fill(day(1, 2), List.of(7, 4), "relaxed", 0)).containsExactly("카페 셋");
    }

    @Test
    @DisplayName("relaxed 초과 채움에서 카페가 하루 상한에 막히면 관광으로 채운다")
    void relaxedFallsBackToSightWhenCafesAreCapped() {
        assertThat(fill(day(1, 5, 6, 2), List.of(7, 4), "relaxed", 0)).containsExactly("골목 공원");
    }

    private List<String> fill(DayState day, List<Integer> spare, String pace, int remainingQuota) {
        Set<Integer> used = new HashSet<>(day.placeIndices);
        GapFill result = GapFiller.fillGapBeforeMeal(day, candidates, new ArrayDeque<>(spare), used,
                candidates.get(0), 13 * 60, 17 * 60 + 30, candidates.get(1), null, "car", remainingQuota,
                new HashSet<>(), new ScheduleConfig(9 * 60, 22 * 60, 0, false, pace), new int[]{0});
        return result.steps().stream().map(StepData::place).map(p -> p.name()).toList();
    }

    private static DayState day(Integer... places) {
        return new DayState(new SelectionOutput.DayPlan(1, null, new ArrayList<>(List.of(places)), null, null));
    }

    private static PlaceCandidate place(int index, String name, String category, String role,
                                        double lat, double lng, Integer sitelinks) {
        return new PlaceCandidate(index, (long) index, name, "서울 성동구 테스트로 " + index, category, List.of(), "Seoul",
                "KR", BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null, null, 0.9, null, null,
                null, 60, false, null, null, null, sitelinks, role, null, false, null);
    }
}
