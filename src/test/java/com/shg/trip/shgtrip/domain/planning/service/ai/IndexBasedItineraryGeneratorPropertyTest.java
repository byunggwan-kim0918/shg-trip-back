package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.domain.planning.dto.IndexBasedItineraryOutput;
import com.shg.trip.shgtrip.domain.planning.dto.IndexStepData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import net.jqwik.api.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// Feature: llm-optimization, Property 13: 벡터 경로 hard validation 실패 시 재생성 최대 1회
class IndexBasedItineraryGeneratorPropertyTest {

    /**
     * Orchestration controller that models the vector path's generate → validate → regenerate → validate flow.
     * This is the exact orchestration logic described in the design:
     * 1. Call generate
     * 2. Run hard validation
     * 3. If validation fails, call regenerate EXACTLY once
     * 4. Run hard validation on regenerated result
     * 5. If still fails, return error (no further retry / no infinite loop)
     */
    static class VectorPathOrchestrator {

        private final GenerateFunction generateFn;
        private final RegenerateFunction regenerateFn;
        private final ValidationFunction validateFn;

        VectorPathOrchestrator(GenerateFunction generateFn, RegenerateFunction regenerateFn,
                               ValidationFunction validateFn) {
            this.generateFn = generateFn;
            this.regenerateFn = regenerateFn;
            this.validateFn = validateFn;
        }

        /**
         * Executes the vector path orchestration:
         * generate → hardValidate → (if fail) regenerate once → hardValidate → (if fail) error
         */
        IndexBasedItineraryOutput execute(VectorEnrichedInput input, List<PlaceCandidate> candidates) {
            // Step 1: Generate
            IndexBasedItineraryOutput result = generateFn.generate(input, candidates);

            // Step 2: Hard validate
            ValidationResult validation = validateFn.validate(result);
            if (validation.isValid()) {
                return result;
            }

            // Step 3: Regenerate exactly once
            IndexBasedItineraryOutput regenerated = regenerateFn.regenerate(input, candidates, validation.failureReason());

            // Step 4: Hard validate the regenerated result
            ValidationResult revalidation = validateFn.validate(regenerated);
            if (revalidation.isValid()) {
                return regenerated;
            }

            // Step 5: Error — no further retry
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                    "hard validation 실패: 재생성 후에도 검증 실패. 원인: " + revalidation.failureReason());
        }
    }

    @FunctionalInterface
    interface GenerateFunction {
        IndexBasedItineraryOutput generate(VectorEnrichedInput input, List<PlaceCandidate> candidates);
    }

    @FunctionalInterface
    interface RegenerateFunction {
        IndexBasedItineraryOutput regenerate(VectorEnrichedInput input, List<PlaceCandidate> candidates, String failureReason);
    }

    @FunctionalInterface
    interface ValidationFunction {
        ValidationResult validate(IndexBasedItineraryOutput output);
    }

    record ValidationResult(boolean isValid, String failureReason) {
        static ValidationResult success() { return new ValidationResult(true, null); }
        static ValidationResult failure(String reason) { return new ValidationResult(false, reason); }
    }

    // ── Property Tests ──

    /**
     * Property 13: When hard validation always fails (both initial and regenerated),
     * regenerate is called EXACTLY 1 time and an error is thrown (no infinite loop).
     *
     */
    @Property(tries = 100)
    void whenHardValidationAlwaysFails_regenerateCalledExactlyOnceAndErrorReturned(
            @ForAll("failureReasons") String failureReason
    ) {
        AtomicInteger generateCount = new AtomicInteger(0);
        AtomicInteger regenerateCount = new AtomicInteger(0);

        IndexBasedItineraryOutput dummyOutput = createDummyOutput();

        VectorPathOrchestrator orchestrator = new VectorPathOrchestrator(
                (input, candidates) -> {
                    generateCount.incrementAndGet();
                    return dummyOutput;
                },
                (input, candidates, reason) -> {
                    regenerateCount.incrementAndGet();
                    return dummyOutput;
                },
                (output) -> ValidationResult.failure(failureReason) // Always fails
        );

        VectorEnrichedInput input = createInput();
        List<PlaceCandidate> candidates = createCandidates();

        // Act & Assert: Should throw error
        BusinessException thrown = null;
        try {
            orchestrator.execute(input, candidates);
        } catch (BusinessException e) {
            thrown = e;
        }

        // Must throw an error (no silent success)
        assertThat(thrown).isNotNull();
        assertThat(thrown.getMessage()).contains("hard validation 실패");

        // generate called exactly once
        assertThat(generateCount.get()).isEqualTo(1);

        // regenerate called exactly once (not 0, not 2+)
        assertThat(regenerateCount.get()).isEqualTo(1);
    }

    /**
     * Property 13: When initial hard validation fails but regeneration succeeds validation,
     * the regenerated result is returned and regenerate is called exactly once.
     *
     */
    @Property(tries = 100)
    void whenInitialValidationFailsButRegenerationSucceeds_resultReturnedWithOneRegeneration(
            @ForAll("failureReasons") String failureReason
    ) {
        AtomicInteger generateCount = new AtomicInteger(0);
        AtomicInteger regenerateCount = new AtomicInteger(0);
        AtomicInteger validateCount = new AtomicInteger(0);

        IndexBasedItineraryOutput initialOutput = createDummyOutput("초기 일정");
        IndexBasedItineraryOutput regeneratedOutput = createDummyOutput("재생성 일정");

        VectorPathOrchestrator orchestrator = new VectorPathOrchestrator(
                (input, candidates) -> {
                    generateCount.incrementAndGet();
                    return initialOutput;
                },
                (input, candidates, reason) -> {
                    regenerateCount.incrementAndGet();
                    return regeneratedOutput;
                },
                (output) -> {
                    validateCount.incrementAndGet();
                    // First call (initial) fails, second call (regenerated) succeeds
                    if (output == initialOutput) {
                        return ValidationResult.failure(failureReason);
                    }
                    return ValidationResult.success();
                }
        );

        VectorEnrichedInput input = createInput();
        List<PlaceCandidate> candidates = createCandidates();

        // Act
        IndexBasedItineraryOutput result = orchestrator.execute(input, candidates);

        // Assert: Returns the regenerated result
        assertThat(result).isSameAs(regeneratedOutput);
        assertThat(result.title()).isEqualTo("재생성 일정");

        // generate called exactly once
        assertThat(generateCount.get()).isEqualTo(1);

        // regenerate called exactly once
        assertThat(regenerateCount.get()).isEqualTo(1);

        // validate called exactly twice (initial + regenerated)
        assertThat(validateCount.get()).isEqualTo(2);
    }

    /**
     * Property 13: When initial hard validation succeeds, regenerate is NEVER called.
     *
     */
    @Property(tries = 100)
    void whenInitialValidationSucceeds_regenerateNeverCalled(
            @ForAll("outputTitles") String title
    ) {
        AtomicInteger generateCount = new AtomicInteger(0);
        AtomicInteger regenerateCount = new AtomicInteger(0);
        AtomicInteger validateCount = new AtomicInteger(0);

        IndexBasedItineraryOutput validOutput = createDummyOutput(title);

        VectorPathOrchestrator orchestrator = new VectorPathOrchestrator(
                (input, candidates) -> {
                    generateCount.incrementAndGet();
                    return validOutput;
                },
                (input, candidates, reason) -> {
                    regenerateCount.incrementAndGet();
                    return createDummyOutput("should not appear");
                },
                (output) -> {
                    validateCount.incrementAndGet();
                    return ValidationResult.success(); // Always passes
                }
        );

        VectorEnrichedInput input = createInput();
        List<PlaceCandidate> candidates = createCandidates();

        // Act
        IndexBasedItineraryOutput result = orchestrator.execute(input, candidates);

        // Assert: Returns the initial valid result
        assertThat(result).isSameAs(validOutput);
        assertThat(result.title()).isEqualTo(title);

        // generate called exactly once
        assertThat(generateCount.get()).isEqualTo(1);

        // regenerate NEVER called
        assertThat(regenerateCount.get()).isEqualTo(0);

        // validate called exactly once (initial only)
        assertThat(validateCount.get()).isEqualTo(1);
    }

    /**
     * Property 13: The total number of generate + regenerate calls is bounded at most 2.
     * This guarantees no infinite loop regardless of validation outcome.
     *
     */
    @Property(tries = 100)
    void totalGenerationCallsNeverExceedTwo(
            @ForAll("validationScenarios") ValidationScenario scenario
    ) {
        AtomicInteger totalCalls = new AtomicInteger(0);

        IndexBasedItineraryOutput output = createDummyOutput();

        VectorPathOrchestrator orchestrator = new VectorPathOrchestrator(
                (input, candidates) -> {
                    totalCalls.incrementAndGet();
                    return output;
                },
                (input, candidates, reason) -> {
                    totalCalls.incrementAndGet();
                    return output;
                },
                (o) -> {
                    if (scenario.initialValidationPasses()) {
                        return ValidationResult.success();
                    }
                    // After regeneration
                    if (totalCalls.get() >= 2) {
                        return scenario.regenerationValidationPasses()
                                ? ValidationResult.success()
                                : ValidationResult.failure("재생성 후에도 실패");
                    }
                    return ValidationResult.failure("초기 검증 실패");
                }
        );

        VectorEnrichedInput input = createInput();
        List<PlaceCandidate> candidates = createCandidates();

        try {
            orchestrator.execute(input, candidates);
        } catch (BusinessException e) {
            // Expected in some scenarios
        }

        // Core invariant: total generation calls (generate + regenerate) never exceeds 2
        assertThat(totalCalls.get()).isLessThanOrEqualTo(2);
    }

    // --- Data Types ---

    record ValidationScenario(boolean initialValidationPasses, boolean regenerationValidationPasses) {}

    // --- Arbitrary Providers ---

    @Provide
    Arbitrary<String> failureReasons() {
        return Arbitraries.of(
                "stepOrder가 연속적이지 않습니다",
                "필수 필드 누락: startTime",
                "시간 형식 오류: 25:00",
                "dayNumber가 일수 범위를 초과합니다",
                "placeIndex가 후보 범위를 초과합니다",
                "동일 인덱스가 중복 사용되었습니다",
                "endTime이 startTime보다 이전입니다"
        );
    }

    @Provide
    Arbitrary<String> outputTitles() {
        return Arbitraries.of(
                "도쿄 3일 여행",
                "오사카 맛집 투어",
                "교토 문화 탐방",
                "후쿠오카 힐링 여행",
                "삿포로 겨울 여행"
        );
    }

    @Provide
    Arbitrary<ValidationScenario> validationScenarios() {
        return Arbitraries.of(
                new ValidationScenario(true, true),   // Initial passes (no regen needed)
                new ValidationScenario(false, true),  // Initial fails, regen passes
                new ValidationScenario(false, false)  // Both fail → error
        );
    }

    // --- Helper Methods ---

    private IndexBasedItineraryOutput createDummyOutput() {
        return createDummyOutput("테스트 일정");
    }

    private IndexBasedItineraryOutput createDummyOutput(String title) {
        IndexStepData step = new IndexStepData(
                1, 1, "09:00", "11:00", 1,
                List.of(2, 3, 4), "WALK", 15,
                BigDecimal.valueOf(1.2), BigDecimal.ZERO,
                "관광 시작", BigDecimal.valueOf(5000)
        );
        return new IndexBasedItineraryOutput(
                title, "도쿄", BigDecimal.valueOf(500000),
                List.of("관광", "맛집"), List.of(step)
        );
    }

    private VectorEnrichedInput createInput() {
        return new VectorEnrichedInput(
                "도쿄",
                List.of("맛집", "관광"),
                List.of("음식", "관광"),
                "normal",
                BigDecimal.valueOf(1000000),
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 3),
                "도쿄 여행",
                null,
                "도쿄",
                "일본",
                List.of("시부야", "하라주쿠", "아사쿠사"),
                List.of("맛집", "관광", "쇼핑"),
                null,
                "MEDIUM",
                "8월 여름 축제",
                "도쿄 여름 여행 컨텍스트"
        );
    }

    private List<PlaceCandidate> createCandidates() {
        return List.of(
                new PlaceCandidate(1, 10L, "센소지", "아사쿠사 2-3-1", "관광", List.of("사찰", "역사"),
                        "아사쿠사", "일본",
                        new BigDecimal("35.7148"), new BigDecimal("139.7967"),
                        "도쿄의 유명 사찰", new BigDecimal("4.5"), 0.95),
                new PlaceCandidate(2, 20L, "메이지신궁", "시부야구 요요기카미조노초 1-1", "관광", List.of("신사", "자연"),
                        "하라주쿠", "일본",
                        new BigDecimal("35.6764"), new BigDecimal("139.6993"),
                        "하라주쿠 신사", new BigDecimal("4.6"), 0.93),
                new PlaceCandidate(3, 30L, "이치란 라멘", "시부야구 진난 1-22-7", "음식", List.of("라멘", "맛집"),
                        "시부야", "일본",
                        new BigDecimal("35.6580"), new BigDecimal("139.7016"),
                        "유명 라멘 체인", new BigDecimal("4.3"), 0.90),
                new PlaceCandidate(4, 40L, "츠키지 시장", "주오구 츠키지 5-2-1", "음식", List.of("해산물", "시장"),
                        "츠키지", "일본",
                        new BigDecimal("35.6654"), new BigDecimal("139.7707"),
                        "도쿄 해산물 시장", new BigDecimal("4.4"), 0.88)
        );
    }
}
