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
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Predicate;
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
    private final GenerationPolicyService generationPolicyService;
    private final ScheduledExecutorService heartbeatExecutor;

    public ItineraryGenerationExecutor(
            AIService aiService,
            ItineraryValidationService validationService,
            ItinerarySaveHelper saveHelper,
            PlaceRepository placeRepository,
            GenerationResultStore resultStore,
            CancellationRegistry cancellationRegistry,
            GenerationPolicyService generationPolicyService,
            @Qualifier("sseHeartbeatScheduler") ScheduledExecutorService heartbeatExecutor) {
        this.aiService = aiService;
        this.validationService = validationService;
        this.saveHelper = saveHelper;
        this.placeRepository = placeRepository;
        this.resultStore = resultStore;
        this.cancellationRegistry = cancellationRegistry;
        this.generationPolicyService = generationPolicyService;
        this.heartbeatExecutor = heartbeatExecutor;
    }

    @Async("planningExecutor")
    public void execute(String jobId, ItineraryGenerateRequest request, Long userId, GenerationStream emitter) {
        MDC.put("jobId", jobId);
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
            ItineraryData allowed = withoutBlockedPlaces(validated, selectedPlaces);

            sendStepStream(emitter, allowed.steps());

            if (cancellationRegistry.isCancelled(jobId)) return;
            sendProgress(emitter, 90, "일정을 저장하고 있습니다...", "SAVING");
            Itinerary saved = saveHelper.save(allowed, enrichedInput, userId, false, null, planContext(request))
                    .itinerary();

            resultStore.save(jobId, saved.getId(), userId);
            // optimized 성공 지점과 상호배타라 쿼터나 미차감 한도를 한 번만 센다.
            if (saved.getQualityStatus() == Itinerary.QualityStatus.NEEDS_ADJUSTMENT) {
                generationPolicyService.recordUnbilled(userId);
            } else {
                generationPolicyService.recordSuccess(userId);
            }
            sendProgress(emitter, 100, "일정 생성이 완료되었습니다.", "COMPLETE");
            sendComplete(emitter);

        } catch (QualityGate.Rejected e) {
            boolean atLimit = generationPolicyService.recordUnbilled(userId);
            sendError(emitter, e.getErrorCode().getCode(), QualityGate.rejectionMessage(e.reason(),
                    atLimit ? generationPolicyService.unbilledLimitNotice(userId) : null));
        } catch (BusinessException e) {
            log.warn("Generation failed for job {}: {} {}", jobId, e.getErrorCode(), e.getMessage());
            sendError(emitter, e.getErrorCode().getCode(), e.userMessage());
        } catch (Exception e) {
            log.error("Unexpected error during generation for job {}", jobId, e);
            sendError(emitter, "UNEXPECTED_ERROR", "일정 생성 중 예기치 않은 오류가 발생했습니다.");
        } finally {
            MDC.remove("jobId");
        }
    }

    /** 이 경로는 국내만 오고(해외는 후보 부족이면 중단) 필수 경험을 문장으로만 받는다. */
    static QualityGate.PlanContext planContext(ItineraryGenerateRequest request) {
        int days = (int) ChronoUnit.DAYS.between(request.startDate(), request.endDate()) + 1;
        return new QualityGate.PlanContext(new ItineraryRules.Context(request.pace(), request.transportPref(),
                request.startDate(), days, ScheduleTimes.departureHubDeadline(request.departureTime(), null)), List.of());
    }

    /** 이 경로는 Sonnet이 이름을 직접 지어 후보 정책을 거치지 않는다. 사용자가 고른 장소는 남긴다. */
    static ItineraryData withoutBlockedPlaces(ItineraryData data, List<Place> selectedPlaces) {
        if (data.steps() == null) return data;
        Set<String> chosen = selectedPlaces.stream().map(Place::getName).collect(Collectors.toSet());
        Predicate<String> blocked = name -> name != null && !chosen.contains(name) && CandidatePolicy.isBlocked(name);
        List<StepData> steps = new ArrayList<>();
        Integer droppedDay = null;
        for (StepData s : data.steps()) {
            if (s.place() != null && blocked.test(s.place().name())) {
                droppedDay = s.dayNumber();
                continue;
            }
            // 뺀 장소에서 출발한 이동 구간이 남으면 그 거리·비용이 저장된다. 비워 두면 fillMissingTransportation이 다시 잰다.
            boolean legFromDropped = Objects.equals(droppedDay, s.dayNumber());
            droppedDay = null;
            List<AlternativeData> alts = s.alternatives() == null ? null
                    : s.alternatives().stream().filter(a -> !blocked.test(a.name())).toList();
            steps.add(new StepData(s.stepOrder(), s.dayNumber(), s.startTime(), s.endTime(), s.place(), alts,
                    legFromDropped ? null : s.transportationMode(),
                    legFromDropped ? null : s.transportationDuration(),
                    legFromDropped ? null : s.transportationDistance(),
                    legFromDropped ? null : s.transportationCost(),
                    s.notes(), s.estimatedCost()));
        }
        return new ItineraryData(data.title(), data.destination(), data.estimatedCost(), data.tags(), steps,
                data.qualityNotices());
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

    private void sendProgress(GenerationStream emitter, int percentage, String message, String stage) {
        try {
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(new ProgressEvent(percentage, message, stage)));
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to send progress event for stage {}", stage);
        }
    }

    private <T> T executeWithHeartbeat(GenerationStream emitter, Callable<T> task) throws Exception {
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

    /**
     * OptimizedGenerationExecutor와 같은 step-stream 계약을 지킨다.
     * payload: {@code { dayNumber, steps: [{name, startTime, category}] }}.
     */
    private void sendStepStream(GenerationStream emitter, List<StepData> steps) {
        if (steps == null || steps.isEmpty()) return;

        Map<Integer, List<Map<String, String>>> byDay = new LinkedHashMap<>();
        steps.stream()
                .sorted(Comparator.comparingInt(StepData::dayNumber).thenComparingInt(StepData::stepOrder))
                .forEach(s -> {
                    String name = s.place() != null ? s.place().name() : null;
                    if (name == null) return;
                    Map<String, String> info = new HashMap<>();
                    info.put("name", name);
                    info.put("startTime", s.startTime());
                    info.put("category", s.place() != null ? s.place().category() : null);
                    byDay.computeIfAbsent(s.dayNumber(), k -> new ArrayList<>()).add(info);
                });

        for (Map.Entry<Integer, List<Map<String, String>>> entry : byDay.entrySet()) {
            try {
                emitter.send(SseEmitter.event()
                        .name("step-stream")
                        .data(Map.of("dayNumber", entry.getKey(), "steps", entry.getValue())));
            } catch (IOException | IllegalStateException e) {
                log.debug("step-stream 전송 실패 (day={}): {}", entry.getKey(), e.getMessage());
            }
        }
    }

    private void sendComplete(GenerationStream emitter) {
        try {
            emitter.send(SseEmitter.event()
                    .name("complete")
                    .data(Map.of("status", "DONE")));
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to send complete event");
        }
    }

    private void sendError(GenerationStream emitter, String errorCode, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(Map.of("status", "error", "errorCode", errorCode, "message", message)));
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to send error event");
        }
    }
}
