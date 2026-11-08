package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryRepository;
import com.shg.trip.shgtrip.domain.planning.dto.EnrichedInput;
import com.shg.trip.shgtrip.domain.planning.dto.ItineraryData;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * @Async 실행기에서 자기 호출 없이 @Transactional을 적용하려고 분리한 빈이다.
 * toEntity()와 save()가 한 트랜잭션에 있어야 cascade가 깨지지 않는다.
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
     * @param referenceCoord 후보 좌표 median 중심점. 여행지 기준 좌표가 이와 어긋나면 매퍼가 median을 쓴다.
     *                       후보 풀이 없는 fallback 경로는 null.
     */
    @Transactional
    public Itinerary save(ItineraryData data, EnrichedInput input, Long userId,
                          boolean alreadyOptimized, double[] referenceCoord) {
        Itinerary itinerary = itineraryDataMapper.toEntity(data, input, userId, alreadyOptimized, referenceCoord);
        // 매퍼는 DB에서 못 찾은 장소의 스텝을 버린다. 전부 버려지면 제목만 있는 빈 일정이 저장되고
        // 성공으로 집계돼 쿼터까지 깎이므로, 저장 전에 실패로 돌린다.
        if (itinerary.getSteps().isEmpty()) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_PLACE_DATA);
        }
        itinerary.assignCoverFromSteps();
        return itineraryRepository.save(itinerary);
    }
}
