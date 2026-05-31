package com.shg.trip.shgtrip.domain.itinerary.dto;

import com.shg.trip.shgtrip.domain.itinerary.entity.AlternativeOption;

import java.math.BigDecimal;

public record AlternativeOptionResponse(
        Long id,
        Integer optionOrder,
        PlaceResponse place,
        String notes,
        BigDecimal estimatedCost
) {
    public static AlternativeOptionResponse from(AlternativeOption alt) {
        return new AlternativeOptionResponse(
                alt.getId(),
                alt.getOptionOrder(),
                PlaceResponse.from(alt.getPlace()),
                alt.getNotes(),
                alt.getEstimatedCost()
        );
    }
}
