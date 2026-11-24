"""
OSM 장소를 풀 행에 잇고(새 행은 만들지 않는다) 근거를 붙인다. 결과는 OSM 출처·라이선스가 붙은 별도 표로만 남긴다.
  쌍 관계 : SAME(자동 연결) PART_OF DISTINCT REVIEW. 근거는 wikidata 태그, 이름(name·ja·en·ko), 좌표, 역할, 전화, 웹사이트.
  osm_links    : 풀 행 하나에 OSM 객체 하나(SAME만). OSM 객체 하나가 여러 풀 행에 걸리면 가장 가까운 하나만 남긴다.
  osm_evidence : 연결된 풀 행의 OSM 근거(주 태그·관광 표시·요리 종류·영업시간·브랜드·wikidata·태그 수).
  place_names  : OSM·Wikidata에서 온 다국어 이름(원문·언어·출처 ID·라이선스).
위도 띠마다 나눠 처리해 메모리를 묶는다. OSM이 없는 풀 행은 osm_evidence에 행이 없고, 판정에서 불이익을 받지 않는다.
"""
import csv
import os

from . import identity, selection
from .rules import literal as _literal
from .rules import load_name_rules, match_sql

_ROLES_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "osm_roles.tsv")
LICENSE = "ODbL-1.0"

SAME_NAME_METERS = 50
CORROBORATED_NAME_METERS = 150
# OSM 면은 꼭짓점 평균이 중심이라 넓은 지형은 같은 이름이면 이만큼까지 같은 장소로 본다.
AREA_NAME_METERS = 500
CONTAINS_METERS = 100
SHARED_CONTACT_METERS = 300
BAND_DEGREES = 2.0
# 띠 경계 너머의 짝을 놓치지 않는 여유(도). LANDFORM_METERS보다 넓다.
BAND_MARGIN = 0.025
_NAME_CELL = 0.005
# 큰 지형은 LANDFORM_METERS를 덮도록 이웃 칸을 더 본다(0.005도 × 5칸 ≥ 2km).
_LANDFORM_CELLS = 5
_CONTAINS_CELL = 0.001
# OSM에서 면으로 그려지는 넓은 장소의 주 태그.
AREA_TAGS = ("leisure=park", "leisure=nature_reserve", "leisure=garden", "natural=*", "tourism=attraction",
             "tourism=theme_park", "tourism=zoo", "historic=*", "amenity=place_of_worship", "boundary=*", "route=*")
# 넓은 면·관계는 그려진 범위(extent_m) 안이면 같은 이름을 같은 장소로 본다. 범위가 아무리 넓어도 이 거리까지만 본다.
EXTENT_MAX_METERS = 30000
_EXTENT_MARGIN = EXTENT_MAX_METERS / 111000
# 산·화산·관광지는 출처마다 대표 좌표(정상·입구·주차장)가 km 단위로 달라 같은 이름이면 이만큼까지 잇는다.
LANDFORM_TAGS = ("natural=peak", "natural=volcano", "natural=valley", "natural=plateau", "tourism=attraction")
LANDFORM_METERS = 2000
SIGHT_ROLES = ("destination", "experience", "general")
_RULE_RANK = ("CASE rule WHEN 'wikidata' THEN 1 WHEN 'name_near' THEN 2 WHEN 'name_corroborated' THEN 3 "
              "WHEN 'contact_name' THEN 4 WHEN 'name_area' THEN 5 WHEN 'name_extent' THEN 6 ELSE 7 END")


def load_osm_roles(path=_ROLES_FILE):
    with open(path, encoding="utf-8") as f:
        rows = list(csv.DictReader((line for line in f if not line.startswith("#")), delimiter="\t"))
    tags = [r["osm_tag"] for r in rows]
    if len(tags) != len(set(tags)):
        raise ValueError("중복 OSM 태그")
    return rows


def build(con, rows="evidence_dedup", candidates="candidates", osm_raw=None, wikidata_raw=None, roles="selection",
          lat_range=(20.0, 46.0)):
    """연결·근거·다국어 이름 표를 만든다. lat_range는 처리할 위도 범위다."""
    _roles_table(con)
    _osm_rows(con, osm_raw)
    _pool_rows(con, rows, candidates, roles)
    con.execute("""CREATE OR REPLACE TABLE osm_pairs (pool_rid BIGINT, osm_rid BIGINT, meters DOUBLE,
                   relation VARCHAR, rule VARCHAR)""")
    lo = lat_range[0]
    while lo < lat_range[1]:
        _band_pairs(con, lo, lo + BAND_DEGREES)
        lo += BAND_DEGREES
    _links(con)
    _evidence(con, wikidata_raw)
    _names(con)


def _roles_table(con):
    """OSM 태그 → 라벨·역할·근거. 역할은 라벨로 roles.tsv에서 찾고, 라벨이 없으면 facility다."""
    selection._roles_table(con)
    values = []
    for r in load_osm_roles():
        tag, _, sub = r["osm_tag"].partition(";")
        sub_key, _, sub_value = sub.partition("=")
        values.append(f"('{_literal(tag)}', {_nullable(sub_key)}, {_nullable(sub_value)}, '{_literal(r['label'])}', "
                      f"'{r['signal']}')")
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE osm_role_map AS
        WITH t AS (SELECT * FROM (VALUES {", ".join(values)}) AS t(osm_tag, sub_key, sub_value, label, signal))
        SELECT t.osm_tag, t.sub_key, t.sub_value, nullif(t.label, '') AS label, t.signal,
               CASE WHEN t.label = '' THEN 'facility'
                    ELSE coalesce(arg_max(m.role, length(m.label_prefix)), 'general') END AS role
        FROM t LEFT JOIN role_map m ON t.label <> '' AND starts_with(t.label, m.label_prefix)
        GROUP BY t.osm_tag, t.sub_key, t.sub_value, t.label, t.signal
    """)


def _nullable(value):
    return f"'{_literal(value)}'" if value else "NULL"


def _osm_rows(con, osm_raw):
    area = " OR ".join(f"o.osm_tag = '{t}'" if not t.endswith("=*") else f"starts_with(o.osm_tag, '{t[:-1]}')"
                       for t in AREA_TAGS)
    landform = ", ".join(f"'{t}'" for t in LANDFORM_TAGS)
    digits = identity.PHONE_DIGITS
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE osm_rows AS
        WITH o AS (
            SELECT row_number() OVER (ORDER BY osm_type, osm_id) AS osm_rid, *,
                   primary_key || '=' || primary_value AS osm_tag
            FROM {osm_raw}
        )
        SELECT o.*, coalesce(sub.role, exact.role, wild.role, 'general') AS osm_role,
               CASE WHEN sub.osm_tag IS NOT NULL THEN sub.label WHEN exact.osm_tag IS NOT NULL THEN exact.label
                    ELSE wild.label END AS osm_label,
               coalesce(sub.signal, exact.signal, wild.signal, 'none') AS osm_signal,
               ({area}) AS area_like, o.osm_tag IN ({landform}) AS landform,
               right(regexp_replace(coalesce(o.phone, o."contact:phone", ''), '[^0-9]', '', 'g'), {digits}) AS phone_tail,
               lower(regexp_extract(coalesce(o.website, o."contact:website", ''), '^[a-zA-Z]+://(?:www\\.)?([^/:?#]+)', 1))
                   AS host,
               list_distinct(list_filter([name_key(o.name), name_key(o.name_ja), name_key(o.name_en),
                                          name_key(o.name_ko)], k -> length(k) >= 2)) AS keys,
               name_key(coalesce(o.name, o.name_ja, o.name_en)) AS main_key
        FROM o
        LEFT JOIN osm_role_map sub ON sub.osm_tag = o.osm_tag AND sub.sub_key IS NOT NULL
             AND o.tags[sub.sub_key] = sub.sub_value
        LEFT JOIN osm_role_map exact ON exact.osm_tag = o.osm_tag AND exact.sub_key IS NULL
        LEFT JOIN osm_role_map wild ON wild.osm_tag = o.primary_key || '=*'
    """)


def _pool_rows(con, rows, candidates, roles):
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE pool_rows AS
        SELECT row_number() OVER (ORDER BY r.source, r.source_id) AS pool_rid, r.source, r.source_id, c.name,
               c.name_key, c.lat, c.lon, c.country, c.phones, r.wd_qid, s.role,
               starts_with(r.pool_category, 'Retail') OR starts_with(r.pool_category, 'Dining and Drinking') AS shop,
               CASE WHEN r.web_kind = 'own' THEN r.web_host END AS own_host,
               list_distinct(list_filter(list_transform(c.names, n -> name_key(n.value)), k -> length(k) >= 2)) AS keys
        FROM {rows} r JOIN {candidates} c USING (source, source_id) JOIN {roles} s USING (source, source_id)
    """)


def _band_pairs(con, lo, hi):
    h = identity._HAVERSINE_M
    rules = load_name_rules()
    sight = f"({_quoted(SIGHT_ROLES)})"
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE band_pool AS SELECT * FROM pool_rows WHERE lat >= {lo} AND lat < {hi}
    """)
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE band_osm AS
        SELECT * FROM osm_rows
        WHERE (lat >= {lo - BAND_MARGIN} AND lat < {hi + BAND_MARGIN})
           OR (extent_m > {LANDFORM_METERS} AND lat >= {lo - _EXTENT_MARGIN} AND lat < {hi + _EXTENT_MARGIN})
    """)
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE band_links AS
        WITH pk AS (SELECT pool_rid, unnest(keys) AS k, floor(lat / {_NAME_CELL})::BIGINT AS cy,
                           floor(lon / {_NAME_CELL})::BIGINT AS cx FROM band_pool),
        ok AS (SELECT osm_rid, unnest(keys) AS k, floor(lat / {_NAME_CELL})::BIGINT + dy AS cy,
                      floor(lon / {_NAME_CELL})::BIGINT + dx AS cx
               FROM band_osm, (SELECT unnest(range(-{_LANDFORM_CELLS}, {_LANDFORM_CELLS} + 1)) AS dy),
                    (SELECT unnest(range(-{_LANDFORM_CELLS}, {_LANDFORM_CELLS} + 1)) AS dx)
               WHERE landform OR (abs(dy) <= 1 AND abs(dx) <= 1)),
        pc AS (SELECT pool_rid, name_key, floor(lat / {_CONTAINS_CELL})::BIGINT AS cy,
                      floor(lon / {_CONTAINS_CELL})::BIGINT AS cx FROM band_pool WHERE length(name_key) >= 2),
        oc AS (SELECT osm_rid, k, floor(lat / {_CONTAINS_CELL})::BIGINT + dy AS cy,
                      floor(lon / {_CONTAINS_CELL})::BIGINT + dx AS cx
               FROM (SELECT osm_rid, lat, lon, unnest(keys) AS k FROM band_osm),
                    (SELECT unnest([-1, 0, 1]) AS dy), (SELECT unnest([-1, 0, 1]) AS dx))
        SELECT DISTINCT pk.pool_rid, ok.osm_rid, 'name' AS kind FROM pk JOIN ok USING (k, cy, cx)
        UNION ALL
        SELECT DISTINCT p.pool_rid, o.osm_rid, 'name'
        FROM (SELECT pool_rid, unnest(keys) AS k FROM band_pool) p
        JOIN (SELECT osm_rid, unnest(keys) AS k FROM band_osm WHERE area_like AND extent_m > {LANDFORM_METERS}) o USING (k)
        UNION ALL
        SELECT DISTINCT p.pool_rid, o.osm_rid, 'qid' FROM band_pool p JOIN band_osm o ON o.wikidata = p.wd_qid
        UNION ALL
        SELECT DISTINCT p.pool_rid, o.osm_rid, 'phone'
        FROM (SELECT pool_rid, unnest(phones) AS phone FROM band_pool) p
        JOIN band_osm o ON length(o.phone_tail) = {identity.PHONE_DIGITS} AND o.phone_tail = p.phone
        UNION ALL
        SELECT DISTINCT p.pool_rid, o.osm_rid, 'host' FROM band_pool p JOIN band_osm o ON o.host <> '' AND o.host = p.own_host
        UNION ALL
        SELECT DISTINCT pc.pool_rid, oc.osm_rid, 'contains' FROM pc JOIN oc USING (cy, cx)
        WHERE pc.name_key <> oc.k AND (contains(pc.name_key, oc.k) OR contains(oc.k, pc.name_key))
    """)
    marker = match_sql("remainder", "country", rules["part_marker"])
    con.execute(f"""
        INSERT INTO osm_pairs
        WITH f AS (
            SELECT pool_rid, osm_rid, bool_or(kind = 'name') AS same_name, bool_or(kind = 'qid') AS same_qid,
                   bool_or(kind = 'phone') AS same_phone, bool_or(kind = 'host') AS same_host,
                   bool_or(kind = 'contains') AS contains_name
            FROM band_links GROUP BY 1, 2
        ),
        c AS (
            SELECT f.*, {h} AS meters, a.country, b.area_like, b.landform, a.shop, least(b.extent_m, {EXTENT_MAX_METERS}) AS extent_m,
                   a.role = b.osm_role OR (a.role IN {sight} AND b.osm_role IN {sight}) AS compatible,
                   CASE WHEN contains(a.name_key, b.main_key) THEN replace(a.name_key, b.main_key, '')
                        WHEN contains(b.main_key, a.name_key) THEN replace(b.main_key, a.name_key, '') END AS remainder
            FROM f JOIN band_pool a USING (pool_rid) JOIN band_osm b USING (osm_rid)
        )
        SELECT pool_rid, osm_rid, meters, split_part(d, ':', 1), split_part(d, ':', 2) FROM (
            SELECT pool_rid, osm_rid, meters,
                   CASE
                     WHEN same_qid AND compatible THEN 'SAME:wikidata'
                     WHEN same_qid THEN 'REVIEW:wikidata_role_conflict'
                     WHEN same_name AND NOT compatible THEN 'DISTINCT:role_conflict'
                     WHEN same_name AND meters <= {SAME_NAME_METERS} THEN 'SAME:name_near'
                     WHEN same_name AND meters <= {CORROBORATED_NAME_METERS} AND (same_phone OR same_host)
                          THEN 'SAME:name_corroborated'
                     WHEN same_name AND area_like AND meters <= {AREA_NAME_METERS} THEN 'SAME:name_area'
                     WHEN same_name AND landform AND compatible AND NOT shop AND meters <= {LANDFORM_METERS}
                          THEN 'SAME:name_landform'
                     WHEN same_name AND area_like AND NOT shop AND meters <= extent_m THEN 'SAME:name_extent'
                     WHEN same_name THEN 'REVIEW:name_apart'
                     WHEN contains_name AND remainder IS NOT NULL AND {marker} THEN 'PART_OF:part_marker'
                     WHEN contains_name AND (same_phone OR same_host) AND compatible
                          AND meters <= {SHARED_CONTACT_METERS} THEN 'SAME:contact_name'
                     WHEN contains_name AND NOT compatible THEN 'DISTINCT:role_conflict'
                     WHEN contains_name THEN 'REVIEW:name_contains'
                     WHEN (same_phone OR same_host) AND meters <= {SHARED_CONTACT_METERS} THEN 'REVIEW:shared_contact'
                   END AS d
            FROM c
            WHERE meters <= CASE WHEN same_qid THEN {identity.WIKIDATA_MAX_METERS}
                                 WHEN same_name THEN greatest({LANDFORM_METERS}, extent_m)
                                 WHEN same_phone OR same_host THEN {SHARED_CONTACT_METERS}
                                 ELSE {CONTAINS_METERS} END)
        WHERE d IS NOT NULL
    """)


def _links(con):
    """풀 행마다 가장 강한 SAME 하나, 그다음 OSM 객체마다 가장 가까운 풀 행 하나."""
    con.execute(f"""
        CREATE OR REPLACE TABLE osm_links AS
        WITH best_pool AS (
            SELECT * FROM osm_pairs WHERE relation = 'SAME'
            QUALIFY row_number() OVER (PARTITION BY pool_rid ORDER BY {_RULE_RANK}, meters, osm_rid) = 1
        )
        SELECT p.source, p.source_id, o.osm_type, o.osm_id, b.rule, round(b.meters) AS meters
        FROM best_pool b JOIN pool_rows p USING (pool_rid) JOIN osm_rows o USING (osm_rid)
        QUALIFY row_number() OVER (PARTITION BY b.osm_rid ORDER BY {_RULE_RANK}, b.meters, b.pool_rid) = 1
    """)


def _evidence(con, wikidata_raw):
    sitelinks, wikidata_join = place_sitelinks(wikidata_raw)
    con.execute(f"""
        CREATE OR REPLACE TABLE osm_evidence AS
        SELECT l.source, l.source_id, l.osm_type, l.osm_id, l.rule AS osm_rule, o.osm_tag, o.osm_signal,
               o.tag_count AS osm_tag_count, o.cuisine AS osm_cuisine, o.opening_hours AS osm_opening_hours,
               coalesce(o.brand, o."brand:wikidata") AS osm_brand, o.wikidata AS osm_wikidata,
               {sitelinks} AS osm_wd_sitelinks, o.heritage AS osm_heritage,
               coalesce(o.website, o."contact:website") AS osm_website, coalesce(o.phone, o."contact:phone") AS osm_phone,
               '{LICENSE}' AS license
        FROM osm_links l JOIN osm_rows o ON o.osm_type = l.osm_type AND o.osm_id = l.osm_id
        {wikidata_join}
    """)


def place_sitelinks(wikidata_raw):
    """OSM 객체 o의 wikidata 항목 언어판 수(식과 JOIN 절). 장소 자신의 항목일 때만 값이 있다.
    이름이 다른 여러 객체가 같은 항목을 달았거나 brand:wikidata와 같으면 운영 주체·브랜드 항목이라 장소 근거로 쓰지 않는다."""
    if not wikidata_raw:
        return "NULL::INTEGER", ""
    return "w.sitelinks", f"""
        LEFT JOIN (
            SELECT q.qid, any_value(q.sitelinks) AS sitelinks FROM {wikidata_raw} q
            SEMI JOIN (SELECT wikidata FROM osm_rows WHERE wikidata IS NOT NULL
                       GROUP BY 1 HAVING count(DISTINCT main_key) = 1
                          AND NOT bool_or(coalesce(wikidata = "brand:wikidata", false))) p ON p.wikidata = q.qid
            GROUP BY 1
        ) w ON w.qid = o.wikidata"""


def _names(con):
    parts = " UNION ALL ".join(
        f"""SELECT l.source, l.source_id, '{lang}' AS lang, o.{col} AS value, 'osm' AS origin,
                   l.osm_type || '/' || l.osm_id AS origin_id, '{LICENSE}' AS license
            FROM osm_links l JOIN osm_rows o ON o.osm_type = l.osm_type AND o.osm_id = l.osm_id
            WHERE o.{col} IS NOT NULL"""
        for lang, col in (("und", "name"), ("ja", "name_ja"), ("en", "name_en"), ("ko", "name_ko")))
    con.execute(f"CREATE OR REPLACE TABLE place_names AS {parts}")


def _quoted(values):
    return ", ".join(f"'{_literal(v)}'" for v in values)
