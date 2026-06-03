package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 인덱스 기반 LLM output을 원본 후보 장소 데이터와 결합하여
 * 기존 ItineraryDataMapper/PlacePersistenceHelper와 호환되는 ItineraryData를 생성한다.
 *
 * IndexBasedItineraryOutput의 placeIndex(1-based) → candidates 리스트에서 PlaceData 조회.
 */
@Slf4j
@Component
public class IndexResultMapper {

    /**
     * 인덱스 기반 output과 후보 장소 목록을 결합하여 ItineraryData를 생성한다.
     *
     * @param output     LLM이 생성한 인덱스 기반 일정 output
     * @param candidates 벡터 검색으로 조회된 후보 장소 목록 (1-based 인덱스)
     * @return 기존 ItineraryDataMapper와 호환되는 ItineraryData
     * @throws IllegalArgumentException placeIndex가 후보 범위를 벗어난 경우
     */
    public ItineraryData mergeIndexOutput(IndexBasedItineraryOutput output, List<PlaceCandidate> candidates) {
        if (output == null) {
            throw new IllegalArgumentException("IndexBasedItineraryOutput must not be null");
        }
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("PlaceCandidate list must not be null or empty");
        }

        List<StepData> steps = new ArrayList<>();

        if (output.steps() != null) {
            for (IndexStepData indexStep : output.steps()) {
                StepData step = convertStep(indexStep, candidates);
                if (step != null) {
                    steps.add(step);
                }
            }
        }

        return new ItineraryData(
                output.title(),
                output.destination(),
                output.estimatedCost(),
                output.tags(),
                steps
        );
    }

    private StepData convertStep(IndexStepData indexStep, List<PlaceCandidate> candidates) {
        // placeIndex 유효성 검증 (1-based)
        int placeIndex = indexStep.placeIndex();
        if (placeIndex < 1 || placeIndex > candidates.size()) {
            log.error("placeIndex {} out of bounds (candidates size: {}), skipping step {}",
                    placeIndex, candidates.size(), indexStep.stepOrder());
            throw new IllegalArgumentException(
                    String.format("placeIndex %d is out of bounds (valid range: 1-%d)", placeIndex, candidates.size()));
        }

        PlaceCandidate candidate = candidates.get(placeIndex - 1);
        PlaceData placeData = toPlaceData(candidate);

        // alternatives 변환 (범위 밖 인덱스는 skip)
        List<AlternativeData> alternatives = convertAlternatives(indexStep.alternativeIndices(), candidates);

        return new StepData(
                indexStep.stepOrder(),
                indexStep.dayNumber(),
                indexStep.startTime(),
                indexStep.endTime(),
                placeData,
                alternatives,
                indexStep.transportationMode(),
                indexStep.transportationDuration(),
                indexStep.transportationDistance(),
                indexStep.transportationCost(),
                indexStep.notes(),
                indexStep.estimatedCost()
        );
    }

    private List<AlternativeData> convertAlternatives(List<Integer> alternativeIndices, List<PlaceCandidate> candidates) {
        if (alternativeIndices == null || alternativeIndices.isEmpty()) {
            return List.of();
        }

        List<AlternativeData> alternatives = new ArrayList<>();
        for (Integer altIndex : alternativeIndices) {
            if (altIndex < 1 || altIndex > candidates.size()) {
                log.warn("alternativeIndex {} out of bounds (candidates size: {}), skipping",
                        altIndex, candidates.size());
                continue; // alternatives는 skip 처리
            }
            PlaceCandidate altCandidate = candidates.get(altIndex - 1);
            alternatives.add(toAlternativeData(altCandidate));
        }
        return alternatives;
    }

    private PlaceData toPlaceData(PlaceCandidate candidate) {
        return new PlaceData(
                candidate.name(),
                candidate.address(), // VectorSearchResult에서 전달받은 address
                candidate.category(),
                candidate.region(),
                candidate.country()
        );
    }

    private AlternativeData toAlternativeData(PlaceCandidate candidate) {
        return new AlternativeData(
                candidate.name(),
                candidate.address(), // VectorSearchResult에서 전달받은 address
                candidate.category(),
                candidate.region(),
                candidate.country(),
                null, // notes는 인덱스 기반 output에서 제공되지 않음
                null  // estimatedCost는 인덱스 기반 output에서 제공되지 않음
        );
    }
}
