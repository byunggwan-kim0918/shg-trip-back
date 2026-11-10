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
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 생성 정책(rate-limit·쿼터)을 다중 인스턴스 전역에서 Redis 카운터로 관리한다.
 *
 * <p>두 정책은 세는 대상이 달라 중복이 아니다:
 * <ul>
 *   <li><b>실패 rate-limit</b>: enrich valid:false(비현실 입력)만 세고 5회면 24h 차단한다.
 *       서버 오류·AI 출력 문제는 사용자 책임이 아니라 세지 않는다.</li>
 *   <li><b>생성 쿼터</b>: 30일 창 성공 5회. 서버 실패로 소모되지 않게 성공 시에만 센다.</li>
 * </ul>
 *
 * <p>차단 체크는 진입부(동기)에서, INCR/DEL은 executor 스레드에서 일어난다.
 * 진입부 체크와 카운트 사이의 동시 초과는 유저당 활성 job 1개 제약이 대부분 막는다.
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
    private static final String IDENTITY_SUFFIX = "ident:";

    // 문장 파싱(Haiku)은 디바운스 입력마다 불려 생성보다 훨씬 잦다. 정상 타이핑은 분당 수 회 수준이다.
    private static final String PARSE_MINUTE_KEY_PREFIX = "parse:rate:min:";
    private static final String PARSE_DAY_KEY_PREFIX = "parse:rate:day:";
    static final int MAX_PARSE_PER_MINUTE = 20;
    static final int MAX_PARSE_PER_DAY = 200;
    private static final Duration PARSE_MINUTE_TTL = Duration.ofMinutes(1);
    private static final Duration PARSE_DAY_TTL = Duration.ofDays(1);

    // 대화 입력은 자유 입력 답마다 Haiku를 부른다. 한 세션은 보통 4~6회다.
    private static final String TRIP_CHAT_MINUTE_KEY_PREFIX = "tripchat:rate:min:";
    private static final String TRIP_CHAT_DAY_KEY_PREFIX = "tripchat:rate:day:";
    static final int MAX_TRIP_CHAT_PER_MINUTE = 20;
    static final int MAX_TRIP_CHAT_PER_DAY = 200;
    private static final Duration TRIP_CHAT_MINUTE_TTL = Duration.ofMinutes(1);
    private static final Duration TRIP_CHAT_DAY_TTL = Duration.ofDays(1);

    /**
     * 사용자 입력 문제만 실패로 센다. AI 포맷 오류·{@code valid} 키 누락("INVALID_INPUT" 폴백)·미상 코드를
     * 세면 정상 사용자가 AI 변덕으로 24h 차단된다.
     */
    private static final Set<String> COUNTABLE_ERROR_CODES = Set.of(
            "UNREALISTIC_BUDGET", "CONFLICTING_THEMES", "INVALID_DESTINATION", "INVALID_DATE_RANGE");

    private final StringRedisTemplate redisTemplate;

    /**
     * 차단이면 SSE 시작 전에 BusinessException(TOO_MANY_REQUESTS)을 던진다.
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
     * {@link #COUNTABLE_ERROR_CODES}에 해당할 때만 실패를 센다.
     */
    public void recordValidationFailure(Long userId, String errorCode) {
        // AI가 케이싱을 흔들어도 화이트리스트 집계가 새지 않게 정규화한다.
        String normalized = errorCode == null ? null : errorCode.trim().toUpperCase();
        if (normalized == null || !COUNTABLE_ERROR_CODES.contains(normalized)) {
            log.info("enrich valid:false but errorCode '{}' not countable — rate-limit 집계 제외 (user {})",
                    errorCode, userId);
            return;
        }
        // Redis 블립이 생성 critical path(SSE/story)를 깨지 않게 정책 부기 예외는 삼킨다.
        try {
            String key = FAIL_KEY_PREFIX + userId;
            // INCR/EXPIRE만 쓰면 TTL이 빠져 임계 도달 시 영구 차단될 수 있어 TTL과 함께 NX로 먼저 시드한다.
            redisTemplate.opsForValue().setIfAbsent(key, "0", FAIL_TTL);
            Long count = redisTemplate.opsForValue().increment(key);
            redisTemplate.expire(key, FAIL_TTL); // 마지막 실패로부터 24h 슬라이딩
            log.info("Generation validation failure for user {} — code={}, count={}", userId, normalized, count);
        } catch (Exception e) {
            log.warn("recordValidationFailure best-effort 실패 (user {}): {}", userId, e.getMessage());
        }
    }

    /** best-effort라 Redis 오류가 critical path를 깨지 않는다. */
    public void recordSuccess(Long userId) {
        try {
            redisTemplate.delete(FAIL_KEY_PREFIX + userId);
            String key = QUOTA_KEY_PREFIX + userId;
            // INCR/EXPIRE를 나누면 크래시 때 TTL이 빠져 영구 잠금되므로 창을 NX로 먼저 시드한다.
            // 키가 이미 있으면 NX가 no-op이라 고정 창이 유지된다.
            redisTemplate.opsForValue().setIfAbsent(key, "0", QUOTA_TTL);
            Long count = redisTemplate.opsForValue().increment(key);
            restoreTtlIfMissing(key, QUOTA_TTL);
            log.info("Generation success for user {} — quota used={}", userId, count);
        } catch (Exception e) {
            log.warn("recordSuccess best-effort 실패 (user {}): {}", userId, e.getMessage());
        }
    }

    /**
     * 문장 파싱 호출 제한(고정 창). 초과면 BusinessException(TOO_MANY_REQUESTS).
     * Redis 장애 시에는 통과시킨다. 파싱은 생성 전 보조 기능이라 막는 쪽 피해가 더 크다.
     */
    public void checkParseAllowed(Long userId) {
        long perMinute;
        long perDay;
        try {
            perMinute = incrementWindow(PARSE_MINUTE_KEY_PREFIX + userId, PARSE_MINUTE_TTL);
            perDay = perMinute > MAX_PARSE_PER_MINUTE ? 0
                    : incrementWindow(PARSE_DAY_KEY_PREFIX + userId, PARSE_DAY_TTL);
        } catch (Exception e) {
            log.warn("checkParseAllowed 조회 실패 — 통과(fail-open, user {}): {}", userId, e.getMessage());
            return;
        }
        if (perMinute > MAX_PARSE_PER_MINUTE) {
            throw new BusinessException(ErrorCode.PARSE_RATE_LIMITED);
        }
        if (perDay > MAX_PARSE_PER_DAY) {
            throw new BusinessException(ErrorCode.PARSE_RATE_LIMITED,
                    "오늘 문장 분석 한도(" + MAX_PARSE_PER_DAY + "회)를 모두 사용했어요. 마법사로 직접 입력해 주세요.");
        }
    }

    /**
     * 대화 입력 해석 호출 제한(고정 창). Redis 장애 시에는 통과시킨다. 입력을 막는 쪽 피해가 더 크다.
     */
    public void checkTripChatAllowed(Long userId) {
        long perMinute;
        long perDay;
        try {
            perMinute = incrementWindow(TRIP_CHAT_MINUTE_KEY_PREFIX + userId, TRIP_CHAT_MINUTE_TTL);
            perDay = perMinute > MAX_TRIP_CHAT_PER_MINUTE ? 0
                    : incrementWindow(TRIP_CHAT_DAY_KEY_PREFIX + userId, TRIP_CHAT_DAY_TTL);
        } catch (Exception e) {
            log.warn("checkTripChatAllowed 조회 실패 — 통과(fail-open, user {}): {}", userId, e.getMessage());
            return;
        }
        if (perMinute > MAX_TRIP_CHAT_PER_MINUTE) {
            throw new BusinessException(ErrorCode.TRIP_CHAT_RATE_LIMITED);
        }
        if (perDay > MAX_TRIP_CHAT_PER_DAY) {
            throw new BusinessException(ErrorCode.TRIP_CHAT_RATE_LIMITED,
                    "오늘 대화 답변 한도(" + MAX_TRIP_CHAT_PER_DAY + "회)를 모두 사용했어요. 내일 다시 이어서 할 수 있어요.");
        }
    }

    private long incrementWindow(String key, Duration ttl) {
        redisTemplate.opsForValue().setIfAbsent(key, "0", ttl);
        Long count = redisTemplate.opsForValue().increment(key);
        restoreTtlIfMissing(key, ttl);
        return count != null ? count : 0;
    }

    /**
     * 시드(NX)와 INCR 사이에 키가 만료되면 INCR이 TTL 없는 키를 만들어 사용자가 영구 차단된다.
     * -1(TTL 없음)일 때만 다시 건다.
     */
    private void restoreTtlIfMissing(String key, Duration ttl) {
        Long remaining = redisTemplate.getExpire(key);
        if (remaining != null && remaining == -1) {
            redisTemplate.expire(key, ttl);
        }
    }

    /** 탈퇴 직전 카운터를 소셜 계정 식별 해시로 옮긴다. 재가입으로 쿼터·차단이 초기화되지 않게 한다. */
    public void parkCountersForWithdrawal(Long userId, List<String> identityHashes) {
        moveCounters(String.valueOf(userId), identityHashes.stream().map(h -> IDENTITY_SUFFIX + h).toList());
    }

    public void restoreCountersOnSignup(String identityHash, Long newUserId) {
        moveCounters(IDENTITY_SUFFIX + identityHash, List.of(String.valueOf(newUserId)));
    }

    private void moveCounters(String fromSuffix, List<String> toSuffixes) {
        if (toSuffixes.isEmpty()) return;
        for (String prefix : List.of(FAIL_KEY_PREFIX, QUOTA_KEY_PREFIX)) {
            String from = prefix + fromSuffix;
            try {
                String value = redisTemplate.opsForValue().get(from);
                Long ttl = getTtlSeconds(from);
                if (value == null || ttl == null) continue;
                for (String to : toSuffixes) {
                    redisTemplate.opsForValue().set(prefix + to, value, Duration.ofSeconds(ttl));
                }
                redisTemplate.delete(from);
            } catch (Exception e) {
                log.warn("생성 카운터 이전 실패 ({}): {}", from, e.getMessage());
            }
        }
    }

    /** Redis 장애 시 배지가 UI를 500으로 깨지 않게 기본값을 돌려준다(fail-open). */
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
