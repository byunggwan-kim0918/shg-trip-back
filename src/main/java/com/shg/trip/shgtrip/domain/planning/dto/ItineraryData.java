package com.shg.trip.shgtrip.domain.planning.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI Tool Use 응답의 전체 일정 스키마.
 *
 * @param qualityNotices 끝내 해소하지 못한 품질 문제의 사용자 안내 문구. 없으면 빈 목록이다.
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
