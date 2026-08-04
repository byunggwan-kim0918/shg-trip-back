package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.GenerationQuotaResponse;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 생성 정책(R1 rate-limit / R2 쿼터) 단위 테스트.
 * Redis 상호작용은 목킹하고 임계값 판정·성공 리셋·쿼터 창 로직을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class GenerationPolicyServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private GenerationPolicyService service;

    private static final Long USER = 100L;
    private static final String FAIL_KEY = "generation:fail:100";
    private static final String QUOTA_KEY = "generation:quota:100";

    @BeforeEach
    void setUp() {
        service = new GenerationPolicyService(redisTemplate);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("한도 미만이면 통과")
    void allowsUnderLimits() {
        when(valueOps.get(FAIL_KEY)).thenReturn("2");
        when(valueOps.get(QUOTA_KEY)).thenReturn("3");
        assertThatCode(() -> service.checkAllowed(USER)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("키가 없으면(=0) 통과")
    void allowsWhenNoKeys() {
        when(valueOps.get(FAIL_KEY)).thenReturn(null);
        when(valueOps.get(QUOTA_KEY)).thenReturn(null);
        assertThatCode(() -> service.checkAllowed(USER)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("실패 5회 도달 시 GENERATION_BLOCKED")
    void blocksAtFiveFailures() {
        when(valueOps.get(FAIL_KEY)).thenReturn("5");
        when(redisTemplate.getExpire(FAIL_KEY, TimeUnit.SECONDS)).thenReturn(3600L);
        assertThatThrownBy(() -> service.checkAllowed(USER))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.GENERATION_BLOCKED);
    }

    @Test
    @DisplayName("쿼터 5회 소진 시 GENERATION_QUOTA_EXCEEDED (실패는 0)")
    void blocksAtQuotaExhausted() {
        when(valueOps.get(FAIL_KEY)).thenReturn("0");
        when(valueOps.get(QUOTA_KEY)).thenReturn("5");
        when(redisTemplate.getExpire(QUOTA_KEY, TimeUnit.SECONDS)).thenReturn(86400L);
        assertThatThrownBy(() -> service.checkAllowed(USER))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.GENERATION_QUOTA_EXCEEDED);
    }

    @Test
    @DisplayName("실패 차단이 쿼터보다 우선 (둘 다 초과여도 BLOCKED)")
    void failBlockTakesPrecedence() {
        when(valueOps.get(FAIL_KEY)).thenReturn("6");
        when(redisTemplate.getExpire(FAIL_KEY, TimeUnit.SECONDS)).thenReturn(3600L);
        assertThatThrownBy(() -> service.checkAllowed(USER))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.GENERATION_BLOCKED);
    }

    @Test
    @DisplayName("검증 실패 기록: 화이트리스트 코드면 INCR + 24h TTL 갱신")
    void recordsValidationFailureForCountableCode() {
        when(valueOps.increment(FAIL_KEY)).thenReturn(3L);
        service.recordValidationFailure(USER, "UNREALISTIC_BUDGET");
        verify(valueOps).increment(FAIL_KEY);
        verify(redisTemplate).expire(FAIL_KEY, Duration.ofHours(24));
    }

    @Test
    @DisplayName("검증 실패 기록: 화이트리스트 밖 코드(INVALID_INPUT)·null은 집계하지 않음")
    void skipsNonCountableValidationFailure() {
        service.recordValidationFailure(USER, "INVALID_INPUT"); // AI 포맷/미상 폴백
        service.recordValidationFailure(USER, null);            // valid 키 누락 등
        service.recordValidationFailure(USER, "SOMETHING_ELSE");
        verify(valueOps, never()).increment(FAIL_KEY);
        verify(redisTemplate, never()).expire(eq(FAIL_KEY), org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    @DisplayName("성공 시 실패 리셋(DEL) + 쿼터 창 원자 시드(SET NX EX 30d) 후 INCR")
    void recordsSuccessSeedsWindowThenIncrements() {
        when(valueOps.increment(QUOTA_KEY)).thenReturn(1L);
        service.recordSuccess(USER);
        verify(redisTemplate).delete(FAIL_KEY);
        verify(valueOps).setIfAbsent(QUOTA_KEY, "0", Duration.ofDays(30)); // 원자 시드
        verify(valueOps).increment(QUOTA_KEY);
        // 별도 expire 호출 없음(TTL은 시드가 담당) → INCR/EXPIRE 분리로 인한 영구 잠금 제거
        verify(redisTemplate, never()).expire(eq(QUOTA_KEY), org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    @DisplayName("성공 반복: 시드는 NX라 기존 창을 덮지 않고 INCR만(고정 창 유지)")
    void recordsSuccessKeepsWindow() {
        when(valueOps.increment(QUOTA_KEY)).thenReturn(2L);
        service.recordSuccess(USER);
        verify(redisTemplate).delete(FAIL_KEY);
        verify(valueOps).setIfAbsent(QUOTA_KEY, "0", Duration.ofDays(30)); // 호출되나 Redis NX가 no-op 보장
        verify(valueOps).increment(QUOTA_KEY);
        verify(redisTemplate, never()).expire(eq(QUOTA_KEY), org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    @DisplayName("잔여 조회: 사용량·리셋시각 반환, 차단 아니면 blockedUntil=null")
    void statusNotBlocked() {
        when(valueOps.get(QUOTA_KEY)).thenReturn("3");
        when(redisTemplate.getExpire(QUOTA_KEY, TimeUnit.SECONDS)).thenReturn(100000L);
        when(valueOps.get(FAIL_KEY)).thenReturn("2");

        GenerationQuotaResponse status = service.getStatus(USER);
        assertThat(status.used()).isEqualTo(3);
        assertThat(status.limit()).isEqualTo(5);
        assertThat(status.resetAt()).isNotNull();
        assertThat(status.blockedUntil()).isNull();
    }

    @Test
    @DisplayName("잔여 조회: 실패 5회면 blockedUntil 채워짐")
    void statusBlocked() {
        when(valueOps.get(QUOTA_KEY)).thenReturn("1");
        when(redisTemplate.getExpire(QUOTA_KEY, TimeUnit.SECONDS)).thenReturn(100000L);
        when(valueOps.get(FAIL_KEY)).thenReturn("5");
        when(redisTemplate.getExpire(FAIL_KEY, TimeUnit.SECONDS)).thenReturn(3600L);

        GenerationQuotaResponse status = service.getStatus(USER);
        assertThat(status.blockedUntil()).isNotNull();
    }
}
