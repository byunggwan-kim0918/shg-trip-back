package com.shg.trip.shgtrip.domain.place.batch;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** FSQ 시딩 실행 이력. 시작 시 RUNNING으로 먼저 남겨 컨테이너가 죽어도 추적된다. */
@Entity
@Table(name = "place_seeding_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class PlaceSeedingHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private OffsetDateTime startedAt;

    private OffsetDateTime finishedAt;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "RUNNING"; // RUNNING | SUCCESS | PARTIAL | FAILED

    @Column(length = 500)
    private String sourceFile;

    @Builder.Default private int totalProcessed = 0;
    @Builder.Default private int inserted = 0;
    @Builder.Default private int updated = 0;
    @Builder.Default private int deleted = 0;
    @Builder.Default private int failed = 0;
    @Builder.Default private int failedChunks = 0;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    /** @param deactivated 이번 추출에서 빠져 끈 행 수(deleted 컬럼에 남긴다) */
    public void complete(int totalProcessed, int inserted, int updated, int deactivated, int failed, int failedChunks) {
        this.totalProcessed = totalProcessed;
        this.inserted = inserted;
        this.updated = updated;
        this.deleted = deactivated;
        this.failed = failed;
        this.failedChunks = failedChunks;
        this.finishedAt = OffsetDateTime.now();
        this.status = (failedChunks > 0) ? "PARTIAL" : "SUCCESS";
    }

    public void fail(String errorMessage) {
        this.errorMessage = errorMessage;
        this.finishedAt = OffsetDateTime.now();
        this.status = "FAILED";
    }
}
