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

    @Modifying
    @Query("DELETE FROM Itinerary i WHERE i.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}
