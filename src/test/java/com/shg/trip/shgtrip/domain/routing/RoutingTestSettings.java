package com.shg.trip.shgtrip.domain.routing;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

public final class RoutingTestSettings {

    public static final RoutingProperties.Area HOKKAIDO = new RoutingProperties.Area(41.3, 139.3, 45.6, 146.0);
    public static final GeoPoint SAPPORO = new GeoPoint(43.0687, 141.3508);
    public static final GeoPoint OTARU = new GeoPoint(43.1990, 141.0020);

    private RoutingTestSettings() {}

    public static RoutingProperties.ProviderSettings settings(String type, Set<TravelMode> modes, int priority,
                                                              String apiKey, int snapMeters, String timezone) {
        return new RoutingProperties.ProviderSettings(type, true, priority, "http://127.0.0.1:8091", apiKey, modes,
                List.of(HOKKAIDO), null, Duration.ofSeconds(5), 0, 0, Duration.ofHours(1), Duration.ofMinutes(30),
                Duration.ofMinutes(15), false, "test", timezone, snapMeters, false, null, null, null, 0, null, false, null, null);
    }

    public static RoutingProperties.ProviderSettings stopToStop(int snapMeters, Set<String> stopModes) {
        return new RoutingProperties.ProviderSettings("otp", true, 1, "http://127.0.0.1:8100", null,
                Set.of(TravelMode.TRANSIT), List.of(HOKKAIDO), null, Duration.ofSeconds(5), 0, 0, Duration.ofHours(1),
                Duration.ofMinutes(30), Duration.ofMinutes(15), false, "test", "Asia/Tokyo", snapMeters, true, stopModes,
                null, 10, 0, null, false, null, null);
    }

    public static RoutingProperties.ProviderSettings withUnverified(RoutingProperties.ProviderSettings s,
                                                                    java.util.Set<String> agencies) {
        return new RoutingProperties.ProviderSettings(s.type(), s.enabled(), s.priority(), s.baseUrl(), s.apiKey(),
                s.modes(), s.coverage(), s.coverageAreas(), s.timeout(), s.dailyLimit(), s.perMinuteLimit(), s.cacheTtl(),
                s.noConnectionTtl(), s.cacheBucket(), s.officialData(), s.attribution(), s.timezone(),
                s.snapToStopMeters(), s.stopToStop(), s.stopModes(), agencies, s.transferMinutes(), s.monthlyLimit(),
                s.commercialUse(), s.external(), s.verifiers(), s.unverifiedFeeds());
    }

    public static RoutingProperties.ProviderSettings withUnverifiedFeeds(RoutingProperties.ProviderSettings s,
                                                                         java.util.Set<String> feeds) {
        return new RoutingProperties.ProviderSettings(s.type(), s.enabled(), s.priority(), s.baseUrl(), s.apiKey(),
                s.modes(), s.coverage(), s.coverageAreas(), s.timeout(), s.dailyLimit(), s.perMinuteLimit(),
                s.cacheTtl(), s.noConnectionTtl(), s.cacheBucket(), s.officialData(), s.attribution(), s.timezone(),
                s.snapToStopMeters(), s.stopToStop(), s.stopModes(), s.unverifiedAgencies(), s.transferMinutes(),
                s.monthlyLimit(), s.commercialUse(), s.external(), s.verifiers(), feeds);
    }

    public static RoutingProperties.ProviderSettings withVerifiers(RoutingProperties.ProviderSettings s,
                                                                   java.util.Set<String> unverified, List<String> verifiers) {
        return new RoutingProperties.ProviderSettings(s.type(), s.enabled(), s.priority(), s.baseUrl(), s.apiKey(),
                s.modes(), s.coverage(), s.coverageAreas(), s.timeout(), s.dailyLimit(), s.perMinuteLimit(),
                s.cacheTtl(), s.noConnectionTtl(), s.cacheBucket(), s.officialData(), s.attribution(), s.timezone(),
                s.snapToStopMeters(), s.stopToStop(), s.stopModes(), unverified, s.transferMinutes(), s.monthlyLimit(),
                s.commercialUse(), s.external(), verifiers, s.unverifiedFeeds());
    }

    public static RoutingProperties.ProviderSettings external(RoutingProperties.ProviderSettings s,
                                                              boolean commercialUse) {
        return new RoutingProperties.ProviderSettings(s.type(), s.enabled(), s.priority(), s.baseUrl(), s.apiKey(),
                s.modes(), s.coverage(), s.coverageAreas(), s.timeout(), s.dailyLimit(), s.perMinuteLimit(),
                s.cacheTtl(), s.noConnectionTtl(), s.cacheBucket(), s.officialData(), s.attribution(), s.timezone(),
                s.snapToStopMeters(), s.stopToStop(), s.stopModes(), s.unverifiedAgencies(), s.transferMinutes(),
                s.monthlyLimit(), commercialUse, true, s.verifiers(), s.unverifiedFeeds());
    }

    public static RouteQuery query(TravelMode mode) {
        return new RouteQuery(SAPPORO, OTARU, ZonedDateTime.of(2026, 11, 26, 9, 0, 0, 0, ZoneId.of("Asia/Tokyo")), mode);
    }
}
