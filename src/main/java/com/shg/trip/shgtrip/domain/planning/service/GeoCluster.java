package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.global.util.GeoUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 후보 좌표를 지리 구역으로 묶어 Sonnet이 같은 날로 묶을 범위를 판단하게 한다.
 * 주소의 {@code subRegion}은 제주에서 제주시/서귀포시 둘뿐이라 "같은 세부지역"이 섬의 절반이 된다.
 *
 * <p>k-means는 초기값에 따라 같은 입력이 다른 일정을 낳아 회귀를 측정할 수 없다.
 * 중심점 기준 방위 격자는 결정론적이고 라벨("동부","서부")이 바로 읽힌다.
 */
public final class GeoCluster {

    private GeoCluster() {}

    /** 구역 라벨과 그 구역의 중심 좌표. */
    public record Zone(String label, double lat, double lon, int count) {}

    /** 후보 index → 라벨, 라벨별 중심. */
    public record Result(Map<Integer, String> labelByIndex, List<Zone> zones) {

        public boolean isEmpty() {
            return zones.isEmpty();
        }

        /** 라벨 쌍 사이의 중심 간 거리(km). 같은 구역이면 0. */
        public double distanceKm(String a, String b) {
            Zone za = zoneOf(a);
            Zone zb = zoneOf(b);
            if (za == null || zb == null) return 0;
            return GeoUtils.haversine(new double[]{za.lat(), za.lon()}, new double[]{zb.lat(), zb.lon()});
        }

        private Zone zoneOf(String label) {
            return zones.stream().filter(z -> z.label().equals(label)).findFirst().orElse(null);
        }
    }

    /**
     * 좌표가 없거나 (0,0)인 후보는 제외하고, 남은 수가 적거나 구역이 하나뿐이면 빈 결과를 준다.
     * 바운딩 박스 중앙은 오염 좌표 하나에 통째로 끌려가 유효 후보의 중심점으로 나눈다.
     */
    public static Result of(List<PlaceCandidate> candidates) {
        List<PlaceCandidate> valid = new ArrayList<>();
        List<Double> lats = new ArrayList<>();
        List<Double> lons = new ArrayList<>();
        for (PlaceCandidate c : candidates) {
            if (c.latitude() == null || c.longitude() == null) continue;
            if (c.latitude().signum() == 0 && c.longitude().signum() == 0) continue;
            valid.add(c);
            lats.add(c.latitude().doubleValue());
            lons.add(c.longitude().doubleValue());
        }
        if (valid.size() < MIN_CANDIDATES) return new Result(Map.of(), List.of());

        double centerLat = GeoUtils.median(lats);
        double centerLon = GeoUtils.median(lons);

        // 여행지가 좁으면 하루에 다 돌 수 있어 구역을 나눌 의미가 없다.
        double spreadKm = spreadKm(valid, centerLat, centerLon);
        if (spreadKm < MIN_SPREAD_KM) return new Result(Map.of(), List.of());

        Map<Integer, String> labelByIndex = new HashMap<>();
        Map<String, List<double[]>> coordsByLabel = new HashMap<>();
        for (PlaceCandidate c : valid) {
            double lat = c.latitude().doubleValue();
            double lon = c.longitude().doubleValue();
            String label = label(lat, lon, centerLat, centerLon, spreadKm);
            labelByIndex.put(c.index(), label);
            coordsByLabel.computeIfAbsent(label, k -> new ArrayList<>()).add(new double[]{lat, lon});
        }

        List<Zone> zones = new ArrayList<>();
        for (Map.Entry<String, List<double[]>> e : coordsByLabel.entrySet()) {
            List<Double> zl = new ArrayList<>();
            List<Double> zo = new ArrayList<>();
            for (double[] p : e.getValue()) {
                zl.add(p[0]);
                zo.add(p[1]);
            }
            zones.add(new Zone(e.getKey(), GeoUtils.median(zl), GeoUtils.median(zo), e.getValue().size()));
        }
        zones.sort(Comparator.comparingInt(Zone::count).reversed());

        // 구역이 하나로 수렴하면 알려줄 게 없다.
        if (zones.size() < 2) return new Result(Map.of(), List.of());
        return new Result(labelByIndex, zones);
    }

    /**
     * 8방위로 쪼개면 칸당 후보가 하루치에 못 미쳐, Sonnet이 "같은 구역" 지시를 지키려다
     * 오히려 규칙을 어긴다.
     */
    private static String label(double lat, double lon, double cLat, double cLon, double spreadKm) {
        double dKm = GeoUtils.haversine(new double[]{lat, lon}, new double[]{cLat, cLon});
        if (dKm < spreadKm * CENTER_FRACTION) return "중심";

        double dLat = lat - cLat;
        // 경도 1도는 위도에 따라 짧아진다. 보정 없이 비교하면 한국 위도에서 동서가 과소평가된다.
        double dLon = (lon - cLon) * Math.cos(Math.toRadians(cLat));
        if (Math.abs(dLon) >= Math.abs(dLat)) return dLon >= 0 ? "동부" : "서부";
        return dLat >= 0 ? "북부" : "남부";
    }

    /** 이상치 한둘에 끌려가지 않도록 여행지 크기를 중심-후보 거리의 중앙값으로 잰다. */
    private static double spreadKm(List<PlaceCandidate> valid, double cLat, double cLon) {
        List<Double> d = new ArrayList<>(valid.size());
        for (PlaceCandidate c : valid) {
            d.add(GeoUtils.haversine(
                    new double[]{c.latitude().doubleValue(), c.longitude().doubleValue()},
                    new double[]{cLat, cLon}));
        }
        return GeoUtils.median(d);
    }

    /** 이보다 후보가 적으면 구역을 나누지 않는다. */
    private static final int MIN_CANDIDATES = 12;
    /** 중심-후보 거리 중앙값이 이(km) 미만이면 하루에 다 돌 수 있어 한 덩어리로 본다. */
    private static final double MIN_SPREAD_KM = 5.0;
    /** 중심 구역 반경 = spread × 이 비율. */
    private static final double CENTER_FRACTION = 0.5;
}
