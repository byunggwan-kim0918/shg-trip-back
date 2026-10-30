package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryRepository;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ItinerarySaveHelperTest {

    @Mock private ItineraryDataMapper itineraryDataMapper;
    @Mock private ItineraryRepository itineraryRepository;
    @InjectMocks private ItinerarySaveHelper saveHelper;

    @Test
    @DisplayName("장소가 하나도 해소되지 않아 스텝이 0개면 저장하지 않고 PLANNING_005로 실패한다")
    void rejectsItineraryWithoutResolvedSteps() {
        when(itineraryDataMapper.toEntity(any(), any(), anyLong(), anyBoolean(), any()))
                .thenReturn(Itinerary.builder().userId(1L).title("제주 3박 4일").destination("제주").build());

        assertThatThrownBy(() -> saveHelper.save(null, null, 1L, false, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INSUFFICIENT_PLACE_DATA);
        verify(itineraryRepository, never()).save(any());
    }
}
