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
     * 장소명 + 주소로 기존 장소 조회 (AI 응답 → DB 매핑용)
     */
    Optional<Place> findByNameAndAddress(String name, String address);

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
     * 임베딩이 없고 활성 상태인 장소를 페이징 조회 — EmbeddingBatchJob 사용.
     */
    @Query("SELECT p FROM Place p WHERE p.embedding IS NULL AND p.active = true")
    Page<Place> findByEmbeddingIsNullAndActiveTrue(Pageable pageable);

    /**
     * 미보강(enriched_at IS NULL)이고 활성 상태인 장소 페이징 조회 — BatchEnrichScheduler 사용.
     */
    Page<Place> findByEnrichedAtIsNullAndActiveTrue(Pageable pageable);

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
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO places
                (fsq_place_id, name, address, latitude, longitude, country, region, category,
                 tags, description, source, active, saved_at, created_at, updated_at)
            VALUES
                (:fsqPlaceId, :name, :address, :latitude, :longitude, :country, :region, :category,
                 CAST(:tags AS TEXT[]), :description, 'foursquare', true, now(), now(), now())
            ON CONFLICT (fsq_place_id) DO UPDATE SET
                name = EXCLUDED.name,
                address = EXCLUDED.address,
                latitude = EXCLUDED.latitude,
                longitude = EXCLUDED.longitude,
                country = EXCLUDED.country,
                region = EXCLUDED.region,
                category = EXCLUDED.category,
                tags = EXCLUDED.tags,
                description = COALESCE(NULLIF(EXCLUDED.description, ''), places.description),
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
            @Param("description") String description
    );
}
