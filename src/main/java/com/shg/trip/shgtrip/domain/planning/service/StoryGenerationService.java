package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.AssemblyItineraryOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.domain.planning.service.ai.AssemblyCallGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 구조 일정 저장(complete) 이후, critical path 밖에서 비동기로 Haiku story를 채우는 전용 빈.
 * OptimizedGenerationExecutor 안에 두면 @Async self-invocation으로 인해 비동기가 적용되지
 * 않으므로(Spring AOP 프록시 우회) 별도 빈으로 분리한다 (ItinerarySaveHelper와 동일한 이유).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoryGenerationService {

    private final AssemblyCallGenerator assemblyCallGenerator;
    private final StorySaveHelper storySaveHelper;
    private final CancellationRegistry cancellationRegistry;

    /**
     * Haiku로 story를 생성하고, story(notes) 컬럼만 직접 UPDATE한다
     * (Itinerary.@Version을 건드리지 않아 동시 편집과 충돌하지 않음). 끝나면 story-ready를
     * 보내고서야 emitter를 닫는다(성공/실패 모두 반드시 닫아 emitter 누수 방지).
     * <p>
     * 구조 저장(complete emit)까지 끝난 뒤 같은 유저가 새 생성을 시작해 이 job이 취소돼도
     * 이 메서드는 별도로 시작되므로 자동으로 중단되지 않는다 — LLM 호출 직전에
     * jobId 취소 여부를 한 번 더 확인해 불필요한 비용을 줄인다(데이터 정합성과는 무관 —
     * itineraryId 기준으로 동작하므로 취소돼도 손상은 없음).
     */
    @Async("planningExecutor")
    public void generateAndAttach(String jobId, SseEmitter emitter, Long itineraryId, List<StepData> fixedSteps,
                                   String concept, VectorEnrichedInput enrichedInput) {
        if (cancellationRegistry.isCancelled(jobId)) {
            log.info("Story 생성 스킵 - 취소된 job (jobId={}, itineraryId={})", jobId, itineraryId);
            emitter.complete();
            return;
        }
        try {
            AssemblyItineraryOutput storyOutput = assemblyCallGenerator.assembleItinerary(
                    fixedSteps, concept, enrichedInput);

            storySaveHelper.saveStory(itineraryId, fixedSteps, storyOutput);

            sendStoryReady(emitter, itineraryId);
        } catch (Exception e) {
            log.error("비동기 story 생성 실패 (itineraryId={}): 구조 일정은 이미 저장됨", itineraryId, e);
            sendStoryFailed(emitter, itineraryId);
        }
    }

    private void sendStoryReady(SseEmitter emitter, Long itineraryId) {
        try {
            emitter.send(SseEmitter.event()
                    .name("story-ready")
                    .data(Map.of("status", "DONE", "itineraryId", itineraryId)));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE story-ready 이벤트 전송 실패: {}", e.getMessage());
        } finally {
            emitter.complete();
        }
    }

    private void sendStoryFailed(SseEmitter emitter, Long itineraryId) {
        try {
            emitter.send(SseEmitter.event()
                    .name("story-failed")
                    .data(Map.of("itineraryId", itineraryId)));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE story-failed 이벤트 전송 실패: {}", e.getMessage());
        } finally {
            emitter.complete();
        }
    }
}
