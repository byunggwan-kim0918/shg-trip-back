package com.shg.trip.shgtrip.domain.routing;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 출발지에서 목적지까지 경로 하나.
 *
 * @param transfers 대중교통 갈아타기 횟수. 대중교통 구간이 없으면 0
 * @param fares     공급자가 준 요금만 담는다. 비어 있으면 요금을 모른다
 * @param endpointGapMeters 요청 좌표와 경로 끝점 사이 직선거리 합. 끝점을 정류장으로 옮겼을 때만 0보다 크고,
 *                          그 거리를 오가는 시간은 소요 시간에 들어 있지 않다
 */
public record RouteItinerary(
        OffsetDateTime departure,
        OffsetDateTime arrival,
        int durationSeconds,
        int transfers,
        List<RouteLeg> legs,
        List<Fare> fares,
        RouteSource source,
        int endpointGapMeters
) {

    public RouteItinerary {
        legs = List.copyOf(legs);
        fares = List.copyOf(fares);
    }

    public RouteItinerary(OffsetDateTime departure, OffsetDateTime arrival, int durationSeconds, int transfers,
                          List<RouteLeg> legs, List<Fare> fares, RouteSource source) {
        this(departure, arrival, durationSeconds, transfers, legs, fares, source, 0);
    }

    public RouteItinerary withEndpointGap(int meters) {
        return new RouteItinerary(departure, arrival, durationSeconds, transfers, legs, fares, source, meters);
    }

    public int durationMinutes() {
        return (int) Math.ceil(durationSeconds / 60.0);
    }

    public boolean usesTransit() {
        return legs.stream().anyMatch(l -> l.mode().isTransit());
    }

    public static int transfersOf(List<RouteLeg> legs) {
        long rides = legs.stream().filter(l -> l.mode().isTransit()).count();
        return (int) Math.max(0, rides - 1);
    }
}
