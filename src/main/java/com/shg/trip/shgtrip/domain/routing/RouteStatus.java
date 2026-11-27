package com.shg.trip.shgtrip.domain.routing;

/**
 * 경로 조회 결과 상태. 조회 실패(UNAVAILABLE)와 데이터 밖(NOT_COVERED)은 이동 불가능을 뜻하지 않는다.
 */
public enum RouteStatus {
    /** 경로를 찾았다. */
    FOUND,
    /** 데이터가 있는 공급자가 찾아봤지만 그 시각에 이어지는 경로가 없다. */
    NO_CONNECTION,
    /** 그 지역·수단을 다루는 공급자나 데이터가 없다. */
    NOT_COVERED,
    /** 시간 초과·한도·인증·서버 오류로 답을 받지 못했다. */
    UNAVAILABLE
}
