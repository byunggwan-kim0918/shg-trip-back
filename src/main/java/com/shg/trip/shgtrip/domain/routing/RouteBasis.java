package com.shg.trip.shgtrip.domain.routing;

/**
 * 경로 시각이 무엇에 근거하는지. 위에서 아래로 믿을 만하다.
 * 화면과 검사는 이 값으로 확정 시각과 추정 시각을 구분한다.
 */
public enum RouteBasis {
    /** 시간표에 실시간 예측이 반영된 시각. 관측 시각이 지나면 시간표 값으로 본다. */
    REALTIME,
    /** 운영기관 API가 준 시간표. */
    OFFICIAL_SCHEDULE,
    /** 공개 GTFS 시간표로 경로 엔진이 계산한 시각. */
    GTFS_SCHEDULE,
    /** 시간표 없이 도로·보행망으로 계산한 시간. */
    ROAD_NETWORK,
    /** 운행일·배차를 검증하지 못한 시간표로 계산한 시각. 그날 그 편이 다닌다고 확정하지 않는다. */
    UNVERIFIED_SCHEDULE,
    /** 직선거리와 평균 속도로 낸 추정. */
    ESTIMATED;

    public static RouteBasis weakest(RouteBasis a, RouteBasis b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
