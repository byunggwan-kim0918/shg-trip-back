package com.shg.trip.shgtrip.domain.place.destination;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;

/** 여행지 사전을 이름·한국어 이름·별칭 키로 찾고, 같은 이름이면 준비된 곳·사람 사는 곳·인구 순으로 고르는지 본다. */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@Transactional
class DestinationCatalogIntegrationTest {

    @MockBean
    S3Client s3Client;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    DestinationCatalog catalog;

    private void insert(String key, String country, String kind, String name, String nameKo, String aliases,
                        int population, String status, String regionKey) {
        jdbcTemplate.update("""
                INSERT INTO destinations (destination_key, country, kind, name, name_ko, alias_keys, latitude, longitude,
                                          population, status, region_key, source)
                VALUES (?, ?, ?, ?, ?, CAST(? AS text[]), 0, 0, ?, ?, ?, 'test')""",
                key, country, kind, name, nameKo, aliases, population, status, regionKey);
    }

    @Test
    @DisplayName("한국어 이름·별칭으로 찾고, 같은 이름이면 큰 거주지를 고르며 나라를 주면 그 나라에서만 찾는다")
    void lookupByAnyNameKey() {
        insert("ZZ:gn:1", "ZZ", "town", "Otaru", "오타루 시", "{otaru,오타루시,小樽}", 115333, "CANDIDATE", null);
        insert("ZZ:gn:2", "ZZ", "town", "Otaru", null, "{otaru}", 0, "CANDIDATE", null);
        insert("ZZ:gn:3", "ZZ", "mountain", "Otaru", null, "{otaru}", 0, "CANDIDATE", null);
        insert("ZY:gn:4", "ZY", "town", "Otaru", null, "{otaru}", 999999, "CANDIDATE", null);

        assertThat(catalog.lookup("小樽", "ZZ")).map(DestinationCatalog.Entry::key).contains("ZZ:gn:1");
        assertThat(catalog.lookup("Otaru", "ZZ")).map(DestinationCatalog.Entry::key).contains("ZZ:gn:1");
        assertThat(catalog.lookup("오타루 시", "ZZ")).map(DestinationCatalog.Entry::shownName).contains("오타루 시");
        assertThat(catalog.lookup("Otaru", null)).map(DestinationCatalog.Entry::key).contains("ZY:gn:4");
        assertThat(catalog.lookup("Atlantis", "ZZ")).isEmpty();
    }

    @Test
    @DisplayName("요청 수는 준비 전 여행지에만 쌓인다")
    void recordRequestOnlyForUnready() {
        insert("ZZ:gn:5", "ZZ", "island", "Yakushima", "야쿠시마", "{yakushima,야쿠시마}", 0, "PREPARING", "ZZ_Yakushima");
        insert("ZZ:curated:Fukuoka", "ZZ", "city", "Fukuoka", "후쿠오카", "{fukuoka,후쿠오카}", 0, "ACTIVE", "ZZ_Fukuoka");

        catalog.recordRequest("ZZ:gn:5");
        catalog.recordRequest("ZZ:curated:Fukuoka");

        assertThat(jdbcTemplate.queryForObject("SELECT requested_count FROM destinations WHERE destination_key = 'ZZ:gn:5'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT requested_count FROM destinations WHERE destination_key = 'ZZ:curated:Fukuoka'",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("후보는 장소 데이터 여행지 키가 없어야 하고, 쓰는 여행지는 키가 있어야 한다")
    void regionKeyMatchesStatus() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        insert("ZZ:gn:6", "ZZ", "town", "Nowhere", null, "{nowhere}", 0, "ACTIVE", null))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
