package com.shg.trip.shgtrip.domain.itinerary.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

public record ItineraryUpdateRequest(
        @Size(max = 100, message = "제목은 100자 이하여야 합니다.")
        String title,

        @Size(max = 20, message = "태그는 최대 20개까지 입력할 수 있습니다.")
        List<@Size(max = 50, message = "태그는 50자 이하여야 합니다.") String> tags
) {}
