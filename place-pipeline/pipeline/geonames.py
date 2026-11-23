"""
GeoNames 국가 덤프(CC.txt) → 여행지 후보 CSV. 인구로 거르지 않고 작은 마을·섬·공원·산·호수·해안도 넣는다.
후보는 데이터를 확보·검증하기 전까지 CANDIDATE로만 들어가고, 일정 생성에는 쓰지 않는다.
덤프 형식: https://download.geonames.org/export/dump/readme.txt (탭 구분 19열)
"""
import csv
import re

# GeoNames feature code → 여행지 종류. 개별 장소(사찰·성)는 여행지가 아니라 장소 데이터라 넣지 않는다.
KIND_BY_CODE = {
    **{c: "town" for c in ("PPL", "PPLA", "PPLA2", "PPLA3", "PPLA4", "PPLA5", "PPLC", "PPLCD", "PPLG", "PPLL",
                          "PPLF", "PPLR", "PPLS")},
    "ISL": "island", "ISLS": "island", "ATOL": "island",
    "PRK": "park", "RESN": "park", "RES": "park", "RESF": "park", "RESW": "park",
    "CST": "coast", "BAY": "coast", "BAYS": "coast", "CAPE": "coast", "PEN": "coast",
    "MT": "mountain", "MTS": "mountain", "VLC": "mountain", "PK": "mountain",
    "VAL": "valley", "GRGE": "valley",
    "LK": "lake", "LKS": "lake", "LKC": "lake", "LKSC": "lake",
    "SPNT": "hot_spring",
}

_HANGUL = re.compile(r"[가-힣]")
MAX_ALIASES = 20

COLUMNS = ("destination_key", "country", "kind", "name", "name_ko", "aliases", "latitude", "longitude",
           "population", "admin1", "source", "source_id")


def parse_line(line):
    """덤프 한 줄 → 후보 dict, 여행지 종류가 아니면 None."""
    f = line.rstrip("\n").split("\t")
    if len(f) < 19:
        return None
    kind = KIND_BY_CODE.get(f[7])
    if kind is None:
        return None
    alternates = [a for a in f[3].split(",") if a] if f[3] else []
    hangul = [a for a in alternates if _HANGUL.search(a)]
    aliases = [a for a in alternates if a != f[1]][:MAX_ALIASES]
    return {
        "destination_key": f"{f[8]}:gn:{f[0]}",
        "country": f[8],
        "kind": kind,
        "name": f[1],
        "name_ko": hangul[0] if hangul else "",
        "aliases": "|".join(a.replace("|", " ") for a in aliases),
        "latitude": f[4],
        "longitude": f[5],
        "population": f[14] or "0",
        "admin1": f[10],
        "source": "geonames",
        "source_id": f[0],
    }


def convert(dump_paths, out_path):
    """@return 종류별 행 수"""
    counts = {}
    with open(out_path, "w", encoding="utf-8", newline="") as out:
        w = csv.DictWriter(out, fieldnames=COLUMNS)
        w.writeheader()
        for path in dump_paths:
            with open(path, encoding="utf-8") as f:
                for line in f:
                    row = parse_line(line)
                    if row is None:
                        continue
                    w.writerow(row)
                    counts[row["kind"]] = counts.get(row["kind"], 0) + 1
    return counts
