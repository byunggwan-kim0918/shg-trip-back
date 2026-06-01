package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.dto.*;
import com.shg.trip.shgtrip.domain.planning.service.ai.AIService;
import com.shg.trip.shgtrip.domain.planning.service.validation.ItineraryValidationService;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 비동기 일정 생성 파이프라인.
 * - TravelPlannerService와 분리하여 @Async 프록시 정상 동작 보장
 * - 각 AI 호출 전 취소 여부 확인 → 클라이언트 disconnect 시 불필요한 API 호출 방지
 * - 저장은 ItinerarySaveHelper(@Transactional)에 위임
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItineraryGenerationExecutor {

    private final AIService aiService;
    private final ItineraryValidationService validationService;
    private final ItinerarySaveHelper saveHelper;
    private final PlaceRepository placeRepository;
    private final GenerationResultStore resultStore;
    private final CancellationRegistry cancellationRegistry;

    /**
     * 비동기 일정 생성 파이프라인.
     * 진행 단계: 20% → 50% → 70% → 90% → 100%
     * 각 AI 호출 전 취소 여부 확인 — 취소 시 조용히 종료 (emitter는 이미 complete 처리됨).
     */
    @Async("planningExecutor")
    public void execute(String jobId, ItineraryGenerateRequest request, Long userId, SseEmitter emitter) {
        try {
            // 1. 입력 보강 (20%)
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendProgress(emitter, 20, "분석 중...", "ENRICHING");
            EnrichedInput enrichedInput = aiService.enrichInput(request);

            // 2. 선택 장소 조회
            if (cancellationRegistry.isCancelled(jobId)) return;
            List<Place> selectedPlaces = resolveSelectedPlaces(request);

            // 3. 일정 생성 (50%)
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendProgress(emitter, 50, "장소 탐색 중...", "GENERATING");
            ItineraryData itineraryData = aiService.generateItinerary(enrichedInput, selectedPlaces);

            // 4. 검증 + 보강 파이프라인 (70%)
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendProgress(emitter, 70, "동선 최적화 중...", "VALIDATING");
            ItineraryData validated = validationService.validateWithRetry(itineraryData, enrichedInput, selectedPlaces);

            // 4-1. Manual Mode: 선택 장소 포함 여부 검증
            validateSelectedPlacesIncluded(validated, selectedPlaces);

            // 5. 저장 (90%)
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendProgress(emitter, 90, "일정을 저장하고 있습니다...", "SAVING");
            Itinerary saved = saveHelper.save(validated, enrichedInput, userId);

            // 6. 완료 — 저장 후 취소 체크는 하지 않음 (이미 DB에 저장됐으므로 결과 전달)
            resultStore.save(jobId, saved.getId());
            sendProgress(emitter, 100, "일정 생성이 완료되었습니다.", "COMPLETE");
            sendComplete(emitter);

        } catch (BusinessException e) {
            log.error("Generation failed for job {}: {}", jobId, e.getMessage());
            sendError(emitter, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error during generation for job {}: {}", jobId, e.getMessage(), e);
            sendError(emitter, "일정 생성 중 예기치 않은 오류가 발생했습니다.");
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void validateSelectedPlacesIncluded(ItineraryData data, List<Place> requiredPlaces) {
        if (requiredPlaces == null || requiredPlaces.isEmpty()) return;

        Set<String> stepPlaceNames = data.steps().stream()
                .filter(s -> s.place() != null && s.place().name() != null)
                .map(s -> s.place().name().toLowerCase())
                .collect(Collectors.toSet());

        List<String> missing = requiredPlaces.stream()
                .filter(p -> !isPlaceIncluded(p.getName(), stepPlaceNames))
                .map(Place::getName)
                .toList();

        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                    "Manual Mode 선택 장소가 일정에 포함되지 않았습니다: " + String.join(", ", missing));
        }
    }

    private boolean isPlaceIncluded(String requiredName, Set<String> stepPlaceNames) {
        String required = requiredName.toLowerCase();
        return stepPlaceNames.stream().anyMatch(stepName ->
                stepName.contains(required) || required.contains(stepName));
    }

    private List<Place> resolveSelectedPlaces(ItineraryGenerateRequest request) {
        boolean isManual = request.mode() == ItineraryGenerateRequest.PlanningMode.MANUAL;
        List<Long> ids = request.selectedPlaceIds();

        if (isManual && (ids == null || ids.isEmpty())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "Manual Mode에서는 장소를 1개 이상 선택해야 합니다.");
        }
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        List<Place> found = placeRepository.findAllById(ids);
        if (found.size() != ids.size()) {
            Set<Long> foundIds = found.stream().map(Place::getId).collect(Collectors.toSet());
            List<Long> missingIds = ids.stream().filter(id -> !foundIds.contains(id)).toList();
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "선택한 장소를 찾을 수 없습니다. ID: " + missingIds);
        }
        return found;
    }

    private void sendProgress(SseEmitter emitter, int percentage, String message, String stage) {
        try {
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(new ProgressEvent(percentage, message, stage)));
        } catch (IOException e) {
            log.warn("Failed to send progress event for stage {}: {}", stage, e.getMessage());
        }
    }

    private void sendComplete(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event()
                    .name("complete")
                    .data(Map.of("status", "DONE")));
            emitter.complete();
        } catch (IOException e) {
            log.warn("Failed to send complete event: {}", e.getMessage());
        }
    }

    private void sendError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(Map.of("message", message)));
            emitter.complete();
        } catch (IOException e) {
            log.warn("Failed to send error event: {}", e.getMessage());
        }
    }
}
