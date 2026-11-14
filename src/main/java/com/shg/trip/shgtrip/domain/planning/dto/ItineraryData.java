package com.shg.trip.shgtrip.domain.planning.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI Tool Use 응답의 전체 일정 스키마.
 *
 * @param qualityNotices 저장 전 미리보기 안내. 저장 관문이 저장할 일정 기준으로 다시 만든다. 없으면 빈 목록이다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ItineraryData(
        String title,
        String destination,
        BigDecimal estimatedCost,
        List<String> tags,
        List<StepData> steps,
        List<String> qualityNotices
) {
    public ItineraryData(String title, String destination, BigDecimal estimatedCost,
                         List<String> tags, List<StepData> steps) {
        this(title, destination, estimatedCost, tags, steps, List.of());
    }
}
