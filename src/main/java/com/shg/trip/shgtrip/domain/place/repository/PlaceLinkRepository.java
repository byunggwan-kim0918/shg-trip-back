package com.shg.trip.shgtrip.domain.place.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 같은 장소·딸린 장소 판정의 대상 쌍, 판정 기록, 합쳐지는 행 기록. 좌표 격자로 근처 쌍을 찾는 쿼리라 JPQL로 쓰지 않는다. */
@Repository
@RequiredArgsConstructor
public class PlaceLinkRepository {

    /** 판정할 장소 종류. 종류가 다른 행(식당과 그 위 호텔)은 같은 곳으로 묻지 않는다. */
    public enum PairClass {
        ATTRACTION(List.of("SIGHT", "ACTIVITY", "MARKET", "SHOPPING")),
        DINING(List.of("MEAL", "BAR", "CAFE")),
        LODGING(List.of("LODGING"));

        final List<String> roles;

        PairClass(List<String> roles) {
            this.roles = roles;
        }
    }

    public record PairRow(long lowId, long highId, int distanceMeters) {
    }

    /** @param searchable 활성이고 임베딩이 있고 방문 불가 역할이 아닌 행(후보 검색에 나올 수 있음) */
    public record PlaceRow(long id, String name, String nameKo, String category, String source, String address,
                           String wikidataId, int sitelinks, boolean searchable, boolean hasImage, boolean enriched,
                           boolean candidate) {
    }

    /** @param basis RULE(이름 표기만 다름) 또는 LLM */
    public record VerdictRow(long lowId, long highId, String relation, String basis, String inputKey) {
    }

    public record LinkRow(long placeId, long targetId, String relation) {
    }

    /** IN 목록 한 번의 크기. PostgreSQL 바인드 파라미터 한도(32767)보다 작아야 한다. */
    private static final int ID_CHUNK = 5_000;
    /** 격자 반 칸(경도 0.002도)이 위도 47도까지 150m를 넘어, 이보다 먼 쌍은 빠질 수 있다. */
    public static final int MAX_PAIR_METERS = 150;

    /** 문장부호만 있는 이름은 비어 버려 모든 이름의 앞부분이 된다. */
    private static final String NAME_PREFIX = """
            (length(a.cname) >= 2 AND length(b.cname) >= 2
             AND (strpos(b.cname, a.cname) = 1 OR strpos(a.cname, b.cname) = 1))""";

    private static final String HAVERSINE_METERS = """
            2 * 6371000 * asin(sqrt(power(sin(radians(b.la - a.la) / 2), 2)
                + cos(radians(a.la)) * cos(radians(b.la)) * power(sin(radians(b.lo - a.lo) / 2), 2)))""";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public List<String> findActiveRegions() {
        return jdbc.queryForList(
                "SELECT DISTINCT region FROM places WHERE active AND region IS NOT NULL ORDER BY region", String.class);
    }

    /** 격자는 0.004도 칸을 반 칸씩 네 번 어긋나게 놓아 150m 안의 쌍을 빠뜨리지 않는다. */
    public List<PairRow> findNearPairs(String region, PairClass pairClass, int maxMeters) {
        if (maxMeters > MAX_PAIR_METERS) {
            throw new IllegalArgumentException("격자가 " + MAX_PAIR_METERS + "m 밖의 쌍을 놓친다: " + maxMeters);
        }
        String filter = switch (pairClass) {
            case ATTRACTION -> "(a.cand OR b.cand OR a.links > 0 OR b.links > 0 OR a.tourapi OR b.tourapi)";
            case LODGING -> "(a.cand OR b.cand OR " + NAME_PREFIX + ")";
            case DINING -> "(" + NAME_PREFIX + " OR (length(a.cname) >= 3 AND left(a.cname, 3) = left(b.cname, 3)))";
        };
        String sql = """
                WITH s AS (
                  SELECT id, last_candidate_at IS NOT NULL AS cand, COALESCE(wikidata_sitelinks, 0) AS links,
                         source = 'tourapi' AS tourapi,
                         place_name_key(name) AS cname,
                         latitude::float8 AS la, longitude::float8 AS lo
                  FROM places
                  WHERE active AND region = :region AND place_role IN (:roles)
                    AND latitude IS NOT NULL AND longitude IS NOT NULL),
                g AS (
                  SELECT s.*, o.k, floor((s.la + o.dy) / 0.004) AS gy, floor((s.lo + o.dx) / 0.004) AS gx
                  FROM s CROSS JOIN (VALUES (1, 0.0, 0.0), (2, 0.002, 0.0), (3, 0.0, 0.002), (4, 0.002, 0.002))
                       AS o(k, dy, dx)),
                p AS (
                  SELECT DISTINCT a.id AS low_id, b.id AS high_id, %s AS d
                  FROM g a JOIN g b ON a.k = b.k AND a.gy = b.gy AND a.gx = b.gx AND a.id < b.id AND %s)
                SELECT low_id, high_id, round(d)::int AS d FROM p WHERE d <= :maxMeters
                """.formatted(HAVERSINE_METERS, filter);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("region", region)
                .addValue("roles", pairClass.roles).addValue("maxMeters", maxMeters);
        return named.query(sql, params,
                (rs, i) -> new PairRow(rs.getLong("low_id"), rs.getLong("high_id"), rs.getInt("d")));
    }

    /** 주소는 판정 프롬프트를 고른 라벨 표본과 같게 앞 80자만 읽는다. */
    public Map<Long, PlaceRow> findPlaceRows(Collection<Long> ids) {
        Map<Long, PlaceRow> out = new HashMap<>();
        List<Long> all = List.copyOf(ids);
        for (int i = 0; i < all.size(); i += ID_CHUNK) {
            readPlaceRows(all.subList(i, Math.min(i + ID_CHUNK, all.size())), out);
        }
        return out;
    }

    private void readPlaceRows(List<Long> ids, Map<Long, PlaceRow> out) {
        named.query("""
                SELECT id, name, name_ko, category, source, left(address, 80) AS address, wikidata_id,
                       COALESCE(wikidata_sitelinks, 0) AS links,
                       active AND embedding IS NOT NULL AND COALESCE(place_role, '') <> 'NOT_VISITABLE' AS searchable,
                       image_url IS NOT NULL AS has_image, enriched_at IS NOT NULL AS enriched,
                       last_candidate_at IS NOT NULL AS cand
                FROM places WHERE id IN (:ids)
                """, new MapSqlParameterSource("ids", ids), rs -> {
            out.put(rs.getLong("id"), new PlaceRow(rs.getLong("id"), rs.getString("name"), rs.getString("name_ko"),
                    rs.getString("category"), rs.getString("source"), rs.getString("address"),
                    rs.getString("wikidata_id"), rs.getInt("links"), rs.getBoolean("searchable"),
                    rs.getBoolean("has_image"), rs.getBoolean("enriched"), rs.getBoolean("cand")));
        });
    }

    /**
     * 지역 안 행이 낀 판정. 근처 쌍은 같은 지역끼리만 만들지만 파이프라인 쌍은 지역 경계를 넘을 수 있어 양쪽 지역에서 읽는다.
     * 한쪽 지역에서만 읽으면 다른 쪽 지역을 처리할 때 그 합침을 판정 없는 합침으로 보고 푼다.
     */
    public List<VerdictRow> findVerdicts(String region) {
        return jdbc.query("""
                SELECT v.low_id, v.high_id, v.relation, v.basis, v.input_key
                FROM place_pair_verdicts v JOIN places p ON p.id = v.low_id JOIN places q ON q.id = v.high_id
                WHERE p.region = ? OR q.region = ?
                """, (rs, i) -> new VerdictRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getString(5)), region, region);
    }

    @Transactional
    public void upsertVerdicts(List<VerdictRow> verdicts) {
        jdbc.batchUpdate("""
                INSERT INTO place_pair_verdicts (low_id, high_id, relation, basis, input_key, judged_at)
                VALUES (?, ?, ?, ?, ?, now())
                ON CONFLICT (low_id, high_id) DO UPDATE
                SET relation = EXCLUDED.relation, basis = EXCLUDED.basis, input_key = EXCLUDED.input_key, judged_at = now()
                """, verdicts, 500, (ps, v) -> {
            ps.setLong(1, v.lowId());
            ps.setLong(2, v.highId());
            ps.setString(3, v.relation());
            ps.setString(4, v.basis());
            ps.setString(5, v.inputKey());
        });
    }

    public List<LinkRow> findLinks(String region) {
        return jdbc.query("""
                SELECT id, merged_into_id, merge_relation FROM places
                WHERE region = ? AND merged_into_id IS NOT NULL
                """, (rs, i) -> new LinkRow(rs.getLong(1), rs.getLong(2), rs.getString(3)), region);
    }

    /** 지역의 합쳐지는 행을 links로 맞춘다. 거기 없는 행은 합침을 푼다. */
    @Transactional
    public int replaceLinks(String region, Collection<LinkRow> links) {
        Map<Long, LinkRow> wanted = new HashMap<>();
        links.forEach(l -> wanted.put(l.placeId(), l));
        List<Long> stale = findLinks(region).stream().map(LinkRow::placeId).filter(id -> !wanted.containsKey(id)).toList();
        int changed = 0;
        for (int i = 0; i < stale.size(); i += ID_CHUNK) {
            changed += named.update("UPDATE places SET merged_into_id = NULL, merge_relation = NULL WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", stale.subList(i, Math.min(i + ID_CHUNK, stale.size()))));
        }
        for (int[] batch : jdbc.batchUpdate("""
                UPDATE places SET merged_into_id = ?, merge_relation = ?
                WHERE id = ? AND (merged_into_id IS DISTINCT FROM ? OR merge_relation IS DISTINCT FROM ?)
                """, List.copyOf(wanted.values()), 500, (ps, l) -> {
            ps.setLong(1, l.targetId());
            ps.setString(2, l.relation());
            ps.setLong(3, l.placeId());
            ps.setLong(4, l.targetId());
            ps.setString(5, l.relation());
        })) {
            for (int n : batch) changed += Math.max(n, 0);
        }
        return changed;
    }
}
