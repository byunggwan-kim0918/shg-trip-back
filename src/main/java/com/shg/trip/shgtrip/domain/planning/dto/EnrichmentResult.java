package com.shg.trip.shgtrip.domain.planning.dto;

/**
 * @param valid        현실성 검증 통과 여부
 * @param enrichedInput 보강된 입력 (valid=true일 때만 non-null)
 * @param errorCode    에러 코드 (UNREALISTIC_BUDGET, CONFLICTING_THEMES 등)
 * @param errorMessage 수정 제안 텍스트
 */
public record EnrichmentResult(
        boolean valid,
        VectorEnrichedInput enrichedInput,
        String errorCode,
        String errorMessage
) {

    public static EnrichmentResult success(VectorEnrichedInput enrichedInput) {
        return new EnrichmentResult(true, enrichedInput, null, null);
    }

    public static EnrichmentResult error(String errorCode, String errorMessage) {
        return new EnrichmentResult(false, null, errorCode, errorMessage);
    }
}
