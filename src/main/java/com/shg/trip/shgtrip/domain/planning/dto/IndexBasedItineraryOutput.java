package com.shg.trip.shgtrip.domain.planning.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 인덱스 기반 LLM output.
 * 장소 전체 정보 대신 인덱스 번호만 포함하여 output 토큰을 대폭 절감한다.
 */
public record IndexBasedItineraryOutput(
        String title,
        String destination,
        BigDecimal estimatedCost,
        List<String> tags,
        List<IndexStepData> steps
) {}
