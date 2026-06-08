package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 폐업 장소 soft delete 처리 서비스.
 * <p>
 * Google Places API 조회 시 permanently_closed로 확인된 장소를 비활성화하여
 * 벡터 검색 결과에서 제외한다.
 * <p>
 * PgVectorPlaceSearchService의 검색 쿼리가 {@code active = true} 조건을 포함하므로,
 * 비활성화된 장소는 자동으로 벡터 검색에서 제외된다.
 *
 * @see com.shg.trip.shgtrip.domain.place.vector.PgVectorPlaceSearchService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceDeactivationService {

    private final PlaceRepository placeRepository;

    /**
     * 장소를 비활성화(soft delete)한다.
     * <p>
     * active=false, deactivatedAt=현재시각으로 설정하여 벡터 검색에서 제외한다.
     *
     * @param placeId 비활성화할 장소 ID
     * @throws IllegalArgumentException 장소가 존재하지 않을 경우
     */
    @Transactional
    public void deactivatePlace(Long placeId) {
        Place place = placeRepository.findById(placeId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Place not found: id=" + placeId));

        place.deactivate();
        placeRepository.save(place);

        log.info("Deactivated place: id={}, name={}", placeId, place.getName());
    }

    /**
     * Google Places API 조회 결과에서 permanently_closed 여부를 확인하고,
     * 폐업 상태인 경우 해당 장소를 비활성화한다.
     *
     * @param placeId           장소 ID
     * @param permanentlyClosed Google Places API의 permanently_closed 값
     */
    @Transactional
    public void deactivateIfPermanentlyClosed(Long placeId, boolean permanentlyClosed) {
        if (permanentlyClosed) {
            deactivatePlace(placeId);
        }
    }
}
