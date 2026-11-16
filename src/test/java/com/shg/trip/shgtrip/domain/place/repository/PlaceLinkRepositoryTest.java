package com.shg.trip.shgtrip.domain.place.repository;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceLinkRepository.LinkRow;
import com.shg.trip.shgtrip.domain.place.repository.PlaceLinkRepository.PairClass;
import com.shg.trip.shgtrip.domain.place.repository.PlaceLinkRepository.PairRow;
import com.shg.trip.shgtrip.domain.place.repository.PlaceLinkRepository.VerdictRow;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 같은 장소 판정의 근처 쌍 찾기·판정 기록·합쳐지는 행 기록을 실제 PostgreSQL에서 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@Transactional
class PlaceLinkRepositoryTest {

    private static final String REGION = "ZZ_PLACE_LINK_TEST";
    /** 위도 0.00015도는 약 17m다. */
    private static final double LAT = 35.10090;
    private static final double LON = 129.03233;

    @MockBean
    S3Client s3Client;

    @Autowired
    PlaceRepository placeRepository;

    @Autowired
    PlaceLinkRepository links;

    @Autowired
    EntityManager em;

    private Place place(String name, String role, double dLat, String source, boolean candidate) {
        Place saved = placeRepository.saveAndFlush(Place.builder()
                .name(name).address("테스트 주소").category("Landmarks and Outdoors > Tourist Attraction")
                .latitude(BigDecimal.valueOf(LAT + dLat)).longitude(BigDecimal.valueOf(LON))
                .region(REGION).country("KR").source(source).build());
        em.createNativeQuery("UPDATE places SET place_role = ?, last_candidate_at = ? WHERE id = ?")
                .setParameter(1, role).setParameter(2, candidate ? OffsetDateTime.now() : null)
                .setParameter(3, saved.getId()).executeUpdate();
        return saved;
    }

    private static long[] ids(Place a, Place b) {
        return new long[]{Math.min(a.getId(), b.getId()), Math.max(a.getId(), b.getId())};
    }

    @Test
    @DisplayName("관광지 쌍은 150m 안에서 후보로 나왔거나 관광공사 행이 낀 쌍만, 종류가 다르면 묶지 않는다")
    void attractionPairs() {
        Place tower = place("부산타워", "SIGHT", 0, "tourapi", false);
        Place diamond = place("부산다이아몬드타워", "SIGHT", 0.00015, "foursquare", false);
        place("부산 타워", "SIGHT", 0.0003, "foursquare", false);
        place("먼 전망대", "SIGHT", 0.003, "tourapi", true);
        Place quietA = place("골목 벽화", "SIGHT", 0.02, "foursquare", false);
        place("골목 계단", "SIGHT", 0.02015, "foursquare", false);
        place("타워 식당", "MEAL", 0.00005, "foursquare", true);

        List<PairRow> pairs = links.findNearPairs(REGION, PairClass.ATTRACTION, 150);

        long[] towerDiamond = ids(tower, diamond);
        assertThat(pairs).anySatisfy(p -> {
            assertThat(p.lowId()).isEqualTo(towerDiamond[0]);
            assertThat(p.highId()).isEqualTo(towerDiamond[1]);
            assertThat(p.distanceMeters()).isBetween(15, 19);
        });
        assertThat(pairs).noneMatch(p -> p.distanceMeters() > 150);
        assertThat(pairs).noneMatch(p -> p.lowId() == quietA.getId() || p.highId() == quietA.getId());
        assertThat(pairs).as("다이아몬드타워와 부산 타워는 후보로 나왔거나 알려진 행이 아니라 고르지 않는다").hasSize(2);
    }

    @Test
    @DisplayName("식당 쌍은 60m 안에서 이름 앞부분이 겹칠 때만 고르고, 문장부호만 있는 이름은 아무 이름과도 겹치지 않는다")
    void diningPairs() {
        Place a = place("원당감자탕", "MEAL", 0, "foursquare", false);
        Place b = place("원당감자탕 충무로점", "MEAL", 0.0001, "foursquare", false);
        place("다른 국밥", "MEAL", 0.00005, "foursquare", false);
        place("원당감자탕 본점", "MEAL", 0.001, "foursquare", false);
        place("!!!", "MEAL", 0.00002, "foursquare", false);

        List<PairRow> pairs = links.findNearPairs(REGION, PairClass.DINING, 60);

        long[] expected = ids(a, b);
        assertThat(pairs).singleElement().satisfies(p -> {
            assertThat(p.lowId()).isEqualTo(expected[0]);
            assertThat(p.highId()).isEqualTo(expected[1]);
        });
    }

    @Test
    @DisplayName("행 사실은 검색 가능 여부(임베딩·역할)와 출처·언어판을 담고, 지역 목록에 시험 지역이 있다")
    void placeRowsAndRegions() {
        Place tower = place("부산타워", "SIGHT", 0, "tourapi", true);
        Place hidden = place("방문 불가", "NOT_VISITABLE", 0.0001, "foursquare", false);

        var rows = links.findPlaceRows(List.of(tower.getId(), hidden.getId()));

        assertThat(rows.get(tower.getId()).source()).isEqualTo("tourapi");
        assertThat(rows.get(tower.getId()).candidate()).isTrue();
        assertThat(rows.get(tower.getId()).searchable()).as("임베딩이 없으면 검색에 나오지 않는다").isFalse();
        assertThat(rows.get(hidden.getId()).searchable()).isFalse();
        assertThat(links.findActiveRegions()).contains(REGION);
    }

    @Test
    @DisplayName("판정은 쌍마다 하나로 덮어쓰고, 합쳐지는 행 기록은 원하는 대로 맞추며 빠진 행은 합침을 푼다")
    void verdictsAndLinks() {
        Place tower = place("부산타워", "SIGHT", 0, "tourapi", true);
        Place diamond = place("부산다이아몬드타워", "SIGHT", 0.00015, "foursquare", false);
        Place shop = place("Busan Tower Shop", "SHOPPING", 0.0002, "foursquare", false);
        long[] pair = ids(tower, diamond);

        links.upsertVerdicts(List.of(new VerdictRow(pair[0], pair[1], "DIFFERENT", "LLM", "k1")));
        links.upsertVerdicts(List.of(new VerdictRow(pair[0], pair[1], "SAME", "RULE", "k2")));
        assertThat(links.findVerdicts(REGION)).containsExactly(new VerdictRow(pair[0], pair[1], "SAME", "RULE", "k2"));

        assertThat(links.replaceLinks(REGION, List.of(new LinkRow(diamond.getId(), tower.getId(), "SAME"),
                new LinkRow(shop.getId(), tower.getId(), "PART")))).isEqualTo(2);
        assertThat(links.replaceLinks(REGION, List.of(new LinkRow(diamond.getId(), tower.getId(), "SAME"),
                new LinkRow(shop.getId(), tower.getId(), "PART")))).as("같은 기록은 다시 쓰지 않는다").isZero();
        assertThat(links.replaceLinks(REGION, List.of(new LinkRow(diamond.getId(), tower.getId(), "SAME"))))
                .isEqualTo(1);
        assertThat(links.findLinks(REGION)).containsExactly(new LinkRow(diamond.getId(), tower.getId(), "SAME"));
    }

    @Test
    @DisplayName("합침 관계는 SAME·PART만 들어간다")
    void mergeRelationValues() {
        Place a = place("가", "SIGHT", 0, "tourapi", true);
        Place b = place("나", "SIGHT", 0.0001, "tourapi", true);

        assertThatThrownBy(() -> merge(b.getId(), a.getId(), "MAYBE")).hasStackTraceContaining("chk_places_merge_relation");
    }

    @Test
    @DisplayName("합침 대상과 관계는 함께 있어야 한다")
    void mergeRelationRequired() {
        Place a = place("가", "SIGHT", 0, "tourapi", true);
        Place b = place("나", "SIGHT", 0.0001, "tourapi", true);

        assertThatThrownBy(() -> merge(b.getId(), a.getId(), null)).hasStackTraceContaining("chk_places_merge_relation");
    }

    @Test
    @DisplayName("행은 자기 자신에 합쳐질 수 없다")
    void mergeIntoSelfRejected() {
        Place a = place("가", "SIGHT", 0, "tourapi", true);

        assertThatThrownBy(() -> merge(a.getId(), a.getId(), "SAME")).hasStackTraceContaining("chk_places_merge_relation");
    }

    private void merge(long id, long target, String relation) {
        em.createNativeQuery("UPDATE places SET merged_into_id = ?, merge_relation = CAST(? AS varchar) WHERE id = ?")
                .setParameter(1, target).setParameter(2, relation).setParameter(3, id).executeUpdate();
    }

    @Test
    @DisplayName("판정은 낮은 id가 앞이다")
    void verdictOrderConstraint() {
        Place a = place("가", "SIGHT", 0, "tourapi", true);
        Place b = place("나", "SIGHT", 0.0001, "tourapi", true);
        long[] pair = ids(a, b);

        assertThatThrownBy(() -> links.upsertVerdicts(List.of(new VerdictRow(pair[1], pair[0], "SAME", "LLM", "k"))))
                .hasStackTraceContaining("chk_place_pair_order");
    }

    @Test
    @DisplayName("판정 관계는 정해진 넷만 들어간다")
    void verdictValueConstraints() {
        Place a = place("가", "SIGHT", 0, "tourapi", true);
        Place b = place("나", "SIGHT", 0.0001, "tourapi", true);
        long[] pair = ids(a, b);

        assertThatThrownBy(() -> links.upsertVerdicts(List.of(new VerdictRow(pair[0], pair[1], "MAYBE", "LLM", "k"))))
                .hasStackTraceContaining("chk_place_pair_relation");
    }

    @Test
    @DisplayName("판정 출처는 RULE·LLM만 들어간다")
    void verdictBasisConstraint() {
        Place a = place("가", "SIGHT", 0, "tourapi", true);
        Place b = place("나", "SIGHT", 0.0001, "tourapi", true);
        long[] pair = ids(a, b);

        assertThatThrownBy(() -> links.upsertVerdicts(List.of(new VerdictRow(pair[0], pair[1], "SAME", "GUESS", "k"))))
                .hasStackTraceContaining("chk_place_pair_basis");
    }

    @Test
    @DisplayName("격자가 놓치는 거리는 받지 않는다")
    void rejectsDistanceBeyondGrid() {
        assertThatThrownBy(() -> links.findNearPairs(REGION, PairClass.ATTRACTION, PlaceLinkRepository.MAX_PAIR_METERS + 1))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
