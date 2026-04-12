package com.shg.trip.shgtrip.domain.itinerary.entity;

import com.shg.trip.shgtrip.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "itineraries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class Itinerary extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String destination;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(precision = 15, scale = 2)
    private BigDecimal totalBudget;

    @Column(precision = 15, scale = 2)
    private BigDecimal estimatedCost;

    private String coverImage;

    @Column(columnDefinition = "TEXT[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> tags;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ItineraryStatus status = ItineraryStatus.DRAFT;

    @Column(length = 64, unique = true)
    private String shareToken;

    private OffsetDateTime shareExpiresAt;

    @Version
    private Integer version;

    private OffsetDateTime deletedAt;

    @OneToMany(mappedBy = "itinerary", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stepOrder ASC")
    @Builder.Default
    private List<ItineraryStep> steps = new ArrayList<>();

    public void addStep(ItineraryStep step) {
        steps.add(step);
        step.setItinerary(this);
    }

    public void softDelete() {
        this.deletedAt = OffsetDateTime.now();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void updateInfo(String title, List<String> tags) {
        if (title != null && !title.isBlank()) this.title = title;
        if (tags != null) this.tags = tags;
    }

    /** 첫 번째 장소의 이미지를 커버로 설정 (photoReference 기반 프록시 URL) */
    public void assignCoverFromSteps() {
        if (this.coverImage != null) return;
        this.steps.stream()
                .filter(s -> s.getPlace() != null && s.getPlace().getPhotoReference() != null)
                .findFirst()
                .ifPresent(s -> this.coverImage = "/api/places/" + s.getPlace().getId() + "/photo");
    }

    public void complete() {
        this.status = ItineraryStatus.FINALIZED;
    }

    public void generateShareToken(String token, OffsetDateTime expiresAt) {
        this.shareToken = token;
        this.shareExpiresAt = expiresAt;
    }

    public enum ItineraryStatus {
        DRAFT, FINALIZED, ARCHIVED
    }
}
