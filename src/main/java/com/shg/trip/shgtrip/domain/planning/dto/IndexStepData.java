package com.shg.trip.shgtrip.domain.planning.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 인덱스 기반 일정 step 데이터.
 * 후보 장소 인덱스와 대안 인덱스를 사용하여 장소 정보 중복 없이 일정을 구성한다.
 */
public record IndexStepData(
        int stepOrder,
        int dayNumber,
        String startTime,
        String endTime,
        int placeIndex,                     // 후보 장소 인덱스
        List<Integer> alternativeIndices,   // 대안 장소 인덱스 목록
        String transportationMode,
        Integer transportationDuration,
        BigDecimal transportationDistance,
        BigDecimal transportationCost,
        String notes,
        BigDecimal estimatedCost
) {}
