package com.shg.trip.shgtrip.domain.planning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.itinerary.entity.GenerationTrace;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;
import com.shg.trip.shgtrip.domain.itinerary.repository.GenerationTraceRepository;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.ScheduleArgs;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.domain.user.service.ActiveUserGuard;
import com.shg.trip.shgtrip.global.config.AnthropicProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.LinkedHashMap;
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
    private final ActiveUserGuard activeUserGuard;
    private final TransactionTemplate transactionTemplate;

    public void record(Itinerary saved, String jobId, ItineraryGenerateRequest request,
                       VectorEnrichedInput enrichedInput, SelectionOutput rawSelection,
                       SelectionOutput finalSelection, List<PlaceCandidate> candidates,
                       ScheduleArgs args, long totalMs, ItineraryRules.Verdict quality) {
        Long itineraryId = saved.getId();
        try {
            GenerationTrace trace = GenerationTrace.builder()
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
                    .score(scoreJson(quality, itineraryId))
                    .generatedSteps(snapshotJson(saved))
                    .build();
            // 요청 문장이 들어 있어 탈퇴가 trace를 지운 뒤에 들어가면 안 된다.
            transactionTemplate.executeWithoutResult(status -> {
                activeUserGuard.lockActive(saved.getUserId());
                traceRepository.save(trace);
            });
        } catch (Exception e) {
            log.warn("생성 기록 저장 실패 (itineraryId={}, jobId={}): {}", itineraryId, jobId, e.getMessage());
        }
    }

    /** 판정 결과만 실패해도 나머지 기록은 남긴다. */
    private String scoreJson(ItineraryRules.Verdict quality, Long itineraryId) {
        if (quality == null) return null;
        try {
            return json(quality);
        } catch (Exception e) {
            log.warn("품질 판정 기록 실패 (itineraryId={}): {}", itineraryId, e.getMessage());
            return null;
        }
    }

    /** 스냅샷만 실패해도 나머지 기록은 남긴다. */
    private String snapshotJson(Itinerary saved) {
        try {
            return json(stepSnapshot(saved));
        } catch (Exception e) {
            log.warn("생성 직후 스텝 기록 실패 (itineraryId={}): {}", saved.getId(), e.getMessage());
            return null;
        }
    }

    static List<Map<String, Object>> stepSnapshot(Itinerary itinerary) {
        return itinerary.getSteps().stream()
                .sorted(Comparator.comparingInt(ItineraryStep::getDayNumber)
                        .thenComparingInt(ItineraryStep::getStepOrder))
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("stepId", s.getId());
                    m.put("day", s.getDayNumber());
                    m.put("order", s.getStepOrder());
                    m.put("placeId", s.getPlace() == null ? null : s.getPlace().getId());
                    m.put("start", s.getStartTime());
                    m.put("end", s.getEndTime());
                    m.put("alternatives", s.getAlternatives().stream()
                            .map(a -> a.getPlace() == null ? null : a.getPlace().getId()).toList());
                    return m;
                })
                .toList();
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
