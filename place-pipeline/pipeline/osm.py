"""
OSM 추출(.osm.pbf → 장소 Parquet). 이름이나 wikidata 태그가 있는 가게·명소·자연·교통 지점만 뽑는다.
점(node)은 좌표를 그대로 쓰고, 면(way)은 꼭짓점 몇 개의 평균을 대략적인 중심으로 쓴다.
관계(relation)는 구성원(면의 꼭짓점 두 개, 점은 그대로) 표본의 평균을 중심으로 쓴다. 습원·국립공원·등산로가 여기에 있다.
일본 전체 꼭짓점이 수억 개라 면마다 모든 꼭짓점을 잇지 않는다. 이름·수백 m 안 매칭에는 표본 평균으로 충분하다.
extent_m은 표본 좌표를 감싸는 사각형 대각선의 절반이다. 넓은 지형은 출처마다 대표 좌표가 그만큼 다를 수 있다.
"""
import json
import os
import time
from datetime import datetime, timezone

from . import snapshot
from .rules import literal as _literal

# 여행 장소 후보가 되는 OSM 주 태그.
# 이름 붙은 건물(building)만으로는 아파트·사무실이 대부분이라 넣지 않는다. 건물로 그린 사찰은 amenity로 잡힌다.
POI_KEYS = ("amenity", "shop", "tourism", "historic", "leisure", "craft", "natural", "man_made", "railway",
            "public_transport", "aeroway")
WATERWAY_POIS = ("waterfall", "rapids")
# 관계에서만 받는 태그. 보호구역은 국립공원급(IUCN 2)과 경관 보호구역(5)만 받는다. 나머지는 수렵·수원 보호구역이 대부분이다.
RELATION_ONLY_TAGS = {"boundary": ("national_park", "protected_area"), "route": ("hiking", "foot")}
PROTECT_CLASSES = ("2", "5")
# 정류장 묶음(stop_area)·노선은 역 점과 겹치고 행 수만 늘린다.
RELATION_EXCLUDED_KEYS = ("railway", "public_transport")
RELATION_SAMPLE_MEMBERS = 16
# 면 중심을 낼 때 쓰는 꼭짓점 수.
WAY_SAMPLE_REFS = 8
# 신호로 쓰는 태그. 태그 수 자체도 남긴다(현지 매퍼가 손본 정도).
SIGNAL_TAGS = ("cuisine", "opening_hours", "website", "contact:website", "phone", "contact:phone", "heritage",
               "wikipedia", "wikidata", "brand", "brand:wikidata", "addr:full", "addr:city", "description")


# 위경도 사각형 대각선의 절반(m). 수 km 범위라 평면 근사로 충분하다.
_HALF_DIAGONAL_M = ("0.5 * 6371000 * sqrt(pow(radians(max(lat) - min(lat)), 2) "
                    "+ pow(cos(radians(avg(lat))) * radians(max(lon) - min(lon)), 2))")


def extract(con, pbf_path, out_path, source=None, log=print):
    """source가 없으면 ST_ReadOSM(pbf_path)를 읽는다. 반환은 manifest."""
    snapshot._check_path(out_path)
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    if source is None:
        con.execute("INSTALL spatial; LOAD spatial;")
        source = f"ST_ReadOSM('{_literal(pbf_path)}')"
    has_poi = " OR ".join(f"tags['{k}'] IS NOT NULL" for k in POI_KEYS)
    # 폭포는 waterway 태그로 찍힌다. 강·수로 선까지 받으면 구간마다 행이 생겨 폭포·급류만 받는다.
    has_poi += " OR tags['waterway'] IN (" + ", ".join(f"'{v}'" for v in WATERWAY_POIS) + ")"
    named = "(tags['name'] IS NOT NULL OR tags['wikidata'] IS NOT NULL)"
    relation_poi = " OR ".join(f"tags['{k}'] IS NOT NULL" for k in POI_KEYS if k not in RELATION_EXCLUDED_KEYS)
    relation_poi += " OR tags['waterway'] IN (" + ", ".join(f"'{v}'" for v in WATERWAY_POIS) + ")"
    relation_poi += " OR tags['route'] IN (" + ", ".join(f"'{v}'" for v in RELATION_ONLY_TAGS["route"]) + ")"
    relation_poi += (" OR tags['boundary'] = 'national_park' OR (tags['boundary'] = 'protected_area' AND "
                     "tags['protect_class'] IN (" + ", ".join(f"'{v}'" for v in PROTECT_CLASSES) + "))")
    started = time.monotonic()
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE osm_nodes AS
        SELECT id, lat, lon, tags FROM {source}
        WHERE kind = 'node' AND {named} AND ({has_poi})
    """)
    log(f"nodes {con.execute('SELECT count(*) FROM osm_nodes').fetchone()[0]:,} ({time.monotonic() - started:.0f}s)")
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE osm_ways AS
        SELECT id, tags,
               list_transform(range(least(len(refs), {WAY_SAMPLE_REFS})),
                              i -> refs[1 + (i * len(refs)) // least(len(refs), {WAY_SAMPLE_REFS})]) AS sample_refs
        FROM {source}
        WHERE kind = 'way' AND {named} AND ({has_poi}) AND len(refs) > 0
    """)
    log(f"ways {con.execute('SELECT count(*) FROM osm_ways').fetchone()[0]:,} ({time.monotonic() - started:.0f}s)")
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE osm_rels AS
        WITH r AS (
            SELECT id, tags, list_transform(list_filter(range(len(refs)), i -> ref_types[i + 1] = 'way'),
                                            i -> refs[i + 1]) AS ways,
                   list_transform(list_filter(range(len(refs)), i -> ref_types[i + 1] = 'node'),
                                  i -> refs[i + 1]) AS nodes
            FROM {source}
            WHERE kind = 'relation' AND {named} AND ({relation_poi}) AND len(refs) > 0
        )
        SELECT id, tags,
               list_transform(range(least(len(ways), {RELATION_SAMPLE_MEMBERS})),
                              i -> ways[1 + (i * len(ways)) // least(len(ways), {RELATION_SAMPLE_MEMBERS})]) AS ways,
               nodes[1:{RELATION_SAMPLE_MEMBERS}] AS nodes
        FROM r
    """)
    con.execute("CREATE OR REPLACE TEMP TABLE osm_rel_way_ids AS SELECT DISTINCT unnest(ways) AS id FROM osm_rels")
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE osm_rel_ways AS
        SELECT w.id, [w.refs[1], w.refs[1 + len(w.refs) // 2]] AS refs
        FROM {source} w SEMI JOIN osm_rel_way_ids m ON m.id = w.id
        WHERE w.kind = 'way' AND len(w.refs) > 0
    """)
    log(f"relations {con.execute('SELECT count(*) FROM osm_rels').fetchone()[0]:,} "
        f"({time.monotonic() - started:.0f}s)")
    con.execute("""
        CREATE OR REPLACE TEMP TABLE osm_way_refs AS
        SELECT DISTINCT unnest(sample_refs) AS ref FROM osm_ways
        UNION SELECT DISTINCT unnest(refs) FROM osm_rel_ways
        UNION SELECT DISTINCT unnest(nodes) FROM osm_rels
    """)
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE osm_ref_coords AS
        SELECT n.id, n.lat, n.lon FROM {source} n SEMI JOIN osm_way_refs r ON r.ref = n.id
        WHERE n.kind = 'node'
    """)
    log(f"way vertices {con.execute('SELECT count(*) FROM osm_ref_coords').fetchone()[0]:,} "
        f"({time.monotonic() - started:.0f}s)")
    signals = ", ".join(f"tags['{t}'] AS \"{t}\"" for t in SIGNAL_TAGS)
    primary = "coalesce(" + ", ".join(f"CASE WHEN tags['{k}'] IS NOT NULL THEN '{k}' END"
                                      for k in POI_KEYS + ("waterway",) + tuple(RELATION_ONLY_TAGS)) + ")"
    con.execute(f"""
        COPY (
            WITH way_points AS (
                SELECT 'way' AS osm_type, w.id, c.lat, c.lon
                FROM osm_ways w, unnest(w.sample_refs) AS u(ref) JOIN osm_ref_coords c ON c.id = u.ref
            ),
            rel_points AS (
                SELECT 'relation' AS osm_type, r.id, c.lat, c.lon
                FROM osm_rels r, unnest(r.ways) AS u(way) JOIN osm_rel_ways w ON w.id = u.way,
                     unnest(w.refs) AS v(ref) JOIN osm_ref_coords c ON c.id = v.ref
                UNION ALL
                SELECT 'relation', r.id, c.lat, c.lon
                FROM osm_rels r, unnest(r.nodes) AS u(ref) JOIN osm_ref_coords c ON c.id = u.ref
            ),
            centers AS (
                SELECT osm_type, id, avg(lat) AS lat, avg(lon) AS lon,
                       {_HALF_DIAGONAL_M} AS extent_m
                FROM (SELECT * FROM way_points UNION ALL SELECT * FROM rel_points)
                GROUP BY 1, 2
            ),
            pois AS (
                SELECT 'node' AS osm_type, id AS osm_id, lat, lon, 0.0 AS extent_m, tags FROM osm_nodes
                UNION ALL SELECT c.osm_type, c.id, c.lat, c.lon, c.extent_m, w.tags
                FROM centers c JOIN osm_ways w ON c.osm_type = 'way' AND w.id = c.id
                UNION ALL SELECT c.osm_type, c.id, c.lat, c.lon, c.extent_m, r.tags
                FROM centers c JOIN osm_rels r ON c.osm_type = 'relation' AND r.id = c.id
            )
            SELECT osm_type, osm_id, lat, lon, round(extent_m) AS extent_m, tags['name'] AS name, tags['name:ja'] AS name_ja,
                   tags['name:en'] AS name_en, tags['name:ko'] AS name_ko,
                   {primary} AS primary_key, tags[{primary}] AS primary_value, cardinality(tags) AS tag_count,
                   {signals}, tags
            FROM pois
        ) TO '{_literal(out_path)}' (FORMAT parquet, COMPRESSION zstd)
    """)
    seconds = time.monotonic() - started
    manifest = {
        "source": "osm", "license": "ODbL-1.0", "terms_url": "https://www.openstreetmap.org/copyright",
        "input": os.path.basename(pbf_path),
        "input_sha256": snapshot.sha256(pbf_path) if os.path.exists(pbf_path) else None,
        "fetched_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "rows": con.execute(f"SELECT count(*) FROM read_parquet('{_literal(out_path)}')").fetchone()[0],
        "file_bytes": os.path.getsize(out_path), "sha256": snapshot.sha256(out_path),
        "seconds": round(seconds, 1), "max_rss_mb": snapshot.max_rss_mb(),
    }
    with open(out_path + ".manifest.json", "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    return manifest
