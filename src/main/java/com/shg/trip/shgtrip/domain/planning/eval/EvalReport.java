package com.shg.trip.shgtrip.domain.planning.eval;

import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules;
import com.shg.trip.shgtrip.domain.planning.service.ScoreReport;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

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
        if (rows.stream().anyMatch(r -> r.replayed() != null && !r.replayed().violations().isEmpty())) {
            sb.append("\n## 하드 위반 (재실행)\n\n");
            for (EvalRunner.Row r : rows) {
                if (r.replayed() == null || r.replayed().violations().isEmpty()) continue;
                sb.append("- ").append(r.itineraryId()).append(": ")
                        .append(String.join(", ", r.replayed().violations())).append("\n");
            }
        }
        appendRules(sb, rows);
        return sb.toString();
    }

    /** 관문을 켰다면 막혔을 비율. 저장본은 생성 당시 코드, 재실행은 현재 코드의 결과다. */
    private static void appendRules(StringBuilder sb, List<EvalRunner.Row> all) {
        sb.append("\n## 규칙 판정 (ItineraryRules v").append(ItineraryRules.VERSION).append(")\n\n");
        // 기록 없는 일정은 이동수단을 몰라 가장 좁은 거리 상한으로 판정돼 비율을 부풀린다
        List<EvalRunner.Row> rows = all.stream().filter(EvalRunner.Row::hasTrace).toList();
        if (rows.size() < all.size()) {
            sb.append("> 기록 없는 일정 ").append(all.size() - rows.size()).append("건은 이 절에서 뺐다.\n\n");
        }
        sb.append("| 국가 | 일정 | 저장본 HARD | 저장본 CORE | 재실행 HARD | 재실행 CORE | 종류 미상 끼니 |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        Map<String, List<EvalRunner.Row>> byCountry = rows.stream()
                .collect(Collectors.groupingBy(EvalRunner.Row::country, TreeMap::new, Collectors.toList()));
        byCountry.forEach((country, list) -> sb.append("| ").append(country)
                .append(" | ").append(list.size())
                .append(" | ").append(countWith(list, EvalRunner.Row::savedRules, ItineraryRules.Severity.HARD))
                .append(" | ").append(countWith(list, EvalRunner.Row::savedRules, ItineraryRules.Severity.CORE))
                .append(" | ").append(countWith(list, EvalRunner.Row::replayedRules, ItineraryRules.Severity.HARD))
                .append(" | ").append(countWith(list, EvalRunner.Row::replayedRules, ItineraryRules.Severity.CORE))
                .append(" | ").append(unknownShare(list))
                .append(" |\n"));

        sb.append("\n| 규칙 | 등급 | 저장본 | 재실행 |\n|---|---|---|---|\n");
        for (ItineraryRules.Rule rule : ItineraryRules.Rule.values()) {
            long saved = ruleCount(rows, EvalRunner.Row::savedRules, rule);
            long replayed = ruleCount(rows, EvalRunner.Row::replayedRules, rule);
            if (saved == 0 && replayed == 0) continue;
            sb.append("| ").append(rule).append(" | ").append(rule.severity)
                    .append(" | ").append(saved).append(" | ").append(replayed).append(" |\n");
        }

        sb.append("\n### 위반 목록 (재실행, 없으면 저장본)\n\n");
        for (EvalRunner.Row r : rows) {
            ItineraryRules.Verdict v = r.replayedRules() != null ? r.replayedRules() : r.savedRules();
            if (v == null || v.violations().isEmpty()) continue;
            sb.append("- ").append(r.itineraryId()).append(" (").append(r.country()).append("): ")
                    .append(v.violations().stream()
                            .map(x -> x.severity() + " " + x.message()).collect(Collectors.joining(", ")))
                    .append("\n");
        }
    }

    private static String countWith(List<EvalRunner.Row> rows, Function<EvalRunner.Row, ItineraryRules.Verdict> pick,
                                    ItineraryRules.Severity severity) {
        List<ItineraryRules.Verdict> verdicts = rows.stream().map(pick).filter(Objects::nonNull).toList();
        if (verdicts.isEmpty()) return "-";
        return verdicts.stream().filter(v -> v.has(severity)).count() + "/" + verdicts.size();
    }

    private static long ruleCount(List<EvalRunner.Row> rows, Function<EvalRunner.Row, ItineraryRules.Verdict> pick,
                                  ItineraryRules.Rule rule) {
        return rows.stream().map(pick).filter(Objects::nonNull)
                .mapToLong(v -> v.countByRule().getOrDefault(rule, 0L)).sum();
    }

    private static String unknownShare(List<EvalRunner.Row> rows) {
        int meals = 0, unknown = 0;
        for (EvalRunner.Row r : rows) {
            ItineraryRules.Verdict v = r.replayedRules() != null ? r.replayedRules() : r.savedRules();
            if (v == null) continue;
            meals += v.meals();
            unknown += v.unknownKindMeals();
        }
        return meals == 0 ? "-" : unknown + "/" + meals;
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
        StringBuilder sb = new StringBuilder("itinerary_id,country,source,hard,total");
        COMPONENTS.forEach(c -> sb.append(',').append(c));
        METRICS.forEach(m -> sb.append(',').append(m));
        sb.append(",rules_hard,rules_core,meals,unknown_meals");
        for (ItineraryRules.Rule rule : ItineraryRules.Rule.values()) sb.append(',').append(rule);
        sb.append('\n');
        for (EvalRunner.Row r : rows) {
            line(sb, r, "saved", r.saved(), r.savedRules());
            if (r.replayed() != null) line(sb, r, "replayed", r.replayed(), r.replayedRules());
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, EvalRunner.Row r, String source, ScoreReport s,
                             ItineraryRules.Verdict verdict) {
        sb.append(r.itineraryId()).append(',').append(r.country()).append(',').append(source)
                .append(',').append(s.hardViolations()).append(',').append(s.total());
        Map<String, Double> c = s.components();
        COMPONENTS.forEach(k -> sb.append(',').append(fmt(c.getOrDefault(k, 0.0))));
        METRICS.forEach(k -> {
            Number v = s.metrics().get(k);
            sb.append(',').append(v == null ? "" : fmt(v.doubleValue()));
        });
        if (verdict == null) {
            sb.append(",,,,");
            for (int i = 0; i < ItineraryRules.Rule.values().length; i++) sb.append(',');
        } else {
            sb.append(',').append(verdict.has(ItineraryRules.Severity.HARD))
                    .append(',').append(verdict.has(ItineraryRules.Severity.CORE))
                    .append(',').append(verdict.meals()).append(',').append(verdict.unknownKindMeals());
            Map<ItineraryRules.Rule, Long> counts = verdict.countByRule();
            for (ItineraryRules.Rule rule : ItineraryRules.Rule.values()) {
                sb.append(',').append(counts.getOrDefault(rule, 0L));
            }
        }
        sb.append('\n');
    }

    private static String fmt(double v) {
        return String.format("%.3f", v);
    }
}
