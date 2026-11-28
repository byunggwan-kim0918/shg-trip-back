package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.TransportRoute;

import java.math.BigDecimal;

/**
 * 장소 사이 이동 한 번. 요금은 원화다.
 *
 * @param route 경로 엔진에 물어 본 근거. 거리 추정만 했으면 null
 */
public record TransportLeg(String mode, int durationMin, BigDecimal distanceKm, BigDecimal cost, TransportRoute route) {

    public TransportLeg(String mode, int durationMin, BigDecimal distanceKm, BigDecimal cost) {
        this(mode, durationMin, distanceKm, cost, null);
    }

    /** 고른 수단으로는 그 시각에 갈 수 없는 구간. 일정은 이 구간을 피해 다시 짓는다. */
    public boolean unreachable() {
        return route != null && route.unreachable();
    }
}
