"""
운영 DB 밖 장소 카탈로그(Parquet) 조회 실측. 기준 선별 행을 국가 파일 하나로 쓰고(위도 순 정렬, 작은 행 그룹),
일정 생성이 부를 두 질의의 지연을 잰다.
  근처 검색 : 좌표 둘레 상자(약 2km) + 역할 → 임베딩 코사인 상위 40
  이름 끌어오기 : 이름 키가 같은 행
임베딩은 임의 단위 벡터다. 전수 비교 비용은 값과 상관없어 지연·크기 측정에는 실제와 같다.

실행: python bench/parquet_tail_bench.py --budget-db build/budget/budget.duckdb --out build/tail
"""
import argparse
import json
import os
import random
import statistics
import sys
import time

import duckdb

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from pipeline import candidates  # noqa: E402

ROW_GROUP = 8192
BOX = 0.02
QUERIES = 40
CENTERS = {"Tokyo": (35.6812, 139.7671, 0.25), "Fukuoka": (33.5902, 130.4017, 0.2), "Otaru": (43.19, 141.0, 0.08)}


def write(con, out, dims):
    """기준 선별 행 전체(임베딩 없음)와 도쿄 반경 행(임베딩 포함) 두 파일. 행당 크기를 둘로 나눠 잰다."""
    meta = os.path.join(out, "catalog-meta.parquet")
    con.execute(f"""
        COPY (SELECT source, source_id, name, strip_accents(name_key(name)) AS name_key, lat, lon, role,
                     pool_category AS category, dish, outcome
              FROM base WHERE kept ORDER BY floor(lat / 0.05), lon)
        TO '{meta}' (FORMAT parquet, ROW_GROUP_SIZE {ROW_GROUP}, COMPRESSION zstd)
    """)
    lat, lon, r = CENTERS["Tokyo"]
    vectors = os.path.join(out, "catalog-tokyo-vectors.parquet")
    con.execute(f"""
        COPY (SELECT source, source_id, name, strip_accents(name_key(name)) AS name_key, lat, lon, role,
                     pool_category AS category, dish,
                     list_transform(range({dims}), x -> (random() * 2 - 1)::FLOAT)::FLOAT[{dims}] AS embedding
              FROM base WHERE kept AND abs(lat - {lat}) < {r} AND abs(lon - {lon}) < {r * 1.2}
              ORDER BY floor(lat / 0.05), lon)
        TO '{vectors}' (FORMAT parquet, ROW_GROUP_SIZE {ROW_GROUP}, COMPRESSION zstd)
    """)
    rows = {p: con.execute(f"SELECT count(*) FROM read_parquet('{p}')").fetchone()[0] for p in (meta, vectors)}
    return {"meta_rows": rows[meta], "meta_bytes": os.path.getsize(meta),
            "meta_bytes_per_row": round(os.path.getsize(meta) / rows[meta]),
            "vector_rows": rows[vectors], "vector_bytes": os.path.getsize(vectors),
            "vector_bytes_per_row": round(os.path.getsize(vectors) / rows[vectors])}, meta, vectors


def latency(path, dims, fresh):
    random.seed(7)
    lat0, lon0, r = CENTERS["Tokyo"]
    con = duckdb.connect()
    points = con.execute(f"SELECT lat, lon, name_key FROM read_parquet('{path}') USING SAMPLE {QUERIES} ROWS").fetchall()
    near, named = [], []
    for lat, lon, key in points:
        q = [random.uniform(-1, 1) for _ in range(dims)]
        c = duckdb.connect() if fresh else con
        t = time.perf_counter()
        c.execute(f"""
            SELECT source_id FROM read_parquet('{path}')
            WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? AND role = 'food'
            ORDER BY array_cosine_similarity(embedding::FLOAT[{dims}], ?::FLOAT[{dims}]) DESC LIMIT 40
        """, [lat - BOX / 2, lat + BOX / 2, lon - BOX / 2, lon + BOX / 2, q]).fetchall()
        near.append((time.perf_counter() - t) * 1000)
        t = time.perf_counter()
        c.execute(f"SELECT source_id FROM read_parquet('{path}') WHERE name_key = ?", [key]).fetchall()
        named.append((time.perf_counter() - t) * 1000)
    return {"near_p50": round(statistics.median(near), 1), "near_p95": round(sorted(near)[int(0.95 * len(near)) - 1], 1),
            "name_p50": round(statistics.median(named), 1), "name_p95": round(sorted(named)[int(0.95 * len(named)) - 1], 1)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--budget-db", default="build/budget/budget.duckdb")
    parser.add_argument("--dims", type=int, default=768)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    con = duckdb.connect(args.budget_db, read_only=True, config={"memory_limit": "3GB", "threads": 2,
                                                                  "temp_directory": "build/tmp"})
    candidates.register_functions(con)
    sizes, _, vectors = write(con, args.out, args.dims)
    result = {"sizes": sizes, "warm": latency(vectors, args.dims, fresh=False),
              "fresh_connection": latency(vectors, args.dims, fresh=True)}
    with open(os.path.join(args.out, "parquet-tail-bench.json"), "w") as f:
        json.dump(result, f, indent=1)
    print(json.dumps(result, indent=1))


if __name__ == "__main__":
    main()
