package com.shg.trip.shgtrip.domain.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoutingStatusEndpointTest {

    @Test
    @SuppressWarnings("unchecked")
    void 공급자별_역할_사용량_상태를_내고_인증정보_값은_내지_않는다() {
        var kakao = RoutingTestSettings.external(RoutingTestSettings.settings("kakao-car", Set.of(TravelMode.CAR), 1,
                "secret-key-value", 0, "Asia/Seoul"), true);
        RouteProvider provider = mock(RouteProvider.class);
        when(provider.id()).thenReturn("kakao-car");
        when(provider.settings()).thenReturn(kakao);
        RouteProviderRegistry registry = new RouteProviderRegistry(List.of(provider));
        ProviderCallBudget budget = mock(ProviderCallBudget.class);
        when(budget.usage(eq("tago-train"), any())).thenReturn(new ProviderCallBudget.Usage(0, 8000, 0));
        when(budget.lastEvent(any())).thenReturn(java.util.Optional.empty());
        RoutingProperties props = new RoutingProperties(true, 4, Map.of(),
                new RoutingProperties.TagoSettings(true, "https://apis.data.go.kr", "tago-secret", Duration.ofSeconds(5), 9000),
                null, null, null, null, false, null, null, null);
        RouteEngine engine = new RouteEngine(registry, mock(RouteCache.class), budget, props);

        Map<String, Object> status = new RoutingStatusEndpoint(registry, engine, budget, props, new ObjectMapper()).status();

        List<Map<String, Object>> runtime = (List<Map<String, Object>>) status.get("runtime");
        assertThat(runtime).extracting(m -> m.get("id")).containsExactly("kakao-car", "tago");
        assertThat(runtime.get(0)).containsEntry("role", ProviderInventory.Role.CORE).containsEntry("keyConfigured", true);
        assertThat(runtime.get(1)).containsEntry("role", ProviderInventory.Role.VALIDATION).containsEntry("warning", true);
        assertThat((double) runtime.get(1).get("dailyRatio")).isEqualTo(8000.0 / 9000);
        assertThat(status.toString()).doesNotContain("secret-key-value").doesNotContain("tago-secret");
        List<Map<String, Object>> others = (List<Map<String, Object>>) status.get("notRunning");
        assertThat(others).extracting(m -> m.get("id")).contains("tmap", "lta", "prim");
    }
}
