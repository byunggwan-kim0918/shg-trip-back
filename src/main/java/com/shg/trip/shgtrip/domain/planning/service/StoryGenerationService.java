package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.enrich.PromotedPlaceEnricher;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.dto.AssemblyItineraryOutput;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.domain.planning.service.ai.AssemblyCallGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 구조 일정 저장(complete) 뒤 critical path 밖에서 Haiku story를 채운다.
 * TripGenerationExecutor 안에 두면 @Async self-invocation이 프록시를 우회해 동기로 돈다.
 */
@Slf4j
@Component
public class StoryGenerationService {

    private final AssemblyCallGenerator assemblyCallGenerator;
    private final StorySaveHelper storySaveHelper;
    private final CancellationRegistry cancellationRegistry;
    private final PromotedPlaceEnricher placeEnricher;
    private final PlaceRepository placeRepository;
    /** 이야기가 승격 장소의 한국어 이름·소개를 쓰도록 그 보강을 기다리는 최대 시간. 화면은 이 동안 이야기 준비 중으로 보인다. */
    private final Duration enrichWait;

    public StoryGenerationService(AssemblyCallGenerator assemblyCallGenerator, StorySaveHelper storySaveHelper,
                                  CancellationRegistry cancellationRegistry, PromotedPlaceEnricher placeEnricher,
                                  PlaceRepository placeRepository,
                                  @Value("${place.promote-enrich.story-wait:PT8S}") Duration enrichWait) {
        this.assemblyCallGenerator = assemblyCallGenerator;
        this.storySaveHelper = storySaveHelper;
        this.cancellationRegistry = cancellationRegistry;
        this.placeEnricher = placeEnricher;
        this.placeRepository = placeRepository;
        this.enrichWait = enrichWait;
    }

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
            List<StepData> steps = withEnrichedPlaces(fixedSteps);
            if (cancellationRegistry.isCancelled(jobId)) {
                log.info("Story 생성 스킵 - 보강을 기다리는 사이 취소됨 (jobId={}, itineraryId={})", jobId, itineraryId);
                emitter.complete();
                return;
            }
            AssemblyItineraryOutput storyOutput = assemblyCallGenerator.assembleItinerary(
                    steps, concept, enrichedInput);

            // 교체 비교 기준은 실제로 저장된 임시 제목이어야 한다.
            // concept을 넘기면 0행 업데이트가 되어 제목이 임시값으로 굳는다.
            storySaveHelper.saveStory(itineraryId, steps, storyOutput, draftTitle);

            sendStoryReady(emitter, itineraryId);
        } catch (Exception e) {
            log.error("비동기 story 생성 실패 (jobId={}, itineraryId={}): 구조 일정은 이미 저장됨", jobId, itineraryId, e);
            sendStoryFailed(emitter, itineraryId);
        }
    }

    /**
     * 스텝은 보강 전 장소로 지어져, 이름·소개가 빈 장소는 진행 중인 보강을 기다린 뒤 DB에서 다시 읽는다. 보강은 대개 이야기
     * 단계보다 먼저 끝나 진행 중인 것만 보면 이미 보강된 장소를 놓친다.
     *
     * @return 보강된 장소만 이름·소개를 채운 스텝. 대기·조회가 실패해도 원래 스텝으로 이야기를 쓴다
     */
    private List<StepData> withEnrichedPlaces(List<StepData> steps) {
        try {
            Set<Long> missing = steps.stream().map(StepData::place)
                    .filter(p -> p != null && p.placeId() != null && (isBlank(p.nameKo()) || isBlank(p.description())))
                    .map(PlaceData::placeId).collect(Collectors.toSet());
            if (missing.isEmpty()) return steps;
            placeEnricher.awaitEnriched(missing, enrichWait);
            Map<Long, Place> fresh = placeRepository.findAllById(missing).stream()
                    .filter(p -> p.getEnrichedAt() != null)
                    .collect(Collectors.toMap(Place::getId, Function.identity()));
            if (fresh.isEmpty()) return steps;
            return steps.stream().map(s -> {
                Place p = s.place() == null ? null : fresh.get(s.place().placeId());
                return p == null ? s : s.withPlace(s.place().withEnrichment(p.getNameKo(), p.getDescription()));
            }).toList();
        } catch (RuntimeException e) {
            log.warn("승격 장소 보강 반영 실패, 원래 스텝으로 이야기 작성: {}", e.getMessage());
            return steps;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
