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
     * Google 리뷰 수. 인기도 신호이며 검색 랭킹이 평점과 함께 본다.
     *
     * <p>평점 단독으로는 인기도를 가릴 수 없다 — 실측(제주 관광지 평점순): 리뷰 소수의
     * 사라봉공원 5.0이 협재해수욕장 4.7(11위)보다 위에 온다. null은 "미상"이며,
     * 랭킹은 null을 평점 없음과 같은 중립으로 다룬다.
     */
    @Column(name = "user_rating_count")
    private Integer userRatingCount;

    private Integer priceLevel;

    private String openingHours;

    private String imageUrl;

    private String photoReference;

    private String sourceUrl;

    /** Google place_id (Place Details ID 직조회용). 첫 매칭 시 저장되고 이후 불변. */
    @Column(name = "google_place_id", length = 255)
    private String googlePlaceId;

    /** Wikidata 항목 ID(Q12345). 좌표 매칭으로 찾는다. null이면 매칭되는 항목이 없다는 뜻. */
    @Column(name = "wikidata_id", length = 16)
    private String wikidataId;

    /**
     * 그 항목을 다루는 위키 언어판 수 — 인기도 신호.
     *
     * <p>Google 리뷰 수를 약관상 보관할 수 없게 되면서 들여온 대체 신호다. 명소일수록 크고
     * 무명은 Wikidata에 항목 자체가 없다(실측 제주: 한라산 46, 우도 17, 성산일출봉 16,
     * 만장굴 11 / 열안지오름·사라봉은 없음). CC0라 저장 제약이 없다.
     */
    @Column(name = "wikidata_sitelinks")
    private Integer wikidataSitelinks;

    /**
     * 매칭을 시도한 시각.
     *
     * <p>{@link #wikidataSitelinks}가 null인 것이 "아직 안 봤음"인지 "봤는데 없음"인지
     * 구분한다. 후자를 매번 다시 조회하면 그게 바로 이번 Google 과금 사고의 구조다.
     */
    @Column(name = "wikidata_matched_at")
    private OffsetDateTime wikidataMatchedAt;

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

    /** 권장 체류시간(분, 배치 보강으로 생성) — 등산형 오름(150~240)과 해변 산책(60)을 구분 */
    @Column(name = "recommended_duration_minutes")
    private Integer recommendedDurationMinutes;

    /** 입장료(원, 배치 보강으로 생성) — 무료 명소는 0, 유료는 실제 입장료. 관광지 비용 산정용 */
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

    /*
     * 여기 있던 Google 쓰기 메서드 4개를 지웠다 — update(placeId, address, lat, lng, rating,
     * userRatingCount, priceLevel, openingHours, photoReference, sourceUrl, description),
     * clearGooglePlaceId(), setSource(), markSyncAttempted().
     *
     * 전부 호출처가 0이 됐고(GooglePlacesClient·PlaceRefreshService·GooglePlaceSyncScheduler가
     * 사라지면서), 하나같이 Maps 약관 3.2.3(a)가 금지하는 필드(평점·리뷰 수·영업시간·사진
     * 참조·주소·좌표)를 DB에 쓰는 통로였다. 남겨 두면 "호출 한 줄만 추가하면 되는" 상태라
     * 위반 경로가 조용히 되살아난다. 되살리려면 약관부터 다시 읽어야 하도록 지운다.
     *
     * google_place_id 컬럼 자체는 남는다 — Place ID는 약관상 유일하게 무기한 보관이 허용된다.
     */

    /**
     * 대표 이미지 URL을 채운다. 호출처는 {@code TourApiSeeder}의 이미지 백필 하나뿐이다.
     *
     * <p>Google 사진 경로를 지우면서 한 번 같이 지웠다가 되살렸다. 지울 당시엔 호출처가 0이었고
     * S3 업로더(= Google 사진 전용)가 유일한 사용처였기 때문인데, TourAPI {@code firstimage}는
     * 약관 제약이 없는 자체 출처라 쓰기 통로가 필요하다. Google 쪽 필드(평점·리뷰·영업시간·
     * 사진 참조)와 달리 이건 되살려도 되는 값이다.
     */
    public void updateImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    /**
     * Wikidata 매칭 결과를 기록한다. <b>실패도 기록한다</b>.
     *
     * <p>매칭되는 항목이 없으면 {@code wikidataId}·{@code sitelinks}가 null로 남지만
     * {@code matchedAt}은 찍힌다. 이 구분이 없으면 "Wikidata에 없는 장소"를 배치가 돌 때마다
     * 다시 조회한다 — 2026-10-08 Google 과금 사고가 정확히 그 구조였다(리뷰 데이터가 없는
     * 57곳이 조건에서 영원히 안 빠져 41회 반복 조회됨).
     *
     * @param wikidataId 매칭된 항목 ID, 없으면 null
     * @param sitelinks  그 항목의 위키 언어판 수, 없으면 null
     */
    public void markWikidataMatch(String wikidataId, Integer sitelinks) {
        this.wikidataId = wikidataId;
        this.wikidataSitelinks = sitelinks;
        this.wikidataMatchedAt = OffsetDateTime.now();
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
     * Anthropic Batch API 보강 결과를 반영한다. (기존 호환 — 체류시간·입장료 없이)
     */
    public void enrichWith(List<String> newTags, String newDescription, List<String> newTimeSlots) {
        enrichWith(newTags, newDescription, newTimeSlots, null, null);
    }

    /** 기존 호환 — 입장료 없이. */
    public void enrichWith(List<String> newTags, String newDescription, List<String> newTimeSlots,
                           Integer durationMinutes) {
        enrichWith(newTags, newDescription, newTimeSlots, durationMinutes, null);
    }

    /** 보강이 정한 일정 속 역할·음식 종류. null이면 기존 값을 유지한다. */
    public void assignRole(String newRole, String newCuisine) {
        if (newRole != null && !newRole.isBlank()) this.placeRole = newRole;
        if (newCuisine != null && !newCuisine.isBlank()) this.cuisine = newCuisine;
    }

    /**
     * Anthropic Batch API 보강 결과를 반영한다.
     */
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
        // 입장료는 0(무료)도 유효값이라 null만 스킵 — "무료"와 "미보강"을 구분한다.
        if (admissionFeeWon != null && admissionFeeWon >= 0) {
            this.admissionFee = admissionFeeWon;
        }
        this.enrichedAt = OffsetDateTime.now();
    }

    /*
     * updateName(String)도 지웠다. Google이 languageCode=ko로 준 한글 공식 명칭을 채택하는
     * 전용 메서드였고, 유일한 호출부 PlaceRefreshService가 사라지면서 고아가 됐다.
     * 이름은 적재 배치(TourAPI·Foursquare)가 정하는 값이다.
     */

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
