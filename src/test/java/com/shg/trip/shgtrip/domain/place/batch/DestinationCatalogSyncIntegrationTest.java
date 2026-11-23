package com.shg.trip.shgtrip.domain.place.batch;

import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 쓰는 여행지는 ACTIVE로 맞추고, 후보 CSV는 없는 것만 CANDIDATE로 넣는지 본다. */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@Transactional
class DestinationCatalogSyncIntegrationTest {

    @MockBean
    S3Client s3Client;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    Destinations destinations;

    @TempDir
    Path dir;

    @Test
    @DisplayName("쓰는 여행지는 ACTIVE·장소 여행지 키를 갖고, 후보는 두 번 넣어도 한 번만 들어가며 쓰는 여행지를 덮지 않는다")
    void syncsCuratedAndCandidates() throws Exception {
        DestinationCatalogSync sync = new DestinationCatalogSync(jdbcTemplate, destinations);
        sync.syncCurated();
        Path csv = dir.resolve("candidates.csv");
        Files.writeString(csv, """
                destination_key,country,kind,name,name_ko,aliases,latitude,longitude,population,admin1,source,source_id
                JP:gn:2128574,JP,town,Otaru,오타루 시,小樽|小樽市,43.18944,141.00222,115333,12,geonames,2128574
                KR:curated:Seoul,KR,town,Seoul,서울,,37.5,127.0,0,11,geonames,1
                """);

        assertThat(sync.loadCandidates(csv)).isEqualTo(1);
        assertThat(sync.loadCandidates(csv)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status || ':' || region_key FROM destinations WHERE destination_key = 'KR:curated:Seoul'",
                String.class)).isEqualTo("ACTIVE:Seoul");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM destinations WHERE destination_key = 'JP:gn:2128574'", String.class))
                .isEqualTo("CANDIDATE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT alias_keys @> ARRAY['小樽'] FROM destinations WHERE destination_key = 'JP:gn:2128574'",
                Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT parent_key FROM destinations WHERE destination_key = 'JP:curated:Otaru'", String.class))
                .isEqualTo("JP:curated:Sapporo");
    }
}
