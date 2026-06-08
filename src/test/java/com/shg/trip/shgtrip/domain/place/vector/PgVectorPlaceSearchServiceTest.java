package com.shg.trip.shgtrip.domain.place.vector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PgVectorPlaceSearchServiceTest {

    private PgVectorPlaceSearchService service;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        service = new PgVectorPlaceSearchService(jdbcTemplate);
    }

    @Nested
    @DisplayName("search")
    class SearchTests {

        @Test
        @DisplayName("queryVector가 null이면 빈 목록을 반환한다")
        void search_nullVector_returnsEmpty() {
            VectorSearchRequest request = new VectorSearchRequest(
                    null, "일본", null, List.of("관광"), null, null, 80
            );

            List<VectorSearchResult> results = service.search(request);

            assertThat(results).isEmpty();
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("queryVector가 빈 배열이면 빈 목록을 반환한다")
        void search_emptyVector_returnsEmpty() {
            VectorSearchRequest request = new VectorSearchRequest(
                    new float[0], "일본", null, List.of("관광"), null, null, 80
            );

            List<VectorSearchResult> results = service.search(request);

            assertThat(results).isEmpty();
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("단일 지역 필터로 검색한다")
        void search_singleRegion_queriesWithRegionFilter() {
            float[] vector = {0.1f, 0.2f, 0.3f};
            VectorSearchRequest request = new VectorSearchRequest(
                    vector, "일본", List.of("시부야"), List.of("관광", "음식"), null, null, 20
            );

            given(jdbcTemplate.query(anyString(), any(Object[].class), any(RowMapper.class)))
                    .willReturn(List.of());

            service.search(request);

            ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).query(sqlCaptor.capture(), paramsCaptor.capture(), any(RowMapper.class));

            String sql = sqlCaptor.getValue();
            Object[] params = paramsCaptor.getValue();

            assertThat(sql).contains("p.country = ?");
            assertThat(sql).contains("p.region = ?");
            assertThat(sql).contains("p.active = true");
            assertThat(sql).contains("p.embedding IS NOT NULL");

            // params: vectorString, country, region, vectorString(ORDER BY), limit
            assertThat(params).hasSize(5);
            assertThat(params[1]).isEqualTo("일본");
            assertThat(params[2]).isEqualTo("시부야");
            assertThat(params[4]).isEqualTo(20);
        }

        @Test
        @DisplayName("지역 필터 없이 검색한다")
        void search_noRegion_queriesWithoutRegionFilter() {
            float[] vector = {0.1f, 0.2f};
            VectorSearchRequest request = new VectorSearchRequest(
                    vector, "일본", null, List.of("관광"), null, null, 80
            );

            given(jdbcTemplate.query(anyString(), any(Object[].class), any(RowMapper.class)))
                    .willReturn(List.of());

            service.search(request);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).query(sqlCaptor.capture(), any(Object[].class), any(RowMapper.class));

            String sql = sqlCaptor.getValue();
            assertThat(sql).doesNotContain("p.region =");
        }

        @Test
        @DisplayName("다중 지역(2개+)인 경우 지역별 분리 조회를 수행한다")
        void search_multipleRegions_performsPerRegionQueries() {
            float[] vector = {0.1f, 0.2f};
            VectorSearchRequest request = new VectorSearchRequest(
                    vector, "일본", List.of("시부야", "아사쿠사"), List.of("관광"), null, null, 20
            );

            VectorSearchResult result1 = new VectorSearchResult(
                    1L, "센소지", "아사쿠사", "관광", null, "아사쿠사", "일본",
                    BigDecimal.valueOf(35.7148), BigDecimal.valueOf(139.7967), "유명 사찰",
                    BigDecimal.valueOf(4.5), 0.95
            );
            VectorSearchResult result2 = new VectorSearchResult(
                    2L, "하치공", "시부야", "관광", null, "시부야", "일본",
                    BigDecimal.valueOf(35.6590), BigDecimal.valueOf(139.7006), "동상",
                    BigDecimal.valueOf(4.2), 0.88
            );

            // 첫번째 지역(시부야) 쿼리
            given(jdbcTemplate.query(anyString(), any(Object[].class), any(RowMapper.class)))
                    .willReturn(List.of(result2))
                    .willReturn(List.of(result1));

            List<VectorSearchResult> results = service.search(request);

            // 2번의 쿼리가 실행됨 (지역별 1회씩)
            verify(jdbcTemplate, times(2)).query(anyString(), any(Object[].class), any(RowMapper.class));
            // 유사도 기준 내림차순 정렬
            assertThat(results).hasSize(2);
            assertThat(results.get(0).similarityScore()).isGreaterThanOrEqualTo(results.get(1).similarityScore());
        }

        @Test
        @DisplayName("지역별 분리 조회 시 전체 limit을 초과하지 않는다")
        void search_multipleRegions_respectsTotalLimit() {
            float[] vector = {0.1f, 0.2f};
            VectorSearchRequest request = new VectorSearchRequest(
                    vector, "일본", List.of("A지역", "B지역"), List.of("관광"), null, null, 2
            );

            VectorSearchResult r1 = makeResult(1L, "장소1", "A지역", 0.95);
            VectorSearchResult r2 = makeResult(2L, "장소2", "A지역", 0.90);
            VectorSearchResult r3 = makeResult(3L, "장소3", "B지역", 0.92);

            given(jdbcTemplate.query(anyString(), any(Object[].class), any(RowMapper.class)))
                    .willReturn(List.of(r1, r2))
                    .willReturn(List.of(r3));

            List<VectorSearchResult> results = service.search(request);

            assertThat(results).hasSize(2);
        }
    }

    @Nested
    @DisplayName("store")
    class StoreTests {

        @Test
        @DisplayName("단일 장소의 임베딩을 저장한다")
        void store_updatesEmbedding() {
            float[] embedding = {0.1f, 0.2f, 0.3f};

            service.store(1L, embedding);

            verify(jdbcTemplate).update(
                    eq("UPDATE places SET embedding = ?::vector WHERE id = ?"),
                    eq("[0.1,0.2,0.3]"),
                    eq(1L)
            );
        }
    }

    @Nested
    @DisplayName("delete")
    class DeleteTests {

        @Test
        @DisplayName("단일 장소의 임베딩을 삭제한다")
        void delete_setsEmbeddingNull() {
            service.delete(42L);

            verify(jdbcTemplate).update(
                    eq("UPDATE places SET embedding = NULL WHERE id = ?"),
                    eq(42L)
            );
        }
    }

    @Nested
    @DisplayName("storeBatch")
    class StoreBatchTests {

        @Test
        @DisplayName("여러 장소의 임베딩을 일괄 저장한다")
        void storeBatch_batchUpdatesEmbeddings() {
            Map<Long, float[]> embeddings = Map.of(
                    1L, new float[]{0.1f, 0.2f},
                    2L, new float[]{0.3f, 0.4f}
            );

            service.storeBatch(embeddings);

            ArgumentCaptor<List<Object[]>> captor = ArgumentCaptor.forClass(List.class);
            verify(jdbcTemplate).batchUpdate(
                    eq("UPDATE places SET embedding = ?::vector WHERE id = ?"),
                    captor.capture()
            );

            List<Object[]> batchArgs = captor.getValue();
            assertThat(batchArgs).hasSize(2);
        }

        @Test
        @DisplayName("빈 맵이면 아무 작업도 하지 않는다")
        void storeBatch_emptyMap_noOp() {
            service.storeBatch(Map.of());

            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("null이면 아무 작업도 하지 않는다")
        void storeBatch_null_noOp() {
            service.storeBatch(null);

            verifyNoInteractions(jdbcTemplate);
        }
    }

    // --- 헬퍼 ---

    private VectorSearchResult makeResult(Long id, String name, String region, double similarity) {
        return new VectorSearchResult(
                id, name, "주소", "관광", null, region, "일본",
                BigDecimal.valueOf(35.0), BigDecimal.valueOf(139.0),
                "설명", BigDecimal.valueOf(4.0), similarity
        );
    }
}
