package com.shg.trip.shgtrip.domain.planning.service;

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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StoryGenerationServiceTest {

    @Mock
    private AssemblyCallGenerator assemblyCallGenerator;
    @Mock
    private StorySaveHelper storySaveHelper;
    @Mock
    private CancellationRegistry cancellationRegistry;

    private StoryGenerationService service;
    private SseEmitter emitter;
    private List<StepData> fixedSteps;
    private VectorEnrichedInput enrichedInput;

    @BeforeEach
    void setUp() {
        service = new StoryGenerationService(assemblyCallGenerator, storySaveHelper, cancellationRegistry);
        emitter = mock(SseEmitter.class);
        fixedSteps = List.of(new StepData(1, 1, "09:00", "11:00",
                new PlaceData("센소지", null, "관광", "아사쿠사", "일본"),
                List.of(), "WALK", 10, BigDecimal.ONE, BigDecimal.ZERO, null, BigDecimal.ZERO));
        enrichedInput = mock(VectorEnrichedInput.class);
    }

    @Test
    @DisplayName("취소된 job이면 Haiku 호출/저장 없이 emitter만 닫고 종료한다")
    void generateAndAttach_cancelledJob_skipsLlmCallAndSave() {
        when(cancellationRegistry.isCancelled("job-1")).thenReturn(true);

        service.generateAndAttach("job-1", emitter, 42L, fixedSteps, "concept", enrichedInput);

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

        service.generateAndAttach("job-1", emitter, 42L, fixedSteps, "concept", enrichedInput);

        verify(storySaveHelper).saveStory(42L, fixedSteps, storyOutput);
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
    }

    @Test
    @DisplayName("Haiku 호출 실패 시 story-failed를 보내고 emitter를 닫는다 (구조 일정은 영향 없음)")
    void generateAndAttach_llmFails_sendsStoryFailed() {
        when(cancellationRegistry.isCancelled("job-1")).thenReturn(false);
        when(assemblyCallGenerator.assembleItinerary(any(), any(), any()))
                .thenThrow(new RuntimeException("Haiku 호출 실패"));

        service.generateAndAttach("job-1", emitter, 42L, fixedSteps, "concept", enrichedInput);

        verifyNoInteractions(storySaveHelper);
        verify(emitter).complete();
    }
}
