package com.shg.trip.shgtrip.domain.planning.eval;

import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.service.SerialPlanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalRunnerScoringTest {

    private static final SelectionOutput RECORDED = new SelectionOutput("", List.of(
            new SelectionOutput.DayPlan(1, null, List.of(1, 2), null, null)), List.of(), List.of(),
            List.of(2), List.of(), List.of(new SelectionOutput.TimingHint(3, "evening")));

    @Test
    @DisplayName("직접 고른 팔은 고른 날짜별 장소와 기록의 요청 의도로 채점한다")
    void pickedArmKeepsTheRecordedIntent() {
        SelectionOutput picked = new SelectionOutput("", List.of(
                new SelectionOutput.DayPlan(1, null, List.of(5, 6), null, null)), List.of(), List.of());
        SerialPlanner.Result serial = new SerialPlanner.Result(List.of(), List.of(), List.of(), picked);

        SelectionOutput scoring = EvalRunner.scoringSelection(RECORDED, serial);

        assertThat(scoring.days()).isEqualTo(picked.days());
        assertThat(scoring.highlightIndices()).containsExactly(2);
        assertThat(scoring.timing()).isEqualTo(RECORDED.timing());
        assertThat(EvalRunner.scoringSelection(RECORDED, null)).isSameAs(RECORDED);
    }

    @Test
    @DisplayName("요청 의도 장소는 highlight와 timing을 합친 것이다")
    void requestedIndicesCombineHighlightsAndTiming() {
        assertThat(EvalRunner.requestedIndices(RECORDED)).containsExactlyInAnyOrder(2, 3);
    }
}
