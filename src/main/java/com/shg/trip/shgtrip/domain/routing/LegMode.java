package com.shg.trip.shgtrip.domain.routing;

/** 경로 한 구간의 실제 수단. */
public enum LegMode {
    WALK, BICYCLE, CAR, TAXI, BUS, TRAM, SUBWAY, RAIL, FERRY, AIR, CABLE, OTHER;

    public boolean isTransit() {
        return switch (this) {
            case BUS, TRAM, SUBWAY, RAIL, FERRY, AIR, CABLE, OTHER -> true;
            default -> false;
        };
    }
}
