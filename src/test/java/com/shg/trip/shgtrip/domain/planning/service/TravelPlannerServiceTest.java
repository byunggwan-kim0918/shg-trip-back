package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.planning.dto.GenerateJobResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TravelPlannerService 단위 테스트.
 * 일정 생성 시 OptimizedGenerationExecutor가 호출되는지, 그리고 유저의 활성 jobId를
 * Redis(active:user:{userId})로 관리하며 기존 작업을 취소하는지 검증.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TravelPlannerServiceTest {

    @Mock
    private OptimizedGenerationExecutor optimizedGenerationExecutor;

    @Mock
    private GenerationResultStore resultStore;

    @Mock
    private CancellationRegistry cancellationRegistry;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private GenerationPolicyService generationPolicyService;

    private TravelPlannerService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new TravelPlannerService(
                optimizedGenerationExecutor, resultStore, cancellationRegistry, redisTemplate,
                generationPolicyService);
    }

    @Test
    @DisplayName("일정 생성 시 OptimizedGenerationExecutor를 호출한다")
    void startGeneration_callsOptimizedExecutor() {
        // given — 기존 활성 job 없음 (getAndSet이 null 반환)
        when(valueOperations.getAndSet(eq("active:user:1"), anyString())).thenReturn(null);

        ItineraryGenerateRequest request = createRequest();
        Long userId = 1L;

        // when
        GenerateJobResponse response = service.startGeneration(request, userId);

        // then
        assertThat(response.jobId()).isNotNull();
        verify(optimizedGenerationExecutor).execute(eq(response.jobId()), eq(request), eq(userId), any(SseEmitter.class));
        // 활성 jobId를 Redis에 등록하고 TTL을 건다
        verify(valueOperations).getAndSet("active:user:1", response.jobId());
        verify(redisTemplate).expire(eq("active:user:1"), any());
        // 기존 작업이 없으므로 취소는 호출되지 않는다
        verify(cancellationRegistry, never()).cancel(anyString());
    }

    @Test
    @DisplayName("동일 유저의 기존 활성 job이 있으면(Redis에 잔존) 취소 후 새 작업을 시작한다")
    void startGeneration_cancelsExistingJob() {
        // given — Redis 활성 키에 기존 jobId가 남아있는 상태
        String existingJobId = "existing-job-id";
        when(valueOperations.getAndSet(eq("active:user:1"), anyString())).thenReturn(existingJobId);

        ItineraryGenerateRequest request = createRequest();
        Long userId = 1L;

        // when
        GenerateJobResponse response = service.startGeneration(request, userId);

        // then
        assertThat(response.jobId()).isNotEqualTo(existingJobId);
        // 기존 job을 취소한다 (다른 인스턴스에서 시작됐을 수 있음)
        verify(cancellationRegistry).cancel(existingJobId);
        verify(optimizedGenerationExecutor).execute(eq(response.jobId()), eq(request), eq(userId), any(SseEmitter.class));
    }

    @Test
    @DisplayName("getAndSet이 새 jobId와 같은 값을 반환해도(재시도 등) 취소하지 않는다")
    void startGeneration_doesNotCancelWhenSameJobId() {
        // given — getAndSet이 새로 쓴 jobId를 그대로 되돌려주는 비정상/경합 케이스 방어
        when(valueOperations.getAndSet(eq("active:user:1"), anyString()))
                .thenAnswer(inv -> inv.getArgument(1)); // 방금 쓴 값과 동일

        // when
        GenerateJobResponse response = service.startGeneration(createRequest(), 1L);

        // then — 자기 자신을 취소하지 않는다
        verify(cancellationRegistry, never()).cancel(response.jobId());
    }

    private ItineraryGenerateRequest createRequest() {
        return new ItineraryGenerateRequest(
                ItineraryGenerateRequest.PlanningMode.AUTO,
                "도쿄",
                List.of("맛집", "관광"),
                List.of("RESTAURANT", "TOURIST_ATTRACTION"),
                "MEDIUM",
                "any",
                new BigDecimal("1000000"),
                LocalDate.of(2025, 7, 1),
                LocalDate.of(2025, 7, 3),
                null,
                null,
        null  // customPlaceNames
        );
    }
}
