package com.shg.trip.shgtrip.domain.planning.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * CancellationRegistry 단위 테스트.
 * 취소 플래그가 Redis에 저장되어 인스턴스 간 공유되고, 로컬 캐시가 Redis 왕복을
 * 줄이는 최적화로 동작하며, 진실의 원천은 Redis 키임을 검증.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CancellationRegistryTest {

    private static final String KEY = "cancel:job:job-1";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private CancellationRegistry registry;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        registry = new CancellationRegistry(redisTemplate);
    }

    @Test
    @DisplayName("cancel은 Redis에 TTL 12분으로 플래그를 저장한다")
    void cancel_setsRedisKeyWithTtl() {
        registry.cancel("job-1");

        verify(valueOperations).set(KEY, "1", Duration.ofMinutes(12));
    }

    @Test
    @DisplayName("취소된 job은 로컬 캐시에서 즉시 true를 반환한다 (Redis 미조회)")
    void isCancelled_returnsTrueFromLocalCacheWithoutRedis() {
        registry.cancel("job-1");

        boolean result = registry.isCancelled("job-1");

        assertThat(result).isTrue();
        // 로컬 캐시 히트이므로 Redis hasKey는 호출되지 않아야 한다
        verify(redisTemplate, never()).hasKey(any());
    }

    @Test
    @DisplayName("로컬 캐시에 없으면 Redis 키를 확인해 다른 인스턴스의 취소를 감지한다")
    void isCancelled_fallsBackToRedisForCrossInstanceCancel() {
        // 이 인스턴스에서는 cancel하지 않았지만 Redis에는 키가 있는 상황
        when(redisTemplate.hasKey(KEY)).thenReturn(true);

        boolean result = registry.isCancelled("job-1");

        assertThat(result).isTrue();
        verify(redisTemplate).hasKey(KEY);
    }

    @Test
    @DisplayName("Redis에서 감지한 취소는 로컬 캐시에 반영되어 이후 조회는 Redis를 재조회하지 않는다")
    void isCancelled_cachesRedisHitLocally() {
        when(redisTemplate.hasKey(KEY)).thenReturn(true);

        registry.isCancelled("job-1"); // Redis 조회 → 캐시에 저장
        registry.isCancelled("job-1"); // 캐시 히트

        // Redis hasKey는 첫 조회에서 한 번만 호출된다
        verify(redisTemplate, times(1)).hasKey(KEY);
    }

    @Test
    @DisplayName("취소되지 않은 job은 false를 반환한다")
    void isCancelled_returnsFalseWhenNotCancelled() {
        when(redisTemplate.hasKey(KEY)).thenReturn(false);

        assertThat(registry.isCancelled("job-1")).isFalse();
    }

    @Test
    @DisplayName("TTL 만료 등으로 Redis 키가 없으면(hasKey=null) 미취소로 처리한다")
    void isCancelled_treatsMissingKeyAsNotCancelled() {
        when(redisTemplate.hasKey(KEY)).thenReturn(null);

        assertThat(registry.isCancelled("job-1")).isFalse();
    }

    @Test
    @DisplayName("remove는 로컬 캐시와 Redis 키를 모두 정리한다")
    void remove_clearsLocalCacheAndRedis() {
        registry.cancel("job-1"); // 로컬 캐시에 표시

        registry.remove("job-1");

        verify(redisTemplate).delete(KEY);
        // remove 후에는 로컬 캐시가 비었으므로 Redis 조회로 폴백한다
        when(redisTemplate.hasKey(KEY)).thenReturn(false);
        assertThat(registry.isCancelled("job-1")).isFalse();
        verify(redisTemplate).hasKey(KEY);
    }
}
