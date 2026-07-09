package com.shg.trip.shgtrip.global.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * estimateLeg — 일정 생성(RouteOptimizer)과 대안 선택 재계산(ItineraryService)이 공유하는
 * 이동 추정 공식. 두 경로의 표시가 어긋나지 않도록 단일 소스로 고정한다.
 */
class GeoUtilsTest {

    private static final double MAX_LEG = 200.0;

    @Test
    @DisplayName("1km 미만은 WALK(비용 0), 이상은 CAR")
    void estimateLeg_modeByDistance() {
        // 약 0.5km (위도 0.0045도 ≈ 0.5km)
        GeoUtils.TransportLeg walk = GeoUtils.estimateLeg(
                new double[]{33.500, 126.500}, new double[]{33.5045, 126.500}, "any", MAX_LEG);
        assertThat(walk.mode()).isEqualTo("WALK");
        assertThat(walk.cost().signum()).isZero();

        // 약 11km
        GeoUtils.TransportLeg car = GeoUtils.estimateLeg(
                new double[]{33.500, 126.500}, new double[]{33.600, 126.500}, "any", MAX_LEG);
        assertThat(car.mode()).isEqualTo("CAR");
        assertThat(car.cost().signum()).isPositive();
    }

    @Test
    @DisplayName("car 선호는 유류 단가(300원/km), any는 혼합 단가(800원/km) — car가 더 저렴")
    void estimateLeg_carCheaperThanAny() {
        double[] from = {33.500, 126.500};
        double[] to = {33.600, 126.500}; // ~11km
        GeoUtils.TransportLeg car = GeoUtils.estimateLeg(from, to, "car", MAX_LEG);
        GeoUtils.TransportLeg any = GeoUtils.estimateLeg(from, to, "any", MAX_LEG);
        assertThat(car.cost().longValue()).isLessThan(any.cost().longValue());
    }

    @Test
    @DisplayName("표시 거리는 도로계수 1.3배가 적용된다 (직선보다 큼)")
    void estimateLeg_appliesRoadFactor() {
        double[] from = {33.500, 126.500};
        double[] to = {33.600, 126.500};
        double straight = GeoUtils.haversine(from, to);
        GeoUtils.TransportLeg leg = GeoUtils.estimateLeg(from, to, "any", MAX_LEG);
        assertThat(leg.distanceKm().doubleValue()).isGreaterThan(straight);
        assertThat(leg.distanceKm().doubleValue())
                .isCloseTo(straight * GeoUtils.ROAD_DISTANCE_FACTOR, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    @DisplayName("비현실 구간(상한 초과)은 null 반환")
    void estimateLeg_nullOverMaxLeg() {
        GeoUtils.TransportLeg leg = GeoUtils.estimateLeg(
                new double[]{33.500, 126.500}, new double[]{37.500, 127.000}, "any", MAX_LEG);
        assertThat(leg).isNull();
    }
}
