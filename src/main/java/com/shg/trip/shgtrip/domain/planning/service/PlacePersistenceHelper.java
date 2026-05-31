package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Place 저장 전용 빈.
 *
 * REQUIRES_NEW 독립 트랜잭션으로 실행되어:
 * 1) Google API 외부 호출(ItineraryDataMapper)은 트랜잭션 밖에서 수행
 * 2) DB 저장만 짧은 독립 트랜잭션으로 처리 → 커넥션 점유 최소화
 * 3) DataIntegrityViolationException catch 시 동일 트랜잭션 dirty state 문제 없음
 *    (REQUIRES_NEW이므로 충돌 트랜잭션이 롤백된 후 재조회 가능)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlacePersistenceHelper {

    private final PlaceRepository placeRepository;

    /**
     * Place 저장. UNIQUE 충돌 시 기존 레코드 반환.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Place saveOrFetch(Place place) {
        try {
            return placeRepository.save(place);
        } catch (DataIntegrityViolationException e) {
            log.warn("Race condition on place save, fetching existing: name={}", place.getName());
            return placeRepository.findByNameAndAddress(place.getName(), place.getAddress())
                    .orElseThrow(() -> new IllegalStateException(
                            "Place save conflict but record not found: " + place.getName()));
        }
    }

    /**
     * 만료된 Place 업데이트 후 저장.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Place updateAndSave(Place place) {
        return placeRepository.save(place);
    }
}
