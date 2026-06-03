package com.shg.trip.shgtrip.domain.planning.dto;

/**
 * 벡터 경로 hard validation 결과.
 * soft validation 없이 필수 필드, 시간 형식, stepOrder 연속성, dayNumber 일관성만 검증한다.
 *
 * @param valid         검증 통과 여부
 * @param failureReason 실패 시 사유 (통과 시 null)
 */
public record HardValidationResult(boolean valid, String failureReason) {

    public static HardValidationResult pass() {
        return new HardValidationResult(true, null);
    }

    public static HardValidationResult fail(String reason) {
        return new HardValidationResult(false, reason);
    }
}
