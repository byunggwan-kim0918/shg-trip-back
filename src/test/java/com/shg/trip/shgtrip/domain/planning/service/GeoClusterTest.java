package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 좌표 기반 구역 분할 검증.
 *
 * <p>주소 기반 세부지역이 제주에서 "제주시/서귀포시" 둘뿐이라(각각 섬의 절반) Sonnet이
 * 하루에 섬을 가로지르는 조합을 골랐다 — 실측 일일 이동 153~155km. 이 분류가 그 자리를 대신한다.
 */
class GeoClusterTest {

    private PlaceCandidate at(int index, double lat, double lon) {
        return new PlaceCandidate(index, (long) index, "p" + index, "addr", "관광",
                List.of(), "Jeju", "KR", BigDecimal.valueOf(lat), BigDecimal.valueOf(lon),
                null, null, 0.5, null, null, null, null, false, null, null, null);
    }

    /** 제주 실제 좌표 — 동(성산)·서(한림)·남(서귀포)·북(제주시). */
    private List<PlaceCandidate> jeju() {
        double[][] pts = {
                {33.458, 126.942}, {33.474, 126.932}, {33.440, 126.920}, {33.490, 126.900}, // 동
                {33.394, 126.240}, {33.388, 126.265}, {33.410, 126.260}, {33.370, 126.290}, // 서
                {33.245, 126.560}, {33.252, 126.570}, {33.230, 126.540}, {33.260, 126.600}, // 남
                {33.510, 126.520}, {33.500, 126.530}, {33.520, 126.500}, {33.490, 126.545}, // 북
        };
        List<PlaceCandidate> out = new ArrayList<>();
        for (int i = 0; i < pts.length; i++) out.add(at(i + 1, pts[i][0], pts[i][1]));
        return out;
    }

    @Test
    @DisplayName("제주 네 방향 좌표가 서로 다른 구역으로 갈린다")
    void splitsJejuIntoZones() {
        GeoCluster.Result r = GeoCluster.of(jeju());

        assertThat(r.isEmpty()).isFalse();
        assertThat(r.zones().size()).isGreaterThanOrEqualTo(3);
        // 동쪽 성산(126.94)과 서쪽 한림(126.24)은 같은 구역일 수 없다 — 약 65km 떨어져 있다
        assertThat(r.labelByIndex().get(1)).isNotEqualTo(r.labelByIndex().get(5));
    }

    @Test
    @DisplayName("구역 간 거리를 실제 좌표로 계산한다")
    void reportsDistanceBetweenZones() {
        GeoCluster.Result r = GeoCluster.of(jeju());
        String east = r.labelByIndex().get(1);
        String west = r.labelByIndex().get(5);

        assertThat(r.distanceKm(east, west)).isGreaterThan(40.0);
        assertThat(r.distanceKm(east, east)).isZero();
    }

    @Test
    @DisplayName("좁은 여행지는 나누지 않는다 — 나눠도 하루에 다 돈다")
    void skipsWhenAreaIsSmall() {
        List<PlaceCandidate> tight = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tight.add(at(i + 1, 33.500 + i * 0.001, 126.520 + i * 0.001)); // 반경 2km 남짓
        }
        assertThat(GeoCluster.of(tight).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("후보가 적으면 나누지 않는다")
    void skipsWhenTooFewCandidates() {
        assertThat(GeoCluster.of(jeju().subList(0, 8)).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("좌표 없는/(0,0) 후보는 분류 대상에서 빠진다")
    void ignoresMissingAndZeroCoords() {
        List<PlaceCandidate> list = new ArrayList<>(jeju());
        list.add(new PlaceCandidate(99, 99L, "좌표없음", "addr", "관광", List.of(), "Jeju", "KR",
                null, null, null, null, 0.5, null, null, null, null, false, null, null, null));
        list.add(at(100, 0, 0));

        GeoCluster.Result r = GeoCluster.of(list);

        assertThat(r.labelByIndex()).doesNotContainKey(99);
        assertThat(r.labelByIndex()).doesNotContainKey(100);
    }

    @Test
    @DisplayName("같은 입력은 같은 결과 — k-means와 달리 초기값 흔들림이 없다")
    void isDeterministic() {
        assertThat(GeoCluster.of(jeju()).labelByIndex())
                .isEqualTo(GeoCluster.of(jeju()).labelByIndex());
    }
}
