package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.enrich.PromotedPlaceEnricher;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.dto.AssemblyItineraryOutput;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.domain.planning.service.ai.AssemblyCallGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StoryGenerationServiceTest {

    private static final Duration WAIT = Duration.ofSeconds(8);

    @Mock
    private AssemblyCallGenerator assemblyCallGenerator;
    @Mock
    private StorySaveHelper storySaveHelper;
    @Mock
    private CancellationRegistry cancellationRegistry;
    @Mock
    private PromotedPlaceEnricher placeEnricher;
    @Mock
    private PlaceRepository placeRepository;

    private StoryGenerationService service;
    private GenerationStream emitter;
    private List<StepData> fixedSteps;
    private VectorEnrichedInput enrichedInput;

    @BeforeEach
    void setUp() {
        service = new StoryGenerationService(assemblyCallGenerator, storySaveHelper, cancellationRegistry,
                placeEnricher, placeRepository, WAIT);
        emitter = mock(GenerationStream.class);
        fixedSteps = List.of(new StepData(1, 1, "09:00", "11:00",
                new PlaceData("센소지", null, "관광", "아사쿠사", "일본"),
                List.of(), "WALK", 10, BigDecimal.ONE, BigDecimal.ZERO, null, BigDecimal.ZERO));
        enrichedInput = mock(VectorEnrichedInput.class);
    }

    @Test
    @DisplayName("취소된 job이면 Haiku 호출/저장 없이 emitter만 닫고 종료한다")
    void generateAndAttach_cancelledJob_skipsLlmCallAndSave() {
        when(cancellationRegistry.isCancelled("job-1")).thenReturn(true);

        service.generateAndAttach("job-1", emitter, 42L, fixedSteps, "제주 3박 4일", enrichedInput, "concept");

        verifyNoInteractions(assemblyCallGenerator);
        verifyNoInteractions(storySaveHelper);
        verify(emitter).complete();
    }

    @Test
    @DisplayName("취소되지 않은 job은 정상적으로 story를 생성하고 저장한다")
    void generateAndAttach_notCancelled_savesStoryAndSendsReady() throws java.io.IOException {
        when(cancellationRegistry.isCancelled("job-1")).thenReturn(false);
        AssemblyItineraryOutput storyOutput = new AssemblyItineraryOutput(
                "제목", List.of("태그"), List.of(new AssemblyItineraryOutput.StoryStep(1, "이야기")));
        when(assemblyCallGenerator.assembleItinerary(fixedSteps, "concept", enrichedInput))
                .thenReturn(storyOutput);

        service.generateAndAttach("job-1", emitter, 42L, fixedSteps, "제주 3박 4일", enrichedInput, "concept");

        // concept은 구조 저장 시점의 임시 제목이므로 그대로 넘어가야 한다 (조건부 제목 교체의 기준값)
        // 교체 비교 기준은 실제 저장된 임시 제목이어야 한다(concept이 아니다)
        verify(storySaveHelper).saveStory(42L, fixedSteps, storyOutput, "제주 3박 4일");
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
    }

    @Test
    @DisplayName("Haiku 호출 실패 시 story-failed를 보내고 emitter를 닫는다 (구조 일정은 영향 없음)")
    void generateAndAttach_llmFails_sendsStoryFailed() {
        when(cancellationRegistry.isCancelled("job-1")).thenReturn(false);
        when(assemblyCallGenerator.assembleItinerary(any(), any(), any()))
                .thenThrow(new RuntimeException("Haiku 호출 실패"));

        service.generateAndAttach("job-1", emitter, 42L, fixedSteps, "제주 3박 4일", enrichedInput, "concept");

        verifyNoInteractions(storySaveHelper);
        verify(emitter).complete();
    }

    @Test
    @DisplayName("보강을 기다려 마친 승격 장소는 한국어 이름·소개로 이야기를 쓰고, 그 스텝으로 저장한다")
    void generateAndAttach_waitsForPromotedPlaceEnrichment() {
        when(cancellationRegistry.isCancelled("job-1")).thenReturn(false);
        StepData promoted = new StepData(1, 1, "13:00", "14:30",
                new PlaceData("大須商店街", "名古屋市中区大須", "Retail > Shopping Mall", "Nagoya", "JP", null, null,
                        3050028L, null),
                List.of(), "WALK", 10, BigDecimal.ONE, BigDecimal.ZERO, null, BigDecimal.ZERO);
        when(placeEnricher.awaitEnriched(Set.of(3050028L), WAIT)).thenReturn(Set.of(3050028L));
        Place enriched = Place.builder().id(3050028L).name("大須商店街").nameKo("오스 상점가")
                .description("나고야 대표 상점가로 전자상가와 노포가 섞여 있다").enrichedAt(OffsetDateTime.now()).build();
        when(placeRepository.findAllById(Set.of(3050028L))).thenReturn(List.of(enriched));
        AssemblyItineraryOutput storyOutput = new AssemblyItineraryOutput(
                "제목", List.of(), List.of(new AssemblyItineraryOutput.StoryStep(1, "이야기")));
        when(assemblyCallGenerator.assembleItinerary(any(), any(), any())).thenReturn(storyOutput);

        service.generateAndAttach("job-1", emitter, 42L, List.of(promoted), "나고야 1박 2일", enrichedInput, "concept");

        verify(assemblyCallGenerator).assembleItinerary(argThat(steps -> {
            PlaceData place = steps.get(0).place();
            return "오스 상점가".equals(place.displayName()) && place.description() != null;
        }), any(), any());
        verify(storySaveHelper).saveStory(eq(42L), argThat(steps -> "오스 상점가".equals(steps.get(0).place().nameKo())),
                eq(storyOutput), eq("나고야 1박 2일"));
    }

    @Test
    @DisplayName("기다리는 동안 보강을 마치지 못한 장소는 원래 이름으로 이야기를 쓴다")
    void generateAndAttach_unfinishedEnrichmentKeepsOriginalName() {
        when(cancellationRegistry.isCancelled("job-1")).thenReturn(false);
        StepData promoted = osuStep();
        when(placeEnricher.awaitEnriched(Set.of(3050028L), WAIT)).thenReturn(Set.of());
        when(placeRepository.findAllById(Set.of(3050028L))).thenReturn(List.of(
                Place.builder().id(3050028L).name("大須商店街").build()));
        when(assemblyCallGenerator.assembleItinerary(any(), any(), any())).thenReturn(new AssemblyItineraryOutput(
                "제목", List.of(), List.of()));

        service.generateAndAttach("job-1", emitter, 42L, List.of(promoted), "나고야 1박 2일", enrichedInput, "concept");

        verify(assemblyCallGenerator).assembleItinerary(argThat(steps -> {
            assertThat(steps.get(0).place().displayName()).isEqualTo("大須商店街");
            return true;
        }), any(), any());
    }

    @Test
    @DisplayName("이야기 단계보다 먼저 끝난 보강도 DB에서 다시 읽어 이야기에 쓴다")
    void generateAndAttach_readsEnrichmentFinishedBeforeStory() {
        when(cancellationRegistry.isCancelled("job-1")).thenReturn(false);
        when(placeEnricher.awaitEnriched(Set.of(3050028L), WAIT)).thenReturn(Set.of());
        when(placeRepository.findAllById(Set.of(3050028L))).thenReturn(List.of(Place.builder().id(3050028L)
                .name("大須商店街").nameKo("오스 상점가").description("상점가").enrichedAt(OffsetDateTime.now()).build()));
        when(assemblyCallGenerator.assembleItinerary(any(), any(), any())).thenReturn(new AssemblyItineraryOutput(
                "제목", List.of(), List.of()));

        service.generateAndAttach("job-1", emitter, 42L, List.of(osuStep()), "나고야 1박 2일", enrichedInput, "concept");

        verify(assemblyCallGenerator).assembleItinerary(
                argThat(steps -> "오스 상점가".equals(steps.get(0).place().displayName())), any(), any());
    }

    @Test
    @DisplayName("보강 대기가 시간 초과로 끝나도 그 사이 취소됐으면 Haiku를 부르지 않는다")
    void generateAndAttach_cancelledDuringTimedOutWait() {
        when(cancellationRegistry.isCancelled("job-1")).thenReturn(false, true);
        when(placeEnricher.awaitEnriched(Set.of(3050028L), WAIT)).thenReturn(Set.of());
        when(placeRepository.findAllById(Set.of(3050028L))).thenReturn(List.of());

        service.generateAndAttach("job-1", emitter, 42L, List.of(osuStep()), "나고야 1박 2일", enrichedInput, "concept");

        verifyNoInteractions(assemblyCallGenerator);
        verify(emitter).complete();
    }

    private static StepData osuStep() {
        return new StepData(1, 1, "13:00", "14:30",
                new PlaceData("大須商店街", null, "Retail > Shopping Mall", "Nagoya", "JP", null, null, 3050028L, null),
                List.of(), "WALK", 10, BigDecimal.ONE, BigDecimal.ZERO, null, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("보강 대기가 실패해도 이야기는 원래 스텝으로 쓴다")
    void generateAndAttach_enrichWaitFailureIsSoft() {
        when(cancellationRegistry.isCancelled("job-1")).thenReturn(false);
        when(placeEnricher.awaitEnriched(any(), any())).thenThrow(new IllegalStateException("boom"));
        List<StepData> steps = List.of(osuStep());
        AssemblyItineraryOutput storyOutput = new AssemblyItineraryOutput("제목", List.of(), List.of());
        when(assemblyCallGenerator.assembleItinerary(steps, "concept", enrichedInput)).thenReturn(storyOutput);

        service.generateAndAttach("job-1", emitter, 42L, steps, "제주 3박 4일", enrichedInput, "concept");

        verify(storySaveHelper).saveStory(42L, steps, storyOutput, "제주 3박 4일");
    }

    @Test
    @DisplayName("보강을 기다리는 사이 취소되면 Haiku를 부르지 않는다")
    void generateAndAttach_cancelledWhileWaiting() {
        when(cancellationRegistry.isCancelled("job-1")).thenReturn(false, true);
        StepData promoted = new StepData(1, 1, "13:00", "14:30",
                new PlaceData("大須商店街", null, "Retail > Shopping Mall", "Nagoya", "JP", null, null, 3050028L, null),
                List.of(), "WALK", 10, BigDecimal.ONE, BigDecimal.ZERO, null, BigDecimal.ZERO);
        when(placeEnricher.awaitEnriched(Set.of(3050028L), WAIT)).thenReturn(Set.of(3050028L));
        when(placeRepository.findAllById(Set.of(3050028L))).thenReturn(List.of(
                Place.builder().id(3050028L).name("大須商店街").nameKo("오스 상점가").enrichedAt(OffsetDateTime.now()).build()));

        service.generateAndAttach("job-1", emitter, 42L, List.of(promoted), "나고야 1박 2일", enrichedInput, "concept");

        verifyNoInteractions(assemblyCallGenerator);
        verify(emitter).complete();
    }
}
