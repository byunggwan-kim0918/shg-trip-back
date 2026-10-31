package com.shg.trip.shgtrip.domain.planning.eval;

import com.shg.trip.shgtrip.domain.planning.service.ScoreReport;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

final class EvalReport {

    private EvalReport() {}

    private static final List<String> COMPONENTS =
            List.of("travel", "timeUse", "visits", "mealVariety", "lodging", "prominence", "intent");
    private static final List<String> METRICS =
            List.of("visitsPerDay", "maxGapMinutes", "longGaps", "lodgings", "totalStraightKm", "selectionRetention");

    static String markdown(String mode, List<EvalRunner.Row> rows) {
        StringBuilder sb = new StringBuilder("# 일정 평가 (mode=").append(mode).append(")\n\n");
        sb.append("| 일정 | 여행지 | 기록 | 하드 | 점수 | 재실행 하드 | 재실행 점수 | 동일 경로 |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (EvalRunner.Row r : rows) {
            sb.append("| ").append(r.itineraryId())
                    .append(" | ").append(r.destination())
                    .append(" | ").append(r.hasTrace() ? "O" : "-")
                    .append(" | ").append(r.saved().hardViolations())
                    .append(" | ").append(r.saved().total())
                    .append(" | ").append(r.replayed() == null ? "-" : r.replayed().hardViolations())
                    .append(" | ").append(r.replayed() == null ? "-" : r.replayed().total())
                    .append(" | ").append(r.sameRoute() == null ? "-" : r.sameRoute() ? "O" : "X")
                    .append(" |\n");
        }
        sb.append("\n## 평균 (저장본)\n\n");
        appendAverages(sb, rows, EvalRunner.Row::saved);
        if (rows.stream().anyMatch(r -> r.replayed() != null)) {
            sb.append("\n## 평균 (현재 코드 재실행)\n\n");
            appendAverages(sb, rows.stream().filter(r -> r.replayed() != null).toList(), EvalRunner.Row::replayed);
        }
        if (rows.stream().anyMatch(r -> !r.hasTrace())) {
            sb.append("\n> 기록 없는 일정은 pace·이동수단을 몰라 normal·any(가장 엄격한 구간 상한)로 판정했다. "
                    + "자동차 여행의 \"구간 상한 초과\"는 과하게 잡혔을 수 있다.\n");
        }
        sb.append("\n## 하드 위반 (저장본)\n\n");
        for (EvalRunner.Row r : rows) {
            if (r.saved().violations().isEmpty()) continue;
            sb.append("- ").append(r.itineraryId()).append(": ")
                    .append(String.join(", ", r.saved().violations())).append("\n");
        }
        return sb.toString();
    }

    private static void appendAverages(StringBuilder sb, List<EvalRunner.Row> rows,
                                       Function<EvalRunner.Row, ScoreReport> pick) {
        if (rows.isEmpty()) return;
        sb.append("- 점수 ").append(fmt(rows.stream().mapToDouble(r -> pick.apply(r).total()).average().orElse(0)))
                .append(" / 하드 위반 ")
                .append(fmt(rows.stream().mapToDouble(r -> pick.apply(r).hardViolations()).average().orElse(0)))
                .append(" / 위반 있는 일정 ")
                .append(rows.stream().filter(r -> pick.apply(r).hardViolations() > 0).count())
                .append("/").append(rows.size()).append("\n");
        for (String c : COMPONENTS) {
            sb.append("- ").append(c).append(" ")
                    .append(fmt(rows.stream().mapToDouble(r -> pick.apply(r).components().getOrDefault(c, 0.0))
                            .average().orElse(0))).append("\n");
        }
        for (String m : METRICS) {
            double avg = rows.stream().map(r -> pick.apply(r).metrics().get(m))
                    .filter(v -> v != null).mapToDouble(Number::doubleValue).average().orElse(Double.NaN);
            if (!Double.isNaN(avg)) sb.append("- ").append(m).append(" ").append(fmt(avg)).append("\n");
        }
    }

    static String csv(List<EvalRunner.Row> rows) {
        StringBuilder sb = new StringBuilder("itinerary_id,source,hard,total");
        COMPONENTS.forEach(c -> sb.append(',').append(c));
        METRICS.forEach(m -> sb.append(',').append(m));
        sb.append('\n');
        for (EvalRunner.Row r : rows) {
            line(sb, r.itineraryId(), "saved", r.saved());
            if (r.replayed() != null) line(sb, r.itineraryId(), "replayed", r.replayed());
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, Long id, String source, ScoreReport s) {
        sb.append(id).append(',').append(source).append(',').append(s.hardViolations()).append(',').append(s.total());
        Map<String, Double> c = s.components();
        COMPONENTS.forEach(k -> sb.append(',').append(fmt(c.getOrDefault(k, 0.0))));
        METRICS.forEach(k -> {
            Number v = s.metrics().get(k);
            sb.append(',').append(v == null ? "" : fmt(v.doubleValue()));
        });
        sb.append('\n');
    }

    private static String fmt(double v) {
        return String.format("%.3f", v);
    }
}
