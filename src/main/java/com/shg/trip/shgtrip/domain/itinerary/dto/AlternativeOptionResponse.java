package com.shg.trip.shgtrip.domain.itinerary.dto;

import com.shg.trip.shgtrip.domain.itinerary.entity.AlternativeOption;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.planning.service.SavedItineraryFacts;

import java.math.BigDecimal;

/**
 * @param notes 지금 스텝 장소와 견준 한 줄 설명. 저장된 값이 아니라 응답할 때 만든다
 */
public record AlternativeOptionResponse(
        Long id,
        Integer optionOrder,
        PlaceResponse place,
        String notes,
        BigDecimal estimatedCost
) {
    public static AlternativeOptionResponse from(AlternativeOption alt, Place current) {
        return new AlternativeOptionResponse(
                alt.getId(),
                alt.getOptionOrder(),
                PlaceResponse.from(alt.getPlace()),
                SavedItineraryFacts.alternativeNote(alt.getPlace(), current),
                alt.getEstimatedCost()
        );
    }
}
