package com.shg.trip.shgtrip.domain.itinerary.dto;

import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;
import com.shg.trip.shgtrip.domain.planning.dto.TransportRoute;
import com.shg.trip.shgtrip.domain.planning.service.SavedItineraryFacts;

import java.math.BigDecimal;
import java.util.List;

/**
 * @param notes     스텝 이야기. 대안을 고르면 그 대안에 실려 있던 글로 바뀌고, 없으면 null이다
 * @param placeNote 스텝 장소의 한 줄 설명(종류·알려진 정도). 숙소·교통 거점은 null
 * @param travelEstimated 이동 시간이 노선·시간표가 아닌 거리로 추정한 값인지. 경로 근거가 없거나 근거가 추정이면 true,
 *                        이동이 없으면 false
 * @param transportationRoute 이동 구간 근거(수단별 토막·공급자·신뢰도·그래프 판·확인 시각). 거리 추정만 했으면 null
 */
public record ItineraryStepResponse(
        Long id,
        Integer stepOrder,
        Integer dayNumber,
        String startTime,
        String endTime,
        PlaceResponse place,
        String transportationMode,
        Integer transportationDuration,
        BigDecimal transportationDistance,
        BigDecimal transportationCost,
        String notes,
        String placeNote,
        String userNotes,
        BigDecimal estimatedCost,
        List<AlternativeOptionResponse> alternatives,
        boolean travelEstimated,
        TransportRoute transportationRoute
) {
    public static ItineraryStepResponse from(ItineraryStep step) {
        TransportRoute route = TransportRoute.fromJson(step.getTransportationRoute());
        return new ItineraryStepResponse(
                step.getId(),
                step.getStepOrder(),
                step.getDayNumber(),
                step.getStartTime(),
                step.getEndTime(),
                PlaceResponse.from(step.getPlace()),
                step.getTransportationMode(),
                step.getTransportationDuration(),
                step.getTransportationDistance(),
                step.getTransportationCost(),
                step.getNotes(),
                SavedItineraryFacts.placeNote(step.getPlace()),
                step.getUserNotes(),
                step.getEstimatedCost(),
                step.getAlternatives().stream()
                        .map(alt -> AlternativeOptionResponse.from(alt, step.getPlace()))
                        .toList(),
                step.getTransportationDuration() != null && (route == null || route.estimated()),
                route == null ? null : route.forTraveler()
        );
    }
}
