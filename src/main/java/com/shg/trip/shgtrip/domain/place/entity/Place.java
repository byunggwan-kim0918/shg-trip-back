package com.shg.trip.shgtrip.domain.place.entity;

import com.shg.trip.shgtrip.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Entity
@Table(name = "places")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class Place extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String address;

    @Column(nullable = false, precision = 10, scale = 8)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 11, scale = 8)
    private BigDecimal longitude;

    @Column(nullable = false, length = 255)
    private String category;

    @Column(length = 255)
    private String region;

    @Column(length = 100)
    private String country;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(precision = 2, scale = 1)
    private BigDecimal rating;

    private Integer priceLevel;

    private String openingHours;

    private String imageUrl;

    private String photoReference;

    private String sourceUrl;

    /** 데이터 소스 ('google', 'foursquare', 'llm_generated') */
    @Column(length = 50)
    @Builder.Default
    private String source = "google";

    // --- LLM Optimization: 벡터 임베딩 및 배치 보강 필드 ---

    /** pgvector embedding (OpenAI text-embedding-3-small: 1536 dimensions) */
    @Column(columnDefinition = "vector(1536)", insertable = false, updatable = false)
    private String embedding;

    /** 장소 태그 (배치 보강으로 생성) */
    @Column(columnDefinition = "TEXT[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> tags;

    /** 추천 시간대 (배치 보강으로 생성) */
    @Column(name = "recommended_time_slots", columnDefinition = "TEXT[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> recommendedTimeSlots;

    /** 배치 보강 완료 시각 */
    @Column(name = "enriched_at")
    private OffsetDateTime enrichedAt;

    /** Google Places API 마지막 동기화 시각 */
    @Column(name = "google_synced_at")
    private OffsetDateTime googleSyncedAt;

    /** soft delete 활성 상태 */
    @Column
    @Builder.Default
    private Boolean active = true;

    /** soft delete 비활성화 시각 */
    @Column(name = "deactivated_at")
    private OffsetDateTime deactivatedAt;

    @Column(nullable = false)
    private OffsetDateTime savedAt;

    @PrePersist
    protected void onSave() {
        if (savedAt == null) savedAt = OffsetDateTime.now();
    }

    public boolean isStale() {
        return savedAt.isBefore(OffsetDateTime.now().minusDays(7));
    }

    public void updateImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public void update(String address, double lat, double lng, Double rating,
                       Integer priceLevel, String openingHours, String photoReference,
                       String sourceUrl, String description) {
        this.address = address;
        this.latitude = BigDecimal.valueOf(lat);
        this.longitude = BigDecimal.valueOf(lng);
        if (rating != null) this.rating = BigDecimal.valueOf(rating);
        if (priceLevel != null) this.priceLevel = priceLevel;
        if (openingHours != null) this.openingHours = openingHours;
        if (photoReference != null) this.photoReference = photoReference;
        if (sourceUrl != null) this.sourceUrl = sourceUrl;
        if (description != null && !description.isBlank()) this.description = description;
        this.savedAt = OffsetDateTime.now();
        this.googleSyncedAt = OffsetDateTime.now();
    }

    /** soft delete 처리 */
    public void deactivate() {
        this.active = false;
        this.deactivatedAt = OffsetDateTime.now();
    }

    /** 재활성화 */
    public void reactivate() {
        this.active = true;
        this.deactivatedAt = null;
    }

    /**
     * Anthropic Batch API 보강 결과를 반영한다.
     */
    public void enrichWith(List<String> newTags, String newDescription, List<String> newTimeSlots) {
        if (newTags != null && !newTags.isEmpty()) {
            this.tags = new java.util.ArrayList<>(newTags);
        }
        if (newDescription != null && !newDescription.isBlank()) {
            this.description = newDescription;
        }
        if (newTimeSlots != null && !newTimeSlots.isEmpty()) {
            this.recommendedTimeSlots = new java.util.ArrayList<>(newTimeSlots);
        }
        this.enrichedAt = OffsetDateTime.now();
    }

    /**
     * Foursquare 시딩 시 메타데이터만 갱신한다.
     */
    public void updateFoursquareMetadata(String category, List<String> newTags, String description) {
        if (category != null && !category.isBlank()) {
            this.category = category;
        }
        if (newTags != null && !newTags.isEmpty()) {
            if (this.tags == null || this.tags.isEmpty()) {
                this.tags = new java.util.ArrayList<>(newTags);
            } else {
                java.util.List<String> merged = new java.util.ArrayList<>(this.tags);
                for (String tag : newTags) {
                    if (!merged.contains(tag)) merged.add(tag);
                }
                this.tags = merged;
            }
        }
        if (description != null && !description.isBlank()) {
            this.description = description;
        }
        this.source = "foursquare";
        this.savedAt = OffsetDateTime.now();
    }
}
