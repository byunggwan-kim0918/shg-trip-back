"""
공급처 사이 같은 장소 후보(L2). Overture 행(a)과 FSQ 행(b)을 적재 적합 여부와 상관없이 잇는다.
FSQ의 폐업 기록, Overture에서 분류 때문에 빠진 행도 같은 장소인지 알아야 "한쪽에만 있음"을 셀 수 있다.
  source_id  : Overture 행이 FSQ 원본 ID를 출처로 들고 있다(같은 레코드). AUTO.
  exact_name : 정확 키가 같고 EXACT_NAME_MAX_METERS 안이다. 대분류까지 같을 때만 AUTO, 아니면 REVIEW.
  phone_name : 양쪽에서 한 곳만 쓰는 전화번호가 같고 PHONE_MAX_METERS 안이며 한쪽 키가 다른 쪽을 품는다. AUTO.
  name_contains : 양쪽 다 식음료이고 한쪽 키가 다른 쪽을 품으며 CONTAINS_MAX_METERS 안이고 그 FSQ 행의 후보가 하나뿐이다.
                  AUTO. 다른 분류는 몰·역·사적과 그 안의 매장·출구·부속 건물이 이름을 나눠 가져서 만들지 않는다.
                  짧은 쪽이 일반명사(name_rules generic)거나 짧은 영문이면 만들지 않는다.
  phone      : 고유 전화번호가 같고 PHONE_REVIEW_MAX_METERS 안이지만 이름이 다르다(표기 차이·업종 변경). REVIEW.
한 쌍에는 rank가 가장 앞선 근거 하나만 남긴다. 표기 변형·번역 이름으로 찾는 쌍은 만들지 않는다(배치 PlaceLinkJob 몫).
"""
from . import policy
from .rules import literal as _literal
from .rules import load_name_rules, match_sql

# 같은 이름 두 출처의 좌표 차이 상한. 같은 건물 안 다른 층 매장까지 붙지 않을 만큼 좁게 둔다.
EXACT_NAME_MAX_METERS = 50
# 고유 전화번호는 이름보다 강한 근거라 공급처 좌표 오차(지번 대표점·건물 중심)만큼 넓힌다.
PHONE_MAX_METERS = 1000
# 이름이 다른 고유 번호 쌍은 같은 자리의 상호 변경이 섞여서 가까운 것만 검토로 남긴다.
PHONE_REVIEW_MAX_METERS = 300
# 국가 번호·시외 번호 표기와 상관없이 비교하는 끝자리 수. 일본 유선 번호 0을 뗀 길이다.
PHONE_DIGITS = 9
# 이름 포함 관계는 전화번호보다 약한 근거라 정확 키와 같은 거리만 본다.
CONTAINS_MAX_METERS = EXACT_NAME_MAX_METERS
# 영문·숫자 키는 짧으면 다른 상호 안에 우연히 들어간다("re" ⊂ "booncoonrecords").
CONTAINS_MIN_LATIN_LENGTH = 4
# FSQ 단독 행을 중복 가능으로 보류하는 거리. 공급처 좌표 오차를 넉넉히 본다. 격자 이웃 2칸(110m 이상)이 덮는다.
DUPLICATE_MAX_METERS = 100
# Wikidata 항목과 같은 이름 장소를 잇는 거리. 운영 배치(WikidataSitelinksJob)와 같은 반경이고 격자 이웃 1칸이 덮는다.
WIKIDATA_MAX_METERS = 2000
# 이름을 품은 행으로 잇는 조건. 짧은 항목 이름(地名 두 글자)은 가게·지점 이름에 흔해 잇지 않는다.
CONTAINED_MAX_METERS = 150
CONTAINED_MIN_CHARS = 3
CONTAINED_MIN_SITELINKS = 3
# 명소 이름을 딴 가게(新宿御苑 앞 식당)가 많은 분류. 같은 항목에 이름이 정확히 같은 명소 행이 있으면 그쪽에 잇는다.
NAMESAKE_STORE_CATEGORIES = ("Dining and Drinking", "Retail")
# 몰·백화점·시장은 이름을 딴 가게가 아니라 그 이름의 장소 자체다.
NAMESAKE_STORE_EXCEPTIONS = ("Retail > Shopping Mall", "Retail > Shopping Plaza", "Retail > Department Store",
                             "Retail > Market")
CONTAINED_IGNORED_CATEGORIES = ("Dining and Drinking", "Retail", "Business and Professional Services",
                                "Travel and Transportation")
_WIKI_CELL = 0.03
# CONTAINED_MAX_METERS를 이웃 칸까지 덮는 칸 크기(도).
_CONTAINED_CELL = 0.002
# 이름 포함·중복 후보를 찾는 격자(도). 위도 0.0005도는 약 55m다.
_CELL_LAT = 0.0005
_CELL_LON = 0.0007

_HAVERSINE_M = ("2 * 6371000 * asin(sqrt(pow(sin(radians(b.lat - a.lat) / 2), 2) "
                "+ cos(radians(a.lat)) * cos(radians(b.lat)) * pow(sin(radians(b.lon - a.lon) / 2), 2)))")

def _unknown(alias):
    """분류를 모르는 행(매핑 없음·검토 분류). 모르는 분류는 충돌이 아니라서 이름이 같으면 잇는다."""
    return f"({alias}.category IS NULL OR {alias}.category = '{policy.REVIEW_LABEL}')"


_LINKABLE = "coalesce(exclusion, '') NOT IN ('no_name', 'bad_coordinate')"


def build_links(con, candidates="candidates", table="source_links"):
    """열: a_source, a_id, b_source, b_id, basis, decision, meters. a는 overture, b는 fsq다."""
    generic = load_name_rules()["generic"]
    contains = "(contains(a.name_key, b.name_key) OR contains(b.name_key, a.name_key))"
    shorter = "CASE WHEN length(a.name_key) <= length(b.name_key) THEN a.name_key ELSE b.name_key END"
    con.execute(f"""
        CREATE OR REPLACE TABLE {table} AS
        WITH a_rows AS (SELECT * FROM {candidates} WHERE source = 'overture' AND {_LINKABLE}),
        b_rows AS (SELECT * FROM {candidates} WHERE source = 'fsq' AND {_LINKABLE}),
        a_phones AS (SELECT DISTINCT source_id, phone FROM a_rows, unnest(phones) AS p(phone)),
        b_phones AS (SELECT DISTINCT source_id, phone FROM b_rows, unnest(phones) AS p(phone)),
        a_unique AS (SELECT phone, any_value(source_id) AS source_id FROM a_phones GROUP BY 1 HAVING count(*) = 1),
        b_unique AS (SELECT phone, any_value(source_id) AS source_id FROM b_phones GROUP BY 1 HAVING count(*) = 1),
        by_id AS (
            SELECT a.source_id AS a_id, b.source_id AS b_id, 1 AS rank, 'source_id' AS basis, 'AUTO' AS decision,
                   {_HAVERSINE_M} AS meters
            FROM a_rows a, unnest(a.links) AS l(link) JOIN b_rows b ON b.source_id = link.id
        ),
        by_name AS (
            SELECT a.source_id, b.source_id, 2, 'exact_name',
                   CASE WHEN split_part(a.category, ' > ', 1) = split_part(b.category, ' > ', 1)
                             OR {_unknown('a')} OR {_unknown('b')} THEN 'AUTO' ELSE 'REVIEW' END,
                   {_HAVERSINE_M}
            FROM a_rows a JOIN b_rows b ON a.name_key = b.name_key
            WHERE abs(a.lat - b.lat) < 0.001 AND abs(a.lon - b.lon) < 0.0015
              AND {_HAVERSINE_M} <= {EXACT_NAME_MAX_METERS}
        ),
        by_phone AS (
            SELECT a.source_id, b.source_id,
                   CASE WHEN contains(a.name_key, b.name_key) OR contains(b.name_key, a.name_key) THEN 3 ELSE 4 END,
                   CASE WHEN contains(a.name_key, b.name_key) OR contains(b.name_key, a.name_key)
                        THEN 'phone_name' ELSE 'phone' END,
                   CASE WHEN contains(a.name_key, b.name_key) OR contains(b.name_key, a.name_key)
                        THEN 'AUTO' ELSE 'REVIEW' END,
                   {_HAVERSINE_M} AS meters
            FROM a_unique ua JOIN b_unique ub USING (phone)
            JOIN a_rows a ON a.source_id = ua.source_id JOIN b_rows b ON b.source_id = ub.source_id
            WHERE length(a.name_key) >= 2 AND length(b.name_key) >= 2
              AND {_HAVERSINE_M} <= CASE WHEN contains(a.name_key, b.name_key) OR contains(b.name_key, a.name_key)
                                         THEN {PHONE_MAX_METERS} ELSE {PHONE_REVIEW_MAX_METERS} END
        ),
        a_cells AS (
            SELECT a.*, floor(a.lat / {_CELL_LAT})::BIGINT + dy AS cy, floor(a.lon / {_CELL_LON})::BIGINT + dx AS cx
            FROM a_rows a, (SELECT unnest([-1, 0, 1]) AS dy), (SELECT unnest([-1, 0, 1]) AS dx)
        ),
        b_cells AS (SELECT b.*, floor(b.lat / {_CELL_LAT})::BIGINT AS cy, floor(b.lon / {_CELL_LON})::BIGINT AS cx
                    FROM b_rows b),
        by_contains AS (
            SELECT a.source_id, b.source_id, 4, 'name_contains', 'AUTO', {_HAVERSINE_M} AS meters
            FROM a_cells a JOIN b_cells b USING (cy, cx)
            WHERE a.name_key <> b.name_key AND {contains}
              AND starts_with(a.category, 'Dining and Drinking') AND starts_with(b.category, 'Dining and Drinking')
              AND length({shorter}) >= CASE WHEN regexp_matches({shorter}, '^[a-z0-9]+$')
                                            THEN {CONTAINS_MIN_LATIN_LENGTH} ELSE 2 END
              AND NOT {match_sql(shorter, 'a.country', generic)}
              AND {_HAVERSINE_M} <= {CONTAINS_MAX_METERS}
            QUALIFY count(*) OVER (PARTITION BY b.source_id) = 1
        ),
        pairs AS (SELECT * FROM by_id UNION ALL SELECT * FROM by_name UNION ALL SELECT * FROM by_phone
                  UNION ALL SELECT * FROM by_contains)
        SELECT 'overture' AS a_source, a_id, 'fsq' AS b_source, b_id, basis, decision, meters
        FROM pairs QUALIFY row_number() OVER (PARTITION BY a_id, b_id ORDER BY rank) = 1
    """)
    return table


# 괄호 안·" / " 뒤는 다른 표기(로마자·정식 이름)거나 위치 설명이라 본 이름과 따로 비교한다.
_ALIAS = "[(（\\[［【「]([^)）\\]］】」]*)[)）\\]］】」]"
_ALIAS_SPLIT = "\\s+/\\s+"
# 공급처마다 빵집·과자점·식품점을 식음료와 소매로 엇갈려 분류한다.
SHOP_MAJORS = ("Dining and Drinking", "Retail")
# 짧은 이름이 긴 이름의 철자 변형(글자 빠짐)으로 보려면 긴 이름 글자의 이만큼은 짧은 이름에 있어야 한다.
VARIANT_MIN_RATIO = 0.7
# 한 FSQ 행의 이름을 품거나 거기 들어가는 Overture 이웃이 이보다 많으면 그 이름은 몰·역·지명이다.
CONTAINS_MAX_PARTNERS = 2


def _latin_fold(k):
    return f"(CASE WHEN regexp_matches(strip_accents({k}), '^[a-z0-9]+$') THEN strip_accents({k}) ELSE {k} END)"


def _name_keys(table, rows):
    """행마다 본 이름 키(is_core)와 별칭 키. 키가 같으면 하나만 남긴다."""
    bare = f"regexp_split_to_array(regexp_replace(name, '{_literal(_ALIAS)}', ' ', 'g'), '{_ALIAS_SPLIT}')"
    core = f"name_key({bare}[1])"
    return f"""
        {table} AS (
            SELECT * FROM (
                SELECT source_id, lat, lon, category, country, name,
                       {_latin_fold(f"CASE WHEN length({core}) >= 2 THEN {core} ELSE name_key END")} AS k, true AS is_core
                FROM {rows}
                UNION ALL
                SELECT source_id, lat, lon, category, country, name, {_latin_fold('name_key(x)')}, false
                FROM {rows}, unnest(regexp_extract_all(name, '{_literal(_ALIAS)}', 1) || {bare}[2:]) AS t(x)
            ) WHERE length(k) >= 2
            QUALIFY row_number() OVER (PARTITION BY source_id, k ORDER BY is_core DESC) = 1
        )"""


def fsq_only(con, candidates="candidates", links="source_links", table="fsq_only"):
    """
    Overture와 이어지지 않은 FSQ 적재 적합 행과 상태. 열: source_id, status.
      only               : 어떤 연결도 없고 가까이 같은 곳으로 볼 Overture 행도 없다. 후보로 쓴다.
      review             : REVIEW 연결만 있다. 같은 장소일 수 있어 쓰지 않는다.
      possible_duplicate : DUPLICATE_MAX_METERS 안 Overture 행과 이름 키가 같거나, 분류가 맞고 한쪽 이름이 다른 쪽의 앞·뒤에
                           붙은 이름(나머지가 부속 표지·지점명이 아님)이거나 글자가 빠진 변형이다. 포함하는 이웃이
                           CONTAINS_MAX_PARTNERS보다 많으면 그 이름은 몰·역·지명이라 중복으로 보지 않는다. 쓰지 않는다.
    앞 세 글자만 보면 같은 브랜드의 다른 건물(北一硝子 三号館·見学硝子工房)을 같은 곳으로 지운다.
    """
    rules = load_name_rules()
    short = "(CASE WHEN length(a.k) <= length(b.k) THEN a.k ELSE b.k END)"
    long = "(CASE WHEN length(a.k) <= length(b.k) THEN b.k ELSE a.k END)"
    rest = (f"(CASE WHEN starts_with({long}, {short}) THEN substr({long}, length({short}) + 1) "
            f"ELSE left({long}, length({long}) - length({short})) END)")
    branch = " OR ".join(
        f"({short} = name_key(regexp_extract({n}, '{_literal(p)}'))"
        + ("" if region == "*" else f" AND b.country = '{_literal(region)}'") + ")"
        for n in ("a.name", "b.name") for region, p in rules["branch_suffix"].items()) or "false"
    am, bm = "split_part(coalesce(a.category, ''), ' > ', 1)", "split_part(coalesce(b.category, ''), ' > ', 1)"
    unknown = f"('', '{policy.REVIEW_LABEL}')"
    shop = ", ".join(f"'{m}'" for m in SHOP_MAJORS)
    compatible = f"({am} = {bm} OR {am} IN {unknown} OR {bm} IN {unknown} OR ({am} IN ({shop}) AND {bm} IN ({shop})))"
    generic = match_sql(short, "b.country", rules["generic"])
    contained = (f"(starts_with({long}, {short}) OR ends_with({long}, {short})) "
                 f"AND length({short}) >= CASE WHEN regexp_matches({short}, '^[a-z0-9]+$') "
                 f"THEN {CONTAINS_MIN_LATIN_LENGTH} ELSE 2 END "
                 f"AND NOT {generic} AND NOT {match_sql(rest, 'b.country', rules['part_marker'])} AND NOT coalesce({branch}, false)")
    variant = (f"a.is_core AND b.is_core AND left(a.k, 3) = left(b.k, 3) AND length({short}) >= 3 AND NOT {generic} "
               f"AND length({short}) >= {VARIANT_MIN_RATIO} * length({long}) "
               f"AND regexp_matches({long}, array_to_string(string_split({short}, ''), '.*'))")
    con.execute(f"""
        CREATE OR REPLACE TABLE {table} AS
        WITH fsq AS (
            SELECT f.* FROM {candidates} f
            WHERE f.source = 'fsq' AND f.exclusion IS NULL
              AND NOT EXISTS (SELECT 1 FROM {links} l WHERE l.b_id = f.source_id AND l.decision = 'AUTO')
        ),
        reviewed AS (SELECT DISTINCT b_id FROM {links} WHERE decision = 'REVIEW'),
        ov AS (SELECT * FROM {candidates} c WHERE source = 'overture' AND {_LINKABLE} AND length(name_key) >= 2),
        {_name_keys('ov_keys', 'ov')},
        {_name_keys('fsq_keys', 'fsq')},
        a_cells AS (SELECT *, floor(lat / {_CELL_LAT})::BIGINT AS cy, floor(lon / {_CELL_LON})::BIGINT AS cx FROM ov_keys),
        b_cells AS (
            SELECT k.*, floor(lat / {_CELL_LAT})::BIGINT + dy AS cy, floor(lon / {_CELL_LON})::BIGINT + dx AS cx
            FROM fsq_keys k, (SELECT unnest([-2, -1, 0, 1, 2]) AS dy), (SELECT unnest([-2, -1, 0, 1, 2]) AS dx)
        ),
        -- 같음·앞뒤 포함·앞 세 글자가 같은 변형은 모두 앞 두 글자나 끝 두 글자가 같다.
        near AS (
            SELECT b.source_id AS b_id, a.source_id AS a_id, a.k = b.k AND a.is_core AND b.is_core AS same,
                   (a.k = b.k AND a.is_core AND b.is_core)
                   OR ({compatible} AND (a.k = b.k OR ({contained}) OR ({variant}))) AS hit
            FROM b_cells b JOIN a_cells a ON a.cy = b.cy AND a.cx = b.cx
             AND (left(a.k, 2) = left(b.k, 2) OR right(a.k, 2) = right(b.k, 2)) AND (a.is_core OR b.is_core)
            WHERE {_HAVERSINE_M} <= {DUPLICATE_MAX_METERS}
        ),
        hits AS (SELECT DISTINCT b_id, a_id, same FROM near WHERE hit),
        duplicate AS (
            SELECT b_id FROM hits
            QUALIFY bool_or(same) OVER (PARTITION BY b_id)
                 OR (count(DISTINCT a_id) OVER (PARTITION BY b_id) <= {CONTAINS_MAX_PARTNERS})
        )
        SELECT fsq.source_id,
               CASE WHEN fsq.source_id IN (SELECT b_id FROM reviewed) THEN 'review'
                    WHEN fsq.source_id IN (SELECT b_id FROM duplicate) THEN 'possible_duplicate'
                    ELSE 'only' END AS status
        FROM fsq
    """)
    return table


def wikidata_links(con, rows, candidates, wikidata_raw, table="wikidata_links", area_items=None):
    """
    rows(키: source, source_id)마다 Wikidata 항목 하나. 열: source, source_id, qid, meters, rule.
      exact    : 이름 정확 키(악센트 접음)가 같은 가장 가까운 항목. 같은 이름의 신사·사찰이 흔해 가까운 것 하나만 남긴다.
                 괄호 병기를 뗀 이름도 본다(鹿苑寺 (金閣寺) → 鹿苑寺). 같은 항목에 명소 행이 이어지면 이름을 딴 가게·식당 행은 잇지 않는다.
      contains : 정확 키로 이어진 행이 없는 항목은 CONTAINED_MAX_METERS 안에서 이름에 항목 이름을 품은 가장 가까운 행 하나에 잇는다.
                 정식 이름이 길게 붙은 명소(浅草観音金龍山浅草寺)를 잡고, 지명을 단 가게·식당과 부속 표지(中神門)는 잇지 않는다.
                 이름이 항목 이름으로 끝나는 행을 먼저 고른다. 끝 낱말이 장소 종류라 京都タワー神社는 타워가 아니라 그 안의 신사다.
                 area_items(열 qid)에 든 지명 항목(市·町)은 시설 이름에 흔히 들어가서 포함 연결에 쓰지 않는다. 없으면 포함 연결을 하지 않는다.
    """
    areas = f"AND qid NOT IN (SELECT qid FROM {area_items})" if area_items else "AND false"
    shops = " OR ".join(f"starts_with(c.category, '{p}')" for p in CONTAINED_IGNORED_CATEGORIES)
    stores = " OR ".join(f"starts_with(c.category, '{p}')" for p in NAMESAKE_STORE_CATEGORIES)
    stores = f"({stores}) AND NOT (" + " OR ".join(f"starts_with(c.category, '{p}')" for p in NAMESAKE_STORE_EXCEPTIONS) + ")"
    part = match_sql("replace(a.k, b.k, '')", "a.country", load_name_rules()["part_marker"])
    con.execute(f"""
        CREATE OR REPLACE TABLE {table} AS
        WITH w AS (
            SELECT qid, lat, lon, sitelinks, strip_accents(name_key(label)) AS k,
                   floor(lat / {_WIKI_CELL})::BIGINT AS cy, floor(lon / {_WIKI_CELL})::BIGINT AS cx
            FROM {wikidata_raw}, unnest([ja::VARCHAR, en::VARCHAR, ko::VARCHAR]) AS l(label)
            WHERE label IS NOT NULL
        ),
        names AS (
            SELECT DISTINCT r.source, r.source_id, c.lat, c.lon, c.country, strip_accents(name_key(v.value)) AS k,
                   coalesce({shops}, false) AS shop, coalesce({stores}, false) AS store,
                   floor(c.lat / {_WIKI_CELL})::BIGINT + dy AS cy, floor(c.lon / {_WIKI_CELL})::BIGINT + dx AS cx
            FROM {rows} r JOIN {candidates} c USING (source, source_id), unnest(c.names) AS u(n),
                 unnest(list_distinct([n.value, trim(regexp_replace(n.value, '[[:space:]]*[（(【［][^）)】］]*[）)】］]', ' ', 'g'))])) AS v(value),
                 (SELECT unnest([-1, 0, 1]) AS dy), (SELECT unnest([-1, 0, 1]) AS dx)
        ),
        exact_any AS (
            SELECT a.source, a.source_id, b.qid, a.store, {_HAVERSINE_M} AS meters
            FROM names a JOIN w b USING (k, cy, cx)
            WHERE length(a.k) >= 2
            QUALIFY row_number() OVER (PARTITION BY a.source, a.source_id ORDER BY meters, b.qid) = 1
                AND meters <= {WIKIDATA_MAX_METERS}
        ),
        exact AS (
            SELECT source, source_id, qid, meters FROM exact_any e
            WHERE NOT (e.store AND e.qid IN (SELECT qid FROM exact_any WHERE NOT store))
        ),
        wc AS (
            SELECT qid, lat, lon, k, floor(lat / {_CONTAINED_CELL})::BIGINT AS cy, floor(lon / {_CONTAINED_CELL})::BIGINT AS cx
            FROM w WHERE length(k) >= CASE WHEN regexp_matches(k, '^[a-z0-9]+$') THEN {CONTAINS_MIN_LATIN_LENGTH}
                                           ELSE {CONTAINED_MIN_CHARS} END
              AND sitelinks >= {CONTAINED_MIN_SITELINKS}
              AND qid NOT IN (SELECT qid FROM exact) {areas}
        ),
        nc AS (
            SELECT DISTINCT source, source_id, lat, lon, country, k,
                   floor(lat / {_CONTAINED_CELL})::BIGINT + dy AS cy, floor(lon / {_CONTAINED_CELL})::BIGINT + dx AS cx
            FROM (SELECT DISTINCT source, source_id, lat, lon, country, k FROM names WHERE NOT shop),
                 (SELECT unnest([-1, 0, 1]) AS dy), (SELECT unnest([-1, 0, 1]) AS dx)
        ),
        contained AS (
            SELECT a.source, a.source_id, b.qid, {_HAVERSINE_M} AS meters, ends_with(a.k, b.k) AS head
            FROM nc a JOIN wc b USING (cy, cx)
            WHERE a.k <> b.k AND contains(a.k, b.k) AND NOT {part}
              AND NOT EXISTS (SELECT 1 FROM exact e WHERE e.source = a.source AND e.source_id = a.source_id)
        ),
        nearest_contained AS (
            SELECT * EXCLUDE (head) FROM contained WHERE meters <= {CONTAINED_MAX_METERS}
            QUALIFY row_number() OVER (PARTITION BY qid ORDER BY head DESC, meters, source, source_id) = 1
        )
        SELECT * FROM (
            SELECT source, source_id, qid, meters, 'exact' AS rule FROM exact
            UNION ALL
            SELECT source, source_id, qid, meters, 'contains' FROM nearest_contained
        )
        QUALIFY row_number() OVER (PARTITION BY source, source_id ORDER BY rule = 'contains', meters, qid) = 1
    """)
    return table


def within_source_duplicates(con, candidates="candidates"):
    """같은 공급처 안에서 정확 키가 같고 가까운 쌍 수(출처 자체의 중복 정도)."""
    return dict(con.execute(f"""
        WITH ok AS (SELECT * FROM {candidates} WHERE exclusion IS NULL)
        SELECT a.source, count(*)
        FROM ok a JOIN ok b ON a.source = b.source AND a.name_key = b.name_key AND a.source_id < b.source_id
        WHERE abs(a.lat - b.lat) < 0.001 AND abs(a.lon - b.lon) < 0.0015 AND {_HAVERSINE_M} <= {EXACT_NAME_MAX_METERS}
        GROUP BY 1
    """).fetchall())
