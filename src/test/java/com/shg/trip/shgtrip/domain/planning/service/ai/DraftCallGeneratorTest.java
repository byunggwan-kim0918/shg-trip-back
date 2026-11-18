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
    @DisplayName("날짜 번호·요구·항목 칸이 맞는 초안만 받는다")
    void wellFormed() {
        DraftPlan.Day day1 = new DraftPlan.Day(1, "전주", null, List.of(item("morning", "sight")), List.of());
        DraftPlan.Day day2 = new DraftPlan.Day(2, "전주", null, List.of(), List.of());
        DraftPlan.Want want = new DraftPlan.Want("w1", "food", "피순대", null, "피순대", null, List.of());

        assertThat(DraftCallGenerator.wellFormed(plan(List.of(want), day1, day2), 2)).isTrue();
        assertThat(DraftCallGenerator.wellFormed(plan(List.of(), day1, day1), 2)).isFalse();
        assertThat(DraftCallGenerator.wellFormed(plan(List.of(), day1), 2)).isFalse();
        assertThat(DraftCallGenerator.wellFormed(plan(List.of(new DraftPlan.Want(null, "food", "피순대", null, "피순대",
                null, List.of())), day1, day2), 2)).isFalse();
        assertThat(DraftCallGenerator.wellFormed(plan(List.of(), new DraftPlan.Day(1, "전주", null,
                List.of(item(null, "sight")), List.of()), day2), 2)).isFalse();
        assertThat(DraftCallGenerator.wellFormed(plan(List.of(), new DraftPlan.Day(1, "전주", null,
                List.of(item("morning", "museum")), List.of()), day2), 2)).isFalse();
    }
}
