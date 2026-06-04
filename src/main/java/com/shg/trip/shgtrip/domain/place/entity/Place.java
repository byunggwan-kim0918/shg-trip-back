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

    @Column(nullable = false, length = 100)
    private String category;

    @Column(length = 100)
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

    /**
     * 데이터 출처. 'google', 'foursquare', 'llm_generated' 중 하나.
     */
    @Column(length = 50)
    private String source;

    /**
     * 세미콜론(;) 구분 태그 문자열. 벡터 검색 및 보강에 활용된다.
     */
    @Column(columnDefinition = "TEXT")
    private String tags;

    /**
     * 장소 활성 여부. false이면 검색에서 제외된다.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private OffsetDateTime savedAt;

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

    /** 데이터 소스 ('google', 'foursquare', 'llm_generated') */
    @Column(length = 50)
    @Builder.Default
    private String source = "google";

    /** soft delete 활성 상태 */
    @Column
    @Builder.Default
    private Boolean active = true;

    /** soft delete 비활성화 시각 */
    @Column(name = "deactivated_at")
    private OffsetDateTime deactivatedAt;

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

    /**
     * Foursquare CSV에서 가져온 메타데이터를 갱신한다.
     * 핵심 필드(name, address, latitude, longitude)는 변경하지 않는다.
     *
     * @param tags        세미콜론 구분 태그 문자열
     * @param description 장소 설명
     */
    public void updateFoursquareMetadata(String tags, String description) {
        if (tags != null && !tags.isBlank()) {
            this.tags = tags;
        }
        if (description != null && !description.isBlank()) {
            this.description = description;
        }
        this.savedAt = OffsetDateTime.now();
    }
}
