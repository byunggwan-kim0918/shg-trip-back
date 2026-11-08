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

    /**
     * Google 리뷰 수. 평점 단독으로는 리뷰 소수의 5.0이 명소를 이겨 검색 랭킹이 평점과 함께 본다.
     * null은 "미상"이며, 랭킹은 null을 평점 없음과 같은 중립으로 다룬다.
     */
    @Column(name = "user_rating_count")
    private Integer userRatingCount;

    private Integer priceLevel;

    private String openingHours;

    private String imageUrl;

    private String photoReference;

    private String sourceUrl;

    /** Google place_id. Maps 약관상 무기한 보관이 허용되는 유일한 Google 필드다. 첫 매칭 시 저장되고 이후 불변. */
    @Column(name = "google_place_id", length = 255)
    private String googlePlaceId;

    /** Wikidata 항목 ID(Q12345). 좌표 매칭으로 찾는다. null이면 매칭되는 항목이 없다는 뜻. */
    @Column(name = "wikidata_id", length = 16)
    private String wikidataId;

    /**
     * 그 항목을 다루는 위키 언어판 수. 인기도 신호다.
     *
     * <p>명소일수록 크고 무명은 Wikidata에 항목 자체가 없다. CC0라 저장 제약이 없다.
     */
    @Column(name = "wikidata_sitelinks")
    private Integer wikidataSitelinks;

    /**
     * 매칭을 시도한 시각.
     *
     * <p>{@link #wikidataSitelinks}가 null인 것이 "아직 안 봤음"인지 "봤는데 없음"인지
     * 구분한다. 구분이 없으면 후자를 배치마다 다시 조회한다.
     */
    @Column(name = "wikidata_matched_at")
    private OffsetDateTime wikidataMatchedAt;

    /** 벡터 검색 후보로 마지막 등장한 시각. 월배치 사전채움의 인기도 기준. */
    @Column(name = "last_candidate_at")
    private OffsetDateTime lastCandidateAt;

    /** 데이터 소스 ('google', 'foursquare', 'llm_generated') */
    @Column(length = 50)
    @Builder.Default
    private String source = "google";


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

    /** 권장 체류시간(분, 배치 보강으로 생성). 등산형 오름과 해변 산책을 구분한다. */
    @Column(name = "recommended_duration_minutes")
    private Integer recommendedDurationMinutes;

    /** 입장료(원, 배치 보강으로 생성). 무료 명소는 0이며 관광지 비용 산정에 쓴다. */
    @Column(name = "admission_fee")
    private Integer admissionFee;

    /**
     * 일정 속 역할(배치 보강으로 생성): MEAL, CAFE, BAR, SIGHT, ACTIVITY, SHOPPING, MARKET, LODGING,
     * TRANSIT, NOT_VISITABLE. 없으면 카테고리·이름 추정으로 대신한다(CandidatePolicy.roleOf).
     */
    @Column(name = "place_role", length = 20)
    private String placeRole;

    /** 음식 종류(배치 보강으로 생성, MEAL만): MEAT, SEAFOOD, NOODLE, SOUP, KOREAN_SET, JAPANESE 등. */
    @Column(length = 20)
    private String cuisine;

    /** 배치 보강 완료 시각 */
    @Column(name = "enriched_at")
    private OffsetDateTime enrichedAt;

    /** 속성 보강(v2) 결과와 TourAPI 공식 원문. null이면 일정 로직이 카테고리·이름으로 추정한다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private PlaceAttributes attributes;

    /** 속성 보강 버전. 2 미만이면 v2 보강 대상이다. */
    @Column(name = "enrich_version")
    private Short enrichVersion;

    @Column(name = "tourapi_content_id", length = 20)
    private String tourapiContentId;

    @Column(name = "tourapi_content_type")
    private Short tourapiContentType;

    /** TourAPI 매칭을 시도한 시각. 못 찾은 장소를 매 실행 다시 조회하지 않게 한다. */
    @Column(name = "tourapi_checked_at")
    private OffsetDateTime tourapiCheckedAt;

    /** detailIntro2를 조회한 시각. */
    @Column(name = "tourapi_detail_at")
    private OffsetDateTime tourapiDetailAt;

    /** opening_hours 출처. 'official'이면 TourAPI 이용시간·쉬는날에서 변환한 값이다. */
    @Column(name = "opening_hours_source", length = 10)
    private String openingHoursSource;

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

    /** 벡터 검색 후보로 등장했음을 기록 (배치 사전채움 인기도 기준). */
    public void markCandidateAppearance() {
        this.lastCandidateAt = OffsetDateTime.now();
    }


    /**
     * TourAPI {@code firstimage}는 약관 제약이 없는 출처라 쓰기 통로를 둔다.
     * Google 사진은 Maps 약관상 저장할 수 없어 이 경로로 쓰지 않는다.
     */
    public void updateImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    /**
     * Wikidata 매칭 결과를 기록한다. <b>실패도 기록한다</b>.
     *
     * <p>매칭되는 항목이 없으면 {@code wikidataId}·{@code sitelinks}가 null로 남지만
     * {@code matchedAt}은 찍힌다. 이 구분이 없으면 "Wikidata에 없는 장소"를 배치가 돌 때마다
     * 다시 조회한다.
     *
     * @param wikidataId 매칭된 항목 ID, 없으면 null
     * @param sitelinks  그 항목의 위키 언어판 수, 없으면 null
     */
    public void markWikidataMatch(String wikidataId, Integer sitelinks) {
        this.wikidataId = wikidataId;
        this.wikidataSitelinks = sitelinks;
        this.wikidataMatchedAt = OffsetDateTime.now();
    }

    public void deactivate() {
        this.active = false;
        this.deactivatedAt = OffsetDateTime.now();
    }

    public void reactivate() {
        this.active = true;
        this.deactivatedAt = null;
    }

    /** 체류시간·입장료 없이 반영하는 호환 오버로드. */
    public void enrichWith(List<String> newTags, String newDescription, List<String> newTimeSlots) {
        enrichWith(newTags, newDescription, newTimeSlots, null, null);
    }

    /** 입장료 없이 반영하는 호환 오버로드. */
    public void enrichWith(List<String> newTags, String newDescription, List<String> newTimeSlots,
                           Integer durationMinutes) {
        enrichWith(newTags, newDescription, newTimeSlots, durationMinutes, null);
    }

    /** 보강이 정한 일정 속 역할·음식 종류. null이면 기존 값을 유지한다. */
    public void assignRole(String newRole, String newCuisine) {
        if (newRole != null && !newRole.isBlank()) this.placeRole = newRole;
        if (newCuisine != null && !newCuisine.isBlank()) this.cuisine = newCuisine;
    }

    public void enrichWith(List<String> newTags, String newDescription, List<String> newTimeSlots,
                           Integer durationMinutes, Integer admissionFeeWon) {
        if (newTags != null && !newTags.isEmpty()) {
            this.tags = new java.util.ArrayList<>(newTags);
        }
        if (newDescription != null && !newDescription.isBlank()) {
            this.description = newDescription;
        }
        if (newTimeSlots != null && !newTimeSlots.isEmpty()) {
            this.recommendedTimeSlots = new java.util.ArrayList<>(newTimeSlots);
        }
        if (durationMinutes != null && durationMinutes > 0) {
            this.recommendedDurationMinutes = durationMinutes;
        }
        // 입장료는 0(무료)도 유효값이라 null만 거른다. "무료"와 "미보강"을 구분한다.
        if (admissionFeeWon != null && admissionFeeWon >= 0) {
            this.admissionFee = admissionFeeWon;
        }
        this.enrichedAt = OffsetDateTime.now();
    }


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
