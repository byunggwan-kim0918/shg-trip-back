package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.services.blocking.MessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.planning.dto.EnrichmentResult;
import com.shg.trip.shgtrip.domain.planning.dto.EnrichedInput;
import com.shg.trip.shgtrip.global.config.AnthropicProperties;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OptimizedClaudeAIServiceTest {

    @Mock private AnthropicClient anthropicClient;
    @Mock private MessageService messageService;
    @Mock private ClaudeAIService fallbackService;

    private OptimizedClaudeAIService service;
    private AnthropicProperties properties;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        properties = new AnthropicProperties("claude-haiku-4-5-20250610", "claude-sonnet-4-20250514", 64000);
        lenient().when(anthropicClient.messages()).thenReturn(messageService);
        service = new OptimizedClaudeAIService(anthropicClient, properties, objectMapper, fallbackService);
    }

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

    // ── parseEnrichResponse 직접 테스트 (핵심 비즈니스 로직) ──

    @Test
    @DisplayName("유효한 JSON 응답을 성공적으로 파싱한다")
    void parseEnrichResponse_validJson_returnsSuccess() {
        String responseJson = """
                {
                  "valid": true,
                  "normalizedDestination": "도쿄",
                  "country": "일본",
                  "regions": ["시부야", "하라주쿠", "아사쿠사"],
                  "searchTags": ["맛집", "쇼핑", "라멘"],
                  "budgetRange": "MEDIUM",
                  "seasonContext": "8월 여름, 축제 시즌",
                  "regionAllocation": null,
                  "enrichedContext": "도쿄는 일본의 수도로..."
                }
                """;

        EnrichmentResult result = service.parseEnrichResponse(responseJson, createRequest());

        assertThat(result.valid()).isTrue();
        assertThat(result.enrichedInput()).isNotNull();
        assertThat(result.enrichedInput().normalizedDestination()).isEqualTo("도쿄");
        assertThat(result.enrichedInput().country()).isEqualTo("일본");
        assertThat(result.enrichedInput().regions()).containsExactly("시부야", "하라주쿠", "아사쿠사");
        assertThat(result.enrichedInput().searchTags()).containsExactly("맛집", "쇼핑", "라멘");
        assertThat(result.enrichedInput().budgetRange()).isEqualTo("MEDIUM");
        assertThat(result.enrichedInput().seasonContext()).isEqualTo("8월 여름, 축제 시즌");
        assertThat(result.enrichedInput().enrichedContext()).isEqualTo("도쿄는 일본의 수도로...");
        assertThat(result.errorCode()).isNull();
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    @DisplayName("비현실적 예산 시 에러 코드와 수정 제안을 반환한다")
    void parseEnrichResponse_unrealisticBudget_returnsError() {
        String responseJson = """
                {
                  "valid": false,
                  "errorCode": "UNREALISTIC_BUDGET",
                  "errorMessage": "10,000원으로 22박은 현실적이지 않습니다. 최소 예산 1일 50,000원 이상을 권장합니다."
                }
                """;

        EnrichmentResult result = service.parseEnrichResponse(responseJson, createRequest());

        assertThat(result.valid()).isFalse();
        assertThat(result.enrichedInput()).isNull();
        assertThat(result.errorCode()).isEqualTo("UNREALISTIC_BUDGET");
        assertThat(result.errorMessage()).contains("현실적이지 않습니다");
    }

    @Test
    @DisplayName("테마 상충 시 CONFLICTING_THEMES 에러를 반환한다")
    void parseEnrichResponse_conflictingThemes_returnsError() {
        String responseJson = """
                {
                  "valid": false,
                  "errorCode": "CONFLICTING_THEMES",
                  "errorMessage": "'조용한 곳'과 '시끌벅적'은 서로 상충됩니다. 하나를 선택해주세요."
                }
                """;

        EnrichmentResult result = service.parseEnrichResponse(responseJson, createRequest());

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("CONFLICTING_THEMES");
        assertThat(result.errorMessage()).contains("상충");
    }

    @Test
    @DisplayName("5일 이상 여행 시 regionAllocation을 파싱한다")
    void parseEnrichResponse_longTrip_parsesRegionAllocation() {
        String responseJson = """
                {
                  "valid": true,
                  "normalizedDestination": "도쿄",
                  "country": "일본",
                  "regions": ["시부야", "하라주쿠", "아사쿠사", "우에노"],
                  "searchTags": ["맛집", "관광", "쇼핑"],
                  "budgetRange": "HIGH",
                  "seasonContext": "8월 여름 축제",
                  "regionAllocation": {"1-2": ["시부야", "하라주쿠"], "3-4": ["아사쿠사", "우에노"]},
                  "enrichedContext": "도쿄 5일 여행 컨텍스트"
                }
                """;

        EnrichmentResult result = service.parseEnrichResponse(responseJson, createRequest());

        assertThat(result.valid()).isTrue();
        assertThat(result.enrichedInput().regionAllocation()).isNotNull();
        assertThat(result.enrichedInput().regionAllocation()).containsKey("1-2");
        assertThat(result.enrichedInput().regionAllocation().get("1-2"))
                .containsExactly("시부야", "하라주쿠");
        assertThat(result.enrichedInput().regionAllocation()).containsKey("3-4");
        assertThat(result.enrichedInput().regionAllocation().get("3-4"))
                .containsExactly("아사쿠사", "우에노");
    }

    @Test
    @DisplayName("JSON이 텍스트에 둘러싸여 있어도 파싱한다")
    void parseEnrichResponse_jsonWrappedInText_parses() {
        String wrappedResponse = "다음은 분석 결과입니다:\n" + """
                {
                  "valid": true,
                  "normalizedDestination": "오사카",
                  "country": "일본",
                  "regions": ["도톤보리"],
                  "searchTags": ["맛집"],
                  "budgetRange": "LOW",
                  "seasonContext": "봄",
                  "enrichedContext": "오사카 맛집 여행"
                }
                """ + "\n위 결과를 참고하세요.";

        EnrichmentResult result = service.parseEnrichResponse(wrappedResponse, createRequest());

        assertThat(result.valid()).isTrue();
        assertThat(result.enrichedInput().normalizedDestination()).isEqualTo("오사카");
    }

    @Test
    @DisplayName("JSON이 없는 응답 시 fallback한다")
    void parseEnrichResponse_noJson_fallsBackToLegacy() {
        String invalidResponse = "이것은 JSON이 아닙니다 자연어 텍스트입니다";

        EnrichedInput legacyResult = new EnrichedInput(
                "도쿄", List.of("맛집"), List.of("음식"), "normal",
                BigDecimal.valueOf(1000000),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3),
                null, "fallback 컨텍스트", null
        );
        given(fallbackService.enrichInput(any(ItineraryGenerateRequest.class)))
                .willReturn(legacyResult);

        EnrichmentResult result = service.parseEnrichResponse(invalidResponse, createRequest());

        assertThat(result.valid()).isTrue();
        assertThat(result.enrichedInput().enrichedContext()).isEqualTo("fallback 컨텍스트");
    }

    @Test
    @DisplayName("원본 입력 필드가 결과에 보존된다")
    void parseEnrichResponse_preservesOriginalInputFields() {
        String responseJson = """
                {
                  "valid": true,
                  "normalizedDestination": "도쿄",
                  "country": "일본",
                  "regions": ["시부야"],
                  "searchTags": ["관광"],
                  "budgetRange": "MEDIUM",
                  "seasonContext": "여름",
                  "enrichedContext": "컨텍스트"
                }
                """;

        ItineraryGenerateRequest request = createRequest();
        EnrichmentResult result = service.parseEnrichResponse(responseJson, request);

        assertThat(result.enrichedInput().destination()).isEqualTo("도쿄");
        assertThat(result.enrichedInput().themes()).isEqualTo(List.of("맛집", "관광"));
        assertThat(result.enrichedInput().categories()).isEqualTo(List.of("음식", "관광"));
        assertThat(result.enrichedInput().pace()).isEqualTo("normal");
        assertThat(result.enrichedInput().budget()).isEqualByComparingTo(BigDecimal.valueOf(1000000));
        assertThat(result.enrichedInput().startDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(result.enrichedInput().endDate()).isEqualTo(LocalDate.of(2026, 8, 3));
    }

    // ── enrichInput 통합 테스트 (API 실패 → fallback) ──

    @Test
    @DisplayName("API 실패 시 기존 enrichInput으로 fallback한다")
    void enrichInput_apiFailure_fallsBackToLegacy() {
        given(messageService.create(any(MessageCreateParams.class)))
                .willThrow(new RuntimeException("API 연결 실패"));

        EnrichedInput legacyResult = new EnrichedInput(
                "도쿄", List.of("맛집", "관광"), List.of("음식", "관광"), "normal",
                BigDecimal.valueOf(1000000),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3),
                "도쿄 여행", "레거시 컨텍스트", null
        );
        given(fallbackService.enrichInput(any(ItineraryGenerateRequest.class)))
                .willReturn(legacyResult);

        EnrichmentResult result = service.enrichInput(createRequest());

        assertThat(result.valid()).isTrue();
        assertThat(result.enrichedInput()).isNotNull();
        assertThat(result.enrichedInput().enrichedContext()).isEqualTo("레거시 컨텍스트");
        verify(fallbackService).enrichInput(any(ItineraryGenerateRequest.class));
    }

    @Test
    @DisplayName("errorCode가 null일 때 기본값 INVALID_INPUT을 사용한다")
    void parseEnrichResponse_nullErrorCode_usesDefault() {
        String responseJson = """
                {
                  "valid": false,
                  "errorCode": null,
                  "errorMessage": null
                }
                """;

        EnrichmentResult result = service.parseEnrichResponse(responseJson, createRequest());

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(result.errorMessage()).isEqualTo("입력을 수정해주세요.");
    }
}
