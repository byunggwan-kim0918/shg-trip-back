package com.shg.trip.shgtrip.domain.itinerary.dto;

import com.shg.trip.shgtrip.global.validation.ValidDateRange;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 일정 생성 요청 DTO.
 */
@ValidDateRange
public record ItineraryGenerateRequest(

        @NotNull(message = "모드를 선택해주세요.")
        PlanningMode mode,

        @NotBlank(message = "여행지를 입력해주세요.")
        String destination,

        @NotEmpty(message = "테마를 1개 이상 선택해주세요.")
        @Size(max = 10, message = "테마는 최대 10개까지 선택할 수 있습니다.")
        List<String> themes,

        @NotEmpty(message = "카테고리를 1개 이상 선택해주세요.")
        @Size(max = 10, message = "카테고리는 최대 10개까지 선택할 수 있습니다.")
        List<String> categories,

        String pace,  // tight, normal, relaxed (기본: normal)

        String transportPref,  // walk, car, any (기본: any)

        @Positive(message = "예산은 0보다 큰 값이어야 합니다.")
        BigDecimal budget,

        @NotNull(message = "시작일을 입력해주세요.")
        @FutureOrPresent(message = "시작일은 오늘 이후여야 합니다.")
        LocalDate startDate,

        @NotNull(message = "종료일을 입력해주세요.")
        @Future(message = "종료일은 미래 날짜여야 합니다.")
        LocalDate endDate,

        String description,

        List<Long> selectedPlaceIds  // Manual Mode 전용, nullable
) {
    public enum PlanningMode {
        AUTO, MANUAL
    }
}
