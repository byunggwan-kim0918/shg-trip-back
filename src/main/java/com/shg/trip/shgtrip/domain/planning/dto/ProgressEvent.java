package com.shg.trip.shgtrip.domain.planning.dto;

/**
 * SSE 진행률 이벤트.
 * Requirements: 10.2
 */
public record ProgressEvent(
        int percentage,
        String message,
        String stage  // ENRICHING, GENERATING, VALIDATING, ENHANCING, REGENERATING
) {}
