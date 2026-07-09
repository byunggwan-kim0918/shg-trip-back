package com.shg.trip.shgtrip.domain.itinerary.dto;

import com.shg.trip.shgtrip.global.validation.ValidDateRange;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 일정 생성 요청 DTO.
 */
@ValidDateRange(maxDays = 10)
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
        @DecimalMax(value = "100000000", message = "예산은 최대 1억원까지 입력할 수 있습니다.")
        BigDecimal budget,

        @NotNull(message = "시작일을 입력해주세요.")
        @FutureOrPresent(message = "시작일은 오늘 이후여야 합니다.")
        LocalDate startDate,

        @NotNull(message = "종료일을 입력해주세요.")
        @Future(message = "종료일은 미래 날짜여야 합니다.")
        LocalDate endDate,

        String description,

        List<Long> selectedPlaceIds,  // Manual Mode 전용, nullable

        // Manual Mode 자유입력 장소명 — 생성 시 Google Places(ko)로 실장소화된다.
        // Google 호출 비용 상한을 위해 최대 5개.
        @Size(max = 5, message = "직접 입력 장소는 최대 5개까지 가능합니다.")
        List<@Size(max = 100, message = "장소명은 100자 이내여야 합니다.") String> customPlaceNames
) {
    public enum PlanningMode {
        AUTO, MANUAL
    }

    /**
     * MANUAL 모드는 선택 장소(ID) 또는 자유입력 장소명 중 하나가 반드시 있어야 한다.
     * 기존엔 Fallback 경로에서만 검증돼 최적화 경로로는 빈 선택이 조용히 AUTO처럼 동작했다.
     * Controller @Valid에서 동기 400으로 차단한다(SSE 진입 전).
     */
    @AssertTrue(message = "MANUAL 모드에서는 장소를 1개 이상 선택하거나 직접 입력해야 합니다.")
    public boolean isManualSelectionPresent() {
        if (mode != PlanningMode.MANUAL) return true;
        boolean hasIds = selectedPlaceIds != null && !selectedPlaceIds.isEmpty();
        // 공백-only 자유입력은 생성 도중 SSE error가 아니라 여기서 동기 400으로 걸러낸다
        boolean hasCustom = customPlaceNames != null
                && customPlaceNames.stream().anyMatch(s -> s != null && !s.isBlank());
        return hasIds || hasCustom;
    }
}
