"""
FSQ OS Places 월간 추출기

Places Portal Iceberg REST Catalog에서 한국/일본 주요 도시 데이터를 추출하여 S3에 저장한다.
공식 문서: https://docs.foursquare.com/data-products/docs/places-os-data-schema
테이블 경로: places.datasets.places_os
"""
import duckdb
import boto3
import os
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


def extract(conn: duckdb.DuckDBPyConnection) -> int:
    """한국 주요 도시 데이터를 추출하여 CSV로 저장한다."""
    log.info("추출 시작: %s (KR, %d개 도시)", TABLE_PATH, len(CITIES))
    source = stage_korea(conn)

    # 체인 목록은 매달 데이터에서 다시 뽑으므로, 업종 일반명이 새로 걸리지 않았는지 로그로 남긴다.
    chains = detected_chains(conn, source)
    log.info("체인 판정 %d개 브랜드(음식·음료에서 제외): %s", len(chains),
             ", ".join(f"{stem}({n})" for _, stem, n in chains))

    # 줄바꿈이 낀 필드는 적재기의 줄 단위 CSV 파서를 깨뜨리므로 쿼리에서 공백으로 바꿔 둔다.
    conn.execute(f"COPY ({build_extract_query(source)}) TO '{OUTPUT_FILE}' (HEADER, DELIMITER ',')")

    count = conn.execute(
        f"SELECT COUNT(*) FROM read_csv_auto('{OUTPUT_FILE}')"
    ).fetchone()[0]
    for region, n in conn.execute(
        f"SELECT region, count(*) FROM read_csv_auto('{OUTPUT_FILE}') GROUP BY region ORDER BY 2 DESC"
    ).fetchall():
        log.info("  %-10s %7d건", region, n)
    log.info("추출 완료: %d건 → %s", count, OUTPUT_FILE)
    return count


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
        conn.execute("SET memory_limit='3GB';")
        conn.execute("SET temp_directory='/tmp/duckdb';")
        conn.execute("SET threads=2;")
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
