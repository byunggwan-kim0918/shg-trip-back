package com.shg.trip.shgtrip.domain.routing;

import java.time.Instant;

/**
 * 경로의 출처.
 *
 * @param officialData 운영기관이 낸 데이터인지. 커뮤니티가 만든 피드는 false다
 * @param observedAt   공급자에게서 받은 시각
 * @param expiresAt    이 시각 뒤에는 다시 조회해야 한다
 * @param validity     시간표 유효기간과 요청 날짜가 그 안인지. 시간표가 없는 공급자(도로망)는 null이다
 */
public record RouteSource(
        String providerId,
        RouteBasis basis,
        boolean officialData,
        String attribution,
        Instant observedAt,
        Instant expiresAt,
        ScheduleValidity validity
) {

    public RouteSource(String providerId, RouteBasis basis, boolean officialData, String attribution,
                       Instant observedAt, Instant expiresAt) {
        this(providerId, basis, officialData, attribution, observedAt, expiresAt, null);
    }

    /**
     * @param requestedDateCovered false면 요청 날짜가 시간표 기간 밖이라 다른 날 시간표로 소요 시간만 낸 것이다
     * @param timetableDate        경로를 계산한 시간표 날짜. 요청 날짜와 다르면 빌려 쓴 날짜다
     */
    public record ScheduleValidity(Instant from, Instant until, boolean requestedDateCovered,
                                   java.time.LocalDate timetableDate) {}
}
