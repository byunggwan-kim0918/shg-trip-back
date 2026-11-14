package com.shg.trip.shgtrip.domain.planning.service;

import java.util.List;
import java.util.Map;

/**
 * 하드 위반을 점수에 섞지 않고 따로 센다. 섞으면 식사를 빼서 이동을 줄인 일정이 점수로 이긴다.
 *
 * @param total     소프트 항목 가중합(0~100)
 * @param estimated 정보가 없어 기본값으로 채운 항목
 * @param days      일차 순서의 하루 단위 점수. 스텝이 없는 날은 빠져 있어 일차는 {@link DayScore#day()}로 읽는다
 */
public record ScoreReport(
        int hardViolations,
        double total,
        Map<String, Double> components,
        List<String> violations,
        List<String> estimated,
        Map<String, Number> metrics,
        List<DayScore> days
) {
    /**
     * @param score           하루 단위 항목(이동·시간 활용·방문 수)의 평균(0~1)
     * @param prominence      그날 방문지의 유명도 평균. 방문지가 없거나 후보를 모르면 null
     * @param lastActivityEnd 숙소·이동 거점을 뺀 마지막 일정이 끝나는 시각(분)
     */
    public record DayScore(int day, double score, int visits, Double prominence, int lastActivityEnd) {}

    public ScoreReport {
        days = days == null ? List.of() : List.copyOf(days);
    }

    public ScoreReport(int hardViolations, double total, Map<String, Double> components, List<String> violations,
                       List<String> estimated, Map<String, Number> metrics) {
        this(hardViolations, total, components, violations, estimated, metrics, List.of());
    }

    public boolean betterThan(ScoreReport other) {
        if (hardViolations != other.hardViolations) return hardViolations < other.hardViolations;
        return total > other.total;
    }
}
