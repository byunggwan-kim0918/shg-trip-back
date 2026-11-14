package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.dto.ItineraryData;
import com.shg.trip.shgtrip.domain.planning.service.ai.AIService;
import com.shg.trip.shgtrip.domain.planning.service.validation.ItineraryValidationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ItineraryGenerationExecutorTest {

    @Mock private AIService aiService;
    @Mock private ItineraryValidationService validationService;
    @Mock private ItinerarySaveHelper saveHelper;
    @Mock private PlaceRepository placeRepository;
    @Mock private GenerationResultStore resultStore;
    @Mock private CancellationRegistry cancellationRegistry;
    @Mock private GenerationPolicyService generationPolicyService;

    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
    private final ItineraryData data = new ItineraryData("강릉 2박 3일", "강릉", BigDecimal.ZERO, List.of(), List.of());
    private ItineraryGenerationExecutor executor;
    private GenerationStream emitter;

    @BeforeEach
    void setUp() {
        executor = new ItineraryGenerationExecutor(aiService, validationService, saveHelper, placeRepository,
                resultStore, cancellationRegistry, generationPolicyService, heartbeat);
        emitter = new GenerationStream("job");
    }

    @AfterEach
    void tearDown() {
        heartbeat.shutdownNow();
    }

    @Test
    @DisplayName("관문이 거부하면 결과 없이 끝내고 미차감 한도만 센다")
    void gateRejectionCountsUnbilled() throws Exception {
        stubPipelineUntilSave();
        when(saveHelper.save(eq(data), any(), eq(1L), eq(false), isNull(), any()))
                .thenThrow(new QualityGate.Rejected(new ItineraryRules.Verdict(ItineraryRules.VERSION, List.of(
                        new ItineraryRules.Violation(ItineraryRules.Rule.DAY_MISSING, 2, "day2 없음")), 0, 0)));

        executor.execute("job", request(null, null, null), 1L, emitter);

        verify(generationPolicyService).recordUnbilled(1L);
        verify(generationPolicyService, never()).recordSuccess(anyLong());
        verify(resultStore, never()).save(anyString(), anyLong(), anyLong());
        assertThat(emitter.isFinished()).isTrue();
        assertThat(sentError()).containsEntry("errorCode", "PLANNING_011")
                .containsEntry("message", "2일차 일정을 채우지 못해 저장하지 않았어요. 다시 만들어 주세요. 30일 생성 횟수는 차감되지 않았어요.");
    }

    @Test
    @DisplayName("조정 필요로 저장되면 쿼터 대신 미차감 한도를 센다")
    void needsAdjustmentCountsUnbilled() throws Exception {
        stubPipelineUntilSave();
        Itinerary saved = Itinerary.builder().id(9L).userId(1L).title("강릉 2박 3일").destination("강릉")
                .qualityStatus(Itinerary.QualityStatus.NEEDS_ADJUSTMENT).build();
        when(saveHelper.save(eq(data), any(), eq(1L), eq(false), isNull(), any()))
                .thenReturn(new ItinerarySaveHelper.Saved(saved, null));

        executor.execute("job", request(null, null, null), 1L, emitter);

        verify(resultStore).save("job", 9L, 1L);
        verify(generationPolicyService).recordUnbilled(1L);
        verify(generationPolicyService, never()).recordSuccess(anyLong());
    }

    @Test
    @DisplayName("판정 입력은 요청의 일수와 출발 시각을 쓰고 비어 있는 pace·이동수단을 그대로 넘긴다")
    void planContextFromRequest() {
        ItineraryRules.Context withDeparture = ItineraryGenerationExecutor.planContext(request("relaxed", "car", "18:00"))
                .rules();
        assertThat(withDeparture.dayCount()).isEqualTo(3);
        assertThat(withDeparture.lastDayHubDeadline()).isEqualTo(17 * 60);

        QualityGate.PlanContext bare = ItineraryGenerationExecutor.planContext(request(null, null, null));
        assertThat(bare.rules().pace()).isNull();
        assertThat(bare.rules().transportPref()).isNull();
        assertThat(bare.rules().lastDayHubDeadline()).isNull();
        assertThat(bare.rules().requiredExperiences()).isEmpty();
        assertThat(bare.fixedNotices()).isEmpty();
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

    private void stubPipelineUntilSave() throws Exception {
        when(aiService.generateItinerary(any(), any())).thenReturn(data);
        when(validationService.validateWithRetry(any(), any(), any())).thenReturn(data);
    }

    private static ItineraryGenerateRequest request(String pace, String transportPref, String departureTime) {
        return new ItineraryGenerateRequest(ItineraryGenerateRequest.PlanningMode.AUTO, "강릉", List.of("바다"),
                List.of("관광", "식당"), pace, transportPref, null, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 3),
                "강릉 바다 여행", null, null, null, null, null, departureTime);
    }
}
