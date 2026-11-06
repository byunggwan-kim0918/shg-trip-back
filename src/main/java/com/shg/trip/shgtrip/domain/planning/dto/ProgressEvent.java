package com.shg.trip.shgtrip.domain.planning.dto;

public record ProgressEvent(
        int percentage,
        String message,
        String stage  // ENRICHING, GENERATING, VALIDATING, ENHANCING, REGENERATING
) {}
