package com.shg.trip.shgtrip.global.util;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 좌표 기반 거리 계산 유틸리티.
 */
public final class GeoUtils {

    private GeoUtils() {}

    /**
     * 값 목록의 median 반환(요소 수 짝수면 가운데 두 값의 평균).
     * 좌표 아웃라이어 판정용 robust 중심점 산출에 사용한다. 입력은 비어있지 않다고 가정.
     */
    public static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

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

    // ── 이동 구간(leg) 추정: 직선거리 → 도로거리 환산 후 모드/시간/비용 산정 ──
    // RouteOptimizer(생성)와 ItineraryService(대안 선택 재계산)가 공유해 표시가 어긋나지 않게 한다.
    /** 직선거리 → 도로거리 환산 계수(해안도로·중산간 우회 반영, ~30% 가산). */
    public static final double ROAD_DISTANCE_FACTOR = 1.3;

    /** 이동 구간 추정 결과: 모드/시간(분)/도로거리(km)/비용(원). */
    public record TransportLeg(String mode, int durationMin, BigDecimal distanceKm, BigDecimal cost) {}

    /**
     * 두 좌표 사이 이동 추정. 직선 1km 미만은 도보(~4km/h), 이상은 차량(~40km/h).
     * 비용은 car 선호면 자차/렌터카 유류 추정(~300원/km), 그 외 택시·대중교통 혼합(~800원/km).
     * 시간·비용·표시거리는 도로환산(ROAD_DISTANCE_FACTOR) 기준.
     *
     * @param transportPref "car"이면 유류 단가, 그 외 혼합 단가
     * @return leg 추정. 좌표 불가/비현실 구간(maxLegKm 초과)이면 null
     */
    public static TransportLeg estimateLeg(double[] from, double[] to, String transportPref, double maxLegKm) {
        if (from == null || to == null) return null;
        double dist = haversine(from, to);
        if (dist > maxLegKm) return null;
        double roadKm = dist * ROAD_DISTANCE_FACTOR;
        BigDecimal distanceKm = BigDecimal.valueOf(roadKm).setScale(2, java.math.RoundingMode.HALF_UP);
        if (dist < 1.0) {
            return new TransportLeg("WALK", Math.max(1, (int) Math.ceil(roadKm * 15)), distanceKm, BigDecimal.ZERO);
        }
        long wonPerKm = "car".equals(transportPref) ? 300L : 800L;
        return new TransportLeg("CAR", (int) Math.ceil(roadKm / 40 * 60), distanceKm,
                BigDecimal.valueOf(roadKm * wonPerKm).setScale(0, java.math.RoundingMode.HALF_UP));
    }
}
