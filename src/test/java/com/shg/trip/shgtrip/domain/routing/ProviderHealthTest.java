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
    @Test
    void 인증_실패는_한_번에_차단하고_상태로_드러낸다() {
        ProviderHealth h = new ProviderHealth(5, Duration.ofSeconds(60), clock);
        h.record("kakao-car", RouteStatus.FOUND, null);
        h.record("kakao-car", RouteStatus.UNAVAILABLE, "http 401");

        assertThat(h.allows("kakao-car")).isFalse();
        ProviderHealth.Snapshot s = h.snapshot("kakao-car");
        assertThat(s.state()).isEqualTo(ProviderHealth.State.AUTH_FAILED);
        assertThat(s.lastFailure()).isEqualTo(ProviderHealth.FailureKind.AUTH);
        assertThat(s.lastSuccessAt()).isEqualTo(now.get());
        assertThat(s.lastFailureAt()).isEqualTo(now.get());
        assertThat(s.successes()).isEqualTo(1);
        assertThat(s.failures()).isEqualTo(1);
    }

    @Test
    void 실패_종류를_어댑터_사유로_나눈다() {
        assertThat(ProviderHealth.classify("http 403")).isEqualTo(ProviderHealth.FailureKind.AUTH);
        assertThat(ProviderHealth.classify("http 429")).isEqualTo(ProviderHealth.FailureKind.RATE_LIMITED);
        assertThat(ProviderHealth.classify("timeout")).isEqualTo(ProviderHealth.FailureKind.TIMEOUT);
        assertThat(ProviderHealth.classify("call deadline exceeded")).isEqualTo(ProviderHealth.FailureKind.TIMEOUT);
        assertThat(ProviderHealth.classify("http 500")).isEqualTo(ProviderHealth.FailureKind.ERROR);
        assertThat(ProviderHealth.classify("http 4010")).isEqualTo(ProviderHealth.FailureKind.ERROR);
    }

    @Test
    void 연속_실패는_차단_전까지_장애_중으로_보이고_성공하면_정상으로_돌아온다() {
        ProviderHealth h = new ProviderHealth(3, Duration.ofSeconds(60), clock);
        h.record("ors", RouteStatus.UNAVAILABLE, "timeout");
        assertThat(h.snapshot("ors").state()).isEqualTo(ProviderHealth.State.DEGRADED);
        h.record("ors", RouteStatus.UNAVAILABLE, "timeout");
        h.record("ors", RouteStatus.UNAVAILABLE, "timeout");
        assertThat(h.snapshot("ors").state()).isEqualTo(ProviderHealth.State.OPEN);
        assertThat(h.snapshot("ors").openUntil()).isEqualTo(now.get().plusSeconds(60));

        h.record("ors", RouteStatus.FOUND, null);
        assertThat(h.snapshot("ors").state()).isEqualTo(ProviderHealth.State.OK);
        assertThat(h.snapshot("ors").lastFailure()).isEqualTo(ProviderHealth.FailureKind.TIMEOUT);
        assertThat(h.snapshot("unknown").state()).isEqualTo(ProviderHealth.State.UNKNOWN);
    }

    @Test
    void 대체_공급자_전환을_횟수와_함께_남긴다() {
        ProviderHealth h = new ProviderHealth(3, Duration.ofSeconds(60), clock);
        h.recordFallback("kakao-car", "valhalla", "call budget exhausted");
        h.recordFallback("kakao-car", "valhalla", "circuit open");

        ProviderHealth.Fallback f = h.snapshot("valhalla").lastFallback();
        assertThat(f.from()).isEqualTo("kakao-car");
        assertThat(f.reason()).isEqualTo("circuit open");
        assertThat(f.count()).isEqualTo(2);
    }
}
