package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 벡터 검색 결과의 충분성을 판단하여 Fallback 경로 진입 여부를 결정한다.
 *
 * 카테고리별 최솟값을 확인하여 불균형이 없는지 검증한다:
 * - accommodation: 최소 1개
 * - restaurant: 최소 days × 2개
 * - attraction: 최소 days개
 * - 총 개수: 최소 15개
 *
 * transportation은 fallback 조건에 포함하지 않음 (DB에 없을 수 있음).
 */
@Component
public class FallbackDecider {

    private static final int MIN_TOTAL_CANDIDATES = 15;

    /**
     * 관광지로 세지 않는 저가치 카테고리 신호. 골프장/마구간/우물/차량대리점 같은 POI만으로
     * attraction 수량을 채워 "충분" 판정이 나면, 테마와 무관한 시설이 관광 스텝을 채우는
     * 저품질 일정이 생성된다(실측: 제주 일정의 관광지가 우물·마구간·야간 골프장뿐).
     */
    private static final java.util.Set<String> LOW_VALUE_ATTRACTION_KEYWORDS = java.util.Set.of(
            "golf", "stable", "well", "dealership", "automotive", "parking", "car wash"
    );

    /** 후보 풀 품질 3단계: 충분 / 컴팩트(관광지 빈약 — quota 완화 + 안내) / fallback. */
    public enum PoolQuality { SUFFICIENT, COMPACT, FALLBACK }

    /**
     * shouldFallback(구조적 최소치)을 통과해도 "유효 관광지"(저가치 시설 제외)가 days×2개
     * 미만이면 COMPACT — 억지로 채우는 대신 컴팩트한 일정으로 정직하게 축소한다.
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
     * Fallback 경로 진입 여부를 판단한다 (카테고리별 최솟값 확인).
     *
     * @param candidates 벡터 검색으로 반환된 후보 장소 목록
     * @param days 여행 일수
     * @return true이면 Fallback 경로 진입 필요, false이면 벡터 경로 사용 가능
     */
    public boolean shouldFallback(List<PlaceCandidate> candidates, long days) {
        if (candidates == null || candidates.isEmpty()) {
            return true;
        }

        // 카테고리별 개수 집계
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

        // 카테고리별 최솟값 확인
        boolean fallback =
                accommodationCount < 1 ||
                restaurantCount < days * 2 ||
                attractionCount < days ||
                candidates.size() < MIN_TOTAL_CANDIDATES;

        return fallback;
    }
}
