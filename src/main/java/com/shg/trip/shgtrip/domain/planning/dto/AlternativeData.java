package com.shg.trip.shgtrip.domain.planning.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * AI Tool Use 응답의 대안 장소 스키마.
 *
 * @param placeId 후보에서 온 장소 id. LLM 응답에서는 읽지 않는다(지어낸 id가 다른 장소로 저장된다)
 * @param nameKo  해외 장소의 한국어 이름. 없으면 null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlternativeData(
        String name,
        String address,
        String category,
        String region,
        String country,
        String notes,
        BigDecimal estimatedCost,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY) Long placeId,
        String nameKo
) {
    public AlternativeData(String name, String address, String category, String region, String country,
                           String notes, BigDecimal estimatedCost) {
        this(name, address, category, region, country, notes, estimatedCost, null, null);
    }

    public AlternativeData withEstimatedCost(BigDecimal cost) {
        return new AlternativeData(name, address, category, region, country, notes, cost, placeId, nameKo);
    }
}
