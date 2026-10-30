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
 * 인스턴스 간 공유한다. 작업 출력(jobs 맵의 GenerationStream)은 특정 인스턴스의 메모리에 있으므로
 * 같은 인스턴스로 라우팅되도록 하는 것은 ALB sticky session의 책임이다(docs/sse-scaling.md 참고).
 * <p>
 * 작업과 HTTP 연결은 수명이 다르다 — 새로고침으로 연결이 끊겨도 작업은 계속 돌고, 다시 붙은
 * 연결은 {@link GenerationStream}이 기록한 이벤트로 따라잡는다. 끝난 작업도 정리 주기 전까지는
 * 맵에 남아 재접속 시 결과 이벤트를 받는다.
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
    private final GenerationPolicyService generationPolicyService;

    public TravelPlannerService(OptimizedGenerationExecutor optimizedGenerationExecutor,
                                GenerationResultStore resultStore,
                                CancellationRegistry cancellationRegistry,
                                StringRedisTemplate redisTemplate,
                                GenerationPolicyService generationPolicyService) {
        this.optimizedGenerationExecutor = optimizedGenerationExecutor;
        this.resultStore = resultStore;
        this.cancellationRegistry = cancellationRegistry;
        this.redisTemplate = redisTemplate;
        this.generationPolicyService = generationPolicyService;
    }

    private final ConcurrentHashMap<String, JobEntry> jobs = new ConcurrentHashMap<>();

    private record JobEntry(GenerationStream stream, Long userId, Instant createdAt) {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * 일정 생성 시작.
     * 동일 유저의 기존 진행 중 작업이 있으면 취소 후 새 작업 시작.
     */
    public GenerateJobResponse startGeneration(ItineraryGenerateRequest request, Long userId) {
        // R1/R2 차단 체크(동기) — 기존 job을 건드리기 전에 먼저 거부(TOO_MANY_REQUESTS).
        generationPolicyService.checkAllowed(userId);

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

        GenerationStream stream = new GenerationStream(jobId, () -> onJobFinished(jobId));
        jobs.put(jobId, new JobEntry(stream, userId, Instant.now()));

        optimizedGenerationExecutor.execute(jobId, request, userId, stream);

        return new GenerateJobResponse(jobId);
    }

    /**
     * 작업의 SSE 스트림을 연다. 진행 중이면 새 연결을 붙여 지금까지의 이벤트부터 다시 보내고,
     * 이 인스턴스에 작업이 없지만 결과가 남아 있으면 complete만 보낸다. 둘 다 아니거나 남의 작업이면
     * error 이벤트 하나를 보내고 닫는다 — 여기서 예외를 던지면 text/event-stream 응답에 JSON을
     * 쓰지 못해 클라이언트가 응답 없이 매달린다.
     */
    public SseEmitter openStream(String jobId, Long userId) {
        JobEntry entry = jobs.get(jobId);
        if (entry != null && entry.userId().equals(userId)) {
            SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
            GenerationStream stream = entry.stream();
            emitter.onCompletion(() -> stream.detach(emitter));
            emitter.onTimeout(() -> stream.detach(emitter));
            emitter.onError(e -> stream.detach(emitter));
            stream.attach(emitter);
            return emitter;
        }
        if (entry == null && resultStore.findForOwner(jobId, userId) != null) {
            return completedStream();
        }
        ErrorCode code = ErrorCode.GENERATION_JOB_NOT_FOUND;
        return GenerationStream.rejected(code.getCode(), code.getMessage());
    }

    private static SseEmitter completedStream() {
        SseEmitter emitter = new SseEmitter();
        try {
            emitter.send(SseEmitter.event().name("complete").data(Map.of("status", "DONE")));
        } catch (java.io.IOException | IllegalStateException e) {
            log.debug("SSE complete 재전송 실패: {}", e.getMessage());
        }
        emitter.complete();
        return emitter;
    }

    /**
     * 완료된 itineraryId 조회 (TTL 내 재조회 허용). 남의 jobId는 없는 것과 같게 응답한다.
     */
    public Long getResult(String jobId, Long userId) {
        Long itineraryId = resultStore.findForOwner(jobId, userId);
        if (itineraryId == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "완료된 생성 결과를 찾을 수 없습니다.");
        }
        return itineraryId;
    }

    /**
     * 작업 취소 (새 요청 시 기존 작업 중단용).
     * 취소 플래그는 executor가 감지할 수 있도록 여기서 제거하지 않음.
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
        // 맵에서 먼저 빼야 onJobFinished가 취소 플래그를 지우지 않는다 — executor가 플래그를 보고 멈춘다.
        JobEntry entry = jobs.remove(jobId);
        if (entry != null) {
            entry.stream().complete();
        }
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    /** 작업이 끝났을 때(정상·오류). 재접속용으로 맵 항목은 정리 주기까지 남긴다. */
    private void onJobFinished(String jobId) {
        JobEntry entry = jobs.get(jobId);
        if (entry != null) {
            clearActiveUserIfMatches(entry.userId(), jobId);
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

    /** 1분 주기 — TTL 초과 작업 강제 정리 */
    @Scheduled(fixedDelay = 60_000)
    public void evictStaleEmitters() {
        Instant threshold = Instant.now().minusMillis(EVICTION_THRESHOLD_MS);
        Iterator<Map.Entry<String, JobEntry>> it = jobs.entrySet().iterator();
        int evicted = 0;

        while (it.hasNext()) {
            Map.Entry<String, JobEntry> entry = it.next();
            if (entry.getValue().createdAt().isBefore(threshold)) {
                entry.getValue().stream().complete();
                clearActiveUserIfMatches(entry.getValue().userId(), entry.getKey());
                cancellationRegistry.remove(entry.getKey());
                it.remove();
                evicted++;
            }
        }

        if (evicted > 0) {
            log.info("Evicted {} stale generation jobs", evicted);
        }
    }
}
