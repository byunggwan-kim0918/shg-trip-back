package com.shg.trip.shgtrip.domain.planning.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI Tool Use 응답 - 전체 일정 스키마.
 *
 * @param qualityNotices 끝내 해소하지 못한 품질 문제의 사용자 안내 문구(없으면 빈 목록).
 *                       "저장하되 알린다"는 graceful degradation 계약의 전달 수단이다 —
 *                       예전엔 로그로만 남아 숙소 없는 일정이 그대로 사용자에게 나갔다.
 */
public record ItineraryData(
        String title,
        String destination,
        BigDecimal estimatedCost,
        List<String> tags,
        List<StepData> steps,
        List<String> qualityNotices
) {
    /** qualityNotices 없이 생성하는 기존 호환 생성자. */
    public ItineraryData(String title, String destination, BigDecimal estimatedCost,
                         List<String> tags, List<StepData> steps) {
        this(title, destination, estimatedCost, tags, steps, List.of());
    }
}
