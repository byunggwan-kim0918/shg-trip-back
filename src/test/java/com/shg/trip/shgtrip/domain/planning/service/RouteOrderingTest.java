package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RouteOrderingTest {

    private static final String SIGHT = "Landmarks and Outdoors > Historic and Protected Site";
    private static final double[] START = {37.5000, 127.0000};

    @Test
    @DisplayName("출발점에서 가까운 순으로 잇는다")
    void ordersFromStart() {
        DayState day = day();

        assertThat(order(day, places(null))).containsExactly(1, 2, 3, 4);
    }

    @Test
    @DisplayName("아침에 가야 하는 곳은 처음부터 첫 자리에 두고 나머지를 그곳에서 이어 정한다")
    void morningOnlyLeadsAndRestFollowsFromIt() {
        DayState day = day();
        day.morningOnlyFirst = true;

        assertThat(order(day, places("morning"))).containsExactly(4, 3, 2, 1);
    }

    @Test
    @DisplayName("영업시간 때문에 아침 맨 앞에 둔 곳도 같은 방식으로 정한다")
    void morningOpeningSlotLeads() {
        DayState day = day();
        day.openingSlots.put(4, DayState.Slot.MORNING_FIRST);

        assertThat(order(day, places(null))).containsExactly(4, 3, 2, 1);
    }

    private static List<Integer> order(DayState day, List<PlaceCandidate> places) {
        return RouteOrdering.orderDay(day, places, List.of(), Set.of(), Set.of(), START, null);
    }

    private static DayState day() {
        return new DayState(new SelectionOutput.DayPlan(1, null, new ArrayList<>(List.of(1, 2, 3, 4)), null, null));
    }

    /** 출발점에서 동쪽으로 1km씩 떨어진 네 곳. 마지막 곳에 시간대 의도를 붙인다. */
    private static List<PlaceCandidate> places(String lastIntent) {
        List<PlaceCandidate> out = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            PlaceCandidate c = new PlaceCandidate(i, (long) i, "장소" + i, "주소" + i, SIGHT, List.of(), "Seoul", "KR",
                    BigDecimal.valueOf(37.5000), BigDecimal.valueOf(127.0010 + (i - 1) * 0.0113), null, null, 0.8, null,
                    null, null, 60, false, null, null, null, null, "SIGHT", null, false, null);
            out.add(i == 4 && lastIntent != null ? c.withIntent(true, lastIntent) : c);
        }
        return out;
    }
}
