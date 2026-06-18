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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ItineraryGenerationExecutor {

    private final AIService aiService;
    private final ItineraryValidationService validationService;
    private final ItinerarySaveHelper saveHelper;
    private final PlaceRepository placeRepository;
    private final GenerationResultStore resultStore;
    private final CancellationRegistry cancellationRegistry;
    private final ScheduledExecutorService heartbeatExecutor;

    public ItineraryGenerationExecutor(
            AIService aiService,
            ItineraryValidationService validationService,
            ItinerarySaveHelper saveHelper,
            PlaceRepository placeRepository,
            GenerationResultStore resultStore,
            CancellationRegistry cancellationRegistry,
            @Qualifier("sseHeartbeatScheduler") ScheduledExecutorService heartbeatExecutor) {
        this.aiService = aiService;
        this.validationService = validationService;
        this.saveHelper = saveHelper;
        this.placeRepository = placeRepository;
        this.resultStore = resultStore;
        this.cancellationRegistry = cancellationRegistry;
        this.heartbeatExecutor = heartbeatExecutor;
    }

    @Async("planningExecutor")
    public void execute(String jobId, ItineraryGenerateRequest request, Long userId, SseEmitter emitter) {
        try {
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendProgress(emitter, 20, "분석 중...", "ENRICHING");
            EnrichedInput enrichedInput = executeWithHeartbeat(emitter,
                    () -> aiService.enrichInput(request));

            if (cancellationRegistry.isCancelled(jobId)) return;
            List<Place> selectedPlaces = resolveSelectedPlaces(request);

            if (cancellationRegistry.isCancelled(jobId)) return;
            sendProgress(emitter, 50, "장소 탐색 중...", "GENERATING");
            ItineraryData itineraryData = executeWithHeartbeat(emitter,
                    () -> aiService.generateItinerary(enrichedInput, selectedPlaces));

            if (cancellationRegistry.isCancelled(jobId)) return;
            sendProgress(emitter, 70, "동선 최적화 중...", "VALIDATING");
            ItineraryData validated = executeWithHeartbeat(emitter,
                    () -> validationService.validateWithRetry(itineraryData, enrichedInput, selectedPlaces));

            validateSelectedPlacesIncluded(validated, selectedPlaces);

            if (cancellationRegistry.isCancelled(jobId)) return;
            sendProgress(emitter, 90, "일정을 저장하고 있습니다...", "SAVING");
            Itinerary saved = saveHelper.save(validated, enrichedInput, userId);

            resultStore.save(jobId, saved.getId());
            sendProgress(emitter, 100, "일정 생성이 완료되었습니다.", "COMPLETE");
            sendComplete(emitter);

        } catch (BusinessException e) {
            log.warn("Generation failed for job {}: {}", jobId, e.getErrorCode());
            sendError(emitter, "요청 처리 중 문제가 발생했습니다.");
        } catch (Exception e) {
            log.error("Unexpected error during generation for job {}", jobId, e);
            sendError(emitter, "일정 생성 중 예기치 않은 오류가 발생했습니다.");
        }
    }

    private void validateSelectedPlacesIncluded(ItineraryData data, List<Place> requiredPlaces) {
        if (requiredPlaces == null || requiredPlaces.isEmpty()) return;

        Set<String> stepPlaceNames = data.steps().stream()
                .filter(s -> s.place() != null && s.place().name() != null)
                .map(s -> s.place().name().toLowerCase())
                .collect(Collectors.toSet());

        boolean allIncluded = requiredPlaces.stream()
                .allMatch(p -> isPlaceIncluded(p.getName(), stepPlaceNames));

        if (!allIncluded) {
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                    "선택한 일부 장소가 최적 일정에 포함될 수 없었습니다.");
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
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "요청하신 일부 장소를 찾을 수 없습니다.");
        }
        return found;
    }

    private void sendProgress(SseEmitter emitter, int percentage, String message, String stage) {
        try {
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(new ProgressEvent(percentage, message, stage)));
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to send progress event for stage {}", stage);
        }
    }

    private <T> T executeWithHeartbeat(SseEmitter emitter, Callable<T> task) throws Exception {
        ScheduledFuture<?> heartbeatFuture = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException e) {
                log.debug("Heartbeat send failed");
            }
        }, 30, 30, TimeUnit.SECONDS);

        try {
            return task.call();
        } finally {
            heartbeatFuture.cancel(false);
        }
    }

    private void sendComplete(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event()
                    .name("complete")
                    .data(Map.of("status", "DONE")));
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to send complete event");
        }
    }

    private void sendError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(Map.of("message", message)));
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to send error event");
        }
    }
}
