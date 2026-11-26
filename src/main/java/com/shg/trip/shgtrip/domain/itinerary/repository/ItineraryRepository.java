package com.shg.trip.shgtrip.domain.itinerary.repository;

import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /** 같은 일정의 스텝 편집을 직렬화한다. 두 번째 요청은 앞 요청이 바꾼 상태를 보고 판단한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Itinerary i WHERE i.id = :id AND i.deletedAt IS NULL")
    Optional<Itinerary> findByIdForUpdate(@Param("id") Long id);

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

    /** JPQL UPDATE는 @Version을 올리지 않아 그사이 사용자 편집이 낙관적 잠금 오류로 실패하지 않는다. */
    @Transactional
    @Modifying
    @Query("UPDATE Itinerary i SET i.storyStatus = :status WHERE i.id = :id")
    int updateStoryStatus(@Param("id") Long id, @Param("status") Itinerary.StoryStatus status);

    /** 아직 {@code from}인 일정만 바꾼다. 늦게 끝난 이야기가 이미 기록한 DONE을 실패로 덮지 않는다. */
    @Transactional
    @Modifying
    @Query("UPDATE Itinerary i SET i.storyStatus = :to WHERE i.id = :id AND i.storyStatus = :from")
    int transitionStoryStatus(@Param("id") Long id, @Param("from") Itinerary.StoryStatus from,
                              @Param("to") Itinerary.StoryStatus to);

    @Transactional
    @Modifying
    @Query("UPDATE Itinerary i SET i.storyStatus = :to WHERE i.storyStatus = :from AND i.createdAt < :before")
    int transitionStoryStatusCreatedBefore(@Param("from") Itinerary.StoryStatus from,
                                           @Param("to") Itinerary.StoryStatus to,
                                           @Param("before") OffsetDateTime before);

    @Modifying
    @Query("DELETE FROM Itinerary i WHERE i.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}
