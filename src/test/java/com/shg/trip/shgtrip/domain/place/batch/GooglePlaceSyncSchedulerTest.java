package com.shg.trip.shgtrip.domain.place.batch;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.place.service.PlaceRefreshService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GooglePlaceSyncSchedulerTest {

    @Mock private PlaceRepository placeRepository;
    @Mock private PlaceRefreshService placeRefreshService;

    @InjectMocks
    private GooglePlaceSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(scheduler, "recentDays", 90);
        ReflectionTestUtils.setField(scheduler, "dailyLimit", 500);
    }

    private Place place(long id, String name) {
        return Place.builder().id(id).name(name).build();
    }

    @Test
    @DisplayName("사전채움 대상이 있으면 각 장소를 refreshSync로 갱신한다")
    void syncsEachTarget() {
        when(placeRepository.findPrefetchTargets(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(place(1L, "A"), place(2L, "B")));

        scheduler.sync();

        verify(placeRefreshService).refreshSync(1L, "A");
        verify(placeRefreshService).refreshSync(2L, "B");
    }

    @Test
    @DisplayName("일부 장소 갱신이 실패해도 나머지는 계속 진행한다")
    void continuesOnFailure() {
        when(placeRepository.findPrefetchTargets(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(place(1L, "A"), place(2L, "B")));
        doThrow(new RuntimeException("boom")).when(placeRefreshService).refreshSync(1L, "A");

        scheduler.sync();

        verify(placeRefreshService).refreshSync(2L, "B");
    }

    @Test
    @DisplayName("대상이 없으면 refreshSync를 호출하지 않는다")
    void skipsWhenNoTargets() {
        when(placeRepository.findPrefetchTargets(any(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        scheduler.sync();

        verify(placeRefreshService, never()).refreshSync(anyLong(), anyString());
    }

    @Test
    @DisplayName("stale 기준 시각은 now-STALENESS_DAYS로 조회한다")
    void queriesWithStalenessThreshold() {
        when(placeRepository.findPrefetchTargets(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(place(1L, "A")));

        scheduler.sync();

        ArgumentCaptor<OffsetDateTime> recent = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> stale = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(placeRepository).findPrefetchTargets(recent.capture(), stale.capture(), any(Pageable.class));

        OffsetDateTime now = OffsetDateTime.now();
        // recent = now-90일, stale = now-30일 (근사 검증)
        assertThat(recent.getValue()).isBefore(now.minusDays(89));
        assertThat(stale.getValue()).isBefore(now.minusDays(Place.STALENESS_DAYS - 1));
        assertThat(stale.getValue()).isAfter(now.minusDays(Place.STALENESS_DAYS + 1));
        verify(placeRefreshService, times(1)).refreshSync(1L, "A");
    }
}
