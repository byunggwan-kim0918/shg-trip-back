package com.shg.trip.shgtrip.domain.tripchat.repository;

import com.shg.trip.shgtrip.domain.tripchat.entity.TripChatSession;
import com.shg.trip.shgtrip.domain.tripchat.entity.TripChatStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface TripChatSessionRepository extends JpaRepository<TripChatSession, UUID> {

    default Optional<TripChatSession> findActive(Long userId) {
        return findFirstByUserIdAndStatusIn(userId, TripChatStatus.ACTIVE);
    }

    Optional<TripChatSession> findFirstByUserIdAndStatusIn(Long userId, Collection<TripChatStatus> statuses);

    Optional<TripChatSession> findByIdAndUserId(UUID id, Long userId);

    @Modifying
    @Query("DELETE FROM TripChatSession s WHERE s.userId = :userId")
    int deleteByUserId(Long userId);

    default int deleteExpired(OffsetDateTime now, OffsetDateTime archivedBefore) {
        return deleteExpired(now, archivedBefore, TripChatStatus.ARCHIVED);
    }

    @Modifying
    @Query("DELETE FROM TripChatSession s WHERE s.expiresAt < :now OR (s.status = :archived AND s.updatedAt < :archivedBefore)")
    int deleteExpired(OffsetDateTime now, OffsetDateTime archivedBefore, TripChatStatus archived);
}
