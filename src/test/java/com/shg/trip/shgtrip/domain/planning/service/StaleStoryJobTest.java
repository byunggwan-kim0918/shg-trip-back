package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary.StoryStatus;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StaleStoryJobTest {

    private final ItineraryRepository repository = mock(ItineraryRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-12-05T03:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("30분 넘게 PENDING인 일정만 FAILED로 바꾼다")
    void failsPendingOlderThan30Minutes() {
        new StaleStoryJob(repository, clock).failStale();

        verify(repository).transitionStoryStatusCreatedBefore(StoryStatus.PENDING, StoryStatus.FAILED,
                OffsetDateTime.parse("2026-12-05T02:30:00Z"));
    }

    @Test
    @DisplayName("DB 오류는 다음 주기에 다시 하도록 삼킨다")
    void dbFailureIsSoft() {
        when(repository.transitionStoryStatusCreatedBefore(any(), any(), any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

        assertThatCode(() -> new StaleStoryJob(repository, clock).failStale()).doesNotThrowAnyException();
    }
}
