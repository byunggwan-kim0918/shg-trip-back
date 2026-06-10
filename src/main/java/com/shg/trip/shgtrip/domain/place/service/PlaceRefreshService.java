package com.shg.trip.shgtrip.domain.place.service;

import com.shg.trip.shgtrip.domain.place.client.GooglePlacesClient;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.place.s3.PlaceImageUploader;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceRefreshService {

    private final PlaceRepository placeRepository;
    private final GooglePlacesClient googlePlacesClient;
    private final PlaceImageUploader placeImageUploader;

    /**
     * 장소 데이터를 Google Places API로 비동기 갱신.
     * 조회 응답을 블로킹하지 않고 백그라운드에서 처리.
     */
    @Async
    @Transactional
    public void refreshAsync(Long placeId, String placeName) {
        refreshInternal(placeId, placeName);
    }

    /**
     * 장소 데이터를 Google Places API로 동기 갱신.
     * CompletableFuture 병렬 호출에서 사용된다.
     */
    @Transactional
    public void refreshSync(Long placeId, String placeName) {
        refreshInternal(placeId, placeName);
    }

    private void refreshInternal(Long placeId, String placeName) {
        try {
            placeRepository.findById(placeId).ifPresent(place -> {
                // 위도경도가 있으면 location bias로 더 정확한 검색
                var detailOpt = (place.getLatitude() != null && place.getLongitude() != null)
                        ? googlePlacesClient.searchAndGetDetailWithLocation(
                                placeName,
                                place.getLatitude().doubleValue(),
                                place.getLongitude().doubleValue()
                        )
                        : googlePlacesClient.searchAndGetDetail(placeName);

                detailOpt.ifPresent(detail -> {
                    place.update(
                            detail.address(),
                            detail.lat(),
                            detail.lng(),
                            detail.rating(),
                            detail.priceLevel(),
                            detail.openingHours(),
                            detail.photoReference(),
                            detail.sourceUrl(),
                            detail.editorialSummary()
                    );
                    // Foursquare 시딩 데이터를 Google API로 갱신 → source 변경
                    place.setSource("google");
                    // stale 갱신 시 최신 이미지도 함께 업로드 (이전 이미지 있어도 갱신)
                    if (detail.photoReference() != null) {
                        try {
                            placeImageUploader.uploadIfAbsent(placeId, detail.photoReference())
                                    .ifPresent(place::updateImageUrl);
                        } catch (Exception e) {
                            log.warn("Failed to upload image for place {}: {}", placeId, e.getMessage());
                        }
                    }
                });
            });
            log.debug("Place {} refreshed successfully and source changed to 'google'", placeId);
        } catch (BusinessException e) {
            log.warn("Failed to refresh place {}: {}", placeId, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error refreshing place {}: {}", placeId, e.getMessage());
        }
    }
}
