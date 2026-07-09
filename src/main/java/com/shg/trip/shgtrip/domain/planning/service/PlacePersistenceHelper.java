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

    /**
     * (name, address)로 기존 Place를 찾고 없으면 저장한다. 독립 트랜잭션(REQUIRES_NEW)으로
     * 실행해 부모(@Async 생성 파이프라인, 논트랜잭션)와 분리하고, 동시 요청이 같은 자유입력
     * 장소를 넣는 경합에서 (name,address) 유니크 위반은 재조회로 멱등 복구한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Place findOrCreate(String name, String address, java.util.function.Supplier<Place> factory) {
        java.util.Optional<Place> existing = placeRepository.findByNameAndAddress(name, address);
        if (existing.isPresent()) return existing.get();
        try {
            return placeRepository.save(factory.get());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return placeRepository.findByNameAndAddress(name, address).orElseThrow(() -> e);
        }
    }
}
