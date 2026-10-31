package com.shg.trip.shgtrip.domain.itinerary.repository;

import com.shg.trip.shgtrip.domain.itinerary.entity.GenerationTrace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GenerationTraceRepository extends JpaRepository<GenerationTrace, Long> {

    Optional<GenerationTrace> findByItineraryId(Long itineraryId);

    List<GenerationTrace> findByItineraryIdIn(Collection<Long> itineraryIds);

    @Modifying
    @Query("DELETE FROM GenerationTrace t WHERE t.itineraryId = :itineraryId")
    void deleteByItineraryId(Long itineraryId);
}
