package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.place.repository.PlaceFactsReader;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.dto.EnrichedInput;
import com.shg.trip.shgtrip.domain.planning.dto.EnrichmentResult;
import com.shg.trip.shgtrip.domain.planning.dto.ItineraryData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.domain.planning.service.ai.DraftCallGenerator;
import com.shg.trip.shgtrip.domain.planning.service.ai.OptimizedClaudeAIService;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 일정 생성 실행기. 입력 보강(Haiku) → 초안(Sonnet) → 짓기·검사와 필요할 때 편집(Sonnet) → 저장 → 이야기(Haiku) 순서다.
 * 생성 화면은 progress의 stage 이름과 percentage로 단계를 그리므로 바꾸려면 LoadingScreen도 같이 바꾼다.
 * 실패는 error 이벤트로만 끝나고 빈 일정을 저장하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TripGenerationExecutor {

    private static final ItineraryRules RULES = new ItineraryRules();
    private static final int HEARTBEAT_SECONDS = 30;

    private final OptimizedClaudeAIService enrichService;
    private final DraftRequestFactory requestFactory;
    private final TripPlanner tripPlanner;
    private final ItinerarySaveHelper saveHelper;
    private final StoryGenerationService storyGenerationService;
    private final GenerationResultStore resultStore;
    private final CancellationRegistry cancellationRegistry;
    private final GenerationPolicyService generationPolicyService;
    private final PlaceRepository placeRepository;
    private final GenerationTraceRecorder traceRecorder;
    private final PlanningCountries planningCountries;

    @Async("planningExecutor")
    public void execute(String jobId, ItineraryGenerateRequest request, Long userId, GenerationStream emitter) {
        MDC.put("jobId", jobId);
        long started = System.nanoTime();
        try {
            run(jobId, request, userId, emitter, started);
        } catch (BusinessException e) {
            log.error("일정 생성 실패 (jobId={}): {}", jobId, e.getMessage());
            sendError(emitter, e.getErrorCode().getCode(), e.userMessage());
        } catch (Exception e) {
            log.error("일정 생성 예기치 않은 오류 (jobId={})", jobId, e);
            sendError(emitter, "UNEXPECTED_ERROR", "일정 생성 중 오류가 발생했습니다.");
        } finally {
            MDC.remove("jobId");
        }
    }

    private void run(String jobId, ItineraryGenerateRequest request, Long userId, GenerationStream emitter,
                     long started) {
        if (cancellationRegistry.isCancelled(jobId)) return;
        sendProgress(emitter, "ENRICHING", 10, "입력을 분석하고 있습니다...");
        EnrichmentResult enriched = withHeartbeat(emitter, () -> enrichService.enrichInput(request));
        if (!enriched.valid()) {
            // 보강 거부만 사용자 입력 탓이라 반복 실패 차단에 센다. AI 오탐·형식 오류는 정책 서비스가 errorCode로 뺀다.
            generationPolicyService.recordValidationFailure(userId, enriched.errorCode());
            sendError(emitter, enriched.errorCode(), enriched.errorMessage());
            return;
        }
        String country = enriched.enrichedInput().country();
        if (!planningCountries.isEnabled(country)) {
            log.info("일정 생성 중단 (jobId={}, reason=country_not_enabled, country={})", jobId, country);
            sendError(emitter, ErrorCode.INSUFFICIENT_PLACE_DATA.getCode(), planningCountries.notEnabledMessage(country));
            return;
        }

        if (cancellationRegistry.isCancelled(jobId)) return;
        sendProgress(emitter, "SEARCHING", 20, "장소를 검색하고 있습니다...");
        DraftRequestFactory.Prepared prepared = withHeartbeat(emitter,
                () -> requestFactory.prepare(request, enriched.enrichedInput()));
        DraftItineraryPlanner.Request base = prepared.request();
        VectorEnrichedInput input = prepared.input();
        String prompt = DraftItineraryPlanner.prompt(base);

        if (cancellationRegistry.isCancelled(jobId)) return;
        sendProgress(emitter, "SELECTING", 50, "날짜별 장소를 선택하고 있습니다...");
        DraftCallGenerator.Drafted drafted = withHeartbeat(emitter, () -> modelCall(() -> tripPlanner.draft(base, prompt)));

        if (cancellationRegistry.isCancelled(jobId)) return;
        sendProgress(emitter, "OPTIMIZING", 65, "동선과 시간을 확정하고 있습니다...");
        TripPlanner.Planned planned;
        try {
            planned = withHeartbeat(emitter, () -> tripPlanner.finish(base, prompt, drafted));
        } catch (RuntimeException e) {
            // 비용을 낸 초안은 남겨 두어야 같은 초안으로 다시 지어 원인을 찾는다.
            traceRecorder.recordTrip(null, userId, jobId, request, input,
                    new GenerationTraceRecorder.TripRun(base, prompt, drafted.plan(), null, elapsedMs(started)), null,
                    "짓기 예외: " + e);
            throw e;
        }
        DraftItineraryPlanner.Result result = planned.built().result();
        int days = (int) ChronoUnit.DAYS.between(input.startDate(), input.endDate()) + 1;
        GenerationTraceRecorder.TripRun traceRun = new GenerationTraceRecorder.TripRun(base, prompt, drafted.plan(),
                planned, 0);

        List<StepData> steps = PartyCosts.scale(DraftItineraryPlanner.withReasonNotes(result), input.partySize());
        markCandidateAppearance(result.candidates());
        ItineraryRules.Context rules = planned.built().context();
        List<String> fixed = new ArrayList<>(prepared.notices());
        planned.built().tripNotes().forEach(n -> {
            if (!fixed.contains(n)) fixed.add(n);
        });
        QualityGate.PlanContext context = new QualityGate.PlanContext(rules, fixed);
        ItineraryRules.Verdict preview = previewQuality(steps, result.candidates(), rules, planned.built().facts());
        if (cancellationRegistry.isCancelled(jobId)) return;
        sendProgress(emitter, "VALIDATING", 80, "일정을 검증하고 있습니다...");
        if (steps.isEmpty()) {
            log.error("짓기 결과에 스텝이 없음 (jobId={}), 메모={}", jobId, result.notes());
            traceRecorder.recordTrip(null, userId, jobId, request, input, timed(traceRun, started), preview,
                    "스텝 없음");
            ErrorCode code = ErrorCode.INSUFFICIENT_PLACE_DATA;
            sendError(emitter, code.getCode(), code.getMessage());
            return;
        }
        String broken = StepStructure.problemOf(steps, result.candidates());
        if (broken != null) {
            log.error("짓기 결과 형태 오류 (jobId={}): {}", jobId, broken);
            traceRecorder.recordTrip(null, userId, jobId, request, input, timed(traceRun, started), preview,
                    "구조 검증: " + broken);
            boolean atLimit = generationPolicyService.recordUnbilled(userId);
            sendError(emitter, ErrorCode.QUALITY_GATE_FAILED.getCode(), QualityGate.rejectionMessage(null,
                    atLimit ? generationPolicyService.unbilledLimitNotice(userId) : null));
            return;
        }

        sendStepStream(emitter, steps);
        // 저장에서 거부될 일정에 바꿔 달라는 안내를 먼저 보내지 않는다.
        if (preview == null || !preview.hard()) {
            sendQualityNotices(emitter, preview == null ? fixed : QualityGate.mergedNotices(preview, context));
        }

        if (cancellationRegistry.isCancelled(jobId)) return;
        sendProgress(emitter, "SAVING", 90, "일정을 저장하고 있습니다...");
        String destination = input.normalizedDestination() != null ? input.normalizedDestination() : input.destination();
        String draftTitle = draftTitle(destination, input);
        List<String> tagSeed = input.searchTags() != null && !input.searchTags().isEmpty()
                ? input.searchTags() : input.themes();
        ItineraryData data = new ItineraryData(draftTitle, destination, totalCost(steps), tags(tagSeed), steps,
                preview == null ? fixed : QualityGate.mergedNotices(preview, context));
        List<double[]> centers = base.cities().stream().map(c -> new double[]{c.lat(), c.lng()}).toList();
        ItinerarySaveHelper.Saved saved;
        try {
            saved = saveHelper.save(data, legacyInput(input), userId, centers, context);
        } catch (QualityGate.Rejected e) {
            traceRecorder.recordTrip(null, userId, jobId, request, input, timed(traceRun, started), e.verdict(), null);
            sendError(emitter, e.getErrorCode().getCode(), rejectionMessage(e, userId));
            return;
        }
        Itinerary itinerary = saved.itinerary();
        // 완료 전송보다 먼저 남긴다. 뒤에 남기면 그 사이 지운 일정의 기록(요청 문장 포함)이 삭제를 피해 남는다.
        traceRecorder.recordTrip(itinerary, userId, jobId, request, input, timed(traceRun, started), saved.verdict(),
                null);

        resultStore.save(jobId, itinerary.getId(), userId);
        generationPolicyService.recordSuccess(userId);
        sendProgress(emitter, "COMPLETE", 100, "일정 생성이 완료되었습니다.");
        sendCompleteKeepOpen(emitter, itinerary.getId());
        log.info("일정 생성 완료: jobId={}, itineraryId={}, days={}, 편집={}건, sonnet in/out={}/{}, {}ms",
                jobId, itinerary.getId(), days, planned.edits().size(),
                planned.inputTokens(), planned.outputTokens(), elapsedMs(started));

        // expectedTitle은 실제로 저장한 임시 제목이어야 한다. 다르면 0행 업데이트가 되어 제목이 임시값으로 남는다.
        storyGenerationService.generateAndAttach(jobId, emitter, itinerary.getId(), steps, draftTitle, input,
                planned.draft().concept());
    }

    /** 모델 호출의 SDK 예외(과부하·시간 초과)는 재시도할 수 있는 실패로 알린다. */
    private static <T> T modelCall(Callable<T> call) throws Exception {
        try {
            return call.call();
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("모델 호출 실패: {}", e.toString());
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, e.getMessage());
        }
    }

    /** 저장 전 안내용 판정. 저장 여부는 저장 관문이 실제 엔티티로 다시 정하므로, 실패해도 생성은 진행한다. */
    private static ItineraryRules.Verdict previewQuality(List<StepData> steps, List<PlaceCandidate> candidates,
                                                         ItineraryRules.Context ctx,
                                                         Map<Long, PlaceFactsReader.PlaceFact> facts) {
        try {
            ItineraryRules.Verdict quality = RULES.evaluate(steps, candidates, ctx, facts);
            log.info("품질 판정 v{}: hard={} {}", quality.version(), quality.hard(),
                    quality.violations().stream().map(ItineraryRules.Violation::message).toList());
            return quality;
        } catch (RuntimeException e) {
            log.warn("품질 판정 실패: {}", e.getMessage(), e);
            return null;
        }
    }

    /** 이번 거부로 미차감 한도에 닿았으면 다시 만들라는 말 대신 한도 안내를 쓴다. */
    private String rejectionMessage(QualityGate.Rejected e, Long userId) {
        boolean atLimit = generationPolicyService.recordUnbilled(userId);
        return QualityGate.rejectionMessage(e.reason(),
                atLimit ? generationPolicyService.unbilledLimitNotice(userId) : null);
    }

    /** 배치가 보강 우선순위를 정하는 데 쓴다. */
    private void markCandidateAppearance(List<PlaceCandidate> candidates) {
        List<Long> ids = candidates.stream().map(PlaceCandidate::placeId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return;
        // 부가 기록이라 실패해도 비용을 낸 생성을 끝내지 않는다.
        try {
            placeRepository.markCandidateAppearance(ids, OffsetDateTime.now());
        } catch (RuntimeException e) {
            log.warn("후보 등장 기록 실패: {}", e.getMessage());
        }
    }

    /**
     * 구조 저장 시점의 임시 제목("제주 3박 4일"). 이야기 단계가 제목을 바꾸고, 그 단계가 실패하면 이 값이 남는다.
     */
    static String draftTitle(String destination, VectorEnrichedInput input) {
        String place = destination != null && !destination.isBlank() ? destination.trim() : "여행";
        long days = ChronoUnit.DAYS.between(input.startDate(), input.endDate()) + 1;
        if (days <= 1) return place + " 당일치기";
        return place + " " + (days - 1) + "박 " + days + "일";
    }

    private static BigDecimal totalCost(List<StepData> steps) {
        return steps.stream().map(StepData::estimatedCost).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 최대 8개는 프론트 MAX_TAGS와 맞춘다. 이야기 단계가 태그를 덮지 않아 여기서 정한 값이 최종이다. */
    private static List<String> tags(List<String> raw) {
        if (raw == null) return List.of();
        return raw.stream().filter(t -> t != null && !t.isBlank()).map(String::trim).distinct().limit(8).toList();
    }

    private static EnrichedInput legacyInput(VectorEnrichedInput input) {
        return new EnrichedInput(
                input.normalizedDestination() != null ? input.normalizedDestination() : input.destination(),
                input.themes(), input.categories(), input.pace(), input.transportPref(), input.budget(),
                input.startDate(), input.endDate(), input.description(), input.enrichedContext(),
                input.selectedPlaceIds());
    }

    private static GenerationTraceRecorder.TripRun timed(GenerationTraceRecorder.TripRun run, long started) {
        return new GenerationTraceRecorder.TripRun(run.request(), run.prompt(), run.draft(), run.planned(),
                elapsedMs(started));
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private void sendProgress(GenerationStream emitter, String stage, int percentage, String message) {
        try {
            emitter.send(SseEmitter.event().name("progress")
                    .data(Map.of("stage", stage, "percentage", percentage, "message", message)));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 이벤트 전송 실패 (stage={}): {}", stage, e.getMessage());
        }
    }

    private void sendQualityNotices(GenerationStream emitter, List<String> notices) {
        if (notices == null || notices.isEmpty()) return;
        try {
            emitter.send(SseEmitter.event().name("quality-notice").data(Map.of("notices", notices)));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE quality-notice 전송 실패: {}", e.getMessage());
        }
    }

    private void sendStepStream(GenerationStream emitter, List<StepData> steps) {
        Map<Integer, List<Map<String, String>>> byDay = new LinkedHashMap<>();
        steps.stream()
                .filter(s -> s.place() != null && s.place().name() != null)
                .sorted(Comparator.comparingInt(StepData::dayNumber).thenComparingInt(StepData::stepOrder))
                .forEach(s -> {
                    Map<String, String> info = new HashMap<>();
                    info.put("name", s.place().name());
                    info.put("nameKo", s.place().nameKo());
                    info.put("startTime", s.startTime());
                    info.put("category", s.place().category());
                    byDay.computeIfAbsent(s.dayNumber(), k -> new ArrayList<>()).add(info);
                });
        for (Map.Entry<Integer, List<Map<String, String>>> entry : byDay.entrySet()) {
            try {
                emitter.send(SseEmitter.event().name("step-stream")
                        .data(Map.of("dayNumber", entry.getKey(), "steps", entry.getValue())));
            } catch (IOException | IllegalStateException e) {
                log.debug("step-stream 전송 실패 (day={}): {}", entry.getKey(), e.getMessage());
            }
        }
    }

    private void sendError(GenerationStream emitter, String errorCode, String message) {
        try {
            emitter.send(SseEmitter.event().name("error")
                    .data(Map.of("status", "error", "errorCode", errorCode, "message", message)));
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 에러 이벤트 전송 실패: {}", e.getMessage());
        }
    }

    /** 완료를 알리되 닫지 않는다. 이야기가 끝날 때까지 같은 연결로 story-ready를 보낸다. */
    private void sendCompleteKeepOpen(GenerationStream emitter, Long itineraryId) {
        try {
            emitter.send(SseEmitter.event().name("complete")
                    .data(Map.of("status", "DONE", "itineraryId", itineraryId, "pipeline", "trip")));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 완료 이벤트 전송 실패: {}", e.getMessage());
        }
    }

    /** 모델 호출이 길어도 프록시가 연결을 끊지 않게 주석 이벤트를 보낸다. */
    private <T> T withHeartbeat(GenerationStream emitter, Callable<T> task) {
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> beat = heartbeat.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException e) {
                log.debug("heartbeat 전송 실패: {}", e.getMessage());
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        try {
            return task.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            beat.cancel(false);
            heartbeat.shutdownNow();
        }
    }
}
