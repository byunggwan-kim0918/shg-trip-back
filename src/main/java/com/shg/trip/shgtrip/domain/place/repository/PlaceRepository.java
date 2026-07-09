package com.shg.trip.shgtrip.domain.place.repository;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public interface PlaceRepository extends JpaRepository<Place, Long> {

    /**
     * 장소명 + 주소로 기존 장소 조회 (AI 응답 → DB 매핑용, 활성 상태만)
     * 중복 데이터 대비: source 우선순위 (google > foursquare > 기타), 최신순
     * List로 반환받아 첫 번째만 선택 (중복 시 예외 대신 우선순위 데이터 사용)
     */
    @Query("""
            SELECT p FROM Place p
            WHERE p.name = :name AND p.address = :address AND p.active = true
            ORDER BY CASE WHEN p.source = 'google' THEN 0 ELSE 1 END, p.createdAt DESC
            """)
    List<Place> findByNameAndAddressList(@Param("name") String name, @Param("address") String address);

    default Optional<Place> findByNameAndAddress(String name, String address) {
        return findByNameAndAddressList(name, address).stream().findFirst();
    }

    /**
     * 키워드로 장소 검색 (이름, 주소, 설명 포함)
     */
    @Query("""
            SELECT p FROM Place p
            WHERE LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(p.address) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(p.description) LIKE LOWER(CONCAT('%', :keyword, '%'))
            """)
    Page<Place> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /**
     * 좌표 반경 내 장소 검색 (Haversine 근사)
     */
    @Query("""
            SELECT p FROM Place p
            WHERE (6371 * acos(
                cos(radians(:lat)) * cos(radians(p.latitude)) *
                cos(radians(p.longitude) - radians(:lng)) +
                sin(radians(:lat)) * sin(radians(p.latitude))
            )) <= :radiusKm
            """)
    Page<Place> searchByRadius(
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("radiusKm") double radiusKm,
            Pageable pageable
    );

    /**
     * 카테고리 + 키워드 복합 검색
     */
    @Query("""
            SELECT p FROM Place p
            WHERE (:category IS NULL OR p.category = :category)
              AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(p.address) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """)
    Page<Place> searchByKeywordAndCategory(
            @Param("keyword") String keyword,
            @Param("category") String category,
            Pageable pageable
    );

    /**
     * 비동기 S3 이미지 URL 복구에 사용되는 메서드.
     * Place 엔티티의 imageUrl 필드를 직접 업데이트한다.
     */
    @Modifying
    @Query("UPDATE Place p SET p.imageUrl = :url WHERE p.id = :id")
    @Transactional
    void updateImageUrl(@Param("id") Long id, @Param("url") String url);

    /**
     * 국가, 지역, source='foursquare'로 장소 목록 조회.
     */
    @Query("SELECT p FROM Place p WHERE p.country = :country AND p.region = :region AND p.source = 'foursquare'")
    List<Place> findAllByCountryAndRegionAndSourceFoursquare(
            @Param("country") String country,
            @Param("region") String region
    );

    /**
     * region 목록 내 Lodging 카테고리 장소를 평점 순 조회 — 숙소 후보 보완용.
     */
    @Query("""
            SELECT p FROM Place p
            WHERE p.region IN :regions
              AND LOWER(p.category) LIKE '%lodging%'
              AND p.active = true
            ORDER BY p.rating DESC NULLS LAST
            """)
    List<Place> findTopAccommodationsByRegions(@Param("regions") List<String> regions, Pageable pageable);

    /**
     * 임베딩이 없고 활성 상태인 장소를 페이징 조회 — EmbeddingBatchJob 사용.
     */
    @Query("SELECT p FROM Place p WHERE p.embedding IS NULL AND p.active = true")
    Page<Place> findByEmbeddingIsNullAndActiveTrue(Pageable pageable);

    /**
     * 벡터 검색 후보 중 Google API 동기화가 필요한 place 조회.
     * 조건: googleSyncedAt IS NULL(동기화 시도 이력 없음) OR 마지막 시도가 staleThreshold 이전.
     * source='foursquare' 조건을 쓰지 않는 이유: Google 무매칭 장소는 source가 영구히
     * 'foursquare'로 남아 매 생성마다 무한 재호출됐음 — 실패 시에도 googleSyncedAt을
     * 기록(markSyncAttempted)하고 이 컬럼만으로 재시도 주기를 판단한다.
     * @param placeIds 벡터 검색 결과 place ID 목록
     * @param staleThreshold stale 판정 기준 시각 (now - STALENESS_DAYS)
     * @return 동기화 대상 place 목록
     */
    @Query("""
            SELECT p FROM Place p
            WHERE p.id IN :placeIds
            AND (p.googleSyncedAt IS NULL OR p.googleSyncedAt < :staleThreshold)
            AND p.active = true
            """)
    List<Place> findByIdAndNeedsSync(@Param("placeIds") List<Long> placeIds,
                                      @Param("staleThreshold") OffsetDateTime staleThreshold);

    /**
     * 벡터 검색 후보로 등장한 장소들의 last_candidate_at을 일괄 갱신 (인기도 추적).
     * 생성당 1회 벌크 UPDATE.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Place p SET p.lastCandidateAt = :now WHERE p.id IN :placeIds")
    void markCandidateAppearance(@Param("placeIds") List<Long> placeIds, @Param("now") OffsetDateTime now);

    /**
     * 월배치 사전채움 대상 조회: 최근 후보로 등장(last_candidate_at >= recentThreshold)했고
     * 동기화가 필요한(googleSyncedAt IS NULL OR < staleThreshold) 활성 장소를,
     * 최근 등장순으로 상위 N개(Pageable limit). "인기 장소 우선" 사전채움에 사용.
     */
    @Query("""
            SELECT p FROM Place p
            WHERE p.active = true
            AND p.lastCandidateAt IS NOT NULL
            AND p.lastCandidateAt >= :recentThreshold
            AND (p.googleSyncedAt IS NULL OR p.googleSyncedAt < :staleThreshold)
            ORDER BY p.lastCandidateAt DESC
            """)
    List<Place> findPrefetchTargets(@Param("recentThreshold") OffsetDateTime recentThreshold,
                                    @Param("staleThreshold") OffsetDateTime staleThreshold,
                                    Pageable pageable);

    /**
     * 미보강(enriched_at IS NULL)이고 활성 상태인 장소 페이징 조회 — BatchEnrichScheduler 사용.
     */
    Page<Place> findByEnrichedAtIsNullAndActiveTrue(Pageable pageable);

    /** TourAPI 시딩 중복 방지 — 같은 지역에 같은 이름의 장소가 이미 있으면 삽입하지 않는다. */
    boolean existsByNameAndRegion(String name, String region);

    /**
     * 지역 스코프 미보강 장소 페이징 조회 — 전량(수만 건) LLM 보강 비용을 통제하기 위해
     * BATCH_ENRICH_REGIONS로 지역 단위(예: Jeju부터) 점진 실행할 때 사용.
     */
    Page<Place> findByEnrichedAtIsNullAndActiveTrueAndRegionIn(List<String> regions, Pageable pageable);

    /**
     * 보강 성공 장소의 임베딩을 초기화한다 — 임베딩 텍스트가 tags/description을 포함하므로
     * 보강 내용이 벡터 검색에 반영되려면 재임베딩이 필요하다(embedding 컬럼은 updatable=false라
     * 엔티티 flush로는 못 바꿈). NULL이 되면 다음 임베딩 배치가 자동으로 다시 잡는다.
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE places SET embedding = NULL WHERE id IN (:ids)", nativeQuery = true)
    int resetEmbeddings(@Param("ids") List<Long> ids);

    /**
     * 배치 시작 시각 이후 신규 등록된 장소 수 (inserted).
     * created_at >= since 이고 created_at = updated_at 이면 이번 배치에서 처음 생성된 row.
     */
    @Query(value = """
            SELECT COUNT(*) FROM places
            WHERE source = 'foursquare'
              AND created_at >= :since
              AND created_at = updated_at
            """, nativeQuery = true)
    int countInsertedSince(@Param("since") OffsetDateTime since);

    /**
     * 배치 시작 시각 이후 갱신된 장소 수 (updated).
     * updated_at >= since 이고 updated_at > created_at 이면 이번 배치에서 기존 row가 갱신된 것.
     */
    @Query(value = """
            SELECT COUNT(*) FROM places
            WHERE source = 'foursquare'
              AND updated_at >= :since
              AND updated_at > created_at
            """, nativeQuery = true)
    int countUpdatedSince(@Param("since") OffsetDateTime since);

    /**
     * Foursquare 시딩용 네이티브 upsert.
     * fsq_place_id(FSQ 전역 고유 ID) 충돌 시 메타데이터만 갱신하고 핵심 필드는 보존한다.
     * 주소가 없는 체인점도 지점별 fsq_place_id로 정확히 구분되며, 청크 간/동시성 중복을 DB가 처리한다.
     *
     * <p>enrich 배치가 채운 한국어 tags/description은 재시딩이 덮어쓰지 않는다
     * (enriched_at IS NOT NULL 가드) — CSV의 tags는 카테고리 복사/파편이라 LLM 보강 결과보다
     * 항상 저품질이다. name도 마찬가지 — Google 동기화(languageCode=ko)가 채택한 한글명을
     * CSV의 영문명이 되돌리지 않도록 "기존 한글명 보존" CASE 가드를 둔다.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO places
                (fsq_place_id, name, address, latitude, longitude, country, region, category,
                 tags, description, source_url, source, active, saved_at, created_at, updated_at)
            VALUES
                (:fsqPlaceId, :name, :address, :latitude, :longitude, :country, :region, :category,
                 CAST(:tags AS TEXT[]), :description, :sourceUrl, 'foursquare', true, now(), now(), now())
            ON CONFLICT (fsq_place_id) DO UPDATE SET
                name = CASE WHEN places.name ~ '[가-힣]' THEN places.name
                            ELSE EXCLUDED.name END,
                address = EXCLUDED.address,
                latitude = EXCLUDED.latitude,
                longitude = EXCLUDED.longitude,
                country = EXCLUDED.country,
                region = EXCLUDED.region,
                category = EXCLUDED.category,
                tags = CASE WHEN places.enriched_at IS NOT NULL THEN places.tags
                            ELSE EXCLUDED.tags END,
                description = CASE WHEN places.enriched_at IS NOT NULL THEN places.description
                                   ELSE COALESCE(NULLIF(EXCLUDED.description, ''), places.description) END,
                source_url = COALESCE(EXCLUDED.source_url, places.source_url),
                source = 'foursquare',
                saved_at = now(),
                updated_at = now()
            """, nativeQuery = true)
    void upsertFoursquarePlace(
            @Param("fsqPlaceId") String fsqPlaceId,
            @Param("name") String name,
            @Param("address") String address,
            @Param("latitude") BigDecimal latitude,
            @Param("longitude") BigDecimal longitude,
            @Param("country") String country,
            @Param("region") String region,
            @Param("category") String category,
            @Param("tags") String tags,
            @Param("description") String description,
            @Param("sourceUrl") String sourceUrl
    );
}
