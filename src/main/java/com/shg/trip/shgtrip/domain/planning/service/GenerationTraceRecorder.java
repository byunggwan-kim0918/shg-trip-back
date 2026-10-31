package com.shg.trip.shgtrip.domain.planning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.itinerary.entity.GenerationTrace;
import com.shg.trip.shgtrip.domain.itinerary.repository.GenerationTraceRepository;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.ScheduleArgs;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.global.config.AnthropicProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 측정용 기록이라 저장에 실패해도 생성은 그대로 성공시킨다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerationTraceRecorder {

    /** 필드 의미가 바뀔 때만 올린다. 필드 추가는 그대로 둔다. */
    static final int TRACE_VERSION = 1;

    private final GenerationTraceRepository traceRepository;
    private final ObjectMapper objectMapper;
    private final AnthropicProperties anthropicProperties;

    public void record(Long itineraryId, String jobId, ItineraryGenerateRequest request,
                       VectorEnrichedInput enrichedInput, SelectionOutput rawSelection,
                       SelectionOutput finalSelection, List<PlaceCandidate> candidates,
                       ScheduleArgs args, long totalMs) {
        try {
            traceRepository.save(GenerationTrace.builder()
                    .itineraryId(itineraryId)
                    .jobId(jobId)
                    .traceVersion(TRACE_VERSION)
                    .request(json(request))
                    .enrichedInput(json(enrichedInput))
                    .selectionRaw(json(rawSelection))
                    .selectionFinal(json(finalSelection))
                    .candidates(json(candidates))
                    .scheduleArgs(json(args))
                    .models(json(Map.of("haiku", anthropicProperties.haiku(),
                            "sonnet", anthropicProperties.sonnet())))
                    .totalMs(totalMs)
                    .build());
        } catch (Exception e) {
            log.warn("생성 기록 저장 실패 (itineraryId={}, jobId={}): {}", itineraryId, jobId, e.getMessage());
        }
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
