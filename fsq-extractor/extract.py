"""
FSQ OS Places 월간 추출기

Places Portal Iceberg REST Catalog에서 한국/일본 주요 도시 데이터를 추출하여 S3에 저장한다.

NOTE: SQL 컬럼 매핑은 DESCRIBE 결과 확인 후 조정 필요.
      컬럼명은 FSQ 스키마 업데이트에 따라 변경될 수 있음.
"""
import duckdb
import boto3
import os
import logging
from datetime import date

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s"
)
log = logging.getLogger(__name__)

# ── 설정 ──────────────────────────────────────────────────────────────────────
S3_BUCKET   = os.environ.get("S3_BUCKET", "shgtrip-data")
S3_PREFIX   = os.environ.get("S3_PREFIX", "foursquare")
AWS_REGION  = os.environ.get("AWS_REGION", "ap-northeast-2")
OUTPUT_FILE = "/tmp/foursquare-places.csv"

PRIORITY_CITIES = {
    # 한국
    "Seoul", "Busan", "Jeju", "Incheon", "Daegu", "Daejeon",
    "Gwangju", "Suwon", "Gangneung", "Gyeongju", "Jeonju", "Yeosu",
    # 일본
    "Tokyo", "Osaka", "Kyoto", "Fukuoka", "Sapporo",
    "Nagoya", "Yokohama", "Kobe", "Nara", "Hiroshima",
    "Okinawa", "Kanazawa", "Hakone", "Kamakura", "Nikko",
    "Sendai", "Nagasaki", "Kagoshima", "Beppu", "Takayama",
}


def get_fsq_token() -> str:
    """환경변수에서 FSQ 토큰을 읽는다. 로그에 절대 노출하지 않는다."""
    token = os.environ.get("FSQ_ACCESS_TOKEN")
    if not token:
        raise EnvironmentError("FSQ_ACCESS_TOKEN 환경변수가 설정되지 않았습니다.")
    return token


def connect_iceberg(conn: duckdb.DuckDBPyConnection) -> None:
    """Iceberg 카탈로그에 연결한다. 토큰은 로그에 노출하지 않는다."""
    token = get_fsq_token()

    conn.execute("INSTALL httpfs; LOAD httpfs;")
    conn.execute("INSTALL iceberg; LOAD iceberg;")

    # 토큰을 포함한 SQL은 로그에 찍지 않음
    conn.execute(f"""
        CREATE SECRET iceberg_secret (
            TYPE ICEBERG,
            TOKEN '{token}'
        )
    """)
    del token  # 메모리에서 제거

    conn.execute("""
        ATTACH 'places' AS places (
            TYPE iceberg,
            SECRET iceberg_secret,
            ENDPOINT 'https://catalog.h3-hub.foursquare.com/iceberg'
        )
    """)
    log.info("FSQ Iceberg 카탈로그 연결 완료")


def print_schema(conn: duckdb.DuckDBPyConnection) -> list[str]:
    """스키마를 로그로 출력하고 컬럼명 목록을 반환한다."""
    schema_df = conn.execute("DESCRIBE places.datasets.places_os").fetchdf()
    log.info("FSQ OS Places 스키마:\n%s", schema_df.to_string(index=False))
    return schema_df["column_name"].tolist()


def extract(conn: duckdb.DuckDBPyConnection, columns: list[str]) -> int:
    """
    KR/JP 주요 도시 데이터를 추출하여 CSV로 저장한다.

    NOTE: 아래 컬럼 매핑은 DESCRIBE 결과 기준으로 수정 필요.
          FSQ 실제 컬럼명이 확인되면 이 주석을 제거할 것.

    확인 필요 컬럼:
      - region: locality 또는 region 중 어느 것이 실제 컬럼인지 확인
      - category: fsq_category_labels 배열의 첫 번째 원소 (1-based)
      - tags: fsq_category_labels 전체를 ';' 구분 문자열로
      - address: formatted_address 또는 address 확인
      - website: tel 또는 website 확인 (없으면 '' 로 대체)
      - description: FSQ 스키마에 없음 → '' 로 고정
    """
    cities_sql = ", ".join(f"'{c}'" for c in PRIORITY_CITIES)

    # region 컬럼명 결정 (locality 우선, 없으면 region)
    region_col = "locality" if "locality" in columns else "region"
    # address 컬럼명 결정
    address_col = "formatted_address" if "formatted_address" in columns else "address"
    # website 컬럼명 결정
    website_col = "website" if "website" in columns else None
    website_expr = f"COALESCE({website_col}, '')" if website_col else "''"

    log.info("추출 쿼리 컬럼 매핑 — region=%s, address=%s, website=%s",
             region_col, address_col, website_col or "없음(빈 문자열)")

    conn.execute(f"""
        COPY (
            SELECT
                name,
                latitude,
                longitude,
                country,
                {region_col}                          AS region,
                fsq_category_labels[1]                AS category,
                COALESCE({address_col}, '')            AS address,
                array_to_string(fsq_category_labels, ';') AS tags,
                {website_expr}                        AS description
            FROM places.datasets.places_os
            WHERE country IN ('KR', 'JP')
              AND {region_col} IN ({cities_sql})
              AND date_closed IS NULL
              AND date_refreshed >= (CURRENT_DATE - INTERVAL 2 YEAR)
        ) TO '{OUTPUT_FILE}' (HEADER, DELIMITER ',')
    """)

    count = conn.execute(
        f"SELECT COUNT(*) FROM read_csv_auto('{OUTPUT_FILE}')"
    ).fetchone()[0]
    log.info("추출 완료: %d건 → %s", count, OUTPUT_FILE)
    return count


def upload_to_s3(row_count: int) -> str:
    dt = date.today().isoformat()
    s3_key = f"{S3_PREFIX}/dt={dt}/foursquare-places.csv"

    log.info("S3 업로드 시작: s3://%s/%s", S3_BUCKET, s3_key)
    s3 = boto3.client("s3", region_name=AWS_REGION)
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
        connect_iceberg(conn)

        columns = print_schema(conn)  # 스키마 확인 및 컬럼 목록 획득

        row_count = extract(conn, columns)
        conn.close()

        if row_count == 0:
            log.warning("추출된 데이터가 없습니다. 컬럼 매핑 또는 필터 조건을 확인하세요.")
            exit(1)

        upload_to_s3(row_count)
        log.info("파이프라인 완료")

    except Exception as e:
        log.error("추출 실패: %s", e, exc_info=True)
        exit(1)
