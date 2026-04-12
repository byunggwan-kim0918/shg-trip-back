package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.planning.dto.EnrichedInput;
import com.shg.trip.shgtrip.domain.planning.dto.ItineraryData;
import com.shg.trip.shgtrip.domain.planning.dto.SoftEvaluationResult;
import com.shg.trip.shgtrip.domain.planning.dto.ValidationResult;

import java.util.List;

/**
 * AI 기반 일정 생성 서비스 인터페이스.
 * Claude LLM을 활용하여 입력 보강, 일정 생성, 보강, 재생성을 수행합니다.
 *
 * Requirements: 2.1, 2.2, 4.2, 4.4, 4.6
 */
public interface AIService {

    /**
     * 사용자 입력을 Haiku 4.5로 보강합니다.
     * 여행지 컨텍스트, 추천 정보 등을 추가합니다.
     *
     * @param input 사용자 일정 생성 요청
     * @return 보강된 입력 데이터
     */
    EnrichedInput enrichInput(ItineraryGenerateRequest input);

    /**
     * Sonnet 4.6으로 일정을 생성합니다.
     * Auto Mode: selectedPlaces가 빈 리스트, Manual Mode: 사용자 선택 장소 포함.
     *
     * @param enrichedInput 보강된 입력 데이터
     * @param selectedPlaces 사용자가 선택한 장소 목록 (Manual Mode)
     * @return 생성된 일정 데이터
     */
    ItineraryData generateItinerary(EnrichedInput enrichedInput, List<Place> selectedPlaces);

    /**
     * Sonnet 4.6으로 검증 피드백 기반 일정을 보강합니다.
     *
     * @param itinerary 기존 일정 데이터
     * @param feedback 검증 결과 피드백
     * @param input 원본 여행 조건 (예산, 여행지, 기간, 테마 — 보강 시 맥락 제공)
     * @return 보강된 일정 데이터
     */
    ItineraryData enhanceItinerary(ItineraryData itinerary, ValidationResult feedback, EnrichedInput input);

    /**
     * Sonnet 4.6으로 일정을 처음부터 재생성합니다.
     * 3회 보강 실패 시 호출됩니다.
     *
     * @param enrichedInput 보강된 입력 데이터
     * @param lastFailureReason 마지막 검증 실패 사유 (구체적 피드백)
     * @param selectedPlaces 사용자가 선택한 장소 목록 (Manual Mode, Auto Mode는 빈 리스트)
     * @return 재생성된 일정 데이터
     */
    ItineraryData regenerateItinerary(EnrichedInput enrichedInput, String lastFailureReason, List<Place> selectedPlaces);

    /**
     * Haiku 4.5로 일정 품질을 평가합니다. (Req 4.2)
     * 문맥 일관성(30점), 동선 효율성(40점), 정보 완전성(30점)을 평가하여 0~100 점수 반환.
     *
     * @param data 평가할 일정 데이터
     * @param input 여행 조건 (여행지, 테마, 기간, 예산)
     * @return AI 품질 평가 결과 (점수 + 이슈 목록)
     */
    SoftEvaluationResult evaluateSoftQuality(ItineraryData data, EnrichedInput input);
}
