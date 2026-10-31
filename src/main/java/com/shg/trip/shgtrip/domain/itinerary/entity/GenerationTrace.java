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

    @Column(nullable = false)
    private Long itineraryId;

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

    @Column(nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private GenerationTrace(Long itineraryId, String jobId, int traceVersion, String request,
                            String enrichedInput, String selectionRaw, String selectionFinal,
                            String candidates, String scheduleArgs, String models, Long totalMs) {
        this.itineraryId = itineraryId;
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
    }

    public void updateScore(String scoreJson) {
        this.score = scoreJson;
    }
}
