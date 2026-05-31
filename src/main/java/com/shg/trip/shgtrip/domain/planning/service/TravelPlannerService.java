package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.planning.dto.GenerateJobResponse;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 여행 일정 생성 워크플로우 서비스.
 * jobId 관리 + SSE emitter 등록 담당.
 * 실제 파이프라인은 ItineraryGenerationExecutor(@Async)에 위임.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TravelPlannerService {

    private static final long SSE_TIMEOUT = 600_000L; // 10분 (Sonnet 응답 1~2분 × 최대 5회 AI 호출 고려)
    private static final long EVICTION_THRESHOLD_MS = 720_000L; // 12분 — SSE_TIMEOUT + 여유 2분

    private final ItineraryGenerationExecutor generationExecutor;
    private final GenerationResultStore resultStore;

    private final ConcurrentHashMap<String, EmitterEntry> emitters = new ConcurrentHashMap<>();

    private record EmitterEntry(SseEmitter emitter, Instant createdAt) {}

    /**
     * 일정 생성 작업 시작 → jobId 반환.
     * emitter 등록 후 executor에 비동기 위임.
     */
    public GenerateJobResponse startGeneration(ItineraryGenerateRequest request, Long userId) {
        String jobId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        emitter.onCompletion(() -> emitters.remove(jobId));
        emitter.onTimeout(() -> emitters.remove(jobId));
        emitter.onError(e -> emitters.remove(jobId));

        emitters.put(jobId, new EmitterEntry(emitter, Instant.now()));

        // 별도 빈(executor)에서 호출 → @Async 프록시 정상 동작
        generationExecutor.execute(jobId, request, userId, emitter);

        return new GenerateJobResponse(jobId);
    }

    /**
     * SSE emitter 조회.
     */
    public SseEmitter getEmitter(String jobId) {
        EmitterEntry entry = emitters.get(jobId);
        if (entry == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "생성 작업을 찾을 수 없습니다: " + jobId);
        }
        return entry.emitter();
    }

    /**
     * 생성 완료된 itineraryId 저장 (executor에서 호출).
     */
    public void saveResult(String jobId, Long itineraryId) {
        resultStore.save(jobId, itineraryId);
    }

    /**
     * 인증된 클라이언트가 jobId로 완료된 itineraryId 조회.
     * 조회 후 즉시 제거 — 1회성 토큰처럼 동작.
     */
    public Long getResult(String jobId) {
        return resultStore.getAndRemove(jobId);
    }

    /**
     * 1분 주기로 TTL 초과 emitter 정리.
     * 정상 흐름에서는 onCompletion/onTimeout 콜백으로 제거되지만,
     * 클라이언트가 SSE 연결을 맺지 않거나 콜백 누락 시 여기서 sweep.
     */
    @Scheduled(fixedDelay = 60_000)
    public void evictStaleEmitters() {
        Instant threshold = Instant.now().minusMillis(EVICTION_THRESHOLD_MS);
        Iterator<Map.Entry<String, EmitterEntry>> it = emitters.entrySet().iterator();
        int evicted = 0;

        while (it.hasNext()) {
            Map.Entry<String, EmitterEntry> entry = it.next();
            if (entry.getValue().createdAt().isBefore(threshold)) {
                try {
                    entry.getValue().emitter().complete();
                } catch (Exception ignored) {
                    // 이미 완료/에러 상태일 수 있음
                }
                it.remove();
                evicted++;
            }
        }

        if (evicted > 0) {
            log.info("Evicted {} stale SSE emitters", evicted);
        }
    }
}
