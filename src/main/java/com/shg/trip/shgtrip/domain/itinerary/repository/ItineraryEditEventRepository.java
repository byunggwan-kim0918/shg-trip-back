package com.shg.trip.shgtrip.domain.itinerary.repository;

import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryEditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItineraryEditEventRepository extends JpaRepository<ItineraryEditEvent, Long> {

    @Modifying
    @Query("DELETE FROM ItineraryEditEvent e WHERE e.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}
