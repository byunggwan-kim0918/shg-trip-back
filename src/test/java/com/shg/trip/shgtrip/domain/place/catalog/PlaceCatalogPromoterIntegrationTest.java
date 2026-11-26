package com.shg.trip.shgtrip.domain.place.catalog;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class PlaceCatalogPromoterIntegrationTest {

    private static final String SOURCE = "promoter-it";

    @MockBean
    S3Client s3Client;

    @Autowired
    PlaceCatalogPromoter promoter;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM place_source_refs WHERE place_id IN (SELECT id FROM places WHERE source = ?)", SOURCE);
        jdbc.update("UPDATE places SET merged_into_id = NULL, merge_relation = NULL WHERE source = ?", SOURCE);
        jdbc.update("DELETE FROM places WHERE source = ?", SOURCE);
    }

    private static CatalogPlace catalog(String name, String fsqId) {
        return new CatalogPlace(name, null, "addr", 35.0, 135.0, "Landmarks and Outdoors > Park", "Kyoto", "JP",
                SOURCE, fsqId, null, null, null, null, null, List.of(), null);
    }

    private static CatalogPlace catalog(String name, double latitude, String... refs) {
        return new CatalogPlace(name, null, "addr", latitude, 135.0, "Landmarks and Outdoors > Park", "Kyoto", "JP",
                SOURCE, null, null, null, null, null, null, List.of(), null, List.of(), List.of(), List.of(refs));
    }

    private void ref(String sourceRef, long placeId) {
        String[] parts = sourceRef.split(":", 2);
        jdbc.update("INSERT INTO place_source_refs (source, source_record_id, place_id) VALUES (?, ?, ?)",
                parts[0], parts[1], placeId);
    }

    private Long refPlace(String sourceRef) {
        String[] parts = sourceRef.split(":", 2);
        return jdbc.query("SELECT place_id FROM place_source_refs WHERE source = ? AND source_record_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, parts[0], parts[1]);
    }

    private int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM places WHERE source = ?", Integer.class, SOURCE);
    }

    private long existing(String name, String fsqId, boolean active, String reason) {
        return jdbc.queryForObject("""
                INSERT INTO places (name, address, latitude, longitude, category, region, country, source, fsq_place_id,
                                    active, deactivated_reason)
                VALUES (?, 'addr', 35.0, 135.0, 'Landmarks and Outdoors > Park', 'Kyoto', 'JP', ?, ?, ?, ?)
                RETURNING id""", Long.class, name, SOURCE, fsqId, active, reason);
    }

    @Test
    @DisplayName("정책으로 끈 행은 다시 켜지 않고 새로 넣지도 않는다")
    void policyDeactivatedRowStaysOff() {
        long id = existing("円山公園", null, false, "policy");

        Optional<Place> promoted = promoter.promote(catalog("円山公園", null));

        assertThat(promoted).isEmpty();
        assertThat(jdbc.queryForObject("SELECT active FROM places WHERE id = ?", Boolean.class, id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM places WHERE source = ?", Integer.class, SOURCE)).isEqualTo(1);
    }

    @Test
    @DisplayName("추출에서 빠져 꺼진 행은 같은 id로 다시 켠다")
    void staleRowIsReactivated() {
        long id = existing("円山公園", null, false, "stale");

        assertThat(promoter.promote(catalog("円山公園", null))).get().extracting(Place::getId).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT active FROM places WHERE id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    @DisplayName("이름이 바뀌었거나 합쳐진 행과 fsq id가 같으면 새로 넣지 않고 그 행(대표 행)을 쓴다")
    void sameFsqIdReusesRowOrRepresentative() {
        long renamed = existing("円山公園 (旧)", "fsq-it-1", true, null);
        assertThat(promoter.promote(catalog("円山公園", "fsq-it-1"))).get().extracting(Place::getId).isEqualTo(renamed);

        long rep = existing("八坂神社", null, true, null);
        long merged = existing("八坂神社 本殿", "fsq-it-2", true, null);
        jdbc.update("UPDATE places SET merged_into_id = ?, merge_relation = 'PART' WHERE id = ?", rep, merged);
        assertThat(promoter.promote(catalog("八坂神社本殿", "fsq-it-2"))).get().extracting(Place::getId).isEqualTo(rep);
    }

    @Test
    @DisplayName("fsq id 없이 이름·주소·좌표가 같은 행이 합쳐져 있으면 대표 행을 쓴다")
    void sameNameMergedRowReturnsRepresentative() {
        long rep = existing("円山公園", null, true, null);
        long merged = existing("Maruyama Park", null, true, null);
        jdbc.update("UPDATE places SET merged_into_id = ?, merge_relation = 'SAME' WHERE id = ?", rep, merged);

        assertThat(promoter.promote(catalog("Maruyama Park", null))).get().extracting(Place::getId).isEqualTo(rep);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM places WHERE source = ?", Integer.class, SOURCE)).isEqualTo(2);
    }

    @Test
    @DisplayName("사유 없이 꺼진 행은 다시 켜지 않는다")
    void rowOffWithoutReasonStaysOff() {
        long id = existing("円山公園", null, false, null);

        assertThat(promoter.promote(catalog("円山公園", null))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT active FROM places WHERE id = ?", Boolean.class, id)).isFalse();
    }

    @Test
    @DisplayName("같은 장소를 동시에 올려도 한 행만 생긴다")
    void concurrentPromotionsInsertOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<Place>>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Callable<Optional<Place>> task = () -> {
                start.await();
                return promoter.promote(catalog("清水寺", null));
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        List<Long> ids = new ArrayList<>();
        for (Future<Optional<Place>> f : results) ids.add(f.get().orElseThrow().getId());
        pool.shutdown();

        assertThat(ids).containsOnly(ids.get(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM places WHERE source = ?", Integer.class, SOURCE)).isEqualTo(1);
    }

    @Test
    @DisplayName("대표 원천 행이 DB 행과 달라도 구성원 원천 id로 기존 장소를 돌려주고 대표 원천 id를 그 장소에 잇는다")
    void memberSourceRefReturnsTheCorePlace() {
        long core = existing("金閣寺", null, true, null);
        ref("overture:core-gers", core);

        Optional<Place> promoted = promoter.promote(catalog("鹿苑寺", 35.0394, "overture:rep-gers", "overture:core-gers"));

        assertThat(promoted).get().extracting(Place::getId).isEqualTo(core);
        assertThat(rows()).isEqualTo(1);
        assertThat(refPlace("overture:rep-gers")).isEqualTo(core);
    }

    @Test
    @DisplayName("구성원 원천 id가 합쳐진 행이면 대표 행을 돌려준다")
    void memberSourceRefOfAMergedRowReturnsTheRepresentative() {
        long rep = existing("八坂神社", null, true, null);
        long merged = existing("祇園さん", null, true, null);
        jdbc.update("UPDATE places SET merged_into_id = ?, merge_relation = 'SAME' WHERE id = ?", rep, merged);
        ref("fsq:merged-fsq", merged);

        assertThat(promoter.promote(catalog("Yasaka Shrine", 35.0036, "overture:x", "fsq:merged-fsq")))
                .get().extracting(Place::getId).isEqualTo(rep);
        assertThat(rows()).isEqualTo(2);
    }

    @Test
    @DisplayName("새로 넣은 장소는 원천 id로 다시 찾아 이름·좌표가 바뀐 카탈로그로 다시 올려도 한 행이다")
    void insertedPlaceKeepsItsSourceRefAcrossRepeatedPromotions() {
        long first = promoter.promote(catalog("清水寺", 34.9948, "overture:kiyomizu")).orElseThrow().getId();
        long again = promoter.promote(catalog("音羽山 清水寺", 34.9950, "overture:kiyomizu")).orElseThrow().getId();

        assertThat(again).isEqualTo(first);
        assertThat(rows()).isEqualTo(1);
        assertThat(refPlace("overture:kiyomizu")).isEqualTo(first);
    }

    @Test
    @DisplayName("이 행의 원천 id가 다른 장소에 이어져 있으면 그 장소가 구성원 원천 id보다 앞서고 잇기를 옮기지 않는다")
    void ownSourceRefWinsOverMemberRefs() {
        long own = existing("円山公園", null, true, null);
        long member = existing("Maruyama Park", null, true, null);
        ref("overture:own", own);
        ref("overture:member", member);

        assertThat(promoter.promote(catalog("円山公園", 35.0, "overture:own", "overture:member")))
                .get().extracting(Place::getId).isEqualTo(own);
        assertThat(refPlace("overture:member")).isEqualTo(member);
    }

    @Test
    @DisplayName("구성원 FSQ 원천 id는 매핑이 없어도 그 fsq id 행을 돌려준다")
    void memberFsqRefFindsTheRowByFsqIdWithoutAMapping() {
        long core = existing("嵐山", "fsq-core", true, null);

        assertThat(promoter.promote(catalog("Arashiyama", 35.0094, "overture:rep", "fsq:fsq-core")))
                .get().extracting(Place::getId).isEqualTo(core);
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    @DisplayName("구성원 원천 id가 서로 다른 장소를 가리키면 고른 장소에 대표 원천 id를 잇지 않는다")
    void ambiguousMemberRefsDoNotFixTheRepresentativeRef() {
        long a = existing("清水寺", null, true, null);
        long b = existing("清水寺 北総門", null, true, null);
        ref("overture:member-a", a);
        ref("overture:member-b", b);

        assertThat(promoter.promote(catalog("音羽山清水寺", 34.9949, "overture:rep-ab", "overture:member-a",
                "overture:member-b"))).get().extracting(Place::getId).isEqualTo(a);
        assertThat(refPlace("overture:rep-ab")).isNull();
    }

    @Test
    @DisplayName("그 나라 장소가 있는데 원천 id 매핑이 없으면 합쳐진 대표 행을 새로 넣지 않고, 구성원이 없는 행은 넣는다")
    void mergedRepresentativeIsNotInsertedBeforeTheMappingExists() {
        jdbc.update("""
                INSERT INTO places (name, address, latitude, longitude, category, region, country, source, active)
                VALUES ('金閣寺', 'addr', 35.0, 135.0, 'Landmarks and Outdoors > Park', 'Kyoto', 'ZZ', ?, true)""", SOURCE);

        assertThat(promoter.promote(country(catalog("鹿苑寺", 35.0394, "overture:zz-rep", "overture:zz-core"), "ZZ")))
                .isEmpty();
        assertThat(promoter.promote(country(catalog("銀閣寺", 35.0270, "overture:zz-single"), "ZZ"))).isPresent();
    }

    private static CatalogPlace country(CatalogPlace c, String country) {
        return new CatalogPlace(c.name(), null, c.address(), c.latitude(), c.longitude(), c.category(), c.region(),
                country, c.source(), null, null, null, null, null, null, List.of(), null, List.of(), List.of(),
                c.sourceRefs());
    }
}
