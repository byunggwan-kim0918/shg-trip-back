package com.shg.trip.shgtrip.domain.planning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.itinerary.entity.GenerationTrace;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;
import com.shg.trip.shgtrip.domain.itinerary.repository.GenerationTraceRepository;
import com.shg.trip.shgtrip.domain.planning.dto.DraftPlan;
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

    /** 필드 의미가 바뀔 때만 올린다. */
    static final int TRIP_TRACE_VERSION = 2;
    static final String TRIP_PIPELINE = "trip";

    private final GenerationTraceRepository traceRepository;
    private final ObjectMapper objectMapper;
    private final AnthropicProperties anthropicProperties;
    private final ActiveUserGuard activeUserGuard;
    private final TransactionTemplate transactionTemplate;

    /**
     * @param draft   모델 초안
     * @param planned 짓기까지 끝났으면 편집·검사 결과. 짓기에서 예외가 났으면 null
     */
    public record TripRun(DraftItineraryPlanner.Request request, String prompt, DraftPlan draft,
                          TripPlanner.Planned planned, long totalMs) {
    }

    /**
     * @param saved        저장한 일정. 관문에서 막혀 저장하지 않았으면 null
     * @param quality      저장 관문 판정. 판정 전에 끝났으면 null
     * @param rejectReason 판정 밖의 실패 사유. 판정으로 끝났으면 null
     */
    public void recordTrip(Itinerary saved, Long userId, String jobId, ItineraryGenerateRequest request,
                           VectorEnrichedInput input, TripRun run, ItineraryRules.Verdict quality,
                           String rejectReason) {
        try {
            TripPlanner.Planned p = run.planned();
            Map<String, Object> outcome = new LinkedHashMap<>();
            outcome.put("problems", p == null ? List.of() : p.problems());
            outcome.put("edits", p == null ? List.of() : p.edits());
            outcome.put("violations", p == null ? List.of() : p.built().violations());
            outcome.put("tripNotes", p == null ? List.of() : p.built().tripNotes());
            outcome.put("notes", p == null ? List.of() : p.built().result().notes());
            DraftItineraryPlanner.Request r = run.request();
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("cities", r.cities().stream().map(c -> c.name()).toList());
            args.put("arrival", r.arrival());
            args.put("departure", r.departure());
            args.put("mustDo", r.mustDo());
            args.put("prompt", run.prompt());
            Map<String, Object> models = new LinkedHashMap<>();
            models.put("haiku", anthropicProperties.haiku());
            models.put("sonnet", anthropicProperties.sonnet());
            models.put("sonnetInputTokens", p == null ? null : p.inputTokens());
            models.put("sonnetOutputTokens", p == null ? null : p.outputTokens());
            GenerationTrace trace = GenerationTrace.builder()
                    .itineraryId(saved == null ? null : saved.getId())
                    .userId(userId)
                    .jobId(jobId)
                    .traceVersion(TRIP_TRACE_VERSION)
                    .request(json(request))
                    .enrichedInput(json(input))
                    // 칼럼 이름은 옛 기록과 같다. selection_raw는 초안, selection_final은 편집·검사, schedule_args는 초안 요청이다.
                    .selectionRaw(json(run.draft()))
                    .selectionFinal(json(outcome))
                    .candidates(json(p == null ? List.of() : p.built().result().candidates()))
                    .searchPoolSize(p == null ? null : p.built().result().candidates().size())
                    .scheduleArgs(json(args))
                    .models(json(models))
                    .totalMs(run.totalMs())
                    .score(scoreJson(quality, rejectReason, jobId))
                    .generatedSteps(saved == null ? null : snapshotJson(saved))
                    .pipeline(TRIP_PIPELINE)
                    .build();
            // 요청 문장이 들어 있어 탈퇴가 trace를 지운 뒤에 들어가면 안 된다.
            transactionTemplate.executeWithoutResult(status -> {
                activeUserGuard.lockActive(userId);
                traceRepository.save(trace);
            });
        } catch (Exception e) {
            log.warn("생성 기록 저장 실패 (jobId={}): {}", jobId, e.getMessage());
        }
    }

    /** 판정 결과만 실패해도 나머지 기록은 남긴다. */
    private String scoreJson(ItineraryRules.Verdict quality, String rejectReason, String jobId) {
        if (quality == null && rejectReason == null) return null;
        try {
            if (rejectReason == null) return json(quality);
            ObjectNode node = quality == null ? objectMapper.createObjectNode() : objectMapper.valueToTree(quality);
            node.put("rejectReason", rejectReason);
            return json(node);
        } catch (Exception e) {
            log.warn("품질 판정 기록 실패 (jobId={}): {}", jobId, e.getMessage());
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
