package com.shg.trip.shgtrip.global.util;

import java.math.BigDecimal;

/**
 * 좌표 기반 거리 계산 유틸리티.
 */
public final class GeoUtils {

    private GeoUtils() {}

    /** Haversine 공식으로 두 좌표 간 거리(km) 계산 */
    public static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public static double haversine(double[] a, double[] b) {
        return haversine(a[0], a[1], b[0], b[1]);
    }

    public static boolean isZeroCoord(BigDecimal lat, BigDecimal lng) {
        return lat.compareTo(BigDecimal.ZERO) == 0 && lng.compareTo(BigDecimal.ZERO) == 0;
    }
}
