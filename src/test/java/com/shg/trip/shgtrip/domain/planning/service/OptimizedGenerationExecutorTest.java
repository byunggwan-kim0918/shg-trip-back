package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.planning.dto.*;
import com.shg.trip.shgtrip.domain.planning.service.ai.IndexBasedItineraryGenerator;
import com.shg.trip.shgtrip.domain.planning.service.ai.OptimizedClaudeAIService;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OptimizedGenerationExecutorTest {

    @Mock private OptimizedClaudeAIService optimizedClaudeAIService;
    @Mock private VectorSearchQueryService vectorSearchQueryService;
    @Mock private FallbackDecider fallbackDecider;
    @Mock private IndexBasedItineraryGenerator indexBasedItineraryGenerator;
    @Mock private HardValidator hardValidator;
    @Mock private IndexResultMapper indexResultMapper;
    @Mock private ItineraryGenerationExecutor fallbackExecutor;
    @Mock private ItinerarySaveHelper saveHelper;
    @Mock private GenerationResultStore resultStore;
    @Mock private CancellationRegistry cancellationRegistry;
    @Mock private PlaceFreshnessFilter placeFreshnessFilter;

    @InjectMocks
    private OptimizedGenerationExecutor executor;

    private ItineraryGenerateRequest request;
    private SseEmitter emitter;
    private VectorEnrichedInput vectorEnrichedInput;
    private EnrichmentResult successResult;
    private List<PlaceCandidate> candidates;
    private IndexBasedItineraryOutput generatedOutput;
    private ItineraryData itineraryData;

    @BeforeEach
    void setUp() {
        request = new ItineraryGenerateRequest(
                ItineraryGenerateRequest.PlanningMode.AUTO,
                "도쿄",
                List.of("맛집", "쇼핑"),
                List.of("관광", "식당", "카페"),
                "normal",
                new BigDecimal("2000000"),
                LocalDate.of(2025, 7, 1),
                LocalDate.of(2025, 7, 4),
                "도쿄 여행",
                null
        );

        emitter = new SseEmitter(300000L);

        vectorEnrichedInput = new VectorEnrichedInput(
                "도쿄", List.of("맛집", "쇼핑"), List.of("관광", "식당", "카페"),
                "normal", new BigDecimal("2000000"),
                LocalDate.of(2025, 7, 1), LocalDate.of(2025, 7, 4),
                "도쿄 여행", null,
                "도쿄", "일본", List.of("시부야", "아사쿠사"),
                List.of("맛집", "쇼핑", "라멘"), null,
                "MEDIUM", "7월 여름 시즌", "도쿄 여행 컨텍스트"
        );

        successResult = EnrichmentResult.success(vectorEnrichedInput);

        candidates = List.of(
                new PlaceCandidate(1, 100L, "센소지", "아사쿠사 2-3-1", "관광", List.of("사찰"), "아사쿠사", "일본",
                        BigDecimal.valueOf(35.7148), BigDecimal.valueOf(139.7967), "유명 사찰",
                        BigDecimal.valueOf(4.5), 0.95),
                new PlaceCandidate(2, 101L, "메이지신궁", "시부야구 요요기카미조노초 1-1", "관광", List.of("신사"), "하라주쿠", "일본",
                        BigDecimal.valueOf(35.6764), BigDecimal.valueOf(139.6993), "유명 신사",
                        BigDecimal.valueOf(4.6), 0.93),
                new PlaceCandidate(3, 102L, "이치란 라멘", "시부야구 진난 1-22-7", "식당", List.of("라멘"), "시부야", "일본",
                        BigDecimal.valueOf(35.6580), BigDecimal.valueOf(139.7016), "라멘 맛집",
                        BigDecimal.valueOf(4.3), 0.90)
        );

        generatedOutput = new IndexBasedItineraryOutput(
                "도쿄 4일 여행", "도쿄", new BigDecimal("1800000"), List.of("맛집", "관광"),
                List.of(
                        new IndexStepData(1, 1, "09:00", "11:00", 1, List.of(2),
                                "SUBWAY", 20, BigDecimal.valueOf(5.0), BigDecimal.valueOf(200), "아침 관광", BigDecimal.valueOf(0)),
                        new IndexStepData(2, 1, "12:00", "13:00", 3, List.of(1),
                                "WALK", 10, BigDecimal.valueOf(1.0), BigDecimal.ZERO, "점심 식사", BigDecimal.valueOf(15000))
                )
        );

        itineraryData = new ItineraryData(
                "도쿄 4일 여행", "도쿄", new BigDecimal("1800000"), List.of("맛집", "관광"),
                List.of(
                        new StepData(1, 1, "09:00", "11:00",
                                new PlaceData("센소지", null, "관광", "아사쿠사", "일본"),
                                List.of(), "SUBWAY", 20, BigDecimal.valueOf(5.0), BigDecimal.valueOf(200), "아침 관광", BigDecimal.valueOf(0)),
                        new StepData(2, 1, "12:00", "13:00",
                                new PlaceData("이치란 라멘", null, "식당", "시부야", "일본"),
                                List.of(), "WALK", 10, BigDecimal.valueOf(1.0), BigDecimal.ZERO, "점심 식사", BigDecimal.valueOf(15000))
                )
        );
    }

    @Test
    @DisplayName("벡터 경로 정상 흐름: enrich → vectorSearch → generate → validate → save → complete")
    void execute_vectorPath_success() {
        // given
        when(cancellationRegistry.isCancelled(anyString())).thenReturn(false);
        when(optimizedClaudeAIService.enrichInput(request)).thenReturn(successResult);
        when(vectorSearchQueryService.search(vectorEnrichedInput)).thenReturn(candidates);
        when(fallbackDecider.shouldFallback(candidates, vectorEnrichedInput.categories())).thenReturn(false);
        when(indexBasedItineraryGenerator.generate(vectorEnrichedInput, candidates)).thenReturn(generatedOutput);
        when(indexResultMapper.mergeIndexOutput(generatedOutput, candidates)).thenReturn(itineraryData);
        when(hardValidator.validate(itineraryData)).thenReturn(HardValidationResult.pass());
        when(placeFreshnessFilter.filter(candidates)).thenReturn(
                new PlaceFreshnessResult(candidates, List.of()));

        Itinerary mockItinerary = mock(Itinerary.class);
        when(mockItinerary.getId()).thenReturn(42L);
        when(saveHelper.save(eq(itineraryData), any(EnrichedInput.class), eq(1L))).thenReturn(mockItinerary);

        // when
        executor.execute("job-1", request, 1L, emitter);

        // then
        verify(optimizedClaudeAIService).enrichInput(request);
        verify(vectorSearchQueryService).search(vectorEnrichedInput);
        verify(fallbackDecider).shouldFallback(candidates, vectorEnrichedInput.categories());
        verify(indexBasedItineraryGenerator).generate(vectorEnrichedInput, candidates);
        verify(indexResultMapper).mergeIndexOutput(generatedOutput, candidates);
        verify(hardValidator).validate(itineraryData);
        verify(saveHelper).save(eq(itineraryData), any(EnrichedInput.class), eq(1L));
        verify(resultStore).save("job-1", 42L);
        verify(fallbackExecutor, never()).execute(anyString(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("enrichInput 비현실적 입력: 에러 SSE 전송 후 파이프라인 중단")
    void execute_enrichInvalid_sendsErrorAndStops() {
        // given
        when(cancellationRegistry.isCancelled(anyString())).thenReturn(false);
        EnrichmentResult errorResult = EnrichmentResult.error("UNREALISTIC_BUDGET", "예산이 너무 적습니다.");
        when(optimizedClaudeAIService.enrichInput(request)).thenReturn(errorResult);

        // when
        executor.execute("job-2", request, 1L, emitter);

        // then
        verify(optimizedClaudeAIService).enrichInput(request);
        verify(vectorSearchQueryService, never()).search(any());
        verify(indexBasedItineraryGenerator, never()).generate(any(), any());
        verify(saveHelper, never()).save(any(), any(), anyLong());
    }

    @Test
    @DisplayName("Fallback 경로: 카테고리 후보 부족 시 기존 executor로 위임")
    void execute_fallbackPath_delegatesToExistingExecutor() {
        // given
        when(cancellationRegistry.isCancelled(anyString())).thenReturn(false);
        when(optimizedClaudeAIService.enrichInput(request)).thenReturn(successResult);
        when(vectorSearchQueryService.search(vectorEnrichedInput)).thenReturn(candidates);
        when(fallbackDecider.shouldFallback(candidates, vectorEnrichedInput.categories())).thenReturn(true);

        // when
        executor.execute("job-3", request, 1L, emitter);

        // then
        verify(fallbackExecutor).execute("job-3", request, 1L, emitter);
        verify(indexBasedItineraryGenerator, never()).generate(any(), any());
        verify(saveHelper, never()).save(any(), any(), anyLong());
    }

    @Test
    @DisplayName("Hard validation 실패 → 1회 재생성 성공")
    void execute_hardValidationFails_regenerateSucceeds() {
        // given
        when(cancellationRegistry.isCancelled(anyString())).thenReturn(false);
        when(optimizedClaudeAIService.enrichInput(request)).thenReturn(successResult);
        when(vectorSearchQueryService.search(vectorEnrichedInput)).thenReturn(candidates);
        when(fallbackDecider.shouldFallback(candidates, vectorEnrichedInput.categories())).thenReturn(false);
        when(indexBasedItineraryGenerator.generate(vectorEnrichedInput, candidates)).thenReturn(generatedOutput);

        ItineraryData invalidData = new ItineraryData("invalid", "도쿄", BigDecimal.ZERO, List.of(), List.of());
        when(indexResultMapper.mergeIndexOutput(generatedOutput, candidates)).thenReturn(invalidData);
        when(hardValidator.validate(invalidData)).thenReturn(HardValidationResult.fail("stepOrder 연속성 위반"));

        // 재생성 결과
        IndexBasedItineraryOutput regeneratedOutput = new IndexBasedItineraryOutput(
                "도쿄 재생성", "도쿄", new BigDecimal("1800000"), List.of("맛집"),
                List.of(new IndexStepData(1, 1, "09:00", "11:00", 1, List.of(2),
                        "WALK", 10, BigDecimal.ONE, BigDecimal.ZERO, "관광", BigDecimal.ZERO)));
        when(indexBasedItineraryGenerator.regenerate(vectorEnrichedInput, candidates, "stepOrder 연속성 위반"))
                .thenReturn(regeneratedOutput);
        when(indexResultMapper.mergeIndexOutput(regeneratedOutput, candidates)).thenReturn(itineraryData);
        when(hardValidator.validate(itineraryData)).thenReturn(HardValidationResult.pass());
        when(placeFreshnessFilter.filter(candidates)).thenReturn(
                new PlaceFreshnessResult(candidates, List.of()));

        Itinerary mockItinerary = mock(Itinerary.class);
        when(mockItinerary.getId()).thenReturn(99L);
        when(saveHelper.save(eq(itineraryData), any(EnrichedInput.class), eq(1L))).thenReturn(mockItinerary);

        // when
        executor.execute("job-4", request, 1L, emitter);

        // then
        verify(indexBasedItineraryGenerator).generate(vectorEnrichedInput, candidates);
        verify(indexBasedItineraryGenerator).regenerate(vectorEnrichedInput, candidates, "stepOrder 연속성 위반");
        verify(saveHelper).save(eq(itineraryData), any(EnrichedInput.class), eq(1L));
        verify(resultStore).save("job-4", 99L);
    }

    @Test
    @DisplayName("Hard validation 실패 → 재생성도 실패 → 에러 반환")
    void execute_hardValidationFails_regenerateAlsoFails_returnsError() {
        // given
        when(cancellationRegistry.isCancelled(anyString())).thenReturn(false);
        when(optimizedClaudeAIService.enrichInput(request)).thenReturn(successResult);
        when(vectorSearchQueryService.search(vectorEnrichedInput)).thenReturn(candidates);
        when(fallbackDecider.shouldFallback(candidates, vectorEnrichedInput.categories())).thenReturn(false);
        when(indexBasedItineraryGenerator.generate(vectorEnrichedInput, candidates)).thenReturn(generatedOutput);

        ItineraryData invalidData = new ItineraryData("bad", "도쿄", BigDecimal.ZERO, List.of(), List.of());
        when(indexResultMapper.mergeIndexOutput(eq(generatedOutput), eq(candidates))).thenReturn(invalidData);
        when(hardValidator.validate(invalidData)).thenReturn(HardValidationResult.fail("시간 형식 오류"));

        IndexBasedItineraryOutput regeneratedOutput = new IndexBasedItineraryOutput(
                "도쿄 재생성2", "도쿄", BigDecimal.ZERO, List.of(), List.of());
        when(indexBasedItineraryGenerator.regenerate(vectorEnrichedInput, candidates, "시간 형식 오류"))
                .thenReturn(regeneratedOutput);

        ItineraryData stillInvalid = new ItineraryData("still bad", "도쿄", BigDecimal.ZERO, List.of(), List.of());
        when(indexResultMapper.mergeIndexOutput(regeneratedOutput, candidates)).thenReturn(stillInvalid);
        when(hardValidator.validate(stillInvalid)).thenReturn(HardValidationResult.fail("여전히 오류"));

        // when
        executor.execute("job-5", request, 1L, emitter);

        // then
        verify(indexBasedItineraryGenerator).regenerate(vectorEnrichedInput, candidates, "시간 형식 오류");
        verify(saveHelper, never()).save(any(), any(), anyLong());
    }

    @Test
    @DisplayName("취소된 작업: 즉시 중단")
    void execute_cancelled_stopsImmediately() {
        // given
        when(cancellationRegistry.isCancelled("job-6")).thenReturn(true);

        // when
        executor.execute("job-6", request, 1L, emitter);

        // then
        verify(optimizedClaudeAIService, never()).enrichInput(any());
        verify(vectorSearchQueryService, never()).search(any());
        verify(saveHelper, never()).save(any(), any(), anyLong());
    }

    @Test
    @DisplayName("BusinessException 발생 시 에러 SSE 전송")
    void execute_businessException_sendsErrorSse() {
        // given
        when(cancellationRegistry.isCancelled(anyString())).thenReturn(false);
        when(optimizedClaudeAIService.enrichInput(request))
                .thenThrow(new BusinessException(ErrorCode.AI_SERVICE_ERROR, "AI 서비스 오류"));

        // when
        executor.execute("job-7", request, 1L, emitter);

        // then
        verify(saveHelper, never()).save(any(), any(), anyLong());
    }

    @Test
    @DisplayName("toLegacyEnrichedInput 변환: VectorEnrichedInput의 주요 필드가 EnrichedInput으로 정확히 매핑")
    void execute_savesWithCorrectLegacyInput() {
        // given
        when(cancellationRegistry.isCancelled(anyString())).thenReturn(false);
        when(optimizedClaudeAIService.enrichInput(request)).thenReturn(successResult);
        when(vectorSearchQueryService.search(vectorEnrichedInput)).thenReturn(candidates);
        when(fallbackDecider.shouldFallback(candidates, vectorEnrichedInput.categories())).thenReturn(false);
        when(indexBasedItineraryGenerator.generate(vectorEnrichedInput, candidates)).thenReturn(generatedOutput);
        when(indexResultMapper.mergeIndexOutput(generatedOutput, candidates)).thenReturn(itineraryData);
        when(hardValidator.validate(itineraryData)).thenReturn(HardValidationResult.pass());
        when(placeFreshnessFilter.filter(candidates)).thenReturn(
                new PlaceFreshnessResult(candidates, List.of()));

        Itinerary mockItinerary = mock(Itinerary.class);
        when(mockItinerary.getId()).thenReturn(50L);
        when(saveHelper.save(any(), any(EnrichedInput.class), eq(1L))).thenReturn(mockItinerary);

        // when
        executor.execute("job-8", request, 1L, emitter);

        // then
        ArgumentCaptor<EnrichedInput> inputCaptor = ArgumentCaptor.forClass(EnrichedInput.class);
        verify(saveHelper).save(eq(itineraryData), inputCaptor.capture(), eq(1L));

        EnrichedInput captured = inputCaptor.getValue();
        assertThat(captured.destination()).isEqualTo("도쿄"); // normalizedDestination
        assertThat(captured.themes()).isEqualTo(List.of("맛집", "쇼핑"));
        assertThat(captured.categories()).isEqualTo(List.of("관광", "식당", "카페"));
        assertThat(captured.pace()).isEqualTo("normal");
        assertThat(captured.budget()).isEqualTo(new BigDecimal("2000000"));
        assertThat(captured.startDate()).isEqualTo(LocalDate.of(2025, 7, 1));
        assertThat(captured.endDate()).isEqualTo(LocalDate.of(2025, 7, 4));
        assertThat(captured.enrichedContext()).isEqualTo("도쿄 여행 컨텍스트");
    }
}
