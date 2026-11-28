package com.shg.trip.shgtrip.domain.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
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
    private InMemory budget;

    /** 카운터 스크립트와 같은 규칙: 모든 창이 남았을 때만 모두 올리고, 처음 올린 카운터에 만료를 둔다. */
    static class InMemory extends ProviderCallBudget {
        final Map<String, Long> counters = new HashMap<>();
        final Map<String, Long> ttls = new HashMap<>();
        RuntimeException failure;

        InMemory(StringRedisTemplate redis, Clock clock) {
            super(redis, clock);
        }

        @Override
        List<Long> take(List<String> keys, String... args) {
            if (failure != null) throw failure;
            for (int i = 0; i < 3; i++) {
                long limit = Long.parseLong(args[i]);
                long n = counters.getOrDefault(keys.get(i), 0L);
                if (limit > 0 && n >= limit) return List.of((long) i + 1, n);
            }
            List<Long> out = new ArrayList<>(List.of(0L));
            for (int i = 0; i < 3; i++) {
                if (Long.parseLong(args[i]) > 0) {
                    long n = counters.merge(keys.get(i), 1L, Long::sum);
                    if (n == 1) ttls.put(keys.get(i), Long.parseLong(args[i + 3]));
                    out.add(n);
                } else {
                    out.add(0L);
                }
            }
            return out;
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        budget = new InMemory(redis, clock);
    }

    @Test
    void 일일_한도는_공급자_현지_날짜로_세고_처음_올릴_때_만료를_둔다() {
        assertThat(budget.tryAcquire("kakao", 10, 0, SEOUL)).isTrue();
        assertThat(budget.counters).containsEntry("route:budget:{kakao}:d:2026-11-26", 1L);
        assertThat(budget.ttls).containsEntry("route:budget:{kakao}:d:2026-11-26", 2 * 86400L);
    }

    @Test
    void 한도를_넘으면_거절하고_카운터를_더_올리지_않는다() {
        for (int i = 0; i < 10; i++) assertThat(budget.tryAcquire("kakao", 10, 0, SEOUL)).isTrue();

        assertThat(budget.tryAcquire("kakao", 10, 0, SEOUL)).isFalse();
        assertThat(budget.counters).containsEntry("route:budget:{kakao}:d:2026-11-26", 10L);
        assertThat(budget.lastEvent("kakao")).get().extracting(ProviderCallBudget.Event::kind).isEqualTo("exhausted");
    }

    @Test
    void 분_한도에_막히면_일_월_카운터도_올리지_않는다() {
        budget.counters.put("route:budget:{tfl}:m:" + clock.millis() / 60_000, 45L);

        assertThat(budget.tryAcquire("tfl", 100, 45, 1000, SEOUL)).isFalse();
        assertThat(budget.counters).doesNotContainKey("route:budget:{tfl}:d:2026-11-26");
        assertThat(budget.counters).doesNotContainKey("route:budget:{tfl}:mo:2026-11");
    }

    @Test
    void 월_한도는_공급자_현지_달로_센다() {
        assertThat(budget.tryAcquire("kakao", 0, 0, 1, SEOUL)).isTrue();
        assertThat(budget.tryAcquire("kakao", 0, 0, 1, SEOUL)).isFalse();
        assertThat(budget.counters).containsEntry("route:budget:{kakao}:mo:2026-11", 1L);
    }

    @Test
    void 무료_한도의_80퍼센트에_닿는_호출에서_한_번_경고한다() {
        for (int i = 0; i < 7; i++) budget.tryAcquire("kakao", 10, 0, SEOUL);
        assertThat(budget.lastEvent("kakao")).isEmpty();

        budget.tryAcquire("kakao", 10, 0, SEOUL);
        assertThat(budget.lastEvent("kakao")).get().satisfies(e -> {
            assertThat(e.kind()).isEqualTo("warning");
            assertThat(e.window()).isEqualTo("day");
            assertThat(e.used()).isEqualTo(8);
        });
        assertThat(ProviderCallBudget.warnAt(9000)).isEqualTo(7200);
        assertThat(ProviderCallBudget.warnAt(1)).isEqualTo(1);
    }

    @Test
    void 사용량을_읽는다() {
        when(ops.multiGet(anyList())).thenReturn(java.util.Arrays.asList("3", "12", "340"));

        assertThat(budget.usage("kakao", SEOUL)).isEqualTo(new ProviderCallBudget.Usage(3, 12, 340));
    }

    @Test
    void 카운터를_못_읽으면_호출하지_않는다() {
        budget.failure = new RedisConnectionFailureException("down");

        assertThat(budget.tryAcquire("kakao", 10, 0, SEOUL)).isFalse();
    }

    @Test
    void 한도가_없으면_세지_않는다() {
        assertThat(budget.tryAcquire("otp", 0, 0, SEOUL)).isTrue();
        assertThat(budget.counters).isEmpty();
        verify(redis, never()).opsForValue();
    }
}
