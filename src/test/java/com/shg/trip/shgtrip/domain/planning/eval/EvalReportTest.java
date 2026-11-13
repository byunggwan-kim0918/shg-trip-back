package com.shg.trip.shgtrip.domain.planning.eval;

import com.shg.trip.shgtrip.domain.planning.service.ScoreReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvalReportTest {

    @Test
    @DisplayName("판정이 없는 행도 CSV 열 개수가 머리글과 같다")
    void csvColumnsAlignWithoutVerdict() {
        ScoreReport score = new ScoreReport(0, 80, Map.of(), List.of(), List.of(), Map.of());
        EvalRunner.Row row = new EvalRunner.Row(1L, "부산", "KR", false, score, null, null, null, null);

        List<String> lines = EvalReport.csv(List.of(row)).lines().toList();

        int header = lines.get(0).split(",", -1).length;
        assertThat(lines.subList(1, lines.size())).allSatisfy(l -> assertThat(l.split(",", -1)).hasSize(header));
    }
}
