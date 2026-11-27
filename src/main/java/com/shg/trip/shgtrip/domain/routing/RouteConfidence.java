package com.shg.trip.shgtrip.domain.routing;

/**
 * 일정 생성기에 넘기는 신뢰도. 공급자 근거({@link RouteBasis})와 조회 상태를 다섯 단계로 줄인다.
 * 원래 근거·상태·사유는 {@link RouteResult}에 그대로 남아 있다.
 */
public enum RouteConfidence {
    /** 실시간 가격·좌석 재고까지 확인했다. 지금 공급자 중에는 없다. */
    LIVE_QUOTE,
    /** 운영기관 공식 운행 시각이다. */
    OFFICIAL_SCHEDULE,
    /** GTFS 시간표로 계산한 운행 시각이다. 운영기관 데이터가 아니면 RouteSource.officialData가 false다. */
    GTFS_SCHEDULE,
    /** 시간표가 아니라 도로·보행망 또는 검증하지 못한 시간표로 낸 시간이다. 그 시각 그 편을 확정하지 않는다. */
    ESTIMATED,
    /** 확인한 경로가 없다. 이동 불가능이라는 뜻이 아니며 원인은 RouteResult.status·reason이다. */
    UNAVAILABLE;

    public static RouteConfidence of(RouteResult result) {
        if (result.status() != RouteStatus.FOUND) return UNAVAILABLE;
        RouteItinerary best = result.earliestArrival().orElseThrow();
        return switch (best.source().basis()) {
            case REALTIME -> best.source().officialData() ? OFFICIAL_SCHEDULE : GTFS_SCHEDULE;
            case OFFICIAL_SCHEDULE -> OFFICIAL_SCHEDULE;
            case GTFS_SCHEDULE -> GTFS_SCHEDULE;
            case ROAD_NETWORK, UNVERIFIED_SCHEDULE, ESTIMATED -> ESTIMATED;
        };
    }
}
