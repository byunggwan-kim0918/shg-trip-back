"""수집 범위(bbox·국가). 범위는 원본을 얼마나 받을지만 정하고, 여행지 판정과는 무관하다."""
import csv
import re
from dataclasses import dataclass

_COUNTRY = re.compile(r"^[A-Z]{2}$")
_NAME = re.compile(r"^[a-z0-9_]+$")


@dataclass(frozen=True)
class Area:
    name: str
    lon0: float
    lon1: float
    lat0: float
    lat1: float
    country: str = ""

    def __post_init__(self):
        if not _NAME.match(self.name):
            raise ValueError(f"범위 이름은 소문자·숫자·밑줄만: {self.name!r}")
        if not (-180 <= self.lon0 < self.lon1 <= 180 and -90 <= self.lat0 < self.lat1 <= 90):
            raise ValueError(f"잘못된 bbox: {self}")
        if self.country and not _COUNTRY.match(self.country):
            raise ValueError(f"국가 코드는 ISO 3166-1 alpha-2: {self.country!r}")

    def overture_where(self):
        clause = (f"bbox.xmin BETWEEN {self.lon0} AND {self.lon1} "
                  f"AND bbox.ymin BETWEEN {self.lat0} AND {self.lat1}")
        if self.country:
            # 자연 지형은 주소가 없는 행이 많아 국가가 비어 있어도 bbox 안이면 받는다.
            clause += f" AND coalesce(addresses[1].country, '{self.country}') = '{self.country}'"
        return clause

    def fsq_where(self):
        clause = (f"longitude BETWEEN {self.lon0} AND {self.lon1} "
                  f"AND latitude BETWEEN {self.lat0} AND {self.lat1}")
        if self.country:
            clause = f"country = '{self.country}' AND " + clause
        return clause


def load_areas(path):
    """TSV: name, lon0, lon1, lat0, lat1, country(비워도 됨). '#'로 시작하는 줄은 건너뛴다."""
    with open(path, encoding="utf-8") as f:
        rows = csv.DictReader((line for line in f if not line.startswith("#")), delimiter="\t")
        return [Area(r["name"], float(r["lon0"]), float(r["lon1"]), float(r["lat0"]), float(r["lat1"]),
                     (r.get("country") or "").strip()) for r in rows]
