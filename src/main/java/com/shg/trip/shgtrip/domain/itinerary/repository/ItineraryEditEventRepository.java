package com.shg.trip.shgtrip.domain.itinerary.repository;

import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryEditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItineraryEditEventRepository extends JpaRepository<ItineraryEditEvent, Long> {
}
