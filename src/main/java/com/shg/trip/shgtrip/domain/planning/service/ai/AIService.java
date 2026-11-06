package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.planning.dto.EnrichedInput;
import com.shg.trip.shgtrip.domain.planning.dto.ItineraryData;
import com.shg.trip.shgtrip.domain.planning.dto.SoftEvaluationResult;
import com.shg.trip.shgtrip.domain.planning.dto.ValidationResult;

import java.util.List;

public interface AIService {

    EnrichedInput enrichInput(ItineraryGenerateRequest input);

    /** Auto Mode면 selectedPlaces는 빈 리스트다. */
    ItineraryData generateItinerary(EnrichedInput enrichedInput, List<Place> selectedPlaces);

    ItineraryData enhanceItinerary(ItineraryData itinerary, ValidationResult feedback, EnrichedInput input);

    /** 보강이 3회 실패하면 호출된다. Auto Mode면 selectedPlaces는 빈 리스트다. */
    ItineraryData regenerateItinerary(EnrichedInput enrichedInput, String lastFailureReason, List<Place> selectedPlaces);

    /** 문맥 일관성(30)·동선 효율성(40)·정보 완전성(30)으로 0~100점을 매긴다. */
    SoftEvaluationResult evaluateSoftQuality(ItineraryData data, EnrichedInput input);
}
