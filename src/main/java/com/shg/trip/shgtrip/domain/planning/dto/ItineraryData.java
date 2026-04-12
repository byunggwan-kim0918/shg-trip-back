package com.shg.trip.shgtrip.domain.planning.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI Tool Use 응답 - 전체 일정 스키마.
 */
public record ItineraryData(
        String title,
        String destination,
        BigDecimal estimatedCost,
        List<String> tags,
        List<StepData> steps
) {}
