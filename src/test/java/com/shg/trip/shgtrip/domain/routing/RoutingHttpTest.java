package com.shg.trip.shgtrip.domain.routing;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingHttpTest {

    @Test
    void 원격은_https만_로컬만_http를_허용한다() {
        RoutingHttp.requireSafeBaseUrl("a", "https://api.tfl.gov.uk");
        RoutingHttp.requireSafeBaseUrl("a", "http://127.0.0.1:8091");
        assertThatThrownBy(() -> RoutingHttp.requireSafeBaseUrl("a", "http://example.com"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RoutingHttp.requireSafeBaseUrl("a", "https://u:p@example.com"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RoutingHttp.requireSafeBaseUrl("a", "https://example.com?k=v"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 쿼리_값을_인코딩한다() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("serviceKey", "a+b/c=");
        q.put("origin", "126.97,37.55");

        assertThat(RoutingHttp.uri("https://h/", "/p", q).toString())
                .isEqualTo("https://h/p?serviceKey=a%2Bb%2Fc%3D&origin=126.97%2C37.55");
    }

    @Test
    void 출발지와_도착지가_한_영역에_모두_들고_수단이_맞아야_다룬다() {
        var s = RoutingTestSettings.settings("otp", java.util.Set.of(TravelMode.TRANSIT), 1, null, 0, null);

        AdminAreas none = AdminAreas.empty();
        assertThat(s.covers(RoutingTestSettings.query(TravelMode.TRANSIT), none)).isTrue();
        assertThat(s.covers(RoutingTestSettings.query(TravelMode.CAR), none)).isFalse();
        assertThat(s.covers(new RouteQuery(RoutingTestSettings.SAPPORO, new GeoPoint(35.68, 139.76),
                RoutingTestSettings.query(TravelMode.TRANSIT).departAt(), TravelMode.TRANSIT), none)).isFalse();
    }
}
