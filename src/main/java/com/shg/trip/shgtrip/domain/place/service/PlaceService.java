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

    /**
     * 장소 단건 조회.
     *
     * <p>예전에는 7일이 지난 장소를 Google Places로 비동기 갱신했다. 그 경로는 제거됐다 —
     * Maps 약관 3.2.3(a)가 세부정보·좌표·평점의 로컬 캐싱을 금지하므로 갱신해 둘 수가 없다.
     * 장소 데이터는 적재 배치(TourAPI·Foursquare·Wikidata)가 책임진다.
     */
    public PlaceResponse getPlace(Long placeId) {
        Place place = placeRepository.findById(placeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLACE_NOT_FOUND));
        return PlaceResponse.from(place);
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

}
