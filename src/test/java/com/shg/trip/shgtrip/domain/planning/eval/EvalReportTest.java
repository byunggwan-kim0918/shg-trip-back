package com.shg.trip.shgtrip.domain.planning.eval;

import com.shg.trip.shgtrip.domain.planning.service.ItineraryScorer;
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
        EvalRunner.Row row = new EvalRunner.Row(1L, "부산", "KR", false, score, null, null, null, null,
                EvalRunner.Trip.untraced(2));

        List<String> lines = EvalReport.csv(List.of(row)).lines().toList();

        int header = lines.get(0).split(",", -1).length;
        assertThat(lines.subList(1, lines.size())).allSatisfy(l -> assertThat(l.split(",", -1)).hasSize(header));
    }

    @Test
    @DisplayName("저장본이 없는 행은 재실행 줄만 내고 머리글과 열 개수가 같다")
    void csvSkipsMissingSaved() {
        EvalRunner.Row row = new EvalRunner.Row(1900023L, "서울 부산", "KR", true, null, fourDays(), null, null, null,
                new EvalRunner.Trip(4, "normal", "walk", true, true, 80, null, 0));

        List<String> lines = EvalReport.csv(List.of(row)).lines().toList();

        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).startsWith("1900023,KR,replayed,");
        assertThat(lines.get(1).split(",", -1)).hasSize(lines.get(0).split(",", -1).length);
    }

    @Test
    @DisplayName("여행 길이별 표는 국내·해외와 길이로 묶고 뒤 이틀을 앞 이틀과 견준다")
    void groupsByLength() {
        ScoreReport twoDays = new ScoreReport(0, 90, Map.of(), List.of(), List.of(), Map.of(), List.of(
                new ScoreReport.DayScore(1, 1.0, 1, null, 1200), new ScoreReport.DayScore(2, 1.0, 0, null, 900)));
        ScoreReport firstDayEmpty = new ScoreReport(1, 60, Map.of(), List.of(), List.of(), Map.of(), List.of(
                new ScoreReport.DayScore(2, 0.8, 2, null, 1140), new ScoreReport.DayScore(3, 0.4, 2, null, 1140),
                new ScoreReport.DayScore(4, 0.4, 1, null, 900)));
        List<EvalRunner.Row> rows = List.of(
                new EvalRunner.Row(1L, "부산", "KR", true, twoDays, null, true, null, null,
                        new EvalRunner.Trip(2, "normal", "car", false, false, 40, null, 3)),
                new EvalRunner.Row(2L, "부산", "KR", true, null, fourDays(), null, null, null,
                        new EvalRunner.Trip(4, "normal", "car", false, false, 60, 48, 0)),
                new EvalRunner.Row(3L, "도쿄 오사카", "JP", true, null, fourDays(), null, null, null,
                        new EvalRunner.Trip(4, "normal", "any", true, false, 90, null, 4)),
                new EvalRunner.Row(1900023L, "서울 부산", "KR", true, null, firstDayEmpty, null, null, null,
                        new EvalRunner.Trip(4, "normal", "walk", true, true, 100, null, 0)));

        String report = EvalReport.markdown("files", rows);

        assertThat(report).contains("ItineraryScorer v" + ItineraryScorer.VERSION);
        // 2일: 첫날 하한 1곳 충족, 마지막 날 0곳 미충족. 하루 종료는 마지막 날을 뺀 20:00.
        assertThat(report).contains(
                "| 국내 2일 이하 | 1 | 90.0 | - | 0 | 0 | 0 | - | - | 1/2 | - | 20:00 | - | - | 40 | 0 |");
        // 4일: 앞 이틀 0.8, 뒤 이틀 0.4. 유명도 0.6 → 0.3.
        assertThat(report).contains(
                "| 국내 4일 이상 | 1 | 70.0 | - | 0 | 0 | 0 | - | - | 4/4 | - | 19:00 | 0.500 | -0.300 | 60 | 1 |");
        // 1일차가 빈 4일: 빈 날은 방문 0곳·점수 0으로 세어 하한 3/4, 앞 이틀 0.4, 뒤 이틀 0.4.
        assertThat(report).contains(
                "| 국내 합성 여러 도시 | 1 | 60.0 | - | 0 | 0 | 0 | - | - | 3/4 | - | 19:00 | 1.000 | - | 100 | 1 |");
        assertThat(report).contains("## 평균 (저장본)");
        assertThat(report).contains("| 해외 여러 도시 | 1 |");
        assertThat(report).contains(
                "| 국내 전체(합성 제외) | 2 | 80.0 | - | 0 | 0 | 0 | - | - | 5/6 | - | 19:15 | 0.500 | -0.300 | 50 | 1 |");
        assertThat(report).doesNotContain("| 국내 3일 |");
    }

    private static ScoreReport fourDays() {
        return new ScoreReport(0, 70, Map.of(), List.of(), List.of(), Map.of(), List.of(
                new ScoreReport.DayScore(1, 0.8, 1, 0.6, 1140), new ScoreReport.DayScore(2, 0.8, 2, 0.6, 1140),
                new ScoreReport.DayScore(3, 0.4, 2, 0.3, 1140), new ScoreReport.DayScore(4, 0.4, 1, null, 900)));
    }
}
