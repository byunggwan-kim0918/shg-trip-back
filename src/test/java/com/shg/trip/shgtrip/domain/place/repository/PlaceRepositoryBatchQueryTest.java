package com.shg.trip.shgtrip.domain.place.repository;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치 쿼리(Wikidata 매칭·보강 대상)를 실제 PostgreSQL에서 확인한다.
 *
 * <p>단위 테스트는 리포지토리를 목킹해서 JPQL·네이티브 SQL 자체는 검증하지 못한다. 문법이 틀리면
 * 운영 배치가 시작하자마자 죽으므로 여기서 실행해 본다. 테스트 프로필은 로컬 개발 DB를 쓰므로
 * 존재하지 않는 지역명을 쓰고 각 테스트를 롤백한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@Transactional
class PlaceRepositoryBatchQueryTest {

    private static final String REGION = "ZZ_BATCH_QUERY_TEST";

    @MockBean
    S3Client s3Client;

    @Autowired
    PlaceRepository placeRepository;

    @Autowired
    EntityManager em;

    private Place save(String name, String category, Integer sitelinks, boolean enriched) {
        Place p = Place.builder()
                .name(name)
                .address("테스트 주소")
                .latitude(BigDecimal.valueOf(33.5))
                .longitude(BigDecimal.valueOf(126.5))
                .category(category)
                .region(REGION)
                .country("KR")
                .source("test")
                .build();
        p.markWikidataMatch(sitelinks == null ? null : "Q1", sitelinks);
        Place saved = placeRepository.saveAndFlush(p);
        if (enriched) {
            em.createNativeQuery("UPDATE places SET enriched_at = now() WHERE id = ?")
                    .setParameter(1, saved.getId()).executeUpdate();
        }
        return saved;
    }

    @Test
    @DisplayName("Wikidata 후보는 (id, name, lat, lon, category, source) 6개 컬럼만 지역 단위로 읽는다")
    void findWikidataCandidates_returnsLightweightRows() {
        Place p = save("성산일출봉", "Landmarks and Outdoors > Tourist Attraction", null, false);

        List<Object[]> rows = placeRepository.findWikidataCandidates(REGION);

        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);
        assertThat(row).hasSize(6);
        assertThat(((Number) row[0]).longValue()).isEqualTo(p.getId());
        assertThat(row[1]).isEqualTo("성산일출봉");
        assertThat(((Number) row[2]).doubleValue()).isEqualTo(33.5);
        assertThat(row[4]).isEqualTo("Landmarks and Outdoors > Tourist Attraction");
        assertThat(row[5]).isEqualTo("test");
    }

    @Test
    @DisplayName("지역 초기화는 전부 sitelinks 0으로 덮고, 매칭 갱신은 해당 행만 쓴다")
    void resetThenUpdate_overwritesRegion() {
        Place a = save("경복궁", "Landmarks and Outdoors > Castle", 60, false);
        Place b = save("경복궁 한식당", "Dining and Drinking > Restaurant", 60, false);

        int reset = placeRepository.resetWikidataForRegion(REGION);
        placeRepository.updateWikidataMatch(a.getId(), "Q485", 60);
        em.clear();

        assertThat(reset).isEqualTo(2);
        Place reloadedA = placeRepository.findById(a.getId()).orElseThrow();
        Place reloadedB = placeRepository.findById(b.getId()).orElseThrow();
        assertThat(reloadedA.getWikidataSitelinks()).isEqualTo(60);
        assertThat(reloadedA.getWikidataId()).isEqualTo("Q485");
        assertThat(reloadedB.getWikidataSitelinks()).isZero();
        assertThat(reloadedB.getWikidataId()).isNull();
        assertThat(reloadedB.getWikidataMatchedAt()).isNotNull();
    }

    @Test
    @DisplayName("보강 대상은 인기도 → 비식당 → id 순이고, 보강된 행과 제외 id는 빠진다")
    void findEnrichTargets_ordersAndExcludes() {
        Place restaurant = save("식당", "Dining and Drinking > Restaurant", null, false);
        Place park = save("공원", "Landmarks and Outdoors > Park", null, false);
        Place famous = save("명소", "Landmarks and Outdoors > Mountain", 46, false);
        Place failed = save("실패", "Landmarks and Outdoors > Beach", 30, false);
        save("이미 보강됨", "Landmarks and Outdoors > Beach", 99, true);

        List<Place> targets = placeRepository.findEnrichTargetsInRegions(
                List.of(REGION), "", List.of(failed.getId()), PageRequest.of(0, 10));

        assertThat(targets).extracting(Place::getId)
                .containsExactly(famous.getId(), park.getId(), restaurant.getId());
        assertThat(placeRepository.findEnrichTargetsInRegions(
                List.of(REGION), "Dining and Drinking", List.of(-1L), PageRequest.of(0, 10)))
                .extracting(Place::getId).containsExactly(restaurant.getId());
        assertThat(placeRepository.findEnrichTargets("Dining and Drinking", List.of(-1L), PageRequest.of(0, 500)))
                .extracting(Place::getId).contains(restaurant.getId()).doesNotContain(park.getId(), famous.getId());
    }

    @Test
    @DisplayName("전역 보강 대상 쿼리도 실행되고 제외 id를 지킨다")
    void findEnrichTargets_global_runsAndExcludes() {
        Place failed = save("실패", "Landmarks and Outdoors > Beach", 999_999, false);

        List<Place> targets = placeRepository.findEnrichTargets(
                "", List.of(failed.getId()), PageRequest.of(0, 50));

        assertThat(targets).extracting(Place::getId).doesNotContain(failed.getId());
    }

    @Test
    @DisplayName("Wikidata 매칭을 아직 거치지 않은 행은 보강 대상이 아니다")
    void findEnrichTargets_waitsForWikidata() {
        Place seen = save("명소", "Landmarks and Outdoors > Mountain", 999_999, false);
        Place unseen = save("새 명소", "Landmarks and Outdoors > Mountain", 999_998, false);
        em.createNativeQuery("UPDATE places SET wikidata_matched_at = NULL WHERE id = ?")
                .setParameter(1, unseen.getId()).executeUpdate();

        assertThat(placeRepository.findEnrichTargets("", List.of(-1L), PageRequest.of(0, 50)))
                .extracting(Place::getId).contains(seen.getId()).doesNotContain(unseen.getId());
        assertThat(placeRepository.findEnrichTargetsInRegions(List.of(REGION), "", List.of(-1L), PageRequest.of(0, 50)))
                .extracting(Place::getId).containsExactly(seen.getId());
    }

    @Test
    @DisplayName("명소 행은 출처 URL로 이름·한국어 이름·지역·적재 때 만든 주소를 맞추고, QID 목록에 남는다")
    void refreshWikidataLandmark_renamesBySourceUrl() {
        Place lm = placeRepository.saveAndFlush(Place.builder()
                .name("기요미즈데라").address("기요미즈데라, ZZ_OLD")
                .latitude(BigDecimal.valueOf(34.9949)).longitude(BigDecimal.valueOf(135.7850))
                .category("Community and Government > Spiritual Center > Buddhist Temple")
                .region("ZZ_OLD").country("JP").source("wikidata")
                .sourceUrl("https://www.wikidata.org/wiki/Q9999991").build());
        em.createNativeQuery("UPDATE places SET embedding = array_fill(0.1, ARRAY[768])::halfvec WHERE id = ?")
                .setParameter(1, lm.getId()).executeUpdate();

        int changed = placeRepository.refreshWikidataLandmark(
                "https://www.wikidata.org/wiki/Q9999991", "清水寺", "기요미즈데라", REGION);
        int again = placeRepository.refreshWikidataLandmark(
                "https://www.wikidata.org/wiki/Q9999991", "清水寺", "키요미즈데라", REGION);
        em.clear();

        Object[] row = (Object[]) em.createNativeQuery(
                        "SELECT name, name_ko, region, address, embedding IS NULL FROM places WHERE id = ?")
                .setParameter(1, lm.getId()).getSingleResult();
        assertThat(changed).isEqualTo(1);
        assertThat(again).isZero();
        assertThat(row).containsExactly("清水寺", "기요미즈데라", REGION, "清水寺, " + REGION, true);
        // 인기도 매칭이 wikidata_id를 비워도 재적재 방지 목록에 남는다
        assertThat(placeRepository.findAllWikidataIds()).contains("Q9999991");
    }

    @Test
    @DisplayName("한국어 이름만 채울 때는 임베딩을 지우지 않는다")
    void refreshWikidataLandmark_keepsEmbeddingWhenOnlyNameKoFills() {
        Place lm = placeRepository.saveAndFlush(Place.builder()
                .name("清水寺").address("清水寺, " + REGION)
                .latitude(BigDecimal.valueOf(34.9949)).longitude(BigDecimal.valueOf(135.7850))
                .category("Community and Government > Spiritual Center > Buddhist Temple")
                .region(REGION).country("JP").source("wikidata")
                .sourceUrl("https://www.wikidata.org/wiki/Q9999993").build());
        em.createNativeQuery("UPDATE places SET embedding = array_fill(0.1, ARRAY[768])::halfvec WHERE id = ?")
                .setParameter(1, lm.getId()).executeUpdate();

        int changed = placeRepository.refreshWikidataLandmark(
                "https://www.wikidata.org/wiki/Q9999993", "清水寺", "기요미즈데라", REGION);
        em.clear();

        Object[] row = (Object[]) em.createNativeQuery("SELECT name_ko, embedding IS NULL FROM places WHERE id = ?")
                .setParameter(1, lm.getId()).getSingleResult();
        assertThat(changed).isEqualTo(1);
        assertThat(row).containsExactly("기요미즈데라", false);
    }

    @Test
    @DisplayName("해외 명소 적재의 기존 QID는 활성 행의 wikidata_id와 명소 적재 행만 본다")
    void findKnownLandmarkQids_ignoresInactiveForeignRows() {
        Place stale = save("추출에서 빠진 행", "Landmarks and Outdoors > Park", 5, false);
        em.createNativeQuery("UPDATE places SET wikidata_id = 'Q9999994', active = false WHERE id = ?")
                .setParameter(1, stale.getId()).executeUpdate();
        Place offLandmark = placeRepository.saveAndFlush(Place.builder()
                .name("끈 명소").address("끈 명소, " + REGION)
                .latitude(BigDecimal.valueOf(34.0)).longitude(BigDecimal.valueOf(135.0))
                .category("Landmarks and Outdoors > Park").region(REGION).country("JP").source("wikidata")
                .sourceUrl("https://www.wikidata.org/wiki/Q9999995").active(false).build());

        List<String> known = placeRepository.findKnownLandmarkQids();

        assertThat(known).contains("Q9999995").doesNotContain("Q9999994");
        assertThat(offLandmark.getId()).isNotNull();
    }

    @Test
    @DisplayName("한국어 라벨이 없으면(null) 이름만 맞추고 name_ko와 직접 적은 주소는 그대로 둔다")
    void refreshWikidataLandmark_nullNameKo() {
        Place lm = placeRepository.saveAndFlush(Place.builder()
                .name("오사카 성").address("1-1 Osakajo, Chuo-ku")
                .latitude(BigDecimal.valueOf(34.6873)).longitude(BigDecimal.valueOf(135.5262))
                .category("Landmarks and Outdoors > Historic and Protected Site > Castle")
                .region(REGION).country("JP").source("wikidata")
                .sourceUrl("https://www.wikidata.org/wiki/Q9999992").build());

        int changed = placeRepository.refreshWikidataLandmark(
                "https://www.wikidata.org/wiki/Q9999992", "大阪城", null, REGION);
        em.clear();

        Object[] row = (Object[]) em.createNativeQuery("SELECT name, name_ko, address FROM places WHERE id = ?")
                .setParameter(1, lm.getId()).getSingleResult();
        assertThat(changed).isEqualTo(1);
        assertThat(row).containsExactly("大阪城", null, "1-1 Osakajo, Chuo-ku");
    }

    @Test
    @DisplayName("Wikidata 매칭 대기 건수는 보강 대상에서 빠진 행만 센다")
    void countEnrichWaitingForWikidata_countsUnseen() {
        long before = placeRepository.countEnrichWaitingForWikidata();
        Place unseen = save("새 명소", "Landmarks and Outdoors > Mountain", null, false);
        save("본 명소", "Landmarks and Outdoors > Mountain", 3, false);
        em.createNativeQuery("UPDATE places SET wikidata_matched_at = NULL WHERE id = ?")
                .setParameter(1, unseen.getId()).executeUpdate();

        assertThat(placeRepository.countEnrichWaitingForWikidata()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("한국어 이름은 비어 있는 그 행만 채우고, 이미 있는 이름은 덮지 않는다")
    void fillNameKo_onlyWhenEmpty() {
        Place a = save("外滩", "Landmarks and Outdoors > Historic and Protected Site", null, false);
        Place b = save("豫园", "Landmarks and Outdoors > Garden", null, false);

        placeRepository.fillNameKo(a.getId(), "와이탄");
        int overwritten = placeRepository.fillNameKo(a.getId(), "외탄");
        em.clear();

        assertThat(overwritten).isZero();
        assertThat(placeRepository.findById(a.getId()).orElseThrow().getNameKo()).isEqualTo("와이탄");
        assertThat(placeRepository.findById(b.getId()).orElseThrow().getNameKo()).isNull();
    }

    @Test
    @DisplayName("보강 결과는 보강 컬럼만 쓰고(배열·null 포함), 다른 컬럼과 이미 있는 name_ko는 그대로 둔다")
    void writeEnrichment_targetedColumns() {
        Place p = save("清水寺", "Community and Government > Spiritual Center > Buddhist Temple", 77, false);
        OffsetDateTime candidateAt = OffsetDateTime.parse("2026-11-01T00:00:00Z");
        em.createNativeQuery("UPDATE places SET last_candidate_at = ?, name_ko = '기요미즈데라' WHERE id = ?")
                .setParameter(1, candidateAt).setParameter(2, p.getId()).executeUpdate();
        em.clear();

        Place enriched = placeRepository.findById(p.getId()).orElseThrow();
        enriched.enrichWith(List.of("사찰", "전망"), "교토의 절", List.of("morning", "afternoon"), null, null);
        enriched.recordAdmissionLocal(400, "JPY");
        enriched.assignRole("SIGHT", null);
        int changed = placeRepository.writeEnrichment(enriched);
        em.clear();

        Object[] row = (Object[]) em.createNativeQuery("""
                SELECT array_to_string(tags, '|'), description, array_to_string(recommended_time_slots, '|'),
                       recommended_duration_minutes, admission_fee, admission_fee_local, admission_currency,
                       place_role, cuisine, name_ko, enriched_at IS NOT NULL, last_candidate_at = ?, wikidata_sitelinks
                  FROM places WHERE id = ?
                """).setParameter(1, candidateAt).setParameter(2, p.getId()).getSingleResult();
        assertThat(changed).isEqualTo(1);
        assertThat(row).containsExactly("사찰|전망", "교토의 절", "morning|afternoon", null, null, 400, "JPY",
                "SIGHT", null, "기요미즈데라", true, true, 77);
    }

    @Test
    @DisplayName("보강 실패 3회인 행은 대상에서 빠지고, 원문 이름이 바뀌면 횟수가 0으로 돌아간다")
    void enrichAttempts_excludeAndResetOnRename() {
        Place p = save("실패 명소", "Landmarks and Outdoors > Mountain", 999_999, false);
        placeRepository.incrementEnrichAttempts(List.of(p.getId()));
        placeRepository.incrementEnrichAttempts(List.of(p.getId()));
        assertThat(placeRepository.findEnrichTargetsInRegions(List.of(REGION), "", List.of(-1L), PageRequest.of(0, 50)))
                .extracting(Place::getId).contains(p.getId());
        placeRepository.incrementEnrichAttempts(List.of(p.getId()));
        em.clear();
        assertThat(placeRepository.findEnrichTargetsInRegions(List.of(REGION), "", List.of(-1L), PageRequest.of(0, 50)))
                .extracting(Place::getId).doesNotContain(p.getId());

        upsert("zz-fsq-attempts", "饭店");
        em.createNativeQuery("UPDATE places SET enrich_attempts = 3, wikidata_matched_at = now() WHERE fsq_place_id = 'zz-fsq-attempts'")
                .executeUpdate();
        upsert("zz-fsq-attempts", "新饭店");
        em.flush();
        em.clear();
        Number attempts = (Number) em.createNativeQuery(
                "SELECT enrich_attempts FROM places WHERE fsq_place_id = 'zz-fsq-attempts'").getSingleResult();
        assertThat(attempts.intValue()).isZero();
    }

    @Test
    @DisplayName("보정표는 QID나 국가·원문 이름으로 name_ko를 덮고, 바뀐 행만 임베딩을 지운다")
    void overrideNameKo() {
        Place byQid = save("鹿苑寺", "Community and Government > Spiritual Center > Buddhist Temple", 57, false);
        Place byName = save("金閣寺", "Community and Government > Spiritual Center > Buddhist Temple", null, false);
        em.createNativeQuery("UPDATE places SET wikidata_id = 'Q9999996', name_ko = '로쿠온지', country = 'JP', "
                        + "embedding = array_fill(0.1, ARRAY[768])::halfvec WHERE id IN (?, ?)")
                .setParameter(1, byQid.getId()).setParameter(2, byName.getId()).executeUpdate();
        em.createNativeQuery("UPDATE places SET wikidata_id = NULL WHERE id = ?")
                .setParameter(1, byName.getId()).executeUpdate();

        int qidChanged = placeRepository.overrideNameKoByQid("Q9999996", "금각사");
        int again = placeRepository.overrideNameKoByQid("Q9999996", "금각사");
        int nameChanged = placeRepository.overrideNameKoByName("JP", "金閣寺", "금각사");
        em.clear();

        assertThat(qidChanged).isEqualTo(1);
        assertThat(again).isZero();
        assertThat(nameChanged).isEqualTo(1);
        assertThat(placeRepository.findById(byQid.getId()).orElseThrow().getNameKo()).isEqualTo("금각사");
        Object embeddingCleared = em.createNativeQuery("SELECT embedding IS NULL FROM places WHERE id = ?")
                .setParameter(1, byName.getId()).getSingleResult();
        assertThat(embeddingCleared).isEqualTo(true);
    }

    @Test
    @DisplayName("보강 결과 쓰기는 태그·설명·시간대가 null이어도 된다")
    void writeEnrichment_nullArraysAndText() {
        Place p = save("새 명소", "Landmarks and Outdoors > Park", 3, false);
        em.clear();
        Place loaded = placeRepository.findById(p.getId()).orElseThrow();
        loaded.assignRole("SIGHT", null);

        int changed = placeRepository.writeEnrichmentColumns(loaded.getId(), null, null, null, null, null, null, null,
                "SIGHT", null, null, null, OffsetDateTime.now());
        em.clear();

        Object[] row = (Object[]) em.createNativeQuery(
                        "SELECT tags IS NULL, description IS NULL, recommended_time_slots IS NULL, place_role FROM places WHERE id = ?")
                .setParameter(1, p.getId()).getSingleResult();
        assertThat(changed).isEqualTo(1);
        assertThat(row).containsExactly(true, true, true, "SIGHT");
    }

    @Test
    @DisplayName("알려진 정도는 값이 있을 때만 쓰고, 없으면(한국 보강) 기존 값을 둔다")
    void writeEnrichment_travelerFame() {
        Place p = save("士林夜市", "Dining and Drinking > Night Market", 0, false);
        em.clear();
        Place loaded = placeRepository.findById(p.getId()).orElseThrow();
        loaded.rateFame((short) 2);
        placeRepository.writeEnrichment(loaded);
        placeRepository.writeEnrichmentColumns(p.getId(), null, null, null, null, null, null, null,
                "MARKET", null, null, null, OffsetDateTime.now());
        em.clear();

        Object fame = em.createNativeQuery("SELECT traveler_fame FROM places WHERE id = ?")
                .setParameter(1, p.getId()).getSingleResult();
        assertThat(((Number) fame).intValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("숙소 보완은 호텔 수영장·헬스장과 비방문 행을 고르지 않는다")
    void findTopAccommodations_skipsHotelFacilities() {
        Place hotel = save("호텔", "Travel and Transportation > Lodging > Hotel", null, false);
        save("Swimming Pool", "Travel and Transportation > Lodging > Hotel > Hotel Pool", null, false);
        save("Fitness Center", "Travel and Transportation > Lodging > Hotel > Hotel Gym", null, false);
        Place marked = save("비방문 숙소", "Travel and Transportation > Lodging > Hotel", null, false);
        em.createNativeQuery("UPDATE places SET place_role = 'NOT_VISITABLE' WHERE id = ?")
                .setParameter(1, marked.getId()).executeUpdate();
        em.clear();

        assertThat(placeRepository.findTopAccommodationsByRegions(List.of(REGION), PageRequest.of(0, 10)))
                .extracting(Place::getId).containsExactly(hotel.getId());
    }

    @Test
    @DisplayName("QID 보정은 식당 행을 건드리지 않는다(체인 항목이 여러 지점에 붙어 있다)")
    void overrideNameKoByQid_skipsDining() {
        Place shop = save("鼎泰豐 信義店", "Dining and Drinking > Restaurant > Chinese Restaurant", 20, false);
        em.createNativeQuery("UPDATE places SET wikidata_id = 'Q9999997', name_ko = '딘타이펑 신이점' WHERE id = ?")
                .setParameter(1, shop.getId()).executeUpdate();

        int changed = placeRepository.overrideNameKoByQid("Q9999997", "딘타이펑");
        em.clear();

        assertThat(changed).isZero();
        assertThat(placeRepository.findById(shop.getId()).orElseThrow().getNameKo()).isEqualTo("딘타이펑 신이점");
    }

    @Test
    @DisplayName("Wikidata 한국어 라벨 채움은 보강이 끝난 행만 임베딩을 다시 만든다")
    void fillNameKo_resetsEmbeddingOnlyWhenEnriched() {
        Place enriched = save("外滩", "Landmarks and Outdoors > Historic and Protected Site", 40, true);
        Place pending = save("豫园", "Landmarks and Outdoors > Garden", 30, false);
        em.createNativeQuery("UPDATE places SET embedding = array_fill(0.1, ARRAY[768])::halfvec WHERE id IN (?, ?)")
                .setParameter(1, enriched.getId()).setParameter(2, pending.getId()).executeUpdate();

        placeRepository.fillNameKo(enriched.getId(), "와이탄");
        placeRepository.fillNameKo(pending.getId(), "예원");
        em.clear();

        assertThat(em.createNativeQuery("SELECT embedding IS NULL FROM places WHERE id = ?")
                .setParameter(1, enriched.getId()).getSingleResult()).isEqualTo(true);
        assertThat(em.createNativeQuery("SELECT embedding IS NULL FROM places WHERE id = ?")
                .setParameter(1, pending.getId()).getSingleResult()).isEqualTo(false);
    }

    private void upsert(String fsqId, String name) {
        placeRepository.upsertFoursquarePlace(fsqId, name, "", BigDecimal.valueOf(31.23), BigDecimal.valueOf(121.47),
                "CN", REGION, "Dining and Drinking > Restaurant", "{}", "", null);
    }

    private Object[] state(String fsqId) {
        em.flush();
        em.clear();
        return (Object[]) em.createNativeQuery("""
                SELECT active, deactivated_reason, name, name_ko, enriched_at FROM places WHERE fsq_place_id = ?
                """).setParameter(1, fsqId).getSingleResult();
    }

    @Test
    @DisplayName("이번 적재에 안 나온 행은 stale로 꺼지고, 다음 적재에 다시 나오면 되살아난다")
    void staleRowIsDeactivatedAndRevived() {
        upsert("zz-fsq-stale", "饭店");
        upsert("zz-fsq-linked", "外滩");
        em.createNativeQuery("UPDATE places SET wikidata_id = 'Q1' WHERE fsq_place_id = 'zz-fsq-linked'").executeUpdate();
        placeRepository.upsertFoursquarePlace("zz-fsq-other", "饭店", "", BigDecimal.valueOf(31.23),
                BigDecimal.valueOf(121.47), "CN", REGION + "_OTHER", "Dining and Drinking > Restaurant", "{}", "", null);

        // 방금 적재한 행은 이번 실행 기준(시작 1시간 전)으로는 빠진 행이 아니다
        assertThat(placeRepository.countStaleFoursquare("CN", REGION, OffsetDateTime.now().minusHours(1))).isZero();

        OffsetDateTime nextRunStart = OffsetDateTime.now().plusHours(2);
        assertThat(placeRepository.countStaleFoursquare("CN", REGION, nextRunStart)).isEqualTo(1);
        assertThat(placeRepository.countExistingFoursquare("CN", REGION, nextRunStart)).isEqualTo(2);
        assertThat(placeRepository.deactivateStaleFoursquare("CN", REGION, nextRunStart, 1000)).isEqualTo(1);
        assertThat(state("zz-fsq-stale")).containsSequence(false, "stale");
        assertThat(state("zz-fsq-linked")).containsSequence(true, null);
        assertThat(state("zz-fsq-other")).containsSequence(true, null);

        upsert("zz-fsq-stale", "饭店");
        assertThat(state("zz-fsq-stale")).containsSequence(true, null);
        Object deactivatedAt = em.createNativeQuery(
                "SELECT deactivated_at FROM places WHERE fsq_place_id = 'zz-fsq-stale'").getSingleResult();
        assertThat(deactivatedAt).isNull();
    }

    @Test
    @DisplayName("정책으로 끈 행은 다시 적재돼도 꺼진 채로 둔다")
    void policyRowStaysOff() {
        upsert("zz-fsq-policy", "饭店");
        em.createNativeQuery("UPDATE places SET active = false, deactivated_reason = 'policy' WHERE fsq_place_id = ?")
                .setParameter(1, "zz-fsq-policy").executeUpdate();

        upsert("zz-fsq-policy", "饭店");

        assertThat(state("zz-fsq-policy")).containsSequence(false, "policy");
    }

    @Test
    @DisplayName("원문 이름이 바뀌면 한국어 이름·보강 시각을 비우고, 한글 이름 행은 그대로 둔다")
    void renameResetsKoreanNameAndEnrichment() {
        upsert("zz-fsq-rename", "饭店");
        upsert("zz-fsq-hangul", "한글식당");
        em.createNativeQuery("UPDATE places SET name_ko = '판뎬', enriched_at = now() WHERE fsq_place_id IN (?, ?)")
                .setParameter(1, "zz-fsq-rename").setParameter(2, "zz-fsq-hangul").executeUpdate();

        upsert("zz-fsq-rename", "新饭店");
        upsert("zz-fsq-hangul", "Hangul Restaurant");

        Object[] renamed = state("zz-fsq-rename");
        assertThat(renamed[2]).isEqualTo("新饭店");
        assertThat(renamed[3]).isNull();
        assertThat(renamed[4]).isNull();
        Object[] hangul = state("zz-fsq-hangul");
        assertThat(hangul[2]).isEqualTo("한글식당");
        assertThat(hangul[3]).isEqualTo("판뎬");
        assertThat(hangul[4]).isNotNull();
    }
}
