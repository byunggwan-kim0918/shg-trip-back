package com.shg.trip.shgtrip.domain.itinerary.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "generation_traces")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GenerationTrace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 관문에서 막혀 저장하지 않은 생성은 null이다. */
    private Long itineraryId;

    private Long userId;

    @Column(nullable = false)
    private String jobId;

    @Column(nullable = false)
    private int traceVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB", nullable = false)
    private String request;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB", nullable = false)
    private String enrichedInput;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB", nullable = false)
    private String selectionRaw;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB", nullable = false)
    private String selectionFinal;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB", nullable = false)
    private String candidates;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB", nullable = false)
    private String scheduleArgs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    private String models;

    private Long totalMs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    private String score;

    /** 저장 직후 스텝(일차·순서·장소 id·시각·대안 장소 id). 사용자 편집 전 상태다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    private String generatedSteps;

    @Column(nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private GenerationTrace(Long itineraryId, Long userId, String jobId, int traceVersion, String request,
                            String enrichedInput, String selectionRaw, String selectionFinal,
                            String candidates, String scheduleArgs, String models, Long totalMs,
                            String score, String generatedSteps) {
        this.itineraryId = itineraryId;
        this.userId = userId;
        this.jobId = jobId;
        this.traceVersion = traceVersion;
        this.request = request;
        this.enrichedInput = enrichedInput;
        this.selectionRaw = selectionRaw;
        this.selectionFinal = selectionFinal;
        this.candidates = candidates;
        this.scheduleArgs = scheduleArgs;
        this.models = models;
        this.totalMs = totalMs;
        this.score = score;
        this.generatedSteps = generatedSteps;
    }

    public void updateScore(String scoreJson) {
        this.score = scoreJson;
    }
}
