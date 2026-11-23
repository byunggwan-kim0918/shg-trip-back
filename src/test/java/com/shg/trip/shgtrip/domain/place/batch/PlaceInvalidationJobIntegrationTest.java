package com.shg.trip.shgtrip.domain.place.batch;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 원문이 바뀐 장소의 파생 데이터만 비우는 SQL을 실제 PostgreSQL에서 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@Transactional
class PlaceInvalidationJobIntegrationTest {

    private static final String REGION = "ZZ_INVALIDATION_TEST";

    @MockBean
    S3Client s3Client;

    @Autowired
    PlaceRepository placeRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    NamedParameterJdbcTemplate namedJdbc;

    @Autowired
    org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private Place enriched(String name) {
        Place p = placeRepository.saveAndFlush(Place.builder().name(name).address("addr " + name)
                .latitude(BigDecimal.valueOf(33.59)).longitude(BigDecimal.valueOf(130.40))
                .category("Dining and Drinking > Restaurant").region(REGION).country("JP").source("test")
                .tags(List.of("라멘")).description("돈코츠 라멘집이다").placeRole("MEAL").build());
        jdbcTemplate.update("""
                UPDATE places SET enriched_at = now(), cuisine = 'NOODLE',
                       attributes = CAST('{"venue":"MEAL","ingredients":["PORK"],"official":{"firstmenu":"라멘"}}' AS jsonb),
                       embedding = array_fill(0.01, ARRAY[768])::halfvec WHERE id = ?""", p.getId());
        return p;
    }

    @Test
    @DisplayName("이름이 바뀐 보강 행은 보강·임베딩·장소 사실·묶음을 비우고, 그대로인 행은 둔다")
    void renamedRowLosesDerivedData() {
        Place renamed = enriched("一蘭");
        Place same = enriched("一風堂");
        Place member = enriched("一蘭 本店");
        jdbcTemplate.update("UPDATE places SET merged_into_id = ?, merge_relation = 'SAME' WHERE id = ?",
                renamed.getId(), member.getId());
        jdbcTemplate.update("INSERT INTO place_facts (place_id, facts, model) VALUES (?, '{}', 'test')", renamed.getId());
        PlaceInvalidationJob job = new PlaceInvalidationJob(jdbcTemplate, namedJdbc, transactionTemplate);

        job.adopt();
        jdbcTemplate.update("UPDATE places SET name = '新一蘭' WHERE id = ?", renamed.getId());
        PlaceInvalidationJob.Counts counts = job.run();

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT enriched_at, tags, description, place_role, cuisine, CAST(attributes AS text) AS attrs,
                       embedding IS NULL AS no_embedding, enrich_input_fp FROM places WHERE id = ?""", renamed.getId());
        assertThat(row.get("enriched_at")).isNull();
        assertThat(row.get("description")).isNull();
        assertThat(row.get("place_role")).isNull();
        assertThat(row.get("cuisine")).isNull();
        assertThat(row.get("attrs")).isEqualTo("{\"official\": {\"firstmenu\": \"라멘\"}}");
        assertThat(row.get("no_embedding")).isEqualTo(true);
        assertThat(row.get("enrich_input_fp")).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM place_facts WHERE place_id = ?", Integer.class,
                renamed.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT merged_into_id FROM places WHERE id = ?", Long.class,
                member.getId())).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT enriched_at IS NOT NULL FROM places WHERE id = ?",
                Boolean.class, same.getId())).isTrue();
        assertThat(counts.enrichInvalidated()).isGreaterThanOrEqualTo(1);
        assertThat(counts.unmerged()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("보강 결과 쓰기와 임베딩 저장은 그 입력의 지문을 남긴다")
    void writersStampFingerprints() {
        Place p = enriched("一蘭");
        Place loaded = placeRepository.findById(p.getId()).orElseThrow();
        loaded.enrichWith(List.of("라멘"), "돈코츠 라멘집이다", List.of("lunch"));
        placeRepository.writeEnrichment(loaded);
        placeRepository.stampEmbeddingTextFingerprints(p.getId() + "", "abcdef0123456789");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT enrich_input_fp, embedding_text_fp FROM places WHERE id = ?", p.getId());
        assertThat(row.get("enrich_input_fp")).isEqualTo(
                com.shg.trip.shgtrip.domain.place.integrity.PlaceFingerprints.enrichInput("一蘭",
                        "Dining and Drinking > Restaurant", REGION, "JP"));
        assertThat(row.get("embedding_text_fp")).isEqualTo("abcdef0123456789");
    }
}
