package com.shg.trip.shgtrip.domain.routing;

/** 요청 단위 이동 방식. TRANSIT은 대중교통과 그 앞뒤 도보를 함께 뜻한다. TAXI는 차 경로에 택시 요금을 붙인다. */
public enum TravelMode {
    TRANSIT, WALK, BICYCLE, CAR, TAXI
}
