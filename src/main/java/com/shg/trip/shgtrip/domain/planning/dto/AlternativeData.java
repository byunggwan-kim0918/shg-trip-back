package com.shg.trip.shgtrip.domain.planning.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * AI Tool Use 응답의 대안 장소 스키마.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlternativeData(
        String name,
        String address,
        String category,
        String region,
        String country,
        String notes,
        BigDecimal estimatedCost
) {}
