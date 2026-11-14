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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 새 파이프라인: enrich → vectorSearch → selectPlaces(Sonnet, concept+day힌트+pairs)
 * → RouteOptimizer.schedule(결정론적 day/순서/시간 확정) → 구조 저장(DRAFT) → complete(emitter 유지)
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
    @Mock private GenerationTraceRecorder traceRecorder;
    @Mock private ItineraryGenerationExecutor fallbackExecutor;
    @Mock private ItinerarySaveHelper saveHelper;
    @Mock private StoryGenerationService storyGenerationService;
    @Mock private GenerationResultStore resultStore;
    @Mock private CancellationRegistry cancellationRegistry;
    @Mock private GenerationPolicyService generationPolicyService;
    @Mock private com.shg.trip.shgtrip.domain.place.repository.PlaceRepository placeRepository;
    @Mock private com.shg.trip.shgtrip.domain.planning.service.validation.PlaceRegionValidator placeRegionValidator;
    @Mock private ScheduledExecutorService sseHeartbeatScheduler;

    @InjectMocks
    private OptimizedGenerationExecutor executor;

    private ItineraryGenerateRequest request;
    private GenerationStream emitter;
    private VectorEnrichedInput vectorEnrichedInput;
    private EnrichmentResult successResult;
    private List<PlaceCandidate> candidates;
    private SelectionOutput selectionOutput;
    private List<StepData> fixedSteps;
    private ItineraryData draftData;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(executor, "destinations",
                com.shg.trip.shgtrip.domain.place.destination.Destinations.fromClasspath());
        enableCountries("KR", "JP");
        ScheduledFuture<?> noopFuture = mock(ScheduledFuture.class);
        doReturn(noopFuture).when(sseHeartbeatScheduler)
                .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any());

        lenient().when(indexResultMapper.stripCodeOwnedFields(any()))
                .thenAnswer(i -> i.getArgument(0));
        lenient().when(indexResultMapper.stripZoneLevelPicks(any(), any()))
                .thenAnswer(i -> i.getArgument(0));
        lenient().when(indexResultMapper.fillMissingAccommodation(any(), any()))
                .thenAnswer(i -> i.getArgument(0));
        lenient().when(indexResultMapper.injectRequiredPlaces(any(), any()))
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
                null,
        null  // customPlaceNames
        );

        emitter = new GenerationStream("job");

        vectorEnrichedInput = new VectorEnrichedInput(
                "도쿄", List.of("맛집", "쇼핑"), List.of("관광", "식당", "카페"),
                "normal", "any", new BigDecimal("2000000"),
                LocalDate.of(2025, 7, 1), LocalDate.of(2025, 7, 4),
                "도쿄 여행", null,
                "도쿄", "JP", List.of("시부야", "아사쿠사"),
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
    @DisplayName("정상 흐름: enrich → vectorSearch → selectPlaces → schedule → 구조 저장 → complete(유지) → 비동기 story")
    void execute_success_savesDraftAndTriggersAsyncStory() {
        when(cancellationRegistry.isCancelled(anyString())).thenReturn(false);
        when(optimizedClaudeAIService.enrichInput(request)).thenReturn(successResult);
        when(vectorSearchQueryService.search(eq(vectorEnrichedInput), any())).thenReturn(candidates);
        when(fallbackDecider.assess(candidates, 4L)).thenReturn(FallbackDecider.PoolQuality.SUFFICIENT);
        when(placeRepository.findAllById(anyList())).thenReturn(List.of());

        when(selectionCallGenerator.selectPlaces(eq(vectorEnrichedInput), anyList(), any())).thenReturn(selectionOutput);
        when(routeOptimizer.schedule(eq(selectionOutput), anyList(), argThat((ScheduleArgs a) -> "normal".equals(a.pace()) && "any".equals(a.transportPref()))))
                .thenReturn(new RouteOptimizer.ScheduleResult(fixedSteps, java.util.List.of()));
        when(indexResultMapper.toDraftItineraryData(eq(fixedSteps), eq("도쿄"), anyString(), anyList(), anyList()))
                .thenReturn(draftData);
        when(hardValidator.validate(draftData)).thenReturn(HardValidationResult.pass());

        Itinerary mockItinerary = mock(Itinerary.class);
        when(mockItinerary.getId()).thenReturn(42L);
        when(saveHelper.save(eq(draftData), any(EnrichedInput.class), eq(1L), eq(true), any())).thenReturn(mockItinerary);

        executor.execute("job-1", request, 1L, emitter);

        verify(optimizedClaudeAIService).enrichInput(request);
        verify(vectorSearchQueryService).search(eq(vectorEnrichedInput), any());
        verify(selectionCallGenerator).selectPlaces(eq(vectorEnrichedInput), anyList(), any());
        verify(routeOptimizer).schedule(eq(selectionOutput), anyList(), argThat((ScheduleArgs a) -> "normal".equals(a.pace()) && "any".equals(a.transportPref())));
        verify(saveHelper).save(eq(draftData), any(EnrichedInput.class), eq(1L), eq(true), any());
        verify(resultStore).save("job-1", 42L, 1L);
        // 임시 제목(짧은 결정론 문구)을 넘겨야 한다 — concept을 넘기면 제목 교체가 0행이 된다.
        verify(storyGenerationService).generateAndAttach(
                eq("job-1"), eq(emitter), eq(42L), eq(fixedSteps), anyString(), eq(vectorEnrichedInput),
                eq(selectionOutput.concept()));
        verify(fallbackExecutor, never()).execute(anyString(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("필수 경험은 맞춘 후보에 의도를 실어 스케줄·기록에 넘기고, 빠지거나 못 맞춘 경험은 안내한다")
    @SuppressWarnings("unchecked")
    void execute_requiredExperiences_attachIntentAndNotify() {
        ItineraryGenerateRequest withRequired = new ItineraryGenerateRequest(request.mode(), request.destination(),
                request.themes(), request.categories(), request.pace(), request.transportPref(), request.budget(),
                request.startDate(), request.endDate(), request.description(), null, null, null, null, null, null,
                List.of(new ItineraryGenerateRequest.RequiredExperience("메이지신궁 아침", "메이지신궁", "morning"),
                        new ItineraryGenerateRequest.RequiredExperience("오다이바 야경", "오다이바", "evening")));
        PlaceCandidate shrine = new PlaceCandidate(2, 101L, "메이지신궁", "시부야구 요요기카미조노초 1-1",
                "Landmarks and Outdoors > Shrine", List.of(), "Tokyo", "JP", BigDecimal.valueOf(35.6764),
                BigDecimal.valueOf(139.6993), null, null, 0.93, null, null, null, 60, false, null, null, null, 40,
                "SIGHT", null, false, null);
        List<PlaceCandidate> pool = List.of(candidates.get(0), shrine, candidates.get(2));
        when(cancellationRegistry.isCancelled(anyString())).thenReturn(false);
        when(optimizedClaudeAIService.enrichInput(withRequired)).thenReturn(successResult);
        when(vectorSearchQueryService.search(eq(vectorEnrichedInput), any())).thenReturn(pool);
        when(fallbackDecider.assess(pool, 4L)).thenReturn(FallbackDecider.PoolQuality.SUFFICIENT);
        when(placeRepository.findAllById(anyList())).thenReturn(List.of());
        when(selectionCallGenerator.selectPlaces(eq(vectorEnrichedInput), anyList(), any())).thenReturn(selectionOutput);
        when(routeOptimizer.schedule(any(), anyList(), any()))
                .thenReturn(new RouteOptimizer.ScheduleResult(fixedSteps, List.of()));
        when(indexResultMapper.toDraftItineraryData(eq(fixedSteps), eq("도쿄"), anyString(), anyList(), anyList()))
                .thenReturn(draftData);
        when(hardValidator.validate(draftData)).thenReturn(HardValidationResult.pass());
        Itinerary mockItinerary = mock(Itinerary.class);
        when(mockItinerary.getId()).thenReturn(43L);
        when(saveHelper.save(eq(draftData), any(EnrichedInput.class), eq(1L), eq(true), any())).thenReturn(mockItinerary);

        executor.execute("job-r", withRequired, 1L, emitter);

        ArgumentCaptor<List<PlaceCandidate>> scheduled = ArgumentCaptor.forClass(List.class);
        verify(routeOptimizer).schedule(any(), scheduled.capture(), any());
        PlaceCandidate attached = scheduled.getValue().stream()
                .filter(c -> "메이지신궁".equals(c.name())).findFirst().orElseThrow();
        assertThat(attached.intentProtected()).isTrue();
        assertThat(attached.intentTime()).isEqualTo("morning");
        assertThat(attached.userSelected()).isFalse();
        verify(traceRecorder).record(any(), eq("job-r"), eq(withRequired), any(), any(), any(),
                eq(scheduled.getValue()), any(), anyLong(), any());

        ArgumentCaptor<List<String>> notices = ArgumentCaptor.forClass(List.class);
        verify(indexResultMapper).toDraftItineraryData(eq(fixedSteps), eq("도쿄"), anyString(), anyList(),
                notices.capture());
        assertThat(notices.getValue()).contains("'오다이바 야경'은(는) 장소를 찾지 못해 일정에 넣지 못했어요.",
                "꼭 가고 싶다고 고른 '메이지신궁 아침'을(를) 일정에 넣지 못했어요.");
    }

    @Test
    @DisplayName("enrichInput 비현실적 입력: 에러 SSE 전송 후 파이프라인 중단")
    void execute_enrichInvalid_sendsErrorAndStops() {
        when(cancellationRegistry.isCancelled(anyString())).thenReturn(false);
        EnrichmentResult errorResult = EnrichmentResult.error("UNREALISTIC_BUDGET", "예산이 너무 적습니다.");
        when(optimizedClaudeAIService.enrichInput(request)).thenReturn(errorResult);

        executor.execute("job-2", request, 1L, emitter);

        verify(optimizedClaudeAIService).enrichInput(request);
        verify(vectorSearchQueryService, never()).search(any(), any());
        verify(selectionCallGenerator, never()).selectPlaces(any(), any(), any());
        verify(saveHelper, never()).save(any(), any(), anyLong(), anyBoolean(), any());
    }

    /** 끝난 스트림에 새 연결을 붙여 지금까지 보낸 이벤트 중 error 데이터를 꺼낸다. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> sentError() throws IOException {
        SseEmitter client = mock(SseEmitter.class);
        emitter.attach(client);
        ArgumentCaptor<Set<ResponseBodyEmitter.DataWithMediaType>> sent = ArgumentCaptor.forClass(Set.class);
        verify(client, atLeastOnce()).send(sent.capture());
        return sent.getAllValues().stream().flatMap(Set::stream)
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .filter(d -> d instanceof Map<?, ?> m && "error".equals(m.get("status")))
                .map(d -> (Map<String, Object>) d)
                .findFirst().orElseThrow();
    }

    private VectorEnrichedInput withCountry(String country, List<String> regions) {
        return withCountry(country, regions, vectorEnrichedInput.transportPref());
    }

    private VectorEnrichedInput withCountry(String country, List<String> regions, String transportPref) {
        VectorEnrichedInput v = vectorEnrichedInput;
        return new VectorEnrichedInput(v.destination(), v.themes(), v.categories(), v.pace(), transportPref,
                v.budget(), v.startDate(), v.endDate(), v.description(), v.selectedPlaceIds(),
                v.normalizedDestination(), country, regions, v.searchTags(), v.regionAllocation(), v.budgetRange(),
                v.seasonContext(), v.enrichedContext(), v.transportationHub(), v.categorySearchQueries());
    }

    @Test
    @DisplayName("Fallback 경로: 국내 카테고리 후보 부족 시 기존 executor로 위임")
    void execute_fallbackPath_delegatesToExistingExecutor() {
        VectorEnrichedInput korea = withCountry("KR", List.of("Busan"));
        when(cancellationRegistry.isCancelled(anyString())).thenReturn(false);
        when(optimizedClaudeAIService.enrichInput(request)).thenReturn(EnrichmentResult.success(korea));
        when(vectorSearchQueryService.search(any(), any())).thenReturn(candidates);
        when(fallbackDecider.assess(candidates, 4L)).thenReturn(FallbackDecider.PoolQuality.FALLBACK);

        executor.execute("job-3", request, 1L, emitter);

        verify(fallbackExecutor).execute(eq("job-3"), any(), eq(1L), eq(emitter));
        verify(selectionCallGenerator, never()).selectPlaces(any(), any(), any());
        verify(saveHelper, never()).save(any(), any(), anyLong(), anyBoolean(), any());
    }

    @Test
    @DisplayName("해외는 후보가 부족해도 fallback으로 가지 않고 장소 부족으로 끝낸다(근교 여행지면 상위 도시 안내)")
    void execute_overseasSparse_doesNotFallback() throws IOException {
        VectorEnrichedInput jiufen = withCountry("TW", List.of("Jiufen"));
        enableCountries("KR", "JP", "TW");
        when(cancellationRegistry.isCancelled(anyString())).thenReturn(false);
        when(optimizedClaudeAIService.enrichInput(request)).thenReturn(EnrichmentResult.success(jiufen));
        when(vectorSearchQueryService.search(any(), any())).thenReturn(candidates);
        when(fallbackDecider.assess(candidates, 4L)).thenReturn(FallbackDecider.PoolQuality.FALLBACK);

        executor.execute("job-tw", request, 1L, emitter);

        verify(fallbackExecutor, never()).execute(anyString(), any(), anyLong(), any());
        verify(generationPolicyService, never()).recordSuccess(anyLong());
        assertThat(sentError()).containsEntry("errorCode", "PLANNING_005")
                .containsEntry("message", "'지우펀'만으로는 장소가 적어 일정을 만들기 어려워요. '타이베이'까지 함께 넣어 다시 요청해 주세요.");
        assertThat(emitter.isFinished()).isTrue();
    }

    @Test
    @DisplayName("공개하지 않은 나라는 검색 전에 준비 중 안내로 끝내고 생성 한도를 쓰지 않는다")
    void execute_countryNotEnabled_stopsBeforeSearch() throws IOException {
        when(cancellationRegistry.isCancelled(anyString())).thenReturn(false);
        when(optimizedClaudeAIService.enrichInput(request))
                .thenReturn(EnrichmentResult.success(withCountry("CN", List.of("Shanghai"))));

        executor.execute("job-cn", request, 1L, emitter);

        verify(vectorSearchQueryService, never()).search(any(), any());
        verify(generationPolicyService, never()).recordSuccess(anyLong());
        verify(generationPolicyService, never()).recordValidationFailure(anyLong(), any());
        assertThat(sentError()).containsEntry("errorCode", "PLANNING_005")
                .containsEntry("message", "중국 여행지는 아직 준비 중이에요. 지금은 한국·일본 여행지로 일정을 만들 수 있어요.");
        assertThat(emitter.isFinished()).isTrue();
    }

    private void enableCountries(String... countries) {
        ReflectionTestUtils.setField(executor, "planningCountries", new PlanningCountries(List.of(countries),
                com.shg.trip.shgtrip.domain.place.destination.Destinations.fromClasspath()));
    }

    @Test
    @DisplayName("한국 면허로 운전할 수 없는 나라(중국·베트남)의 자동차 요청은 택시·차량 호출 기준이라고 알린다")
    void execute_carWhereKoreanLicenseInvalid_addsNotice() {
        enableCountries("KR", "JP", "CN");
        when(cancellationRegistry.isCancelled(anyString())).thenReturn(false);
        when(optimizedClaudeAIService.enrichInput(request))
                .thenReturn(EnrichmentResult.success(withCountry("CN", List.of("Shanghai"), "car")));
        when(vectorSearchQueryService.search(any(), any())).thenReturn(candidates);
        when(fallbackDecider.assess(candidates, 4L)).thenReturn(FallbackDecider.PoolQuality.SUFFICIENT);
        when(selectionCallGenerator.selectPlaces(any(), anyList(), any())).thenReturn(selectionOutput);
        when(routeOptimizer.schedule(any(), anyList(), any()))
                .thenReturn(new RouteOptimizer.ScheduleResult(fixedSteps, List.of()));

        executor.execute("job-car", request, 1L, emitter);

        verify(indexResultMapper).toDraftItineraryData(eq(fixedSteps), any(), anyString(), anyList(),
                argThat((List<String> notices) -> notices.contains(
                        "중국은 한국 운전면허로 운전할 수 없어 택시·차량 호출로 다니는 기준으로 짰어요.")));
        verify(vectorSearchQueryService).search(argThat(in -> "any".equals(in.transportPref())), any());
        verify(routeOptimizer).schedule(any(), anyList(), argThat((ScheduleArgs a) -> "any".equals(a.transportPref())));
    }

    @Test
    @DisplayName("관문이 있는 해외 일정은 FSQ 교통 허브 후보(공항 행·항공편·역)를 빼고 관문만 허브로 붙인다")
    void appendGateways_replacesTransitHubsWithGateways() {
        PlaceCandidate fsqKobe = new PlaceCandidate(1, 48707L, "神戸空港 (Kobe Airport - UKB/RJBE)", "x",
                "Travel and Transportation > Transport Hub > Airport > Airport Terminal", List.of(), "Kobe", "JP",
                new BigDecimal("34.6328"), new BigDecimal("135.2239"), null, null, 0.5);
        PlaceCandidate flight = new PlaceCandidate(2, 48708L, "JL2051 ITM→FUK", "x",
                "Travel and Transportation > Transport Hub > Airport", List.of(), "Kobe", "JP",
                new BigDecimal("34.791"), new BigDecimal("135.440"), null, null, 0.5);
        PlaceCandidate station = new PlaceCandidate(3, 48709L, "三ノ宮駅", "x",
                "Travel and Transportation > Transport Hub > Rail Station", List.of(), "Kobe", "JP",
                new BigDecimal("34.6946"), new BigDecimal("135.1955"), null, null, 0.5);
        PlaceCandidate sight = new PlaceCandidate(4, 48710L, "北野異人館街", "x",
                "Landmarks and Outdoors > Historic and Protected Site", List.of(), "Kobe", "JP",
                new BigDecimal("34.7003"), new BigDecimal("135.1901"), null, null, 0.5);
        com.shg.trip.shgtrip.domain.place.entity.Place gatewayKobe = com.shg.trip.shgtrip.domain.place.entity.Place.builder()
                .id(901L).name("Kobe Airport").address("Kobe Airport, Kobe")
                .latitude(new BigDecimal("34.6328")).longitude(new BigDecimal("135.2380"))
                .category("Travel and Transportation > Transport Hub > Airport").region("Kobe").country("JP")
                .source("gateway").placeRole("TRANSIT").build();
        when(placeRepository.findGatewaysByRegions(List.of("Kobe"))).thenReturn(List.of(gatewayKobe));

        List<PlaceCandidate> result = executor.appendGateways(List.of(fsqKobe, flight, station, sight), List.of("Kobe"));

        assertThat(result).extracting(PlaceCandidate::placeId).containsExactly(48710L, 901L);
        assertThat(result).extracting(PlaceCandidate::index).containsExactly(1, 2);
    }

    @Test
    @DisplayName("저장 단계 기준점은 여행지별 후보 중앙점이고, 표본이 모자라면 전체 중앙점 하나다")
    void regionCenters_perRegionMedian() {
        List<PlaceCandidate> tokyoOsaka = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tokyoOsaka.add(new PlaceCandidate(i + 1, (long) i + 1, "t" + i, "", "x", List.of(), "Tokyo", "JP",
                    BigDecimal.valueOf(35.68 + i * 0.01), BigDecimal.valueOf(139.76), null, null, 0.5));
            tokyoOsaka.add(new PlaceCandidate(i + 11, (long) i + 11, "o" + i, "", "x", List.of(), "Osaka", "JP",
                    BigDecimal.valueOf(34.69 + i * 0.01), BigDecimal.valueOf(135.50), null, null, 0.5));
        }

        List<double[]> centers = executor.regionCenters(tokyoOsaka);

        assertThat(centers).hasSize(2);
        assertThat(centers.get(0)[1]).isEqualTo(139.76);
        assertThat(centers.get(1)[1]).isEqualTo(135.50);
        assertThat(executor.regionCenters(tokyoOsaka.subList(0, 4))).hasSize(1);
        assertThat(executor.regionCenters(tokyoOsaka.subList(0, 2))).isEmpty();
    }

    @Test
    @DisplayName("자유입력 장소는 여러 여행지 중 가장 가까운 중심에서 재고, 모든 중심에서 멀면 찾지 못한 것으로 본다")
    void findCustomPlaceInDb_usesNearestOfManyCenters() {
        double[] tokyo = {35.68, 139.76};
        double[] osaka = {34.69, 135.50};
        com.shg.trip.shgtrip.domain.place.entity.Place dotonbori = com.shg.trip.shgtrip.domain.place.entity.Place.builder()
                .id(7L).name("道頓堀").nameKo("도톤보리").address("大阪市中央区")
                .latitude(new BigDecimal("34.6687")).longitude(new BigDecimal("135.5013"))
                .category("Landmarks and Outdoors").region("Osaka").country("JP").build();
        when(placeRepository.findActiveByNameLikeNearest(eq("도톤보리"), anyDouble(), anyDouble(), any()))
                .thenReturn(List.of(dotonbori));

        assertThat(executor.findCustomPlaceInDb("도톤보리", List.of(tokyo, osaka))).contains(dotonbori);
        assertThat(executor.findCustomPlaceInDb("도톤보리", List.of(tokyo))).isEmpty();
    }

    @Test
    @DisplayName("같은 장소의 출처별 행은 한국어 이름이 있는 행을 대표로 남긴다")
    void dedupe_prefersRowWithKoreanName() {
        PlaceCandidate fsq = new PlaceCandidate(1, 1L, "浅草寺", "東京都台東区浅草2-3-1",
                "Community and Government > Spiritual Center > Buddhist Temple", List.of(), "Tokyo", "JP",
                new BigDecimal("35.7148"), new BigDecimal("139.7967"), null, null, 0.5);
        PlaceCandidate wiki = new PlaceCandidate(2, 2L, "浅草寺", "浅草寺, Tokyo",
                "Community and Government > Spiritual Center > Buddhist Temple", List.of(), "Tokyo", "JP",
                new BigDecimal("35.7148"), new BigDecimal("139.7967"), null, null, 0.5, null, null, null, null,
                false, null, null, null, null, null, null, false, null, null, "센소지");

        assertThat(executor.dedupePhysicalCandidates(List.of(fsq, wiki)))
                .singleElement().extracting(PlaceCandidate::placeId).isEqualTo(2L);
    }

    @Test
    @DisplayName("Wikidata 명소 이름 뒤에 영어 이름만 붙은 가까운 같은 분류 행은 같은 장소로 보고 명소 행만 남긴다")
    void dedupe_dropsBilingualTwinOfWikidataItem() {
        PlaceCandidate item = twinCandidate(1, 11L, "饒河街觀光夜市", "Dining and Drinking > Night Market", 25.05017, 121.57264, 7);
        PlaceCandidate twin = twinCandidate(2, 12L, "饒河街觀光夜市 Raohe St. Night Market",
                "Dining and Drinking > Night Market", 25.05058, 121.57590, 0);
        PlaceCandidate dome = twinCandidate(3, 13L, "東京ドームシティ", "Arts and Entertainment > Theme Park", 35.7056, 139.7519, 9);
        PlaceCandidate domeTwin = twinCandidate(4, 14L, "東京ドームシティ (Tokyo Dome City)",
                "Arts and Entertainment > Theme Park", 35.7050, 139.7525, 0);
        PlaceCandidate hall = twinCandidate(5, 15L, "饒河街觀光夜市 美食區", "Dining and Drinking > Night Market", 25.0505, 121.5755, 0);

        assertThat(executor.dedupePhysicalCandidates(List.of(item, twin, dome, domeTwin, hall)))
                .extracting(PlaceCandidate::placeId).containsExactly(11L, 13L, 15L);
    }

    @Test
    @DisplayName("Wikidata 명소와 이름이 같은 항목 없는 행은 1km 안이면 같은 장소로 보고, 멀면 다른 장소로 둔다")
    void dedupe_dropsSameNameRowNearWikidataItem() {
        PlaceCandidate beach = twinCandidate(1, 31L, "My Khe Beach", "Landmarks and Outdoors > Beach", 16.0610, 108.2470, 7);
        PlaceCandidate beachRow = twinCandidate(2, 32L, "My Khe Beach", "Landmarks and Outdoors > Beach", 16.0565, 108.2480, 0);
        PlaceCandidate shrine = twinCandidate(3, 33L, "氷川神社", "Community and Government > Spiritual Center > Shrine",
                35.8925, 139.5015, 12);
        PlaceCandidate otherShrine = twinCandidate(4, 34L, "氷川神社", "Community and Government > Spiritual Center > Shrine",
                35.9012, 139.5184, 0);

        assertThat(executor.dedupePhysicalCandidates(List.of(beach, beachRow, shrine, otherShrine)))
                .extracting(PlaceCandidate::placeId).containsExactly(31L, 33L, 34L);
    }

    @Test
    @DisplayName("구역 명소 이름으로 시작하는 가게는 그 구역 안 장소라 이중 표기 중복으로 보지 않는다")
    void dedupe_keepsVenueInsideAreaLandmark() {
        PlaceCandidate area = twinCandidate(1, 21L, "アメリカ村", "Landmarks and Outdoors > Pedestrian Plaza", 34.6719, 135.4986, 8);
        PlaceCandidate club = twinCandidate(2, 22L, "アメリカ村 Club DROP",
                "Arts and Entertainment > Performing Arts Venue > Music Venue > Rock Club", 34.6722, 135.4990, 0);

        assertThat(executor.dedupePhysicalCandidates(List.of(area, club)))
                .extracting(PlaceCandidate::placeId).containsExactly(21L, 22L);
    }

    @Test
    @DisplayName("장소 항목과 그곳 박물관 기관 항목(故宫·故宫博物院)은 언어판이 많은 쪽만 남기고, 영어 이름 박물관은 그대로 둔다")
    void dedupe_dropsMuseumInstitutionOfSameSite() {
        PlaceCandidate palace = twinCandidate(1, 41L, "故宫", "Landmarks and Outdoors > Palace", 39.9163, 116.3972, 106);
        PlaceCandidate museum = twinCandidate(2, 42L, "故宫博物院", "Arts and Entertainment > Museum", 39.9180, 116.3970, 20);
        PlaceCandidate peace = twinCandidate(3, 43L, "Peace Memorial", "Landmarks and Outdoors > Monument", 34.3955, 132.4536, 30);
        PlaceCandidate peaceMuseum = twinCandidate(4, 44L, "Peace Memorial Museum", "Arts and Entertainment > Museum",
                34.3916, 132.4519, 25);

        assertThat(executor.dedupePhysicalCandidates(List.of(palace, museum, peace, peaceMuseum)))
                .extracting(PlaceCandidate::placeId).containsExactly(41L, 43L, 44L);
    }

    @Test
    @DisplayName("예비 후보 검색 중심은 그날 방문지만으로 잡아 먼 도시 식당에 끌려가지 않는다(바나힐)")
    void activityCentroid_ignoresFarMeals() {
        List<PlaceCandidate> pool = List.of(
                twinCandidate(1, 51L, "Golden Bridge", "Landmarks and Outdoors > Bridge", 15.9950, 107.9965, 25),
                twinCandidate(2, 52L, "Solar Castle", "Landmarks and Outdoors > Castle", 15.9970, 107.9880, 0),
                twinCandidate(3, 53L, "Bánh Mì Bà Ry", "Dining and Drinking > Sandwich Spot", 16.0600, 108.2200, 0),
                twinCandidate(4, 54L, "Mỳ Quảng Faifo", "Dining and Drinking > Restaurant", 15.8800, 108.3300, 0));
        SelectionOutput.DayPlan day = new SelectionOutput.DayPlan(2, null, List.of(1, 2, 3, 4), null, null);

        double[] center = OptimizedGenerationExecutor.activityCentroid(day, pool);

        assertThat(center[0]).isCloseTo(15.996, org.assertj.core.data.Offset.offset(0.01));
        assertThat(center[1]).isCloseTo(107.992, org.assertj.core.data.Offset.offset(0.01));
    }

    private static PlaceCandidate twinCandidate(int index, long id, String name, String category,
                                                double lat, double lng, int sitelinks) {
        return new PlaceCandidate(index, id, name, "x", category, List.of(), "Taipei", "TW",
                BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), null, null, 0.5, null, null, null, null,
                false, null, null, null, sitelinks, null, null, false, null, null, null);
    }

    @Test
    @DisplayName("관문 없는 근교 여행지는 상위 여행지의 관문을 붙인다(호이안 → 다낭)")
    void appendGateways_usesParentGatewaysForNearbyDestinations() {
        when(placeRepository.findGatewaysByRegions(List.of("Da Nang", "Taipei"))).thenReturn(List.of());

        executor.appendGateways(candidates, List.of("Hoi An", "Da Nang", "Taipei", "Jiufen"));

        verify(placeRepository).findGatewaysByRegions(List.of("Da Nang", "Taipei"));
    }

    @Test
    @DisplayName("관문 행이 DB에 남아 있어도 여행지 목록에서 뺀 관문(국내선 전용 공항)은 붙이지 않는다")
    void appendGateways_skipsGatewaysNotListed() {
        com.shg.trip.shgtrip.domain.place.entity.Place kix = com.shg.trip.shgtrip.domain.place.entity.Place.builder()
                .id(910L).name("Kansai International Airport").address("Kansai International Airport, Osaka")
                .latitude(new BigDecimal("34.432")).longitude(new BigDecimal("135.2304"))
                .category("Travel and Transportation > Transport Hub > Airport").region("Osaka").country("JP")
                .source("gateway").placeRole("TRANSIT").build();
        com.shg.trip.shgtrip.domain.place.entity.Place itami = com.shg.trip.shgtrip.domain.place.entity.Place.builder()
                .id(911L).name("Itami Airport").address("Itami Airport, Osaka")
                .latitude(new BigDecimal("34.7855")).longitude(new BigDecimal("135.4382"))
                .category("Travel and Transportation > Transport Hub > Airport").region("Osaka").country("JP")
                .source("gateway").placeRole("TRANSIT").build();
        when(placeRepository.findGatewaysByRegions(List.of("Osaka"))).thenReturn(List.of(kix, itami));

        List<PlaceCandidate> result = executor.appendGateways(candidates, List.of("Kyoto"));

        assertThat(result.subList(candidates.size(), result.size()))
                .extracting(PlaceCandidate::name).containsExactly("Kansai International Airport");
    }

    @Test
    @DisplayName("해외 여행지의 관문(공항)을 후보 끝에 붙이고, 이미 있으면 다시 넣지 않는다")
    void appendGateways_addsMissingGateways() {
        com.shg.trip.shgtrip.domain.place.entity.Place narita = com.shg.trip.shgtrip.domain.place.entity.Place.builder()
                .id(900L).name("Narita International Airport").address("Narita International Airport, Tokyo")
                .latitude(new BigDecimal("35.772")).longitude(new BigDecimal("140.3929"))
                .category("Travel and Transportation > Transport Hub > Airport").region("Tokyo").country("JP")
                .source("gateway").placeRole("TRANSIT").build();
        com.shg.trip.shgtrip.domain.place.entity.Place already = com.shg.trip.shgtrip.domain.place.entity.Place.builder()
                .id(100L).name("센소지").address("x").latitude(BigDecimal.ONE).longitude(BigDecimal.ONE)
                .category("관광").region("Tokyo").country("JP").source("gateway").build();
        when(placeRepository.findGatewaysByRegions(List.of("Tokyo"))).thenReturn(List.of(narita, already));

        List<PlaceCandidate> result = executor.appendGateways(candidates, List.of("Tokyo"));

        assertThat(result).hasSize(candidates.size() + 1);
        assertThat(result.get(result.size() - 1)).satisfies(c -> {
            assertThat(c.name()).isEqualTo("Narita International Airport");
            assertThat(c.index()).isEqualTo(candidates.size() + 1);
            assertThat(c.placeRole()).isEqualTo("TRANSIT");
        });
    }

    @Test
    @DisplayName("후보가 0개면 Sonnet(fallback) 호출 없이 PLANNING_005로 끝낸다")
    void execute_noCandidates_abortsBeforeFallback() {
        when(cancellationRegistry.isCancelled(anyString())).thenReturn(false);
        when(optimizedClaudeAIService.enrichInput(request)).thenReturn(successResult);
        when(vectorSearchQueryService.search(eq(vectorEnrichedInput), any())).thenReturn(List.of());

        executor.execute("job-empty", request, 1L, emitter);

        verify(fallbackExecutor, never()).execute(anyString(), any(), anyLong(), any());
        verify(fallbackDecider, never()).assess(any(), anyLong());
        verify(saveHelper, never()).save(any(), any(), anyLong(), anyBoolean(), any());
        verify(generationPolicyService, never()).recordSuccess(anyLong());
        assertThat(emitter.isFinished()).isTrue();
    }

    @Test
    @DisplayName("구조 검증 실패는 결정론적 버그로 간주 — 재시도 없이 로그만 남기고 그대로 저장 진행")
    void execute_hardValidationFails_logsButStillSaves() {
        when(cancellationRegistry.isCancelled(anyString())).thenReturn(false);
        when(optimizedClaudeAIService.enrichInput(request)).thenReturn(successResult);
        when(vectorSearchQueryService.search(eq(vectorEnrichedInput), any())).thenReturn(candidates);
        when(fallbackDecider.assess(candidates, 4L)).thenReturn(FallbackDecider.PoolQuality.SUFFICIENT);
        when(placeRepository.findAllById(anyList())).thenReturn(List.of());

        when(selectionCallGenerator.selectPlaces(eq(vectorEnrichedInput), anyList(), any())).thenReturn(selectionOutput);
        when(routeOptimizer.schedule(eq(selectionOutput), anyList(), argThat((ScheduleArgs a) -> "normal".equals(a.pace()) && "any".equals(a.transportPref()))))
                .thenReturn(new RouteOptimizer.ScheduleResult(fixedSteps, java.util.List.of()));
        when(indexResultMapper.toDraftItineraryData(eq(fixedSteps), eq("도쿄"), anyString(), anyList(), anyList()))
                .thenReturn(draftData);
        when(hardValidator.validate(draftData)).thenReturn(HardValidationResult.fail("일부 검증 경고"));

        Itinerary mockItinerary = mock(Itinerary.class);
        when(mockItinerary.getId()).thenReturn(99L);
        when(saveHelper.save(eq(draftData), any(EnrichedInput.class), eq(1L), eq(true), any())).thenReturn(mockItinerary);

        executor.execute("job-4", request, 1L, emitter);

        verify(saveHelper).save(eq(draftData), any(EnrichedInput.class), eq(1L), eq(true), any());
        verify(resultStore).save("job-4", 99L, 1L);
        verify(storyGenerationService).generateAndAttach(eq("job-4"), any(), eq(99L), any(), any(), any(), any());
    }

    @Test
    @DisplayName("취소된 작업: 즉시 중단")
    void execute_cancelled_stopsImmediately() {
        when(cancellationRegistry.isCancelled("job-6")).thenReturn(true);

        executor.execute("job-6", request, 1L, emitter);

        verify(optimizedClaudeAIService, never()).enrichInput(any());
        verify(vectorSearchQueryService, never()).search(any(), any());
        verify(saveHelper, never()).save(any(), any(), anyLong(), anyBoolean(), any());
    }

    @Test
    @DisplayName("BusinessException 발생 시 에러 SSE 전송")
    void execute_businessException_sendsErrorSse() {
        when(cancellationRegistry.isCancelled(anyString())).thenReturn(false);
        when(optimizedClaudeAIService.enrichInput(request))
                .thenThrow(new BusinessException(ErrorCode.AI_SERVICE_ERROR, "AI 서비스 오류"));

        executor.execute("job-7", request, 1L, emitter);

        verify(saveHelper, never()).save(any(), any(), anyLong(), anyBoolean(), any());
    }

    @Test
    @DisplayName("toLegacyEnrichedInput 변환: VectorEnrichedInput의 주요 필드가 EnrichedInput으로 정확히 매핑")
    void execute_savesWithCorrectLegacyInput() {
        when(cancellationRegistry.isCancelled(anyString())).thenReturn(false);
        when(optimizedClaudeAIService.enrichInput(request)).thenReturn(successResult);
        when(vectorSearchQueryService.search(eq(vectorEnrichedInput), any())).thenReturn(candidates);
        when(fallbackDecider.assess(candidates, 4L)).thenReturn(FallbackDecider.PoolQuality.SUFFICIENT);
        when(placeRepository.findAllById(anyList())).thenReturn(List.of());

        when(selectionCallGenerator.selectPlaces(eq(vectorEnrichedInput), anyList(), any())).thenReturn(selectionOutput);
        when(routeOptimizer.schedule(eq(selectionOutput), anyList(), argThat((ScheduleArgs a) -> "normal".equals(a.pace()) && "any".equals(a.transportPref()))))
                .thenReturn(new RouteOptimizer.ScheduleResult(fixedSteps, java.util.List.of()));
        when(indexResultMapper.toDraftItineraryData(eq(fixedSteps), eq("도쿄"), anyString(), anyList(), anyList()))
                .thenReturn(draftData);
        when(hardValidator.validate(draftData)).thenReturn(HardValidationResult.pass());

        Itinerary mockItinerary = mock(Itinerary.class);
        when(mockItinerary.getId()).thenReturn(50L);
        when(saveHelper.save(any(), any(EnrichedInput.class), eq(1L), eq(true), any())).thenReturn(mockItinerary);

        executor.execute("job-8", request, 1L, emitter);

        ArgumentCaptor<EnrichedInput> inputCaptor = ArgumentCaptor.forClass(EnrichedInput.class);
        verify(saveHelper).save(eq(draftData), inputCaptor.capture(), eq(1L), eq(true), any());

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

    @org.junit.jupiter.api.Nested
    @DisplayName("filterAccommodationsByGeography - 보완 숙소 좌표 sanity")
    class AccommodationGeoFilter {

        private com.shg.trip.shgtrip.domain.place.entity.Place hotel(String name, String region, double lat, double lng) {
            return com.shg.trip.shgtrip.domain.place.entity.Place.builder()
                    .name(name).region(region).country("KR")
                    .latitude(BigDecimal.valueOf(lat)).longitude(BigDecimal.valueOf(lng))
                    .category("Travel and Transportation > Lodging > Hotel")
                    .build();
        }

        private PlaceCandidate candidate(String region, double lat, double lng) {
            return new PlaceCandidate(0, 1L, "후보", "주소", "attraction", List.of(),
                    region, "KR", BigDecimal.valueOf(lat), BigDecimal.valueOf(lng),
                    "설명", BigDecimal.valueOf(4.0), 0.9, null, null);
        }

        private List<PlaceCandidate> jejuCandidates() {
            return List.of(
                    candidate("Jeju", 33.5063, 126.4931),
                    candidate("Jeju", 33.4587, 126.9426),
                    candidate("Jeju", 33.3940, 126.2396),
                    candidate("Jeju", 33.3617, 126.5292));
        }

        @Test
        @DisplayName("지역 중심점에서 100km 초과 숙소(오태깅)는 제외, 정상 제주 숙소는 보존")
        void removesOutlierHotelKeepsValid() {
            var validHotel = hotel("제주신라호텔", "Jeju", 33.2470, 126.4100);
            var outlierHotel = hotel("서울호텔(오태깅)", "Jeju", 37.5665, 126.9780); // 제주 region인데 서울 좌표

            List<com.shg.trip.shgtrip.domain.place.entity.Place> kept =
                    executor.filterAccommodationsByGeography(List.of(validHotel, outlierHotel), jejuCandidates());

            assertThat(kept).extracting(com.shg.trip.shgtrip.domain.place.entity.Place::getName)
                    .containsExactly("제주신라호텔");
        }

        @Test
        @DisplayName("같은 region 후보가 4개 미만이면 중심점 신뢰도 부족으로 필터하지 않고 통과")
        void skipsWhenSampleTooSmall() {
            var outlierHotel = hotel("서울호텔", "Jeju", 37.5665, 126.9780);
            List<PlaceCandidate> few = List.of(
                    candidate("Jeju", 33.5, 126.5),
                    candidate("Jeju", 33.4, 126.4));

            List<com.shg.trip.shgtrip.domain.place.entity.Place> kept =
                    executor.filterAccommodationsByGeography(List.of(outlierHotel), few);

            assertThat(kept).hasSize(1);
        }

        @Test
        @DisplayName("좌표가 없는 숙소는 판단 보류하고 보존")
        void keepsNullCoordinateHotel() {
            var noCoord = com.shg.trip.shgtrip.domain.place.entity.Place.builder()
                    .name("좌표없음호텔").region("Jeju").country("KR")
                    .category("Travel and Transportation > Lodging > Hotel").build();

            List<com.shg.trip.shgtrip.domain.place.entity.Place> kept =
                    executor.filterAccommodationsByGeography(List.of(noCoord), jejuCandidates());

            assertThat(kept).hasSize(1);
        }
    }

    @Test
    @DisplayName("덧붙인 후보 중 앞선 후보와 같은 위키데이터 항목은 빼고, 남은 꼬리 번호를 다시 매긴다")
    void dropsAppendedRowOfSameWikidataItem() {
        PlaceCandidate base = place(1, 10L, "경주 불국사 [유네스코 세계유산]");
        PlaceCandidate dup = place(2, 11L, "불국사 (佛國寺)");
        PlaceCandidate fresh = place(3, 12L, "석굴암");
        when(placeRepository.findAllById(anyList())).thenReturn(List.of(
                placeWithQid(10L, "Q408318"), placeWithQid(11L, "Q408318"), placeWithQid(12L, "Q483813")));

        List<PlaceCandidate> result = executor.dropAppendedSameWikidataItem(List.of(base, dup, fresh), 1);

        assertThat(result).extracting(PlaceCandidate::name).containsExactly("경주 불국사 [유네스코 세계유산]", "석굴암");
        assertThat(result).extracting(PlaceCandidate::index).containsExactly(1, 2);
    }

    private static PlaceCandidate place(int index, long placeId, String name) {
        return new PlaceCandidate(index, placeId, name, "경주", "Landmarks and Outdoors > Temple", List.of(),
                "Gyeongju", "KR", java.math.BigDecimal.valueOf(35.79), java.math.BigDecimal.valueOf(129.33),
                null, null, 0.8, null, null, null, null, false, null, null);
    }

    private static com.shg.trip.shgtrip.domain.place.entity.Place placeWithQid(long id, String qid) {
        com.shg.trip.shgtrip.domain.place.entity.Place p =
                com.shg.trip.shgtrip.domain.place.entity.Place.builder().id(id).name("x").build();
        p.markWikidataMatch(qid, 10);
        return p;
    }

    @Test
    @DisplayName("찾지 못한 입력 장소와 일정에서 빠진 필수 장소를 각각 안내한다")
    void mustVisitNotices() {
        assertThat(OptimizedGenerationExecutor.mustVisitNotices(List.of(), List.of())).isEmpty();
        assertThat(OptimizedGenerationExecutor.mustVisitNotices(List.of("없는장소"), List.of("도톤보리", "오사카성")))
                .containsExactly(
                        "'없는장소'은(는) 장소를 찾지 못해 일정에 넣지 못했어요.",
                        "꼭 가고 싶다고 고른 '도톤보리', '오사카성'을(를) 일정에 넣지 못했어요.");
    }
}
