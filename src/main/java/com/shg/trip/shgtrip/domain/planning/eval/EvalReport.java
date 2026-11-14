package com.shg.trip.shgtrip.domain.planning.eval;

import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryScorer;
import com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning;
import com.shg.trip.shgtrip.domain.planning.service.ScoreReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

final class EvalReport {

    private EvalReport() {}

    private static final List<String> COMPONENTS =
            List.of("travel", "timeUse", "visits", "mealVariety", "lodging", "prominence", "intent");
    private static final List<String> METRICS =
            List.of("visitsPerDay", "maxGapMinutes", "longGaps", "lodgings", "totalStraightKm", "selectionRetention");

    static String markdown(String mode, List<EvalRunner.Row> rows) {
        StringBuilder sb = new StringBuilder("# 일정 평가 (mode=").append(mode)
                .append(", ItineraryScorer v").append(ItineraryScorer.VERSION).append(")\n\n");
        sb.append("| 일정 | 여행지 | 기록 | 하드 | 점수 | 재실행 하드 | 재실행 점수 | 동일 경로 |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (EvalRunner.Row r : rows) {
            sb.append("| ").append(r.itineraryId())
                    .append(" | ").append(r.destination())
                    .append(" | ").append(r.hasTrace() ? "O" : "-")
                    .append(" | ").append(r.saved() == null ? "-" : r.saved().hardViolations())
                    .append(" | ").append(r.saved() == null ? "-" : r.saved().total())
                    .append(" | ").append(r.replayed() == null ? "-" : r.replayed().hardViolations())
                    .append(" | ").append(r.replayed() == null ? "-" : r.replayed().total())
                    .append(" | ").append(r.sameRoute() == null ? "-" : r.sameRoute() ? "O" : "X")
                    .append(" |\n");
        }
        List<EvalRunner.Row> withSaved = rows.stream().filter(r -> r.saved() != null).toList();
        if (!withSaved.isEmpty()) {
            sb.append("\n## 평균 (저장본)\n\n");
            appendAverages(sb, withSaved, EvalRunner.Row::saved);
        }
        if (rows.stream().anyMatch(r -> r.replayed() != null)) {
            sb.append("\n## 평균 (현재 코드 재실행)\n\n");
            appendAverages(sb, rows.stream().filter(r -> r.replayed() != null).toList(), EvalRunner.Row::replayed);
        }
        if (rows.stream().anyMatch(r -> !r.hasTrace())) {
            sb.append("\n> 기록 없는 일정은 pace·이동수단을 몰라 normal·any(가장 엄격한 구간 상한)로 판정했다. "
                    + "자동차 여행의 \"구간 상한 초과\"는 과하게 잡혔을 수 있다.\n");
        }
        if (!withSaved.isEmpty()) sb.append("\n## 하드 위반 (저장본)\n\n");
        for (EvalRunner.Row r : withSaved) {
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
        appendByLength(sb, rows);
        return sb.toString();
    }

    /** 날짜 간 배분의 효과는 긴 여행에서만 드러나 전체 평균으로 보면 묻힌다. */
    private static void appendByLength(StringBuilder sb, List<EvalRunner.Row> all) {
        Map<String, List<EvalRunner.Row>> groups = new LinkedHashMap<>();
        for (String scope : List.of("국내", "해외")) {
            for (String length : List.of("2일 이하", "3일", "4일 이상", "여러 도시", RECORDED_TOTAL,
                    "합성 여러 도시", "합성 인접 지역")) {
                groups.put(scope + " " + length, new ArrayList<>());
            }
        }
        for (EvalRunner.Row r : all) {
            if (!r.hasTrace() || r.current() == null) continue;
            String scope = "KR".equals(r.country()) ? "국내 " : "해외 ";
            groups.get(scope + lengthOf(r.trip())).add(r);
            if (!r.trip().joined()) groups.get(scope + RECORDED_TOTAL).add(r);
        }
        sb.append("\n## 여행 길이별 (재실행, 없으면 저장본)\n\n");
        sb.append("> 뒤/앞 점수는 4일 이상 일정에서 마지막 이틀 평균을 처음 이틀 평균으로 나눈 값이고, ")
                .append("뒤−앞 유명도는 같은 이틀의 방문지 유명도 평균 차이다. ")
                .append("관광 하한은 pace의 하루 관광 수 최소값이고 첫날·마지막 날은 절반(올림)이다. ")
                .append("합성은 기록 두 건을 이어 붙인 입력이다.\n\n");
        sb.append("| 묶음 | 일정 | 점수 | 같은 날 유지 | 조정 필요 | 거리 위반 | 끼니 누락 | 종류 미상 끼니 | 하루 관광 "
                + "| 관광 하한 충족 날 | 최대 공백(분) | 하루 종료 | 뒤/앞 점수 | 뒤−앞 유명도 | 확정 후보 수 | 구역 없음 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        groups.forEach((label, rows) -> {
            if (rows.isEmpty()) return;
            int[] floor = sightFloorDays(rows);
            sb.append("| ").append(label)
                    .append(" | ").append(rows.size())
                    .append(" | ").append(avg(rows, r -> r.current().total(), "%.1f"))
                    .append(" | ").append(avgMetric(rows, "selectionRetention"))
                    .append(" | ").append(rows.stream().filter(EvalReport::needsAdjustment).count())
                    .append(" | ").append(withRule(rows, ItineraryRules.Rule.LEG_OVER_CAP,
                            ItineraryRules.Rule.DAY_DISTANCE_OVER_CAP))
                    .append(" | ").append(withRule(rows, ItineraryRules.Rule.LUNCH_MISSING,
                            ItineraryRules.Rule.DINNER_MISSING))
                    .append(" | ").append(unknownShare(rows))
                    .append(" | ").append(avgMetric(rows, "visitsPerDay"))
                    .append(" | ").append(floor[0]).append('/').append(floor[1])
                    .append(" | ").append(avgMetric(rows, "maxGapMinutes"))
                    .append(" | ").append(clock(dayEnd(rows)))
                    .append(" | ").append(backVersusFront(rows, ScoreReport.DayScore::score, 0, true, "%.3f"))
                    .append(" | ").append(backVersusFront(rows,
                            d -> d.prominence() == null ? Double.NaN : d.prominence(), Double.NaN, false, "%+.3f"))
                    .append(" | ").append(avg(rows, r -> r.trip().pool(), "%.0f"))
                    .append(" | ").append(rows.stream().filter(r -> r.trip().zones() == 0).count())
                    .append(" |\n");
        });
    }

    /** 이어 붙인 입력은 근사라 실기록 합계에 넣지 않는다. */
    private static final String RECORDED_TOTAL = "전체(합성 제외)";

    private static String lengthOf(EvalRunner.Trip t) {
        if (t.joined()) return t.multiCity() ? "합성 여러 도시" : "합성 인접 지역";
        if (t.multiCity()) return "여러 도시";
        return t.days() <= 2 ? "2일 이하" : t.days() == 3 ? "3일" : "4일 이상";
    }

    private static boolean needsAdjustment(EvalRunner.Row r) {
        ItineraryRules.Verdict v = r.currentRules();
        return v != null && (v.has(ItineraryRules.Severity.CORE) || v.has(ItineraryRules.Severity.HARD));
    }

    private static long withRule(List<EvalRunner.Row> rows, ItineraryRules.Rule... anyOf) {
        return rows.stream().map(EvalRunner.Row::currentRules).filter(Objects::nonNull)
                .filter(v -> {
                    for (ItineraryRules.Rule rule : anyOf) {
                        if (v.countByRule().getOrDefault(rule, 0L) > 0) return true;
                    }
                    return false;
                }).count();
    }

    private static Map<Integer, ScoreReport.DayScore> byDay(EvalRunner.Row r) {
        return r.current().days().stream()
                .collect(Collectors.toMap(ScoreReport.DayScore::day, Function.identity(), (a, b) -> a));
    }

    /** [하한을 채운 날, 전체 날]. 스텝이 없는 날은 방문 0곳으로 센다. */
    private static int[] sightFloorDays(List<EvalRunner.Row> rows) {
        int met = 0, total = 0;
        for (EvalRunner.Row r : rows) {
            Map<Integer, ScoreReport.DayScore> days = byDay(r);
            int last = r.trip().days();
            int min = ScheduleTuning.sightsPerDay(r.trip().pace())[0];
            for (int day = 1; day <= last; day++) {
                int floor = day == 1 || day == last ? (int) Math.ceil(min / 2.0) : min;
                ScoreReport.DayScore score = days.get(day);
                total++;
                if (score != null && score.visits() >= floor) met++;
            }
        }
        return new int[]{met, total};
    }

    /** 마지막 날은 출발 시각에 맞춰 끝나 평균에서 뺀다. */
    private static OptionalDouble dayEnd(List<EvalRunner.Row> rows) {
        return rows.stream()
                .flatMap(r -> r.current().days().stream().filter(d -> d.day() < r.trip().days()))
                .mapToInt(ScoreReport.DayScore::lastActivityEnd)
                .filter(end -> end > 0)
                .average();
    }

    private static String clock(OptionalDouble minutes) {
        if (minutes.isEmpty()) return "-";
        int m = (int) Math.round(minutes.getAsDouble());
        return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60);
    }

    /**
     * 유명도는 하한 근처 값이 많아 비율로 보면 튄다.
     *
     * @param missing 스텝이 없는 날의 값. NaN이면 평균에서 뺀다
     */
    private static String backVersusFront(List<EvalRunner.Row> rows, ToDoubleFunction<ScoreReport.DayScore> value,
                                          double missing, boolean asRatio, String format) {
        OptionalDouble average = rows.stream()
                .filter(r -> r.trip().days() >= 4)
                .mapToDouble(r -> {
                    Map<Integer, ScoreReport.DayScore> days = byDay(r);
                    int last = r.trip().days();
                    double front = mean(days, List.of(1, 2), value, missing);
                    double back = mean(days, List.of(last - 1, last), value, missing);
                    if (!asRatio) return back - front;
                    return front > 0 ? back / front : Double.NaN;
                })
                .filter(v -> !Double.isNaN(v))
                .average();
        return average.isPresent() ? String.format(Locale.ROOT, format, average.getAsDouble()) : "-";
    }

    private static double mean(Map<Integer, ScoreReport.DayScore> days, List<Integer> dayNumbers,
                               ToDoubleFunction<ScoreReport.DayScore> value, double missing) {
        return dayNumbers.stream()
                .mapToDouble(day -> days.containsKey(day) ? value.applyAsDouble(days.get(day)) : missing)
                .filter(v -> !Double.isNaN(v))
                .average().orElse(Double.NaN);
    }

    private static String avg(List<EvalRunner.Row> rows, ToDoubleFunction<EvalRunner.Row> value, String format) {
        return String.format(Locale.ROOT, format, rows.stream().mapToDouble(value).average().orElse(0));
    }

    private static String avgMetric(List<EvalRunner.Row> rows, String metric) {
        OptionalDouble average = rows.stream().map(r -> r.current().metrics().get(metric))
                .filter(Objects::nonNull).mapToDouble(Number::doubleValue).average();
        return average.isPresent() ? fmt(average.getAsDouble()) : "-";
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
            ItineraryRules.Verdict v = r.currentRules();
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
            ItineraryRules.Verdict v = r.currentRules();
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
        sb.append(",days,pace,transport,multi_city,joined,pool,search_pool,zones,day_scores,day_visits,day_prominence,day_end");
        sb.append('\n');
        for (EvalRunner.Row r : rows) {
            if (r.saved() != null) line(sb, r, "saved", r.saved(), r.savedRules());
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
        EvalRunner.Trip t = r.trip();
        sb.append(',').append(t.days())
                .append(',').append(Objects.toString(t.pace(), ""))
                .append(',').append(Objects.toString(t.transport(), ""))
                .append(',').append(t.multiCity())
                .append(',').append(t.joined())
                .append(',').append(t.pool())
                .append(',').append(Objects.toString(t.searchPool(), ""))
                .append(',').append(t.zones())
                .append(',').append(perDay(s, d -> fmt(d.score())))
                .append(',').append(perDay(s, d -> String.valueOf(d.visits())))
                .append(',').append(perDay(s, d -> d.prominence() == null ? "" : fmt(d.prominence())))
                .append(',').append(perDay(s, d -> String.valueOf(d.lastActivityEnd())));
        sb.append('\n');
    }

    private static String perDay(ScoreReport s, Function<ScoreReport.DayScore, String> value) {
        return s.days().stream().map(value).collect(Collectors.joining(";"));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
