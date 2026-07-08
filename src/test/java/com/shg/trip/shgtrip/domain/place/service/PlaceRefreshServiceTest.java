package com.shg.trip.shgtrip.domain.place.service;

import com.shg.trip.shgtrip.domain.place.client.GooglePlaceDetail;
import com.shg.trip.shgtrip.domain.place.client.GooglePlacesClient;
import com.shg.trip.shgtrip.domain.place.client.PlaceIdNotFoundException;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.place.s3.PlaceImageUploader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Google 무매칭 장소의 무한 재조회 방지 검증.
 * 실사례: 매칭 실패 시 source='foursquare' + 옛 googleSyncedAt이 그대로 남아
 * 후보로 뽑힐 때마다 매 생성마다 Google API가 재호출되던 비용 버그 —
 * 실패 시에도 markSyncAttempted()로 시도 이력을 남겨 7일 주기로만 재시도되어야 한다.
 */
@ExtendWith(MockitoExtension.class)
class PlaceRefreshServiceTest {

    @Mock private PlaceRepository placeRepository;
    @Mock private GooglePlacesClient googlePlacesClient;
    @Mock private PlaceImageUploader placeImageUploader;

    @InjectMocks
    private PlaceRefreshService placeRefreshService;

    private Place foursquarePlace(OffsetDateTime savedAt) {
        return Place.builder()
                .id(1L)
                .name("무매칭식당")
                .address("제주 어딘가")
                .latitude(BigDecimal.valueOf(33.5))
                .longitude(BigDecimal.valueOf(126.5))
                .category("식당")
                .source("foursquare")
                .savedAt(savedAt)
                .build();
    }

    @Test
    @DisplayName("좌표+상호명 검색 모두 무매칭이면 시도 이력(googleSyncedAt/savedAt)을 남기고 source는 유지한다")
    void marksSyncAttemptedWhenNoMatch() {
        OffsetDateTime oldSavedAt = OffsetDateTime.now().minusDays(30);
        Place place = foursquarePlace(oldSavedAt);
        when(placeRepository.findById(1L)).thenReturn(Optional.of(place));
        when(googlePlacesClient.searchForRefreshWithLocation(anyString(), anyDouble(), anyDouble()))
                .thenReturn(Optional.empty());
        when(googlePlacesClient.searchForRefresh(anyString())).thenReturn(Optional.empty());

        placeRefreshService.refreshSync(1L, "무매칭식당");

        assertThat(place.getGoogleSyncedAt()).isNotNull();
        assertThat(place.getSavedAt()).isAfter(oldSavedAt);
        // 실제 Google 데이터로 확정된 게 아니므로 source는 foursquare 유지
        assertThat(place.getSource()).isEqualTo("foursquare");
    }

    @Test
    @DisplayName("상호명 검색 결과가 원래 이름과 너무 다르면(오탐) 무매칭으로 처리하고 시도 이력을 남긴다")
    void marksSyncAttemptedWhenNameTooDifferent() {
        Place place = foursquarePlace(OffsetDateTime.now().minusDays(30));
        when(placeRepository.findById(1L)).thenReturn(Optional.of(place));
        when(googlePlacesClient.searchForRefreshWithLocation(anyString(), anyDouble(), anyDouble()))
                .thenReturn(Optional.empty());
        when(googlePlacesClient.searchForRefresh(anyString()))
                .thenReturn(Optional.of(new GooglePlaceDetail(
                        "g1", "완전히다른가게", "서울 어딘가", 37.5, 127.0,
                        4.0, 2, null, null, null, List.of(), null)));

        placeRefreshService.refreshSync(1L, "무매칭식당");

        assertThat(place.getGoogleSyncedAt()).isNotNull();
        assertThat(place.getSource()).isEqualTo("foursquare");
    }

    @Test
    @DisplayName("매칭 성공 시 데이터를 갱신하고 source를 google로 변경한다")
    void updatesPlaceAndSourceOnMatch() {
        Place place = foursquarePlace(OffsetDateTime.now().minusDays(30));
        when(placeRepository.findById(1L)).thenReturn(Optional.of(place));
        when(googlePlacesClient.searchForRefreshWithLocation(anyString(), anyDouble(), anyDouble()))
                .thenReturn(Optional.of(new GooglePlaceDetail(
                        "g1", "무매칭식당", "제주 어딘가 1-1", 33.51, 126.51,
                        4.5, 2, "매일 09:00-21:00", null, "https://maps.google.com/x", List.of(), null)));

        placeRefreshService.refreshSync(1L, "무매칭식당");

        assertThat(place.getSource()).isEqualTo("google");
        assertThat(place.getGoogleSyncedAt()).isNotNull();
        assertThat(place.getRating()).isEqualByComparingTo(BigDecimal.valueOf(4.5));
        // Text Search 첫 매칭 시 반환된 place_id가 저장되어 다음 refresh는 Details 경로로 감
        assertThat(place.getGooglePlaceId()).isEqualTo("g1");
    }

    private Place googlePlace(String googlePlaceId) {
        return Place.builder()
                .id(2L)
                .name("확정장소")
                .address("제주 어딘가")
                .latitude(BigDecimal.valueOf(33.5))
                .longitude(BigDecimal.valueOf(126.5))
                .category("관광")
                .source("google")
                .googlePlaceId(googlePlaceId)
                .savedAt(OffsetDateTime.now().minusDays(40))
                .build();
    }

    @Test
    @DisplayName("Details 응답 좌표가 (0,0)이면(파손) 기존 정상 좌표를 덮어쓰지 않고 유지한다")
    void keepsCoordsWhenDetailsReturnsZeroZero() {
        Place place = googlePlace("ChIJ_broken");
        when(placeRepository.findById(2L)).thenReturn(Optional.of(place));
        when(googlePlacesClient.getPlaceDetails("ChIJ_broken"))
                .thenReturn(Optional.of(new GooglePlaceDetail(
                        "ChIJ_broken", "확정장소", "제주 어딘가", 0.0, 0.0,
                        null, null, null, null, null, List.of(), null)));

        placeRefreshService.refreshSync(2L, "확정장소");

        // 기존 좌표(33.5,126.5) 보존, (0,0)으로 파괴되지 않아야 함
        assertThat(place.getLatitude()).isEqualByComparingTo(BigDecimal.valueOf(33.5));
        assertThat(place.getLongitude()).isEqualByComparingTo(BigDecimal.valueOf(126.5));
        // 무매칭 취급 → 재시도 이력만 기록
        assertThat(place.getGoogleSyncedAt()).isNotNull();
    }

    @Test
    @DisplayName("google_place_id가 있으면 Text Search가 아닌 Place Details 직조회를 사용한다")
    void usesDetailsWhenPlaceIdPresent() {
        Place place = googlePlace("ChIJ_abc");
        when(placeRepository.findById(2L)).thenReturn(Optional.of(place));
        when(googlePlacesClient.getPlaceDetails("ChIJ_abc"))
                .thenReturn(Optional.of(new GooglePlaceDetail(
                        "ChIJ_abc", "확정장소", "제주 어딘가 2-2", 33.52, 126.52,
                        4.2, 3, null, null, null, List.of(), null)));

        placeRefreshService.refreshSync(2L, "확정장소");

        assertThat(place.getRating()).isEqualByComparingTo(BigDecimal.valueOf(4.2));
        // Text Search 계열은 호출되지 않아야 함
        org.mockito.Mockito.verify(googlePlacesClient, org.mockito.Mockito.never())
                .searchForRefreshWithLocation(anyString(), anyDouble(), anyDouble());
        org.mockito.Mockito.verify(googlePlacesClient, org.mockito.Mockito.never())
                .searchForRefresh(anyString());
    }

    @Test
    @DisplayName("photoReference가 있으면 S3 이미지 업로드를 시도하고 imageUrl을 반영한다")
    void uploadsImageWhenPhotoReferencePresent() {
        Place place = foursquarePlace(OffsetDateTime.now().minusDays(40));
        when(placeRepository.findById(1L)).thenReturn(Optional.of(place));
        when(googlePlacesClient.searchForRefreshWithLocation(anyString(), anyDouble(), anyDouble()))
                .thenReturn(Optional.of(new GooglePlaceDetail(
                        "g1", "무매칭식당", "제주 어딘가 1-1", 33.51, 126.51,
                        4.5, 2, null, "places/g1/photos/xyz", null, List.of(), null)));
        when(placeImageUploader.uploadIfAbsent(1L, "places/g1/photos/xyz"))
                .thenReturn(Optional.of("https://s3/img.jpg"));

        placeRefreshService.refreshSync(1L, "무매칭식당");

        assertThat(place.getImageUrl()).isEqualTo("https://s3/img.jpg");
    }

    @Test
    @DisplayName("refreshAsync도 동일 경로로 갱신한다")
    void refreshAsyncDelegatesToInternal() {
        Place place = googlePlace("ChIJ_async");
        when(placeRepository.findById(2L)).thenReturn(Optional.of(place));
        when(googlePlacesClient.getPlaceDetails("ChIJ_async"))
                .thenReturn(Optional.of(new GooglePlaceDetail(
                        "ChIJ_async", "확정장소", "제주 2-2", 33.52, 126.52,
                        4.1, 1, null, null, null, List.of(), null)));

        placeRefreshService.refreshAsync(2L, "확정장소");

        assertThat(place.getRating()).isEqualByComparingTo(BigDecimal.valueOf(4.1));
    }

    @Test
    @DisplayName("Details가 404(place_id 폐기)면 id를 리셋하고 Text Search로 재매칭한다")
    void resetsAndRematchesOnDetails404() {
        Place place = googlePlace("ChIJ_dead");
        when(placeRepository.findById(2L)).thenReturn(Optional.of(place));
        when(googlePlacesClient.getPlaceDetails("ChIJ_dead"))
                .thenThrow(new PlaceIdNotFoundException("ChIJ_dead"));
        when(googlePlacesClient.searchForRefreshWithLocation(anyString(), anyDouble(), anyDouble()))
                .thenReturn(Optional.of(new GooglePlaceDetail(
                        "ChIJ_new", "확정장소", "제주 어딘가 2-2", 33.52, 126.52,
                        4.0, 2, null, null, null, List.of(), null)));

        placeRefreshService.refreshSync(2L, "확정장소");

        // 폐기된 ID가 새 ID로 교체됨
        assertThat(place.getGooglePlaceId()).isEqualTo("ChIJ_new");
        assertThat(place.getRating()).isEqualByComparingTo(BigDecimal.valueOf(4.0));
    }
}
