package com.shg.trip.shgtrip.domain.itinerary.repository;

import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ItineraryStepRepository extends JpaRepository<ItineraryStep, Long> {

    /**
     * 부모 Itinerary를 로드하지 않아 @Version이 안 올라가므로 비동기 story 채움이 동시 편집(PUT)과 충돌하지 않는다.
     * {@code @Modifying} JPQL은 트랜잭션이 자동으로 걸리지 않아 빼면 @Async 호출부에서 TransactionRequiredException이 난다.
     */
    @Transactional
    @Modifying
    @Query("UPDATE ItineraryStep s SET s.notes = :notes WHERE s.itinerary.id = :itineraryId AND s.stepOrder = :stepOrder")
    int updateNotesByItineraryIdAndStepOrder(@Param("itineraryId") Long itineraryId,
                                              @Param("stepOrder") Integer stepOrder,
                                              @Param("notes") String notes);
}
