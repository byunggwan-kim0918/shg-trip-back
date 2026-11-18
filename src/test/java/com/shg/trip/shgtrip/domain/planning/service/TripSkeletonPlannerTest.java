package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.DraftPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TripSkeletonPlannerTest {

    @Test
    @DisplayName("초안이 어느 날에도 두지 않은 요구는 그 도시의 온전한 날에 넣는다")
    void missingWantIsPlaced() {
        DraftPlan.Want food = new DraftPlan.Want("w1", "food", "피순대 먹기", null, "피순대", null, List.of());
        DraftPlan.Want place = new DraftPlan.Want("w2", "place", "오목대", "오목대", null, null, List.of());
        DraftPlan.Item placed = new DraftPlan.Item("morning", "sight", "오목대", "한옥마을", true, "w2", null, null);
        DraftPlan draft = new DraftPlan("전주", List.of(food, place), List.of(
                new DraftPlan.Day(1, "전주 한옥마을", "한옥마을", List.of(placed), List.of()),
                new DraftPlan.Day(2, "전주 남부시장", null, List.of(), List.of())));
        List<String> notes = new ArrayList<>();

        DraftPlan out = TripSkeletonPlanner.withAllWants(draft, 2, List.of("전주"), notes);

        List<DraftPlan.Item> all = out.days().stream().flatMap(d -> d.items().stream()).toList();
        assertThat(all).filteredOn(i -> "w1".equals(i.want())).singleElement()
                .satisfies(i -> {
                    assertThat(i.kind()).isEqualTo("meal");
                    assertThat(i.name()).isEqualTo("피순대");
                });
        assertThat(all).filteredOn(i -> "w2".equals(i.want())).hasSize(1);
        assertThat(notes).hasSize(1);
    }

    @Test
    @DisplayName("도시 배분 요구에서 도시별 날 수를 읽는다")
    void readsCityDays() {
        assertThat(TripSkeletonPlanner.cityDaysOf("서울 1일, 전주 3일", List.of("서울", "전주")))
                .isEqualTo(Map.of("서울", 1, "전주", 3));
        assertThat(TripSkeletonPlanner.cityDaysOf("부산과 경주를 둘러보고 싶어요", List.of("부산", "경주"))).isEmpty();
    }
}
