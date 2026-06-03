package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.anthropic.client.AnthropicClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.planning.dto.EnrichmentResult;
import com.shg.trip.shgtrip.global.config.AnthropicProperties;
import net.jqwik.api.*;
import net.jqwik.api.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

// Feature: llm-optimization, Property 7: 비현실적 입력 시 파이프라인 중단
class OptimizedClaudeAIServicePropertyTest {

    private final OptimizedClaudeAIService service;
    private final ClaudeAIService fallbackService;

    OptimizedClaudeAIServicePropertyTest() {
        AnthropicClient anthropicClient = mock(AnthropicClient.class);
        AnthropicProperties properties = new AnthropicProperties(
                "claude-haiku-4-5-20250610", "claude-sonnet-4-20250514", 64000);
        ObjectMapper objectMapper = new ObjectMapper();
        fallbackService = mock(ClaudeAIService.class);
        service = new OptimizedClaudeAIService(anthropicClient, properties, objectMapper, fallbackService);
    }

    /**
     * Property 7: 비현실적 입력 시 파이프라인 중단
     *
     * For any enrichInput 결과가 valid=false (비현실적 입력)인 경우,
     * 시스템은 일정 생성(generateItinerary)을 호출하지 않고 에러 코드와
     * 수정 제안을 포함한 에러 응답을 반환해야 한다.
     *
     * This test verifies: When parseEnrichResponse parses a JSON with valid=false,
     * the result ALWAYS has valid=false, non-null/non-empty errorCode and errorMessage,
     * null enrichedInput (so no itinerary generation can proceed), and no interaction
     * with fallback/generation services.
     *
     */
    @Property(tries = 100)
    void invalidEnrichResultAbortsPipelineWithErrorResponse(
            @ForAll("errorCodes") String errorCode,
            @ForAll("errorMessages") String errorMessage
    ) {
        // Arrange: Build a JSON response representing an invalid/unrealistic input
        String invalidResponseJson = String.format("""
                {
                  "valid": false,
                  "errorCode": "%s",
                  "errorMessage": "%s"
                }
                """, escapeJson(errorCode), escapeJson(errorMessage));

        ItineraryGenerateRequest request = createRequest();

        // Act: Parse the response (this is the core enrichInput logic)
        EnrichmentResult result = service.parseEnrichResponse(invalidResponseJson, request);

        // Assert 1: Result must be invalid (pipeline must abort)
        assertThat(result.valid()).isFalse();

        // Assert 2: enrichedInput must be null (no data for itinerary generation)
        assertThat(result.enrichedInput()).isNull();

        // Assert 3: Error code must be present (non-null, non-empty)
        assertThat(result.errorCode()).isNotNull();
        assertThat(result.errorCode()).isNotEmpty();

        // Assert 4: Error message must be present (non-null, non-empty)
        assertThat(result.errorMessage()).isNotNull();
        assertThat(result.errorMessage()).isNotEmpty();

        // Assert 5: No interaction with fallback service (pipeline aborted, no generation)
        verifyNoInteractions(fallbackService);
    }

    /**
     * Property 7 (supplementary): EnrichmentResult.error() factory always produces
     * a valid abort state regardless of input strings.
     *
     * For any non-blank errorCode and errorMessage, the error factory produces
     * a result that guarantees pipeline abort: valid=false, enrichedInput=null,
     * and the provided error details are preserved.
     *
     */
    @Property(tries = 100)
    void errorFactoryAlwaysProducesAbortState(
            @ForAll @NotBlank String errorCode,
            @ForAll @NotBlank String errorMessage
    ) {
        // Act: Create error result directly via factory
        EnrichmentResult result = EnrichmentResult.error(errorCode, errorMessage);

        // Assert: Pipeline abort state guarantees
        assertThat(result.valid()).isFalse();
        assertThat(result.enrichedInput()).isNull();
        assertThat(result.errorCode()).isEqualTo(errorCode);
        assertThat(result.errorMessage()).isEqualTo(errorMessage);
    }

    /**
     * Property 7 (supplementary): When the JSON has valid=false with null/missing
     * errorCode or errorMessage, the system still aborts and provides defaults.
     *
     * For any response with valid=false, even if errorCode/errorMessage are missing,
     * the system must still abort (valid=false) and provide non-null defaults.
     *
     */
    @Property(tries = 100)
    void invalidResponseWithMissingFieldsStillAborts(
            @ForAll("nullableErrorCodes") String errorCode,
            @ForAll("nullableErrorMessages") String errorMessage
    ) {
        // Arrange: Build a JSON where errorCode/errorMessage might be null
        String errorCodeJson = errorCode != null
                ? "\"" + escapeJson(errorCode) + "\""
                : "null";
        String errorMessageJson = errorMessage != null
                ? "\"" + escapeJson(errorMessage) + "\""
                : "null";

        String invalidResponseJson = String.format("""
                {
                  "valid": false,
                  "errorCode": %s,
                  "errorMessage": %s
                }
                """, errorCodeJson, errorMessageJson);

        ItineraryGenerateRequest request = createRequest();

        // Act
        EnrichmentResult result = service.parseEnrichResponse(invalidResponseJson, request);

        // Assert: Pipeline MUST still abort
        assertThat(result.valid()).isFalse();
        assertThat(result.enrichedInput()).isNull();

        // Assert: Error code and message must have non-null values (defaults applied if needed)
        assertThat(result.errorCode()).isNotNull();
        assertThat(result.errorCode()).isNotEmpty();
        assertThat(result.errorMessage()).isNotNull();
        assertThat(result.errorMessage()).isNotEmpty();

        // Assert: No fallback service interaction (pipeline aborted at enrichment stage)
        verifyNoInteractions(fallbackService);
    }

    // --- Arbitrary Providers ---

    @Provide
    Arbitrary<String> errorCodes() {
        return Arbitraries.of(
                "UNREALISTIC_BUDGET",
                "CONFLICTING_THEMES",
                "INVALID_DESTINATION",
                "IMPOSSIBLE_DURATION",
                "INVALID_INPUT",
                "BUDGET_TOO_LOW",
                "DATE_CONFLICT"
        );
    }

    @Provide
    Arbitrary<String> errorMessages() {
        return Arbitraries.of(
                "10,000원으로 22박은 현실적이지 않습니다. 최소 예산 1일 50,000원 이상을 권장합니다.",
                "'조용한 곳'과 '시끌벅적'은 서로 상충됩니다. 하나를 선택해주세요.",
                "해당 여행지를 찾을 수 없습니다. 여행지명을 확인해주세요.",
                "1일 여행에 예산 100원은 비현실적입니다. 최소 5만원을 권장합니다.",
                "여행 기간이 90일을 초과합니다. 최대 30일까지 지원합니다.",
                "시작일이 종료일보다 이후입니다. 날짜를 확인해주세요.",
                "예산 대비 여행 기간이 너무 깁니다. 예산을 늘리거나 기간을 줄여주세요."
        );
    }

    @Provide
    Arbitrary<String> nullableErrorCodes() {
        return Arbitraries.of(
                null,
                "UNREALISTIC_BUDGET",
                "CONFLICTING_THEMES",
                "INVALID_INPUT"
        );
    }

    @Provide
    Arbitrary<String> nullableErrorMessages() {
        return Arbitraries.of(
                null,
                "예산이 비현실적입니다.",
                "테마가 상충됩니다.",
                "입력을 수정해주세요."
        );
    }

    // --- Helpers ---

    private ItineraryGenerateRequest createRequest() {
        return new ItineraryGenerateRequest(
                ItineraryGenerateRequest.PlanningMode.AUTO,
                "도쿄",
                List.of("맛집", "관광"),
                List.of("음식", "관광"),
                "normal",
                BigDecimal.valueOf(1000000),
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 3),
                "도쿄 여행",
                null
        );
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
