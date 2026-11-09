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
import java.util.Objects;
import java.util.stream.Stream;

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
     * imageUrl이 있는 스텝 중 카테고리 우선순위가 가장 높은 장소를 커버로 지정한다.
     * 첫 스텝은 거의 항상 공항·역이라 목적지를 설명하지 못한다.
     */
    public void assignCoverFromSteps() {
        if (this.coverPlaceId != null) return;
        this.steps.stream()
                .filter(s -> s.getPlace() != null && s.getPlace().getImageUrl() != null)
                .max(java.util.Comparator
                        .comparingInt((ItineraryStep s) -> coverScore(s.getPlace().getCategory()))
                        // 같은 등급이면 평점이 높은 쪽이 대체로 사진 품질도 낫다.
                        .thenComparing(s -> s.getPlace().getRating() != null
                                ? s.getPlace().getRating()
                                : java.math.BigDecimal.ZERO)
                        // 그래도 같으면 앞 스텝을 골라 결과를 결정적으로 만든다.
                        .thenComparing(java.util.Comparator.comparingInt(
                                (ItineraryStep s) -> s.getStepOrder() != null ? s.getStepOrder() : 0).reversed()))
                .ifPresent(s -> this.coverPlaceId = s.getPlace().getId());
    }

    /**
     * 높을수록 커버로 우선한다. category는 Foursquare 분류 문자열("A > B > C")이다.
     * 교통 허브는 여행지를 설명하지 못해 최하위지만, 그것밖에 없으면 쓰인다.
     */
    private static int coverScore(String category) {
        if (category == null) return 1;
        if (category.startsWith("Travel and Transportation")) return 0;
        if (category.startsWith("Landmarks and Outdoors")) return 4;
        if (category.startsWith("Arts and Entertainment")) return 3;
        if (category.startsWith("Dining and Drinking")) return 2;
        return 1;
    }

    /** 상세 화면 합계와 같은 식(장소 비용 + 이동 비용)이라 편집 뒤 목록·공유 금액이 어긋나지 않는다. */
    public void recalculateEstimatedCost() {
        this.estimatedCost = steps.stream()
                .flatMap(s -> Stream.of(s.getEstimatedCost(), s.getTransportationCost()))
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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
