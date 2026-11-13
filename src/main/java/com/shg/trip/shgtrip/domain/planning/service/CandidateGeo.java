package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.global.util.GeoUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.byIndex;

/** 후보 장소의 좌표와 중심점. */
final class CandidateGeo {

    private CandidateGeo() {
    }

    static double[] coordsOf(PlaceCandidate c) {
        if (c == null || c.latitude() == null || c.longitude() == null) return null;
        // DB에 남아 있는 좌표 (0,0) fallback 행은 좌표 없음으로 본다. FallbackStepReorderer.resolveCoords와 기준이 같다.
        // 그래야 scheduleDay·centroidOf·대안 계산의 거리에 (0,0)이 끼지 않는다.
        if (c.latitude().compareTo(BigDecimal.ZERO) == 0 && c.longitude().compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return new double[]{c.latitude().doubleValue(), c.longitude().doubleValue()};
    }

    static double[] centroidOf(List<Integer> indices, List<PlaceCandidate> candidates) {
        List<double[]> coords = indices.stream()
                .map(i -> coordsOf(byIndex(candidates, i)))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (coords.isEmpty()) return null;
        double lat = coords.stream().mapToDouble(c -> c[0]).average().orElse(0);
        double lng = coords.stream().mapToDouble(c -> c[1]).average().orElse(0);
        return new double[]{lat, lng};
    }

    static double[] accommodationCoords(DayState day, List<PlaceCandidate> candidates) {
        return day.accommodationIndex != null ? coordsOf(byIndex(candidates, day.accommodationIndex)) : null;
    }

    /** from→via→to 경유 거리(양끝 null 허용). via가 null이면 최댓값(선택 배제). */
    static double detourVia(double[] from, double[] via, double[] to) {
        if (via == null) return Double.MAX_VALUE;
        double d = 0;
        if (from != null) d += GeoUtils.haversine(from, via);
        if (to != null) d += GeoUtils.haversine(via, to);
        return d;
    }

    static double[] lastCoord(List<Integer> bucket, List<PlaceCandidate> candidates) {
        for (int i = bucket.size() - 1; i >= 0; i--) {
            double[] c = coordsOf(byIndex(candidates, bucket.get(i)));
            if (c != null) return c;
        }
        return null;
    }

    static double[] firstCoord(List<Integer> bucket, List<PlaceCandidate> candidates) {
        for (Integer idx : bucket) {
            double[] c = coordsOf(byIndex(candidates, idx));
            if (c != null) return c;
        }
        return null;
    }

    /** 하루 거리 예산에 넣을 좌표. 해외 관문은 null이다({@link InvariantChecker#isOverseasHubTransfer}와 같은 기준). */
    static double[] budgetCoords(PlaceCandidate c) {
        return c != null && PlaceCategoryConstants.isOverseasHub(c.country(), c.category()) ? null : coordsOf(c);
    }
}
