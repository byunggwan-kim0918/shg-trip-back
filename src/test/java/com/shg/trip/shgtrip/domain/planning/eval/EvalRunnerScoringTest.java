package com.shg.trip.shgtrip.domain.planning.eval;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.service.SerialPlanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
        SerialPlanner.Result serial = new SerialPlanner.Result(List.of(), List.of(), List.of(), picked,
                java.util.Map.of());

        SelectionOutput scoring = EvalRunner.scoringSelection(RECORDED, serial);

        assertThat(scoring.days()).isEqualTo(picked.days());
        assertThat(scoring.highlightIndices()).containsExactly(2);
        assertThat(scoring.timing()).isEqualTo(RECORDED.timing());
        assertThat(EvalRunner.scoringSelection(RECORDED, null)).isSameAs(RECORDED);
    }

    @Test
    @DisplayName("기록된 선택 반영은 날짜와 상관없이 일정에 든 장소를 센다")
    void pickUseCountsPlacesAnywhereInTheItinerary() {
        List<PlaceCandidate> candidates = List.of(candidate(1, "궁"), candidate(2, "식당"), candidate(3, "전망대"));
        List<StepData> steps = List.of(new StepData(1, 3, "09:00", "10:00",
                new PlaceData("궁", null, "Landmarks and Outdoors", "Seoul", "KR", null, null, 1L, null), List.of(),
                null, null, null, null, null, null));

        assertThat(EvalRunner.pickUse(RECORDED, candidates, steps)).isEqualTo(new EvalRunner.PickUse(2, 1));
    }

    @Test
    @DisplayName("장소 id가 없는 후보는 이름과 주소가 모두 같아야 반영된 것으로 센다")
    void pickUseMatchesByNameAndAddressWithoutId() {
        PlaceCandidate chain = new PlaceCandidate(1, null, "체인 식당", "서울특별시 종로구 율곡로 1",
                "Dining and Drinking > Restaurant", List.of(), "Seoul", "KR", BigDecimal.valueOf(37.57),
                BigDecimal.valueOf(126.98), null, null, 0.8, null, null, null, 60, false, null, null, null, null,
                "MEAL", null, false, null);
        StepData otherBranch = new StepData(1, 1, "12:00", "13:00",
                new PlaceData("체인 식당", "서울특별시 강남구 테헤란로 9", "Dining and Drinking > Restaurant", "Seoul",
                        "KR"), List.of(), null, null, null, null, null, null);
        SelectionOutput selection = new SelectionOutput("", List.of(
                new SelectionOutput.DayPlan(1, null, List.of(1, 7), null, null)), List.of(), List.of());

        assertThat(EvalRunner.pickUse(selection, List.of(chain), List.of(otherBranch)))
                .isEqualTo(new EvalRunner.PickUse(1, 0));
    }

    private static PlaceCandidate candidate(int index, String name) {
        return new PlaceCandidate(index, (long) index, name, "서울특별시 종로구 율곡로 " + index,
                "Landmarks and Outdoors", List.of(), "Seoul", "KR", BigDecimal.valueOf(37.57),
                BigDecimal.valueOf(126.98), null, null, 0.8, null, null, null, 60, false, null, null, null, null,
                "SIGHT", null, false, null);
    }

    @Test
    @DisplayName("요청 의도 장소는 highlight와 timing을 합친 것이다")
    void requestedIndicesCombineHighlightsAndTiming() {
        assertThat(EvalRunner.requestedIndices(RECORDED)).containsExactlyInAnyOrder(2, 3);
    }
}
