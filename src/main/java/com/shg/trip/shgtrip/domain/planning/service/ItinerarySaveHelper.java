package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryRepository;
import com.shg.trip.shgtrip.domain.planning.dto.EnrichedInput;
import com.shg.trip.shgtrip.domain.planning.dto.ItineraryData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일정 저장 전용 빈.
 * ItineraryGenerationExecutor(@Async)에서 자기 호출 없이 @Transactional을 적용하기 위해 분리.
 * toEntity() + save()를 단일 트랜잭션으로 묶어 cascade 안전성 보장.
 */
@Component
@RequiredArgsConstructor
public class ItinerarySaveHelper {

    private final ItineraryDataMapper itineraryDataMapper;
    private final ItineraryRepository itineraryRepository;

    @Transactional
    public Itinerary save(ItineraryData data, EnrichedInput input, Long userId) {
        return save(data, input, userId, false);
    }

    @Transactional
    public Itinerary save(ItineraryData data, EnrichedInput input, Long userId, boolean alreadyOptimized) {
        return save(data, input, userId, alreadyOptimized, null);
    }

    /**
     * @param referenceCoord 후보 좌표 median 중심점 — 여행지 기준 좌표(Redis 캐시/Google)가
     *                       이 중심점과 어긋나면 매퍼가 기준 좌표를 폐기하고 median을 쓴다.
     *                       fallback 경로처럼 후보 풀이 없으면 null(기존 동작).
     */
    @Transactional
    public Itinerary save(ItineraryData data, EnrichedInput input, Long userId,
                          boolean alreadyOptimized, double[] referenceCoord) {
        Itinerary itinerary = itineraryDataMapper.toEntity(data, input, userId, alreadyOptimized, referenceCoord);
        itinerary.assignCoverFromSteps();
        return itineraryRepository.save(itinerary);
    }
}
