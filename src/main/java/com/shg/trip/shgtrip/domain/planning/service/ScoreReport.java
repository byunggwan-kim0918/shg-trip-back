package com.shg.trip.shgtrip.domain.planning.service;

import java.util.List;
import java.util.Map;

/**
 * 하드 위반을 점수에 섞지 않고 따로 센다. 섞으면 식사를 빼서 이동을 줄인 일정이 점수로 이긴다.
 *
 * @param total     소프트 항목 가중합(0~100)
 * @param estimated 정보가 없어 기본값으로 채운 항목
 */
public record ScoreReport(
        int hardViolations,
        double total,
        Map<String, Double> components,
        List<String> violations,
        List<String> estimated,
        Map<String, Number> metrics
) {
    public boolean betterThan(ScoreReport other) {
        if (hardViolations != other.hardViolations) return hardViolations < other.hardViolations;
        return total > other.total;
    }
}
