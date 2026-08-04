package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.GenerationQuotaResponse;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 생성 정책(rate-limit·쿼터) — Redis 카운터로 전역(다중 인스턴스) 관리.
 *
 * <p>두 정책은 세는 대상이 달라 중복이 아니다:
 * <ul>
 *   <li><b>R1 실패 rate-limit</b>: enrich valid:false(비현실 입력)만 카운트 → 5회 도달 시 24h 차단.
 *       봇/무의미 반복 방어. 서버 오류·AI 출력 문제는 카운트하지 않는다(사용자 책임 아님).</li>
 *   <li><b>R2 생성 쿼터</b>: 30일 창 성공 5회(수익성). 성공 시에만 소모(서버 실패로 소모 방지).</li>
 * </ul>
 *
 * <p>@Async 경계 대응: 차단 체크는 startGeneration 진입부(동기)에서, 카운트 변경(INCR/DEL)은
 * executor 스레드에서 일어난다. Redis INCR/DEL은 원자적이라 카운트 자체는 안전하고,
 * 진입부 체크는 "현재 used>=5면 차단"만 하므로 동시 초과는 유저당 활성 job 1개 제약이 대부분 막는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenerationPolicyService {

    private static final String FAIL_KEY_PREFIX = "generation:fail:";
    private static final String QUOTA_KEY_PREFIX = "generation:quota:";
    static final int MAX_FAILURES = 5;
    static final int MAX_QUOTA = 5;
    private static final Duration FAIL_TTL = Duration.ofHours(24);
    private static final Duration QUOTA_TTL = Duration.ofDays(30);

    /**
     * 실패 카운트 대상 errorCode 화이트리스트 — <b>사용자 입력</b> 문제만 집계한다.
     * AI 포맷 오류·{@code valid} 키 누락(→ "INVALID_INPUT" 폴백)·미상 코드는 사용자 책임이 아니므로
     * 카운트하지 않는다(정상 사용자가 AI 변덕으로 24h 차단되는 것을 방지 — 클래스 주석의 철학 준수).
     */
    private static final Set<String> COUNTABLE_ERROR_CODES = Set.of(
            "UNREALISTIC_BUDGET", "CONFLICTING_THEMES", "INVALID_DESTINATION", "INVALID_DATE_RANGE");

    private final StringRedisTemplate redisTemplate;

    /**
     * 생성 시작 진입부 체크(동기). 차단이면 BusinessException(TOO_MANY_REQUESTS) — SSE 시작 전 즉시 반환.
     */
    public void checkAllowed(Long userId) {
        long fails = getCount(FAIL_KEY_PREFIX + userId);
        if (fails >= MAX_FAILURES) {
            throw new BusinessException(ErrorCode.GENERATION_BLOCKED,
                    blockMessage(getTtlSeconds(FAIL_KEY_PREFIX + userId)));
        }
        long quota = getCount(QUOTA_KEY_PREFIX + userId);
        if (quota >= MAX_QUOTA) {
            throw new BusinessException(ErrorCode.GENERATION_QUOTA_EXCEEDED,
                    quotaMessage(getTtlSeconds(QUOTA_KEY_PREFIX + userId)));
        }
    }

    /**
     * enrich valid:false(입력 검증 실패) — 실패 카운트 INCR + 24h 창 갱신.
     * 단 사용자 입력 문제({@link #COUNTABLE_ERROR_CODES})일 때만 집계한다.
     * AI 포맷 오류·미상 코드는 카운트하지 않는다(정상 사용자 오차단 방지).
     */
    public void recordValidationFailure(Long userId, String errorCode) {
        // 대소문자/공백 정규화 — AI가 케이싱을 흔들어도 화이트리스트 집계가 새지 않게.
        String normalized = errorCode == null ? null : errorCode.trim().toUpperCase();
        if (normalized == null || !COUNTABLE_ERROR_CODES.contains(normalized)) {
            log.info("enrich valid:false but errorCode '{}' not countable — rate-limit 집계 제외 (user {})",
                    errorCode, userId);
            return;
        }
        // 정책 부기(bookkeeping)는 best-effort — Redis 블립이 생성 critical path(SSE/story)를 깨지 않게 삼킨다.
        try {
            String key = FAIL_KEY_PREFIX + userId;
            // 신규 키를 TTL과 함께 원자적으로 시드(NX) 후 INCR/EXPIRE — INCR/EXPIRE 분리로 인한
            // TTL 누락(임계 도달 시 영구 차단)을 quota 경로와 동일하게 방지한다.
            redisTemplate.opsForValue().setIfAbsent(key, "0", FAIL_TTL);
            Long count = redisTemplate.opsForValue().increment(key);
            redisTemplate.expire(key, FAIL_TTL); // 마지막 실패로부터 24h 슬라이딩 갱신(best-effort)
            log.info("Generation validation failure for user {} — code={}, count={}", userId, normalized, count);
        } catch (Exception e) {
            log.warn("recordValidationFailure best-effort 실패 (user {}): {}", userId, e.getMessage());
        }
    }

    /** 생성 성공 — 실패 카운트 리셋 + 30일 쿼터 INCR. (best-effort — critical path 비파괴) */
    public void recordSuccess(Long userId) {
        try {
            redisTemplate.delete(FAIL_KEY_PREFIX + userId);
            String key = QUOTA_KEY_PREFIX + userId;
            // 창을 원자적으로 시드(SET key 0 EX 30d NX) 후 INCR — INCR/EXPIRE 분리로 인한
            // TTL 누락(크래시 시 영구 잠금)을 방지한다. 이미 존재하면 NX로 no-op(고정 창 유지).
            redisTemplate.opsForValue().setIfAbsent(key, "0", QUOTA_TTL);
            Long count = redisTemplate.opsForValue().increment(key);
            log.info("Generation success for user {} — quota used={}", userId, count);
        } catch (Exception e) {
            log.warn("recordSuccess best-effort 실패 (user {}): {}", userId, e.getMessage());
        }
    }

    /** 잔여 조회(배지·차단 안내 겸용). Redis 장애 시 fail-open — 배지가 UI를 500으로 깨지 않게 기본값 반환. */
    public GenerationQuotaResponse getStatus(Long userId) {
        try {
            long used = getCount(QUOTA_KEY_PREFIX + userId);
            OffsetDateTime resetAt = ttlToInstant(getTtlSeconds(QUOTA_KEY_PREFIX + userId));

            OffsetDateTime blockedUntil = null;
            if (getCount(FAIL_KEY_PREFIX + userId) >= MAX_FAILURES) {
                blockedUntil = ttlToInstant(getTtlSeconds(FAIL_KEY_PREFIX + userId));
            }
            return new GenerationQuotaResponse((int) used, MAX_QUOTA, resetAt, blockedUntil);
        } catch (Exception e) {
            log.warn("getStatus 조회 실패 — 기본값 반환(fail-open, user {}): {}", userId, e.getMessage());
            return new GenerationQuotaResponse(0, MAX_QUOTA, null, null);
        }
    }

    // ── helpers ──

    private long getCount(String key) {
        String v = redisTemplate.opsForValue().get(key);
        if (v == null) return 0;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 남은 TTL(초). 키 없음/만료없음이면 null. */
    private Long getTtlSeconds(String key) {
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        return (ttl != null && ttl > 0) ? ttl : null;
    }

    private OffsetDateTime ttlToInstant(Long ttlSeconds) {
        return ttlSeconds != null ? OffsetDateTime.now().plusSeconds(ttlSeconds) : null;
    }

    private String blockMessage(Long ttlSeconds) {
        long hours = ttlSeconds != null ? (ttlSeconds + 3599) / 3600 : 24;
        return "입력 검증에 " + MAX_FAILURES + "회 실패해 생성이 일시 제한됐어요. 약 " + hours
                + "시간 후 다시 시도할 수 있어요. 여행지·기간·예산을 현실적으로 조정해 주세요.";
    }

    private String quotaMessage(Long ttlSeconds) {
        long days = ttlSeconds != null ? (ttlSeconds + 86399) / 86400 : 30;
        return "30일 생성 한도(" + MAX_QUOTA + "회)를 모두 사용했어요. 약 " + days + "일 후 다시 생성할 수 있어요.";
    }
}
