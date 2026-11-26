package com.shg.trip.shgtrip.domain.place.enrich;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaceEnrichBudgetTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final Map<String, Long> counters = new HashMap<>();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-26T03:15:00Z"), ZoneOffset.UTC);

    private PlaceEnrichBudget budget(long perHour, long perDay) {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenAnswer(inv -> counters.merge(inv.getArgument(0), 1L, Long::sum));
        when(ops.decrement(anyString())).thenAnswer(inv -> counters.merge(inv.getArgument(0), -1L, Long::sum));
        return new PlaceEnrichBudget(redis, clock, perHour, perDay);
    }

    @Test
    @DisplayName("시간당 상한까지만 호출을 내주고, 넘은 차례는 세지 않는다")
    void hourlyCap() {
        PlaceEnrichBudget budget = budget(2, 100);

        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isFalse();

        assertThat(counters.get("place-enrich:calls:h:2026112603")).isEqualTo(2L);
        assertThat(counters.get("place-enrich:calls:d:20261126")).isEqualTo(2L);
        verify(redis).expire("place-enrich:calls:h:2026112603", Duration.ofHours(2));
    }

    @Test
    @DisplayName("일일 상한이 시간 상한보다 먼저 닿으면 그것으로 막는다")
    void dailyCap() {
        PlaceEnrichBudget budget = budget(100, 1);

        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("카운터를 읽지 못하면 상한 없이 부르지 않고 미룬다")
    void counterFailureDenies() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenThrow(new IllegalStateException("valkey down"));

        assertThat(new PlaceEnrichBudget(redis, clock, 60, 300).tryAcquire()).isFalse();
    }
}
