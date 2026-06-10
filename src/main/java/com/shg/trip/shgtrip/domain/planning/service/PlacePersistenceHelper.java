package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Place 업데이트 저장 전용 빈.
 *
 * REQUIRES_NEW 독립 트랜잭션으로 실행되어:
 * 1) 부모 트랜잭션과 분리된 독립 트랜잭션
 * 2) DB 저장만 짧은 독립 트랜잭션으로 처리 → 커넥션 점유 최소화
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlacePersistenceHelper {

    private final PlaceRepository placeRepository;

    /**
     * 만료된 Place 업데이트 후 저장.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Place updateAndSave(Place place) {
        return placeRepository.save(place);
    }
}
