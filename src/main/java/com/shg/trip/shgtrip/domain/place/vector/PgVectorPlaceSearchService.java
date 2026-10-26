package com.shg.trip.shgtrip.domain.place.vector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Array;
import java.util.*;

/**
 * pgvector 기반 벡터 검색 구현체.
 * <p>
 * cosine distance 연산자({@code <=>})를 사용하여 유사도 검색을 수행한다.
 * 5일+ 여행 시 지역별 분리 조회 로직을 포함한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PgVectorPlaceSearchService implements PlaceVectorSearchService {

    private static final int REGION_SPLIT_THRESHOLD = 1;

    /**
     * HNSW 탐색 폭. 기본값(40)은 "전역 최근접 40개를 훑고 그 안에서 WHERE를 적용"하는 방식이라,
     * 여행지·카테고리 필터가 좁을수록 결과가 통째로 비는 사고가 난다(실측: 오사카 숙소 슬롯 0건 —
     * 한국어 쿼리의 전역 최근접이 전부 국내 숙소라 JP/Osaka 필터에서 전멸). 폭을 넓히고
     * iterative_scan을 켜서 필터 통과분을 LIMIT만큼 채울 때까지 더 탐색하게 한다.
     */
    private static final int HNSW_EF_SEARCH = 200;

    /**
     * 인기도 시딩 갈래의 상·하한. 유사도 갈래와 비슷한 규모로 두어 한쪽이 윈도를 독식하지 않게 한다.
     * 제주 관광지 기준 12건이면 sitelinks 상위(한라산 46 ~ 만장굴 11)를 모두 담는다.
     */
    private static final int POPULARITY_SEED_MIN = 5;
    private static final int POPULARITY_SEED_MAX = 20;

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(readOnly = true)
    public List<VectorSearchResult> search(VectorSearchRequest request) {
        if (request.queryVector() == null || request.queryVector().length == 0) {
            return List.of();
        }

        applyAnnSearchSettings();

        List<String> regions = request.regions();

        // 지역이 여러 개인 경우 → 지역별 분리 조회 후 합산
        if (regions != null && regions.size() > REGION_SPLIT_THRESHOLD) {
            return searchByRegions(request);
        }

        // 기본 검색 (단일 지역 또는 지역 필터 없음)
        return searchSingle(request, regions);
    }

    @Override
    @Transactional
    public void store(Long placeId, float[] embedding) {
        String vectorString = toVectorString(embedding);
        jdbcTemplate.update(
                "UPDATE places SET embedding = ?::vector WHERE id = ?",
                vectorString, placeId
        );
        log.debug("Stored embedding for place id={}", placeId);
    }

    @Override
    @Transactional
    public void delete(Long placeId) {
        jdbcTemplate.update(
                "UPDATE places SET embedding = NULL WHERE id = ?",
                placeId
        );
        log.debug("Deleted embedding for place id={}", placeId);
    }

    @Override
    @Transactional
    public void storeBatch(Map<Long, float[]> embeddings) {
        if (embeddings == null || embeddings.isEmpty()) {
            return;
        }

        String sql = "UPDATE places SET embedding = ?::vector WHERE id = ?";

        List<Object[]> batchArgs = embeddings.entrySet().stream()
                .map(entry -> new Object[]{toVectorString(entry.getValue()), entry.getKey()})
                .toList();

        jdbcTemplate.batchUpdate(sql, batchArgs);
        log.info("Stored embeddings for {} places in batch", embeddings.size());
    }

    /**
     * 필터가 있는 ANN 검색의 리콜을 보장하는 세션 설정. 트랜잭션 로컬(SET LOCAL)이라 커넥션 풀에
     * 반납된 뒤 다른 쿼리에 영향을 주지 않는다. pgvector 확장이 늦게 로드되면 파라미터가 아직
     * 등록되지 않아 실패할 수 있으므로, 실패는 로그만 남기고 기본값으로 진행한다(검색 중단 방지).
     */
    private void applyAnnSearchSettings() {
        try {
            jdbcTemplate.execute("SET LOCAL hnsw.ef_search = " + HNSW_EF_SEARCH);
            jdbcTemplate.execute("SET LOCAL hnsw.iterative_scan = 'relaxed_order'");
        } catch (Exception e) {
            log.warn("HNSW 검색 파라미터 설정 실패 (기본값으로 진행): {}", e.getMessage());
        }
    }

    /**
     * 지역별 분리 조회 (5일+ 여행).
     * 각 지역에 대해 별도 쿼리를 실행하고 결과를 합산한다.
     */
    private List<VectorSearchResult> searchByRegions(VectorSearchRequest request) {
        List<String> regions = request.regions();
        int limitPerRegion = Math.max(1, request.limit() / regions.size());
        String vectorString = toVectorString(request.queryVector());

        List<VectorSearchResult> allResults = new ArrayList<>();

        for (String region : regions) {
            List<VectorSearchResult> regionResults = executeRegionQuery(
                    vectorString, request.destination(), region, limitPerRegion,
                    request.excludedCategoryPatterns()
            );
            allResults.addAll(regionResults);
        }

        // 유사도 기준 내림차순 정렬 후 전체 limit 적용
        allResults.sort(Comparator.comparingDouble(VectorSearchResult::similarityScore).reversed());

        if (allResults.size() > request.limit()) {
            return allResults.subList(0, request.limit());
        }
        return allResults;
    }

    /**
     * 단일 지역 또는 지역 필터 없는 기본 검색.
     */
    private List<VectorSearchResult> searchSingle(VectorSearchRequest request, List<String> regions) {
        String vectorString = toVectorString(request.queryVector());

        StringBuilder where = new StringBuilder("""
                WHERE p.embedding IS NOT NULL
                  AND p.active = true
                  AND p.country = ?
                  AND NOT (p.latitude = 0 AND p.longitude = 0)
                """);
        List<Object> whereParams = new ArrayList<>();
        whereParams.add(request.destination());

        // 단일 지역 필터
        if (regions != null && regions.size() == 1) {
            where.append("  AND p.region = ?\n");
            whereParams.add(regions.get(0));
        }

        appendCategoryExclusions(where, whereParams, request.excludedCategoryPatterns());
        appendRadiusFilter(where, whereParams, request.center(), request.radiusKm());

        return queryTwoLeg(vectorString, where.toString(), whereParams, request.limit());
    }

    /**
     * 후보 윈도를 <b>유사도 최근접</b>과 <b>인기도 상위</b> 두 갈래로 가져온다.
     *
     * <p>유사도만으로 윈도를 자르면 대표 명소가 후보에 들어오지 못한다. 실측(제주 관광지 364곳,
     * 쿼리 "제주 자연 경관 오름 해안 절경 관광지"): 코사인 순위가 낮아질수록 평균 인기도가
     * 오히려 올라간다. 성산일출봉이 339위, 주상절리대가 242위이고 상위 12위는 열안지오름·
     * 유수암마을처럼 Wikidata에 항목조차 없는 곳으로 찬다.
     *
     * <p>원인은 데이터나 가중치가 아니라 <b>질의가 일반 명사</b>라는 점이다. "오름·해안" 같은
     * 일반어로 질의하면 이름·태그가 그 일반어 자체인 장소(열안지<b>오름</b>, 남원 큰엉<b>해안</b>)가
     * 이기고, 고유한 정체성을 가진 명소(일출봉·주상절리)는 어휘가 겹치지 않아 밀린다.
     * 유사도 재랭킹을 어떻게 바꿔도 윈도 밖에 있으면 이길 수 없으므로 윈도 자체를 넓힌다.
     *
     * <p>두 갈래를 UNION할 뿐 채택은 하지 않는다 — 호출부가 슬롯 최소 유사도로 거르고
     * 하이브리드 점수로 재랭킹한다. 명소가 슬롯 의미에 안 맞으면 거기서 탈락한다.
     */
    private List<VectorSearchResult> queryTwoLeg(
            String vectorString, String whereClause, List<Object> whereParams, int limit) {

        int seedLimit = Math.min(POPULARITY_SEED_MAX, Math.max(POPULARITY_SEED_MIN, limit));

        String columns = """
                SELECT p.id, p.name, p.address, p.category, p.tags, p.region, p.country,
                       p.latitude, p.longitude, p.description, p.rating, p.user_rating_count,
                       p.wikidata_sitelinks,
                       1 - (p.embedding <=> ?::vector) AS similarity_score
                FROM places p
                """;

        String sql = "(" + columns + whereClause + "ORDER BY p.embedding <=> ?::vector\nLIMIT ?)\n"
                + "UNION\n"
                + "(" + columns + whereClause + "  AND p.wikidata_sitelinks > 0\n"
                + "ORDER BY p.wikidata_sitelinks DESC\nLIMIT ?)";

        List<Object> params = new ArrayList<>();
        params.add(vectorString);          // 1번 갈래 SELECT의 similarity_score
        params.addAll(whereParams);
        params.add(vectorString);          // 1번 갈래 ORDER BY
        params.add(limit);
        params.add(vectorString);          // 2번 갈래 SELECT의 similarity_score
        params.addAll(whereParams);
        params.add(seedLimit);

        return jdbcTemplate.query(sql, params.toArray(), (rs, rowNum) -> mapRow(rs));
    }

    /**
     * 지역별 분리 조회 쿼리 실행.
     */
    private List<VectorSearchResult> executeRegionQuery(
            String vectorString, String country, String targetRegion, int limitPerRegion,
            List<String> excludedCategoryPatterns) {

        StringBuilder where = new StringBuilder("""
                WHERE p.embedding IS NOT NULL
                  AND p.active = true
                  AND p.country = ?
                  AND p.region = ?
                  AND NOT (p.latitude = 0 AND p.longitude = 0)
                """);
        List<Object> whereParams = new ArrayList<>();
        whereParams.add(country);
        whereParams.add(targetRegion);

        appendCategoryExclusions(where, whereParams, excludedCategoryPatterns);

        return queryTwoLeg(vectorString, where.toString(), whereParams, limitPerRegion);
    }

    /**
     * 중심 좌표 기준 반경 필터를 덧붙인다. 위경도 bounding box로 좁힌 뒤 정확한 거리는 호출부가
     * 다시 거른다 — box만으로 충분히 좁아져 ANN 리콜 손실 없이 "그 지역 안"을 보장할 수 있다.
     */
    private void appendRadiusFilter(StringBuilder sql, List<Object> params, double[] center, double radiusKm) {
        if (center == null || radiusKm <= 0) return;
        double latDelta = radiusKm / 111.0;
        double lngDelta = radiusKm / (111.0 * Math.max(0.2, Math.cos(Math.toRadians(center[0]))));
        sql.append("  AND p.latitude BETWEEN ? AND ?\n");
        sql.append("  AND p.longitude BETWEEN ? AND ?\n");
        params.add(java.math.BigDecimal.valueOf(center[0] - latDelta));
        params.add(java.math.BigDecimal.valueOf(center[0] + latDelta));
        params.add(java.math.BigDecimal.valueOf(center[1] - lngDelta));
        params.add(java.math.BigDecimal.valueOf(center[1] + lngDelta));
    }

    /**
     * 슬롯에서 "명백히 틀린 대분류"만 배제하는 WHERE 절을 덧붙인다(소프트 필터의 하한선).
     * 마법사 카테고리 id와 DB category(Foursquare 계층 경로)는 형식이 달라 하드 매칭이 불가능하고
     * DB 오적재도 흔하므로, 의미 판별은 벡터 유사도에 맡기고 여기서는 카페 슬롯에 숙소가 나오는
     * 식의 명백한 오분류만 제거한다. 패턴이 비어 있으면 아무것도 배제하지 않는다.
     */
    private void appendCategoryExclusions(StringBuilder sql, List<Object> params, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return;
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) continue;
            // category가 NULL인 행은 판단 불가 — 보존한다(과도 필터링 방지).
            sql.append("  AND (p.category IS NULL OR p.category NOT ILIKE ?)\n");
            params.add("%" + pattern.trim() + "%");
        }
    }

    /**
     * ResultSet 한 행을 VectorSearchResult로 매핑.
     */
    private VectorSearchResult mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        // tags 배열 처리
        List<String> tags = null;
        Array tagsArray = rs.getArray("tags");
        if (tagsArray != null) {
            String[] tagValues = (String[]) tagsArray.getArray();
            tags = tagValues != null ? Arrays.asList(tagValues) : null;
        }

        BigDecimal rating = rs.getBigDecimal("rating");

        // 리뷰 수는 백필 전에는 NULL이다. getInt는 NULL을 0으로 돌려주므로 wasNull로 구분해야
        // "리뷰 0개"와 "미상"이 섞이지 않는다 — 랭킹이 둘을 다르게 다룬다.
        int reviewCountRaw = rs.getInt("user_rating_count");
        Integer userRatingCount = rs.wasNull() ? null : reviewCountRaw;

        // sitelinks도 세 상태를 구분해야 한다 — NULL(미매칭) / 0(항목없음) / 1+(인기도).
        // getInt가 NULL을 0으로 돌려주므로 wasNull 없이는 "아직 안 봤음"과 "봐도 없음"이 섞인다.
        int sitelinksRaw = rs.getInt("wikidata_sitelinks");
        Integer wikidataSitelinks = rs.wasNull() ? null : sitelinksRaw;

        return new VectorSearchResult(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("address"),
                rs.getString("category"),
                tags,
                rs.getString("region"),
                rs.getString("country"),
                rs.getBigDecimal("latitude"),
                rs.getBigDecimal("longitude"),
                rs.getString("description"),
                rating,
                userRatingCount,
                wikidataSitelinks,
                rs.getDouble("similarity_score")
        );
    }

    /**
     * float 배열을 pgvector가 인식하는 문자열 형식으로 변환.
     * 예: [0.1, 0.2, 0.3] → "[0.1,0.2,0.3]"
     */
    private String toVectorString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
