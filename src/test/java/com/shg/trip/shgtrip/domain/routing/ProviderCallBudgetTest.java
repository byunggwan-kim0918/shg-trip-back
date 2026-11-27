package com.shg.trip.shgtrip.domain.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderCallBudgetTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 한국 11월 26일 오전 1시, UTC로는 25일이다. */
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-25T16:00:00Z"), ZoneOffset.UTC);
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private ProviderCallBudget budget;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        budget = new ProviderCallBudget(redis, clock);
    }

    @Test
    void 일일_한도는_공급자_현지_날짜로_센다() {
        when(ops.increment("route:budget:kakao:d:2026-11-26")).thenReturn(1L);

        assertThat(budget.tryAcquire("kakao", 10, 0, SEOUL)).isTrue();
        verify(redis).expire("route:budget:kakao:d:2026-11-26", Duration.ofDays(2));
    }

    @Test
    void 한도를_넘으면_거절한다() {
        when(ops.increment(anyString())).thenReturn(11L);

        assertThat(budget.tryAcquire("kakao", 10, 0, SEOUL)).isFalse();
        verify(redis, never()).expire(anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    void 분_한도를_넘으면_일일_카운터를_쓰지_않는다() {
        when(ops.increment(startsWith("route:budget:tfl:m:"))).thenReturn(46L);

        assertThat(budget.tryAcquire("tfl", 100, 45, SEOUL)).isFalse();
        verify(ops, never()).increment(startsWith("route:budget:tfl:d:"));
    }

    @Test
    void 월_한도는_공급자_현지_달로_센다() {
        when(ops.increment("route:budget:ors:mo:2026-11")).thenReturn(2L);

        assertThat(budget.tryAcquire("ors", 0, 0, 1, SEOUL)).isFalse();
    }

    @Test
    void 사용량을_읽는다() {
        when(ops.get("route:budget:kakao:d:2026-11-26")).thenReturn("12");
        when(ops.get("route:budget:kakao:mo:2026-11")).thenReturn("340");

        assertThat(budget.usage("kakao", SEOUL)).isEqualTo(new ProviderCallBudget.Usage(12, 340));
    }

    @Test
    void 카운터를_못_읽으면_호출하지_않는다() {
        when(ops.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(budget.tryAcquire("kakao", 10, 0, SEOUL)).isFalse();
    }

    @Test
    void 한도가_없으면_세지_않는다() {
        assertThat(budget.tryAcquire("otp", 0, 0, SEOUL)).isTrue();
        verify(redis, never()).opsForValue();
    }
}
