package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.planning.dto.*;
import com.shg.trip.shgtrip.domain.planning.service.ai.SelectionCallGenerator;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 새 파이프라인: enrich → vectorSearch → selectPlaces(Sonnet, concept+day힌트+pairs)
 * → RouteOptimizer.repairAndSchedule(결정론적 day/순서/시간 확정) → 구조 저장(DRAFT) → complete(emitter 유지)
 * → StoryGenerationService.generateAndAttach (비동기, story-ready 후 emitter 종료)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OptimizedGenerationExecutorTest {

    @Mock private com.shg.trip.shgtrip.domain.planning.service.ai.OptimizedClaudeAIService optimizedClaudeAIService;
    @Mock private VectorSearchQueryService vectorSearchQueryService;
    @Mock private FallbackDecider fallbackDecider;
    @Mock private SelectionCallGenerator selectionCallGenerator;
    @Mock private HardValidator hardValidator;
    @Mock private IndexResultMapper indexResultMapper;
    @Mock private RouteOptimizer routeOptimizer;
    @Mock private ItineraryGenerationExecutor fallbackExecutor;
    @Mock private ItinerarySaveHelper saveHelper;
    @Mock private StoryGenerationService storyGenerationService;
    @Mock private GenerationResultStore resultStore;
    @Mock private CancellationRegistry cancellationRegistry;
    @Mock private com.shg.trip.shgtrip.domain.place.service.PlaceRefreshService placeRefreshService;
    @Mock private com.shg.trip.shgtrip.domain.place.repository.PlaceRepository placeRepository;
    @Mock private Executor googleSyncExecutor;
    @Mock private ScheduledExecutorService sseHeartbeatScheduler;

    @InjectMocks
    private OptimizedGenerationExecutor executor;

    private ItineraryGenerateRequest request;
    private SseEmitter emitter;
    private VectorEnrichedInput vectorEnrichedInput;
    private EnrichmentResult successResult;
    private List<PlaceCandidate> candidates;
    private SelectionOutput selectionOutput;
    private List<StepData> fixedSteps;
    private ItineraryData draftData;

    @BeforeEach
    void setUp() {
        ScheduledFuture<?> noopFuture = mock(ScheduledFuture.class);
        doReturn(noopFuture).when(sseHeartbeatScheduler)
                .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any());

        lenient().when(indexResultMapper.fillMissingAccommodation(any(), any()))
                .thenAnswer(i -> i.getArgument(0));

        request = new ItineraryGenerateRequest(
                ItineraryGenerateRequest.PlanningMode.AUTO,
                "도쿄",
                List.of("맛집", "쇼핑"),
                List.of("관광", "식당", "카페"),
                "normal",
                "any",
                new BigDecimal("2000000"),
                LocalDate.of(2025, 7, 1),
                LocalDate.of(2025, 7, 4),
                "도쿄 여행",
                null
        );

        emitter = new SseEmitter(300000L);

        vectorEnrichedInput = new VectorEnrichedInput(
                "도쿄", List.of("맛집", "쇼핑"), List.of("관광", "식당", "카페"),
                "normal", "any", new BigDecimal("2000000"),
                LocalDate.of(2025, 7, 1), LocalDate.of(2025, 7, 4),
                "도쿄 여행", null,
                "도쿄", "일본", List.of("시부야", "아사쿠사"),
                List.of("맛집", "쇼핑", "라멘"), null,
                "MEDIUM", "7월 여름 시즌", "도쿄 여행 컨텍스트",
                null, null
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

        selectionOutput = new SelectionOutput(
                "도쿄 골목과 사찰을 잇는 도보 여행",
                List.of(new SelectionOutput.DayPlan(1, null, List.of(1, 2, 3), null, null)),
                List.of(),
                List.of()
        );

        fixedSteps = List.of(
                new StepData(1, 1, "09:00", "11:00",
                        new PlaceData("센소지", null, "관광", "아사쿠사", "일본"),
                        List.of(), "SUBWAY", 20, BigDecimal.valueOf(5.0), BigDecimal.valueOf(200), null, BigDecimal.valueOf(0)),
                new StepData(2, 1, "12:00", "13:00",
                        new PlaceData("이치란 라멘", null, "식당", "시부야", "일본"),
                        List.of(), "WALK", 10, BigDecimal.valueOf(1.0), BigDecimal.ZERO, null, BigDecimal.valueOf(15000))
        );

        draftData = new ItineraryData(
                selectionOutput.concept(), "도쿄", new BigDecimal("15000"), List.of(), fixedSteps);
    }

    @Test
    @DisplayName("정상 흐름: enrich → vectorSearch → selectPlaces → repairAndSchedule → 구조 저장 → complete(유지) → 비동기 story")
    void execute_success_savesDraftAndTriggersAsyncStory() {
        when(cancellationRegistry.isCancelled(anyString())).thenReturn(false);
        when(optimizedClaudeAIService.enrichInput(request)).thenReturn(successResult);
        when(vectorSearchQueryService.search(vectorEnrichedInput)).thenReturn(candidates);
        when(fallbackDecider.shouldFallback(candidates, 4L)).thenReturn(false);
        when(placeRepository.findByIdAndNeedsSync(anyList(), any())).thenReturn(List.of());
        when(placeRepository.findAllById(anyList())).thenReturn(List.of());

        when(selectionCallGenerator.selectPlaces(eq(vectorEnrichedInput), anyList())).thenReturn(selectionOutput);
        when(routeOptimizer.repairAndSchedule(eq(selectionOutput), anyList(), eq("normal"), eq("any"), any())).thenReturn(fixedSteps);
        when(indexResultMapper.toDraftItineraryData(eq(fixedSteps), eq("도쿄"), eq(selectionOutput.concept())))
                .thenReturn(draftData);
        when(hardValidator.validate(draftData)).thenReturn(HardValidationResult.pass());

        Itinerary mockItinerary = mock(Itinerary.class);
        when(mockItinerary.getId()).thenReturn(42L);
        when(saveHelper.save(eq(draftData), any(EnrichedInput.class), eq(1L), eq(true))).thenReturn(mockItinerary);

        executor.execute("job-1", request, 1L, emitter);

        verify(optimizedClaudeAIService).enrichInput(request);
        verify(vectorSearchQueryService).search(vectorEnrichedInput);
        verify(selectionCallGenerator).selectPlaces(eq(vectorEnrichedInput), anyList());
        verify(routeOptimizer).repairAndSchedule(eq(selectionOutput), anyList(), eq("normal"), eq("any"), any());
        verify(saveHelper).save(eq(draftData), any(EnrichedInput.class), eq(1L), eq(true));
        verify(resultStore).save("job-1", 42L);
        verify(storyGenerationService).generateAndAttach(
                eq("job-1"), eq(emitter), eq(42L), eq(fixedSteps), eq(selectionOutput.concept()), eq(vectorEnrichedInput));
        verify(fallbackExecutor, never()).execute(anyString(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("enrichInput 비현실적 입력: 에러 SSE 전송 후 파이프라인 중단")
    void execute_enrichInvalid_sendsErrorAndStops() {
        when(cancellationRegistry.isCancelled(anyString())).thenReturn(false);
        EnrichmentResult errorResult = EnrichmentResult.error("UNREALISTIC_BUDGET", "예산이 너무 적습니다.");
        when(optimizedClaudeAIService.enrichInput(request)).thenReturn(errorResult);

        executor.execute("job-2", request, 1L, emitter);

        verify(optimizedClaudeAIService).enrichInput(request);
        verify(vectorSearchQueryService, never()).search(any());
        verify(selectionCallGenerator, never()).selectPlaces(any(), any());
        verify(saveHelper, never()).save(any(), any(), anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("Fallback 경로: 카테고리 후보 부족 시 기존 executor로 위임")
    void execute_fallbackPath_delegatesToExistingExecutor() {
        when(cancellationRegistry.isCancelled(anyString())).thenReturn(false);
        when(optimizedClaudeAIService.enrichInput(request)).thenReturn(successResult);
        when(vectorSearchQueryService.search(vectorEnrichedInput)).thenReturn(candidates);
        when(fallbackDecider.shouldFallback(candidates, 4L)).thenReturn(true);

        executor.execute("job-3", request, 1L, emitter);

        verify(fallbackExecutor).execute("job-3", request, 1L, emitter);
        verify(selectionCallGenerator, never()).selectPlaces(any(), any());
        verify(saveHelper, never()).save(any(), any(), anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("구조 검증 실패는 결정론적 버그로 간주 — 재시도 없이 로그만 남기고 그대로 저장 진행")
    void execute_hardValidationFails_logsButStillSaves() {
        when(cancellationRegistry.isCancelled(anyString())).thenReturn(false);
        when(optimizedClaudeAIService.enrichInput(request)).thenReturn(successResult);
        when(vectorSearchQueryService.search(vectorEnrichedInput)).thenReturn(candidates);
        when(fallbackDecider.shouldFallback(candidates, 4L)).thenReturn(false);
        when(placeRepository.findByIdAndNeedsSync(anyList(), any())).thenReturn(List.of());
        when(placeRepository.findAllById(anyList())).thenReturn(List.of());

        when(selectionCallGenerator.selectPlaces(eq(vectorEnrichedInput), anyList())).thenReturn(selectionOutput);
        when(routeOptimizer.repairAndSchedule(eq(selectionOutput), anyList(), eq("normal"), eq("any"), any())).thenReturn(fixedSteps);
        when(indexResultMapper.toDraftItineraryData(eq(fixedSteps), eq("도쿄"), eq(selectionOutput.concept())))
                .thenReturn(draftData);
        when(hardValidator.validate(draftData)).thenReturn(HardValidationResult.fail("일부 검증 경고"));

        Itinerary mockItinerary = mock(Itinerary.class);
        when(mockItinerary.getId()).thenReturn(99L);
        when(saveHelper.save(eq(draftData), any(EnrichedInput.class), eq(1L), eq(true))).thenReturn(mockItinerary);

        executor.execute("job-4", request, 1L, emitter);

        verify(saveHelper).save(eq(draftData), any(EnrichedInput.class), eq(1L), eq(true));
        verify(resultStore).save("job-4", 99L);
        verify(storyGenerationService).generateAndAttach(eq("job-4"), any(), eq(99L), any(), any(), any());
    }

    @Test
    @DisplayName("취소된 작업: 즉시 중단")
    void execute_cancelled_stopsImmediately() {
        when(cancellationRegistry.isCancelled("job-6")).thenReturn(true);

        executor.execute("job-6", request, 1L, emitter);

        verify(optimizedClaudeAIService, never()).enrichInput(any());
        verify(vectorSearchQueryService, never()).search(any());
        verify(saveHelper, never()).save(any(), any(), anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("BusinessException 발생 시 에러 SSE 전송")
    void execute_businessException_sendsErrorSse() {
        when(cancellationRegistry.isCancelled(anyString())).thenReturn(false);
        when(optimizedClaudeAIService.enrichInput(request))
                .thenThrow(new BusinessException(ErrorCode.AI_SERVICE_ERROR, "AI 서비스 오류"));

        executor.execute("job-7", request, 1L, emitter);

        verify(saveHelper, never()).save(any(), any(), anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("toLegacyEnrichedInput 변환: VectorEnrichedInput의 주요 필드가 EnrichedInput으로 정확히 매핑")
    void execute_savesWithCorrectLegacyInput() {
        when(cancellationRegistry.isCancelled(anyString())).thenReturn(false);
        when(optimizedClaudeAIService.enrichInput(request)).thenReturn(successResult);
        when(vectorSearchQueryService.search(vectorEnrichedInput)).thenReturn(candidates);
        when(fallbackDecider.shouldFallback(candidates, 4L)).thenReturn(false);
        when(placeRepository.findByIdAndNeedsSync(anyList(), any())).thenReturn(List.of());
        when(placeRepository.findAllById(anyList())).thenReturn(List.of());

        when(selectionCallGenerator.selectPlaces(eq(vectorEnrichedInput), anyList())).thenReturn(selectionOutput);
        when(routeOptimizer.repairAndSchedule(eq(selectionOutput), anyList(), eq("normal"), eq("any"), any())).thenReturn(fixedSteps);
        when(indexResultMapper.toDraftItineraryData(eq(fixedSteps), eq("도쿄"), eq(selectionOutput.concept())))
                .thenReturn(draftData);
        when(hardValidator.validate(draftData)).thenReturn(HardValidationResult.pass());

        Itinerary mockItinerary = mock(Itinerary.class);
        when(mockItinerary.getId()).thenReturn(50L);
        when(saveHelper.save(any(), any(EnrichedInput.class), eq(1L), eq(true))).thenReturn(mockItinerary);

        executor.execute("job-8", request, 1L, emitter);

        ArgumentCaptor<EnrichedInput> inputCaptor = ArgumentCaptor.forClass(EnrichedInput.class);
        verify(saveHelper).save(eq(draftData), inputCaptor.capture(), eq(1L), eq(true));

        EnrichedInput captured = inputCaptor.getValue();
        assertThat(captured.destination()).isEqualTo("도쿄");
        assertThat(captured.themes()).isEqualTo(List.of("맛집", "쇼핑"));
        assertThat(captured.categories()).isEqualTo(List.of("관광", "식당", "카페"));
        assertThat(captured.pace()).isEqualTo("normal");
        assertThat(captured.budget()).isEqualTo(new BigDecimal("2000000"));
        assertThat(captured.startDate()).isEqualTo(LocalDate.of(2025, 7, 1));
        assertThat(captured.endDate()).isEqualTo(LocalDate.of(2025, 7, 4));
        assertThat(captured.enrichedContext()).isEqualTo("도쿄 여행 컨텍스트");
    }
}
