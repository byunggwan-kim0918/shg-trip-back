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
     * 장소명 + 주소로 기존 장소 조회 (AI 응답 → DB 매핑용, 활성 상태만).
     * 중복 행이 있으면 <b>데이터가 많은 쪽</b>을 고르고, 동률이면 최신순.
     *
     * <p>예전 정렬 기준은 {@code source = 'google'} 우선이었다. 당시엔 Google 동기화된 행이
     * 평점·리뷰·사진을 다 갖고 있어 맞는 기준이었다. 지금은 **정반대로 뒤집혔다** — 약관에 따라
     * Google 유래 필드를 전부 지웠으므로 {@code source='google'}로 표시된 763행이 오히려
     * 가장 빈약하다. 그 기준을 그대로 뒀다면 중복이 있을 때마다 빈 행을 골랐을 것이다.
     * 그래서 출처 이름 대신 실제 보유 데이터(이미지 → Wikidata 매칭)로 판단한다.
     */
    @Query("""
            SELECT p FROM Place p
            WHERE p.name = :name AND p.address = :address AND p.active = true
            ORDER BY CASE WHEN p.imageUrl IS NOT NULL THEN 0 ELSE 1 END,
                     CASE WHEN p.wikidataSitelinks IS NOT NULL THEN 0 ELSE 1 END,
                     p.createdAt DESC
            """)
    List<Place> findByNameAndAddressList(@Param("name") String name, @Param("address") String address);

    default Optional<Place> findByNameAndAddress(String name, String address) {
        return findByNameAndAddressList(name, address).stream().findFirst();
    }

    /**
     * 자유입력 장소명을 DB에서 해소할 때 쓰는 이름 부분일치 검색 — <b>여행지에서 가까운 순</b>.
     *
     * <p>좌표가 없거나 (0,0)인 행은 제외한다 — 동선·지도에 쓸 수 없는 장소를 사용자가 지정한
     * 필수 방문지로 넣으면 그 스텝이 통째로 깨진다.
     *
     * <p>정렬을 거리로 하는 이유: 처음엔 이름 길이순으로 잘랐는데, 그러면 "스타벅스"처럼 전국에
     * 수백 건인 이름에서 <b>짧은 이름 상위 N건</b>만 남아 정작 여행지 안의 지점이 잘려 나간다.
     * 거리로 자르면 호출자가 받은 N건이 이미 "가까운 후보"다. 거리 동률이면 이름이 짧은 쪽을
     * 먼저 준다 — "성산일출봉 주차장"보다 "성산일출봉"이 사용자 의도에 가깝다.
     *
     * <p>{@code searchByRadius}와 같은 Haversine 근사식을 쓴다(JPQL에서 검증된 형태).
     */
    @Query("""
            SELECT p FROM Place p
            WHERE p.active = true
              AND p.latitude IS NOT NULL AND p.longitude IS NOT NULL
              AND NOT (p.latitude = 0 AND p.longitude = 0)
              AND LOWER(p.name) LIKE LOWER(CONCAT('%', :name, '%'))
            ORDER BY (6371 * acos(
                cos(radians(:lat)) * cos(radians(p.latitude)) *
                cos(radians(p.longitude) - radians(:lng)) +
                sin(radians(:lat)) * sin(radians(p.latitude))
            )), LENGTH(p.name), p.id
            """)
    List<Place> findActiveByNameLikeNearest(@Param("name") String name,
                                            @Param("lat") double lat,
                                            @Param("lng") double lng,
                                            Pageable pageable);

    /** 여행지 기준 좌표를 모를 때 — 이름이 짧은(= 가장 타이트한) 순. */
    @Query("""
            SELECT p FROM Place p
            WHERE p.active = true
              AND p.latitude IS NOT NULL AND p.longitude IS NOT NULL
              AND NOT (p.latitude = 0 AND p.longitude = 0)
              AND LOWER(p.name) LIKE LOWER(CONCAT('%', :name, '%'))
            ORDER BY LENGTH(p.name), p.id
            """)
    List<Place> findActiveByNameLike(@Param("name") String name, Pageable pageable);

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
     * 벡터 검색 후보로 등장한 장소들의 last_candidate_at을 일괄 갱신 (인기도 추적).
     * 생성당 1회 벌크 UPDATE.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Place p SET p.lastCandidateAt = :now WHERE p.id IN :placeIds")
    void markCandidateAppearance(@Param("placeIds") List<Long> placeIds, @Param("now") OffsetDateTime now);

    /**
     * 지역별 좌표 경계 상자(bbox)를 <b>백분위수</b>로 계산한다 — Wikidata SPARQL 질의 범위용.
     *
     * <p>min/max를 그대로 쓰면 안 된다. 좌표가 오염된 행이 섞여 있어 서울의 위도 하한이
     * 19.694(동남아)까지 내려가고 부산도 마찬가지다. 2~98% 구간으로 자르면 서울이
     * 37.475~37.640 / 126.850~127.127로 실제 도시 범위가 나온다.
     *
     * @return {@code [region, minLat, minLon, maxLat, maxLon, placeCount]} 행 목록
     */
    @Query(value = """
            SELECT region,
                   percentile_cont(0.02) WITHIN GROUP (ORDER BY latitude)  AS min_lat,
                   percentile_cont(0.02) WITHIN GROUP (ORDER BY longitude) AS min_lon,
                   percentile_cont(0.98) WITHIN GROUP (ORDER BY latitude)  AS max_lat,
                   percentile_cont(0.98) WITHIN GROUP (ORDER BY longitude) AS max_lon,
                   count(*) AS place_count
            FROM places
            WHERE active = true
              AND latitude IS NOT NULL AND longitude IS NOT NULL
              AND NOT (latitude = 0 AND longitude = 0)
            GROUP BY region
            HAVING count(*) >= :minPlaces
            ORDER BY count(*) DESC
            """, nativeQuery = true)
    List<Object[]> findRegionBoundingBoxes(@Param("minPlaces") int minPlaces);

    /**
     * Wikidata 매칭을 아직 시도하지 않은 활성 장소 — 좌표가 있는 것만.
     *
     * <p>{@code wikidata_matched_at IS NULL}만 본다. 매칭 실패도 시각을 찍으므로 여기서 빠지고,
     * 그래서 "Wikidata에 없는 장소"를 배치가 돌 때마다 다시 조회하지 않는다.
     */
    @Query("""
            SELECT p FROM Place p
            WHERE p.active = true
              AND p.region = :region
              AND p.wikidataMatchedAt IS NULL
              AND p.latitude IS NOT NULL AND p.longitude IS NOT NULL
            ORDER BY p.id
            """)
    List<Place> findWikidataUnmatched(@Param("region") String region, Pageable pageable);

    /**
     * 미보강(enriched_at IS NULL)이고 활성 상태인 장소 페이징 조회 — BatchEnrichScheduler 사용.
     */
    Page<Place> findByEnrichedAtIsNullAndActiveTrue(Pageable pageable);

    /** TourAPI 시딩 중복 방지 — 같은 지역에 같은 이름의 장소가 이미 있으면 삽입하지 않는다. */
    boolean existsByNameAndRegion(String name, String region);

    /**
     * TourAPI 이미지 백필 대상 조회 — 같은 지역·같은 이름이면서 <b>이미지가 비어 있는</b> 활성 행.
     *
     * <p>시더는 중복을 스킵만 해서 기존 행을 영영 손대지 않는다. 그 사이 Google 동기화가
     * 이 행들의 {@code image_url}을 덮어썼고, 약관에 따라 Google 유래 데이터를 지우면서 같이
     * 비었다. 실측: TourAPI 제주 관광지 175건이 DB에 전부 있는데 이미지는 0건(154건은
     * {@code source}까지 'google'로 재라벨됨). 원본은 TourAPI가 아직 그대로 주고 있다(172/175).
     */
    @Query("""
            SELECT p FROM Place p
            WHERE p.name = :name AND p.region = :region
              AND p.active = true AND p.imageUrl IS NULL
            """)
    List<Place> findImagelessByNameAndRegion(@Param("name") String name, @Param("region") String region);

    /**
     * 이미지가 없는 관광지 — TourAPI 키워드 백필 대상.
     *
     * <p>지역 기반 조회(areaBasedList2)만으로는 닿지 않는 행이 있다. 실측: TourAPI가 성산일출봉의
     * {@code areacode}를 <b>빈 값</b>으로 내려주기 때문에 {@code areaCode=39} 목록에 영영 안 들어온다
     * (제주 관광지 totalCount가 arrange와 무관하게 175로 고정). 같은 레코드가 searchKeyword2로는
     * 이미지까지 정상 조회된다. 그래서 DB 쪽에서 빠진 행을 꺼내 이름으로 역조회한다.
     *
     * <p>인기도가 있는 행을 먼저 준다 — 일정에 실제로 뽑히는 건 그쪽이고, 상한에 걸려 잘리더라도
     * 체감 품질에 기여하는 순서로 채워진다.
     */
    @Query("""
            SELECT p FROM Place p
            WHERE p.active = true AND p.region = :region AND p.imageUrl IS NULL
              AND (p.category LIKE 'Landmarks%' OR p.category LIKE 'Arts and Entertainment%')
            ORDER BY p.wikidataSitelinks DESC NULLS LAST, p.id
            """)
    List<Place> findImagelessAttractions(@Param("region") String region, Pageable pageable);

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
