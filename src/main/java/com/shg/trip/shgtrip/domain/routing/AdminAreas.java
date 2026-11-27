package com.shg.trip.shgtrip.domain.routing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ISO 3166-2 행정구역 경계(scripts/routing/admin_areas.py로 만든 리소스). 공급자 영역을 구역 코드로 적게 한다.
 * 단순화한 해안선 밖 매립지(공항 등)를 놓치지 않도록 경계에서 bufferKm 안도 구역 안으로 본다.
 */
public class AdminAreas {

    private static final double KM_PER_DEGREE = 111.32;

    private record Ring(double[] lat, double[] lng, double minLat, double maxLat, double minLng, double maxLng) {}

    private final Map<String, List<Ring>> areas;
    private final double bufferKm;

    private AdminAreas(Map<String, List<Ring>> areas, double bufferKm) {
        this.areas = areas;
        this.bufferKm = bufferKm;
    }

    public static AdminAreas empty() {
        return new AdminAreas(Map.of(), 0);
    }

    public static AdminAreas read(InputStream in, ObjectMapper mapper, double bufferKm) throws IOException {
        Map<String, List<List<List<Double>>>> raw = mapper.readValue(in, new TypeReference<>() {});
        Map<String, List<Ring>> areas = new java.util.HashMap<>();
        raw.forEach((code, rings) -> areas.put(code, rings.stream().map(AdminAreas::ring).toList()));
        return new AdminAreas(Map.copyOf(areas), bufferKm);
    }

    private static Ring ring(List<List<Double>> points) {
        int n = points.size();
        double[] lat = new double[n];
        double[] lng = new double[n];
        for (int i = 0; i < n; i++) {
            lat[i] = points.get(i).get(0);
            lng[i] = points.get(i).get(1);
        }
        return new Ring(lat, lng, java.util.Arrays.stream(lat).min().orElse(0), java.util.Arrays.stream(lat).max().orElse(0),
                java.util.Arrays.stream(lng).min().orElse(0), java.util.Arrays.stream(lng).max().orElse(0));
    }

    /**
     * @param code 구역 코드(JP-13) 또는 나라 코드(JP, 그 나라 모든 구역)
     */
    public boolean contains(String code, GeoPoint p) {
        List<Ring> rings = new ArrayList<>();
        if (code.contains("-")) {
            rings.addAll(areas.getOrDefault(code, List.of()));
        } else {
            areas.forEach((k, v) -> {
                if (k.startsWith(code + "-")) rings.addAll(v);
            });
        }
        double bufferDeg = bufferKm / KM_PER_DEGREE;
        for (Ring r : rings) {
            if (p.lat() < r.minLat() - bufferDeg || p.lat() > r.maxLat() + bufferDeg) continue;
            double lngBuffer = bufferDeg / Math.max(0.1, Math.cos(Math.toRadians(p.lat())));
            if (p.lng() < r.minLng() - lngBuffer || p.lng() > r.maxLng() + lngBuffer) continue;
            if (inside(r, p) || nearEdgeKm(r, p) <= bufferKm) return true;
        }
        return false;
    }

    public boolean known(String code) {
        return code.contains("-") ? areas.containsKey(code) : areas.keySet().stream().anyMatch(k -> k.startsWith(code + "-"));
    }

    private static boolean inside(Ring r, GeoPoint p) {
        boolean hit = false;
        int n = r.lat().length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            if ((r.lat()[i] > p.lat()) != (r.lat()[j] > p.lat())
                    && p.lng() < (r.lng()[j] - r.lng()[i]) * (p.lat() - r.lat()[i]) / (r.lat()[j] - r.lat()[i]) + r.lng()[i]) {
                hit = !hit;
            }
        }
        return hit;
    }

    /** 경계 선분까지 가장 짧은 거리. 수 km 단위라 평면 근사로 충분하다. */
    private static double nearEdgeKm(Ring r, GeoPoint p) {
        double kx = KM_PER_DEGREE * Math.cos(Math.toRadians(p.lat()));
        double best = Double.MAX_VALUE;
        int n = r.lat().length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double ax = (r.lng()[j] - p.lng()) * kx;
            double ay = (r.lat()[j] - p.lat()) * KM_PER_DEGREE;
            double bx = (r.lng()[i] - p.lng()) * kx;
            double by = (r.lat()[i] - p.lat()) * KM_PER_DEGREE;
            double dx = bx - ax;
            double dy = by - ay;
            double len = dx * dx + dy * dy;
            double t = len == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len));
            double cx = ax + t * dx;
            double cy = ay + t * dy;
            best = Math.min(best, Math.sqrt(cx * cx + cy * cy));
        }
        return best;
    }
}
