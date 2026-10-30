package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.global.util.GeoUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 후보 좌표를 <b>지리 구역</b>으로 묶어 Sonnet이 "같은 날로 묶을 범위"를 판단할 수 있게 한다.
 *
 * <h2>왜 필요한가</h2>
 * 선택 프롬프트가 Sonnet에게 주는 유일한 지리 신호는 주소에서 뽑은 {@code subRegion}이었다.
 * 그런데 제주는 그 값이 <b>제주시(346) / 서귀포시(236)</b> 둘뿐이고, 238건은 광역명
 * ("제주특별자치도"), 29건은 파싱 실패("Korea,")다. "같은 세부지역으로 묶으세요"라는 지시가
 * 제주에서는 <b>"섬의 절반(50~70km) 안에 묶어라"</b>가 된다.
 *
 * <p>그 결과 Sonnet이 하루에 섬을 가로지르는 조합을 고르고, 뒤의 RouteOptimizer는 이미 흩어진
 * 것을 날짜만 바꿔 담을 뿐이라 일일 이동이 표시 153~155km까지 나왔다(실측 92~95번).
 * 긴 이동이 거리 예산을 다 먹으니 갭 보충도 막혀 순공백 197분이 그대로 남았다 —
 * <b>"하루 155km"와 "3시간 공백"은 같은 문제의 양면이다.</b>
 *
 * <p>그래서 주소 문자열이 아니라 <b>좌표</b>로 구역을 만들고, 구역 간 실제 거리까지 함께 준다.
 *
 * <h2>왜 k-means가 아니라 격자인가</h2>
 * k-means는 초기값에 따라 결과가 흔들려 같은 입력이 다른 일정을 낳는다. 생성은 이미 LLM
 * 분산이 커서 결정론적인 축을 하나라도 더 쥐고 있어야 회귀를 측정할 수 있다. 여행지 하나의
 * 후보 수십 개를 나누는 데는 중심점 기준 <b>방위 격자</b>로 충분하고, 결과가 결정론적이며
 * 라벨("동부","서부")이 사람에게도 Sonnet에게도 바로 읽힌다.
 */
public final class GeoCluster {

    private GeoCluster() {}

    /** 구역 라벨과 그 구역의 중심 좌표. */
    public record Zone(String label, double lat, double lon, int count) {}

    /** 분류 결과 — 후보 index → 라벨, 그리고 라벨별 중심. */
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
     * 구역을 나눈다. 좌표가 없거나 (0,0)인 후보는 제외하고, 남은 수가 적으면 빈 결과를 준다
     * (구역이 하나뿐이면 Sonnet에게 알려줄 정보가 없다).
     *
     * <p>분할 기준은 <b>유효 후보의 중심점</b>이다. 바운딩 박스 중앙이 아니라 중심점을 쓰는 이유는
     * 좌표가 오염된 행 하나가 박스를 통째로 끌고 가기 때문이다(실측: 서울 위도가 19.694까지
     * 내려간 적이 있다).
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

        // 여행지가 좁으면 구역을 나눠도 의미가 없다 — 하루에 다 돌 수 있는 범위다.
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
     * 중심 기준 방위 라벨. 중심에서 가까우면 "중심", 아니면 동/서/남/북.
     *
     * <p>8방위(북동·남서…)로 쪼개지 않는 이유: 후보 40~50개를 8칸에 나누면 칸당 5개 남짓이라
     * 하루치를 채울 수 없고, Sonnet이 "같은 구역으로 묶으라"는 지시를 지키려다 후보가 모자라
     * 오히려 규칙을 어기게 된다.
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

    /** 중심에서 후보까지 거리의 중앙값 — 이상치 한둘에 끌려가지 않는 "여행지 크기". */
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
    /** 중심에서 이 거리(km) 중앙값 미만이면 한 덩어리로 본다 — 나눠도 하루에 다 돈다. */
    private static final double MIN_SPREAD_KM = 5.0;
    /** 중심 구역 반경 = spread × 이 비율. */
    private static final double CENTER_FRACTION = 0.5;
}
