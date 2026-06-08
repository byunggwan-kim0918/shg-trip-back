package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.planning.dto.GenerateJobResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TravelPlannerService 단위 테스트.
 * 일정 생성 시 OptimizedGenerationExecutor가 호출되는지 검증.
 */
@ExtendWith(MockitoExtension.class)
class TravelPlannerServiceTest {

    @Mock
    private OptimizedGenerationExecutor optimizedGenerationExecutor;

    @Mock
    private GenerationResultStore resultStore;

    @Mock
    private CancellationRegistry cancellationRegistry;

    @Test
    @DisplayName("일정 생성 시 OptimizedGenerationExecutor를 호출한다")
    void startGeneration_callsOptimizedExecutor() {
        // given
        TravelPlannerService service = new TravelPlannerService(
                optimizedGenerationExecutor, resultStore, cancellationRegistry);

        ItineraryGenerateRequest request = createRequest();
        Long userId = 1L;

        // when
        GenerateJobResponse response = service.startGeneration(request, userId);

        // then
        assertThat(response.jobId()).isNotNull();
        verify(optimizedGenerationExecutor).execute(eq(response.jobId()), eq(request), eq(userId), any(SseEmitter.class));
    }

    @Test
    @DisplayName("동일 유저의 기존 작업이 있으면 취소 후 새 작업을 시작한다")
    void startGeneration_cancelsExistingJob() {
        // given
        TravelPlannerService service = new TravelPlannerService(
                optimizedGenerationExecutor, resultStore, cancellationRegistry);

        ItineraryGenerateRequest request = createRequest();
        Long userId = 1L;

        // when - 첫 번째 생성
        GenerateJobResponse first = service.startGeneration(request, userId);
        // when - 두 번째 생성 (기존 작업 취소됨)
        GenerateJobResponse second = service.startGeneration(request, userId);

        // then
        assertThat(first.jobId()).isNotEqualTo(second.jobId());
        verify(cancellationRegistry).cancel(first.jobId());
        verify(optimizedGenerationExecutor, times(2)).execute(anyString(), eq(request), eq(userId), any(SseEmitter.class));
    }

    private ItineraryGenerateRequest createRequest() {
        return new ItineraryGenerateRequest(
                ItineraryGenerateRequest.PlanningMode.AUTO,
                "도쿄",
                List.of("맛집", "관광"),
                List.of("RESTAURANT", "TOURIST_ATTRACTION"),
                "MEDIUM",
                new BigDecimal("1000000"),
                LocalDate.of(2025, 7, 1),
                LocalDate.of(2025, 7, 3),
                null,
                null
        );
    }
}
