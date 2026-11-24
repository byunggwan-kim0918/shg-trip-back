"""
풀 안의 같은 장소 판정과 병합. 출처 사이와 같은 출처 안을 함께 본다. 원본 행은 지우지 않는다.
  쌍 관계 : SAME(같은 장소, 자동 병합) PART_OF(한쪽이 다른 쪽의 일부) DISTINCT(다른 장소) REVIEW(판단 보류)
  SAME   : 같은 Wikidata 항목 + 역할 호환, 같은 이름 + 가까움 + 역할 호환, 같은 이름 + 주소·전화·고유 도메인 일치,
           같은 이름 + 넓은 자연 지형, 같은 이름 + 같은 분류 명소, 같은 고유 전화·도메인 + 이름 포함 + 같은 분류
  PART_OF: 긴 이름 = 짧은 이름 + 부속 표지(name_rules part_marker), 또는 짧은 쪽이 몰·역처럼 다른 시설을 품는 분류
  전화·도메인을 함께 쓰는데 분류가 다르면 판단하지 않는다(공원 안 체육관, 한 신직이 맡은 여러 신사, 지역명 붙은 같은 시설).
이름만 같다는 이유로는 합치지 않는다. 연쇄로 커진 묶음(MAX_CLUSTER_SIZE·MAX_CLUSTER_METERS 초과)과
서로 다른 Wikidata 항목이 섞인 묶음은 합치지 않고 REVIEW로 둔다.
결과: dedup_pairs(쌍·관계·근거), place_clusters(묶음·구성원·대표, run_id), place_relations(PART_OF), evidence_dedup.
place_clusters의 한 run_id 행을 지우면 그 병합은 되돌아간다.
"""
import csv
import math
import os
import uuid

from . import identity
from .rules import literal as _literal
from .rules import load_name_rules, match_sql

SAME_NAME_METERS = 50
CORROBORATED_NAME_METERS = 150
LARGE_FEATURE_METERS = 150
SHARED_CONTACT_METERS = 300
CONTAINS_METERS = 100
# 이 전화번호를 쓰는 풀 행이 이보다 많으면 대표번호(백화점·역)라 같은 장소 근거로 쓰지 않는다.
SHARED_PHONE_MAX_ROWS = 3
ADDRESS_MIN_LENGTH = 6
MAX_CLUSTER_SIZE = 10
MAX_CLUSTER_METERS = 500
# 같은 이름 후보를 찾는 격자(도). 위도 0.002도는 약 220m로 같은 이름 거리 상한을 이웃 칸이 덮는다.
_NAME_CELL = 0.002
# 이름 포함 후보를 찾는 격자(도). 위도 0.001도는 약 110m로 CONTAINS_METERS를 이웃 칸이 덮는다.
_CONTAINS_CELL = 0.001

# 좌표가 넓은 면의 한 점이라 같은 이름이면 150m까지 같은 장소로 보는 분류.
LARGE_FEATURE_PREFIXES = ("Landmarks and Outdoors > Park", "Landmarks and Outdoors > Mountain",
                          "Landmarks and Outdoors > Lake", "Landmarks and Outdoors > River",
                          "Landmarks and Outdoors > Beach", "Landmarks and Outdoors > Island",
                          "Landmarks and Outdoors > National Park", "Landmarks and Outdoors > Forest",
                          "Landmarks and Outdoors > Nature Preserve", "Landmarks and Outdoors > Hiking Trail",
                          "Landmarks and Outdoors > Garden", "Landmarks and Outdoors > Botanical Garden")
# 다른 시설을 품는 분류. 이 이름을 품은 다른 분류의 장소는 그 안의 일부다(몰 안 매장, 역 안 매장).
CONTAINER_PREFIXES = ("Retail > Shopping Mall", "Retail > Shopping Plaza", "Retail > Department Store",
                      "Travel and Transportation > Transport Hub", "Travel and Transportation > Lodging",
                      "Arts and Entertainment > Amusement Park", "Arts and Entertainment > Zoo",
                      "Landmarks and Outdoors > Park", "Landmarks and Outdoors > National Park")
# 서로 다른 출처가 같은 명소를 다른 분류(사적·신사·교회)로 싣는 역할 묶음. 식음료·숙박과는 섞지 않는다.
SIGHT_ROLES = ("destination", "experience", "general")
PARENT_ROLES = ("destination", "experience")
_BASIS_RANK = "CASE pool_basis WHEN 'overture' THEN 0 WHEN 'supplemented' THEN 1 WHEN 'fsq_notable' THEN 2 ELSE 3 END"


def build(con, evidence="evidence", candidates="candidates", roles="selection", work_dir="build/tmp"):
    """중복 판정·병합 결과 표를 만든다. 반환은 이번 실행의 run_id."""
    run_id = uuid.uuid4().hex[:12]
    rules = load_name_rules()
    _rows(con, evidence, candidates, roles)
    _pairs(con, rules)
    clusters = _clusters(con)
    _write_clusters(con, clusters, run_id, work_dir)
    _relations(con)
    _merged_evidence(con, evidence)
    return run_id


def _rows(con, evidence, candidates, roles):
    large = " OR ".join(f"starts_with(e.pool_category, '{_literal(p)}')" for p in LARGE_FEATURE_PREFIXES)
    container = " OR ".join(f"starts_with(e.pool_category, '{_literal(p)}')" for p in CONTAINER_PREFIXES)
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE dd_rows AS
        SELECT row_number() OVER (ORDER BY e.source, e.source_id) AS rid, e.source, e.source_id, e.pool_basis,
               e.pool_category, c.name, c.name_key, c.names, c.lat, c.lon, c.country, c.phones,
               CASE WHEN length(name_key(c.address)) >= {ADDRESS_MIN_LENGTH} THEN name_key(c.address) END AS address_key,
               CASE WHEN e.web_kind = 'own' THEN e.web_host END AS own_host, e.wd_qid, s.role,
               {large} AS large_feature, {container} AS container,
               (CASE WHEN e.independent_confirm THEN 4 ELSE 0 END + CASE WHEN e.wd_qid IS NOT NULL THEN 3 ELSE 0 END
                + CASE WHEN e.web_kind = 'own' THEN 1 ELSE 0 END + CASE WHEN e.fsq_basis IS NOT NULL THEN 1 ELSE 0 END)
                   AS info_rank
        FROM {evidence} e JOIN {candidates} c USING (source, source_id)
        JOIN {roles} s USING (source, source_id)
    """)


def _pairs(con, rules):
    """같은 키(이름·전화·Wikidata·도메인)를 가진 행끼리, 이름 포함은 이웃 칸끼리만 잇는다. 근처 전체 쌍은 만들지 않는다."""
    h = identity._HAVERSINE_M
    compatible = (f"(a.role = b.role OR (a.role IN ({_quoted(SIGHT_ROLES)}) AND b.role IN ({_quoted(SIGHT_ROLES)})))")
    name_meters = max(CORROBORATED_NAME_METERS, LARGE_FEATURE_METERS)
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE dd_keys AS
        SELECT DISTINCT rid, name_key(n.value) AS k,
               floor(lat / {_NAME_CELL})::BIGINT AS cy, floor(lon / {_NAME_CELL})::BIGINT AS cx
        FROM dd_rows, unnest(names) AS u(n)
        WHERE length(name_key(n.value)) >= 2
    """)
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE dd_phone_rows AS
        WITH p AS (SELECT DISTINCT rid, phone FROM dd_rows, unnest(phones) AS u(phone))
        SELECT p.* FROM p JOIN (SELECT phone FROM p GROUP BY 1 HAVING count(*) <= {SHARED_PHONE_MAX_ROWS}) USING (phone)
    """)
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE dd_links AS
        SELECT least(x.rid, y.rid) AS a_rid, greatest(x.rid, y.rid) AS b_rid, 'name' AS kind
        FROM (SELECT * REPLACE (cy + dy AS cy, cx + dx AS cx)
              FROM dd_keys, (SELECT unnest([-1, 0, 1]) AS dy), (SELECT unnest([-1, 0, 1]) AS dx)) x
        JOIN dd_keys y ON x.k = y.k AND x.cy = y.cy AND x.cx = y.cx AND x.rid <> y.rid
        UNION ALL
        SELECT least(x.rid, y.rid), greatest(x.rid, y.rid), 'phone'
        FROM dd_phone_rows x JOIN dd_phone_rows y ON x.phone = y.phone AND x.rid < y.rid
        UNION ALL
        SELECT a.rid, b.rid, 'qid' FROM dd_rows a JOIN dd_rows b ON a.wd_qid = b.wd_qid AND a.rid < b.rid
        UNION ALL
        SELECT a.rid, b.rid, 'host' FROM dd_rows a JOIN dd_rows b ON a.own_host = b.own_host AND a.rid < b.rid
        UNION ALL
        SELECT a.rid, b.rid, 'contains'
        FROM (SELECT rid, name_key, floor(lat / {_CONTAINS_CELL})::BIGINT + dy AS cy,
                     floor(lon / {_CONTAINS_CELL})::BIGINT + dx AS cx
              FROM dd_rows, (SELECT unnest([-1, 0, 1]) AS dy), (SELECT unnest([-1, 0, 1]) AS dx)
              WHERE length(name_key) >= 2) a
        JOIN (SELECT rid, name_key, floor(lat / {_CONTAINS_CELL})::BIGINT AS cy,
                     floor(lon / {_CONTAINS_CELL})::BIGINT AS cx
              FROM dd_rows WHERE length(name_key) >= 2) b
          ON a.cy = b.cy AND a.cx = b.cx AND a.rid < b.rid
        WHERE a.name_key <> b.name_key AND (contains(a.name_key, b.name_key) OR contains(b.name_key, a.name_key))
    """)
    con.execute(f"""
        CREATE OR REPLACE TABLE dd_candidates AS
        WITH flags AS (
            SELECT a_rid, b_rid, bool_or(kind = 'name') AS same_name, bool_or(kind = 'phone') AS same_phone,
                   bool_or(kind = 'qid') AS same_qid, bool_or(kind = 'host') AS same_host
            FROM dd_links GROUP BY 1, 2
        )
        SELECT f.*, {h} AS meters, a.name AS a_name, b.name AS b_name,
               a.address_key IS NOT NULL AND b.address_key IS NOT NULL
                   AND (ends_with(a.address_key, b.address_key) OR ends_with(b.address_key, a.address_key)) AS same_address,
               length(a.name_key) >= 2 AND length(b.name_key) >= 2 AND a.name_key <> b.name_key
                   AND (contains(a.name_key, b.name_key) OR contains(b.name_key, a.name_key)) AS contains_name,
               CASE WHEN length(a.name_key) > length(b.name_key) THEN replace(a.name_key, b.name_key, '')
                    ELSE replace(b.name_key, a.name_key, '') END AS remainder,
               CASE WHEN length(a.name_key) > length(b.name_key) THEN a.rid ELSE b.rid END AS longer_rid,
               CASE WHEN length(a.name_key) > length(b.name_key) THEN b.container ELSE a.container END AS shorter_container,
               CASE WHEN length(a.name_key) > length(b.name_key) THEN a.country ELSE b.country END AS longer_country,
               a.pool_category = b.pool_category AS same_category,
               a.large_feature AND b.large_feature AS both_large,
               a.role IN ({_quoted(SIGHT_ROLES)}) AND b.role IN ({_quoted(SIGHT_ROLES)})
                   AND NOT starts_with(a.pool_category, 'Retail') AS both_sight,
               {compatible} AS compatible
        FROM flags f JOIN dd_rows a ON a.rid = f.a_rid JOIN dd_rows b ON b.rid = f.b_rid
        WHERE {h} <= CASE WHEN f.same_qid THEN {identity.WIKIDATA_MAX_METERS}
                          WHEN f.same_phone OR f.same_host THEN {SHARED_CONTACT_METERS}
                          WHEN f.same_name THEN {name_meters}
                          ELSE {CONTAINS_METERS} END
    """)
    marker = match_sql("remainder", "longer_country", rules["part_marker"])
    con.execute(f"""
        CREATE OR REPLACE TABLE dedup_pairs AS
        SELECT a_rid, b_rid, a_name, b_name, round(meters) AS meters,
               CASE
                 WHEN same_qid AND compatible THEN 'SAME:wikidata'
                 WHEN same_qid THEN 'REVIEW:wikidata_role_conflict'
                 WHEN same_name AND NOT compatible THEN 'DISTINCT:role_conflict'
                 WHEN same_name AND meters <= {SAME_NAME_METERS} THEN 'SAME:name_near'
                 WHEN same_name AND meters <= {CORROBORATED_NAME_METERS}
                      AND (same_address OR same_phone OR same_host) THEN 'SAME:name_corroborated'
                 WHEN same_name AND both_large AND meters <= {LARGE_FEATURE_METERS} THEN 'SAME:name_large_feature'
                 WHEN same_name AND same_category AND both_sight AND meters <= {CORROBORATED_NAME_METERS}
                      THEN 'SAME:name_same_sight'
                 WHEN same_name THEN 'REVIEW:name_apart'
                 WHEN contains_name AND {marker} THEN 'PART_OF:part_marker'
                 WHEN (same_phone OR same_host) AND contains_name AND same_category AND meters <= {SHARED_CONTACT_METERS}
                      THEN 'SAME:contact_name'

                 WHEN contains_name AND shorter_container AND NOT same_category THEN 'PART_OF:inside_container'
                 WHEN contains_name AND NOT compatible THEN 'DISTINCT:role_conflict'
                 WHEN contains_name THEN 'REVIEW:name_contains'
                 WHEN same_phone OR same_host THEN 'REVIEW:shared_contact'
               END AS decision,
               CASE WHEN contains_name THEN longer_rid END AS child_rid
        FROM dd_candidates
    """)
    con.execute("""
        CREATE OR REPLACE TABLE dedup_pairs AS
        SELECT * EXCLUDE (decision), split_part(decision, ':', 1) AS relation, split_part(decision, ':', 2) AS rule
        FROM dedup_pairs WHERE decision IS NOT NULL
    """)


def _clusters(con):
    """SAME 쌍의 연결 요소. 너무 크거나 넓은 묶음은 합치지 않는다."""
    rows = {rid: (lat, lon, info, basis, qid)
            for rid, lat, lon, info, basis, qid in con.execute(
                f"SELECT rid, lat, lon, info_rank, {_BASIS_RANK}, wd_qid FROM dd_rows "
                "WHERE rid IN (SELECT a_rid FROM dedup_pairs WHERE relation = 'SAME' "
                "UNION SELECT b_rid FROM dedup_pairs WHERE relation = 'SAME')").fetchall()}
    parent = {rid: rid for rid in rows}

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for a, b in con.execute("SELECT a_rid, b_rid FROM dedup_pairs WHERE relation = 'SAME'").fetchall():
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[max(ra, rb)] = min(ra, rb)
    groups = {}
    for rid in rows:
        groups.setdefault(find(rid), []).append(rid)
    clusters = []
    for members in groups.values():
        if len(members) < 2:
            continue
        lats = [rows[m][0] for m in members]
        lons = [rows[m][1] for m in members]
        span = _meters(min(lats), min(lons), max(lats), max(lons))
        if len(members) > MAX_CLUSTER_SIZE or span > MAX_CLUSTER_METERS:
            clusters.append((members, None, "too_large"))
            continue
        if len({rows[m][4] for m in members if rows[m][4]}) > 1:
            clusters.append((members, None, "conflicting_ids"))
            continue
        representative = min(members, key=lambda m: (-rows[m][2], rows[m][3], m))
        clusters.append((members, representative, "merged"))
    return clusters


def _write_clusters(con, clusters, run_id, work_dir):
    os.makedirs(work_dir, exist_ok=True)
    path = os.path.join(work_dir, f"clusters-{run_id}.csv")
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["cluster_id", "rid", "is_representative", "status"])
        for n, (members, representative, status) in enumerate(clusters, 1):
            for m in members:
                w.writerow([n, m, m == representative, status])
    con.execute(f"""
        CREATE OR REPLACE TABLE place_clusters AS
        SELECT '{run_id}' AS run_id, k.cluster_id, r.source, r.source_id, k.is_representative, k.status
        FROM read_csv('{_literal(path)}', header = true,
                      columns = {{'cluster_id': 'BIGINT', 'rid': 'BIGINT', 'is_representative': 'BOOLEAN',
                                  'status': 'VARCHAR'}}) k
        JOIN dd_rows r ON r.rid = k.rid
    """)
    os.remove(path)
    con.execute("""
        UPDATE dedup_pairs SET relation = 'REVIEW', rule = 'cluster_' || c.status
        FROM (SELECT r.rid, c.status FROM place_clusters c JOIN dd_rows r USING (source, source_id)
              WHERE c.status <> 'merged') c
        WHERE dedup_pairs.relation = 'SAME' AND dedup_pairs.a_rid = c.rid
    """)


def _relations(con):
    con.execute("""
        CREATE OR REPLACE TABLE place_relations AS
        SELECT c.source AS child_source, c.source_id AS child_id, p.source AS parent_source, p.source_id AS parent_id,
               p.role AS parent_role, d.rule, d.meters
        FROM dedup_pairs d
        JOIN dd_rows c ON c.rid = d.child_rid
        JOIN dd_rows p ON p.rid = CASE WHEN d.child_rid = d.a_rid THEN d.b_rid ELSE d.a_rid END
        WHERE d.relation = 'PART_OF'
    """)


def _merged_evidence(con, evidence):
    """병합된 묶음은 대표 행 하나로 남기고 구성원의 근거를 모은다. 묶이지 않은 행은 그대로 둔다."""
    # 자식을 상황 활용으로 낮추는 근거라서, 부모가 목적지·경험일 때만 쓴다(교회 분류로 잡힌 신사 본체 아래 拝殿 등).
    # 부모가 다른 묶음에 합쳐졌으면 그 묶음의 대표 행을 부모로 본다.
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE parents AS
        WITH resolved AS (
            SELECT r.child_source, r.child_id, coalesce(rep.source_id, r.parent_id) AS parent_id
            FROM place_relations r
            LEFT JOIN place_clusters m ON m.status = 'merged' AND m.source = r.parent_source AND m.source_id = r.parent_id
            LEFT JOIN place_clusters rep ON rep.cluster_id = m.cluster_id AND rep.is_representative
            WHERE r.parent_role IN ({_quoted(PARENT_ROLES)})
        )
        SELECT child_source, child_id, any_value(parent_id) AS parent_id FROM resolved GROUP BY 1, 2
    """)
    con.execute(f"""
        CREATE OR REPLACE TABLE evidence_dedup AS
        WITH members AS (
            SELECT c.cluster_id, e.* FROM place_clusters c JOIN {evidence} e USING (source, source_id)
            WHERE c.status = 'merged'
        ),
        merged AS (
            SELECT cluster_id, count(*) AS cluster_size,
                   bool_or(independent_confirm) AS independent_confirm,
                   arg_max(wd_qid, coalesce(wd_sitelinks, -1)) AS wd_qid, max(wd_sitelinks) AS wd_sitelinks,
                   arg_max(wd_name_ko, coalesce(wd_sitelinks, -1)) AS wd_name_ko, bool_or(wd_kowiki) AS wd_kowiki,
                   min(fsq_created) AS fsq_created, max(fsq_refreshed) AS fsq_refreshed,
                   bool_and(fsq_closed) FILTER (WHERE fsq_basis IS NOT NULL) AS fsq_closed,
                   bool_or(fsq_flagged) AS fsq_flagged,
                   bool_or(web_wiki) AS web_wiki, bool_or(web_kind = 'own') AS any_own_web
            FROM members GROUP BY 1
        ),
        representative AS (
            SELECT c.cluster_id, e.* FROM place_clusters c JOIN {evidence} e USING (source, source_id)
            WHERE c.status = 'merged' AND c.is_representative
        )
        SELECT r.* EXCLUDE (cluster_id) REPLACE (
                   m.independent_confirm AS independent_confirm, m.wd_qid AS wd_qid, m.wd_sitelinks AS wd_sitelinks,
                   m.wd_name_ko AS wd_name_ko, m.wd_kowiki AS wd_kowiki, m.fsq_created AS fsq_created,
                   m.fsq_refreshed AS fsq_refreshed, coalesce(m.fsq_closed, r.fsq_closed) AS fsq_closed,
                   m.fsq_flagged AS fsq_flagged, m.web_wiki AS web_wiki,
                   CASE WHEN m.any_own_web AND r.web_kind <> 'own' THEN 'own' ELSE r.web_kind END AS web_kind),
               m.cluster_size, p.parent_id AS part_of
        FROM representative r JOIN merged m USING (cluster_id)
        LEFT JOIN parents p
               ON p.child_source = r.source AND p.child_id = r.source_id
        UNION ALL BY NAME
        SELECT e.*, 1 AS cluster_size, p.parent_id AS part_of
        FROM {evidence} e
        LEFT JOIN parents p
               ON p.child_source = e.source AND p.child_id = e.source_id
        WHERE NOT EXISTS (SELECT 1 FROM place_clusters c WHERE c.status = 'merged'
                          AND c.source = e.source AND c.source_id = e.source_id)
    """)


def _quoted(values):
    return ", ".join(f"'{_literal(v)}'" for v in values)


def _meters(lat0, lon0, lat1, lon1):
    dlat = math.radians(lat1 - lat0)
    dlon = math.radians(lon1 - lon0)
    a = math.sin(dlat / 2) ** 2 + math.cos(math.radians(lat0)) * math.cos(math.radians(lat1)) * math.sin(dlon / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(a))
