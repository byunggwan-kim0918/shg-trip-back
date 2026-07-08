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
     * title/tags만 직접 UPDATE한다 (story 채움 시 함께 갱신). @Version을 증가시키지 않는
     * bulk 쿼리이므로, 동시에 진행 중인 다른 PUT(낙관적 락 기반)과 충돌하지 않는다.
     *
     * @Transactional 필수 — @Modifying JPQL은 호출부에 트랜잭션이 없으면
     * TransactionRequiredException이 발생한다.
     */
    @Transactional
    @Modifying
    @Query("UPDATE Itinerary i SET i.title = :title, i.tags = :tags WHERE i.id = :id")
    int updateTitleAndTags(@Param("id") Long id, @Param("title") String title, @Param("tags") List<String> tags);

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
