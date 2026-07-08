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

    /** 커버 이미지 place 참조. imageUrl(만료 presigned)은 조회 시점에 이 id로 해소한다. */
    private Long coverPlaceId;

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

    /**
     * 커버로 쓸 place 참조(coverPlaceId)를 지정한다. 사진 확보가 가능한(photoReference 있는) 첫 스텝의
     * place id를 저장하며, 실제 imageUrl(만료되는 presigned URL)은 저장하지 않고 조회 시점에 이 id로 해소한다.
     * (과거엔 미구현된 /api/places/{id}/photo 프록시 URL을 coverImage에 넣어 커버가 항상 깨졌음.)
     */
    public void assignCoverFromSteps() {
        if (this.coverPlaceId != null) return;
        this.steps.stream()
                .filter(s -> s.getPlace() != null && s.getPlace().getPhotoReference() != null)
                .findFirst()
                .ifPresent(s -> this.coverPlaceId = s.getPlace().getId());
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
