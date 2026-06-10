package com.shg.trip.shgtrip.domain.place.batch;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * FSQ 장소 시딩 배치 실행 이력.
 * 배치가 시작할 때 RUNNING 상태로 row를 먼저 생성하고,
 * 완료/실패 시 업데이트하여 진행 중 컨테이너가 죽어도 RUNNING 상태가 남아 추적 가능하다.
 */
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

    public void complete(int totalProcessed, int inserted, int updated, int failed, int failedChunks) {
        this.totalProcessed = totalProcessed;
        this.inserted = inserted;
        this.updated = updated;
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
