"""
공급처 원본 스냅샷 → 공통 후보(L1) → 적재 적합성 판정(L3).
공통 후보 한 행은 공급처의 한 장소이고, 같은 장소 묶기는 identity.py가 한다.
"""
import re

import duckdb
from duckdb.sqltypes import VARCHAR

from . import policy
from .categories import load_overture_map, overture_label
from .names import exact_key

COMMON_COLUMNS = ("source, source_id, release, name, name_key, names, lat, lon, country, address, locality, "
                  "category_raw, category, refreshed_at, closed, flagged, brand, links, license")


def register_functions(con, mapping=None):
    """이름 키·카테고리 매핑 함수를 연결에 등록한다. 이미 등록된 연결에서는 아무것도 하지 않는다."""
    if con.execute("SELECT count(*) FROM duckdb_functions() WHERE function_name = 'name_key'").fetchone()[0]:
        return
    mapping = mapping if mapping is not None else load_overture_map()
    con.create_function("name_key", exact_key, [VARCHAR], VARCHAR)

    def label(hierarchy, primary):
        found, _ = overture_label(hierarchy, primary, mapping)
        return found

    con.create_function("overture_label", label, [duckdb.list_type(VARCHAR), VARCHAR], VARCHAR,
                        null_handling="special")


def overture_sql(raw, release):
    return f"""
    SELECT 'overture' AS source, id AS source_id, '{_literal(release)}' AS release,
           names."primary" AS name, name_key(names."primary") AS name_key,
           list_concat(
             [struct_pack(value := names."primary", lang := 'und', kind := 'primary')],
             coalesce(list_transform(map_entries(names.common),
                      e -> struct_pack(value := e.value, lang := e.key, kind := 'common')), []),
             coalesce(list_transform(names.rules,
                      r -> struct_pack(value := r.value, lang := coalesce(r.language, 'und'),
                                       kind := coalesce(r.variant, 'rule'))), [])) AS names,
           bbox.ymin AS lat, bbox.xmin AS lon,
           addresses[1].country AS country, addresses[1].freeform AS address, addresses[1].locality AS locality,
           coalesce(taxonomy."primary", basic_category) AS category_raw,
           overture_label(taxonomy.hierarchy, taxonomy."primary") AS category,
           CAST(list_max(list_transform(sources, s -> try_cast(s.update_time AS TIMESTAMP))) AS DATE) AS refreshed_at,
           coalesce(operating_status = 'permanently_closed', false) AS closed,
           false AS flagged,
           brand.names."primary" AS brand,
           coalesce(list_transform(list_filter(sources, s -> s.dataset = 'Foursquare' AND s.record_id IS NOT NULL),
                                   s -> struct_pack(source := 'fsq', id := s.record_id)), []) AS links,
           array_to_string(list_sort(list_distinct(list_transform(sources, s -> s.license))), ';') AS license
    FROM {raw}
    """


def fsq_sql(raw, release):
    return f"""
    SELECT 'fsq' AS source, fsq_place_id AS source_id, '{_literal(release)}' AS release,
           name, name_key(name) AS name_key,
           [struct_pack(value := name, lang := 'und', kind := 'primary')] AS names,
           CAST(latitude AS DOUBLE) AS lat, CAST(longitude AS DOUBLE) AS lon,
           country, address, locality,
           fsq_category_labels[1] AS category_raw, fsq_category_labels[1] AS category,
           try_cast(date_refreshed AS DATE) AS refreshed_at,
           date_closed IS NOT NULL AS closed,
           coalesce(len(unresolved_flags), 0) > 0 AS flagged,
           CAST(NULL AS VARCHAR) AS brand,
           CAST([] AS STRUCT(source VARCHAR, id VARCHAR)[]) AS links,
           'Apache-2.0' AS license
    FROM {raw}
    """


def exclusion_sql():
    """행마다 첫 제외 사유(없으면 NULL). 순서는 policy.EXCLUSION_ORDER와 같다."""
    non_travel = "|".join(re.escape(k) for k in policy.NON_TRAVEL_LABEL_KEYWORDS)
    lasting = " OR ".join(f"starts_with(category, '{_literal(p)}')" for p in policy.LASTING_LABEL_PREFIXES)
    tourist = " OR ".join(f"starts_with(lower(name), '{_literal(b)}')" for b in policy.TOURIST_CHAINS)
    return f"""
    CASE
      WHEN coalesce(name_key, '') = '' THEN 'no_name'
      WHEN lat IS NULL OR lon IS NULL OR lat NOT BETWEEN -90 AND 90 OR lon NOT BETWEEN -180 AND 180
           OR (lat = 0 AND lon = 0) THEN 'bad_coordinate'
      WHEN closed THEN 'closed'
      WHEN flagged THEN 'flagged'
      WHEN category IS NULL THEN 'unmapped_category'
      WHEN category = '' OR regexp_matches(lower(category), '{_literal(non_travel)}') THEN 'non_travel_category'
      WHEN refreshed_at < current_date - to_years(CASE WHEN {lasting} THEN {policy.FRESH_YEARS_LASTING}
                                                       ELSE {policy.FRESH_YEARS_DEFAULT} END) THEN 'stale'
      WHEN NOT starts_with(category, 'Dining and Drinking')
           AND regexp_matches(name, '{_literal(policy.JUNK_NAME_PATTERN)}') THEN 'junk_name'
      WHEN starts_with(category, 'Dining and Drinking') AND brand IS NOT NULL AND NOT ({tourist}) THEN 'chain'
    END
    """


def build(con, overture_raw=None, overture_release=None, fsq_raw=None, fsq_release=None, table="candidates"):
    """원본 테이블(또는 Parquet 경로)을 받아 공통 후보 테이블을 만든다. 공급처는 하나만 있어도 된다."""
    parts = []
    if overture_raw:
        parts.append(overture_sql(overture_raw, overture_release))
    if fsq_raw:
        parts.append(fsq_sql(fsq_raw, fsq_release))
    if not parts:
        raise ValueError("원본이 하나도 없다")
    union = " UNION ALL BY NAME ".join(f"({p})" for p in parts)
    con.execute(f"""
        CREATE OR REPLACE TABLE {table} AS
        SELECT {COMMON_COLUMNS}, {exclusion_sql()} AS exclusion
        FROM ({union})
    """)
    return table


def _literal(value):
    return str(value).replace("'", "''")
