package com.shg.trip.shgtrip.domain.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.routing.timetable.TagoTrainTimetable;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 공급자별 역할·연결 여부·인증정보 설정 여부·사용량·한도·장애 상태(/actuator/routing).
 * 운영 프로필은 health만 노출하므로 이 엔드포인트는 노출 목록에 넣은 환경에서만 열린다. 인증정보 값은 내보내지 않는다.
 */
@Component
@Endpoint(id = "routing")
@ConditionalOnProperty(prefix = "routing", name = "enabled", havingValue = "true")
public class RoutingStatusEndpoint {

    private final RouteProviderRegistry registry;
    private final RouteEngine engine;
    private final ProviderCallBudget budget;
    private final RoutingProperties properties;
    private final ObjectMapper mapper;
    private volatile ProviderInventory inventory;

    public RoutingStatusEndpoint(RouteProviderRegistry registry, RouteEngine engine, ProviderCallBudget budget,
                                 RoutingProperties properties, ObjectMapper mapper) {
        this.registry = registry;
        this.engine = engine;
        this.budget = budget;
        this.properties = properties;
        this.mapper = mapper;
    }

    private ProviderInventory inventory() {
        if (inventory == null) inventory = ProviderInventory.load(mapper);
        return inventory;
    }

    @ReadOperation
    public Map<String, Object> status() {
        ProviderInventory inventory = inventory();
        List<Map<String, Object>> runtime = new ArrayList<>();
        List<String> listed = new ArrayList<>();
        for (RouteProvider p : registry.providers()) {
            RoutingProperties.ProviderSettings s = p.settings();
            var entry = inventory.forRuntime(p.id());
            entry.ifPresent(e -> listed.add(e.id()));
            runtime.add(row(p.id(), p.id(), s.type(), entry.orElse(null), s.external(), s.apiKey(), s.perMinuteLimit(),
                    s.dailyLimit(), s.monthlyLimit(), s.zone()));
        }
        RoutingProperties.TagoSettings tago = properties.tago();
        if (tago != null && tago.enabled()) {
            var entry = inventory.forRuntime("tago");
            entry.ifPresent(e -> listed.add(e.id()));
            // TAGO 호출은 열차 시간표 조회 id로 센다.
            runtime.add(row("tago", TagoTrainTimetable.PROVIDER_ID, "verifier", entry.orElse(null), true,
                    tago.serviceKey(), 0, tago.dailyLimit(), 0,
                    java.time.ZoneId.of("Asia/Seoul")));
        }
        List<Map<String, Object>> others = inventory.providers().stream().filter(e -> !listed.contains(e.id()))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", e.id());
                    m.put("role", e.role());
                    m.put("integration", e.integration());
                    m.put("kind", e.kind());
                    m.put("keysConfigured", keysConfigured(e));
                    return m;
                }).toList();
        return Map.of("runtime", runtime, "notRunning", others);
    }

    private Map<String, Object> row(String id, String budgetId, String type, ProviderInventory.Entry entry,
                                    boolean external, String key,
                                    int perMinute, int daily, int monthly, java.time.ZoneId zone) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type);
        m.put("inventoryId", entry == null ? null : entry.id());
        m.put("role", entry == null ? null : entry.role());
        m.put("external", external);
        m.put("keyConfigured", key != null && !key.isBlank());
        m.put("limits", Map.of("perMinute", perMinute, "daily", daily, "monthly", monthly));
        if (perMinute > 0 || daily > 0 || monthly > 0) {
            ProviderCallBudget.Usage u = budget.usage(budgetId, zone);
            m.put("usage", u);
            m.put("dailyRatio", ratio(u.today(), daily));
            m.put("monthlyRatio", ratio(u.thisMonth(), monthly));
            m.put("warning", ratio(u.today(), daily) >= ProviderCallBudget.WARN_RATIO
                    || ratio(u.thisMonth(), monthly) >= ProviderCallBudget.WARN_RATIO);
            budget.lastEvent(budgetId).ifPresent(e -> m.put("lastBudgetEvent", e));
        }
        m.put("health", engine.health(id));
        return m;
    }

    private static double ratio(long used, int limit) {
        return limit <= 0 || used < 0 ? 0 : (double) used / limit;
    }

    private static Map<String, Boolean> keysConfigured(ProviderInventory.Entry e) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String k : e.keyEnv()) {
            String v = System.getenv(k);
            out.put(k, v != null && !v.isBlank());
        }
        return out;
    }
}
