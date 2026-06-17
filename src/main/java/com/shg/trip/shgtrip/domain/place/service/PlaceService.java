package com.shg.trip.shgtrip.domain.place.service;

import com.shg.trip.shgtrip.domain.place.dto.PlaceResponse;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaceService {

    private final PlaceRepository placeRepository;
    private final PlaceRefreshService placeRefreshService;

    /**
     * 장소 단건 조회. 7일 이상 경과 시 비동기 갱신 트리거.
     */
    public PlaceResponse getPlace(Long placeId) {
        Place place = placeRepository.findById(placeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLACE_NOT_FOUND));

        if (place.isStale()) {
            log.info("Place {} is stale, triggering async refresh", placeId);
            placeRefreshService.refreshAsync(placeId, place.getName());
        }

        return PlaceResponse.from(place);
    }

    /**
     * Google Places API로 장소 데이터 갱신.
     */
    @Transactional
    public void refreshPlaceData(Place place) {
        placeRefreshService.refreshAsync(place.getId(), place.getName());
    }

    /**
     * 키워드 기반 장소 검색
     */
    public Page<PlaceResponse> searchPlaces(String keyword, Pageable pageable) {
        return placeRepository.searchByKeyword(keyword, pageable)
                .map(PlaceResponse::from);
    }

    public Page<PlaceResponse> searchPlaces(String keyword, String category, Pageable pageable) {
        return placeRepository.searchByKeywordAndCategory(keyword, category, pageable)
                .map(PlaceResponse::from);
    }

    public Page<PlaceResponse> searchNearby(double lat, double lng, double radiusKm, Pageable pageable) {
        return placeRepository.searchByRadius(lat, lng, radiusKm, pageable)
                .map(PlaceResponse::from);
    }

    /**
     * 장소 데이터 신선도 확인 (7일 기준)
     */
    public boolean isPlaceStale(Long placeId) {
        return placeRepository.findById(placeId)
                .map(Place::isStale)
                .orElse(true);
    }

    /**
     * photo proxy용 photo_reference 조회
     */
    public String getPhotoReference(Long placeId) {
        return placeRepository.findById(placeId)
                .map(Place::getPhotoReference)
                .orElse(null);
    }

    /**
     * photo proxy용 S3 image_url 조회
     */
    public String getImageUrl(Long placeId) {
        return placeRepository.findById(placeId)
                .map(Place::getImageUrl)
                .orElse(null);
    }
}
