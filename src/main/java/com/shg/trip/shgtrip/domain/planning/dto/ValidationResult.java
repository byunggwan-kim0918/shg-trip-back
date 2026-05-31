package com.shg.trip.shgtrip.domain.planning.dto;

import java.util.List;

/**
 * 일정 검증 결과.
 */
public record ValidationResult(
        boolean valid,
        int score,          // 0~100
        String type,        // HARD, SOFT
        List<String> errors,
        List<String> warnings,
        String feedback
) {
    public static ValidationResult hardPass() {
        return new ValidationResult(true, 100, "HARD", List.of(), List.of(), null);
    }

    public static ValidationResult hardFail(List<String> errors) {
        return new ValidationResult(false, 0, "HARD", errors, List.of(), null);
    }

    public static ValidationResult softPass(int score, List<String> warnings, String feedback) {
        return new ValidationResult(true, score, "SOFT", List.of(), warnings != null ? warnings : List.of(), feedback);
    }

    public static ValidationResult softFail(int score, List<String> errors, List<String> warnings, String feedback) {
        return new ValidationResult(false, score, "SOFT", errors != null ? errors : List.of(),
                warnings != null ? warnings : List.of(), feedback);
    }
}
