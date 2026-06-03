package com.shg.trip.shgtrip.domain.planning.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 벡터 검색 후보 장소 DTO.
 * 인덱스 번호를 포함하여 LLM이 인덱스 기반으로 일정을 구성할 수 있도록 한다.
 */
public record PlaceCandidate(
        int index,              // 1-based 인덱스 번호
        Long placeId,           // DB id
        String name,
        String address,         // 주소 (Google Places 매핑 시 활용)
        String category,
        List<String> tags,
        String region,
        String country,
        BigDecimal latitude,
        BigDecimal longitude,
        String description,
        BigDecimal rating,
        double similarityScore  // cosine 유사도
) {}
