package com.shg.trip.shgtrip.domain.tripchat.entity;

import com.shg.trip.shgtrip.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "trip_chat_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TripChatSession extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TripChatStatus status;

    @Column(nullable = false, length = 64)
    private String clientRequestId;

    @Column(length = 64)
    private String jobId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB", nullable = false)
    private String state;

    @Version
    private Long version;

    private OffsetDateTime confirmedAt;

    @Column(nullable = false)
    private OffsetDateTime expiresAt;

    public static TripChatSession start(Long userId, String clientRequestId, String state, OffsetDateTime expiresAt) {
        TripChatSession s = new TripChatSession();
        s.id = UUID.randomUUID();
        s.userId = userId;
        s.status = TripChatStatus.ASKING;
        s.clientRequestId = clientRequestId;
        s.state = state;
        s.expiresAt = expiresAt;
        return s;
    }

    public void update(TripChatStatus status, String state, OffsetDateTime expiresAt) {
        this.status = status;
        this.state = state;
        this.expiresAt = expiresAt;
        if (status != TripChatStatus.CONFIRMED) {
            this.jobId = null;
            this.confirmedAt = null;
        }
    }

    public void confirm(String jobId, String state, OffsetDateTime now) {
        this.status = TripChatStatus.CONFIRMED;
        this.jobId = jobId;
        this.state = state;
        this.confirmedAt = now;
    }

    public void archive() {
        this.status = TripChatStatus.ARCHIVED;
    }

    public boolean isActive() {
        return TripChatStatus.ACTIVE.contains(status);
    }
}
