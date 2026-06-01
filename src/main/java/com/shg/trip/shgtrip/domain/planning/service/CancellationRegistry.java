package com.shg.trip.shgtrip.domain.planning.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 비동기 생성 작업의 취소 플래그 레지스트리.
 * TravelPlannerService ↔ ItineraryGenerationExecutor 순환 의존성을 방지하기 위해 분리.
 */
@Component
public class CancellationRegistry {

    private final ConcurrentHashMap<String, Boolean> cancelledJobs = new ConcurrentHashMap<>();

    /** 작업 취소 플래그 설정 */
    public void cancel(String jobId) {
        cancelledJobs.put(jobId, Boolean.TRUE);
    }

    /** 취소 여부 확인 */
    public boolean isCancelled(String jobId) {
        return Boolean.TRUE.equals(cancelledJobs.get(jobId));
    }

    /** 작업 완료 후 플래그 정리 */
    public void remove(String jobId) {
        cancelledJobs.remove(jobId);
    }
}
