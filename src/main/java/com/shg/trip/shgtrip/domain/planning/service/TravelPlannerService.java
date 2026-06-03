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
 * - jobId / emitter 생명주기 관리
 * - 유저별 동시 생성 1개 제한 (새 요청 시 기존 작업 취소)
 * - 취소 플래그는 CancellationRegistry에 위임 (순환 의존성 방지)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TravelPlannerService {

    private static final long SSE_TIMEOUT_MS = 600_000L;      // 10분
    private static final long EVICTION_THRESHOLD_MS = 720_000L; // 12분

    private final OptimizedGenerationExecutor optimizedGenerationExecutor;
    private final GenerationResultStore resultStore;
    private final CancellationRegistry cancellationRegistry;

    /** jobId → JobEntry */
    private final ConcurrentHashMap<String, JobEntry> jobs = new ConcurrentHashMap<>();
    /** userId → 현재 진행 중인 jobId (동시 생성 1개 제한) */
    private final ConcurrentHashMap<Long, String> activeUserJobs = new ConcurrentHashMap<>();

    private record JobEntry(SseEmitter emitter, Long userId, Instant createdAt) {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * 일정 생성 시작.
     * 동일 유저의 기존 진행 중 작업이 있으면 취소 후 새 작업 시작.
     */
    public GenerateJobResponse startGeneration(ItineraryGenerateRequest request, Long userId) {
        // 기존 작업 취소
        String existingJobId = activeUserJobs.get(userId);
        if (existingJobId != null) {
            log.info("Cancelling existing job {} for user {} — new generation requested", existingJobId, userId);
            cancelJob(existingJobId);
        }

        String jobId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        emitter.onCompletion(() -> cleanupJob(jobId));
        emitter.onTimeout(() -> {
            log.warn("SSE emitter timed out for job {}", jobId);
            cleanupJob(jobId);
        });
        emitter.onError(e -> cleanupJob(jobId));

        jobs.put(jobId, new JobEntry(emitter, userId, Instant.now()));
        activeUserJobs.put(userId, jobId);

        optimizedGenerationExecutor.execute(jobId, request, userId, emitter);

        return new GenerateJobResponse(jobId);
    }

    /**
     * SSE emitter 조회.
     * jobId가 없으면 RESOURCE_NOT_FOUND — 클라이언트는 /plan/new로 안내.
     */
    public SseEmitter getEmitter(String jobId) {
        JobEntry entry = jobs.get(jobId);
        if (entry == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                    "생성 작업을 찾을 수 없습니다: " + jobId);
        }
        return entry.emitter();
    }

    /**
     * 완료된 itineraryId 조회 (TTL 내 재조회 허용).
     */
    public Long getResult(String jobId) {
        return resultStore.get(jobId);
    }

    /**
     * 작업 취소 (새 요청 시 기존 작업 중단용).
     * 취소 플래그는 executor가 감지할 수 있도록 cleanupJob에서 제거하지 않음.
     * executor가 return 후 emitter onCompletion 콜백 → cleanupJob 순서로 정리됨.
     */
    public void cancelJob(String jobId) {
        cancellationRegistry.cancel(jobId);
        JobEntry entry = jobs.get(jobId);
        if (entry != null) {
            try {
                entry.emitter().complete();
            } catch (Exception ignored) {}
        }
        // cleanupJob은 emitter.onCompletion 콜백에서 자동 호출됨
        // 여기서 직접 호출하면 cancellationRegistry.remove가 너무 일찍 실행될 수 있음
        JobEntry removed = jobs.remove(jobId);
        if (removed != null) {
            activeUserJobs.remove(removed.userId(), jobId);
        }
        // 취소 플래그는 executor가 종료된 후 evictStaleEmitters에서 정리되거나
        // 다음 startGeneration 시 덮어씌워짐 — 여기서 remove 하지 않음
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void cleanupJob(String jobId) {
        JobEntry entry = jobs.remove(jobId);
        if (entry != null) {
            activeUserJobs.remove(entry.userId(), jobId);
            // cancelJob 경로에서는 이미 jobs.remove가 됐으므로 entry == null → 여기 안 들어옴
            // 정상 완료 / 타임아웃 경로에서만 취소 플래그 정리
            cancellationRegistry.remove(jobId);
        }
    }

    /** 1분 주기 — TTL 초과 emitter 강제 정리 */
    @Scheduled(fixedDelay = 60_000)
    public void evictStaleEmitters() {
        Instant threshold = Instant.now().minusMillis(EVICTION_THRESHOLD_MS);
        Iterator<Map.Entry<String, JobEntry>> it = jobs.entrySet().iterator();
        int evicted = 0;

        while (it.hasNext()) {
            Map.Entry<String, JobEntry> entry = it.next();
            if (entry.getValue().createdAt().isBefore(threshold)) {
                try {
                    entry.getValue().emitter().complete();
                } catch (Exception ignored) {}
                activeUserJobs.remove(entry.getValue().userId(), entry.getKey());
                cancellationRegistry.remove(entry.getKey());
                it.remove();
                evicted++;
            }
        }

        if (evicted > 0) {
            log.info("Evicted {} stale SSE emitters", evicted);
        }
    }
}
