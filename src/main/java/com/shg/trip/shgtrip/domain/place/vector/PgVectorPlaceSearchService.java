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

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(readOnly = true)
    public List<VectorSearchResult> search(VectorSearchRequest request) {
        if (request.queryVector() == null || request.queryVector().length == 0) {
            return List.of();
        }

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
                    vectorString, request.destination(), region, limitPerRegion
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
        String destination = request.destination();
        int limit = request.limit();

        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();

        sql.append("""
                SELECT p.id, p.name, p.address, p.category, p.tags, p.region, p.country,
                       p.latitude, p.longitude, p.description, p.rating,
                       1 - (p.embedding <=> ?::vector) AS similarity_score
                FROM places p
                WHERE p.embedding IS NOT NULL
                  AND p.active = true
                  AND p.country = ?
                """);
        params.add(vectorString);
        params.add(destination);

        // 단일 지역 필터
        if (regions != null && regions.size() == 1) {
            sql.append("  AND p.region = ?\n");
            params.add(regions.get(0));
        }

        sql.append("ORDER BY p.embedding <=> ?::vector\n");
        params.add(vectorString);

        sql.append("LIMIT ?");
        params.add(limit);

        return jdbcTemplate.query(sql.toString(), params.toArray(), (rs, rowNum) -> mapRow(rs));
    }

    /**
     * 지역별 분리 조회 쿼리 실행.
     */
    private List<VectorSearchResult> executeRegionQuery(
            String vectorString, String country, String targetRegion, int limitPerRegion) {

        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();

        sql.append("""
                SELECT p.id, p.name, p.address, p.category, p.tags, p.region, p.country,
                       p.latitude, p.longitude, p.description, p.rating,
                       1 - (p.embedding <=> ?::vector) AS similarity_score
                FROM places p
                WHERE p.embedding IS NOT NULL
                  AND p.active = true
                  AND p.country = ?
                  AND p.region = ?
                """);
        params.add(vectorString);
        params.add(country);
        params.add(targetRegion);

        sql.append("ORDER BY p.embedding <=> ?::vector\n");
        params.add(vectorString);

        sql.append("LIMIT ?");
        params.add(limitPerRegion);

        return jdbcTemplate.query(sql.toString(), params.toArray(), (rs, rowNum) -> mapRow(rs));
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
