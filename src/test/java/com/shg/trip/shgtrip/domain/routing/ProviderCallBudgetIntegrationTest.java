package com.shg.trip.shgtrip.domain.routing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** docker compose의 로컬 Valkey에서 카운터 스크립트를 실제로 돌린다. */
@Tag("integration")
class ProviderCallBudgetIntegrationTest {

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private String provider;

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        provider = "it-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        var keys = redis.keys("route:budget:{" + provider + "}:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        factory.destroy();
    }

    @Test
    void 동시에_불러도_한도만큼만_통과하고_모든_카운터에_만료가_있다() throws InterruptedException {
        ProviderCallBudget budget = new ProviderCallBudget(redis, Clock.systemUTC());
        AtomicInteger passed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        for (int i = 0; i < 200; i++) {
            pool.submit(() -> {
                if (budget.tryAcquire(provider, 50, 0, 1000, ZoneId.of("Asia/Seoul"))) passed.incrementAndGet();
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(passed.get()).isEqualTo(50);
        var keys = redis.keys("route:budget:{" + provider + "}:*");
        assertThat(keys).hasSize(2);
        for (String k : keys) {
            assertThat(redis.opsForValue().get(k)).isEqualTo("50");
            assertThat(redis.getExpire(k)).isPositive();
        }
        assertThat(budget.usage(provider, ZoneId.of("Asia/Seoul"))).isEqualTo(new ProviderCallBudget.Usage(0, 50, 50));
    }
}
