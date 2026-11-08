package com.shg.trip.shgtrip.domain.itinerary.dto;

import com.shg.trip.shgtrip.global.validation.ValidDateRange;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@ValidDateRange(maxDays = 10)
public record ItineraryGenerateRequest(

        @NotNull(message = "모드를 선택해주세요.")
        PlanningMode mode,

        @NotBlank(message = "여행지를 입력해주세요.")
        @Size(max = 50, message = "여행지는 50자 이내로 입력해주세요.")
        String destination,

        @NotEmpty(message = "테마를 1개 이상 선택해주세요.")
        @Size(max = 10, message = "테마는 최대 10개까지 선택할 수 있습니다.")
        List<@NotNull(message = "테마 값이 비어 있습니다.")
             @Pattern(regexp = WizardOptionIds.THEME_PATTERN, message = "지원하지 않는 테마입니다.") String> themes,

        @NotEmpty(message = "카테고리를 1개 이상 선택해주세요.")
        @Size(max = 10, message = "카테고리는 최대 10개까지 선택할 수 있습니다.")
        List<@NotNull(message = "카테고리 값이 비어 있습니다.")
             @Pattern(regexp = WizardOptionIds.CATEGORY_PATTERN, message = "지원하지 않는 카테고리입니다.") String> categories,

        @Pattern(regexp = WizardOptionIds.PACE_PATTERN, message = "지원하지 않는 일정 밀도입니다.")
        String pace,  // null이면 normal

        @Pattern(regexp = WizardOptionIds.TRANSPORT_PATTERN, message = "지원하지 않는 이동 수단입니다.")
        String transportPref,  // null이면 any

        @Positive(message = "예산은 0보다 큰 값이어야 합니다.")
        @DecimalMax(value = "100000000", message = "예산은 최대 1억원까지 입력할 수 있습니다.")
        BigDecimal budget,

        @NotNull(message = "시작일을 입력해주세요.")
        @FutureOrPresent(message = "시작일은 오늘 이후여야 합니다.")
        LocalDate startDate,

        @NotNull(message = "종료일을 입력해주세요.")
        @Future(message = "종료일은 미래 날짜여야 합니다.")
        LocalDate endDate,

        @Size(max = 500, message = "추가 요청은 500자 이내로 입력해주세요.")
        String description,

        List<Long> selectedPlaceIds,  // Manual Mode 전용, nullable

        // Manual Mode 자유입력 장소명은 DB 이름 매칭으로만 실장소화하고, 없는 이름은 "찾지 못함"으로 안내한다.
        @Size(max = 5, message = "직접 입력 장소는 최대 5개까지 가능합니다.")
        List<@Size(max = 100, message = "장소명은 100자 이내여야 합니다.") String> customPlaceNames,

        // 사용자가 고르면 문장에서 추론한 값보다 우선한다
        @Pattern(regexp = "solo|couple|friends|family|parents", message = "지원하지 않는 동행입니다.")
        String companion,

        @Min(value = 1, message = "인원은 1명 이상이어야 합니다.")
        @Max(value = 20, message = "인원은 20명 이하로 입력해주세요.")
        Integer partySize,

        @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d", message = "도착 시각 형식이 올바르지 않습니다.")
        String arrivalTime,

        @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d", message = "출발 시각 형식이 올바르지 않습니다.")
        String departureTime
) {
    public ItineraryGenerateRequest(PlanningMode mode, String destination, List<String> themes, List<String> categories,
                                    String pace, String transportPref, BigDecimal budget, LocalDate startDate,
                                    LocalDate endDate, String description, List<Long> selectedPlaceIds,
                                    List<String> customPlaceNames) {
        this(mode, destination, themes, categories, pace, transportPref, budget, startDate, endDate, description,
                selectedPlaceIds, customPlaceNames, null, null, null, null);
    }

    /** 동행·인원·도착·출발을 요청 문장 끝에 덧붙인 사본. 문장만 읽는 경로(fallback)용. */
    public ItineraryGenerateRequest withTripFactsInDescription() {
        List<String> facts = new java.util.ArrayList<>();
        String who = switch (companion == null ? "" : companion) {
            case "solo" -> "혼자";
            case "couple" -> "커플";
            case "friends" -> "친구";
            case "family" -> "아이와 함께하는 가족";
            case "parents" -> "부모님 동반";
            default -> null;
        };
        if (who != null) facts.add("동행 " + who + (partySize != null ? " " + partySize + "명" : ""));
        else if (partySize != null) facts.add(partySize + "명");
        if (arrivalTime != null) facts.add("첫날 " + arrivalTime + " 도착");
        if (departureTime != null) facts.add("마지막 날 " + departureTime + " 출발");
        if (facts.isEmpty()) return this;
        String note = "(" + String.join(", ", facts) + ")";
        String merged = description == null || description.isBlank() ? note : description + " " + note;
        return new ItineraryGenerateRequest(mode, destination, themes, categories, pace, transportPref, budget,
                startDate, endDate, merged, selectedPlaceIds, customPlaceNames, companion, partySize,
                arrivalTime, departureTime);
    }

    public enum PlanningMode {
        AUTO, MANUAL
    }

    /**
     * MANUAL 모드는 선택 장소(ID)나 자유입력 장소명이 하나는 있어야 한다.
     * 최적화 경로는 따로 검증하지 않아 빈 선택이 AUTO처럼 동작하므로 SSE 진입 전 400으로 막는다.
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
