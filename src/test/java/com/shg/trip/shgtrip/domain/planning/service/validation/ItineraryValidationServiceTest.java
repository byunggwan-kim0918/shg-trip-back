package com.shg.trip.shgtrip.domain.planning.service.validation;

import com.shg.trip.shgtrip.domain.planning.dto.*;
import com.shg.trip.shgtrip.domain.planning.service.ai.AIService;
import com.shg.trip.shgtrip.global.config.PlanningProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ItineraryValidationServiceTest {

    private ItineraryValidationService validationService;

    @Mock private AIService aiService;

    private EnrichedInput baseInput;

    @BeforeEach
    void setUp() {
        // PlanningProperties는 record라 직접 생성
        PlanningProperties planningProperties = new PlanningProperties(new PlanningProperties.Validation(70));
        validationService = new ItineraryValidationService(aiService, planningProperties);

        baseInput = new EnrichedInput(
                "도쿄", List.of("관광"), List.of("음식"), "normal",
                BigDecimal.valueOf(500000),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3),
                null, "도쿄 여행 컨텍스트", null
        );
    }

    // ── Hard 검증 ──

    @Test
    @DisplayName("제목이 없으면 hard 검증에 실패한다")
    void validateHard_missingTitle_fails() {
        ItineraryData data = makeItinerary(null, "도쿄", makeSteps(1));

        ValidationResult result = validationService.validateHard(data, baseInput);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("제목"));
    }

    @Test
    @DisplayName("일정 단계가 없으면 hard 검증에 실패한다")
    void validateHard_noSteps_fails() {
        ItineraryData data = makeItinerary("도쿄 여행", "도쿄", List.of());

        ValidationResult result = validationService.validateHard(data, baseInput);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("단계"));
    }

    @Test
    @DisplayName("일정 일수가 여행 기간을 초과하면 hard 검증에 실패한다")
    void validateHard_dayNumberExceedsTripDuration_fails() {
        // 여행 기간 3일인데 4일차 step 포함
        List<StepData> steps = List.of(makeStep(1, 1, "도쿄타워", "09:00", "11:00"));
        StepData day4Step = makeStep(2, 4, "신주쿠", "09:00", "11:00");
        ItineraryData data = makeItinerary("도쿄 여행", "도쿄",
                List.of(steps.get(0), day4Step));

        ValidationResult result = validationService.validateHard(data, baseInput);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("초과"));
    }

    @Test
    @DisplayName("시간 형식이 잘못되면 hard 검증에 실패한다")
    void validateHard_invalidTimeFormat_fails() {
        StepData badStep = new StepData(1, 1, "9:0", "11:00", makePlaceData("도쿄타워"),
                makeAlternatives(3), null, null, null, null, null, null);
        ItineraryData data = makeItinerary("도쿄 여행", "도쿄", List.of(badStep));

        ValidationResult result = validationService.validateHard(data, baseInput);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("시작 시간"));
    }

    @Test
    @DisplayName("대안 장소가 3개 미만이면 hard 검증에 실패한다")
    void validateHard_tooFewAlternatives_fails() {
        StepData step = new StepData(1, 1, "09:00", "11:00", makePlaceData("도쿄타워"),
                makeAlternatives(2), null, null, null, null, null, null);
        ItineraryData data = makeItinerary("도쿄 여행", "도쿄", List.of(step));

        ValidationResult result = validationService.validateHard(data, baseInput);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("대안"));
    }

    @Test
    @DisplayName("모든 조건을 만족하면 hard 검증에 통과한다")
    void validateHard_validData_passes() {
        ItineraryData data = makeItinerary("도쿄 여행", "도쿄", makeSteps(3));

        ValidationResult result = validationService.validateHard(data, baseInput);

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    @DisplayName("같은 장소가 중복 배치되면 hard 검증에 실패한다")
    void validateHard_duplicatePlace_fails() {
        StepData step1 = makeStep(1, 1, "도쿄타워", "09:00", "11:00");
        StepData step2 = makeStep(2, 1, "도쿄타워", "13:00", "15:00");
        ItineraryData data = makeItinerary("도쿄 여행", "도쿄", List.of(step1, step2));

        ValidationResult result = validationService.validateHard(data, baseInput);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("중복"));
    }

    @Test
    @DisplayName("공항/기차역 step은 대안 개수 검증에서 제외된다")
    void validateHard_transitHubStep_skipsAlternativeCheck() {
        StepData airportStep = new StepData(1, 1, "09:00", "11:00", makePlaceData("나리타공항"),
                List.of(), null, null, null, null, null, null); // 대안 0개
        StepData normalStep = makeStep(2, 1, "도쿄타워", "13:00", "15:00");
        ItineraryData data = makeItinerary("도쿄 여행", "도쿄", List.of(airportStep, normalStep));

        ValidationResult result = validationService.validateHard(data, baseInput);

        // 공항 step의 대안 부족은 에러가 아님
        assertThat(result.errors()).noneMatch(e -> e.contains("Step 1") && e.contains("대안"));
    }

    // ── Soft 검증 ──

    @Test
    @DisplayName("AI 평가 점수가 threshold 이상이면 soft 검증에 통과한다")
    void validateSoft_scoreAboveThreshold_passes() {
        // 3일 여행에 하루 평균 2개 이상 step 필요 (normal pace)
        ItineraryData data = makeItinerary("도쿄 여행", "도쿄", makeMultiDaySteps(3, 2));
        given(aiService.evaluateSoftQuality(data, baseInput))
                .willReturn(new SoftEvaluationResult(80, List.of()));

        ValidationResult result = validationService.validateSoft(data, baseInput);

        assertThat(result.valid()).isTrue();
        assertThat(result.score()).isEqualTo(80);
    }

    @Test
    @DisplayName("예산 초과 시 soft 점수에서 15점이 감점된다")
    void validateSoft_budgetExceeded_deducts15Points() {
        List<StepData> steps = makeMultiDaySteps(3, 2);
        ItineraryData data = new ItineraryData("도쿄 여행", "도쿄",
                BigDecimal.valueOf(600000), // 예산(500000) 초과
                null, steps);
        given(aiService.evaluateSoftQuality(data, baseInput))
                .willReturn(new SoftEvaluationResult(85, List.of()));

        ValidationResult result = validationService.validateSoft(data, baseInput);

        // 85 - 15(예산초과) = 70 → threshold 이상이므로 pass
        assertThat(result.score()).isEqualTo(70);
    }

    @Test
    @DisplayName("AI 평가 실패 시 threshold 미만 점수로 fallback된다")
    void validateSoft_aiFailure_returnsFallbackScore() {
        ItineraryData data = makeItinerary("도쿄 여행", "도쿄", makeSteps(3));
        given(aiService.evaluateSoftQuality(data, baseInput))
                .willThrow(new RuntimeException("AI 서비스 오류"));

        ValidationResult result = validationService.validateSoft(data, baseInput);

        assertThat(result.valid()).isFalse();
        assertThat(result.score()).isLessThan(70);
    }

    // ── 헬퍼 메서드 ──

    private ItineraryData makeItinerary(String title, String destination, List<StepData> steps) {
        return new ItineraryData(title, destination, null, null, steps);
    }

    private List<StepData> makeSteps(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> makeStep(i, 1, "장소" + i,
                        String.format("%02d:00", 8 + i),
                        String.format("%02d:00", 9 + i)))
                .toList();
    }

    /** 여러 날에 걸쳐 step 생성 (일정 밀도 감점 방지용) */
    private List<StepData> makeMultiDaySteps(int days, int stepsPerDay) {
        List<StepData> steps = new java.util.ArrayList<>();
        int order = 1;
        for (int day = 1; day <= days; day++) {
            for (int s = 0; s < stepsPerDay; s++) {
                // 각 날의 첫 step 이후에는 교통 정보 포함 (감점 방지)
                String transport = (s > 0) ? "WALK" : null;
                steps.add(new StepData(order++, day,
                        String.format("%02d:00", 9 + s * 2),
                        String.format("%02d:00", 10 + s * 2),
                        makePlaceData("장소" + order),
                        makeAlternatives(3), transport, null, null, null, null, null));
            }
        }
        return steps;
    }

    private StepData makeStep(int order, int day, String placeName, String start, String end) {
        return new StepData(order, day, start, end, makePlaceData(placeName),
                makeAlternatives(3), null, null, null, null, null, null);
    }

    private PlaceData makePlaceData(String name) {
        return new PlaceData(name, null, null, null, null);
    }

    private List<AlternativeData> makeAlternatives(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> new AlternativeData("대안" + i, null, null, null, null, null, null))
                .toList();
    }
}
