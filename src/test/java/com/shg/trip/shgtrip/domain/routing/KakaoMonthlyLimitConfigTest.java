package com.shg.trip.shgtrip.domain.routing;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ROUTING_KAKAO_MONTHLY_LIMIT가 없거나 잘못 적혔을 때 카카오 호출 한도가 어떻게 잡히는지. */
class KakaoMonthlyLimitConfigTest {

    private static RoutingProperties.ProviderSettings kakao(Map<String, Object> env) throws IOException {
        StandardEnvironment e = new StandardEnvironment();
        e.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        e.getPropertySources().addFirst(new MapPropertySource("test-env", env));
        new YamlPropertySourceLoader().load("routing", new ClassPathResource("application-routing.yml"))
                .forEach(e.getPropertySources()::addLast);
        return Binder.get(e).bind("routing", RoutingProperties.class).get().providers().get("kakao-car");
    }

    @Test
    void 설정하지_않으면_월_한도는_세지_않고_일일_한도는_그대로_막는다() throws IOException {
        var s = kakao(Map.of());
        assertThat(s.monthlyLimit()).isZero();
        assertThat(s.dailyLimit()).isEqualTo(9000);
    }

    @Test
    void 콘솔_쿼터_값을_넣으면_월_한도로_센다() throws IOException {
        assertThat(kakao(Map.of("ROUTING_KAKAO_MONTHLY_LIMIT", "300000")).monthlyLimit()).isEqualTo(300000);
    }

    @Test
    void 숫자가_아니거나_음수면_기동하지_않는다() {
        assertThatThrownBy(() -> kakao(Map.of("ROUTING_KAKAO_MONTHLY_LIMIT", "300,000"))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> kakao(Map.of("ROUTING_KAKAO_MONTHLY_LIMIT", "abc"))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> kakao(Map.of("ROUTING_KAKAO_MONTHLY_LIMIT", "-1"))).isInstanceOf(BindException.class)
                .hasRootCauseMessage("호출 한도는 0 이상이어야 한다: daily=9000 perMinute=120 monthly=-1");
    }

    @Test
    void 빈_값이면_설정하지_않은_것과_같다() throws IOException {
        assertThat(kakao(Map.of("ROUTING_KAKAO_MONTHLY_LIMIT", "")).monthlyLimit()).isZero();
    }
}
