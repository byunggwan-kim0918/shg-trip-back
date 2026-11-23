"""공급처 카테고리를 Foursquare 라벨 체계 하나로 옮긴다."""
import csv
import os

_MAP_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "overture_categories.csv")


def load_overture_map(path=_MAP_FILE):
    """{overture_key: fsq_label}. 빈 라벨("")은 여행 장소가 아니라는 뜻이다."""
    with open(path, encoding="utf-8") as f:
        rows = csv.DictReader(line for line in f if not line.startswith("#"))
        mapping = {}
        for row in rows:
            key = row["overture_key"].strip()
            if key in mapping:
                raise ValueError(f"중복 키: {key}")
            mapping[key] = (row["fsq_label"] or "").strip()
        return mapping


def overture_label(hierarchy, primary, mapping):
    """
    taxonomy.hierarchy를 구체적인 항목부터 거슬러 올라가 처음 맞는 라벨을 준다.
    반환: (라벨, 맞은 키). 맞는 키가 없으면 (None, None)이고, 맞은 키의 라벨이 비었으면 ("", 키).
    """
    path = list(hierarchy or [])
    if primary and (not path or path[-1] != primary):
        path.append(primary)
    for key in reversed(path):
        if key in mapping:
            return mapping[key], key
    return None, None
