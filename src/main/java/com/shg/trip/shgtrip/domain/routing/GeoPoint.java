package com.shg.trip.shgtrip.domain.routing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** WGS84 좌표. */
public record GeoPoint(double lat, double lng) {

    public GeoPoint {
        if (!(lat >= -90 && lat <= 90) || !(lng >= -180 && lng <= 180)) {
            throw new IllegalArgumentException("좌표 범위 밖: " + lat + "," + lng);
        }
    }

    public GeoPoint snapped(int decimals) {
        return new GeoPoint(round(lat, decimals), round(lng, decimals));
    }

    private static double round(double v, int decimals) {
        return BigDecimal.valueOf(v).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }
}
