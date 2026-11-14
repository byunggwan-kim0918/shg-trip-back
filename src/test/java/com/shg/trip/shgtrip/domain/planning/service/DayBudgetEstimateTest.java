package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DayBudgetEstimateTest {

    private static final String PARK = "Landmarks and Outdoors > Park";
    private static final String HOTEL = "Travel and Transportation > Lodging > Hotel";

    /** 1·2는 1.8km 거리, 4는 2에서 동쪽 20km, 3(전날 숙소)은 1에서 북쪽 100km. 자동차 하루 상한은 115km. */
    private final List<PlaceCandidate> candidates = List.of(
            place(1, "아침 명소", PARK, "SIGHT", 37.50, 127.00),
            place(2, "점심 근처 명소", PARK, "SIGHT", 37.50, 127.02),
            place(3, "전날 숙소", HOTEL, "LODGING", 38.40, 127.00),
            place(4, "동쪽 명소", PARK, "SIGHT", 37.50, 127.25));

    @Test
    @DisplayName("전날 숙소에서 나오는 아침 구간까지 세면 같은 후보도 하루 상한을 넘는다")
    void countsMorningLegFromPreviousLodging() {
        DayState day = new DayState(new SelectionOutput.DayPlan(2, null, new ArrayList<>(List.of(1, 2)), null, null));
        ScheduleConfig cfg = new ScheduleConfig(9 * 60, 22 * 60, 0, false, "normal");

        assertThat(PlanEdits.wouldExceedDayBudget(day, candidates.get(3), candidates, "car", cfg)).isFalse();

        day.startLodgingIndex = 3;
        assertThat(PlanEdits.wouldExceedDayBudget(day, candidates.get(3), candidates, "car", cfg)).isTrue();
    }

    private static PlaceCandidate place(int index, String name, String category, String role, double lat, double lng) {
        return new PlaceCandidate(index, (long) index, name, "강원 테스트시 테스트로 " + index, category, List.of(), "Seoul",
                "KR", BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null, null, 0.9, null, null,
                null, 60, false, null, null, null, null, role, null, false, null);
    }
}
