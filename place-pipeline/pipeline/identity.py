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
from .rules import load_item_kinds, load_name_rules, match_sql, strip_sql

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
# 가게·숙소 행이 사업체가 아닌 같은 이름 항목에 이어지는 거리. 이보다 먼 강·다리·명소 항목은 이름만 딴 것이다(식당 Нева).
SHOP_NAME_MAX_METERS = 250
STORE_LIKE_CATEGORIES = ("Dining and Drinking", "Retail", "Travel and Transportation > Lodging",
                         "Travel and Transportation > Tourist Information and Service")
# 한 항목에 이어진 같은 이름 행을 같은 장소의 출처별 행으로 보는 거리. 출처별 좌표가 같은 건물 안에서 이만큼 다르다.
NAMESAKE_NEAR_METERS = 150
# 이 언어판 수 이상인 항목의 먼 같은 이름 행은 넓은 장소(절 경내·공항·산)의 출처별 사본이다.
NAMESAKE_FAMOUS_SITELINKS = 3
# 끝 낱말이 장소 종류인(머리말이 뒤에 오는) 문자. 이 문자의 이름은 항목 이름으로 끝나야 그 항목 자체다.
HEAD_FINAL_SCRIPTS = "[\\p{Han}\\p{Hiragana}\\p{Katakana}\\p{Hangul}]"
# 역 행은 운영사 이름만 붙었을 때(name_rules operator_prefix) 포함 연결한다. 정류장·출구 이름은 역 이름을 품는다.
TRANSIT_HUB_CATEGORY = "Travel and Transportation > Transport Hub"
# 외래어 가타카나는 같은 소리를 두 표기로 적는다(テュイルリー·チュイルリー). 항목 연결 키에서만 한쪽으로 접는다.
KATAKANA_VARIANTS = (("ヴァ", "バ"), ("ヴィ", "ビ"), ("ヴェ", "ベ"), ("ヴォ", "ボ"), ("ヴ", "ブ"), ("テュ", "チュ"),
                     ("デュ", "ジュ"), ("ティ", "チ"), ("ディ", "ジ"))
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
# 한 이름 안의 다른 언어 병기(" / "·" | "·"ㅣ"). " - "·쉼표 뒤는 위치(- Berlin)나 그 안의 시설(- Red Stairs)이 많아 나누지 않는다.
_SEGMENT_SPLIT = "\\s+[/|｜]\\s+|\\s*[ㅣ｜]\\s*"
# 이름 뒤 " - "·쉼표 꼬리. 꼬리가 지명·군말뿐일 때만 앞 조각을 이름으로 본다(Japanese garden, Monte Carlo).
_HEAD_SPLIT = "\\s+[-–—]\\s+|\\s*,\\s*"
# 구분 기호 없이 현지 문자 이름 뒤(앞)에 로마자 표기가 붙은 이름(醍醐寺 Daigo-ji Temple). 두 쪽이 한 항목의 이름일 때만
# 병기다. 한쪽이 지명이면(BIG JOY 心斎橋) 위치 표시다.
_LOCAL = "\\p{Han}\\p{Hiragana}\\p{Katakana}\\p{Hangul}"
_SCRIPT_SPLITS = (f"^([^A-Za-z]*[{_LOCAL}][^A-Za-z]*?)\\s+([A-Za-z][^{_LOCAL}]*)$",
                  f"^([A-Za-z][^{_LOCAL}]*?)\\s+([^A-Za-z]*[{_LOCAL}][^A-Za-z]*)$")
# Wikidata 정확 연결에서 떼는 괄호. 「」 안은 장소 안의 물건·작품 이름(八幡神社「狛犬」)이라 떼지 않는다.
_NOTE = "[[:space:]]*[（(【［][^）)】］]*[）)】］]"
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


def wikidata_links(con, rows, candidates, wikidata_raw, table="wikidata_links", area_items=None,
                   brand_items=None, business_items=None, kind_items=None):
    """
    rows(키: source, source_id)마다 Wikidata 항목 하나. 열: source, source_id, qid, meters, rule.
      exact    : 이름 정확 키(악센트 접음)가 같은 가장 가까운 항목. 전체 이름 → 괄호 뗀 이름 → 조각 순으로 앞서고, 같은 이름이면
                 라벨 일치가 별칭 일치보다 앞선다. 별칭에는 옛 이름(北品川駅의 品川駅)과 기관이 든 건물 이름이 섞인다.
                 가게·식당·숙소 행(몰·백화점 제외)은 사업체 항목(business_items)이 아니면 SHOP_NAME_MAX_METERS 안에서만
                 잇는다. 이름이 브랜드명뿐인 체인 지점은 사업체 항목에도 가까이서만 잇는다. 같은 이름 지점 항목이 따로 있다.
                 business_items가 없으면 모든 항목을 사업체로 본다. 괄호 병기를 뗀 이름과 병기 조각(" / "·" | "·
                 "ㅣ"로 나눈 부분)도 따로 본다(鹿苑寺 (金閣寺) → 鹿苑寺). 병기 조각은 가게·교통 행에 쓰지 않고(정류장 이름
                 뒤의 거리 이름) 지명 항목에 잇지 않는다. " - "·쉼표 뒤 꼬리가 모두 지명(지명 항목 이름이나 그 행 주소의 도시)이거나 군말(name_rules
                 tail_filler)이거나 앞 조각과 함께 한 항목의 이름이면 앞 조각도 병기 조각처럼 본다. 구분 기호 없이 현지 문자와
                 로마자가 이어진 이름(醍醐寺 Daigo-ji Temple)은 두 쪽이 한 항목의 이름일 때 병기 조각으로 본다. 같은 항목에 명소 행이 이어지면 이름을 딴 가게·식당 행은 잇지 않는다.
                 지명 항목(area_items)은 가게·식당·업무·교통 행에 잇지 않고, 지명 이름뿐인 가게 행과 병기 조각은 같은 이름의 다른
                 항목(도시 이름 조각상)에도 잇지 않는다(시 이름만 적힌 주류점, Le District | Paris).
                 항목 이름은 ja·en·ko 라벨, 스냅샷에 있으면 그 나라 언어 라벨(labels)과 별칭(aliases)이다. 별칭은 정확 연결에만 쓰고, 언어판이
                 NAMESAKE_FAMOUS_SITELINKS개 미만인 항목의 별칭이 다른 항목의 라벨이면(흔한 이름 宇賀神社) 쓰지 않는다.
                 행 분류가 item_kinds.tsv의 종류(휴게소)면 별칭으로는 그 종류 항목(kind_items, 열 qid·kind)에만 잇는다.
                 kind_items가 없으면 종류를 보지 않는다. 이름 키는 외래어 가타카나 표기 차이(KATAKANA_VARIANTS)를 접는다.
                 한 항목에 이어진 행 가운데 그 항목에 가장 가까운 행(기준 행)에서 NAMESAKE_NEAR_METERS 밖에 있는 행은
                 같은 이름의 다른 항목이 있고 항목 언어판 수가 NAMESAKE_FAMOUS_SITELINKS보다 적을 때 잇지 않는다.
                 흔한 이름(熊野神社)의 먼 행은 항목이 없는 다른 장소이고, 드물거나 유명한 이름의 먼 행은 같은 장소의 사본이다.
      contains : 정확 키로 이어진 행이 없는 항목은 CONTAINED_MAX_METERS 안에서 이름에 항목 이름을 품은 가장 가까운 행 하나에 잇는다.
                 정식 이름이 길게 붙은 명소(浅草観音金龍山浅草寺)를 잡고, 지명을 단 가게·식당은 잇지 않는다.
                 항목 이름 뒤에 다른 말이 붙은 행(大涌谷延命地蔵尊·鹿苑寺 方丈·新宿駅 臨時口)은 그 안의 시설이나 이름을 딴
                 다른 곳이라 잇지 않는다. 한자·가나·한글 이름은 끝 낱말이 장소 종류라 이름이 항목 이름으로 끝나야 하고,
                 그 밖의 문자는 남은 말이 같은 종류를 다시 말하는 낱말(name_rules same_kind)이거나 항목 이름 앞에 낱말
                 하나(Evangelische Christuskirche·AKC Museum of the Dog)만 붙었을 때 잇는다. "Museum of …"처럼 앞에 여럿이
                 붙으면 그 항목 안·옆의 다른 시설이다. 라틴 문자 이름 뒤에 [전치사] + 지명만 남으면(… in Berlin) 같은 장소다.
                 교통 허브 행은 남은 말이 운영사·노선 이름(name_rules operator_prefix)일 때만 포함 연결한다(JR 新宿駅).
                 area_items(열 qid)에 든 지명 항목(市·町)은 시설 이름에 흔히 들어가서 포함 연결에 쓰지 않는다. 없으면 포함 연결을 하지 않는다.
    brand_items(열 qid)는 브랜드·체인 항목이다(wikidata.brand_qids). 좌표가 본사 한 점이라 같은 이름 지점 어디에도 잇지 않는다.
    """
    rules = load_name_rules()
    columns = {d[0] for d in con.execute(f"SELECT * FROM {wikidata_raw} LIMIT 0").description}
    local_labels = "coalesce(labels, [])" if "labels" in columns else "[]::VARCHAR[]"
    alias_labels = "coalesce(aliases, [])" if "aliases" in columns else "[]::VARCHAR[]"
    # 좌표가 여러 개인 항목(강)은 행에 가장 가까운 좌표로 잰다.
    points = "coalesce(coords, [[lat, lon]])" if "coords" in columns else "[[lat, lon]]"
    # 목록 표에 NULL이 섞여도 NOT IN이 모든 항목을 버리지 않게 NULL을 거른다.
    listed = lambda table: f"(SELECT qid FROM {table} WHERE qid IS NOT NULL)"
    area_qid = f"qid IN {listed(area_items)}" if area_items else "false"
    item_is_area = f"b.qid IN {listed(area_items)}" if area_items else "false"
    areas = f"AND qid NOT IN {listed(area_items)}" if area_items else "AND false"
    brands = f"AND qid NOT IN {listed(brand_items)}" if brand_items else ""
    shops = " OR ".join(f"starts_with(c.category, '{p}')" for p in CONTAINED_IGNORED_CATEGORIES)
    stores = " OR ".join(f"starts_with(c.category, '{p}')" for p in NAMESAKE_STORE_CATEGORIES)
    big_store = " OR ".join(f"starts_with(c.category, '{p}')" for p in NAMESAKE_STORE_EXCEPTIONS)
    store_like = " OR ".join(f"starts_with(c.category, '{p}')" for p in STORE_LIKE_CATEGORIES)
    business = f"b.qid IN {listed(business_items)}" if business_items else "true"
    stores = f"({stores}) AND NOT ({big_store})"
    rest = "replace(a.k, b.k, '')"
    part = match_sql(rest, "a.country", rules["part_marker"])
    same_kind = match_sql(rest, "a.country", rules["same_kind"])
    filler = match_sql("t.tk", "t.country", rules["tail_filler"])
    operator = match_sql(rest, "a.country", rules["operator_prefix"])
    def is_place_name(text, country, locality):
        stripped = strip_sql(text, country, rules["place_preposition"])
        return (f"({text} IN (SELECT k FROM area_keys) OR {stripped} IN (SELECT k FROM area_keys) "
                f"OR (length({locality}) > 0 AND ({text} = {locality} OR {stripped} = {locality})))")
    fold = "name_key(s)"
    for variant, standard in KATAKANA_VARIANTS:
        fold = f"replace({fold}, '{variant}', '{standard}')"
    con.execute(f"CREATE OR REPLACE TEMP MACRO link_key(s) AS strip_accents({fold})")
    kinds = load_item_kinds()
    row_kind = ("CASE " + " ".join(f"WHEN starts_with(c.category, '{_literal(prefix)}') THEN '{_literal(kind)}'"
                                   for kind, prefix, _ in kinds) + " END") if kinds else "NULL"
    lacks_kind = (f"(b.qid, a.kind) NOT IN (SELECT (qid, kind) FROM {kind_items} WHERE qid IS NOT NULL)"
                  if kind_items else "false")
    locality = "link_key(coalesce(c.locality, ''))"
    bare = f"trim(regexp_replace(n.value, '{_literal(_NOTE)}', ' ', 'g'))"
    anchor_meters = _HAVERSINE_M.replace("a.lat", "x.lat").replace("a.lon", "x.lon").replace("b.", "e.")
    con.execute(f"""
        CREATE OR REPLACE TABLE {table} AS
        WITH w_all AS (
            SELECT qid, p[1] AS lat, p[2] AS lon, sitelinks, link_key(l.label) AS k, l.alias,
                   len(regexp_split_to_array(trim(l.label), '\\s+')) AS words,
                   floor(p[1] / {_WIKI_CELL})::BIGINT AS cy, floor(p[2] / {_WIKI_CELL})::BIGINT AS cx
            FROM (SELECT * EXCLUDE (lat, lon), unnest({points}) AS p FROM {wikidata_raw}),
                 unnest(list_transform([ja::VARCHAR, en::VARCHAR, ko::VARCHAR] || {local_labels}, x -> {{'label': x, 'alias': false}})
                        || list_transform({alias_labels}, x -> {{'label': x, 'alias': true}})) AS u(l)
            WHERE l.label IS NOT NULL {brands}
        ),
        label_keys AS (SELECT k, min(qid) AS q1, max(qid) AS q2 FROM w_all WHERE NOT alias GROUP BY k),
        w AS (
            SELECT w_all.* FROM w_all LEFT JOIN label_keys USING (k)
            WHERE NOT (alias AND sitelinks < {NAMESAKE_FAMOUS_SITELINKS} AND label_keys.k IS NOT NULL
                       AND (label_keys.q1 <> qid OR label_keys.q2 <> qid))
        ),
        area_keys AS (SELECT DISTINCT k FROM w WHERE NOT alias AND length(k) > 0 AND {area_qid}),
        tails AS (
            SELECT source, source_id, country, lk, value, parts[1] AS head, len(parts) - 1 AS n,
                   link_key(t.tail) AS tk
            FROM (SELECT r.source, r.source_id, c.country, {locality} AS lk, n.value,
                         regexp_split_to_array({bare}, '{_HEAD_SPLIT}') AS parts
                  FROM {rows} r JOIN {candidates} c USING (source, source_id), unnest(c.names) AS u(n)),
                 unnest(parts[2:]) AS t(tail)
            WHERE len(parts) > 1 AND length(trim(parts[1])) > 0
        ),
        heads AS (
            SELECT source, source_id, head FROM tails t
            GROUP BY source, source_id, value, head
            HAVING bool_and(length(tk) > 0 AND ({filler} OR {is_place_name('t.tk', 't.country', 't.lk')}))
            UNION
            SELECT t.source, t.source_id, t.head FROM tails t
            JOIN w a ON a.k = t.tk JOIN w b ON b.qid = a.qid AND b.k = link_key(t.head)
            GROUP BY t.source, t.source_id, t.value, t.head, a.qid
            HAVING count(DISTINCT t.tk) = any_value(t.n)
        ),
        script_pairs AS (
            SELECT source, source_id, regexp_extract(value, pattern, 1) AS x, regexp_extract(value, pattern, 2) AS y
            FROM (SELECT DISTINCT r.source, r.source_id, {bare} AS value
                  FROM {rows} r JOIN {candidates} c USING (source, source_id), unnest(c.names) AS u(n)),
                 (SELECT unnest([{", ".join(f"'{_literal(x)}'" for x in _SCRIPT_SPLITS)}]) AS pattern)
            WHERE regexp_matches(value, pattern)
        ),
        name_values AS (
            SELECT DISTINCT r.source, r.source_id, v.value, v.segment, v.rank
            FROM {rows} r JOIN {candidates} c USING (source, source_id), unnest(c.names) AS u(n),
                 unnest([{{'value': n.value, 'segment': false, 'rank': 0}}, {{'value': {bare}, 'segment': false, 'rank': 1}}]
                        || list_transform(regexp_split_to_array({bare}, '{_SEGMENT_SPLIT}'),
                                          x -> {{'value': x, 'segment': len(regexp_split_to_array({bare}, '{_SEGMENT_SPLIT}')) > 1,
                                                'rank': 2}})
                 ) AS u2(v)
            UNION ALL
            SELECT source, source_id, head, true, 2 FROM heads
            UNION ALL
            SELECT DISTINCT s.source, s.source_id, part, true, 2 FROM script_pairs s
            JOIN w a ON a.k = link_key(s.x) JOIN w b ON b.qid = a.qid AND b.k = link_key(s.y),
                 unnest([s.x, s.y]) AS u(part)
        ),
        row_attrs AS MATERIALIZED (
            SELECT r.source, r.source_id, c.lat, c.lon, c.country, {locality} AS lk,
                   coalesce({shops}, false) AS shop, coalesce({stores}, false) AS store,
                   coalesce(starts_with(c.category, '{TRANSIT_HUB_CATEGORY}'), false) AS transit,
                   coalesce(({store_like}) AND NOT ({big_store}), false) AS store_like,
                   coalesce(({store_like}) AND name_key(c.brand) = name_key(c.name), false) AS branch,
                   {row_kind} AS kind
            FROM {rows} r JOIN {candidates} c USING (source, source_id)
        ),
        name_keys AS MATERIALIZED (
            SELECT source, source_id, k, min(words) AS words, bool_and(segment) AS segment, min(rank) AS rank
            FROM (SELECT source, source_id, link_key(value) AS k, segment, rank,
                         len(regexp_split_to_array(trim(value), '\\s+')) AS words FROM name_values)
            GROUP BY source, source_id, k
        ),
        names AS (
            SELECT v.source, v.source_id, c.lat, c.lon, c.country, c.lk, v.k, v.words, v.segment, v.rank,
                   c.shop, c.store, c.transit, c.store_like, c.branch, c.kind,
                   floor(c.lat / {_WIKI_CELL})::BIGINT + dy AS cy, floor(c.lon / {_WIKI_CELL})::BIGINT + dx AS cx
            FROM name_keys v JOIN row_attrs c USING (source, source_id),
                 (SELECT unnest([-1, 0, 1]) AS dy), (SELECT unnest([-1, 0, 1]) AS dx)
        ),
        exact_any AS (
            SELECT a.source, a.source_id, b.qid, a.store, a.k, a.lat, a.lon, b.sitelinks, {_HAVERSINE_M} AS meters
            FROM names a JOIN w b USING (k, cy, cx)
            WHERE length(a.k) >= 2 AND NOT (a.shop AND {item_is_area}) AND NOT (a.segment AND (a.shop OR {item_is_area}))
              AND NOT ((a.shop OR a.segment) AND a.k IN (SELECT k FROM area_keys))
              AND NOT (b.alias AND a.kind IS NOT NULL AND {lacks_kind})
              AND {_HAVERSINE_M} <= CASE WHEN a.branch OR (a.store_like AND NOT ({business})) THEN {SHOP_NAME_MAX_METERS}
                                         ELSE {WIKIDATA_MAX_METERS} END
            QUALIFY row_number() OVER (PARTITION BY a.source, a.source_id ORDER BY a.rank, b.alias, meters, b.qid) = 1
        ),
        exact_kept AS (
            SELECT * FROM exact_any e
            WHERE NOT (e.store AND e.qid IN (SELECT qid FROM exact_any WHERE NOT store))
        ),
        anchors AS (
            SELECT qid, p.lat, p.lon FROM (SELECT qid, arg_min({{'lat': lat, 'lon': lon}}, (meters, source, source_id)) AS p
                                FROM exact_kept GROUP BY 1)
        ),
        shared_names AS (SELECT k FROM w GROUP BY k HAVING count(DISTINCT qid) > 1),
        exact AS (
            SELECT e.source, e.source_id, e.qid, e.meters FROM exact_kept e JOIN anchors x USING (qid)
            WHERE {anchor_meters} <= {NAMESAKE_NEAR_METERS} OR e.sitelinks >= {NAMESAKE_FAMOUS_SITELINKS}
               OR e.k NOT IN (SELECT k FROM shared_names)
        ),
        wc AS (
            SELECT qid, lat, lon, k, words,
                   floor(lat / {_CONTAINED_CELL})::BIGINT AS cy, floor(lon / {_CONTAINED_CELL})::BIGINT AS cx
            FROM w WHERE NOT alias AND length(k) >= CASE WHEN regexp_matches(k, '^[a-z0-9]+$') THEN {CONTAINS_MIN_LATIN_LENGTH}
                                           ELSE {CONTAINED_MIN_CHARS} END
              AND sitelinks >= {CONTAINED_MIN_SITELINKS}
              AND qid NOT IN (SELECT qid FROM exact) {areas}
        ),
        nc AS (
            SELECT DISTINCT source, source_id, lat, lon, country, lk, k, words, shop,
                   floor(lat / {_CONTAINED_CELL})::BIGINT + dy AS cy, floor(lon / {_CONTAINED_CELL})::BIGINT + dx AS cx
            FROM (SELECT DISTINCT source, source_id, lat, lon, country, lk, k, words, shop FROM names WHERE NOT shop OR transit),
                 (SELECT unnest([-1, 0, 1]) AS dy), (SELECT unnest([-1, 0, 1]) AS dx)
        ),
        contained AS (
            SELECT a.source, a.source_id, b.qid, {_HAVERSINE_M} AS meters, ends_with(a.k, b.k) AS head
            FROM nc a JOIN wc b USING (cy, cx)
            WHERE a.k <> b.k AND contains(a.k, b.k) AND NOT {part}
              AND (NOT a.shop OR (ends_with(a.k, b.k) AND {operator}))
              AND ({same_kind} OR (ends_with(a.k, b.k)
                                   AND (regexp_matches(a.k, '{HEAD_FINAL_SCRIPTS}') OR a.words - b.words <= 1))
                   OR (starts_with(a.k, b.k) AND NOT regexp_matches(a.k, '{HEAD_FINAL_SCRIPTS}')
                       AND {is_place_name(rest, 'a.country', 'a.lk')}))
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
