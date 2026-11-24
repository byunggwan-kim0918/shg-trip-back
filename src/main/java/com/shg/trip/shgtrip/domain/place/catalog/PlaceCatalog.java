package com.shg.trip.shgtrip.domain.place.catalog;

import lombok.extern.slf4j.Slf4j;
import org.duckdb.DuckDBConnection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Array;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 운영 DB 밖 장소 카탈로그. 나라별 Parquet 파일(place-pipeline 기준 선별 전체)을 DuckDB로 읽는다.
 * 운영 DB에는 장소 수 상한이 있어 핵심 장소만 두고, 초안이 고른 장소가 DB에 없을 때 여기서 찾아 DB로 올린다.
 * {@code place.catalog.path}가 비어 있으면 꺼져 있고 조회는 빈 결과다.
 *
 * <p>파일 열: name·name_key·name_ko·name_ko_key·address·latitude·longitude·category·region·country·source·
 * fsq_place_id·wikidata_id·wikidata_sitelinks·place_role·cuisine·description·tags·embedding.
 * 이름 키는 DB {@code place_name_key}로 만든 값이라 {@code PlaceRepository} 이름 조회와 같은 규칙으로 맞춘다.
 */
@Slf4j
@Component
public class PlaceCatalog implements AutoCloseable {

    private static final int COMPACT_LIMIT = 200;
    private static final int EDGES_LIMIT = 50;
    /** 이름 대조에 읽는 열. 임베딩은 행당 수 KB라 DB로 새로 올릴 한 행만 {@link #embeddingOf}로 읽는다. */
    private static final String COLUMNS = "name, name_ko, address, latitude, longitude, category, region, country, source, "
            + "fsq_place_id, wikidata_id, wikidata_sitelinks, place_role, cuisine, description, tags";

    private final DuckDBConnection connection;

    public PlaceCatalog(@Value("${place.catalog.path:}") String path) {
        this.connection = path == null || path.isBlank() ? null : open(path);
    }

    private static DuckDBConnection open(String path) {
        if (!Files.isDirectory(Path.of(path))) {
            throw new IllegalStateException("장소 카탈로그 폴더가 없다: " + path);
        }
        try {
            DuckDBConnection c = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
            try (Statement s = c.createStatement()) {
                s.execute("CREATE VIEW catalog AS SELECT * FROM read_parquet('"
                        + Path.of(path).resolve("*.parquet").toString().replace("'", "''") + "')");
            }
            return c;
        } catch (SQLException e) {
            throw new IllegalStateException("DuckDB를 열지 못했다", e);
        }
    }

    public boolean enabled() {
        return connection != null;
    }

    /** {@code PlaceRepository.findActiveByCompactName}과 같은 조건(이름·한국어 이름 키가 질의를 담거나 질의에 담김). */
    public List<CatalogPlace> findByCompactName(String compact, int minLength, Collection<String> regions,
                                                Collection<String> roles) {
        String where = "(contains(name_key, ?) OR (length(name_key) >= ? AND contains(?, name_key))"
                + " OR contains(name_ko_key, ?) OR (length(name_ko_key) >= ? AND contains(?, name_ko_key)))";
        return query(where, List.of(compact, minLength, compact, compact, minLength, compact), regions, roles,
                COMPACT_LIMIT);
    }

    /** {@code PlaceRepository.findActiveByNameEdges}와 같은 조건(이름 키가 head로 시작하고 tail로 끝남). */
    public List<CatalogPlace> findByNameEdges(String head, String tail, Collection<String> regions,
                                              Collection<String> roles) {
        String where = "((starts_with(name_key, ?) AND ends_with(name_key, ?))"
                + " OR (starts_with(name_ko_key, ?) AND ends_with(name_ko_key, ?)))";
        return query(where, List.of(head, tail, head, tail), regions, roles, EDGES_LIMIT);
    }

    private List<CatalogPlace> query(String where, List<Object> params, Collection<String> regions,
                                     Collection<String> roles, int limit) {
        if (connection == null || regions == null || regions.isEmpty()) return List.of();
        List<String> roleList = roles == null ? List.of() : List.copyOf(roles);
        String roleFilter = roleList.isEmpty() ? "place_role IS NULL"
                : "(place_role IS NULL OR place_role IN (" + marks(roleList.size()) + "))";
        String sql = "SELECT " + COLUMNS + " FROM catalog WHERE region IN (" + marks(regions.size()) + ") AND "
                + roleFilter + " AND " + where + " ORDER BY length(name), name LIMIT " + limit;
        List<CatalogPlace> out = new ArrayList<>();
        try (DuckDBConnection c = (DuckDBConnection) connection.duplicate();
             PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (String r : regions) ps.setString(i++, r);
            for (String r : roleList) ps.setString(i++, r);
            for (Object p : params) ps.setObject(i++, p);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(row(rs));
            }
        } catch (SQLException e) {
            // 카탈로그를 못 읽으면 DB 장소만으로 짓는다. 생성 자체를 실패시키지 않는다.
            log.warn("장소 카탈로그 조회 실패: {}", e.getMessage());
            return List.of();
        }
        return out;
    }

    /** DB에 새로 넣을 행의 임베딩(halfvec 문자열). 없거나 못 읽으면 null이고, 그 행은 임베딩 배치가 채운다. */
    public String embeddingOf(CatalogPlace c) {
        if (connection == null) return null;
        String sql = "SELECT embedding FROM catalog WHERE name = ? AND address IS NOT DISTINCT FROM ? AND source = ?"
                + " AND latitude = ? AND longitude = ? LIMIT 1";
        try (DuckDBConnection d = (DuckDBConnection) connection.duplicate(); PreparedStatement ps = d.prepareStatement(sql)) {
            ps.setString(1, c.name());
            ps.setString(2, c.address());
            ps.setString(3, c.source());
            ps.setDouble(4, c.latitude());
            ps.setDouble(5, c.longitude());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            log.warn("장소 카탈로그 임베딩 조회 실패: {}", e.getMessage());
            return null;
        }
    }

    private static String marks(int n) {
        return String.join(", ", Collections.nCopies(n, "?"));
    }

    private static CatalogPlace row(ResultSet rs) throws SQLException {
        Array tags = rs.getArray("tags");
        List<String> tagList = new ArrayList<>();
        if (tags != null) {
            for (Object t : (Object[]) tags.getArray()) if (t != null) tagList.add(t.toString());
        }
        Number sitelinks = (Number) rs.getObject("wikidata_sitelinks");
        return new CatalogPlace(rs.getString("name"), rs.getString("name_ko"), rs.getString("address"),
                rs.getDouble("latitude"), rs.getDouble("longitude"), rs.getString("category"), rs.getString("region"),
                rs.getString("country"), rs.getString("source"), rs.getString("fsq_place_id"),
                rs.getString("wikidata_id"), sitelinks == null ? null : sitelinks.intValue(), rs.getString("place_role"),
                rs.getString("cuisine"), rs.getString("description"), tagList, null);
    }

    @Override
    public void close() throws SQLException {
        if (connection != null) connection.close();
    }
}
