package com.shg.trip.shgtrip.domain.itinerary.repository;

import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface ItineraryRepository extends JpaRepository<Itinerary, Long> {

    /** alternatives까지 fetch join하면 MultipleBagFetchException이 나서 @BatchSize IN 쿼리로 로딩한다. */
    @Query("""
            SELECT DISTINCT i FROM Itinerary i
            LEFT JOIN FETCH i.steps s
            LEFT JOIN FETCH s.place
            WHERE i.id = :id AND i.deletedAt IS NULL
            """)
    Optional<Itinerary> findByIdWithDetails(@Param("id") Long id);

    Page<Itinerary> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long userId, Pageable pageable);

    @Query("""
            SELECT DISTINCT i FROM Itinerary i
            LEFT JOIN FETCH i.steps s
            LEFT JOIN FETCH s.place
            WHERE i.shareToken = :shareToken AND i.deletedAt IS NULL
            """)
    Optional<Itinerary> findByShareToken(@Param("shareToken") String shareToken);

    /**
     * 제목이 아직 {@code expectedTitle}일 때만 교체한다. 0행이면 그사이 사용자가 바꾼 것이다.
     * JPQL UPDATE는 @Version이 안 걸려 조건 없이 바꾸면 비동기 story가 사용자 편집을 덮어쓴다.
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

    /**
     * 이미 지운 일정도 공유 링크가 남아 있을 수 있어 삭제 여부와 무관하게 링크를 끊는다.
     * version을 올려야 동시에 읽어 둔 편집 요청이 낙관적 락에 걸려 삭제를 되돌리지 못한다.
     */
    @Modifying
    @Query("""
            UPDATE Itinerary i
            SET i.deletedAt = COALESCE(i.deletedAt, :now), i.shareToken = NULL, i.shareExpiresAt = NULL,
                i.version = i.version + 1
            WHERE i.userId = :userId
            """)
    int softDeleteAllByUserId(@Param("userId") Long userId, @Param("now") OffsetDateTime now);
}
