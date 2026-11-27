package com.shg.trip.shgtrip.domain.routing;

import java.time.OffsetDateTime;

/**
 * 경로 한 구간.
 *
 * @param distanceMeters     모르면 null
 * @param line               노선 이름(예: 5, エアポート), 대중교통이 아니면 null
 * @param realtime           시각에 실시간 예측이 반영됐는지
 * @param unverifiedSchedule 운행일·배차를 확정하지 못한 시간표에서 나온 대중교통 구간인지
 */
public record RouteLeg(
        LegMode mode,
        String fromName,
        String toName,
        GeoPoint from,
        GeoPoint to,
        OffsetDateTime departure,
        OffsetDateTime arrival,
        int durationSeconds,
        Integer distanceMeters,
        String line,
        String operator,
        boolean realtime,
        boolean unverifiedSchedule
) {
    public RouteLeg(LegMode mode, String fromName, String toName, GeoPoint from, GeoPoint to,
                    OffsetDateTime departure, OffsetDateTime arrival, int durationSeconds, Integer distanceMeters,
                    String line, String operator, boolean realtime) {
        this(mode, fromName, toName, from, to, departure, arrival, durationSeconds, distanceMeters, line, operator,
                realtime, false);
    }

    public RouteLeg shifted(OffsetDateTime newDeparture, OffsetDateTime newArrival) {
        return new RouteLeg(mode, fromName, toName, from, to, newDeparture, newArrival, durationSeconds, distanceMeters,
                line, operator, false, unverifiedSchedule);
    }
}
