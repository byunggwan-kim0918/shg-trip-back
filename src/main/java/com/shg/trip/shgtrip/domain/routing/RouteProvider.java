package com.shg.trip.shgtrip.domain.routing;

/**
 * 경로 공급자 어댑터. 공급자 응답을 공통 모델로 바꾸고 상태를 구분하는 일만 한다.
 * 캐시·호출 한도·공급자 순서는 {@link RouteEngine}이 맡는다.
 */
public interface RouteProvider {

    String id();

    RoutingProperties.ProviderSettings settings();

    /**
     * 예외를 던지지 않는다. 통신 실패는 UNAVAILABLE로 돌려준다.
     */
    RouteResult route(RouteQuery query);
}
