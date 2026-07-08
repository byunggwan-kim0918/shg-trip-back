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

    /** Foursquare POI 전역 고유 ID (FSQ 시딩 데이터의 자연키) */
    @Column(name = "fsq_place_id", unique = true)
    private String fsqPlaceId;

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

    /** Google place_id (Place Details ID 직조회용). 첫 매칭 시 저장되고 이후 불변. */
    @Column(name = "google_place_id", length = 255)
    private String googlePlaceId;

    /** 벡터 검색 후보로 마지막 등장한 시각 — 월배치 사전채움의 인기도 기준. */
    @Column(name = "last_candidate_at")
    private OffsetDateTime lastCandidateAt;

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

    /**
     * Google 데이터 재동기화 주기(일). rating은 수개월 단위, priceLevel은 거의 불변,
     * openingHours(정기휴무)가 가장 민감하나 폐업 아닌 이상 분기 단위로 변동 → 30일이 균형점.
     * refresh 판정(isStale)과 배치 동기화 필터(findByIdAndNeedsSync)가 공유한다.
     */
    public static final int STALENESS_DAYS = 30;

    @PrePersist
    protected void onSave() {
        if (savedAt == null) savedAt = OffsetDateTime.now();
    }

    public boolean isStale() {
        return savedAt.isBefore(OffsetDateTime.now().minusDays(STALENESS_DAYS));
    }

    public void updateImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    /** 벡터 검색 후보로 등장했음을 기록 (배치 사전채움 인기도 기준). */
    public void markCandidateAppearance() {
        this.lastCandidateAt = OffsetDateTime.now();
    }

    public void update(String googlePlaceId, String address, double lat, double lng, Double rating,
                       Integer priceLevel, String openingHours, String photoReference,
                       String sourceUrl, String description) {
        if (googlePlaceId != null) this.googlePlaceId = googlePlaceId;
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

    /** Google place_id를 무효화 (Details 조회가 404 → 폐기된 ID). 다음 refresh 때 Text Search로 재매칭. */
    public void clearGooglePlaceId() {
        this.googlePlaceId = null;
    }

    /** 데이터 소스 변경 (Foursquare → Google) */
    public void setSource(String source) {
        this.source = source;
    }

    /**
     * Google 매칭 실패 시 동기화 시도 사실만 기록한다.
     * savedAt을 갱신하지 않으면 source='foursquare' OR savedAt<7일 조건에 영구히 걸려
     * 후보로 뽑힐 때마다 무한 재호출되므로, 실패해도 savedAt을 갱신해 7일 주기로만 재시도되게 한다.
     * source는 실제 Google 데이터로 확정된 것이 아니므로 그대로 유지한다.
     */
    public void markSyncAttempted() {
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
