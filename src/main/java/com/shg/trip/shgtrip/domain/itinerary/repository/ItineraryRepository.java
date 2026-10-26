package com.shg.trip.shgtrip.domain.itinerary.repository;

import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    /**
     * 제목이 아직 {@code expectedTitle}일 때만 교체한다(0행이면 그사이 누군가 바꾼 것).
     *
     * <p>비동기 story 단계가 더 나은 제목을 들고 오지만, 그 몇 초 사이에 사용자가 제목을 고쳤을
     * 수도 있다. 무조건 UPDATE하면 그 편집이 조용히 사라진다(JPQL UPDATE라 @Version도 안 걸린다).
     * WHERE에 현재 제목을 넣어 "안 건드려졌을 때만" 바꾸는 단일 원자적 쿼리로 처리한다.
     */
    @Transactional
    @Modifying
    @Query("""
            UPDATE Itinerary i SET i.title = :newTitle
            WHERE i.id = :id AND i.title = :expectedTitle AND i.deletedAt IS NULL
            """)
    int updateTitleIfUnchanged(@Param("id") Long id,
                               @Param("newTitle") String newTitle,
                               @Param("expectedTitle") String expectedTitle);
}
