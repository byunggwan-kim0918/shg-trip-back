package com.shg.trip.shgtrip.domain.itinerary.repository;

import com.shg.trip.shgtrip.domain.itinerary.entity.GenerationTrace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GenerationTraceRepository extends JpaRepository<GenerationTrace, Long> {

    Optional<GenerationTrace> findByItineraryId(Long itineraryId);

    List<GenerationTrace> findByItineraryIdIn(Collection<Long> itineraryIds);

    @Modifying
    @Query("DELETE FROM GenerationTrace t WHERE t.itineraryId = :itineraryId")
    void deleteByItineraryId(Long itineraryId);

    @Modifying
    @Query("DELETE FROM GenerationTrace t WHERE t.userId = :userId"
            + " OR t.itineraryId IN (SELECT i.id FROM Itinerary i WHERE i.userId = :userId)")
    int deleteByUserId(Long userId);

    /** 관문에서 막힌 생성의 기록. 요청 문장이 있어 오래 두지 않는다. */
    @Modifying
    @Query("DELETE FROM GenerationTrace t WHERE t.itineraryId IS NULL AND t.createdAt < :before")
    int deleteFailedBefore(OffsetDateTime before);
}
