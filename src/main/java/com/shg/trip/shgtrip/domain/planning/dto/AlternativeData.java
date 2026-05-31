package com.shg.trip.shgtrip.domain.planning.dto;

import java.math.BigDecimal;

/**
 * AI Tool Use 응답 - 대안 장소 스키마.
 * 장소 정보 외에 해당 대안 선택 시의 notes와 estimatedCost를 포함.
 */
public record AlternativeData(
        String name,
        String address,
        String category,
        String region,
        String country,
        String notes,
        BigDecimal estimatedCost
) {}
