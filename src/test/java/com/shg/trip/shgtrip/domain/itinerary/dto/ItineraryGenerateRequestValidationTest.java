package com.shg.trip.shgtrip.domain.itinerary.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 입력 가드 검증: 기간 최대 10일, 예산 상한 1억, MANUAL 빈 선택 차단, 자유입력 최대 5개.
 * 극단 입력(30박31일, 예산 무제한)이 파이프라인 품질을 붕괴시키던 문제의 진입점 차단.
 */
class ItineraryGenerateRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private ItineraryGenerateRequest request(ItineraryGenerateRequest.PlanningMode mode,
                                             BigDecimal budget, LocalDate start, LocalDate end,
                                             List<Long> selectedIds, List<String> customNames) {
        return new ItineraryGenerateRequest(mode, "제주도", List.of("힐링"), List.of("관광"),
                "normal", "any", budget, start, end, null, selectedIds, customNames);
    }

    private Set<String> messages(ItineraryGenerateRequest req) {
        return validator.validate(req).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    private final LocalDate base = LocalDate.now().plusDays(30);

    @Test
    @DisplayName("여행 기간 10일은 통과, 11일은 거부된다")
    void maxTripDays() {
        var ok = request(ItineraryGenerateRequest.PlanningMode.AUTO, null,
                base, base.plusDays(9), null, null); // 10일
        var tooLong = request(ItineraryGenerateRequest.PlanningMode.AUTO, null,
                base, base.plusDays(10), null, null); // 11일

        assertThat(messages(ok)).noneMatch(m -> m.contains("최대 10일"));
        assertThat(messages(tooLong)).anyMatch(m -> m.contains("최대 10일"));
    }

    @Test
    @DisplayName("예산 1억은 통과, 초과는 거부된다")
    void maxBudget() {
        var ok = request(ItineraryGenerateRequest.PlanningMode.AUTO,
                new BigDecimal("100000000"), base, base.plusDays(2), null, null);
        var tooMuch = request(ItineraryGenerateRequest.PlanningMode.AUTO,
                new BigDecimal("100000001"), base, base.plusDays(2), null, null);

        assertThat(messages(ok)).noneMatch(m -> m.contains("1억"));
        assertThat(messages(tooMuch)).anyMatch(m -> m.contains("1억"));
    }

    @Test
    @DisplayName("MANUAL 모드에서 선택도 자유입력도 없으면 거부된다 (기존엔 조용히 AUTO처럼 동작)")
    void manualRequiresSelection() {
        var empty = request(ItineraryGenerateRequest.PlanningMode.MANUAL, null,
                base, base.plusDays(2), null, null);
        var withIds = request(ItineraryGenerateRequest.PlanningMode.MANUAL, null,
                base, base.plusDays(2), List.of(1L), null);
        var withCustom = request(ItineraryGenerateRequest.PlanningMode.MANUAL, null,
                base, base.plusDays(2), null, List.of("성산일출봉"));

        assertThat(messages(empty)).anyMatch(m -> m.contains("MANUAL"));
        assertThat(messages(withIds)).noneMatch(m -> m.contains("MANUAL"));
        assertThat(messages(withCustom)).noneMatch(m -> m.contains("MANUAL"));
    }

    @Test
    @DisplayName("자유입력 장소는 5개까지 허용, 6개는 거부된다")
    void customPlaceNamesLimit() {
        var five = request(ItineraryGenerateRequest.PlanningMode.MANUAL, null,
                base, base.plusDays(2), null, List.of("a", "b", "c", "d", "e"));
        var six = request(ItineraryGenerateRequest.PlanningMode.MANUAL, null,
                base, base.plusDays(2), null, List.of("a", "b", "c", "d", "e", "f"));

        assertThat(messages(five)).noneMatch(m -> m.contains("최대 5개"));
        assertThat(messages(six)).anyMatch(m -> m.contains("최대 5개"));
    }
}
