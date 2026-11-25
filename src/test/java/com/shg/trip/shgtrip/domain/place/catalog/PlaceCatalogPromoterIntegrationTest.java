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
        jdbc.update("UPDATE places SET merged_into_id = NULL, merge_relation = NULL WHERE source = ?", SOURCE);
        jdbc.update("DELETE FROM places WHERE source = ?", SOURCE);
    }

    private static CatalogPlace catalog(String name, String fsqId) {
        return new CatalogPlace(name, null, "addr", 35.0, 135.0, "Landmarks and Outdoors > Park", "Kyoto", "JP",
                SOURCE, fsqId, null, null, null, null, null, List.of(), null);
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
}
