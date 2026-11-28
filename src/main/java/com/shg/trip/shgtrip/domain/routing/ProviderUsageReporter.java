package com.shg.trip.shgtrip.domain.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 한도가 있는 공급자 사용량을 매시 남긴다. 한도 경고(route budget warning)와 함께 로그 알림의 근거가 된다. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "routing", name = "enabled", havingValue = "true")
public class ProviderUsageReporter {

    private final RouteProviderRegistry registry;
    private final ProviderCallBudget budget;
    private final RoutingProperties properties;

    public ProviderUsageReporter(RouteProviderRegistry registry, ProviderCallBudget budget, RoutingProperties properties) {
        this.registry = registry;
        this.budget = budget;
        this.properties = properties;
    }

    @Scheduled(cron = "0 5 * * * *", zone = "UTC")
    public void report() {
        for (RouteProvider p : registry.providers()) {
            RoutingProperties.ProviderSettings s = p.settings();
            if (s.dailyLimit() <= 0 && s.monthlyLimit() <= 0) continue;
            ProviderCallBudget.Usage u = budget.usage(p.id(), s.zone());
            log.info("route budget usage provider={} day={}/{} month={}/{}", p.id(), u.today(), s.dailyLimit(),
                    u.thisMonth(), s.monthlyLimit());
        }
        RoutingProperties.TagoSettings tago = properties.tago();
        if (tago != null && tago.enabled() && tago.dailyLimit() > 0) {
            String id = com.shg.trip.shgtrip.domain.routing.timetable.TagoTrainTimetable.PROVIDER_ID;
            ProviderCallBudget.Usage u = budget.usage(id, java.time.ZoneId.of("Asia/Seoul"));
            log.info("route budget usage provider={} day={}/{}", id, u.today(), tago.dailyLimit());
        }
    }
}
