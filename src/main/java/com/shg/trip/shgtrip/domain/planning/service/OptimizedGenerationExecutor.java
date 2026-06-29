package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.place.service.PlaceRefreshService;
import com.shg.trip.shgtrip.domain.planning.dto.*;
import com.shg.trip.shgtrip.domain.planning.service.ai.SelectionCallGenerator;
import com.shg.trip.shgtrip.domain.planning.service.ai.OptimizedClaudeAIService;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.data.domain.PageRequest;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 2-Call 일정 생성 파이프라인 실행기.
 *
 * enrich(Haiku) → vectorSearch(카테고리별) →
 *   if (충분) selectPlaces(Sonnet) → assembleItinerary(Haiku) → validate → save
 *   else fallback → ItineraryGenerationExecutor
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OptimizedGenerationExecutor {

    private final OptimizedClaudeAIService optimizedClaudeAIService;
    private final VectorSearchQueryService vectorSearchQueryService;
    private final FallbackDecider fallbackDecider;
    private final SelectionCallGenerator selectionCallGenerator;
    private final HardValidator hardValidator;
    private final IndexResultMapper indexResultMapper;
    private final RouteOptimizer routeOptimizer;
    private final ItineraryGenerationExecutor fallbackExecutor;
    private final ItinerarySaveHelper saveHelper;
    private final StoryGenerationService storyGenerationService;
    private final GenerationResultStore resultStore;
    private final CancellationRegistry cancellationRegistry;
    private final PlaceRefreshService placeRefreshService;
    private final PlaceRepository placeRepository;

    @Async("planningExecutor")
    public void execute(String jobId, ItineraryGenerateRequest request, Long userId, SseEmitter emitter) {
        try {
            // [10%] Haiku enrichInput
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "ENRICHING", 10, "입력을 분석하고 있습니다...");

            EnrichmentResult enrichResult = executeWithHeartbeat(emitter,
                    () -> optimizedClaudeAIService.enrichInput(request));

            if (!enrichResult.valid()) {
                sendSseError(emitter, enrichResult.errorCode(), enrichResult.errorMessage());
                return;
            }

            VectorEnrichedInput enrichedInput = enrichResult.enrichedInput();
            long days = ChronoUnit.DAYS.between(enrichedInput.startDate(), enrichedInput.endDate()) + 1;

            // [20%] 카테고리별 벡터 검색
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "SEARCHING", 20, "장소를 검색하고 있습니다...");

            List<PlaceCandidate> candidates = executeWithHeartbeat(emitter,
                    () -> vectorSearchQueryService.search(enrichedInput));

            // [25%] Fallback 분기 판단
            if (cancellationRegistry.isCancelled(jobId)) return;
            boolean needsFallback = fallbackDecider.shouldFallback(candidates, days);

            if (needsFallback) {
                sendSseEvent(emitter, "FALLBACK", 30, "Fallback 경로로 일정을 생성합니다...");
                log.info("OptimizedGeneration → Fallback (jobId={}, reason=insufficient_candidates)", jobId);
                fallbackExecutor.execute(jobId, request, userId, emitter);
                return;
            }

            // [35%] Google Places 동기화
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "SYNCING", 35, "장소 정보를 동기화하고 있습니다...");

            List<Long> placeIds = candidates.stream()
                    .map(PlaceCandidate::placeId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());

            List<Place> toSync = placeRepository.findByIdAndNeedsSync(
                    placeIds,
                    OffsetDateTime.now().minusDays(7)
            );

            if (!toSync.isEmpty()) {
                log.info("Google Places 동기화: {}건", toSync.size());
                syncAllPlaces(toSync);
            }

            // DB 최신 데이터 반영 + 숙소 보완
            final List<PlaceCandidate> enrichedCandidates = ensureAccommodationCandidates(
                    enrichCandidatesFromDb(candidates), enrichedInput.regions());

            // [50%] Call 1: Sonnet selectPlaces
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "SELECTING", 50, "날짜별 장소를 선택하고 있습니다...");

            SelectionOutput rawSelectionOutput = executeWithHeartbeat(emitter,
                    () -> selectionCallGenerator.selectPlaces(enrichedInput, enrichedCandidates));

            // 중간 날 숙소 누락 보정 (추가 LLM 호출 없는 결정론적 코드 보정)
            final SelectionOutput selectionOutput =
                    indexResultMapper.fillMissingAccommodation(rawSelectionOutput, enrichedCandidates);

            // [65%] Backend Repair·Optimizer: day/순서/시간/교통/대안 전부 결정론적으로 확정
            // (LLM 재호출 없음 — pace quota·pair·거리이탈·연속숙소·허브를 fixpoint로 수리 후 NN+2-opt)
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "OPTIMIZING", 65, "동선과 시간을 확정하고 있습니다...");

            List<StepData> fixedSteps = executeWithHeartbeat(emitter,
                    () -> routeOptimizer.repairAndSchedule(
                            selectionOutput, enrichedCandidates, enrichedInput.pace(),
                            enrichedInput.transportPref(), enrichedInput.startDate()));

            // [80%] 구조 검증(안전망 — 결정론적 코드이므로 실패 시 재시도가 아니라 버그로 취급)
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "VALIDATING", 80, "일정을 검증하고 있습니다...");

            String destination = enrichedInput.normalizedDestination() != null
                    ? enrichedInput.normalizedDestination() : enrichedInput.destination();
            ItineraryData draftData = indexResultMapper.toDraftItineraryData(
                    fixedSteps, destination, selectionOutput.concept());
            HardValidationResult validationResult = hardValidator.validate(draftData);
            if (!validationResult.valid()) {
                log.error("Optimized 경로 구조 검증 실패 (결정론적 로직 버그 가능성): {}", validationResult.failureReason());
            }

            // [90%] 구조 일정 저장 (story는 비어있음) — 즉시 complete, story는 비동기로 채움
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "SAVING", 90, "일정을 저장하고 있습니다...");

            EnrichedInput legacyInput = toLegacyEnrichedInput(enrichedInput);
            Itinerary saved = saveHelper.save(draftData, legacyInput, userId, true);

            // [100%] 구조 완료 — emitter는 닫지 않고 story-ready까지 유지
            resultStore.save(jobId, saved.getId());
            sendSseEvent(emitter, "COMPLETE", 100, "일정 생성이 완료되었습니다.");
            sendCompleteKeepOpen(emitter, saved.getId());

            log.info("OptimizedGeneration 구조 완료: jobId={}, itineraryId={}, days={}",
                    jobId, saved.getId(), days);

            // 비동기 스토리텔링 — critical path 밖에서 진행, 완료 시 story-ready emit 후 emitter 종료
            storyGenerationService.generateAndAttach(
                    jobId, emitter, saved.getId(), fixedSteps, selectionOutput.concept(), enrichedInput);

        } catch (BusinessException e) {
            log.error("OptimizedGeneration 비즈니스 오류 (jobId={}): {}", jobId, e.getMessage());
            sendSseError(emitter, "GENERATION_FAILED", e.getMessage());
        } catch (Exception e) {
            log.error("OptimizedGeneration 예기치 않은 오류 (jobId={})", jobId, e);
            sendSseError(emitter, "UNEXPECTED_ERROR", "일정 생성 중 오류가 발생했습니다.");
        }
    }

    // ── Private helpers ──

    private void syncAllPlaces(List<Place> places) {
        log.info("Google Places 동기화 시작: {}건", places.size());

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Place place : places) {
            if (place.getId() == null) continue;
            futures.add(CompletableFuture.runAsync(() ->
                    placeRefreshService.refreshSync(place.getId(), place.getName())
            ));
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(20, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("Google Places 동기화 타임아웃 (20초), 동기화된 장소만 사용");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Google Places 동기화 인터럽트");
        } catch (ExecutionException e) {
            log.warn("Google Places 동기화 중 오류: {}", e.getMessage());
        }
    }

    private List<PlaceCandidate> enrichCandidatesFromDb(List<PlaceCandidate> candidates) {
        List<Long> placeIds = candidates.stream()
                .map(PlaceCandidate::placeId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        if (placeIds.isEmpty()) return candidates;

        Map<Long, Place> placeMap = placeRepository.findAllById(placeIds).stream()
                .collect(Collectors.toMap(Place::getId, Function.identity()));

        return candidates.stream().map(c -> {
            Place place = c.placeId() != null ? placeMap.get(c.placeId()) : null;
            if (place == null) return c;
            return new PlaceCandidate(
                    c.index(), c.placeId(), c.name(), c.address(), c.category(),
                    c.tags(), c.region(), c.country(), c.latitude(), c.longitude(),
                    place.getDescription() != null ? place.getDescription() : c.description(),
                    place.getRating() != null ? place.getRating() : c.rating(),
                    c.similarityScore(),
                    place.getPriceLevel(),
                    place.getOpeningHours()
            );
        }).collect(Collectors.toList());
    }

    private List<PlaceCandidate> ensureAccommodationCandidates(
            List<PlaceCandidate> candidates, List<String> regions) {

        boolean hasAccommodation = candidates.stream()
                .anyMatch(c -> c.category() != null
                        && c.category().toLowerCase().contains("lodging"));

        if (hasAccommodation) return candidates;

        if (regions == null || regions.isEmpty()) return candidates;

        List<Place> hotels = placeRepository.findTopAccommodationsByRegions(
                regions, PageRequest.of(0, 2));

        if (hotels.isEmpty()) {
            log.warn("숙소 후보 보완 실패: {}", regions);
            return candidates;
        }

        List<PlaceCandidate> result = new ArrayList<>(candidates);
        int nextIndex = candidates.size() + 1;
        for (Place hotel : hotels) {
            result.add(new PlaceCandidate(
                    nextIndex++, hotel.getId(), hotel.getName(), hotel.getAddress(),
                    hotel.getCategory(), hotel.getTags(), hotel.getRegion(), hotel.getCountry(),
                    hotel.getLatitude(), hotel.getLongitude(), hotel.getDescription(),
                    hotel.getRating(), 0.0, null, hotel.getOpeningHours()
            ));
        }
        return result;
    }

    private EnrichedInput toLegacyEnrichedInput(VectorEnrichedInput input) {
        return new EnrichedInput(
                input.normalizedDestination() != null ? input.normalizedDestination() : input.destination(),
                input.themes(),
                input.categories(),
                input.pace(),
                input.transportPref(),
                input.budget(),
                input.startDate(),
                input.endDate(),
                input.description(),
                input.enrichedContext(),
                input.selectedPlaceIds()
        );
    }

    private void sendSseEvent(SseEmitter emitter, String status, int progress, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(Map.of(
                            "stage", status,
                            "percentage", progress,
                            "message", message
                    )));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 이벤트 전송 실패 (status={}): {}", status, e.getMessage());
        }
    }

    private void sendSseError(SseEmitter emitter, String errorCode, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(Map.of(
                            "status", "error",
                            "errorCode", errorCode,
                            "message", message
                    )));
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 에러 이벤트 전송 실패: {}", e.getMessage());
        }
    }

    /**
     * 구조 일정 완료를 알리지만 emitter는 닫지 않는다 — story-ready까지 같은 emitter로 추가
     * 이벤트를 보내야 하므로, 표준 SSE "complete=종료" 관례를 이 흐름에서만 예외로 둔다.
     */
    private void sendCompleteKeepOpen(SseEmitter emitter, Long itineraryId) {
        try {
            emitter.send(SseEmitter.event()
                    .name("complete")
                    .data(Map.of("status", "DONE", "itineraryId", itineraryId, "pipeline", "optimized")));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 완료 이벤트 전송 실패: {}", e.getMessage());
        }
    }

    private <T> T executeWithHeartbeat(SseEmitter emitter, Callable<T> task) {
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> heartbeatFuture = heartbeat.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException e) {
                // emitter 닫힘
            }
        }, 30, 30, TimeUnit.SECONDS);

        try {
            return task.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            heartbeatFuture.cancel(false);
            heartbeat.shutdownNow();
            try {
                if (!heartbeat.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Heartbeat executor did not terminate gracefully");
                }
            } catch (InterruptedException e) {
                log.warn("Interrupted waiting for heartbeat executor termination");
                Thread.currentThread().interrupt();
            }
        }
    }
}
