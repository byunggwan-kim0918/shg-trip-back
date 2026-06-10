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

TABLE_PATH = "places.datasets.places_os"

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


def extract(conn: duckdb.DuckDBPyConnection) -> int:
    """KR/JP 주요 도시 데이터를 추출하여 CSV로 저장한다."""
    cities_sql = ", ".join(f"'{c}'" for c in PRIORITY_CITIES)

    log.info("추출 시작: %s (KR/JP, %d개 도시)", TABLE_PATH, len(PRIORITY_CITIES))

    conn.execute(f"""
        COPY (
            SELECT
                name,
                latitude,
                longitude,
                country,
                locality                              AS region,
                fsq_category_labels[1]                AS category,
                COALESCE(address, '')                  AS address,
                array_to_string(fsq_category_labels, ';') AS tags,
                COALESCE(website, '')                  AS description
            FROM {TABLE_PATH}
            WHERE country IN ('KR', 'JP')
              AND locality IN ({cities_sql})
              AND date_closed IS NULL
              AND CAST(date_refreshed AS DATE) >= (CURRENT_DATE - INTERVAL 2 YEAR)
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
