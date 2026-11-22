package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.place.destination.DomesticCities;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.dto.DraftPlan;
import com.shg.trip.shgtrip.domain.planning.dto.EnrichmentResult;
import com.shg.trip.shgtrip.domain.planning.dto.ItineraryData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.TripConditions;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.domain.planning.service.ai.DraftCallGenerator;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TripGenerationExecutorTest {

    @Mock private OptimizedClaudeAIService enrichService;
    @Mock private DraftRequestFactory requestFactory;
    @Mock private TripPlanner tripPlanner;
    @Mock private ItinerarySaveHelper saveHelper;
    @Mock private StoryGenerationService storyGenerationService;
    @Mock private GenerationResultStore resultStore;
    @Mock private CancellationRegistry cancellationRegistry;
    @Mock private GenerationPolicyService generationPolicyService;
    @Mock private PlaceRepository placeRepository;
    @Mock private GenerationTraceRecorder traceRecorder;
    @Mock private PlanningCountries planningCountries;

    @InjectMocks
    private TripGenerationExecutor executor;

    private final List<String> events = new ArrayList<>();
    private GenerationStream stream;
    private ItineraryGenerateRequest request;
    private VectorEnrichedInput input;
    private DraftItineraryPlanner.Request base;
    private DraftCallGenerator.Drafted drafted;
    private TripPlanner.Planned defaultPlanned;

    @BeforeEach
    void setUp() {
        stream = new GenerationStream("job", () -> { }, new GenerationStream.Mirror() {
            @Override
            public void append(Set<ResponseBodyEmitter.DataWithMediaType> items) {
                StringBuilder sb = new StringBuilder();
                for (ResponseBodyEmitter.DataWithMediaType item : items) sb.append(item.getData());
                events.add(sb.toString());
            }

            @Override
            public void end() {
            }
        });
        request = new ItineraryGenerateRequest(ItineraryGenerateRequest.PlanningMode.AUTO, "부산", List.of("food"),
                List.of("restaurant"), "normal", "any", new BigDecimal("500000"), LocalDate.of(2027, 5, 1),
                LocalDate.of(2027, 5, 2), "부산 여행", null, null, "couple", 2, null, "15:00");
        input = new VectorEnrichedInput("부산", List.of("food"), List.of("restaurant"), "normal", "any",
                new BigDecimal("500000"), LocalDate.of(2027, 5, 1), LocalDate.of(2027, 5, 2), "부산 여행", List.of(),
                "부산", "KR", List.of("Busan"), List.of("맛집"), Map.of(), null, null, null, null, Map.of(),
                VectorEnrichedInput.CompanionType.COUPLE, 2, List.of());
        base = new DraftItineraryPlanner.Request(input, null, List.of(DomesticCities.find("부산").orElseThrow()),
                null, LocalTime.of(15, 0), null, null, List.of(), List.of(), Map.of(), null, null, Map.of(),
                Set.of(), List.of());
        drafted = new DraftCallGenerator.Drafted(new DraftPlan("바다 여행", List.of(), List.of()), 100, 50);

        when(planningCountries.isEnabled(any())).thenReturn(true);
        when(enrichService.enrichInput(any())).thenReturn(EnrichmentResult.success(input));
        when(requestFactory.prepare(any(), any())).thenReturn(new DraftRequestFactory.Prepared(base, input,
                List.of("고른 장소 중 1곳은 찾을 수 없어 일정에서 뺐어요.")));
        when(tripPlanner.draft(any(), anyString())).thenReturn(drafted);
        defaultPlanned = planned(defaultSteps());
        when(tripPlanner.finish(any(), anyString(), any())).thenReturn(defaultPlanned);
    }

    @Test
    @DisplayName("성공하면 화면이 아는 단계 이름 순서로 진행을 보내고 저장 뒤 complete를 보낸 채 이야기를 이어 간다")
    void success() {
        Itinerary saved = savedItinerary();
        when(saveHelper.save(any(), any(), anyLong(), any(), any()))
                .thenReturn(new ItinerarySaveHelper.Saved(saved, null));

        executor.execute("job", request, 1L, stream);

        assertThat(stages()).containsExactly("ENRICHING", "SEARCHING", "SELECTING", "OPTIMIZING", "VALIDATING",
                "SAVING", "COMPLETE");
        assertThat(String.join("\n", events)).contains("event:step-stream").contains("event:complete")
                .doesNotContain("event:error");
        int stepStream = indexOf("event:step-stream");
        assertThat(stepStream).isGreaterThan(indexOf("stage=OPTIMIZING")).isLessThan(indexOf("event:complete"));
        verify(resultStore).save("job", 7L, 1L);
        verify(generationPolicyService).recordSuccess(1L);
        verify(generationPolicyService, never()).recordUnbilled(anyLong());
        verify(storyGenerationService).generateAndAttach(eq("job"), eq(stream), eq(7L), any(), eq("부산 1박 2일"),
                eq(input), eq("바다 여행"));
        verify(traceRecorder).recordTrip(eq(saved), eq(1L), eq("job"), eq(request), eq(input), any(), isNull(),
                isNull());
    }

    @Test
    @DisplayName("저장할 일정은 인원수만큼 1인 비용을 곱하고, 판정 입력은 짓기 단계가 만든 것을 그대로 쓴다")
    void savedDataUsesPartyCostsAndPlannerContext() {
        Itinerary saved = savedItinerary();
        when(saveHelper.save(any(), any(), anyLong(), any(), any()))
                .thenReturn(new ItinerarySaveHelper.Saved(saved, null));

        executor.execute("job", request, 1L, stream);

        ArgumentCaptor<ItineraryData> data = ArgumentCaptor.forClass(ItineraryData.class);
        ArgumentCaptor<QualityGate.PlanContext> context = ArgumentCaptor.forClass(QualityGate.PlanContext.class);
        verify(saveHelper).save(data.capture(), any(), eq(1L), any(), context.capture());
        StepData meal = data.getValue().steps().get(0);
        assertThat(meal.estimatedCost()).isEqualByComparingTo("30000");
        assertThat(meal.notes()).isEqualTo("바다를 보며 먹을 수 있어요");
        assertThat(context.getValue().rules()).isSameAs(defaultPlanned.built().context());
        assertThat(context.getValue().fixedNotices()).contains("고른 장소 중 1곳은 찾을 수 없어 일정에서 뺐어요.",
                "요청하신 '야경'은(는) 일정에 넣지 못했어요.");
    }

    @Test
    @DisplayName("입력 보강이 거부하면 실패 횟수만 세고 초안을 부르지 않는다")
    void enrichRejected() {
        when(enrichService.enrichInput(any())).thenReturn(EnrichmentResult.error("INVALID_DESTINATION", "여행지를 알 수 없어요"));

        executor.execute("job", request, 1L, stream);

        assertThat(lastEvent()).contains("event:error").contains("INVALID_DESTINATION");
        verify(generationPolicyService).recordValidationFailure(1L, "INVALID_DESTINATION");
        verifyNoInteractions(tripPlanner, saveHelper);
    }

    @Test
    @DisplayName("열지 않은 나라는 장소 부족 오류로 끝나고 실패 횟수에 세지 않는다")
    void countryNotEnabled() {
        when(planningCountries.isEnabled(any())).thenReturn(false);
        when(planningCountries.notEnabledMessage(any())).thenReturn("아직 준비 중이에요.");

        executor.execute("job", request, 1L, stream);

        assertThat(lastEvent()).contains(ErrorCode.INSUFFICIENT_PLACE_DATA.getCode()).contains("아직 준비 중이에요.");
        verify(generationPolicyService, never()).recordValidationFailure(anyLong(), any());
        verifyNoInteractions(tripPlanner);
    }

    @Test
    @DisplayName("초안 모델 호출이 SDK 예외로 실패하면 AI 오류로 알리고 생성 횟수를 쓰지 않는다")
    void draftFailure() {
        when(tripPlanner.draft(any(), anyString())).thenThrow(new IllegalStateException("overloaded"));

        executor.execute("job", request, 1L, stream);

        assertThat(lastEvent()).contains("event:error").contains(ErrorCode.AI_SERVICE_ERROR.getCode());
        verify(generationPolicyService, never()).recordSuccess(anyLong());
        verify(generationPolicyService, never()).recordUnbilled(anyLong());
        verifyNoInteractions(saveHelper);
    }

    @Test
    @DisplayName("짓기에서 예외가 나면 초안을 기록에 남기고 저장하지 않는다")
    void buildFailure() {
        when(tripPlanner.finish(any(), anyString(), any())).thenThrow(new IllegalStateException("bug"));

        executor.execute("job", request, 1L, stream);

        assertThat(lastEvent()).contains("event:error").contains("UNEXPECTED_ERROR");
        ArgumentCaptor<GenerationTraceRecorder.TripRun> run = ArgumentCaptor.forClass(GenerationTraceRecorder.TripRun.class);
        verify(traceRecorder).recordTrip(isNull(), eq(1L), eq("job"), eq(request), eq(input), run.capture(), isNull(),
                notNull());
        assertThat(run.getValue().draft()).isEqualTo(drafted.plan());
        verifyNoInteractions(saveHelper);
        verify(generationPolicyService, never()).recordSuccess(anyLong());
    }

    @Test
    @DisplayName("저장 관문이 거부하면 관문 오류로 알리고 미차감 생성으로 센다")
    void qualityGateRejected() {
        ItineraryRules.Verdict hard = new ItineraryRules.Verdict(ItineraryRules.VERSION, List.of(
                new ItineraryRules.Violation(ItineraryRules.Rule.DAY_MISSING, 2, "day2 없음", List.of())), 0, 0);
        when(saveHelper.save(any(), any(), anyLong(), any(), any()))
                .thenThrow(new QualityGate.Rejected(hard));

        executor.execute("job", request, 1L, stream);

        assertThat(lastEvent()).contains("event:error").contains(ErrorCode.QUALITY_GATE_FAILED.getCode());
        verify(generationPolicyService).recordUnbilled(1L);
        verify(generationPolicyService, never()).recordSuccess(anyLong());
        verify(traceRecorder).recordTrip(isNull(), eq(1L), eq("job"), eq(request), eq(input), any(), eq(hard),
                isNull());
        verifyNoInteractions(resultStore, storyGenerationService);
    }

    @Test
    @DisplayName("짓기 결과에 스텝이 없으면 빈 일정을 저장하지 않고 장소 부족으로 끝낸다")
    void emptyResult() {
        when(tripPlanner.finish(any(), anyString(), any())).thenReturn(planned(List.of()));

        executor.execute("job", request, 1L, stream);

        assertThat(lastEvent()).contains(ErrorCode.INSUFFICIENT_PLACE_DATA.getCode());
        verifyNoInteractions(saveHelper);
        verify(generationPolicyService, never()).recordSuccess(anyLong());
    }

    @Test
    @DisplayName("시각이 겹친 일정은 저장하지 않고 생성 횟수 없이 관문 실패로 끝낸다")
    void brokenStructureRejected() {
        StepData lunch = defaultSteps().get(0);
        StepData overlapping = new StepData(2, 1, "12:30", "13:30", defaultSteps().get(1).place(), List.of(),
                null, null, null, null, null, null);
        when(tripPlanner.finish(any(), anyString(), any())).thenReturn(planned(List.of(lunch, overlapping)));

        executor.execute("job", request, 1L, stream);

        assertThat(lastEvent()).contains(ErrorCode.QUALITY_GATE_FAILED.getCode());
        verifyNoInteractions(saveHelper);
        verify(generationPolicyService).recordUnbilled(1L);
        verify(generationPolicyService, never()).recordSuccess(anyLong());
    }

    @Test
    @DisplayName("취소된 작업은 다음 단계로 넘어가지 않는다")
    void cancelledAfterEnrich() {
        when(cancellationRegistry.isCancelled("job")).thenReturn(false, true);

        executor.execute("job", request, 1L, stream);

        assertThat(stages()).containsExactly("ENRICHING");
        verifyNoInteractions(tripPlanner, saveHelper);
    }

    @Test
    @DisplayName("요청 변환이 업무 오류로 막으면 그 코드로 알린다")
    void prepareRejected() {
        when(requestFactory.prepare(any(), any()))
                .thenThrow(new BusinessException(ErrorCode.PLACE_REGION_MISMATCH, "선택한 장소가 여행지와 다른 지역입니다."));

        executor.execute("job", request, 1L, stream);

        assertThat(lastEvent()).contains(ErrorCode.PLACE_REGION_MISMATCH.getCode());
        verifyNoInteractions(tripPlanner);
    }

    private TripPlanner.Planned planned(List<StepData> steps) {
        List<PlaceCandidate> candidates = List.of(candidate(1, 11L, "해운대 횟집", "Dining and Drinking > Restaurant", "MEAL"),
                candidate(2, 12L, "부산역", "Travel and Transportation > Rail Station", "TRANSIT"));
        DraftItineraryPlanner.Result result = new DraftItineraryPlanner.Result(steps, candidates, List.of(), List.of(),
                List.of(), Map.of(11L, "바다를 보며 먹을 수 있어요"));
        TripSkeletonPlanner.Skeleton skeleton = new TripSkeletonPlanner.Skeleton(base, List.of(), List.of(),
                new TripConditions(List.of(), Map.of(), List.of(), false, "any", null, LocalTime.of(15, 0), "normal"));
        TripPlanner.Built built = new TripPlanner.Built(skeleton, result, TripPlanner.ruleContext(skeleton, result),
                Map.of(), List.of(), Map.of(), List.of("요청하신 '야경'은(는) 일정에 넣지 못했어요."));
        return new TripPlanner.Planned(drafted.plan(), List.of(), List.of(), built, 100, 50);
    }

    private static List<StepData> defaultSteps() {
        return List.of(
                new StepData(1, 1, "12:00", "13:00",
                        new PlaceData("해운대 횟집", "부산", "Dining and Drinking > Restaurant", "Busan", "KR", null,
                                null, 11L, null),
                        List.of(), null, null, null, null, null, new BigDecimal("15000")),
                new StepData(2, 2, "14:20", "14:50",
                        new PlaceData("부산역", "부산", "Travel and Transportation > Rail Station", "Busan", "KR", null,
                                null, 12L, null),
                        List.of(), "TRANSIT", 20, BigDecimal.ONE, null, null, null));
    }

    private static PlaceCandidate candidate(int index, Long id, String name, String category, String role) {
        return new PlaceCandidate(index, id, name, "부산", category, List.of(), "Busan", "KR",
                new BigDecimal("35.1"), new BigDecimal("129.0"), null, null, 1.0, null, null, null, null, false, null,
                null, null, null, role, null, false, null);
    }

    private static Itinerary savedItinerary() {
        Itinerary itinerary = mock(Itinerary.class);
        when(itinerary.getId()).thenReturn(7L);
        return itinerary;
    }

    private List<String> stages() {
        List<String> out = new ArrayList<>();
        for (String e : events) {
            if (!e.contains("event:progress")) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("stage=([A-Z]+)").matcher(e);
            if (m.find()) out.add(m.group(1));
        }
        return out;
    }

    private int indexOf(String text) {
        for (int i = 0; i < events.size(); i++) if (events.get(i).contains(text)) return i;
        return -1;
    }

    private String lastEvent() {
        return events.isEmpty() ? "" : events.get(events.size() - 1);
    }
}
