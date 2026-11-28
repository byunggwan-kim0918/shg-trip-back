package com.shg.trip.shgtrip.domain.routing;

import java.math.BigDecimal;

/** 공급자가 준 요금. 요금을 모르면 Fare를 두지 않는다(0원으로 두지 않는다). */
public record Fare(BigDecimal amount, String currency, Kind kind) {

    /**
     * TICKET은 사람마다 내는 승차권, TOLL은 차 한 대의 통행료, TAXI_ESTIMATE는 공급자가 낸 택시 요금 추정,
     * TAXI_TABLE은 공시된 거리 요금표로 계산한 택시 요금(시간 요금·할증 제외)이다.
     */
    public enum Kind { TICKET, TOLL, TAXI_ESTIMATE, TAXI_TABLE }
}
