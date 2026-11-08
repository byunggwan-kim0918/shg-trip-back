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
 * 유저당 동시 생성은 1개다. 새 요청이 오면 기존 작업을 취소한다.
 * <p>
 * 활성 jobId는 Redis 키(active:user:{userId})로 인스턴스 간 공유하지만 GenerationStream은 인스턴스 메모리에 있다.
 * 같은 인스턴스로 라우팅하는 것은 ALB sticky session이 맡는다(docs/sse-scaling.md).
 * <p>
 * 새로고침으로 연결이 끊겨도 작업은 계속 돈다. 끝난 작업도 정리 주기 전까지 맵에 남아 재접속 시 결과를 받는다.
 */
@Slf4j
@Service
public class TravelPlannerService {

    private static final long SSE_TIMEOUT_MS = 600_000L;
    private static final long EVICTION_THRESHOLD_MS = 720_000L;
    private static final String ACTIVE_USER_KEY_PREFIX = "active:user:";
    // SSE 타임아웃(10분)보다 약간 길게 두어 진행 중 job이 조기 만료되지 않게 한다.
    private static final Duration ACTIVE_USER_TTL = Duration.ofMinutes(12);

    /**
     * 현재 값이 기대한 jobId일 때만 지운다.
     * A→B 재생성 레이스에서 A의 취소가 B가 방금 등록한 jobId를 지우면 안 된다.
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

    /**
     * 동일 유저의 진행 중 작업은 취소하고 새로 시작한다.
     */
    public GenerateJobResponse startGeneration(ItineraryGenerateRequest request, Long userId) {
        // 차단이면 기존 job을 건드리기 전에 거부해야 한다.
        generationPolicyService.checkAllowed(userId);

        String jobId = UUID.randomUUID().toString();

        // 원자적 교체로 직전 jobId를 얻어야 다른 인스턴스에서 시작한 기존 job도 감지된다.
        String existingJobId = redisTemplate.opsForValue()
                .getAndSet(ACTIVE_USER_KEY_PREFIX + userId, jobId);
        redisTemplate.expire(ACTIVE_USER_KEY_PREFIX + userId, ACTIVE_USER_TTL);
        if (existingJobId != null && !existingJobId.equals(jobId)) {
            log.info("Cancelling existing job {} for user {} — new generation requested", existingJobId, userId);
            // 활성 키는 이미 새 jobId로 덮어써 기존 job의 취소·정리만 한다.
            cancelJobInternal(existingJobId);
        }

        GenerationStream stream = new GenerationStream(jobId, () -> onJobFinished(jobId));
        jobs.put(jobId, new JobEntry(stream, userId, Instant.now()));

        optimizedGenerationExecutor.execute(jobId, request, userId, stream);

        return new GenerateJobResponse(jobId);
    }

    /**
     * 작업이 없고 결과도 없거나 남의 작업이면 error 이벤트 하나를 보내고 닫는다.
     * 예외를 던지면 text/event-stream 응답에 JSON을 쓰지 못해 클라이언트가 응답 없이 매달린다.
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
     * 남의 jobId는 없는 것과 같게 응답한다.
     */
    public Long getResult(String jobId, Long userId) {
        Long itineraryId = resultStore.findForOwner(jobId, userId);
        if (itineraryId == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "완료된 생성 결과를 찾을 수 없습니다.");
        }
        return itineraryId;
    }

    /**
     * 취소 플래그는 executor가 감지하도록 여기서 지우지 않는다.
     */
    public void cancelJob(String jobId) {
        JobEntry entry = jobs.get(jobId);
        Long userId = entry != null ? entry.userId() : null;
        cancelJobInternal(jobId);
        // 이 job이 활성 키의 소유자일 수 있어 CAS로 정리한다. 다른 job이 차지한 키는 건드리지 않는다.
        if (userId != null) {
            clearActiveUserIfMatches(userId, jobId);
        }
    }

    /**
     * 활성 유저 키는 건드리지 않는다.
     */
    private void cancelJobInternal(String jobId) {
        cancellationRegistry.cancel(jobId);
        // 맵에서 먼저 빼야 onJobFinished가 취소 플래그를 지우지 않아 executor가 멈춘다.
        JobEntry entry = jobs.remove(jobId);
        if (entry != null) {
            entry.stream().complete();
        }
    }

    /** 작업이 끝났을 때(정상·오류). 재접속용으로 맵 항목은 정리 주기까지 남긴다. */
    private void onJobFinished(String jobId) {
        JobEntry entry = jobs.get(jobId);
        if (entry != null) {
            clearActiveUserIfMatches(entry.userId(), jobId);
            cancellationRegistry.remove(jobId);
        }
    }

    /**
     * active:user:{userId}의 현재 값이 jobId일 때만 삭제한다(CAS).
     */
    private void clearActiveUserIfMatches(Long userId, String jobId) {
        redisTemplate.execute(COMPARE_AND_DELETE,
                List.of(ACTIVE_USER_KEY_PREFIX + userId), jobId);
    }

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
