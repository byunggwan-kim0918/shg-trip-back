package com.shg.trip.shgtrip.domain.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 application-routing.yml의 영역이 나라끼리 겹치지 않는지 본다. 겹치면 다른 나라 서버가 먼저 불린다. */
class RoutingConfigCoverageTest {

    private static Map<String, RoutingProperties.ProviderSettings> providers() throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader().load("routing", new ClassPathResource("application-routing.yml"))
                .forEach(env.getPropertySources()::addLast);
        return Binder.get(env).bind("routing", RoutingProperties.class).get().providers();
    }

    private static AdminAreas areas() throws IOException {
        try (var in = new ClassPathResource("routing/admin-areas.json").getInputStream()) {
            return AdminAreas.read(in, new ObjectMapper(), 3);
        }
    }

    private static List<String> transitProvidersFor(GeoPoint a, GeoPoint b) throws IOException {
        RouteQuery q = new RouteQuery(a, b, ZonedDateTime.of(2026, 11, 26, 9, 0, 0, 0, ZoneId.of("Asia/Seoul")),
                TravelMode.TRANSIT);
        AdminAreas areas = areas();
        return providers().entrySet().stream().filter(e -> e.getValue().covers(q, areas)).map(Map.Entry::getKey)
                .sorted().toList();
    }

    @Test
    void 매립지_공항과_섬도_자기_구역에_든다() throws IOException {
        AdminAreas areas = areas();

        assertThat(areas.contains("JP-27", new GeoPoint(34.4347, 135.2440))).isTrue();
        assertThat(areas.contains("JP-13", new GeoPoint(35.5494, 139.7798))).isTrue();
        assertThat(areas.contains("JP-42", new GeoPoint(34.2027, 129.2878))).isTrue();
        assertThat(areas.contains("KR-49", new GeoPoint(33.5066, 126.4929))).isTrue();
        assertThat(areas.contains("KR", new GeoPoint(33.5902, 130.4207))).isFalse();
        assertThat(areas.contains("JP", new GeoPoint(35.1151, 129.0422))).isFalse();
    }

    @Test
    void 작은_섬은_자기_구역에_들고_먼_바다는_어느_나라에도_들지_않는다() throws IOException {
        AdminAreas areas = areas();

        assertThat(areas.contains("KR-49", new GeoPoint(33.5064, 126.9530))).as("우도").isTrue();
        assertThat(areas.contains("KR-49", new GeoPoint(33.9626, 126.2985))).as("추자도").isTrue();
        assertThat(areas.contains("KR-45", new GeoPoint(35.8100, 126.4100))).as("선유도").isTrue();
        assertThat(areas.contains("KR-28", new GeoPoint(37.9600, 124.6700))).as("백령도").isTrue();
        assertThat(areas.contains("JP-37", new GeoPoint(34.4600, 133.9950))).as("나오시마").isTrue();
        assertThat(areas.contains("JP-47", new GeoPoint(24.0600, 123.7800))).as("하테루마").isTrue();
        assertThat(areas.contains("JP-47", new GeoPoint(26.2124, 127.6792))).as("나하").isTrue();
        assertThat(areas.contains("JP-13", new GeoPoint(33.1100, 139.7900))).as("하치조지마").isTrue();
        assertThat(areas.contains("KR", new GeoPoint(34.5, 129.0))).as("대한해협").isFalse();
        assertThat(areas.contains("JP", new GeoPoint(34.5, 129.0))).as("대한해협").isFalse();
        assertThat(areas.contains("KR", new GeoPoint(34.0, 125.5))).as("서해 먼 바다").isFalse();
    }

    @Test
    void 상업_이용이_안_되는_공급자는_설정에_표시돼_있다() throws IOException {
        assertThat(providers().get("ors").commercialUse()).isFalse();
        assertThat(providers().entrySet().stream().filter(e -> e.getValue().external()).map(Map.Entry::getKey))
                .containsExactlyInAnyOrder("tfl", "kakao-car", "ors");
    }

    @Test
    void 새_나라를_더해도_기존_나라_판정은_그대로다() throws IOException {
        assertThat(transitProvidersFor(new GeoPoint(1.3040, 103.8318), new GeoPoint(1.2834, 103.8607)))
                .containsExactly("otp-sg");
        assertThat(transitProvidersFor(new GeoPoint(1.3592, 103.9894), new GeoPoint(1.2931, 103.8520)))
                .containsExactly("otp-sg");
        assertThat(transitProvidersFor(new GeoPoint(37.5547, 126.9707), new GeoPoint(37.4979, 127.0276)))
                .containsExactly("otp-kr");
        assertThat(transitProvidersFor(new GeoPoint(35.6896, 139.7006), new GeoPoint(35.6812, 139.7671)))
                .containsExactlyInAnyOrder("otp-jp-kanto", "otp-jp-rail");
    }

    @Test
    void 경계_리소스에_없는_구역_코드는_기동을_막는다() throws IOException {
        var s = providers().get("otp-sg");
        var broken = new RoutingProperties.ProviderSettings(s.type(), true, s.priority(), s.baseUrl(), null, s.modes(),
                List.of(), List.of("XX-99"), s.timeout(), 0, 0, null, null, null, false, "t", "UTC", 0, false, null,
                null, null, 0, null, false, null, null, null);
        RoutingProperties props = new RoutingProperties(true, 4, Map.of("broken", broken), null, null, null,
                "routing/admin-areas.json", 3.0, false, null, null, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RouteProviderRegistry(props, null,
                        new ObjectMapper(), java.time.Clock.systemUTC()))
                .hasMessageContaining("XX-99");
    }

    @Test
    void 제주는_한국_공급자만_다룬다() throws IOException {
        assertThat(transitProvidersFor(new GeoPoint(33.5066, 126.4929), new GeoPoint(33.4581, 126.9425)))
                .containsExactly("otp-kr");
    }

    @Test
    void 후쿠오카는_일본_공급자만_다룬다() throws IOException {
        assertThat(transitProvidersFor(new GeoPoint(33.5902, 130.4207), new GeoPoint(33.5215, 130.5348)))
                .containsExactlyInAnyOrder("otp-jp-kyushu", "otp-jp-rail");
    }

    @Test
    void 부산과_서울은_한국_공급자만_다룬다() throws IOException {
        assertThat(transitProvidersFor(new GeoPoint(35.1151, 129.0422), new GeoPoint(37.5547, 126.9707)))
                .containsExactly("otp-kr");
    }

    @Test
    void 지역을_넘는_일본_철도는_전국_철도_그래프가_다룬다() throws IOException {
        assertThat(transitProvidersFor(new GeoPoint(35.6812, 139.7671), new GeoPoint(34.9858, 135.7588)))
                .containsExactly("otp-jp-rail");
        assertThat(transitProvidersFor(new GeoPoint(36.1408, 137.2516), new GeoPoint(34.8584, 136.8054)))
                .containsExactlyInAnyOrder("otp-jp-chubu", "otp-jp-rail");
    }
}
