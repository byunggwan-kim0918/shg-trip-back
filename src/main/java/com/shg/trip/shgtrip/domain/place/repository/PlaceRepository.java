package com.shg.trip.shgtrip.domain.place.repository;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public interface PlaceRepository extends JpaRepository<Place, Long> {

    /**
     * 장소명 + 주소로 기존 장소 조회 (AI 응답 → DB 매핑용, 활성 상태만).
     * 중복 행이 있으면 <b>데이터가 많은 쪽</b>(이미지 → Wikidata 매칭)을 고르고, 동률이면 최신순.
     *
     * <p>{@code source='google'} 행은 약관에 따라 Google 유래 필드를 지워 가장 빈약하므로 출처 이름으로 고르지 않는다.
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
     * 자유입력 장소명을 DB에서 해소할 때 쓰는 이름 부분일치 검색. <b>여행지에서 가까운 순</b>이다.
     *
     * <p>좌표가 없거나 (0,0)인 행은 제외한다. 동선·지도에 쓸 수 없는 장소를 사용자가 지정한
     * 필수 방문지로 넣으면 그 스텝이 통째로 깨진다.
     *
     * <p>이름 길이순으로 자르면 "스타벅스"처럼 전국에 수백 건인 이름에서 여행지 안의 지점이 잘려 나가므로
     * 거리로 자른다. 거리 동률이면 이름이 짧은 쪽을 먼저 준다("성산일출봉 주차장"보다 "성산일출봉").
     *
     * <p>{@code searchByRadius}와 같은 Haversine 근사식을 쓴다(JPQL에서 검증된 형태).
     */
    @Query("""
            SELECT p FROM Place p
            WHERE p.active = true
              AND p.latitude IS NOT NULL AND p.longitude IS NOT NULL
              AND NOT (p.latitude = 0 AND p.longitude = 0)
              AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :name, '%'))
                OR LOWER(p.nameKo) LIKE LOWER(CONCAT('%', :name, '%')))
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

    /** 여행지 기준 좌표를 모를 때 쓴다. 이름이 짧은(= 가장 타이트한) 순. */
    @Query("""
            SELECT p FROM Place p
            WHERE p.active = true
              AND p.latitude IS NOT NULL AND p.longitude IS NOT NULL
              AND NOT (p.latitude = 0 AND p.longitude = 0)
              AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :name, '%'))
                OR LOWER(p.nameKo) LIKE LOWER(CONCAT('%', :name, '%')))
            ORDER BY LENGTH(p.name), p.id
            """)
    List<Place> findActiveByNameLike(@Param("name") String name, Pageable pageable);

    /**
     * 평점은 대부분 비어 있어 평점순이면 설명에만 키워드가 든 장소가 실제 명소보다 앞선다.
     * 순서를 쿼리가 정하므로 Pageable에 정렬을 넣으면 안 된다.
     *
     * @param keyword  소문자로 바꾸고 LIKE 와일드카드(\\, %, _)를 이스케이프한 값
     * @param category null이면 전체
     */
    @Query(value = """
            SELECT p FROM Place p
            WHERE p.active = true
              AND (p.placeRole IS NULL OR p.placeRole <> 'NOT_VISITABLE')
              AND (:category IS NULL OR p.category = :category)
              AND (LOWER(p.name) LIKE CONCAT('%', :keyword, '%') ESCAPE '\\'
                OR LOWER(p.nameKo) LIKE CONCAT('%', :keyword, '%') ESCAPE '\\'
                OR LOWER(p.address) LIKE CONCAT('%', :keyword, '%') ESCAPE '\\'
                OR LOWER(p.description) LIKE CONCAT('%', :keyword, '%') ESCAPE '\\')
            ORDER BY CASE
                       WHEN LOWER(p.name) = :exact OR LOWER(p.nameKo) = :exact THEN 0
                       WHEN LOWER(p.name) LIKE CONCAT(:keyword, ' (%') ESCAPE '\\' THEN 0
                       WHEN LOWER(p.name) LIKE CONCAT(:keyword, '%') ESCAPE '\\'
                         OR LOWER(p.nameKo) LIKE CONCAT(:keyword, '%') ESCAPE '\\' THEN 1
                       WHEN LOWER(p.name) LIKE CONCAT('%', :keyword, '%') ESCAPE '\\'
                         OR LOWER(p.nameKo) LIKE CONCAT('%', :keyword, '%') ESCAPE '\\' THEN 2
                       ELSE 3
                     END,
                     p.wikidataSitelinks DESC NULLS LAST,
                     p.rating DESC NULLS LAST,
                     LENGTH(p.name),
                     p.id
            """,
            countQuery = """
            SELECT COUNT(p) FROM Place p
            WHERE p.active = true
              AND (p.placeRole IS NULL OR p.placeRole <> 'NOT_VISITABLE')
              AND (:category IS NULL OR p.category = :category)
              AND (LOWER(p.name) LIKE CONCAT('%', :keyword, '%') ESCAPE '\\'
                OR LOWER(p.nameKo) LIKE CONCAT('%', :keyword, '%') ESCAPE '\\'
                OR LOWER(p.address) LIKE CONCAT('%', :keyword, '%') ESCAPE '\\'
                OR LOWER(p.description) LIKE CONCAT('%', :keyword, '%') ESCAPE '\\')
            """)
    Page<Place> searchByKeyword(@Param("keyword") String keyword,
                                @Param("exact") String exact,
                                @Param("category") String category,
                                Pageable pageable);

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


    boolean existsBySourceAndRegionAndName(String source, String region, String name);

    /** 해외 여행지 관문(공항·역). 배치가 destinations.json gateways로 넣는다. */
    @Query("SELECT p FROM Place p WHERE p.region IN :regions AND p.source = 'gateway' AND p.active = true ORDER BY p.id")
    List<Place> findGatewaysByRegions(@Param("regions") List<String> regions);

    /**
     * 숙소 후보 보완용. region 목록 내 Lodging 카테고리 장소를 평점 순으로 준다. 호텔 부대시설은
     * {@code CandidatePolicy.isCertainlyNotVisitable}과 같은 기준으로 뺀다(보강이 LODGING 역할을 붙인 행도 있다).
     */
    @Query("""
            SELECT p FROM Place p
            WHERE p.region IN :regions
              AND LOWER(p.category) LIKE '%lodging%'
              AND LOWER(p.category) NOT LIKE '%> hotel pool'
              AND LOWER(p.category) NOT LIKE '%> hotel gym'
              AND (p.placeRole IS NULL OR p.placeRole <> 'NOT_VISITABLE')
              AND p.active = true
            ORDER BY p.rating DESC NULLS LAST
            """)
    List<Place> findTopAccommodationsByRegions(@Param("regions") List<String> regions, Pageable pageable);

    @Query("SELECT p FROM Place p WHERE p.embedding IS NULL AND p.active = true"
            + " AND (p.placeRole IS NULL OR p.placeRole <> 'NOT_VISITABLE')")
    Page<Place> findByEmbeddingIsNullAndActiveTrue(Pageable pageable);

    @Query("SELECT p FROM Place p WHERE p.embedding IS NULL AND p.active = true AND p.region IN :regions"
            + " AND (p.placeRole IS NULL OR p.placeRole <> 'NOT_VISITABLE')")
    Page<Place> findByEmbeddingIsNullAndActiveTrueAndRegionIn(@Param("regions") List<String> regions,
                                                             Pageable pageable);


    /** 벡터 검색 후보로 등장한 장소들의 last_candidate_at을 생성당 1회 벌크로 갱신한다. */
    @Modifying
    @Transactional
    @Query("UPDATE Place p SET p.lastCandidateAt = :now WHERE p.id IN :placeIds")
    void markCandidateAppearance(@Param("placeIds") List<Long> placeIds, @Param("now") OffsetDateTime now);

    /**
     * 지역별 좌표 경계 상자(bbox)를 <b>백분위수</b>로 계산한다. Wikidata SPARQL 질의 범위용.
     *
     * <p>좌표가 오염된 행이 섞여 있어 min/max를 쓰면 서울 위도 하한이 동남아까지 내려가므로 2~98% 구간으로 자른다.
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
     * Wikidata 매칭 후보: 지역의 좌표 있는 활성 장소. 행: (id, name, latitude, longitude, category, source).
     *
     * <p>엔티티에는 임베딩이 실려 있어 통째로 올리면 서울만 수백 MB라 컬럼만 읽는다.
     */
    @Query(value = """
            SELECT id, name, latitude, longitude, category, source
            FROM places
            WHERE active = true
              AND region = :region
              AND latitude IS NOT NULL AND longitude IS NOT NULL
            """, nativeQuery = true)
    List<Object[]> findWikidataCandidates(@Param("region") String region);

    /**
     * 지역 전체를 "항목 없음"(sitelinks 0)으로 덮는다. 직후에 매칭된 장소만 다시 쓰므로
     * 호출부가 같은 트랜잭션으로 묶는다. 매칭 실패도 시각을 찍어야 미조회(NULL)와 구분된다.
     */
    @Modifying
    @Query(value = """
            UPDATE places
               SET wikidata_id = NULL, wikidata_sitelinks = 0, wikidata_matched_at = now()
             WHERE active = true
               AND region = :region
               AND latitude IS NOT NULL AND longitude IS NOT NULL
            """, nativeQuery = true)
    int resetWikidataForRegion(@Param("region") String region);

    /**
     * 이미 DB에 있는 Wikidata 항목 id(활성·비활성 모두). 명소 적재의 재실행 중복 방지용.
     * 인기도 매칭이 지역을 다시 계산하며 wikidata_id를 비울 수 있어, 명소 적재 행은 출처 URL의 id도 본다.
     */
    @Query(value = """
            SELECT wikidata_id FROM places WHERE wikidata_id IS NOT NULL
            UNION
            SELECT substring(source_url FROM 'Q[0-9]+$') FROM places
             WHERE source = 'wikidata' AND source_url ~ 'Q[0-9]+$'
            """, nativeQuery = true)
    List<String> findAllWikidataIds();

    /**
     * 해외 명소 적재가 "이미 있다"고 볼 Wikidata 항목 id. 활성 행의 wikidata_id와 명소 적재 행(상태 무관)의 출처 id다.
     * 비활성 FSQ 행의 wikidata_id까지 보면 그 행이 추출에서 빠진 뒤로 명소가 다시 들어오지 못한다.
     */
    @Query(value = """
            SELECT wikidata_id FROM places WHERE wikidata_id IS NOT NULL AND active = true
            UNION
            SELECT substring(source_url FROM 'Q[0-9]+$') FROM places
             WHERE source = 'wikidata' AND source_url ~ 'Q[0-9]+$'
            """, nativeQuery = true)
    List<String> findKnownLandmarkQids();

    /**
     * 비어 있는 한국어 표시 이름을 Wikidata 한국어 라벨로 채운다. 라벨이 정식명("로쿠온지")인 곳이 있어
     * 보강·보정표가 이미 정한 이름은 덮지 않는다. 보강이 끝난 행은 임베딩을 다시 만든다. 보강 전인 행은 보강 결과를
     * 반영할 때 다시 만들어지므로 그때까지 검색에 남겨 둔다.
     */
    @Modifying
    @Query(value = """
            UPDATE places SET name_ko = :nameKo,
                   embedding = CASE WHEN enriched_at IS NOT NULL THEN NULL ELSE embedding END
             WHERE id = :id AND name_ko IS NULL
            """, nativeQuery = true)
    int fillNameKo(@Param("id") long id, @Param("nameKo") String nameKo);

    /**
     * 해외 Wikidata 명소 행의 이름·여행지를 지금 규칙(현지 라벨, 가장 가까운 여행지)에 맞추고 비어 있는
     * 한국어 이름을 채운다({@link #fillNameKo}와 같은 이유로 덮지 않는다). 주소가 적재 때 만든 "이름, 지역" 꼴이면
     * 같이 바꾼다(SET의 name·region은 바꾸기 전 값이다). 임베딩은 이름·지역이 바뀔 때와, 보강이 끝난 행에
     * name_ko가 새로 채워질 때만 지운다({@link #fillNameKo}와 같은 규칙). 지우면 다음 임베딩 단계까지 검색 후보에서 빠진다.
     *
     * @return 바뀐 행 수(이미 같으면 0)
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE places
               SET name = :name, name_ko = COALESCE(name_ko, CAST(:nameKo AS varchar)), region = :region,
                   address = CASE WHEN address = name || ', ' || region THEN :name || ', ' || :region ELSE address END,
                   embedding = CASE WHEN name <> :name OR region IS DISTINCT FROM :region
                                         OR (name_ko IS NULL AND CAST(:nameKo AS varchar) IS NOT NULL
                                             AND enriched_at IS NOT NULL)
                                    THEN NULL ELSE embedding END
             WHERE source = 'wikidata' AND source_url = :sourceUrl
               AND (name <> :name OR region IS DISTINCT FROM :region
                    OR (name_ko IS NULL AND CAST(:nameKo AS varchar) IS NOT NULL))
            """, nativeQuery = true)
    int refreshWikidataLandmark(@Param("sourceUrl") String sourceUrl, @Param("name") String name,
                                @Param("nameKo") String nameKo, @Param("region") String region);

    @Modifying
    @Query(value = """
            UPDATE places
               SET wikidata_id = :wikidataId, wikidata_sitelinks = :sitelinks, wikidata_matched_at = now()
             WHERE id = :id
            """, nativeQuery = true)
    int updateWikidataMatch(@Param("id") long id, @Param("wikidataId") String wikidataId,
                            @Param("sitelinks") int sitelinks);

    /**
     * 보강 대상: 미보강(enriched_at IS NULL)·활성. BatchEnrichScheduler가 첫 페이지만 반복 조회한다.
     * 카테고리만으로 비방문(NOT_VISITABLE)이 확정된 곳은 보강해도 일정에 안 나오므로 뺀다.
     * Wikidata 매칭을 아직 거치지 않은 행도 뺀다. 인기도 없이 보강하면 유명 식당이 설명 없이 보강 완료로 남는다.
     * 응답이 3번 실패한 행(enrich_attempts)도 뺀다. 매 실행 같은 행을 다시 사는 비용만 든다.
     *
     * <p><b>순서</b>: 유명한 곳(Wikidata sitelinks) → 식당·카페가 아닌 곳 → id. 보강은 하루 가까이 걸릴 수
     * 있어서, 중간에 끊겨도 일정에 자주 나오는 곳은 끝나 있게 한다.
     *
     * <p><b>excludedIds</b>: 이번 실행에서 이미 실패한 장소. 메모리에서 거르면 실패가 한 페이지를 넘을 때
     * 남은 장소를 두고 끝나므로 쿼리에서 뺀다. 빈 컬렉션은 호출부가 -1 하나로 바꿔 넘긴다.
     */
    @Query("""
            SELECT p FROM Place p
            WHERE p.enrichedAt IS NULL AND p.active = true
              AND p.wikidataMatchedAt IS NOT NULL
              AND p.enrichAttempts < 3
              AND (p.placeRole IS NULL OR p.placeRole <> 'NOT_VISITABLE')
              AND p.id NOT IN :excludedIds
            ORDER BY COALESCE(p.wikidataSitelinks, 0) DESC,
                     CASE WHEN p.category LIKE 'Dining and Drinking%' THEN 1 ELSE 0 END,
                     p.id
            """)
    List<Place> findEnrichTargets(@Param("excludedIds") Collection<Long> excludedIds, Pageable pageable);

    /** Wikidata 매칭을 기다리느라 {@link #findEnrichTargets}에서 빠진 행 수. */
    @Query(value = """
            SELECT count(*) FROM places
            WHERE enriched_at IS NULL AND active = true AND wikidata_matched_at IS NULL
              AND (place_role IS NULL OR place_role <> 'NOT_VISITABLE')
            """, nativeQuery = true)
    long countEnrichWaitingForWikidata();

    /**
     * 보강 컬럼만 쓴다. 엔티티째 저장하면 결과를 기다린 동안(최대 하루) 바뀐 컬럼을 그때 읽은 값으로 되돌린다.
     * 배열은 chr(31)로 이은 문자열로 받고, name_ko는 비어 있을 때만 채운다(Wikidata 라벨·보정표 우선).
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE places SET
                tags = string_to_array(CAST(:tags AS text), chr(31)),
                description = CAST(:description AS text),
                recommended_time_slots = string_to_array(CAST(:slots AS text), chr(31)),
                recommended_duration_minutes = CAST(:duration AS integer),
                admission_fee = CAST(:fee AS integer),
                admission_fee_local = CAST(:feeLocal AS integer),
                admission_currency = CAST(:currency AS varchar),
                place_role = CAST(:role AS varchar),
                cuisine = CAST(:cuisine AS varchar),
                name_ko = COALESCE(name_ko, CAST(:nameKo AS varchar)),
                traveler_fame = COALESCE(CAST(:fame AS smallint), traveler_fame),
                enriched_at = CAST(:enrichedAt AS timestamptz),
                updated_at = now()
             WHERE id = :id
            """, nativeQuery = true)
    int writeEnrichmentColumns(@Param("id") long id, @Param("tags") String tags,
                               @Param("description") String description, @Param("slots") String slots,
                               @Param("duration") Integer duration, @Param("fee") Integer fee,
                               @Param("feeLocal") Integer feeLocal, @Param("currency") String currency,
                               @Param("role") String role, @Param("cuisine") String cuisine,
                               @Param("nameKo") String nameKo, @Param("fame") Short fame,
                               @Param("enrichedAt") OffsetDateTime enrichedAt);

    String ARRAY_SEPARATOR = "\u001f";

    default int writeEnrichment(Place p) {
        return writeEnrichmentColumns(p.getId(), joinArray(p.getTags()), p.getDescription(),
                joinArray(p.getRecommendedTimeSlots()), p.getRecommendedDurationMinutes(), p.getAdmissionFee(),
                p.getAdmissionFeeLocal(), p.getAdmissionCurrency(), p.getPlaceRole(), p.getCuisine(), p.getNameKo(),
                p.getTravelerFame(), p.getEnrichedAt());
    }

    private static String joinArray(List<String> values) {
        return values == null ? null : String.join(ARRAY_SEPARATOR, values);
    }

    /** 보강 응답이 3번 실패해 대상에서 빠진 활성 미보강 행 수. */
    @Query(value = "SELECT count(*) FROM places WHERE active = true AND enriched_at IS NULL AND enrich_attempts >= 3",
            nativeQuery = true)
    long countEnrichExhausted();

    /** 장소 탓인 보강 실패만 센다(BatchEnrichScheduler.applyResults). */
    @Modifying
    @Transactional
    @Query(value = "UPDATE places SET enrich_attempts = enrich_attempts + 1 WHERE id IN (:ids)", nativeQuery = true)
    int incrementEnrichAttempts(@Param("ids") Collection<Long> ids);

    /**
     * 보정표 한국어 이름(QID 기준). 바뀐 행은 임베딩을 다시 만든다(임베딩 텍스트에 name_ko가 들어간다).
     * 식음료 행은 뺀다. 체인 항목은 지점명을 뗀 이름으로 여러 지점에 붙어 있어 지점 이름이 모두 같아진다.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE places SET name_ko = :nameKo, embedding = NULL
             WHERE (wikidata_id = :qid OR (source = 'wikidata' AND source_url = 'https://www.wikidata.org/wiki/' || :qid))
               AND category NOT LIKE 'Dining and Drinking%'
               AND name_ko IS DISTINCT FROM :nameKo
            """, nativeQuery = true)
    int overrideNameKoByQid(@Param("qid") String qid, @Param("nameKo") String nameKo);

    /** 보정표 한국어 이름(국가·원문 이름 기준). Wikidata에 연결되지 않은 장소용. */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE places SET name_ko = :nameKo, embedding = NULL
             WHERE country = :country AND name = :name AND name_ko IS DISTINCT FROM :nameKo
            """, nativeQuery = true)
    int overrideNameKoByName(@Param("country") String country, @Param("name") String name,
                             @Param("nameKo") String nameKo);

    /**
     * 역할 미확정 활성 장소의 (id, name, category)를 id 순으로 준다. {@code PlaceRoleBackfillJob}이 keyset 페이지로 읽는다.
     * 엔티티로 읽으면 임베딩 컬럼까지 실려 매 실행 수 GB를 읽는다.
     */
    @Query(value = """
            SELECT id, name, category FROM places
            WHERE active = true AND place_role IS NULL AND id > :afterId
            ORDER BY id
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findRoleBackfillTargets(@Param("afterId") long afterId, @Param("limit") int limit);

    /** 카테고리로 비방문이 확정된 장소에 역할을 기록한다. 보강이 이미 역할을 정한 행은 건드리지 않는다. */
    @Modifying
    @Transactional
    @Query(value = "UPDATE places SET place_role = 'NOT_VISITABLE' WHERE id IN (:ids) AND place_role IS NULL",
            nativeQuery = true)
    int markNotVisitable(@Param("ids") List<Long> ids);

    /** TourAPI 시딩 중복 방지. 같은 지역에 같은 이름의 장소가 이미 있으면 삽입하지 않는다. */
    boolean existsByNameAndRegion(String name, String region);

    /** 같은 지역·같은 이름·같은 대분류(카테고리 첫 단계)인 장소가 있는지. TourAPI 중복 판정용. */
    boolean existsByNameAndRegionAndCategoryStartingWith(String name, String region, String categoryPrefix);

    /**
     * 총칭 "Restaurant"로 적재된 TourAPI 음식점의 업종 카테고리를 갱신한다. category가 임베딩 텍스트에
     * 들어가므로 임베딩을 비워 다음 배치가 다시 만들게 한다. 보강 결과(태그·역할·음식 종류)도 옛 업종
     * 기준이라 함께 비운다. 안 비우면 베이커리로 바뀐 행에 "식당" 태그가 남아 식사도 카페도 아닌 상태가 된다.
     * 배치 호출이라 트랜잭션이 필요하다.
     *
     * @return 고친 행 수
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE places
            SET category = :category, embedding = NULL, enriched_at = NULL, place_role = NULL, cuisine = NULL,
                updated_at = now()
            WHERE name = :name AND region = :region AND source = 'tourapi'
              AND category = 'Dining and Drinking > Restaurant'
            """, nativeQuery = true)
    int refineGenericTourApiDiningCategory(@Param("name") String name, @Param("region") String region,
                                           @Param("category") String category);

    /**
     * 좌표가 깨진 TourAPI 행((0,0) 또는 좌표 변환 오류로 생긴 남중국해 고정점)을 정상 좌표로 고치고 되살린다.
     *
     * <p>V40이 이 행들을 비활성화했는데, 시더는 같은 이름이 있으면 새로 넣지 않으므로 여기서 고치지
     * 않으면 영영 빠진다. 호출부(배치)에 트랜잭션이 없어 {@code @Transactional}이 빠지면
     * TransactionRequiredException이 난다. 새 좌표의 유효성은 호출부(TourApiSeeder.toPlace)가 보장한다.
     *
     * @return 고친 행 수
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE places
            SET latitude = :lat, longitude = :lng, active = true, deactivated_at = NULL, deactivated_reason = NULL,
                updated_at = now()
            WHERE name = :name AND region = :region AND source = 'tourapi'
              AND ((latitude = 0 AND longitude = 0)
                   OR (abs(latitude - 19.694) < 0.01 AND abs(longitude - 117.993) < 0.01))
            """, nativeQuery = true)
    int repairBrokenTourApiCoordinates(@Param("name") String name, @Param("region") String region,
                                       @Param("lat") java.math.BigDecimal lat,
                                       @Param("lng") java.math.BigDecimal lng);

    /**
     * TourAPI 이미지 백필 대상: 같은 지역·같은 이름이면서 <b>이미지가 비어 있는</b> 활성 행.
     * 시더는 중복을 건너뛰기만 해서 Google 데이터 삭제로 비워진 {@code image_url}은 여기서만 채워진다.
     */
    @Query("""
            SELECT p FROM Place p
            WHERE p.name = :name AND p.region = :region
              AND p.active = true AND p.imageUrl IS NULL
            """)
    List<Place> findImagelessByNameAndRegion(@Param("name") String name, @Param("region") String region);

    /**
     * 이미지가 없는 관광지. TourAPI 키워드 백필 대상이다.
     *
     * <p>TourAPI가 일부 레코드의 {@code areacode}를 빈 값으로 내려줘 지역 기반 조회(areaBasedList2)로는
     * 닿지 않으므로, DB 쪽에서 빠진 행을 꺼내 이름으로 역조회한다.
     *
     * <p>인기도가 있는 행을 먼저 준다. 일정에 실제로 뽑히는 건 그쪽이라 상한에 잘려도
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
     * {@link #findEnrichTargets}의 지역 스코프판. BATCH_ENRICH_REGIONS로 지역 단위(예: Jeju부터)
     * 점진 실행할 때 쓴다. 순서·제외 규칙은 같다.
     */
    @Query("""
            SELECT p FROM Place p
            WHERE p.enrichedAt IS NULL AND p.active = true
              AND p.wikidataMatchedAt IS NOT NULL
              AND p.enrichAttempts < 3
              AND (p.placeRole IS NULL OR p.placeRole <> 'NOT_VISITABLE')
              AND p.region IN :regions
              AND p.id NOT IN :excludedIds
            ORDER BY COALESCE(p.wikidataSitelinks, 0) DESC,
                     CASE WHEN p.category LIKE 'Dining and Drinking%' THEN 1 ELSE 0 END,
                     p.id
            """)
    List<Place> findEnrichTargetsInRegions(@Param("regions") List<String> regions,
                                           @Param("excludedIds") Collection<Long> excludedIds,
                                           Pageable pageable);

    /**
     * 속성 보강(v2) 대상: 후보로 등장했거나 인기도가 있는 장소 중 v2 속성이 없는 것.
     * 교통 거점은 분류할 필드가 없어 뺀다.
     * 최근 후보 등장 → 인기도 순이라 중간에 끊겨도 일정에 자주 나오는 곳부터 끝나 있다.
     */
    @Query("""
            SELECT p FROM Place p
            WHERE p.active = true
              AND (p.placeRole IS NULL OR p.placeRole NOT IN ('NOT_VISITABLE', 'TRANSIT'))
              AND p.region IN :regions
              AND (p.lastCandidateAt IS NOT NULL OR p.wikidataSitelinks > 0)
              AND (p.enrichVersion IS NULL OR p.enrichVersion < 2)
              AND p.id NOT IN :excludedIds
            ORDER BY CASE WHEN p.lastCandidateAt IS NULL THEN 1 ELSE 0 END,
                     p.lastCandidateAt DESC,
                     COALESCE(p.wikidataSitelinks, 0) DESC,
                     p.id
            """)
    List<Place> findAttributeTargetsInRegions(@Param("regions") List<String> regions,
                                              @Param("excludedIds") Collection<Long> excludedIds,
                                              Pageable pageable);

    @Query("""
            SELECT COUNT(p) FROM Place p
            WHERE p.active = true
              AND (p.placeRole IS NULL OR p.placeRole NOT IN ('NOT_VISITABLE', 'TRANSIT'))
              AND p.region IN :regions
              AND (p.lastCandidateAt IS NOT NULL OR p.wikidataSitelinks > 0)
              AND (p.enrichVersion IS NULL OR p.enrichVersion < 2)
            """)
    long countAttributeTargetsInRegions(@Param("regions") List<String> regions);

    /**
     * 속성 분류만 교체한다. 엔티티 save는 배치 대기(수 시간) 전에 읽은 값으로 행 전체를 덮어써
     * 그사이 갱신된 last_candidate_at·TourAPI 원문(official)을 되돌린다.
     *
     * @param classifiedJson official을 뺀 {@link com.shg.trip.shgtrip.domain.place.entity.PlaceAttributes} JSON
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE places
            SET attributes = (COALESCE(attributes, CAST('{}' AS jsonb))
                              - ARRAY['venue', 'subType', 'visitStyle', 'hikeLevel', 'lodgingType', 'breakfast'])
                             || CAST(:classifiedJson AS jsonb),
                enrich_version = :version
            WHERE id = :id
            """, nativeQuery = true)
    int writeAttributeClassification(@Param("id") Long id, @Param("classifiedJson") String classifiedJson,
                                     @Param("version") short version);

    /**
     * TourAPI 매칭 대상 (id, name, category, latitude, longitude). 후보 등장·인기 장소 중 아직 시도하지 않은 것.
     * 숙소·교통·행사는 영업시간을 쓰지 않아 뺀다.
     */
    @Query(value = """
            SELECT id, name, category, latitude, longitude FROM places
            WHERE active AND region IN (:regions)
              AND tourapi_content_id IS NULL AND tourapi_checked_at IS NULL
              AND (last_candidate_at IS NOT NULL OR wikidata_sitelinks > 0)
              AND (place_role IS NULL OR place_role NOT IN ('NOT_VISITABLE', 'TRANSIT', 'LODGING'))
              AND category NOT ILIKE '%lodging%'
              AND category NOT LIKE 'Travel and Transportation%'
              AND category NOT LIKE 'Event%'
            ORDER BY last_candidate_at DESC NULLS LAST, COALESCE(wikidata_sitelinks, 0) DESC, id
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findTourApiMatchTargets(@Param("regions") List<String> regions, @Param("limit") int limit);

    /** 매칭 결과를 기록한다. 못 찾았으면 contentId·type이 null이고 시도 시각만 남는다. */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE places
            SET tourapi_content_id = COALESCE(CAST(:contentId AS varchar), tourapi_content_id),
                tourapi_content_type = COALESCE(CAST(:contentType AS smallint), tourapi_content_type),
                tourapi_checked_at = now()
            WHERE id = :id
            """, nativeQuery = true)
    int writeTourApiMatch(@Param("id") Long id, @Param("contentId") String contentId,
                          @Param("contentType") Short contentType);

    /** 상세 조회 대상 (id, tourapi_content_id, tourapi_content_type). */
    @Query(value = """
            SELECT id, tourapi_content_id, tourapi_content_type FROM places
            WHERE active AND region IN (:regions)
              AND tourapi_content_id IS NOT NULL AND tourapi_detail_at IS NULL
              AND tourapi_content_type IN (12, 14, 28, 38, 39)
            ORDER BY last_candidate_at DESC NULLS LAST, COALESCE(wikidata_sitelinks, 0) DESC, id
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findTourApiDetailTargets(@Param("regions") List<String> regions, @Param("limit") int limit);

    /**
     * 상세 원문을 attributes.official에 넣고, 판독된 영업시간·요금만 덮어쓴다(null이면 기존 값 유지).
     * 분류 키는 건드리지 않는다.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE places
            SET attributes = COALESCE(attributes, CAST('{}' AS jsonb))
                             || jsonb_build_object('official', CAST(:officialJson AS jsonb)),
                opening_hours = COALESCE(CAST(:hours AS varchar), opening_hours),
                opening_hours_source = CASE WHEN CAST(:hours AS varchar) IS NULL THEN opening_hours_source
                                            ELSE 'official' END,
                admission_fee = COALESCE(CAST(:fee AS integer), admission_fee),
                tourapi_detail_at = now()
            WHERE id = :id
            """, nativeQuery = true)
    int writeTourApiDetail(@Param("id") Long id, @Param("officialJson") String officialJson,
                           @Param("hours") String hours, @Param("fee") Integer fee);

    /**
     * 보강 성공 장소의 임베딩을 초기화한다. 임베딩 텍스트가 tags/description을 포함하고 embedding 컬럼은
     * updatable=false라 엔티티 flush로는 못 바꾼다. NULL이 되면 다음 임베딩 배치가 다시 잡는다.
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

    /** 이번 적재(seenSince) 전부터 있던 활성 Foursquare 행 수. 빠진 비율의 분모다. */
    @Query(value = """
            SELECT count(*) FROM places
            WHERE source = 'foursquare' AND active AND country = :country AND region = :region
              AND created_at < :seenSince
            """, nativeQuery = true)
    int countExistingFoursquare(@Param("country") String country, @Param("region") String region,
                                @Param("seenSince") OffsetDateTime seenSince);

    /**
     * 이번 적재(seenSince 이후 upsert)에서 다시 나오지 않은 활성 Foursquare 행 수.
     * Wikidata·TourAPI와 연결된 행은 다른 시더가 중복 판정에 써서 끄면 대표 명소가 다시 들어오지 않으므로 뺀다.
     */
    @Query(value = """
            SELECT count(*) FROM places
            WHERE source = 'foursquare' AND active AND country = :country AND region = :region
              AND saved_at < :seenSince AND wikidata_id IS NULL AND tourapi_content_id IS NULL
            """, nativeQuery = true)
    int countStaleFoursquare(@Param("country") String country, @Param("region") String region,
                             @Param("seenSince") OffsetDateTime seenSince);

    /**
     * 위 행을 최대 limit건 deactivated_reason = 'stale'로 끈다. 다음 적재에 다시 나오면 upsert가 되살린다.
     * 한 번에 다 끄면 생성 경로의 후보 기록(markCandidateAppearance)이 행 잠금을 오래 기다린다.
     *
     * @return 끈 행 수. limit보다 작으면 남은 대상이 없다.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE places
            SET active = false, deactivated_at = now(), deactivated_reason = 'stale', updated_at = now()
            WHERE id IN (
                SELECT id FROM places
                WHERE source = 'foursquare' AND active AND country = :country AND region = :region
                  AND saved_at < :seenSince AND wikidata_id IS NULL AND tourapi_content_id IS NULL
                LIMIT :limit)
            """, nativeQuery = true)
    int deactivateStaleFoursquare(@Param("country") String country, @Param("region") String region,
                                  @Param("seenSince") OffsetDateTime seenSince, @Param("limit") int limit);

    /**
     * Foursquare 시딩용 네이티브 upsert. fsq_place_id(FSQ 전역 고유 ID) 충돌 시 메타데이터만 갱신하고 핵심 필드는 보존한다.
     * 주소가 없는 체인점도 지점별 fsq_place_id로 구분되며, 청크 간·동시 실행 중복을 DB가 처리한다.
     *
     * <p>enriched_at IS NOT NULL이면 tags/description을 덮어쓰지 않는다. CSV의 tags는 카테고리 복사·파편이라
     * LLM 보강 결과보다 항상 저품질이다. name도 CSV 영문명이 기존 한글명을 되돌리지 않도록 CASE 가드를 둔다.
     *
     * <p>카테고리가 바뀌면 임베딩·역할·음식 종류·보강 시각을 비운다. 전부 옛 카테고리 기준이라
     * 남겨 두면 다음 배치가 다시 만들지 않는다. 이름이 바뀌면 임베딩·음식 종류·보강 시각·한국어 이름을 비운다.
     *
     * <p>월간 추출에서 빠져 꺼진 행(deactivated_reason = 'stale')이 다시 나오면 되살린다.
     * 정책으로 끈 행('policy')은 그대로 둔다.
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
                embedding = CASE WHEN places.category IS DISTINCT FROM EXCLUDED.category
                                      OR (places.name !~ '[가-힣]' AND places.name IS DISTINCT FROM EXCLUDED.name)
                                 THEN NULL ELSE places.embedding END,
                place_role = CASE WHEN places.category IS DISTINCT FROM EXCLUDED.category
                                  THEN NULL ELSE places.place_role END,
                cuisine = CASE WHEN places.category IS DISTINCT FROM EXCLUDED.category
                                    OR (places.name !~ '[가-힣]' AND places.name IS DISTINCT FROM EXCLUDED.name)
                               THEN NULL ELSE places.cuisine END,
                enriched_at = CASE WHEN places.category IS DISTINCT FROM EXCLUDED.category
                                        OR (places.name !~ '[가-힣]' AND places.name IS DISTINCT FROM EXCLUDED.name)
                                   THEN NULL ELSE places.enriched_at END,
                enrich_attempts = CASE WHEN places.category IS DISTINCT FROM EXCLUDED.category
                                            OR (places.name !~ '[가-힣]' AND places.name IS DISTINCT FROM EXCLUDED.name)
                                       THEN 0 ELSE places.enrich_attempts END,
                name_ko = CASE WHEN places.name !~ '[가-힣]' AND places.name IS DISTINCT FROM EXCLUDED.name
                               THEN NULL ELSE places.name_ko END,
                active = CASE WHEN places.deactivated_reason = 'stale' THEN true ELSE places.active END,
                deactivated_at = CASE WHEN places.deactivated_reason = 'stale' THEN NULL
                                      ELSE places.deactivated_at END,
                deactivated_reason = CASE WHEN places.deactivated_reason = 'stale' THEN NULL
                                          ELSE places.deactivated_reason END,
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
