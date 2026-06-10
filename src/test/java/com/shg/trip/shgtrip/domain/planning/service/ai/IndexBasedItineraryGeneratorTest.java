package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.services.blocking.MessageService;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.global.config.AnthropicProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IndexBasedItineraryGeneratorTest {

    @Mock private AnthropicClient anthropicClient;
    @Mock private MessageService messageService;

    private IndexBasedItineraryGenerator generator;
    private AnthropicProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AnthropicProperties("claude-haiku-4-5-20250610", "claude-sonnet-4-20250514", 64000);
        lenient().when(anthropicClient.messages()).thenReturn(messageService);
        generator = new IndexBasedItineraryGenerator(anthropicClient, properties);
    }

    // ── calculateMaxTokens 테스트 ──

    @Test
    @DisplayName("1일 여행: maxTokens = 1 * 3000 + 500 = 3500")
    void calculateMaxTokens_oneDay_returns3500() {
        int result = generator.calculateMaxTokens(1);
        assertThat(result).isEqualTo(3500);
    }

    @Test
    @DisplayName("3일 여행: maxTokens = 3 * 3000 + 500 = 9500")
    void calculateMaxTokens_threeDays_returns9500() {
        int result = generator.calculateMaxTokens(3);
        assertThat(result).isEqualTo(9500);
    }

    @Test
    @DisplayName("5일 여행: maxTokens = 5 * 3000 + 500 = 15500")
    void calculateMaxTokens_fiveDays_returns15500() {
        int result = generator.calculateMaxTokens(5);
        assertThat(result).isEqualTo(15500);
    }

    @Test
    @DisplayName("7일 여행: maxTokens = 7 * 3000 + 500 = 21500")
    void calculateMaxTokens_sevenDays_returns21500() {
        int result = generator.calculateMaxTokens(7);
        assertThat(result).isEqualTo(21500);
    }

    @Test
    @DisplayName("maxOutputTokens보다 큰 값이 계산되면 maxOutputTokens로 제한")
    void calculateMaxTokens_exceedsMax_cappedAtMaxOutputTokens() {
        // 100일 × 3000 + 500 = 300500 > 64000
        int result = generator.calculateMaxTokens(100);
        assertThat(result).isEqualTo(64000);
    }

    @Test
    @DisplayName("0일 여행: maxTokens = 0 * 3000 + 500 = 500")
    void calculateMaxTokens_zeroDays_returns500() {
        int result = generator.calculateMaxTokens(0);
        assertThat(result).isEqualTo(500);
    }

    // ── buildUserMessage 테스트 ──

    @Test
    @DisplayName("사용자 메시지에 여행 정보 섹션이 포함된다")
    void buildUserMessage_containsTripInfo() {
        VectorEnrichedInput input = createInput();
        List<PlaceCandidate> candidates = createCandidates();

        String message = generator.buildUserMessage(input, candidates, 3);

        assertThat(message).contains("## 여행 정보");
        assertThat(message).contains("여행지: 도쿄");
        assertThat(message).contains("테마: 맛집, 관광");
        assertThat(message).contains("예산: 1000000원 (MEDIUM)");
        assertThat(message).contains("기간: 2026-08-01 ~ 2026-08-03 (3일)");
        assertThat(message).contains("페이스: normal");
        assertThat(message).contains("시즌: 8월 여름 축제");
    }

    @Test
    @DisplayName("사용자 메시지에 후보 장소 목록이 포함된다")
    void buildUserMessage_containsCandidatesList() {
        VectorEnrichedInput input = createInput();
        List<PlaceCandidate> candidates = createCandidates();

        String message = generator.buildUserMessage(input, candidates, 3);

        assertThat(message).contains("## 후보 장소 목록");
        assertThat(message).contains("1. 센소지 [관광] (아사쿠사) lat:35.7148 lng:139.7967 ★4.5");
        assertThat(message).contains("2. 메이지신궁 [관광] (하라주쿠) lat:35.6764 lng:139.6993 ★4.6");
    }

    @Test
    @DisplayName("사용자 메시지에 도구 호출 지시가 포함된다")
    void buildUserMessage_containsToolCallInstruction() {
        VectorEnrichedInput input = createInput();
        List<PlaceCandidate> candidates = createCandidates();

        String message = generator.buildUserMessage(input, candidates, 3);

        assertThat(message).contains("generate_itinerary 도구를 호출하여 인덱스 기반 일정을 생성하세요.");
    }

    @Test
    @DisplayName("regionAllocation이 있으면 사용자 메시지에 지역 배분이 포함된다")
    void buildUserMessage_withRegionAllocation_containsAllocation() {
        VectorEnrichedInput input = createInputWithRegionAllocation();
        List<PlaceCandidate> candidates = createCandidates();

        String message = generator.buildUserMessage(input, candidates, 5);

        assertThat(message).contains("지역 배분:");
        assertThat(message).contains("시부야, 하라주쿠");
        assertThat(message).contains("아사쿠사, 우에노");
    }

    @Test
    @DisplayName("regionAllocation이 null이면 지역 배분 섹션이 없다")
    void buildUserMessage_noRegionAllocation_omitsSection() {
        VectorEnrichedInput input = createInput();
        List<PlaceCandidate> candidates = createCandidates();

        String message = generator.buildUserMessage(input, candidates, 3);

        assertThat(message).doesNotContain("지역 배분:");
    }

    @Test
    @DisplayName("rating이 null이면 별점을 표시하지 않는다")
    void buildUserMessage_nullRating_omitsStar() {
        VectorEnrichedInput input = createInput();
        PlaceCandidate noRating = new PlaceCandidate(
                1, 100L, "테스트장소", "시부야 1-2-3", "맛집", List.of("라멘"),
                "시부야", "일본", new BigDecimal("35.6580"), new BigDecimal("139.7016"),
                "라멘 맛집", null, 0.92
        );

        String message = generator.buildUserMessage(input, List.of(noRating), 3);

        assertThat(message).contains("1. 테스트장소 [맛집] (시부야)");
        assertThat(message).doesNotContain("★");
    }

    @Test
    @DisplayName("rating이 0이면 별점을 표시하지 않는다")
    void buildUserMessage_zeroRating_omitsStar() {
        VectorEnrichedInput input = createInput();
        PlaceCandidate zeroRating = new PlaceCandidate(
                1, 100L, "테스트장소", "시부야 1-2-3", "맛집", List.of("라멘"),
                "시부야", "일본", new BigDecimal("35.6580"), new BigDecimal("139.7016"),
                "라멘 맛집", BigDecimal.ZERO, 0.92
        );

        String message = generator.buildUserMessage(input, List.of(zeroRating), 3);

        assertThat(message).doesNotContain("★");
    }

    @Test
    @DisplayName("빈 후보 장소 목록으로도 메시지를 빌드할 수 있다")
    void buildUserMessage_emptyCandidates_noError() {
        VectorEnrichedInput input = createInput();

        String message = generator.buildUserMessage(input, List.of(), 3);

        assertThat(message).contains("## 후보 장소 목록");
        assertThat(message).contains("generate_itinerary 도구를 호출하여 인덱스 기반 일정을 생성하세요.");
    }

    @Test
    @DisplayName("seasonContext가 null이면 시즌 필드는 비어있다")
    void buildUserMessage_nullSeasonContext_emptySeasonField() {
        VectorEnrichedInput input = new VectorEnrichedInput(
                "도쿄", List.of("맛집"), List.of("음식"), "normal",
                BigDecimal.valueOf(1000000),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3),
                "도쿄 여행", null,
                "도쿄", "일본", List.of("시부야"), List.of("맛집"),
                null, "MEDIUM", null, "컨텍스트"
        );

        String message = generator.buildUserMessage(input, createCandidates(), 3);

        assertThat(message).contains("시즌: \n");
    }

    // ── generate 메서드 테스트 (API 연동 기본 검증) ──

    @Test
    @DisplayName("API 호출 시 RuntimeException이 전파된다")
    void generate_apiFailure_throwsException() {
        VectorEnrichedInput input = createInput();
        List<PlaceCandidate> candidates = createCandidates();

        given(messageService.create(any(MessageCreateParams.class)))
                .willThrow(new RuntimeException("API 연결 실패"));

        assertThatThrownBy(() -> generator.generate(input, candidates))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("API 연결 실패");
    }

    @Test
    @DisplayName("generate 호출 시 MessageCreateParams에 Sonnet 모델과 올바른 maxTokens가 설정된다")
    void generate_setsCorrectParamsBeforeApiCall() {
        VectorEnrichedInput input = createInput(); // 3일
        List<PlaceCandidate> candidates = createCandidates();

        // API 호출 직전에 파라미터를 캡처하고 예외를 던짐 (실제 호출 방지)
        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        given(messageService.create(captor.capture()))
                .willThrow(new RuntimeException("의도적 중단"));

        try {
            generator.generate(input, candidates);
        } catch (RuntimeException e) {
            // 예상된 예외
        }

        MessageCreateParams params = captor.getValue();
        // Sonnet 모델 사용
        assertThat(params.model().toString()).contains("claude-sonnet-4-20250514");
        // maxTokens = 3 * 3000 + 500 = 9500
        assertThat(params.maxTokens()).isEqualTo(9500L);
    }

    @Test
    @DisplayName("generate 호출 시 generate_itinerary 도구가 포함된다")
    void generate_includesToolInParams() {
        VectorEnrichedInput input = createInput();
        List<PlaceCandidate> candidates = createCandidates();

        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        given(messageService.create(captor.capture()))
                .willThrow(new RuntimeException("의도적 중단"));

        try {
            generator.generate(input, candidates);
        } catch (RuntimeException e) {
            // 예상된 예외
        }

        MessageCreateParams params = captor.getValue();
        assertThat(params.tools()).isNotEmpty();
    }

    // ── regenerate 메서드 테스트 ──

    @Test
    @DisplayName("regenerate 호출 시 사용자 메시지에 실패 원인 컨텍스트가 포함된다")
    void regenerate_includesFailureReasonInUserMessage() {
        VectorEnrichedInput input = createInput(); // 3일
        List<PlaceCandidate> candidates = createCandidates();
        String failureReason = "stepOrder가 연속적이지 않습니다";

        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        given(messageService.create(captor.capture()))
                .willThrow(new RuntimeException("의도적 중단"));

        try {
            generator.regenerate(input, candidates, failureReason);
        } catch (RuntimeException e) {
            // 예상된 예외
        }

        MessageCreateParams params = captor.getValue();
        // params.toString()으로 전체 파라미터 내용을 확인 (사용자 메시지 포함)
        String paramsStr = params.toString();
        assertThat(paramsStr).contains("이전 생성 결과가 검증에 실패했습니다.");
        assertThat(paramsStr).contains("stepOrder가 연속적이지 않습니다");
        assertThat(paramsStr).contains("규칙을 준수하여 다시 생성하세요.");
    }

    @Test
    @DisplayName("regenerate 호출 시 generate와 동일한 Sonnet 모델과 maxTokens를 사용한다")
    void regenerate_usesSameModelAndMaxTokensAsGenerate() {
        VectorEnrichedInput input = createInput(); // 3일
        List<PlaceCandidate> candidates = createCandidates();
        String failureReason = "필수 필드 누락";

        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        given(messageService.create(captor.capture()))
                .willThrow(new RuntimeException("의도적 중단"));

        try {
            generator.regenerate(input, candidates, failureReason);
        } catch (RuntimeException e) {
            // 예상된 예외
        }

        MessageCreateParams params = captor.getValue();
        // Sonnet 모델 사용
        assertThat(params.model().toString()).contains("claude-sonnet-4-20250514");
        // maxTokens = 3 * 3000 + 500 = 9500
        assertThat(params.maxTokens()).isEqualTo(9500L);
    }

    @Test
    @DisplayName("regenerate 호출 시 generate_itinerary 도구가 포함된다")
    void regenerate_includesToolInParams() {
        VectorEnrichedInput input = createInput();
        List<PlaceCandidate> candidates = createCandidates();
        String failureReason = "시간 형식 오류";

        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        given(messageService.create(captor.capture()))
                .willThrow(new RuntimeException("의도적 중단"));

        try {
            generator.regenerate(input, candidates, failureReason);
        } catch (RuntimeException e) {
            // 예상된 예외
        }

        MessageCreateParams params = captor.getValue();
        assertThat(params.tools()).isNotEmpty();
    }

    @Test
    @DisplayName("regenerate API 호출 실패 시 RuntimeException이 전파된다")
    void regenerate_apiFailure_throwsException() {
        VectorEnrichedInput input = createInput();
        List<PlaceCandidate> candidates = createCandidates();
        String failureReason = "stepOrder 오류";

        given(messageService.create(any(MessageCreateParams.class)))
                .willThrow(new RuntimeException("API 연결 실패"));

        assertThatThrownBy(() -> generator.regenerate(input, candidates, failureReason))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("API 연결 실패");
    }

    @Test
    @DisplayName("regenerate 사용자 메시지에는 기본 여행 정보도 포함된다")
    void regenerate_containsOriginalTripInfoPlusFailureContext() {
        VectorEnrichedInput input = createInput();
        List<PlaceCandidate> candidates = createCandidates();
        String failureReason = "dayNumber가 일수 범위를 초과";

        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        given(messageService.create(captor.capture()))
                .willThrow(new RuntimeException("의도적 중단"));

        try {
            generator.regenerate(input, candidates, failureReason);
        } catch (RuntimeException e) {
            // 예상된 예외
        }

        MessageCreateParams params = captor.getValue();
        String paramsStr = params.toString();

        // 기본 여행 정보도 포함
        assertThat(paramsStr).contains("여행 정보");
        assertThat(paramsStr).contains("도쿄");
        assertThat(paramsStr).contains("후보 장소 목록");
        // 실패 컨텍스트도 포함
        assertThat(paramsStr).contains("이전 생성 결과가 검증에 실패했습니다.");
        assertThat(paramsStr).contains("dayNumber가 일수 범위를 초과");
    }

    // ── Helper methods ──

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

    private VectorEnrichedInput createInputWithRegionAllocation() {
        return new VectorEnrichedInput(
                "도쿄",
                List.of("맛집", "관광"),
                List.of("음식", "관광"),
                "normal",
                BigDecimal.valueOf(2000000),
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 5),
                "도쿄 5일 여행",
                null,
                "도쿄",
                "일본",
                List.of("시부야", "하라주쿠", "아사쿠사", "우에노"),
                List.of("맛집", "관광", "쇼핑"),
                Map.of("1-2", List.of("시부야", "하라주쿠"), "3-4", List.of("아사쿠사", "우에노")),
                "MEDIUM",
                "8월 여름 축제",
                "도쿄 5일 여행 컨텍스트"
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
                        "하라주쿠 신사", new BigDecimal("4.6"), 0.93)
        );
    }
}
