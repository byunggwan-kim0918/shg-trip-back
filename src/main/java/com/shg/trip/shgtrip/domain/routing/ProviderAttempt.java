package com.shg.trip.shgtrip.domain.routing;

/** 엔진이 공급자 하나에 물어본 결과. 왜 그 결과가 나왔는지 추적하는 데 쓴다. */
public record ProviderAttempt(String providerId, RouteStatus status, String reason, boolean fromCache) {}
