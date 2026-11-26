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
    @DisplayName("이름이 바뀐 보강 행은 보강·임베딩·장소 사실·묶음·파이프라인 판정을 비우고, 그대로인 행과 LLM 판정은 둔다")
    void renamedRowLosesDerivedData() {
        Place renamed = enriched("一蘭");
        Place same = enriched("一風堂");
        Place member = enriched("一蘭 本店");
        jdbcTemplate.update("UPDATE places SET merged_into_id = ?, merge_relation = 'SAME' WHERE id = ?",
                renamed.getId(), member.getId());
        jdbcTemplate.update("INSERT INTO place_facts (place_id, facts, model) VALUES (?, '{}', 'test')", renamed.getId());
        long[] pipelinePair = {Math.min(renamed.getId(), member.getId()), Math.max(renamed.getId(), member.getId())};
        long[] llmPair = {Math.min(renamed.getId(), same.getId()), Math.max(renamed.getId(), same.getId())};
        jdbcTemplate.update("INSERT INTO place_pair_verdicts (low_id, high_id, relation, basis, input_key) "
                + "VALUES (?, ?, 'SAME', 'PIPELINE', 'run'), (?, ?, 'DIFFERENT', 'LLM', 'k')",
                pipelinePair[0], pipelinePair[1], llmPair[0], llmPair[1]);
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
        assertThat(jdbcTemplate.queryForList("SELECT basis FROM place_pair_verdicts WHERE ? IN (low_id, high_id)",
                String.class, renamed.getId())).containsExactly("LLM");
        assertThat(jdbcTemplate.queryForObject("SELECT enriched_at IS NOT NULL FROM places WHERE id = ?",
                Boolean.class, same.getId())).isTrue();
        assertThat(counts.enrichInvalidated()).isGreaterThanOrEqualTo(1);
        assertThat(counts.unmerged()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("깨진 한국어 이름 행은 이름·보강·임베딩만 비우고 장소 사실·묶음은 남기며, 맞는 이름 행은 둔다")
    void brokenNameKoIsReaskedWithoutLosingFacts() {
        Place broken = enriched("仏眼寺");
        Place good = enriched("一風堂");
        Place member = enriched("仏眼寺 本堂");
        jdbcTemplate.update("UPDATE places SET name_ko = '부processing' WHERE id = ?", broken.getId());
        jdbcTemplate.update("UPDATE places SET name_ko = '잇푸도' WHERE id = ?", good.getId());
        jdbcTemplate.update("UPDATE places SET merged_into_id = ?, merge_relation = 'PART' WHERE id = ?",
                broken.getId(), member.getId());
        jdbcTemplate.update("INSERT INTO place_facts (place_id, facts, model) VALUES (?, '{}', 'test')", broken.getId());
        PlaceInvalidationJob job = new PlaceInvalidationJob(jdbcTemplate, namedJdbc, transactionTemplate);

        job.adopt();
        PlaceInvalidationJob.Counts counts = job.run();

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT name_ko, enriched_at, embedding IS NULL AS no_embedding, place_role, description
                FROM places WHERE id = ?""", broken.getId());
        assertThat(row.get("name_ko")).isNull();
        assertThat(row.get("enriched_at")).isNull();
        assertThat(row.get("no_embedding")).isEqualTo(true);
        assertThat(row.get("place_role")).isEqualTo("MEAL");
        assertThat(row.get("description")).isEqualTo("돈코츠 라멘집이다");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM place_facts WHERE place_id = ?", Integer.class,
                broken.getId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT merged_into_id FROM places WHERE id = ?", Long.class,
                member.getId())).isEqualTo(broken.getId());
        assertThat(jdbcTemplate.queryForObject("SELECT name_ko FROM places WHERE id = ?", String.class,
                good.getId())).isEqualTo("잇푸도");
        assertThat(counts.nameKoReasked()).isEqualTo(1);
        assertThat(counts.enrichInvalidated()).isZero();
    }

    @Test
    @DisplayName("비방문 행의 깨진 한국어 이름은 이름만 비우고 보강 완료로 둔다")
    void brokenNameKoOfNotVisitableIsOnlyCleared() {
        Place office = enriched("大阪市平野区");
        jdbcTemplate.update("UPDATE places SET name_ko = '오사카시 hirano구', place_role = 'NOT_VISITABLE' WHERE id = ?",
                office.getId());
        PlaceInvalidationJob job = new PlaceInvalidationJob(jdbcTemplate, namedJdbc, transactionTemplate);

        job.adopt();
        job.run();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT name_ko, enriched_at, enrich_input_fp FROM places WHERE id = ?", office.getId());
        assertThat(row.get("name_ko")).isNull();
        assertThat(row.get("enriched_at")).isNotNull();
        assertThat(row.get("enrich_input_fp")).isNotNull();
    }

    @Test
    @DisplayName("보강을 비우면 입장료도 비우고, 공식 요금 다시 읽기가 원문이 있는 행만 원문 값으로 되돌린다")
    void invalidatedFeeIsRestoredOnlyFromOfficialText() {
        Place official = enriched("首里城");
        Place estimated = enriched("波上宮");
        jdbcTemplate.update("""
                UPDATE places SET admission_fee = 400, tourapi_detail_at = now(),
                       attributes = CAST('{"official":{"usefee":"성인 400원"}}' AS jsonb) WHERE id = ?""",
                official.getId());
        jdbcTemplate.update("UPDATE places SET admission_fee = 1000 WHERE id = ?", estimated.getId());
        PlaceInvalidationJob job = new PlaceInvalidationJob(jdbcTemplate, namedJdbc, transactionTemplate);

        job.adopt();
        jdbcTemplate.update("UPDATE places SET category = 'Landmarks and Outdoors > Castle' WHERE id IN (?, ?)",
                official.getId(), estimated.getId());
        job.run();
        new TourApiDetailJob(placeRepository, new com.fasterxml.jackson.databind.ObjectMapper()).reparseStoredFees();

        assertThat(jdbcTemplate.queryForObject("SELECT admission_fee FROM places WHERE id = ?", Integer.class,
                official.getId())).isEqualTo(400);
        assertThat(jdbcTemplate.queryForObject("SELECT admission_fee FROM places WHERE id = ?", Integer.class,
                estimated.getId())).isNull();
    }

    @Test
    @DisplayName("보강 결과 쓰기와 임베딩 저장은 그 입력의 지문을 남긴다")
    void writersStampFingerprints() {
        Place p = enriched("一蘭");
        jdbcTemplate.update("UPDATE places SET enriched_at = NULL WHERE id = ?", p.getId());
        Place loaded = Place.builder().id(p.getId()).name("一蘭").category("Dining and Drinking > Restaurant")
                .region(REGION).country("JP").build();
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
