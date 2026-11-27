package com.shg.trip.shgtrip.domain.routing;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderHealthTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-26T00:00:00Z"));
    private final Clock clock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    };

    @Test
    void 차단_시간이_지나면_한_요청만_다시_묻는다() {
        ProviderHealth h = new ProviderHealth(2, Duration.ofSeconds(60), clock);
        h.record("a", RouteStatus.UNAVAILABLE);
        h.record("a", RouteStatus.UNAVAILABLE);
        assertThat(h.allows("a")).isFalse();

        now.set(now.get().plusSeconds(61));

        assertThat(h.allows("a")).isTrue();
        assertThat(h.allows("a")).isFalse();
    }

    @Test
    void 다시_물은_요청이_성공하면_차단을_푼다() {
        ProviderHealth h = new ProviderHealth(1, Duration.ofSeconds(60), clock);
        h.record("a", RouteStatus.UNAVAILABLE);
        now.set(now.get().plusSeconds(61));
        assertThat(h.allows("a")).isTrue();

        h.record("a", RouteStatus.FOUND);

        assertThat(h.allows("a")).isTrue();
        assertThat(h.allows("a")).isTrue();
    }
}
