package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.planning.dto.GenerateJobResponse;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 여행 일정 생성 워크플로우 서비스.
 * - jobId / emitter 생명주기 관리
 * - 유저별 동시 생성 1개 제한 (새 요청 시 기존 작업 취소)
 * - 취소 플래그는 CancellationRegistry에 위임 (순환 의존성 방지)
 * <p>
 * 다중 인스턴스 대응: "유저의 현재 활성 jobId"를 Redis 키(active:user:{userId})로 관리해
 * 인스턴스 간 공유한다. emitter 자체(jobs 맵)는 특정 인스턴스에 물리적으로 묶인 소켓이라
 * 인메모리로 유지하고, 같은 인스턴스로 라우팅되도록 하는 것은 ALB sticky session의 책임이다
 * (docs/sse-scaling.md 참고).
 */
@Slf4j
@Service
public class TravelPlannerService {

    private static final long SSE_TIMEOUT_MS = 600_000L;      // 10분
    private static final long EVICTION_THRESHOLD_MS = 720_000L; // 12분
    private static final String ACTIVE_USER_KEY_PREFIX = "active:user:";
    // SSE 타임아웃(10분)보다 약간 길게 두어 진행 중 job이 조기 만료되지 않게 한다.
    private static final Duration ACTIVE_USER_TTL = Duration.ofMinutes(12);

    /**
     * "현재 값이 기대한 jobId와 일치할 때만 삭제"하는 compare-and-delete Lua 스크립트.
     * ConcurrentHashMap.remove(key, value)의 원자적 값 비교 삭제 시맨틱을 Redis에서 재현한다 —
     * A→B 재생성 레이스에서 A의 취소가 B가 방금 등록한 새 jobId를 지우지 않도록 방어한다.
     */
    private static final RedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final OptimizedGenerationExecutor optimizedGenerationExecutor;
    private final GenerationResultStore resultStore;
    private final CancellationRegistry cancellationRegistry;
    private final StringRedisTemplate redisTemplate;

    public TravelPlannerService(OptimizedGenerationExecutor optimizedGenerationExecutor,
                                GenerationResultStore resultStore,
                                CancellationRegistry cancellationRegistry,
                                StringRedisTemplate redisTemplate) {
        this.optimizedGenerationExecutor = optimizedGenerationExecutor;
        this.resultStore = resultStore;
        this.cancellationRegistry = cancellationRegistry;
        this.redisTemplate = redisTemplate;
    }

    /** jobId → JobEntry (emitter는 이 인스턴스에 물리적으로 묶여 인메모리로만 관리) */
    private final ConcurrentHashMap<String, JobEntry> jobs = new ConcurrentHashMap<>();

    private record JobEntry(SseEmitter emitter, Long userId, Instant createdAt) {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * 일정 생성 시작.
     * 동일 유저의 기존 진행 중 작업이 있으면 취소 후 새 작업 시작.
     */
    public GenerateJobResponse startGeneration(ItineraryGenerateRequest request, Long userId) {
        String jobId = UUID.randomUUID().toString();

        // 유저의 활성 jobId를 새 jobId로 원자적 교체하고, 직전 값(기존 job)을 얻는다.
        // 다른 인스턴스에서 시작했더라도 Redis가 전역 단일 소스이므로 정확히 감지된다.
        String existingJobId = redisTemplate.opsForValue()
                .getAndSet(ACTIVE_USER_KEY_PREFIX + userId, jobId);
        redisTemplate.expire(ACTIVE_USER_KEY_PREFIX + userId, ACTIVE_USER_TTL);
        if (existingJobId != null && !existingJobId.equals(jobId)) {
            log.info("Cancelling existing job {} for user {} — new generation requested", existingJobId, userId);
            // 활성 키는 이미 새 jobId로 덮어썼으므로, 기존 job의 취소/정리만 수행한다.
            cancelJobInternal(existingJobId);
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        emitter.onCompletion(() -> cleanupJob(jobId));
        emitter.onTimeout(() -> {
            log.warn("SSE emitter timed out for job {}", jobId);
            cleanupJob(jobId);
        });
        emitter.onError(e -> cleanupJob(jobId));

        jobs.put(jobId, new JobEntry(emitter, userId, Instant.now()));

        optimizedGenerationExecutor.execute(jobId, request, userId, emitter);

        return new GenerateJobResponse(jobId);
    }

    /**
     * SSE emitter 조회.
     * jobId가 없으면 RESOURCE_NOT_FOUND — 클라이언트는 /plan/new로 안내.
     * (다중 인스턴스에서 이 인스턴스에 emitter가 없다면 sticky session 미설정 신호 — docs/sse-scaling.md)
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
        JobEntry entry = jobs.get(jobId);
        Long userId = entry != null ? entry.userId() : null;
        cancelJobInternal(jobId);
        // 명시적 취소 시에는 이 job이 활성 키의 소유자일 수 있으므로 CAS로 정리한다
        // (다른 job이 이미 활성 키를 차지했다면 건드리지 않음).
        if (userId != null) {
            clearActiveUserIfMatches(userId, jobId);
        }
    }

    /**
     * jobId 단위 취소·로컬 정리 (활성 유저 키는 건드리지 않음).
     * startGeneration에서 기존 job을 끊을 때, 그리고 cancelJob에서 공통으로 사용.
     */
    private void cancelJobInternal(String jobId) {
        cancellationRegistry.cancel(jobId);
        JobEntry entry = jobs.get(jobId);
        if (entry != null) {
            try {
                entry.emitter().complete();
            } catch (Exception ignored) {}
        }
        // cleanupJob은 emitter.onCompletion 콜백에서 자동 호출됨
        // 여기서 직접 호출하면 cancellationRegistry.remove가 너무 일찍 실행될 수 있음
        jobs.remove(jobId);
        // 취소 플래그는 executor가 종료된 후 evictStaleEmitters에서 정리되거나
        // 다음 startGeneration 시 덮어씌워짐 — 여기서 remove 하지 않음
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void cleanupJob(String jobId) {
        JobEntry entry = jobs.remove(jobId);
        if (entry != null) {
            clearActiveUserIfMatches(entry.userId(), jobId);
            // cancelJob 경로에서는 이미 jobs.remove가 됐으므로 entry == null → 여기 안 들어옴
            // 정상 완료 / 타임아웃 경로에서만 취소 플래그 정리
            cancellationRegistry.remove(jobId);
        }
    }

    /**
     * active:user:{userId}의 현재 값이 jobId일 때만 삭제 (CAS).
     * ConcurrentHashMap.remove(userId, jobId)의 값 비교 삭제를 Redis에서 재현한다.
     */
    private void clearActiveUserIfMatches(Long userId, String jobId) {
        redisTemplate.execute(COMPARE_AND_DELETE,
                List.of(ACTIVE_USER_KEY_PREFIX + userId), jobId);
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
                clearActiveUserIfMatches(entry.getValue().userId(), entry.getKey());
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
