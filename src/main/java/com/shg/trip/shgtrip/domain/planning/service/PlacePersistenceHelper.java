package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * REQUIRES_NEW로 DB 저장만 짧은 독립 트랜잭션에 담아 커넥션 점유를 줄인다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlacePersistenceHelper {

    private final PlaceRepository placeRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Place updateAndSave(Place place) {
        return placeRepository.save(place);
    }

    /**
     * (name, address)로 기존 Place를 찾고 없으면 저장한다.
     * 동시 요청이 같은 자유입력 장소를 넣어 유니크 위반이 나면 재조회로 멱등 복구한다.
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
