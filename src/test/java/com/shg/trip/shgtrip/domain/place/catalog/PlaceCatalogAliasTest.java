package com.shg.trip.shgtrip.domain.place.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceCatalogAliasTest {

    @TempDir
    Path dir;

    private PlaceCatalog catalog(boolean withAliases) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("""
                    COPY (SELECT 'Blue Pond' AS name, 'bluepond' AS name_key, 'bluepond' AS bare_key, '아오이이케' AS name_ko,
                                 '아오이이케' AS name_ko_key, '' AS address, 43.49 AS latitude, 142.61 AS longitude,
                                 'Landmarks and Outdoors > Lake' AS category, 'Biei' AS region, 'JP' AS country,
                                 'overture' AS source, NULL::VARCHAR AS fsq_place_id, 'Q1' AS wikidata_id,
                                 3 AS wikidata_sitelinks, NULL::VARCHAR AS place_role, NULL::VARCHAR AS cuisine,
                                 NULL::VARCHAR AS description, []::VARCHAR[] AS tags, NULL::VARCHAR AS embedding %s)
                    TO '%s' (FORMAT parquet)""".formatted(withAliases ? ", ['青い池'] AS alias_keys, 'overture:rep' AS source_ref, ['fsq:member'] AS member_refs" : "",
                    dir.resolve("jp.parquet")));
        }
        return new PlaceCatalog(dir.toString());
    }

    @Test
    @DisplayName("합쳐진 원천 행의 이름 키로 대표 행을 찾고 원천 id를 읽으며, 별칭·원천 id 열이 없는 옛 파일도 그대로 읽는다")
    void findsRepresentativeByMergedRowName() throws Exception {
        try (PlaceCatalog withAliases = catalog(true)) {
            assertThat(withAliases.findByExactKey("青い池", List.of("Biei"), List.of()))
                    .extracting(CatalogPlace::name).containsExactly("Blue Pond");
            assertThat(withAliases.findByExactKey("青い池", List.of("Biei"), List.of()).get(0).sourceRefs())
                    .containsExactly("overture:rep", "fsq:member");
        }
        try (PlaceCatalog old = catalog(false)) {
            assertThat(old.findByExactKey("青い池", List.of("Biei"), List.of())).isEmpty();
            assertThat(old.findByExactKey("bluepond", List.of("Biei"), List.of())).extracting(CatalogPlace::sourceRefs)
                    .containsExactly(List.of());
        }
    }
}
