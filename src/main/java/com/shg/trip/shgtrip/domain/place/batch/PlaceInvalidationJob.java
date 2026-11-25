package com.shg.trip.shgtrip.domain.place.batch;

import com.shg.trip.shgtrip.domain.place.embedding.EmbeddingTextBuilder;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.integrity.PlaceFingerprints;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 원문이 바뀐 장소의 파생 데이터를 입력 지문으로 다시 만들 대상으로 돌린다. 적재기(업서트)는 원문만 쓰고 이 판정을 하지 않는다.
 * <ul>
 *   <li>보강 입력(이름·카테고리·지역·국가)이 바뀌면 보강 결과와 임베딩을 비우고, 다른 곳이 됐을 수 있어 같은 장소 묶음을 푼다.</li>
 *   <li>임베딩 텍스트가 바뀌면 임베딩만 비운다.</li>
 *   <li>장소 사실 입력이 바뀌면 사실을 지운다. 다른 곳의 휴무 요일·방문 가치가 남는 것보다 없는 편이 낫다.
 *   빈 행은 scripts/eval/place-facts.py를 손으로 돌려 운영에 복사할 때까지 사실 없이 생성된다.</li>
 * </ul>
 * 지문이 비어 있는 행은 지금 값으로 채운다. 옛 보강 설명과 새 이름이 섞인 임베딩을 만들지 않으려고 보강을 비울 때 설명·태그도 비운다.
 */
@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
public class PlaceInvalidationJob {

    private static final int PAGE_SIZE = 2000;

    /** 보강이 채우는 속성 키. TourAPI 공식 원문(official)은 원문 데이터라 남긴다. */
    private static final String ENRICHED_ATTRIBUTE_KEYS =
            "ARRAY['ingredients', 'venue', 'subType', 'visitStyle', 'hikeLevel', 'lodgingType', 'breakfast']";

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final TransactionTemplate transactionTemplate;

    record Counts(int stamped, int enrichInvalidated, int embeddingInvalidated, int factsInvalidated, int unmerged) {
        Counts plus(Counts o) {
            return new Counts(stamped + o.stamped, enrichInvalidated + o.enrichInvalidated,
                    embeddingInvalidated + o.embeddingInvalidated, factsInvalidated + o.factsInvalidated,
                    unmerged + o.unmerged);
        }
    }

    /** 적재 전에 비어 있는 지문만 채운다. 첫 실행이 이번 적재로 바뀐 값을 원래 값으로 받아들이지 않게 한다. */
    public Counts adopt() {
        Counts total = sweep(false);
        log.info("[PlaceInvalidation] 지문 채움 {}건", total.stamped());
        return total;
    }

    public Counts run() {
        Counts total = sweep(true);
        log.info("[PlaceInvalidation] 지문 채움 {}건, 보강 다시 {}건, 임베딩 다시 {}건, 장소 사실 다시 {}건, 묶음 풀림 {}건",
                total.stamped(), total.enrichInvalidated(), total.embeddingInvalidated(), total.factsInvalidated(),
                total.unmerged());
        return total;
    }

    private Counts sweep(boolean invalidate) {
        Counts total = new Counts(0, 0, 0, 0, 0);
        long afterId = 0;
        while (true) {
            List<Row> rows = jdbcTemplate.query("""
                    SELECT p.id, p.name, p.name_ko, p.category, p.region, p.country, p.latitude, p.longitude,
                           p.tags, p.description, p.enriched_at IS NOT NULL AS enriched, p.embedding IS NOT NULL AS embedded,
                           p.enrich_input_fp, p.embedding_text_fp, f.place_id IS NOT NULL AS has_facts, f.input_fp
                    FROM places p LEFT JOIN place_facts f ON f.place_id = p.id
                    WHERE p.id > ? ORDER BY p.id LIMIT ?
                    """, PlaceInvalidationJob::row, afterId, PAGE_SIZE);
            if (rows.isEmpty()) return total;
            // 보강 비우기·묶음 풀기·사실 지우기가 함께 남거나 함께 빠져야 한다. 일부만 남으면 지문이 비어 다음 실행이 다시 보지 않는다.
            Counts page = transactionTemplate.execute(status -> apply(rows, invalidate));
            total = total.plus(page);
            afterId = rows.get(rows.size() - 1).id();
        }
    }

    record Row(long id, String name, String nameKo, String category, String region, String country,
               BigDecimal latitude, BigDecimal longitude, List<String> tags, String description, boolean enriched,
               boolean embedded, String enrichFp, String textFp, boolean hasFacts, String factsFp) {}

    /** 한 페이지의 판정. 계산과 쓰기를 나눠 판정 규칙만 단위 테스트로 본다. */
    record Decisions(List<Object[]> stampEnrich, List<Object[]> stampText, List<Object[]> stampFacts,
                     List<Long> invalidateEnrich, List<Long> invalidateEmbedding, List<Long> invalidateFacts) {}

    static Decisions decide(List<Row> rows, boolean invalidate) {
        Decisions d = new Decisions(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>());
        for (Row r : rows) {
            String enrichFp = PlaceFingerprints.enrichInput(r.name(), r.category(), r.region(), r.country());
            boolean enrichChanged = false;
            if (r.enriched()) {
                if (r.enrichFp() == null) {
                    d.stampEnrich().add(new Object[]{enrichFp, r.id()});
                } else if (invalidate && !r.enrichFp().equals(enrichFp)) {
                    d.invalidateEnrich().add(r.id());
                    enrichChanged = true;
                }
            }
            if (r.embedded() && !enrichChanged && r.name() != null && !r.name().isBlank()) {
                String textFp = PlaceFingerprints.embeddingText(EmbeddingTextBuilder.buildText(asPlace(r)));
                if (r.textFp() == null) {
                    d.stampText().add(new Object[]{textFp, r.id()});
                } else if (invalidate && !r.textFp().equals(textFp)) {
                    d.invalidateEmbedding().add(r.id());
                }
            }
            if (r.hasFacts()) {
                String factsFp = PlaceFingerprints.factsInput(r.name(), r.category(), r.latitude(), r.longitude());
                if (r.factsFp() == null) {
                    d.stampFacts().add(new Object[]{factsFp, r.id()});
                } else if (invalidate && !r.factsFp().equals(factsFp)) {
                    d.invalidateFacts().add(r.id());
                }
            }
        }
        return d;
    }

    private Counts apply(List<Row> rows, boolean invalidate) {
        Decisions d = decide(rows, invalidate);
        jdbcTemplate.batchUpdate("UPDATE places SET enrich_input_fp = ? WHERE id = ?", d.stampEnrich());
        jdbcTemplate.batchUpdate("UPDATE places SET embedding_text_fp = ? WHERE id = ?", d.stampText());
        jdbcTemplate.batchUpdate("UPDATE place_facts SET input_fp = ? WHERE place_id = ?", d.stampFacts());
        int unmerged = 0, factsDeleted = 0;
        if (!d.invalidateEnrich().isEmpty()) {
            MapSqlParameterSource ids = new MapSqlParameterSource("ids", d.invalidateEnrich());
            namedJdbc.update("""
                    UPDATE places SET
                        enriched_at = NULL, enrich_attempts = 0, enrich_input_fp = NULL, enrich_version = NULL,
                        place_role = NULL, cuisine = NULL, attributes = attributes - %s,
                        tags = '{}', description = NULL, recommended_time_slots = NULL,
                        recommended_duration_minutes = NULL, admission_fee = NULL, admission_fee_local = NULL,
                        admission_currency = NULL, traveler_fame = NULL, description_rewrite_attempts = 0,
                        embedding = NULL, embedding_text_fp = NULL, updated_at = now()
                    WHERE id IN (:ids)
                    """.formatted(ENRICHED_ATTRIBUTE_KEYS), ids);
            unmerged = namedJdbc.update("""
                    UPDATE places SET merged_into_id = NULL, merge_relation = NULL
                    WHERE merged_into_id IS NOT NULL AND (id IN (:ids) OR merged_into_id IN (:ids))
                    """, ids);
            factsDeleted += namedJdbc.update("DELETE FROM place_facts WHERE place_id IN (:ids)", ids);
        }
        if (!d.invalidateEmbedding().isEmpty()) {
            namedJdbc.update("UPDATE places SET embedding = NULL, embedding_text_fp = NULL WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", d.invalidateEmbedding()));
        }
        if (!d.invalidateFacts().isEmpty()) {
            factsDeleted += namedJdbc.update("DELETE FROM place_facts WHERE place_id IN (:ids)",
                    new MapSqlParameterSource("ids", d.invalidateFacts()));
        }
        int stamped = d.stampEnrich().size() + d.stampText().size() + d.stampFacts().size();
        return new Counts(stamped, d.invalidateEnrich().size(), d.invalidateEmbedding().size(), factsDeleted,
                unmerged);
    }

    /** EmbeddingTextBuilder가 읽는 필드만 채운 장소. 임베딩 배치와 같은 텍스트를 만들어야 지문이 맞는다. */
    private static Place asPlace(Row r) {
        return Place.builder().name(r.name()).nameKo(r.nameKo()).category(r.category()).tags(r.tags())
                .description(r.description()).region(r.region()).country(r.country()).build();
    }

    private static Row row(ResultSet rs, int i) throws SQLException {
        Array tags = rs.getArray("tags");
        return new Row(rs.getLong("id"), rs.getString("name"), rs.getString("name_ko"), rs.getString("category"),
                rs.getString("region"), rs.getString("country"), rs.getBigDecimal("latitude"),
                rs.getBigDecimal("longitude"), tags == null ? null : Arrays.asList((String[]) tags.getArray()),
                rs.getString("description"), rs.getBoolean("enriched"), rs.getBoolean("embedded"),
                rs.getString("enrich_input_fp"), rs.getString("embedding_text_fp"), rs.getBoolean("has_facts"),
                rs.getString("input_fp"));
    }
}
