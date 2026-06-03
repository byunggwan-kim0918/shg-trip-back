package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 벡터 검색 결과의 충분성을 판단하여 Fallback 경로 진입 여부를 결정한다.
 *
 * 카테고리당 5개 이상의 후보가 있으면 벡터 경로를 사용하고,
 * 하나라도 5개 미만이면 Fallback 경로(기존 LLM 직접 장소 생성)로 분기한다.
 */
@Component
public class FallbackDecider {

    private static final int MIN_TOTAL_CANDIDATES = 20;

    /**
     * Fallback 경로 진입 여부를 판단한다.
     *
     * 벡터 검색 결과가 최소 기준(20개)을 넘으면 벡터 경로를 사용한다.
     * 카테고리 매칭은 하지 않는다 — DB 카테고리(영어)와 사용자 입력(한국어)이 다르고,
     * 벡터 유사도가 이미 의미 기반으로 적절한 장소를 선별했기 때문.
     *
     * @param candidates 벡터 검색으로 반환된 후보 장소 목록
     * @param requestedCategories 사용자가 요청한 카테고리 목록 (현재 미사용)
     * @return true이면 Fallback 경로 진입 필요, false이면 벡터 경로 사용 가능
     */
    public boolean shouldFallback(List<PlaceCandidate> candidates, List<String> requestedCategories) {
        // 후보가 비어있거나 최소 기준 미달이면 Fallback
        if (candidates == null || candidates.size() < MIN_TOTAL_CANDIDATES) {
            return true;
        }

        // 충분한 후보가 있으면 벡터 경로 OK
        return false;
    }
}
