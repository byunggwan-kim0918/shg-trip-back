package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryRepository;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.dto.EnrichedInput;
import com.shg.trip.shgtrip.domain.planning.dto.ItineraryData;
import com.shg.trip.shgtrip.domain.user.service.ActiveUserGuard;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @Async 실행기에서 자기 호출 없이 @Transactional을 적용하려고 분리한 빈이다.
 * toEntity()와 save()가 한 트랜잭션에 있어야 cascade가 깨지지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ItinerarySaveHelper {

    private final ItineraryDataMapper itineraryDataMapper;
    private final ItineraryRepository itineraryRepository;
    private final ActiveUserGuard activeUserGuard;
    private final PlaceRepository placeRepository;

    @Transactional
    public Itinerary save(ItineraryData data, EnrichedInput input, Long userId) {
        return save(data, input, userId, false);
    }

    @Transactional
    public Itinerary save(ItineraryData data, EnrichedInput input, Long userId, boolean alreadyOptimized) {
        return save(data, input, userId, alreadyOptimized, null);
    }

    /**
     * @param referenceCenters 여행지별 후보 좌표 median 중심점. 후보 풀이 없는 fallback 경로는 null.
     */
    @Transactional
    public Itinerary save(ItineraryData data, EnrichedInput input, Long userId,
                          boolean alreadyOptimized, List<double[]> referenceCenters) {
        activeUserGuard.lockActive(userId);
        Itinerary itinerary = itineraryDataMapper.toEntity(data, input, userId, alreadyOptimized, referenceCenters);
        // 매퍼는 DB에서 못 찾은 장소의 스텝을 버린다. 전부 버려지면 제목만 있는 빈 일정이 저장되고
        // 성공으로 집계돼 쿼터까지 깎이므로, 저장 전에 실패로 돌린다.
        if (itinerary.getSteps().isEmpty()) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_PLACE_DATA);
        }
        itinerary.assignCoverFromSteps();
        if (itinerary.getCoverPlaceId() == null) {
            mainRegion(itinerary).flatMap(region -> placeRepository
                            .findCoverCandidates(region, PageRequest.of(0, 1)).stream().findFirst())
                    .ifPresent(place -> itinerary.assignCoverPlaceIfAbsent(place.getId()));
        }
        itinerary.recalculateEstimatedCost();
        return itineraryRepository.save(itinerary);
    }

    /** 스텝 장소가 가장 많이 속한 지역. */
    private static Optional<String> mainRegion(Itinerary itinerary) {
        Map<String, Long> counts = itinerary.getSteps().stream()
                .map(ItineraryStep::getPlace)
                .filter(Objects::nonNull)
                .map(Place::getRegion)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        return counts.entrySet().stream()
                .max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey);
    }
}
