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
 * 구조 일정 저장(complete) 뒤 critical path 밖에서 Haiku story를 채운다.
 * OptimizedGenerationExecutor 안에 두면 @Async self-invocation이 프록시를 우회해 동기로 돈다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoryGenerationService {

    private final AssemblyCallGenerator assemblyCallGenerator;
    private final StorySaveHelper storySaveHelper;
    private final CancellationRegistry cancellationRegistry;

    /**
     * notes 컬럼만 직접 UPDATE해 Itinerary.@Version과 동시 편집이 충돌하지 않는다.
     * 성공·실패 모두 story-ready를 보낸 뒤 emitter를 닫아야 누수가 없다.
     * <p>
     * job 취소로 자동 중단되지 않으므로 LLM 호출 직전에 취소 여부를 다시 확인해 비용을 아낀다.
     */
    @Async("planningExecutor")
    public void generateAndAttach(String jobId, GenerationStream emitter, Long itineraryId, List<StepData> fixedSteps,
                                   String draftTitle, VectorEnrichedInput enrichedInput, String concept) {
        if (cancellationRegistry.isCancelled(jobId)) {
            log.info("Story 생성 스킵 - 취소된 job (jobId={}, itineraryId={})", jobId, itineraryId);
            emitter.complete();
            return;
        }
        try {
            AssemblyItineraryOutput storyOutput = assemblyCallGenerator.assembleItinerary(
                    fixedSteps, concept, enrichedInput);

            // 교체 비교 기준은 실제로 저장된 임시 제목이어야 한다.
            // concept을 넘기면 0행 업데이트가 되어 제목이 임시값으로 굳는다.
            storySaveHelper.saveStory(itineraryId, fixedSteps, storyOutput, draftTitle);

            sendStoryReady(emitter, itineraryId);
        } catch (Exception e) {
            log.error("비동기 story 생성 실패 (jobId={}, itineraryId={}): 구조 일정은 이미 저장됨", jobId, itineraryId, e);
            sendStoryFailed(emitter, itineraryId);
        }
    }

    private void sendStoryReady(GenerationStream emitter, Long itineraryId) {
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

    private void sendStoryFailed(GenerationStream emitter, Long itineraryId) {
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
