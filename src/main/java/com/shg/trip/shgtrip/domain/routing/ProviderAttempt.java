package com.shg.trip.shgtrip.domain.routing;

/**
 * 엔진이 공급자 하나에 물어본 결과. 왜 그 결과가 나왔는지 추적하는 데 쓴다.
 *
 * @param dataVersion 답을 낸 데이터의 판. 조회하는 동안 판이 바뀌었거나 모르면 null이다
 */
public record ProviderAttempt(String providerId, RouteStatus status, String reason, boolean fromCache,
                              String dataVersion) {

    public ProviderAttempt(String providerId, RouteStatus status, String reason, boolean fromCache) {
        this(providerId, status, reason, fromCache, null);
    }
}
