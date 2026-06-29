package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.place.client.GooglePlaceDetail;
import com.shg.trip.shgtrip.domain.place.client.GooglePlacesClient;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.dto.EnrichedInput;
import com.shg.trip.shgtrip.domain.planning.dto.ItineraryData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * "여행지에서 너무 먼 장소 거르기" 안전장치 검증.
 * 실사례: 제주 일정인데 DB에 같은 이름+주소로 캐시된 서울 장소(약 450km 떨어짐)가 그대로
 * 좌표로 채택되어 지도에 서울이 찍힌 버그 — 기존엔 신규 Google 조회 경로에만 거리 검증이
 * 있었고, DB 캐시 적중/만료 갱신 경로에는 검증이 전혀 없었다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ItineraryDataMapperTest {

    @Mock private PlaceRepository placeRepository;
    @Mock private GooglePlacesClient googlePlacesClient;
    @Mock private PlacePersistenceHelper placePersistenceHelper;
    @Mock private RouteOptimizer routeOptimizer;
    @Mock private ItineraryAutoFixer autoFixer;

    @InjectMocks
    private ItineraryDataMapper mapper;

    private static final double JEJU_LAT = 33.5;
    private static final double JEJU_LNG = 126.5;
    private static final double SEOUL_LAT = 37.5;
    private static final double SEOUL_LNG = 127.0;

    private EnrichedInput jejuInput() {
        return new EnrichedInput("제주", List.of(), List.of(), "normal", "any",
                BigDecimal.valueOf(1000000), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3),
                null, null, null);
    }

    private Place placeAt(String name, String address, double lat, double lng, OffsetDateTime savedAt) {
        return Place.builder()
                .name(name).address(address)
                .latitude(BigDecimal.valueOf(lat)).longitude(BigDecimal.valueOf(lng))
                .category("관광").region("제주").country("Korea")
                .source("google").savedAt(savedAt)
                .build();
    }

    @Test
    @DisplayName("DB에 캐시된 장소가 여행지에서 너무 멀면(오매칭) 그대로 쓰지 않고 Google로 재해결한다")
    void rejectsFarDbCacheAndResolvesViaGoogle() {
        // 여행지 좌표 조회 (제주)
        when(googlePlacesClient.searchAndGetDetail("제주"))
                .thenReturn(Optional.of(new GooglePlaceDetail(
                        "dest", "제주", "제주", JEJU_LAT, JEJU_LNG, null, null, null, null, null, List.of(), null)));

        // DB에는 같은 이름+주소로 서울에 있는(잘못된) 장소가 캐시되어 있음 — fresh(7일 이내)
        Place wrongCachedPlace = placeAt("착한장소", "addr-1", SEOUL_LAT, SEOUL_LNG, OffsetDateTime.now());
        when(placeRepository.findByNameAndAddress("착한장소", "addr-1"))
                .thenReturn(Optional.of(wrongCachedPlace))
                .thenReturn(Optional.empty()); // Google 재조회 후 name+address 중복확인 시엔 없음

        // Google 재조회는 제주 근처의 올바른 좌표를 반환
        when(googlePlacesClient.searchAndGetDetail("착한장소 addr-1"))
                .thenReturn(Optional.of(new GooglePlaceDetail(
                        "p1", "착한장소", "addr-1", JEJU_LAT + 0.01, JEJU_LNG + 0.01,
                        4.5, 2, null, null, null, List.of(), null)));
        when(placeRepository.save(any(Place.class))).thenAnswer(inv -> inv.getArgument(0));

        ItineraryData data = new ItineraryData("제주 여행", "제주", BigDecimal.ZERO, List.of(),
                List.of(new StepData(1, 1, "09:00", "10:00",
                        new PlaceData("착한장소", "addr-1", "관광", "제주", "Korea"),
                        List.of(), null, null, null, null, null, BigDecimal.ZERO)));

        Itinerary itinerary = mapper.toEntity(data, jejuInput(), 1L, true);

        Place resolved = itinerary.getSteps().get(0).getPlace();
        assertThat(resolved.getLatitude().doubleValue()).isCloseTo(JEJU_LAT, org.assertj.core.data.Offset.offset(1.0));
        assertThat(resolved.getLongitude().doubleValue()).isCloseTo(JEJU_LNG, org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    @DisplayName("만료된(stale) 장소를 Google로 갱신할 때 결과가 여행지에서 너무 멀면 갱신을 적용하지 않고 기존 좌표를 유지한다")
    void keepsStaleDataWhenGoogleRefreshReturnsFarPlace() {
        when(googlePlacesClient.searchAndGetDetail("제주"))
                .thenReturn(Optional.of(new GooglePlaceDetail(
                        "dest", "제주", "제주", JEJU_LAT, JEJU_LNG, null, null, null, null, null, List.of(), null)));

        // DB에 제주 근처의 stale(7일 이상 지난) 장소가 있음
        Place stalePlace = placeAt("오래된장소", "addr-2", JEJU_LAT + 0.02, JEJU_LNG + 0.02,
                OffsetDateTime.now().minusDays(10));
        when(placeRepository.findByNameAndAddress("오래된장소", "addr-2"))
                .thenReturn(Optional.of(stalePlace));

        // Google 갱신 결과가 서울(엉뚱한 곳)을 반환
        when(googlePlacesClient.searchAndGetDetail("오래된장소 addr-2"))
                .thenReturn(Optional.of(new GooglePlaceDetail(
                        "p2", "오래된장소", "addr-2", SEOUL_LAT, SEOUL_LNG,
                        4.0, 1, null, null, null, List.of(), null)));

        ItineraryData data = new ItineraryData("제주 여행", "제주", BigDecimal.ZERO, List.of(),
                List.of(new StepData(1, 1, "09:00", "10:00",
                        new PlaceData("오래된장소", "addr-2", "관광", "제주", "Korea"),
                        List.of(), null, null, null, null, null, BigDecimal.ZERO)));

        Itinerary itinerary = mapper.toEntity(data, jejuInput(), 1L, true);

        Place resolved = itinerary.getSteps().get(0).getPlace();
        // 갱신이 적용되지 않아 원래(제주 근처) 좌표가 유지되어야 함
        assertThat(resolved.getLatitude().doubleValue()).isCloseTo(JEJU_LAT, org.assertj.core.data.Offset.offset(1.0));
        verify(placePersistenceHelper, never()).updateAndSave(any());
    }
}
