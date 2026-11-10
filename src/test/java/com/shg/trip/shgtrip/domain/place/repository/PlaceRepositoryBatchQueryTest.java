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
        if (sitelinks != null) p.markWikidataMatch("Q1", sitelinks);
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
                List.of(REGION), List.of(failed.getId()), PageRequest.of(0, 10));

        assertThat(targets).extracting(Place::getId)
                .containsExactly(famous.getId(), park.getId(), restaurant.getId());
    }

    @Test
    @DisplayName("전역 보강 대상 쿼리도 실행되고 제외 id를 지킨다")
    void findEnrichTargets_global_runsAndExcludes() {
        Place failed = save("실패", "Landmarks and Outdoors > Beach", 999_999, false);

        List<Place> targets = placeRepository.findEnrichTargets(
                List.of(failed.getId()), PageRequest.of(0, 50));

        assertThat(targets).extracting(Place::getId).doesNotContain(failed.getId());
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
