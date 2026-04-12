package com.shg.trip.shgtrip.domain.itinerary.repository;

import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ItineraryRepository extends JpaRepository<Itinerary, Long> {

    /**
     * 일정 상세 조회 (steps + place 한 번에 로딩).
     * alternatives는 @BatchSize(size=30)로 IN 쿼리 로딩 — MultipleBagFetchException 방지.
     */
    @Query("""
            SELECT DISTINCT i FROM Itinerary i
            LEFT JOIN FETCH i.steps s
            LEFT JOIN FETCH s.place
            WHERE i.id = :id AND i.deletedAt IS NULL
            """)
    Optional<Itinerary> findByIdWithDetails(@Param("id") Long id);

    /**
     * 사용자별 일정 목록 (soft delete 제외, 최신순).
     */
    Page<Itinerary> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * 공유 토큰으로 일정 조회.
     * alternatives는 @BatchSize(size=30)로 IN 쿼리 로딩.
     */
    @Query("""
            SELECT DISTINCT i FROM Itinerary i
            LEFT JOIN FETCH i.steps s
            LEFT JOIN FETCH s.place
            WHERE i.shareToken = :shareToken AND i.deletedAt IS NULL
            """)
    Optional<Itinerary> findByShareToken(@Param("shareToken") String shareToken);
}
