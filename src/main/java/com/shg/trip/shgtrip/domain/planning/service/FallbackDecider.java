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
