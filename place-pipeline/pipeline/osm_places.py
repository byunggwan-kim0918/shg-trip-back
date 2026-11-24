"""
풀에 없는 OSM 장소를 풀과 같은 모양의 행으로 만든다. 운영 반영 전 실험용이고, 출처·라이선스는 행마다 남긴다.
  osm_places : OSM 객체마다 상태 하나. new(풀에 없음) possible_duplicate(같은 역할의 풀 행이 바로 옆) osm_duplicate(같은 이름 OSM 객체가 옆에 있고 그쪽이 더 낫다)
  pool_*     : 기존 풀과 new 행을 합친 뷰(candidates·evidence·osm_evidence). selection.build에 그대로 넣는다.
osm_link.build가 만든 osm_rows·osm_pairs·pool_rows를 읽는다. 풀 행과 어떤 관계(SAME·PART_OF·DISTINCT·REVIEW)라도 있으면 넣지 않는다.
"""
from . import identity, osm_link
from .rules import literal as _literal

SOURCE = "osm"
LICENSE = "ODbL-1.0"
# 풀에 더하는 역할. support·general은 풀에도 충분하고, OSM에만 있는 것은 대부분 이름 붙은 부대시설이다.
ADDABLE_ROLES = ("destination", "experience", "food")
# 이름이 달라도 같은 역할의 풀 행이 이만큼 안에 있으면 같은 장소일 수 있어 따로 둔다.
POOL_NEAR_METERS = 30
# OSM 안에서 같은 이름이 이만큼 안에 있으면 같은 장소를 점·면으로 두 번 그린 것으로 본다.
OSM_DUPLICATE_METERS = 150
_CELL = 0.001
_KEY_CELL = 0.002


def build(con, country, release, wikidata_raw=None, pool_candidates="candidates", pool_evidence="evidence",
          pool_osm_evidence="osm_evidence"):
    """osm_places 표와 pool_candidates·pool_evidence·pool_osm_evidence 뷰를 만든다."""
    roles = ", ".join(f"'{r}'" for r in ADDABLE_ROLES)
    h = identity._HAVERSINE_M
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE osm_unpaired AS
        SELECT o.* FROM osm_rows o
        WHERE o.osm_role IN ({roles}) AND o.osm_label IS NOT NULL AND o.name IS NOT NULL AND length(o.main_key) >= 2
          AND NOT EXISTS (SELECT 1 FROM osm_pairs p WHERE p.osm_rid = o.osm_rid)
    """)
    # 같은 이름 OSM 객체 가운데 위키데이터·태그 수·면(관계>면>점) 순으로 하나만 남긴다.
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE osm_dup AS
        WITH k AS (SELECT osm_rid, main_key, lat, lon, extent_m,
                          (wikidata IS NOT NULL)::INT * 1000 + tag_count * 10
                          + CASE osm_type WHEN 'relation' THEN 2 WHEN 'way' THEN 1 ELSE 0 END AS rank_score,
                          floor(lat / {_KEY_CELL})::BIGINT AS cy, floor(lon / {_KEY_CELL})::BIGINT AS cx
                   FROM osm_unpaired),
             n AS (SELECT *, cy + dy AS ny, cx + dx AS nx FROM k, (SELECT unnest([-1, 0, 1]) AS dy),
                   (SELECT unnest([-1, 0, 1]) AS dx))
        SELECT DISTINCT a.osm_rid
        FROM k a JOIN n b ON b.main_key = a.main_key AND b.ny = a.cy AND b.nx = a.cx AND b.osm_rid <> a.osm_rid
        WHERE (b.rank_score > a.rank_score OR (b.rank_score = a.rank_score AND b.osm_rid < a.osm_rid))
          AND {h} <= greatest({OSM_DUPLICATE_METERS}, b.extent_m)
    """)
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE osm_near_pool AS
        WITH o AS (SELECT osm_rid, osm_role, lat, lon, floor(lat / {_CELL})::BIGINT + dy AS cy,
                          floor(lon / {_CELL})::BIGINT + dx AS cx
                   FROM osm_unpaired, (SELECT unnest([-1, 0, 1]) AS dy), (SELECT unnest([-1, 0, 1]) AS dx)),
             p AS (SELECT role, lat, lon, floor(lat / {_CELL})::BIGINT AS cy, floor(lon / {_CELL})::BIGINT AS cx
                   FROM pool_rows)
        SELECT DISTINCT a.osm_rid FROM o a JOIN p b USING (cy, cx)
        WHERE b.role = a.osm_role AND {h} <= {POOL_NEAR_METERS}
    """)
    sitelinks, wikidata_join = osm_link.place_sitelinks(wikidata_raw)
    con.execute(f"""
        CREATE OR REPLACE TABLE osm_places AS
        SELECT '{SOURCE}' AS source, o.osm_type || '/' || o.osm_id AS source_id, o.osm_rid,
               CASE WHEN d.osm_rid IS NOT NULL THEN 'osm_duplicate'
                    WHEN n.osm_rid IS NOT NULL THEN 'possible_duplicate'
                    ELSE 'new' END AS status,
               o.name, o.name_ja, o.name_en, o.name_ko, o.lat, o.lon, o.extent_m, o.osm_type, o.osm_id, o.osm_tag,
               o.osm_label, o.osm_role, o.osm_signal, o.tag_count, o.cuisine, o.opening_hours, o.wikidata,
               {sitelinks} AS wd_sitelinks, o.heritage, coalesce(o.brand, o."brand:wikidata") AS brand,
               coalesce(o.website, o."contact:website") AS website, coalesce(o.phone, o."contact:phone") AS phone,
               o.phone_tail, o.host, '{LICENSE}' AS license
        FROM osm_unpaired o
        LEFT JOIN osm_dup d USING (osm_rid) LEFT JOIN osm_near_pool n USING (osm_rid)
        {wikidata_join}
    """)
    names = ("list_filter([" + ", ".join(
        f"CASE WHEN {col} IS NOT NULL THEN {{'value': {col}, 'lang': '{lang}', 'kind': 'primary'}} END"
        for col, lang in (("name", "und"), ("name_ja", "ja"), ("name_en", "en"), ("name_ko", "ko"))) + "], x -> x IS NOT NULL)")
    con.execute(f"""
        CREATE OR REPLACE VIEW osm_new_candidates AS
        SELECT source, source_id, '{_literal(release)}' AS release, name, name_key(name) AS name_key, {names} AS names,
               lat, lon, '{_literal(country)}' AS country, osm_label AS category,
               CASE WHEN length(phone_tail) = {identity.PHONE_DIGITS} THEN [phone_tail] ELSE [] END AS phones,
               CASE WHEN website IS NOT NULL THEN [website] ELSE [] END AS websites, license
        FROM osm_places WHERE status = 'new'
    """)
    con.execute(f"""
        CREATE OR REPLACE VIEW osm_new_evidence AS
        SELECT source, source_id, 'osm_only' AS pool_basis, osm_label AS pool_category, false AS independent_confirm,
               CASE WHEN website IS NOT NULL THEN 'own' END AS web_kind, host AS web_host,
               wikidata AS wd_qid, wd_sitelinks
        FROM osm_places WHERE status = 'new'
    """)
    con.execute(f"""
        CREATE OR REPLACE VIEW osm_new_osm_evidence AS
        SELECT source, source_id, osm_type, osm_id, 'self' AS osm_rule, osm_tag, osm_signal, tag_count AS osm_tag_count,
               cuisine AS osm_cuisine, opening_hours AS osm_opening_hours, brand AS osm_brand, wikidata AS osm_wikidata,
               wd_sitelinks AS osm_wd_sitelinks, heritage AS osm_heritage, website AS osm_website, phone AS osm_phone,
               license
        FROM osm_places WHERE status = 'new'
    """)
    for view, pool, new in (("pool_candidates", pool_candidates, "osm_new_candidates"),
                            ("pool_evidence", pool_evidence, "osm_new_evidence"),
                            ("pool_osm_evidence", pool_osm_evidence, "osm_new_osm_evidence")):
        con.execute(f"CREATE OR REPLACE VIEW {view} AS SELECT * FROM {pool} UNION ALL BY NAME SELECT * FROM {new}")
