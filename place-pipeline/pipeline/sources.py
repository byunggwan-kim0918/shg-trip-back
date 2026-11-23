"""공급처별 원본 접근. 원본은 손대지 않고 필요한 열만 그대로 받아 스냅샷으로 남긴다."""
import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Source:
    name: str
    license: str
    terms_url: str


OVERTURE = Source("overture", "CDLA-Permissive-2.0 / Apache-2.0 (행별 sources.license)",
                  "https://docs.overturemaps.org/guides/places/")
FSQ = Source("fsq", "Apache-2.0 (NOTICE 보존)", "https://opensource.foursquare.com/places-notice-txt/")
SOURCES = {s.name: s for s in (OVERTURE, FSQ)}

OVERTURE_BUCKET = "s3://overturemaps-us-west-2/release"
FSQ_CATALOG_ENDPOINT = "https://catalog.h3-hub.foursquare.com/iceberg"
FSQ_TABLE = "places.datasets.places_os"

# 원본에서 받는 열. 공통 후보로 옮길 때 쓰는 열과 출처 추적에 필요한 열만 고른다.
OVERTURE_COLUMNS = ("id, version, names, taxonomy, basic_category, confidence, operating_status, "
                    "addresses, websites, phones, brand, sources, bbox")
FSQ_COLUMNS = ("fsq_place_id, name, latitude, longitude, address, locality, region, country, "
               "date_created, date_refreshed, date_closed, fsq_category_labels, unresolved_flags, website, tel")


def overture_path(release):
    if not release or "/" in release or ".." in release:
        raise ValueError(f"잘못된 Overture 릴리스: {release!r}")
    return f"{OVERTURE_BUCKET}/{release}/theme=places/type=place/*"


def connect_overture(con):
    con.execute("INSTALL httpfs; LOAD httpfs;")
    con.execute("SET s3_region='us-west-2';")
    # 범위마다 같은 파일 수백 개의 footer를 다시 받지 않게 한 연결 안에서 메타데이터를 재사용한다.
    con.execute("SET parquet_metadata_cache = true; SET enable_http_metadata_cache = true;")


def connect_fsq(con):
    """FSQ_ACCESS_TOKEN(Places Portal 개인 토큰)으로 Iceberg 카탈로그에 붙는다. 토큰은 로그·예외에 남기지 않는다."""
    token = os.environ.get("FSQ_ACCESS_TOKEN", "").strip()
    if not token:
        raise RuntimeError("FSQ_ACCESS_TOKEN이 없다")
    if "'" in token:
        raise RuntimeError("FSQ_ACCESS_TOKEN 형식이 잘못됐다")
    con.execute("INSTALL httpfs; LOAD httpfs; INSTALL iceberg; LOAD iceberg;")
    con.execute(f"CREATE OR REPLACE SECRET fsq_iceberg (TYPE ICEBERG, TOKEN '{token}')")
    del token
    con.execute("DETACH DATABASE IF EXISTS places")
    con.execute(f"ATTACH 'places' AS places (TYPE iceberg, SECRET fsq_iceberg, ENDPOINT '{FSQ_CATALOG_ENDPOINT}')")


def overture_select(release, where):
    return f"SELECT {OVERTURE_COLUMNS} FROM read_parquet('{overture_path(release)}', hive_partitioning=1) WHERE {where}"


def fsq_select(where):
    return f"SELECT {FSQ_COLUMNS} FROM {FSQ_TABLE} WHERE {where}"
