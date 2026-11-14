package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryRepository;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ItinerarySaveHelperTest {

    @Mock private ItineraryDataMapper itineraryDataMapper;
    @Mock private ItineraryRepository itineraryRepository;
    @Mock private com.shg.trip.shgtrip.domain.user.service.ActiveUserGuard activeUserGuard;
    @Mock private PlaceRepository placeRepository;
    @Mock private QualityGate qualityGate;
    @InjectMocks private ItinerarySaveHelper saveHelper;

    @Test
    @DisplayName("장소가 하나도 해소되지 않아 스텝이 0개면 저장하지 않고 PLANNING_005로 실패한다")
    void rejectsItineraryWithoutResolvedSteps() {
        when(itineraryDataMapper.toEntity(any(), any(), anyLong(), anyBoolean(), any()))
                .thenReturn(Itinerary.builder().userId(1L).title("제주 3박 4일").destination("제주").build());

        assertThatThrownBy(() -> saveHelper.save(null, null, 1L, false, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INSUFFICIENT_PLACE_DATA);
        verify(itineraryRepository, never()).save(any());
    }

    @Test
    @DisplayName("관문이 거부하면 판정을 실은 예외를 그대로 올리고 저장하지 않는다")
    void gateRejectionSkipsSave() {
        Itinerary itinerary = Itinerary.builder().userId(1L).title("나고야 2박 3일").destination("나고야")
                .steps(new ArrayList<>(List.of(step(1, "Nagoya")))).build();
        when(itineraryDataMapper.toEntity(any(), any(), anyLong(), anyBoolean(), any())).thenReturn(itinerary);
        QualityGate.PlanContext ctx = new QualityGate.PlanContext(null, List.of());
        QualityGate.Rejected rejected = new QualityGate.Rejected(new ItineraryRules.Verdict(ItineraryRules.VERSION,
                List.of(new ItineraryRules.Violation(ItineraryRules.Rule.DAY_MISSING, 2, "day2 없음")), 0, 0));
        when(qualityGate.admit(itinerary, ctx)).thenThrow(rejected);

        assertThatThrownBy(() -> saveHelper.save(null, null, 1L, false, null, ctx)).isSameAs(rejected);
        verify(itineraryRepository, never()).save(any());
    }

    @Test
    @DisplayName("사진 있는 스텝이 없으면 스텝이 가장 많은 지역의 대표 관광지 사진을 표지로 쓴다")
    void coverFallsBackToRegionLandmark() {
        Itinerary itinerary = Itinerary.builder().userId(1L).title("나고야 2박 3일").destination("나고야")
                .steps(new ArrayList<>(List.of(step(1, "Nagoya"), step(2, "Nagoya"), step(3, "Gifu")))).build();
        when(itineraryDataMapper.toEntity(any(), any(), anyLong(), anyBoolean(), any())).thenReturn(itinerary);
        when(placeRepository.findCoverCandidates(eq("Nagoya"), any()))
                .thenReturn(List.of(Place.builder().id(77L).name("Nagoya Castle").imageUrl("https://upload.wikimedia.org/c.jpg")
                        .build()));
        when(itineraryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Itinerary saved = saveHelper.save(null, null, 1L, false, null, null).itinerary();

        assertThat(saved.getCoverPlaceId()).isEqualTo(77L);
    }

    private static ItineraryStep step(long id, String region) {
        Place place = Place.builder().id(id).name("place " + id).address("addr").region(region)
                .latitude(BigDecimal.ONE).longitude(BigDecimal.ONE).category("Landmarks and Outdoors > Park").build();
        return ItineraryStep.builder().id(id).dayNumber(1).stepOrder((int) id).place(place).build();
    }
}
