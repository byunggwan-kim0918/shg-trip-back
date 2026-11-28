package com.shg.trip.shgtrip.domain.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FeedCoverageTest {

    private static final GeoPoint SENDAI = new GeoPoint(38.2601, 140.8822);
    private static final GeoPoint TOKYO = new GeoPoint(35.6812, 139.7671);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-27T00:00:00Z"));
    private final Clock clock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    };

    private static final String SENDAI_TWO_EDITIONS = """
            {"feeds": [{"feed": "odpt-SendaiMunicipal", "modes": ["BUS"], "bbox": [38.18, 140.55, 38.39, 141.0],
                        "periods": [["20261120", "20261130"], ["20261201", "20261230"]]}]}""";

    @Test
    void 이어_실은_판의_기간이면_운행_중이고_마지막_판이_끝나면_만료다() throws IOException {
        FeedCoverage c = FeedCoverage.read(mapper.readTree(SENDAI_TWO_EDITIONS));

        assertThat(c.lapsed(SENDAI, SENDAI, LocalDate.of(2026, 12, 1))).isEmpty();
        assertThat(c.lapsed(SENDAI, TOKYO, LocalDate.of(2026, 12, 31))).hasSize(1);
        assertThat(FeedCoverage.describe(c.lapsed(SENDAI, SENDAI, LocalDate.of(2026, 12, 31)), LocalDate.of(2026, 12, 31)))
                .contains("odpt-SendaiMunicipal [BUS] ended 2026-12-30");
    }

    @Test
    void 피드_지역_밖_경로는_건드리지_않는다() throws IOException {
        FeedCoverage c = FeedCoverage.read(mapper.readTree(SENDAI_TWO_EDITIONS));

        assertThat(c.lapsed(TOKYO, TOKYO, LocalDate.of(2027, 1, 15))).isEmpty();
    }

    @Test
    void 파일이_바뀌면_다시_읽고_깨지면_판정을_끈다(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("feeds-coverage.json");
        Files.writeString(file, """
                {"feeds": [{"feed": "f", "modes": ["BUS"], "bbox": [38.18, 140.55, 38.39, 141.0], "periods": [["20261101", "20261130"]]}]}""");
        FeedCoverage.File f = new FeedCoverage.File(file, mapper, clock);
        assertThat(f.get().lapsed(SENDAI, SENDAI, LocalDate.of(2026, 12, 5))).hasSize(1);

        Files.writeString(file, SENDAI_TWO_EDITIONS);
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-11-27T01:00:00Z")));
        now.set(now.get().plusSeconds(120));
        assertThat(f.get().lapsed(SENDAI, SENDAI, LocalDate.of(2026, 12, 5))).isEmpty();

        Files.writeString(file, "{broken");
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-11-27T02:00:00Z")));
        now.set(now.get().plusSeconds(120));
        assertThat(f.get().lapsed(SENDAI, SENDAI, LocalDate.of(2027, 5, 1))).isEmpty();
    }
}
