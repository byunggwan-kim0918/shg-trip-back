package com.shg.trip.shgtrip.domain.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.routing.provider.KakaoCarRouteProvider;
import com.shg.trip.shgtrip.domain.routing.provider.OrsRouteProvider;
import com.shg.trip.shgtrip.domain.routing.provider.OtpRouteProvider;
import com.shg.trip.shgtrip.domain.routing.provider.TflRouteProvider;
import com.shg.trip.shgtrip.domain.routing.provider.ValhallaRouteProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 설정에 적힌 공급자를 어댑터로 만든다. 새 지역 OTP는 설정 항목만 더하면 된다. */
@Component
public class RouteProviderRegistry {

    private final List<RouteProvider> providers;
    private final AdminAreas areas;

    @Autowired
    public RouteProviderRegistry(RoutingProperties properties, RoutingHttp http, ObjectMapper mapper, Clock clock) {
        TaxiFareTable taxiFares = taxiFares(properties);
        this.areas = adminAreas(properties, mapper);
        this.providers = properties.providers().entrySet().stream()
                .filter(e -> e.getValue().enabled())
                .filter(e -> e.getValue().commercialUse() || properties.allowNonCommercial())
                .map(e -> create(e.getKey(), e.getValue(), http, mapper, clock, taxiFares, areas))
                .sorted(Comparator.comparingInt((RouteProvider p) -> p.settings().priority()).thenComparing(RouteProvider::id))
                .toList();
    }

    private static AdminAreas adminAreas(RoutingProperties properties, ObjectMapper mapper) {
        if (properties.adminAreasFile() == null || properties.adminAreasFile().isBlank()) return AdminAreas.empty();
        try (var in = new ClassPathResource(properties.adminAreasFile()).getInputStream()) {
            return AdminAreas.read(in, mapper, properties.areaBufferKm());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("행정구역 경계를 읽지 못했다: " + properties.adminAreasFile(), e);
        }
    }

    private static TaxiFareTable taxiFares(RoutingProperties properties) {
        if (properties.taxiFaresFile() == null || properties.taxiFaresFile().isBlank()) return TaxiFareTable.empty();
        try (var in = new ClassPathResource(properties.taxiFaresFile()).getInputStream()) {
            return new TaxiFareTable(TaxiFareTable.readCsv(in), properties.taxiAnchorMaxKm());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("택시 요금표를 읽지 못했다: " + properties.taxiFaresFile(), e);
        }
    }

    RouteProviderRegistry(List<RouteProvider> providers) {
        this.areas = AdminAreas.empty();
        this.providers = providers.stream()
                .sorted(Comparator.comparingInt((RouteProvider p) -> p.settings().priority()).thenComparing(RouteProvider::id))
                .toList();
    }

    public List<RouteProvider> providersFor(RouteQuery q) {
        return providers.stream().filter(p -> p.settings().covers(q, areas)).toList();
    }

    private static RouteProvider create(String id, RoutingProperties.ProviderSettings s, RoutingHttp http,
                                        ObjectMapper mapper, Clock clock, TaxiFareTable taxiFares,
                                        AdminAreas areas) {
        RoutingHttp.requireSafeBaseUrl(id, s.baseUrl());
        if ((s.coverage().isEmpty() && s.coverageAreas().isEmpty()) || s.modes().isEmpty()) {
            throw new IllegalStateException(id + ": coverage 또는 coverage-areas와 modes를 적어야 한다");
        }
        s.coverageAreas().stream().filter(code -> !areas.known(code)).findFirst().ifPresent(code -> {
            throw new IllegalStateException(id + ": 경계 리소스에 없는 구역 코드 " + code);
        });
        if (s.dailyLimit() > 0 && (s.timezone() == null || s.timezone().isBlank())) {
            throw new IllegalStateException(id + ": 일일 한도를 공급자 현지 날짜로 세도록 timezone을 적어야 한다");
        }
        Map<String, java.util.function.Supplier<RouteProvider>> factories = Map.of(
                "otp", () -> new OtpRouteProvider(id, s, http, mapper, clock),
                "tfl", () -> new TflRouteProvider(id, s, http, mapper, clock),
                "kakao-car", () -> new KakaoCarRouteProvider(id, s, http, mapper, clock),
                "valhalla", () -> new ValhallaRouteProvider(id, s, http, mapper, clock, taxiFares),
                "ors", () -> new OrsRouteProvider(id, s, http, mapper, clock));
        var factory = factories.get(s.type());
        if (factory == null) throw new IllegalStateException(id + ": 모르는 공급자 종류 " + s.type());
        return factory.get();
    }
}
