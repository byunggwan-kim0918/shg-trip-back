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

    /**
     * 생성 시 끝내 해소하지 못한 품질 문제(숙소 미배정·식사 누락 등)의 사용자 안내 문구.
     * 비어 있으면 모든 구조적 불변식을 만족한 일정이다.
     */
    @Column(name = "quality_notices", columnDefinition = "TEXT[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> qualityNotices;

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

    /** 스텝 제거 (orphanRemoval=true 이므로 컬렉션에서 빼면 DELETE 된다). */
    public void removeStep(ItineraryStep step) {
        steps.remove(step);
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
     * 커버로 쓸 place 참조(coverPlaceId)를 지정한다. 사진 확보가 가능한(photoReference 있는) 스텝 중
     * 커버에 가장 어울리는 하나를 고른다. 실제 imageUrl(만료되는 presigned URL)은 저장하지 않고
     * 조회 시점에 이 id로 해소한다.
     * (과거엔 미구현된 /api/places/{id}/photo 프록시 URL을 coverImage에 넣어 커버가 항상 깨졌음.)
     *
     * 과거엔 findFirst()로 "첫 스텝"을 썼는데, 여행의 첫 스텝은 구조적으로 거의 항상 공항/역이다.
     * 그 결과 제주 일정 5건의 커버가 전부 "제주국제공항" 사진으로 동일했다(대시보드가 같은 사진의 벽).
     * 공항 사진은 목적지를 전혀 설명하지 못하므로 카테고리 우선순위로 고른다.
     */
    public void assignCoverFromSteps() {
        if (this.coverPlaceId != null) return;
        this.steps.stream()
                .filter(s -> s.getPlace() != null && s.getPlace().getPhotoReference() != null)
                .max(java.util.Comparator
                        .comparingInt((ItineraryStep s) -> coverScore(s.getPlace().getCategory()))
                        // 같은 등급이면 평점이 높은 쪽 — 대체로 사진 품질도 낫다
                        .thenComparing(s -> s.getPlace().getRating() != null
                                ? s.getPlace().getRating()
                                : java.math.BigDecimal.ZERO)
                        // 그래도 같으면 원래 동작(앞 스텝)을 유지해 결과를 결정적으로 만든다
                        .thenComparing(java.util.Comparator.comparingInt(
                                (ItineraryStep s) -> s.getStepOrder() != null ? s.getStepOrder() : 0).reversed()))
                .ifPresent(s -> this.coverPlaceId = s.getPlace().getId());
    }

    /**
     * 커버 적합도. 높을수록 우선. category는 Foursquare 분류 문자열("A > B > C").
     * 교통 허브(공항·역·버스정류장)는 여행지를 설명하지 못하므로 최하위 — 다만 그것밖에 없으면 쓰인다.
     */
    private static int coverScore(String category) {
        if (category == null) return 1;
        if (category.startsWith("Travel and Transportation")) return 0;
        if (category.startsWith("Landmarks and Outdoors")) return 4;
        if (category.startsWith("Arts and Entertainment")) return 3;
        if (category.startsWith("Dining and Drinking")) return 2;
        return 1;
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
