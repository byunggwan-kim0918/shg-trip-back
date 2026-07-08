package com.shg.trip.shgtrip.domain.place.service;

import com.shg.trip.shgtrip.domain.place.client.GooglePlaceDetail;
import com.shg.trip.shgtrip.domain.place.client.GooglePlacesClient;
import com.shg.trip.shgtrip.domain.place.client.PlaceIdNotFoundException;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.place.s3.PlaceImageUploader;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

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
                Optional<GooglePlaceDetail> detailOpt = resolveDetail(place, placeName);

                if (detailOpt.isPresent() && hasValidCoords(detailOpt.get())) {
                    GooglePlaceDetail detail = detailOpt.get();
                    place.update(
                            detail.placeId(),
                            detail.address(),
                            detail.lat(),
                            detail.lng(),
                            detail.rating(),
                            detail.priceLevel(),
                            detail.openingHours(),
                            detail.photoReference(),
                            detail.sourceUrl(),
                            null   // description: refresh 시엔 갱신 안 함 (임베딩 이미 생성됨, 프론트 미표시)
                    );
                    place.setSource("google");
                    if (detail.photoReference() != null) {
                        try {
                            placeImageUploader.uploadIfAbsent(placeId, detail.photoReference())
                                    .ifPresent(place::updateImageUrl);
                        } catch (Exception e) {
                            log.warn("Failed to upload image for place {}: {}", placeId, e.getMessage());
                        }
                    }
                    log.debug("Place {} refreshed successfully and source changed to 'google'", placeId);
                } else {
                    // 무매칭도 시도 이력을 남겨야 주기적으로만 재시도됨 (미기록 시 매 생성마다 무한 재호출)
                    place.markSyncAttempted();
                    log.debug("Place {} refresh 무매칭: 검색 실패, {}일 후 재시도", placeId, Place.STALENESS_DAYS);
                }
            });
        } catch (BusinessException e) {
            log.warn("Failed to refresh place {}: {}", placeId, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error refreshing place {}: {}", placeId, e.getMessage());
        }
    }

    /**
     * place_id가 있으면 Place Details 직조회(저렴+오매칭 없음), 404면 리셋 후 Text Search 재매칭.
     * place_id가 없으면 기존 Text Search 경로(좌표기반 → 상호명, refresh 마스크).
     */
    private Optional<GooglePlaceDetail> resolveDetail(Place place, String placeName) {
        if (place.getGooglePlaceId() != null) {
            try {
                return googlePlacesClient.getPlaceDetails(place.getGooglePlaceId());
            } catch (PlaceIdNotFoundException e) {
                // place_id 폐기 → 무효화 후 Text Search로 재매칭 (아래로 진행)
                log.info("Place {} google_place_id 폐기 감지, Text Search 재매칭: {}", place.getId(), e.getMessage());
                place.clearGooglePlaceId();
            }
        }

        // 1차: 좌표 기반 검색 (가장 정확함)
        Optional<GooglePlaceDetail> detailOpt = (place.getLatitude() != null && place.getLongitude() != null)
                ? googlePlacesClient.searchForRefreshWithLocation(
                        placeName,
                        place.getLatitude().doubleValue(),
                        place.getLongitude().doubleValue())
                : Optional.empty();

        // 2차: 좌표 없이 상호명 전체로 검색 (오탐 방지: 이름 유사도 80% 이상만)
        if (detailOpt.isEmpty()) {
            var result = googlePlacesClient.searchForRefresh(placeName);
            if (result.isPresent() && isSimilarName(placeName, result.get())) {
                detailOpt = result;
            }
        }
        return detailOpt;
    }

    /** Google 응답 좌표가 (0,0) 파손 결과가 아닌지 — 정상 좌표를 (0,0)으로 덮어쓰는 것을 방지. */
    private boolean hasValidCoords(GooglePlaceDetail detail) {
        return detail.lat() != 0.0 || detail.lng() != 0.0;
    }

    /**
     * 오탐 방지: Google Places 검색 결과가 원래 상호명과 유사한지 확인.
     * 정확한 일치 또는 80% 이상 유사하면 같은 장소로 판단.
     */
    private boolean isSimilarName(String originalName, GooglePlaceDetail detail) {
        // Details 응답에 displayName이 없으면 name이 null — 파손 결과이므로 오탐으로 처리
        if (detail.name() == null) return false;
        String detailName = detail.name().trim().toLowerCase();
        String original = originalName.trim().toLowerCase();

        // 1) 정확히 같음
        if (detailName.equals(original)) {
            return true;
        }

        // 2) 한쪽이 다른 쪽을 포함 (예: "명주다락" vs "명주다락 레스토랑")
        if (detailName.contains(original) || original.contains(detailName)) {
            return true;
        }

        // 3) Levenshtein distance로 유사도 확인 (80% 이상)
        int distance = levenshteinDistance(original, detailName);
        int maxLength = Math.max(original.length(), detailName.length());
        double similarity = 1.0 - (double) distance / maxLength;

        if (similarity >= 0.8) {
            log.debug("Name similar: '{}' vs '{}' (similarity: {:.0f}%)",
                    originalName, detail.name(), similarity * 100);
            return true;
        }

        log.debug("Name too different: '{}' vs '{}' (similarity: {:.0f}%)",
                originalName, detail.name(), similarity * 100);
        return false;
    }

    /**
     * Levenshtein distance 계산 (문자열 유사도 판단).
     * 두 문자열의 편집 거리로 유사도를 측정.
     */
    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];

        for (int i = 0; i <= s1.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= s2.length(); j++) {
            dp[0][j] = j;
        }

        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }

        return dp[s1.length()][s2.length()];
    }

    /**
     * photoReference만으로 S3 업로드 (Google Places 재검색 없음).
     * refreshSync 내에서 동기로 호출되며, imageUrl 저장 완료를 보장한다.
     */
    @Transactional
    public void uploadPhotoIfAbsent(Long placeId, String photoReference) {
        try {
            placeRepository.findById(placeId).ifPresent(place -> {
                if (place.getImageUrl() != null) return;
                placeImageUploader.uploadIfAbsent(placeId, photoReference)
                        .ifPresent(place::updateImageUrl);
            });
        } catch (Exception e) {
            log.warn("Failed to upload photo for place {}: {}", placeId, e.getMessage());
        }
    }
}
