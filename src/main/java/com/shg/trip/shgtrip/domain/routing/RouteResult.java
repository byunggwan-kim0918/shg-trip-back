package com.shg.trip.shgtrip.domain.routing;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 경로 조회 결과.
 *
 * @param providerId 결과를 낸 공급자. NOT_COVERED·UNAVAILABLE을 엔진이 모아 낸 경우 null
 * @param reason     FOUND가 아니면 원인(공급자 오류 코드 등), FOUND면 끝점을 옮긴 내역. 사용자에게 그대로 보이지 않는다
 * @param attempts   엔진이 거친 공급자 순서와 각 결과
 */
public record RouteResult(
        RouteStatus status,
        List<RouteItinerary> itineraries,
        String providerId,
        String reason,
        List<ProviderAttempt> attempts
) {

    public RouteResult {
        itineraries = List.copyOf(itineraries);
        attempts = List.copyOf(attempts);
        if (status == RouteStatus.FOUND && itineraries.isEmpty()) {
            throw new IllegalArgumentException("FOUND에는 경로가 하나 이상 있어야 한다");
        }
    }

    public static RouteResult found(String providerId, List<RouteItinerary> itineraries) {
        return new RouteResult(RouteStatus.FOUND, itineraries, providerId, null, List.of());
    }

    public static RouteResult of(RouteStatus status, String providerId, String reason) {
        return new RouteResult(status, List.of(), providerId, reason, List.of());
    }

    public RouteResult withReason(String reason) {
        return new RouteResult(status, itineraries, providerId, reason, attempts);
    }

    public RouteResult withAttempts(List<ProviderAttempt> attempts) {
        return new RouteResult(status, itineraries, providerId, reason, attempts);
    }

    /** 가장 일찍 도착하는 경로. 같으면 덜 갈아타는 쪽. */
    public Optional<RouteItinerary> earliestArrival() {
        return itineraries.stream().min(Comparator.comparing(RouteItinerary::arrival)
                .thenComparingInt(RouteItinerary::transfers));
    }
}
