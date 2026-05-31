package com.shg.trip.shgtrip.domain.itinerary.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 일정 수정 요청 DTO (제목, 태그만 수정 가능).
 * 단계 수정은 Task 12에서 별도 처리.
 */
public record ItineraryUpdateRequest(
        @Size(max = 100, message = "제목은 100자 이하여야 합니다.")
        String title,

        @Size(max = 20, message = "태그는 최대 20개까지 입력할 수 있습니다.")
        List<@Size(max = 50, message = "태그는 50자 이하여야 합니다.") String> tags
) {}
