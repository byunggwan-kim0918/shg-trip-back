package com.shg.trip.shgtrip.domain.routing;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * 경로 조회 한 건.
 *
 * @param departAt 출발 희망 시각. 시간대가 있어야 공급자 현지 시각으로 바꿀 수 있다
 */
public record RouteQuery(GeoPoint origin, GeoPoint destination, ZonedDateTime departAt, TravelMode mode) {

    public RouteQuery {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(departAt, "departAt");
        Objects.requireNonNull(mode, "mode");
    }

    public RouteQuery snapped(int decimals) {
        return new RouteQuery(origin.snapped(decimals), destination.snapped(decimals), departAt, mode);
    }
}
