package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaceDeactivationServiceTest {

    @Mock
    private PlaceRepository placeRepository;

    @InjectMocks
    private PlaceDeactivationService service;

    private Place createActivePlace(Long id, String name) {
        return Place.builder()
                .id(id)
                .name(name)
                .address("서울시 종로구")
                .latitude(BigDecimal.valueOf(37.5))
                .longitude(BigDecimal.valueOf(127.0))
                .category("관광")
                .savedAt(OffsetDateTime.now())
                .active(true)
                .build();
    }

    @Nested
    @DisplayName("deactivatePlace")
    class DeactivatePlaceTests {

        @Test
        @DisplayName("활성 장소를 비활성화하면 active=false, deactivatedAt이 설정됨")
        void deactivatePlace_setsActiveToFalseAndDeactivatedAt() {
            Place place = createActivePlace(1L, "센소지");
            when(placeRepository.findById(1L)).thenReturn(Optional.of(place));
            when(placeRepository.save(any(Place.class))).thenAnswer(inv -> inv.getArgument(0));

            service.deactivatePlace(1L);

            ArgumentCaptor<Place> captor = ArgumentCaptor.forClass(Place.class);
            verify(placeRepository).save(captor.capture());

            Place saved = captor.getValue();
            assertThat(saved.getActive()).isFalse();
            assertThat(saved.getDeactivatedAt()).isNotNull();
            assertThat(saved.getDeactivatedAt()).isBeforeOrEqualTo(OffsetDateTime.now());
        }

        @Test
        @DisplayName("존재하지 않는 장소 ID로 호출 시 IllegalArgumentException 발생")
        void deactivatePlace_nonExistentId_throwsException() {
            when(placeRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deactivatePlace(999L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Place not found");
        }

        @Test
        @DisplayName("이미 비활성화된 장소도 다시 비활성화 가능 (멱등성)")
        void deactivatePlace_alreadyInactive_stillSetsFields() {
            Place place = createActivePlace(1L, "폐업 장소");
            // 이미 비활성화 상태 시뮬레이션
            place.deactivate();
            OffsetDateTime originalDeactivatedAt = place.getDeactivatedAt();

            when(placeRepository.findById(1L)).thenReturn(Optional.of(place));
            when(placeRepository.save(any(Place.class))).thenAnswer(inv -> inv.getArgument(0));

            service.deactivatePlace(1L);

            ArgumentCaptor<Place> captor = ArgumentCaptor.forClass(Place.class);
            verify(placeRepository).save(captor.capture());

            Place saved = captor.getValue();
            assertThat(saved.getActive()).isFalse();
            assertThat(saved.getDeactivatedAt()).isNotNull();
            // deactivatedAt은 호출 시 갱신됨
            assertThat(saved.getDeactivatedAt()).isAfterOrEqualTo(originalDeactivatedAt);
        }
    }

    @Nested
    @DisplayName("deactivateIfPermanentlyClosed")
    class DeactivateIfPermanentlyClosedTests {

        @Test
        @DisplayName("permanentlyClosed=true이면 장소가 비활성화됨")
        void permanentlyClosed_true_deactivatesPlace() {
            Place place = createActivePlace(1L, "폐업된 식당");
            when(placeRepository.findById(1L)).thenReturn(Optional.of(place));
            when(placeRepository.save(any(Place.class))).thenAnswer(inv -> inv.getArgument(0));

            service.deactivateIfPermanentlyClosed(1L, true);

            verify(placeRepository).save(any(Place.class));
            assertThat(place.getActive()).isFalse();
            assertThat(place.getDeactivatedAt()).isNotNull();
        }

        @Test
        @DisplayName("permanentlyClosed=false이면 아무 동작도 하지 않음")
        void permanentlyClosed_false_doesNothing() {
            service.deactivateIfPermanentlyClosed(1L, false);

            verify(placeRepository, never()).findById(anyLong());
            verify(placeRepository, never()).save(any(Place.class));
        }

        @Test
        @DisplayName("permanentlyClosed=true + 존재하지 않는 ID = 예외 발생")
        void permanentlyClosed_true_nonExistentId_throwsException() {
            when(placeRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deactivateIfPermanentlyClosed(999L, true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Place not found");
        }
    }
}
