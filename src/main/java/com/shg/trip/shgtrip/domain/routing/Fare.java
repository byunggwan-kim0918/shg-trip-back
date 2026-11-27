package com.shg.trip.shgtrip.domain.routing;

import java.math.BigDecimal;

/** 공급자가 준 요금. 요금을 모르면 Fare를 두지 않는다(0원으로 두지 않는다). */
public record Fare(BigDecimal amount, String currency, Kind kind) {

    public enum Kind { TICKET, TOLL, TAXI_ESTIMATE }
}
