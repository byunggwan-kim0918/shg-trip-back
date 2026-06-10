package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.place.service.PlaceRefreshService;
import com.shg.trip.shgtrip.domain.planning.dto.*;
import com.shg.trip.shgtrip.domain.planning.service.ai.IndexBasedItineraryGenerator;
import com.shg.trip.shgtrip.domain.planning.service.ai.OptimizedClaudeAIService;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 최적화된 일정 생성 파이프라인 실행기.
 *
 * enrich(Haiku 1회) → vectorSearch(LLM 0회) →
 *   if (충분) indexBasedGenerate(Sonnet 1회) → hardValidate → merge → save
 *   else fallback → 기존 ItineraryGenerationExecutor 경로
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OptimizedGenerationExecutor {

    private final OptimizedClaudeAIService optimizedClaudeAIService;
    private final VectorSearchQueryService vectorSearchQueryService;
    private final FallbackDecider fallbackDecider;
    private final IndexBasedItineraryGenerator indexBasedItineraryGenerator;
    private final HardValidator hardValidator;
    private final IndexResultMapper indexResultMapper;
    private final ItineraryGenerationExecutor fallbackExecutor;
    private final ItinerarySaveHelper saveHelper;
    private final GenerationResultStore resultStore;
    private final CancellationRegistry cancellationRegistry;
    private final PlaceFreshnessFilter placeFreshnessFilter;
    private final PlaceRefreshService placeRefreshService;
    private final PlaceRepository placeRepository;

    /**
     * 비동기 최적화 일정 생성 파이프라인.
     * SSE로 진행 상태를 전송하며, 벡터 경로 또는 Fallback 경로로 분기한다.
     */
    @Async("planningExecutor")
    public void execute(String jobId, ItineraryGenerateRequest request, Long userId, SseEmitter emitter) {
        try {
            // 1. enrichInput (Haiku 1회)
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "enriching_input", 10, "입력을 분석하고 있습니다...");

            EnrichmentResult enrichResult = executeWithHeartbeat(emitter,
                    () -> optimizedClaudeAIService.enrichInput(request));

            if (!enrichResult.valid()) {
                sendSseError(emitter, enrichResult.errorCode(), enrichResult.errorMessage());
                return;
            }

            VectorEnrichedInput enrichedInput = enrichResult.enrichedInput();

            // 2. 벡터 검색
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "searching_places", 30, "장소를 검색하고 있습니다...");

            List<PlaceCandidate> candidates = executeWithHeartbeat(emitter,
                    () -> vectorSearchQueryService.search(enrichedInput));

            // 3. Fallback 분기 판단
            if (cancellationRegistry.isCancelled(jobId)) return;
            boolean needsFallback = fallbackDecider.shouldFallback(candidates, enrichedInput.categories());

            if (needsFallback) {
                // Fallback 경로: 기존 ItineraryGenerationExecutor로 위임
                sendSseEvent(emitter, "generating_fallback", 40, "Fallback 경로로 일정을 생성합니다...");
                log.info("OptimizedGenerationExecutor: Fallback 경로 진입 (jobId={})", jobId);
                fallbackExecutor.execute(jobId, request, userId, emitter);
                return;
            }

            // 4. Google Places 동기화 (source='foursquare' OR stale 조건 확인)
            // 벡터 검색 결과 중 source='foursquare' 또는 stale(7일 이상)인 것들만 Google API로 갱신
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "syncing_places", 40, "장소 정보를 동기화하고 있습니다...");

            List<Long> placeIds = candidates.stream()
                    .map(PlaceCandidate::placeId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());

            // 동기화 대상: source='foursquare' OR stale(7일 이상)
            List<Place> toSync = placeRepository.findByIdAndNeedsSync(
                    placeIds,
                    OffsetDateTime.now().minusDays(7)
            );

            if (!toSync.isEmpty()) {
                log.info("Google Places 동기화 대상: {}건 (source='foursquare' or stale)", toSync.size());
                syncAllPlaces(toSync);
            }

            // 동기화 후 DB 최신 데이터(rating, priceLevel, openingHours)를 candidates에 반영
            List<PlaceCandidate> enrichedCandidates = enrichCandidatesFromDb(candidates);

            // 5. 벡터 경로: 인덱스 기반 일정 생성 (Sonnet 1회)
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "generating_itinerary", 50, "일정을 생성하고 있습니다...");

            IndexBasedItineraryOutput generatedOutput = executeWithHeartbeat(emitter,
                    () -> indexBasedItineraryGenerator.generate(enrichedInput, enrichedCandidates));

            // 6. 인덱스 → 장소 데이터 결합 (placeIndex 범위 초과 시 재생성 경로로 전환)
            ItineraryData itineraryData;
            try {
                itineraryData = indexResultMapper.mergeIndexOutput(generatedOutput, enrichedCandidates);
            } catch (IllegalArgumentException e) {
                // placeIndex가 범위 밖 → 재생성 시도
                log.info("IndexResultMapper 범위 초과 에러, 1회 재생성 시도: {}", e.getMessage());

                if (cancellationRegistry.isCancelled(jobId)) return;
                sendSseEvent(emitter, "regenerating", 60, "일정을 재생성하고 있습니다...");

                IndexBasedItineraryOutput regeneratedOutput = executeWithHeartbeat(emitter,
                        () -> indexBasedItineraryGenerator.regenerate(
                                enrichedInput, enrichedCandidates, e.getMessage()));

                try {
                    itineraryData = indexResultMapper.mergeIndexOutput(regeneratedOutput, enrichedCandidates);
                } catch (IllegalArgumentException e2) {
                    log.error("재생성 후에도 인덱스 범위 초과: {}", e2.getMessage());
                    sendSseError(emitter, "VALIDATION_FAILED",
                            "AI가 유효하지 않은 장소 인덱스를 반환했습니다. 다시 시도해주세요.");
                    return;
                }
            }

            // 6. Hard Validation
            HardValidationResult validationResult = hardValidator.validate(itineraryData);

            if (!validationResult.valid()) {
                // 1회 재생성 시도
                log.info("Hard validation 실패, 1회 재생성 시도: {}", validationResult.failureReason());

                if (cancellationRegistry.isCancelled(jobId)) return;
                sendSseEvent(emitter, "regenerating", 65, "일정을 재검증하고 있습니다...");

                IndexBasedItineraryOutput regeneratedOutput = executeWithHeartbeat(emitter,
                        () -> indexBasedItineraryGenerator.regenerate(
                                enrichedInput, enrichedCandidates, validationResult.failureReason()));

                try {
                    itineraryData = indexResultMapper.mergeIndexOutput(regeneratedOutput, enrichedCandidates);
                } catch (IllegalArgumentException e) {
                    log.error("재생성 후 인덱스 범위 초과: {}", e.getMessage());
                    sendSseError(emitter, "VALIDATION_FAILED",
                            "일정 생성 결과가 검증에 실패했습니다. 다시 시도해주세요.");
                    return;
                }

                HardValidationResult retryResult = hardValidator.validate(itineraryData);
                if (!retryResult.valid()) {
                    log.error("재생성 후에도 hard validation 실패: {}", retryResult.failureReason());
                    sendSseError(emitter, "VALIDATION_FAILED",
                            "일정 생성 결과가 검증에 실패했습니다. 다시 시도해주세요.");
                    return;
                }
            }

            // 7. 저장
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "saving", 90, "일정을 저장하고 있습니다...");

            EnrichedInput legacyInput = toLegacyEnrichedInput(enrichedInput);
            Itinerary saved = saveHelper.save(itineraryData, legacyInput, userId);

            // 8. 완료
            resultStore.save(jobId, saved.getId());
            sendSseEvent(emitter, "completed", 100, "일정 생성이 완료되었습니다.");
            sendComplete(emitter);

        } catch (BusinessException e) {
            log.error("OptimizedGeneration failed for job {}: {}", jobId, e.getMessage());
            sendSseError(emitter, "GENERATION_FAILED", e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error during optimized generation for job {}: {}", jobId, e.getMessage(), e);
            sendSseError(emitter, "UNEXPECTED_ERROR", "일정 생성 중 예기치 않은 오류가 발생했습니다.");
        }
    }

    // ── Private helpers ──

    /**
     * stale 장소들을 병렬로 Google Places 동기화한다.
     * 동기화 실패 시 해당 장소를 건너뛰고 계속 진행한다 (best-effort).
     */
    /**
     * Place 목록을 Google Places API로 동기화한다.
     * source를 'foursquare' → 'google'으로 변경하여 완전한 정보로 갱신한다.
     * 동기화 실패 시 해당 장소를 건너뛰고 계속 진행한다 (best-effort).
     */
    private void syncAllPlaces(List<Place> places) {
        log.info("Google Places 동기화 시작: {}건 (source='foursquare' or stale)", places.size());

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Place place : places) {
            if (place.getId() == null) continue;
            futures.add(CompletableFuture.runAsync(() ->
                    placeRefreshService.refreshSync(place.getId(), place.getName())
            ));
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("Google Places 동기화 타임아웃 (10초), 동기화된 장소만 사용");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Google Places 동기화 인터럽트");
        } catch (ExecutionException e) {
            log.warn("Google Places 동기화 중 일부 실패: {}", e.getMessage());
        }
    }

    /**
     * 동기화 후 candidates를 DB의 최신 데이터(rating, priceLevel, openingHours)로 갱신한다.
     */
    private List<PlaceCandidate> enrichCandidatesFromDb(List<PlaceCandidate> candidates) {
        List<Long> placeIds = candidates.stream()
                .map(PlaceCandidate::placeId)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());

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

    /**
     * VectorEnrichedInput → EnrichedInput 변환.
     * ItinerarySaveHelper가 기존 EnrichedInput을 요구하므로 호환성 변환을 수행한다.
     */
    private EnrichedInput toLegacyEnrichedInput(VectorEnrichedInput input) {
        return new EnrichedInput(
                input.normalizedDestination() != null ? input.normalizedDestination() : input.destination(),
                input.themes(),
                input.categories(),
                input.pace(),
                input.budget(),
                input.startDate(),
                input.endDate(),
                input.description(),
                input.enrichedContext(),
                input.selectedPlaceIds()
        );
    }

    /**
     * SSE 진행 상태 이벤트를 전송한다.
     * JSON 형식: {status, progress, message}
     */
    private void sendSseEvent(SseEmitter emitter, String status, int progress, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(Map.of(
                            "status", status,
                            "progress", progress,
                            "message", message
                    )));
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to send SSE progress event (status={}): {}", status, e.getMessage());
        }
    }

    /**
     * SSE 에러 이벤트를 전송하고 emitter를 완료한다.
     */
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
            log.warn("Failed to send SSE error event: {}", e.getMessage());
        }
    }

    /**
     * SSE 완료 이벤트를 전송한다.
     */
    private void sendComplete(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event()
                    .name("complete")
                    .data(Map.of("status", "DONE")));
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to send SSE complete event: {}", e.getMessage());
        }
    }

    /**
     * 장시간 작업을 실행하면서 30초마다 SSE heartbeat를 전송.
     * Next.js 프록시 레이어의 idle 타임아웃 방지.
     */
    private <T> T executeWithHeartbeat(SseEmitter emitter, Callable<T> task) {
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> heartbeatFuture = heartbeat.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException e) {
                // emitter가 이미 닫힌 경우 — 무시
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
            heartbeat.shutdown();
        }
    }
}
