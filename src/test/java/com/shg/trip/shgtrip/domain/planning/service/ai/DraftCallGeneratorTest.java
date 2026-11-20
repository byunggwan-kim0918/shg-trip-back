package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.shg.trip.shgtrip.domain.planning.dto.DraftPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DraftCallGeneratorTest {

    private static DraftPlan.Item item(String slot, String kind) {
        return new DraftPlan.Item(slot, kind, "경기전", "한옥마을", true, null, null, null);
    }

    private static DraftPlan plan(List<DraftPlan.Want> wants, DraftPlan.Day... days) {
        return new DraftPlan("전주", wants, List.of(days));
    }

    @Test
    @DisplayName("날짜 번호와 요구 칸이 맞는 초안만 받는다")
    void wellFormed() {
        DraftPlan.Day day1 = new DraftPlan.Day(1, "전주", null, List.of(item("morning", "sight")), List.of());
        DraftPlan.Day day2 = new DraftPlan.Day(2, "전주", null, List.of(), List.of());
        DraftPlan.Want want = new DraftPlan.Want("w1", "food", "피순대", null, "피순대", null, List.of());

        assertThat(DraftCallGenerator.wellFormed(plan(List.of(want), day1, day2), 2)).isTrue();
        assertThat(DraftCallGenerator.wellFormed(plan(List.of(), day1, day1), 2)).isFalse();
        assertThat(DraftCallGenerator.wellFormed(plan(List.of(), day1), 2)).isFalse();
        assertThat(DraftCallGenerator.wellFormed(plan(List.of(new DraftPlan.Want(null, "food", "피순대", null, "피순대",
                null, List.of())), day1, day2), 2)).isFalse();
    }

    @Test
    @DisplayName("여행 날 수보다 많이 쓴 날은 버리고, 빠진 날이 있으면 받지 않는다")
    void extraDaysAreTrimmed() {
        DraftPlan.Day day1 = new DraftPlan.Day(1, "전주", null, List.of(), List.of());
        DraftPlan.Day day2 = new DraftPlan.Day(2, "전주", null, List.of(), List.of());
        DraftPlan.Day day3 = new DraftPlan.Day(3, "전주", null, List.of(), List.of());

        assertThat(DraftCallGenerator.wellFormed(DraftCallGenerator.withinDays(plan(List.of(), day1, day2, day3), 2), 2))
                .isTrue();
        assertThat(DraftCallGenerator.wellFormed(DraftCallGenerator.withinDays(plan(List.of(), day1, day3, day3), 2), 2))
                .isFalse();
    }

    @Test
    @DisplayName("시간대·종류가 맞지 않는 항목만 빼고 초안은 쓴다")
    void dropsMalformedItems() {
        DraftPlan.Day day1 = new DraftPlan.Day(1, "전주", null,
                List.of(item("morning", "sight"), item(null, "sight"), item("morning", "museum")),
                List.of(item("afternoon", null)));
        DraftPlan.Day day2 = new DraftPlan.Day(2, "전주", null, List.of(), List.of());

        DraftPlan cleaned = DraftCallGenerator.withoutMalformedItems(plan(List.of(), day1, day2));

        assertThat(cleaned.days().get(0).items()).hasSize(1);
        assertThat(cleaned.days().get(0).spares()).isEmpty();
        assertThat(DraftCallGenerator.wellFormed(cleaned, 2)).isTrue();
    }
}
