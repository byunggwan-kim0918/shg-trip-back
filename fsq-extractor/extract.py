"""
FSQ OS Places 월간 추출기

Places Portal Iceberg REST Catalog에서 한국 주요 도시와 해외 여행지(destinations.json) 데이터를 추출하여 S3에 저장한다.
공식 문서: https://docs.foursquare.com/data-products/docs/places-os-data-schema
테이블 경로: places.datasets.places_os
"""
import duckdb
import boto3
import json
import math
import os
import unicodedata
import logging
from dataclasses import dataclass
from datetime import date

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s"
)
log = logging.getLogger(__name__)

# ── 설정 ──────────────────────────────────────────────────────────────────────
S3_BUCKET       = os.environ.get("S3_BUCKET", "shgtrip-data")
S3_PREFIX       = os.environ.get("S3_PREFIX", "foursquare")
AWS_REGION      = os.environ.get("AWS_REGION", "ap-northeast-2")
AWS_ENDPOINT_URL = os.environ.get("AWS_ENDPOINT_URL")  # LocalStack 엔드포인트
OUTPUT_FILE     = "/tmp/foursquare-places.csv"

TABLE_PATH = "places.datasets.places_os"

# 프랜차이즈 판정 기준 — "… OO점" 지점명이 이만큼 이상인 브랜드는 음식·음료에서 뺀다.
# 실측(2026-10, KR 영업중·2년내): 20 이상 브랜드는 파리바게뜨·롯데리아·스타벅스·본죽 등
# 전부 전국 체인이고, 경계 구간(20~40)에도 오탐이 없었다. 여행 일정의 식사·카페 슬롯에
# 체인 지점이 들어가는 건 경험을 깎는다. 숙소·상점 체인(호텔, 면세점, 올리브영)은 건드리지 않는다.
CHAIN_MIN_BRANCHES = 20

# 영문 상호 체인(Starbucks 1,656곳, Paris Baguette 648곳 …)은 "…점"이 안 붙은 같은 이름으로 반복돼
# 위 규칙에 안 걸린다(실측: 12개 도시에서 5,115행 누수). 영문 이름은 같은 이름 반복 횟수로 판정하되,
# 업종 일반명은 브랜드가 아니므로 뺀다. 한글 이름에는 반복 횟수 규칙을 쓰지 않는다 —
# 고향집·시골집·서울식당·함흥냉면처럼 체인이 아닌 흔한 상호가 걸린다.
GENERIC_LATIN_NAMES = (
    "cafe", "the cafe", "the caffe", "coffee", "coffee house", "coffee shop", "bakery",
    "pub", "bar", "restaurant", "kitchen", "37.5",
)

# 갱신 기준(년). 식당·상점은 오래 갱신되지 않으면 폐업 가능성이 높지만, 명소·자연은
# 갱신이 뜸할 뿐 사라지지 않는다. 실측: 2년 기준이면 첨성대 12행 중 2행, 경포대 18행 중 5행만 남았다.
FRESH_YEARS_DEFAULT = 2
FRESH_YEARS_LANDMARK = 5

# ── 해외 ──────────────────────────────────────────────────────────────────────
# 해외는 도시 텍스트 대신 여행지 목록(백엔드와 같은 파일)의 중심·반경으로 배정한다.
# 이미지에는 extract.py 옆에, 저장소에서는 백엔드 resources에 있다.
_HERE = os.path.dirname(os.path.abspath(__file__))
DESTINATIONS_FILE = os.environ.get("DESTINATIONS_FILE") or next(
    (p for p in (os.path.join(_HERE, "destinations.json"),
                 os.path.join(_HERE, "..", "src", "main", "resources", "destinations.json"))
     if os.path.exists(p)), "destinations.json")

# 해외 식당은 갱신 1년. 2년 기준이면 폐업 표시가 늦은 중국·베트남 식당이 많이 남는다.
OVERSEAS_FRESH_YEARS_DINING = 1

# 여러 나라에 지점이 있는 브랜드. 현지 문자 표기가 달라 반복 횟수로는 한 브랜드로 묶이지 않는다.
GLOBAL_CHAINS = (
    "starbucks", "星巴克", "スターバックス", "mcdonald", "麦当劳", "麥當勞", "マクドナルド",
    "kfc", "肯德基", "ケンタッキー", "burger king", "汉堡王", "漢堡王", "バーガーキング",
    "subway", "赛百味", "賽百味", "サブウェイ", "pizza hut", "必胜客", "必勝客", "ピザハット",
    "domino's", "domino’s", "domino pizza", "达美乐", "達美樂", "ドミノ・ピザ", "costa coffee", "咖世家",
    "tim hortons", "dunkin", "唐恩都乐", "ダンキン", "krispy kreme", "クリスピー・クリーム",
    "baskin", "サーティワン", "31冰淇淋", "häagen-dazs", "haagen-dazs", "哈根达斯", "哈根達斯",
    "ハーゲンダッツ", "dairy queen", "冰雪皇后", "shake shack", "taco bell", "papa john",
    "wendy's", "wendy’s", "ウェンディーズ", "carl's jr", "carl’s jr", "popeyes", "paris baguette", "巴黎贝甜", "巴黎貝甜",
    "tous les jours", "多乐之日", "the coffee bean", "coffee bean & tea leaf", "香啡缤", "pacific coffee",
    "lotteria", "ロッテリア", "乐天利", "jollibee", "texas chicken", "auntie anne", "gong cha", "贡茶",
    "貢茶", "mister donut", "ミスタードーナツ", "mixue", "蜜雪冰城", "luckin", "瑞幸",
)
# 한 나라 대상 여행지 전체에서 같은 상호가 이만큼 이상이면 현지 대형 체인으로 보고 뺀다.
# 일본은 すき家·吉野家·松屋 같은 규동 체인이 수백~천 곳이라 남겨 두면 끼니가 체인으로 채워진다.
MASS_CHAIN_MIN_BRANCHES = 100
# 체인이어도 그 자체가 여행 코스인 브랜드. 위 두 규칙보다 우선한다.
TOURIST_CHAINS = (
    "ichiran", "一蘭", "一兰", "ippudo", "一風堂", "一风堂", "sushiro", "スシロー", "寿司郎",
    "marugame", "丸亀製麺", "丸龜製麵", "丸龟制面", "din tai fung", "鼎泰豐", "鼎泰丰",
    "haidilao", "海底捞", "海底撈", "cộng cà phê", "cong caphe", "cong ca phe", "kebuke", "可不可",
    "50嵐", "85度c", "85°c", "點都德", "点都德", "茶颜悦色", "551蓬莱", "551蓬萊",
    "starbucks reserve roastery", "スターバックス リザーブ ロースタリー", "星巴克臻选上海烘焙工坊",
)
# 같은 브랜드는 여행지마다 이만큼만 남겨 후보가 한 브랜드로 덮이지 않게 한다.
BRAND_PER_DESTINATION = 3

# 여행지 규모(선별 후 행 수)별 명소·식음료 상한. 숙소·시장·교통 허브는 수가 적어 두지 않는다.
# 일정 후보는 슬롯마다 상위 수십 곳만 쓰므로 대도시도 수천 곳이면 충분하고, 도쿄 전량(약 19만)이면 DB가 두 배가 된다.
DESTINATION_CAPS = (  # (최소 행 수, 명소 상한, 식음료 상한)
    (60_000, 5000, 7500),
    (25_000, 2500, 5000),
    (10_000, 1500, 3100),
    (4_000, 1000, 1900),
)

# 일정에 쓸 수 없는 명소·교통 시설 하위 장소(승강장·개찰구·출구·주차장).
JUNK_NAME_PATTERN = (r"(?i)(番線|改札|ホーム|出口|入口|駐車場|乗り場|のりば|站台|出入口|出站口|登机口|搭乗口"
                     r"|[A-Z]\d*口|\d+号口|停车场|停車場|Bãi (đỗ|giữ) xe|\bExit\s?\d|\bGate\s?\d|\bPlatform\s?\d)")
# Arts and Entertainment 아래의 비여행 업종.
NON_TRAVEL_SIGHT_PATTERN = ("(Movie Theater|Casino|Strip Club|Adult|Pachinko|Gaming Cafe|Internet Cafe|Bingo"
                            "|Lottery|Bowling|Playground|Dog Park|Cemetery|Karaoke)")
# 업종이 이것뿐이면 "구체 업종" 신호를 주지 않는다.
GENERIC_FOOD_CATEGORIES = (
    "Dining and Drinking > Restaurant",
    "Dining and Drinking > Cafe, Coffee, and Tea House",
    "Dining and Drinking > Bar",
    "Dining and Drinking > Restaurant > Asian Restaurant",
    "Dining and Drinking > Restaurant > Asian Restaurant > Chinese Restaurant",
    "Dining and Drinking > Restaurant > Asian Restaurant > Japanese Restaurant",
    "Dining and Drinking > Restaurant > Asian Restaurant > Vietnamese Restaurant",
    "Dining and Drinking > Restaurant > Asian Restaurant > Taiwanese Restaurant",
)


@dataclass(frozen=True)
class City:
    """적재 대상 도시. name이 DB places.region 값이 된다(적재기·벡터 검색이 쓰는 표준 영어 도시명)."""
    name: str
    bbox: tuple                     # (min_lat, max_lat, min_lng, max_lng)
    aliases: tuple                  # 소문자 표기 — locality는 접두어, region은 부분문자열로 비교
    metro: bool                     # 광역시·특별자치도면 True: 시도명(region)으로도 판정한다
    districts: tuple = ()           # 시 단위 도시의 구 이름 (수원 장안구 등)
    region_exclude: tuple = ()      # region에 이게 섞여 있으면 alias가 맞아도 이 도시가 아니다
    district_region_aliases: tuple = ()  # locality가 구일 때만 이 도시로 보는 region 표기


# 순서가 우선순위다(좌표 범위가 겹치는 행은 먼저 맞는 도시로 간다 — 서울·인천 경계).
#
# locality는 자유 텍스트라 같은 도시도 표기가 제각각이다(2026-10 실측: 서울 범위 안에
# "서울특별시" 63,596 / "Seoul" 14,220 / "서울" 3,605 / "서울시" 2,718 / 구 이름 / NULL 31,424).
# 이전 구현은 영어 정확일치(locality IN ('Seoul', ...))라 12개 도시 18만 건 중 1.5만 건만
# 잡았고, 제주는 1.1만 건 중 46건이었다.
CITIES = (
    City("Seoul",    (37.413, 37.715, 126.734, 127.270), ("서울", "seoul"), True),
    City("Busan",    (34.880, 35.390, 128.760, 129.310), ("부산", "busan", "pusan"), True),
    City("Incheon",  (37.300, 37.850, 126.100, 126.800), ("인천", "incheon", "inchon"), True),
    City("Daegu",    (35.600, 36.300, 128.350, 128.800), ("대구", "daegu", "taegu"), True),
    City("Daejeon",  (36.180, 36.500, 127.250, 127.560), ("대전", "daejeon", "taejon"), True),
    # "Jeonnam-Gwangju Special Metropolitan City"는 전남 시군에도 붙는 표기라 alias로 쓰지 않는다.
    # 다만 전남에는 '구'가 없으니, locality가 구면 광주다.
    City("Gwangju",  (35.050, 35.260, 126.640, 127.020), ("광주", "gwangju", "kwangju"), True,
         region_exclude=("jeonnam", "전남", "전라남도", "jeollanam"),
         district_region_aliases=("jeonnam-gwangju", "전남광주")),
    City("Jeju",     (33.100, 33.580, 126.080, 126.980),
         ("제주", "서귀포", "jeju", "seogwipo", "sogwipo", "cheju"), True),
    City("Suwon",    (37.220, 37.330, 126.930, 127.080), ("수원", "suwon"), False,
         districts=("장안구", "권선구", "팔달구", "영통구")),
    City("Gangneung", (37.480, 37.950, 128.660, 129.100), ("강릉", "gangneung", "kangnung"), False),
    City("Gyeongju", (35.650, 36.060, 128.970, 129.500), ("경주", "gyeongju", "kyongju", "kyonju"), False),
    City("Jeonju",   (35.760, 35.900, 127.040, 127.200), ("전주", "jeonju", "chonju"), False,
         districts=("완산구", "덕진구")),
    City("Yeosu",    (34.550, 34.900, 127.450, 127.800), ("여수", "yeosu", "yosu"), False),
)

# region 값이 이것뿐이면 도시 정보가 없는 것으로 본다.
_GENERIC_REGIONS = ("", "대한민국", "south korea", "korea", "republic of korea")


def get_fsq_token() -> str:
    """환경변수에서 FSQ 토큰을 읽는다."""
    token = os.environ.get("FSQ_ACCESS_TOKEN")
    if not token:
        raise EnvironmentError("FSQ_ACCESS_TOKEN 환경변수가 설정되지 않았습니다.")
    return token


def connect_iceberg(conn: duckdb.DuckDBPyConnection) -> None:
    """Iceberg 카탈로그에 연결한다."""
    token = get_fsq_token()

    conn.execute("INSTALL httpfs; LOAD httpfs;")
    conn.execute("INSTALL iceberg; LOAD iceberg;")

    conn.execute(f"""
        CREATE SECRET iceberg_secret (
            TYPE ICEBERG,
            TOKEN '{token}'
        )
    """)
    del token

    conn.execute("""
        ATTACH 'places' AS places (
            TYPE iceberg,
            SECRET iceberg_secret,
            ENDPOINT 'https://catalog.h3-hub.foursquare.com/iceberg'
        )
    """)
    log.info("FSQ Iceberg 카탈로그 연결 완료")


def _quote(s: str) -> str:
    return "'" + s.replace("'", "''") + "'"


def _any(fn: str, col: str, values: tuple) -> str:
    """values 중 하나라도 fn(col, v)이면 참. 비어 있으면 거짓."""
    if not values:
        return "FALSE"
    return "(" + " OR ".join(f"{fn}({col}, {_quote(v)})" for v in values) + ")"


def _city_condition(city: City) -> str:
    """
    한 도시에 속하는 행의 조건. 텍스트가 1순위이고 좌표는 텍스트가 없을 때만 쓴다.

    좌표 범위만 쓰면 경계 도시가 섞인다(서울 범위 안 경기도 17,554행 — 고양·부천,
    부산 범위 안 김해·양산, 대전 범위 안 세종, 경주 범위 안 포항). 그래서
      1. locality가 도시명이면 그 도시 — 다른 도시명이면 제외
      2. locality가 비었거나 구·군이면 region으로 판정 — 다른 시도면 제외
      3. 둘 다 비었을 때만 좌표로 판정
    그리고 어느 경우든 좌표가 그 도시 범위 안이어야 한다(지오코딩 오류 차단).
    """
    lat0, lat1, lng0, lng1 = city.bbox
    in_bbox = f"(latitude BETWEEN {lat0} AND {lat1} AND longitude BETWEEN {lng0} AND {lng1})"
    loc_is_city = _any("starts_with", "loc", city.aliases)
    reg_generic = f"reg IN ({', '.join(_quote(r) for r in _GENERIC_REGIONS)})"

    if city.metro:
        reg_is_city = f"({_any('contains', 'reg', city.aliases)} AND NOT {_any('contains', 'reg', city.region_exclude)})"
        # 구(區)만 본다 — 군(郡)은 전남에도 있다(화순군 등).
        reg_is_city_for_district = f"(loc_gu AND {_any('contains', 'reg', city.district_region_aliases)})"
        text_ok = (f"({loc_is_city} OR ((loc = '' OR loc_district) AND "
                   f"({reg_is_city} OR {reg_generic} OR {reg_is_city_for_district})))")
    else:
        # 시 단위 도시는 region이 도(경기도 등)라 도시를 못 가린다 — locality로만 판정한다.
        loc_is_district = f"loc IN ({', '.join(_quote(d) for d in city.districts)})" if city.districts else "FALSE"
        text_ok = f"({loc_is_city} OR {loc_is_district} OR (loc = '' AND {reg_generic}))"

    return f"{in_bbox} AND {text_ok}"


def build_extract_query(source: str) -> str:
    """
    source 테이블(places_os 스키마)에서 적재 대상 행을 고르는 SELECT문.
    Iceberg 대신 합성 테이블을 넣어 테스트할 수 있게 분리해 뒀다(test_extract.py).

    출력 컬럼은 FoursquareSeeder가 읽는 형식 그대로다. region은 표준 영어 도시명이다.
    비여행 카테고리(은행·병원 등) 필터는 적재기(FoursquareSeeder)가 한 곳에서 맡는다.
    """
    city_case = "CASE\n" + "\n".join(
        f"        WHEN {_city_condition(c)} THEN {_quote(c.name)}" for c in CITIES
    ) + "\n        END"

    return f"""
    WITH {_source_ctes(source)},
    assigned AS (
        SELECT *, {city_case} AS city
        FROM normalized
    )
    SELECT
        fsq_place_id,
        regexp_replace(name, '[\\r\\n]+', ' ', 'g')                     AS name,
        latitude,
        longitude,
        country,
        city                                                          AS region,
        fsq_category_labels[1]                                        AS category,
        regexp_replace(coalesce(address, ''), '[\\r\\n]+', ' ', 'g')    AS address,
        array_to_string(fsq_category_labels, ';')                     AS tags,
        regexp_replace(coalesce(website, ''), '[\\r\\n]+', ' ', 'g')    AS description
    FROM assigned a
    WHERE city IS NOT NULL
      AND NOT (top_category = 'Dining and Drinking'
               AND (EXISTS (SELECT 1 FROM chain c
                            WHERE starts_with(lower(trim(a.name)), lower(c.stem)))
                    -- 영문은 단어 경계까지 본다: "starbucks coffee"는 걸리고 "bbqueen"은 안 걸린다
                    OR EXISTS (SELECT 1 FROM latin_chain l
                               WHERE a.latin_stem = l.stem OR starts_with(a.latin_stem, l.stem || ' '))))
    """


def detected_chains(conn: duckdb.DuckDBPyConnection, source: str) -> list:
    """이번 데이터에서 체인으로 판정된 브랜드 (종류, 이름, 행 수). 오탐을 로그로 확인하려고 쓴다."""
    return conn.execute(f"""
        WITH {_source_ctes(source)}
        SELECT 'ko', stem, n FROM chain
        UNION ALL
        SELECT 'latin', stem, n FROM latin_chain
        ORDER BY 3 DESC
    """).fetchall()


def _source_ctes(source: str) -> str:
    """추출·체인 로그가 공유하는 CTE(base → normalized → chain, latin_chain)."""
    return f"""base AS (
        SELECT *,
               split_part(fsq_category_labels[1], ' > ', 1) AS top_category,
               lower(trim(coalesce(locality, ''))) AS loc,
               lower(trim(coalesce(region, '')))   AS reg
        FROM {source}
        WHERE country = 'KR'
          AND date_closed IS NULL
          -- 사용자 신고(폐업·존재안함·중복·사유지 등)가 걸린 행은 뺀다 — "가보니 없음"이 최악의 경험이다.
          AND coalesce(len(unresolved_flags), 0) = 0
          AND CAST(date_refreshed AS DATE) >= CURRENT_DATE - to_years(
                CASE WHEN split_part(fsq_category_labels[1], ' > ', 1) = 'Landmarks and Outdoors'
                     THEN {FRESH_YEARS_LANDMARK} ELSE {FRESH_YEARS_DEFAULT} END)
    ),
    normalized AS (
        SELECT *,
               regexp_matches(loc, '(구|군)$|(-gu|-gun| gu| district| county)$') AS loc_district,
               regexp_matches(loc, '구$|(-gu| gu| district)$') AS loc_gu,
               {_latin_stem_expr()} AS latin_stem
        FROM base
    ),
    {_chain_ctes('normalized')}"""


def _latin_stem_expr() -> str:
    """상호에서 지점명("… 강남점")과 괄호 이하("(강남)")를 떼고 소문자로 만든 값."""
    return ("trim(regexp_replace(regexp_replace(lower(trim(name)), '\\s+\\S+점$', ''), "
            "'\\s*[\\(\\[].*$', ''))")


def _chain_ctes(source: str) -> str:
    """
    체인 브랜드 CTE 두 개(chain: 한글 지점명 기준, latin_chain: 영문 같은 이름 반복 기준).
    source에는 top_category·name·latin_stem 컬럼이 있어야 한다.
    브랜드 목록을 매달 데이터에서 다시 뽑는다(하드코딩하면 새 체인을 놓친다).
    """
    generic = ", ".join(_quote(n) for n in GENERIC_LATIN_NAMES)
    return f"""chain AS (
        SELECT stem, count(*) AS n FROM (
            SELECT regexp_replace(trim(name), '\\s+\\S+점$', '') AS stem
            FROM {source}
            WHERE top_category = 'Dining and Drinking'
              AND regexp_matches(trim(name), '\\s\\S+점$')
        )
        WHERE length(stem) >= 2
        GROUP BY stem
        HAVING count(*) >= {CHAIN_MIN_BRANCHES}
    ),
    latin_chain AS (
        SELECT latin_stem AS stem, count(*) AS n
        FROM {source}
        WHERE top_category = 'Dining and Drinking'
          AND regexp_matches(latin_stem, '^[a-z0-9][a-z0-9 &''’.\\-]*$')
          AND length(latin_stem) >= 3
          AND latin_stem NOT IN ({generic})
        GROUP BY latin_stem
        HAVING count(*) >= {CHAIN_MIN_BRANCHES}
    )"""


def stage_korea(conn: duckdb.DuckDBPyConnection) -> str:
    """
    한국 영업중 행을 임시 테이블로 먼저 받는다.

    필터를 Iceberg 스캔에 직접 걸면 DuckDB iceberg 확장(1.4.4)이 리스트 컬럼 조건을
    파일 프루닝 단계로 밀어 넣다가 내부 오류로 죽는다
    ("Expected vector of type LIST, but found vector of type INT32", 2026-10 실측).
    그래서 스캔에는 단순 조건만 걸고 나머지 규칙은 로컬 테이블에서 돌린다(KR 약 160만 행).
    """
    conn.execute(f"""
        CREATE TEMP TABLE kr AS
        SELECT fsq_place_id, name, latitude, longitude, address, locality, region, country,
               date_closed, date_refreshed, website, fsq_category_labels, unresolved_flags
        FROM {TABLE_PATH}
        WHERE country = 'KR' AND date_closed IS NULL
    """)
    log.info("한국 영업중 %d행 적재", conn.execute("SELECT count(*) FROM kr").fetchone()[0])
    return "kr"


def load_destinations(path: str, countries) -> list:
    """여행지 목록에서 countries(None이면 전체)에 속한 것만. 목록에 없는 국가 코드를 요청하면 실패한다."""
    with open(path, encoding="utf-8") as f:
        dests = json.load(f)
    if countries is not None:
        unknown = set(countries) - {d["country"] for d in dests}
        if unknown:
            raise ValueError(f"여행지 목록에 없는 국가: {sorted(unknown)}")
    return [d for d in dests if countries is None or d["country"] in countries]


def create_destination_table(conn: duckdb.DuckDBPyConnection, dests: list, name: str = "dest") -> str:
    conn.execute(f"CREATE OR REPLACE TEMP TABLE {name} "
                 "(country VARCHAR, name VARCHAR, lat DOUBLE, lng DOUBLE, radius_km DOUBLE)")
    conn.executemany(f"INSERT INTO {name} VALUES (?, ?, ?, ?, ?)",
                     [(d["country"], d["name"], d["lat"], d["lng"], d["radiusKm"]) for d in dests])
    return name


def country_boxes(dests: list) -> dict:
    """국가마다 여행지 반경 원을 모두 덮는 범위 하나 (min_lat, max_lat, min_lng, max_lng)."""
    boxes = {}
    for d in dests:
        dlat = d["radiusKm"] / 111.0
        dlng = d["radiusKm"] / (111.0 * math.cos(math.radians(d["lat"])))
        b = boxes.setdefault(d["country"], [90.0, -90.0, 180.0, -180.0])
        b[0], b[1] = min(b[0], d["lat"] - dlat), max(b[1], d["lat"] + dlat)
        b[2], b[3] = min(b[2], d["lng"] - dlng), max(b[3], d["lng"] + dlng)
    return {c: tuple(b) for c, b in boxes.items()}


def stage_overseas(conn: duckdb.DuckDBPyConnection, dests: list) -> str:
    """
    해외 영업중 행을 국가별 범위(country_boxes)로 받는다. 스캔 조건은 단순 비교만 쓴다(stage_korea 참고).
    여행지 반경 배정은 받은 뒤 로컬 테이블에서 한다.
    """
    cond = " OR ".join(
        f"(country = {_quote(c)} AND latitude BETWEEN {b[0]} AND {b[1]} AND longitude BETWEEN {b[2]} AND {b[3]})"
        for c, b in country_boxes(dests).items())
    conn.execute(f"""
        CREATE TEMP TABLE ov AS
        SELECT fsq_place_id, name, latitude, longitude, address, country, date_closed, date_created,
               date_refreshed, website, tel, instagram, facebook_id, fsq_category_labels, unresolved_flags
        FROM {TABLE_PATH}
        WHERE date_closed IS NULL AND ({cond})
    """)
    for country, n in conn.execute("SELECT country, count(*) FROM ov GROUP BY 1 ORDER BY 1").fetchall():
        log.info("해외 영업중 %s %d행 적재", country, n)
    return "ov"


def _starts_with_any(col: str, prefixes: tuple) -> str:
    return _any("starts_with", col, tuple(unicodedata.normalize("NFC", p).lower() for p in prefixes))


def _stem_expr(col: str) -> str:
    """상호에서 브랜드 부분만. 앞머리 괄호(【公式】), 첫 괄호 이하, " - 지점명", 끝의 "…店" 토큰을 차례로 뗀다."""
    return ("trim(regexp_replace(regexp_replace(regexp_replace(regexp_replace("
            f"{col}, '^\\s*[\\(（\\[【][^\\)）\\]】]*[\\)）\\]】]\\s*', ''), "
            "'\\s*[\\(（\\[【].*$', ''), "
            "'\\s+[-–|｜]\\s+.*$', ''), "
            "'\\s+\\S*(店|分店|支店|門市|门市)$', ''))")


def build_scored_query(source: str, dest: str) -> str:
    """
    해외 행을 여행지에 배정하고 상호(stem)와 신호 점수를 붙인 SELECT문.
    갱신 기준 → 역할 → 하위시설 이름 제외 → 여행지 배정 → 점수 순서다.
    """
    haversine = ("2 * 6371 * asin(sqrt(pow(sin(radians(k.latitude - d.lat) / 2), 2) + cos(radians(k.latitude))"
                 " * cos(radians(d.lat)) * pow(sin(radians(k.longitude - d.lng) / 2), 2)))")
    generic = ", ".join(_quote(c) for c in GENERIC_FOOD_CATEGORIES)
    return f"""
    WITH base AS (
        SELECT *,
               fsq_category_labels[1] AS cat,
               split_part(fsq_category_labels[1], ' > ', 1) AS top_category
        FROM {source}
        WHERE country IN (SELECT DISTINCT country FROM {dest})
          AND date_closed IS NULL
          AND coalesce(len(unresolved_flags), 0) = 0
          AND CAST(date_refreshed AS DATE) >= CURRENT_DATE - to_years(
                CASE WHEN split_part(fsq_category_labels[1], ' > ', 1) = 'Dining and Drinking'
                          THEN {OVERSEAS_FRESH_YEARS_DINING}
                     WHEN split_part(fsq_category_labels[1], ' > ', 1) = 'Landmarks and Outdoors'
                          THEN {FRESH_YEARS_LANDMARK}
                     ELSE {FRESH_YEARS_DEFAULT} END)
    ),
    roled AS (
        SELECT *,
               CASE
                 WHEN cat LIKE '%States and Municipalities%' OR cat LIKE '%Residential%' THEN NULL
                 WHEN top_category = 'Dining and Drinking' THEN 'food'
                 WHEN cat LIKE '%Lodging%' THEN 'lodging'
                 WHEN cat LIKE 'Travel and Transportation > Transport Hub%'
                      AND NOT regexp_matches(cat, '(Bus Stop|Platform)') THEN 'transit'
                 WHEN (top_category IN ('Landmarks and Outdoors', 'Arts and Entertainment')
                       OR cat LIKE 'Community and Government > Spiritual Center%')
                      AND NOT regexp_matches(cat, {_quote(NON_TRAVEL_SIGHT_PATTERN)}) THEN 'sight'
                 WHEN cat LIKE 'Retail%'
                      AND regexp_matches(cat, '(Market|Shopping Mall|Department Store|Shopping Plaza|Souvenir|Outlet)')
                      THEN 'market'
               END AS role
        FROM base
    ),
    kept AS (
        SELECT * FROM roled
        WHERE role IS NOT NULL
          AND NOT (role <> 'food' AND regexp_matches(name, {_quote(JUNK_NAME_PATTERN)}))
    ),
    placed AS (
        SELECT k.*, d.name AS dest, {haversine} AS km, d.radius_km
        FROM kept k
        JOIN {dest} d
          ON k.country = d.country
         AND k.latitude BETWEEN d.lat - d.radius_km / 111.0 AND d.lat + d.radius_km / 111.0
         AND k.longitude BETWEEN d.lng - d.radius_km / (111.0 * cos(radians(d.lat)))
                             AND d.lng + d.radius_km / (111.0 * cos(radians(d.lat)))
    ),
    nearest AS (
        SELECT * FROM placed
        WHERE km <= radius_km
        QUALIFY row_number() OVER (PARTITION BY fsq_place_id ORDER BY km / radius_km, dest) = 1
    ),
    named AS (
        -- 베트남어 성조 표기는 조합형·완성형이 섞여 있어 접두어 비교 전에 NFC로 맞춘다.
        SELECT *, lower(nfc_normalize(trim(name))) AS lname FROM nearest
    )
    SELECT *,
           {_stem_expr('lname')} AS stem,
           (CASE WHEN coalesce(tel, '') <> '' THEN 1 ELSE 0 END
            + CASE WHEN coalesce(website, '') <> '' THEN 1 ELSE 0 END
            + CASE WHEN coalesce(instagram, '') <> '' OR facebook_id IS NOT NULL THEN 1 ELSE 0 END
            -- 오래 영업하면서 지금도 갱신되는 곳. 인기도 데이터가 없는 해외 식당의 단골 대용 신호다.
            + CASE WHEN TRY_CAST(date_created AS DATE) <= TRY_CAST(date_refreshed AS DATE) - INTERVAL 5 YEAR THEN 2
                   WHEN TRY_CAST(date_created AS DATE) <= TRY_CAST(date_refreshed AS DATE) - INTERVAL 2 YEAR THEN 1
                   ELSE 0 END
            + CASE WHEN cat IN ({generic}) THEN 0 ELSE 1 END) AS signal
    FROM named
    """


def _cap_expr(role_col: str, total_col: str) -> str:
    """DESTINATION_CAPS를 행마다 적용할 상한 식. 규모가 가장 작은 구간보다 작으면 상한이 없다."""
    cases = " ".join(
        f"WHEN {total_col} >= {n} THEN CASE {role_col} WHEN 'sight' THEN {sight} ELSE {food} END"
        for n, sight, food in DESTINATION_CAPS)
    return f"CASE {cases} ELSE NULL END"


def _selection_ctes(scored: str) -> str:
    """
    체인 규칙 → 여행지 규모별 명소·식음료 상한. 상한 안 순위는 signal이고 같으면 fsq_place_id 해시다.
    상호를 알 수 없는 행(stem 1자 이하)은 브랜드로 묶지 않는다.
    """
    return f"""mass_chain AS (
        SELECT country, stem, count(*) AS n
        FROM {scored}
        WHERE role = 'food' AND length(stem) >= 2
        GROUP BY country, stem
        HAVING count(*) >= {MASS_CHAIN_MIN_BRANCHES}
    ),
    food AS (
        SELECT s.* FROM {scored} s
        WHERE s.role = 'food'
          AND ({_starts_with_any('s.lname', TOURIST_CHAINS)}
               OR (NOT {_starts_with_any('s.lname', GLOBAL_CHAINS)}
                   AND NOT EXISTS (SELECT 1 FROM mass_chain m WHERE m.country = s.country AND m.stem = s.stem)))
        QUALIFY row_number() OVER (
                    PARTITION BY dest, CASE WHEN length(stem) >= 2 THEN stem ELSE fsq_place_id END
                    ORDER BY signal DESC, hash(fsq_place_id)) <= {BRAND_PER_DESTINATION}
    ),
    unchained AS (
        SELECT * FROM {scored} WHERE role <> 'food'
        UNION ALL BY NAME
        SELECT * FROM food
    ),
    ranked AS (
        SELECT *,
               count(*) OVER (PARTITION BY dest) AS dest_total,
               row_number() OVER (PARTITION BY dest, role ORDER BY signal DESC, hash(fsq_place_id)) AS role_rank
        FROM unchained
    ),
    selected AS (
        SELECT * FROM ranked
        WHERE role NOT IN ('sight', 'food')
           OR role_rank <= coalesce({_cap_expr('role', 'dest_total')}, role_rank)
    )"""


_OVERSEAS_OUTPUT = """
    SELECT
        fsq_place_id,
        regexp_replace(name, '[\\r\\n]+', ' ', 'g')                     AS name,
        latitude,
        longitude,
        country,
        dest                                                          AS region,
        cat                                                           AS category,
        regexp_replace(coalesce(address, ''), '[\\r\\n]+', ' ', 'g')    AS address,
        array_to_string(fsq_category_labels, ';')                     AS tags,
        regexp_replace(coalesce(website, ''), '[\\r\\n]+', ' ', 'g')    AS description,
        signal
    FROM selected
    """


def build_overseas_query(source: str, dest: str) -> str:
    """해외 적재 대상 SELECT문. 출력 컬럼은 build_extract_query에 signal을 더한 것이다."""
    return f"WITH scored AS ({build_scored_query(source, dest)}), {_selection_ctes('scored')} {_OVERSEAS_OUTPUT}"


def build_output_query(kr_source: str, scored: str = None) -> str:
    """
    최종 CSV 쿼리. 한국 + 해외(scored는 build_scored_query 결과 테이블).
    해외가 없는 달에도 signal 컬럼을 둬 CSV 스키마를 고정한다.
    """
    query = f"SELECT *, CAST(NULL AS INTEGER) AS signal FROM ({build_extract_query(kr_source)})"
    if scored:
        query += f" UNION ALL BY NAME SELECT * FROM (WITH {_selection_ctes(scored)} {_OVERSEAS_OUTPUT})"
    return query


def overseas_mass_chains(conn: duckdb.DuckDBPyConnection, scored: str) -> list:
    """현지 대형 체인으로 빠진 브랜드 (국가, 상호, 행 수). 허용 목록 누락을 로그로 확인하려고 쓴다."""
    return conn.execute(f"""
        WITH {_selection_ctes(scored)}
        SELECT country, stem, n FROM mass_chain ORDER BY country, n DESC
    """).fetchall()


def extract_countries():
    """
    해외 추출 국가(EXTRACT_COUNTRIES, 쉼표 구분). "all"이면 None(여행지 목록 전체), 없으면 빈 집합(한국만).
    해외 식당은 상한 전 수십만 건이라 월간 실행이 의도 없이 적재하지 않도록 명시해야 켜진다.
    """
    raw = os.environ.get("EXTRACT_COUNTRIES", "").strip()
    if raw.lower() == "all":
        return None
    return {c.strip().upper() for c in raw.split(",") if c.strip()}


def extract(conn: duckdb.DuckDBPyConnection) -> int:
    """한국 주요 도시와 해외 여행지 데이터를 추출하여 CSV로 저장한다."""
    log.info("추출 시작: %s (KR, %d개 도시)", TABLE_PATH, len(CITIES))
    source = stage_korea(conn)

    # 체인 목록은 매달 데이터에서 다시 뽑으므로, 업종 일반명이 새로 걸리지 않았는지 로그로 남긴다.
    chains = detected_chains(conn, source)
    log.info("체인 판정 %d개 브랜드(음식·음료에서 제외): %s", len(chains),
             ", ".join(f"{stem}({n})" for _, stem, n in chains))

    scored = None
    countries = extract_countries()
    if countries is None or countries:
        dests = load_destinations(DESTINATIONS_FILE, countries)
        log.info("해외 추출: %d개 여행지 (%s)", len(dests), ", ".join(sorted({d["country"] for d in dests})))
        overseas = stage_overseas(conn, dests)
        dest = create_destination_table(conn, dests)
        # 체인 로그와 최종 출력이 같은 배정·점수 결과를 읽는다(수백만 행 조인을 한 번만 한다).
        conn.execute(f"CREATE TEMP TABLE ov_scored AS {build_scored_query(overseas, dest)}")
        scored = "ov_scored"
        for country, stem, n in overseas_mass_chains(conn, scored):
            log.info("  현지 대형 체인 제외 %s %s(%d)", country, stem, n)

    # 줄바꿈이 낀 필드는 적재기의 줄 단위 CSV 파서를 깨뜨리므로 쿼리에서 공백으로 바꿔 둔다.
    conn.execute(f"COPY ({build_output_query(source, scored)}) TO '{OUTPUT_FILE}' (HEADER, DELIMITER ',')")

    count = conn.execute(
        f"SELECT COUNT(*) FROM read_csv_auto('{OUTPUT_FILE}')"
    ).fetchone()[0]
    for country, region, n in conn.execute(
        f"SELECT country, region, count(*) FROM read_csv_auto('{OUTPUT_FILE}') GROUP BY 1, 2 ORDER BY 1, 3 DESC"
    ).fetchall():
        log.info("  %s %-18s %7d건", country, region, n)
    log.info("추출 완료: %d건 → %s", count, OUTPUT_FILE)
    return count


def memory_limit() -> str:
    """DUCKDB_MEMORY_LIMIT가 없으면 컨테이너 메모리의 75%. 태스크 크기를 바꿔도 따로 맞출 값이 없다."""
    if os.environ.get("DUCKDB_MEMORY_LIMIT"):
        return os.environ["DUCKDB_MEMORY_LIMIT"]
    for path in ("/sys/fs/cgroup/memory.max", "/sys/fs/cgroup/memory/memory.limit_in_bytes"):
        try:
            with open(path) as f:
                raw = f.read().strip()
        except OSError:
            continue
        if raw.isdigit() and int(raw) < 1 << 50:
            return f"{max(1, int(int(raw) * 0.75) >> 30)}GB"
    return "3GB"


def upload_to_s3(row_count: int) -> str:
    dt = date.today().isoformat()
    s3_key = f"{S3_PREFIX}/dt={dt}/foursquare-places.csv"

    log.info("S3 업로드 시작: s3://%s/%s", S3_BUCKET, s3_key)

    # LocalStack 또는 실제 AWS S3에 업로드
    s3_kwargs = {"region_name": AWS_REGION}
    if AWS_ENDPOINT_URL:
        s3_kwargs["endpoint_url"] = AWS_ENDPOINT_URL
        log.info("LocalStack S3 엔드포인트 사용: %s", AWS_ENDPOINT_URL)

    s3 = boto3.client("s3", **s3_kwargs)
    s3.upload_file(
        OUTPUT_FILE,
        S3_BUCKET,
        s3_key,
        ExtraArgs={
            "ContentType": "text/csv",
            "Metadata": {
                "row-count": str(row_count),
                "extracted-date": dt,
            }
        }
    )
    log.info("S3 업로드 완료: s3://%s/%s (%d건)", S3_BUCKET, s3_key, row_count)
    return s3_key


if __name__ == "__main__":
    try:
        conn = duckdb.connect()
        conn.execute("SET home_directory='/tmp';")
        conn.execute(f"SET memory_limit='{memory_limit()}';")
        conn.execute("SET temp_directory='/tmp/duckdb';")
        conn.execute(f"SET threads={int(os.environ.get('DUCKDB_THREADS', '2'))};")
        connect_iceberg(conn)

        row_count = extract(conn)
        conn.close()

        if row_count == 0:
            log.warning("추출된 데이터가 없습니다. 필터 조건을 확인하세요.")
            exit(1)

        upload_to_s3(row_count)
        log.info("파이프라인 완료")

    except Exception as e:
        log.error("추출 실패: %s", e, exc_info=True)
        exit(1)
