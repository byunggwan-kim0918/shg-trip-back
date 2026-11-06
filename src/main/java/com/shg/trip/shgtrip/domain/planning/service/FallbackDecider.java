package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * transportation은 DB에 없을 수 있어 fallback 조건에 넣지 않는다.
 */
@Component
public class FallbackDecider {

    private static final int MIN_TOTAL_CANDIDATES = 15;

    /**
     * 골프장·마구간·우물 같은 POI만으로 attraction 수량이 "충분" 판정되면
     * 테마와 무관한 시설이 관광 스텝을 채운다.
     */
    private static final java.util.Set<String> LOW_VALUE_ATTRACTION_KEYWORDS = java.util.Set.of(
            "golf", "stable", "well", "dealership", "automotive", "parking", "car wash"
    );

    /** 후보 풀 품질 3단계: 충분 / 컴팩트(관광지 빈약, quota 완화 + 안내) / fallback. */
    public enum PoolQuality { SUFFICIENT, COMPACT, FALLBACK }

    /**
     * 구조적 최소치를 통과해도 유효 관광지(저가치 시설 제외)가 days×2개 미만이면 COMPACT다.
     * 억지로 채우지 않고 일정을 축소한다.
     */
    public PoolQuality assess(List<PlaceCandidate> candidates, long days) {
        if (shouldFallback(candidates, days)) {
            return PoolQuality.FALLBACK;
        }
        long validAttractions = candidates.stream()
                .filter(c -> "ATTRACTION".equals(PlaceCategoryConstants.majorCategory(c.category())))
                .filter(c -> !isLowValueAttraction(c))
                .count();
        return validAttractions < days * 2 ? PoolQuality.COMPACT : PoolQuality.SUFFICIENT;
    }

    private boolean isLowValueAttraction(PlaceCandidate c) {
        String combined = ((c.category() != null ? c.category() : "") + " "
                + (c.name() != null ? c.name() : "")).toLowerCase();
        return LOW_VALUE_ATTRACTION_KEYWORDS.stream().anyMatch(combined::contains);
    }

    /**
     * @return true이면 Fallback 경로 진입 필요, false이면 벡터 경로 사용 가능
     */
    public boolean shouldFallback(List<PlaceCandidate> candidates, long days) {
        if (candidates == null || candidates.isEmpty()) {
            return true;
        }

        // DB 카테고리는 Foursquare 계층 경로 형식 (예: "Dining and Drinking > Restaurant > ...")
        long accommodationCount = candidates.stream()
                .filter(c -> c.category() != null &&
                        c.category().toLowerCase().contains("lodging"))
                .count();

        long restaurantCount = candidates.stream()
                .filter(c -> c.category() != null &&
                        c.category().toLowerCase().contains("restaurant"))
                .count();

        long attractionCount = candidates.stream()
                .filter(c -> c.category() != null && (
                        c.category().toLowerCase().contains("landmarks") ||
                        c.category().toLowerCase().contains("arts and entertainment") ||
                        c.category().toLowerCase().contains("sports and recreation") ||
                        c.category().toLowerCase().contains("outdoors")))
                .count();

        boolean fallback =
                accommodationCount < 1 ||
                restaurantCount < days * 2 ||
                attractionCount < days ||
                candidates.size() < MIN_TOTAL_CANDIDATES;

        return fallback;
    }
}
