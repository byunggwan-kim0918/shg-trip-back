package com.shg.trip.shgtrip.domain.itinerary.repository;

import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ItineraryStepRepository extends JpaRepository<ItineraryStep, Long> {

    /**
     * story(notes) 컬럼만 직접 UPDATE한다. Itinerary 엔티티를 로드/저장하지 않으므로
     * 부모 Itinerary의 @Version을 건드리지 않는다 — 비동기 story 채움과 동시 편집(PUT) 간
     * 낙관적 락 충돌을 피하기 위함.
     *
     * @Transactional 필수: @Modifying JPQL은 SimpleJpaRepository의 표준 메서드와 달리
     * 자동으로 트랜잭션이 걸리지 않아, 호출부(StoryGenerationService, @Async)에 트랜잭션이
     * 없으면 TransactionRequiredException이 발생한다.
     */
    @Transactional
    @Modifying
    @Query("UPDATE ItineraryStep s SET s.notes = :notes WHERE s.itinerary.id = :itineraryId AND s.stepOrder = :stepOrder")
    int updateNotesByItineraryIdAndStepOrder(@Param("itineraryId") Long itineraryId,
                                              @Param("stepOrder") Integer stepOrder,
                                              @Param("notes") String notes);
}
