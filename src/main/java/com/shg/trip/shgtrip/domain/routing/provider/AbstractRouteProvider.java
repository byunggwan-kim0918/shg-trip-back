package com.shg.trip.shgtrip.domain.routing.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.routing.RouteBasis;
import com.shg.trip.shgtrip.domain.routing.RouteProvider;
import com.shg.trip.shgtrip.domain.routing.RouteQuery;
import com.shg.trip.shgtrip.domain.routing.RouteResult;
import com.shg.trip.shgtrip.domain.routing.RouteSource;
import com.shg.trip.shgtrip.domain.routing.RouteStatus;
import com.shg.trip.shgtrip.domain.routing.RoutingHttp;
import com.shg.trip.shgtrip.domain.routing.RoutingProperties.ProviderSettings;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/** 어댑터 공통: 통신 실패와 응답 해석 실패를 UNAVAILABLE로 바꾼다. */
abstract class AbstractRouteProvider implements RouteProvider {

    protected final String id;
    protected final ProviderSettings settings;
    protected final RoutingHttp http;
    protected final ObjectMapper mapper;
    protected final Clock clock;

    AbstractRouteProvider(String id, ProviderSettings settings, RoutingHttp http, ObjectMapper mapper, Clock clock) {
        this.id = id;
        this.settings = settings;
        this.http = http;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public ProviderSettings settings() {
        return settings;
    }

    @Override
    public final RouteResult route(RouteQuery query) {
        try {
            return call(query);
        } catch (RoutingHttp.CallFailed e) {
            return unavailable(e.getMessage());
        } catch (IOException | RuntimeException e) {
            return unavailable("unreadable response " + e.getClass().getSimpleName());
        }
    }

    protected abstract RouteResult call(RouteQuery query) throws IOException;

    protected RouteResult unavailable(String reason) {
        return RouteResult.of(RouteStatus.UNAVAILABLE, id, reason);
    }

    protected RouteResult status(RouteStatus status, String reason) {
        return RouteResult.of(status, id, reason);
    }

    protected RouteSource source(RouteBasis basis) {
        return source(basis, null);
    }

    protected RouteSource source(RouteBasis basis, RouteSource.ScheduleValidity validity) {
        Instant now = clock.instant();
        return new RouteSource(id, basis, settings.officialData(), settings.attribution(), now,
                now.plus(settings.cacheTtl()), validity);
    }

    protected ZoneId zone(RouteQuery q) {
        return settings.timezone() == null || settings.timezone().isBlank() ? q.departAt().getZone()
                : ZoneId.of(settings.timezone());
    }

    protected JsonNode json(String body) throws IOException {
        return mapper.readTree(body);
    }
}
