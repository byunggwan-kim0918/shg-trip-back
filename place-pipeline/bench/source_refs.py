"""
DB 장소 행 ↔ 파이프라인 원천 행(Overture GERS id·FSQ id) 매핑을 만든다. 장소 id는 DB마다 달라서 숫자 id가 아니라 적재 때 원천 행에서
옮긴 값으로 찾는다. 운영을 그대로 복사한 로컬 DB에도 같은 방법으로 돌린다. DB에는 쓰지 않는다.
  fsq   : fsq_place_id가 FSQ 원천 행 id다.
  tuple : 출처·이름(앞 255자)·주소가 같고 좌표 차이가 SAME_COORDINATE도 안이다(PlaceCatalogPromoter와 같은 기준).
  원천 행 하나에 DB 행이 여럿 맞으면 잇지 않고 ambiguous로 센다. DB 행 하나에 이름·주소·좌표가 모두 같은 원천 행이 여럿 맞으면
  (출처 안 중복 기록) 모두 그 행에 잇는다.
결과: --out CSV(id, place_source, source, source_id, rule)와 요약. sync_core --core-refs가 이 CSV를 core 대응표로 쓰고 place_source_refs에 기록한다.
--check-budget-db를 주면 그 예산 DB로 적재한 순서(load_country.core)와 견준다(로컬 실험 DB 검증용).

실행: python bench/source_refs.py --database trip_jp --country-db build/identity/v5/country.duckdb --out build/identity/refs.csv \
        [--check-budget-db build/budget/budget.duckdb]
"""
import argparse
import json
import os
import sys

import duckdb

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from bench import load_country  # noqa: E402

# PlaceCatalogPromoter.SAME_COORDINATE와 같다. DB 좌표는 원천 좌표를 소수 8자리로 반올림한 값이다.
SAME_COORDINATE = 1e-6
_DB_COLUMNS = ("id", "source", "fsq_place_id", "name", "address", "latitude", "longitude")


def derive(con, database, country):
    """source_refs(id, source, source_id, rule)와 요약. con에는 나라 DB가 jp로 붙어 있다."""
    db = load_country._psql(database, f"""
        SELECT id || E'\\t' || coalesce(source, '') || E'\\t' || coalesce(fsq_place_id, '') || E'\\t'
               || translate(name, E'\\n\\r\\t', '   ') || E'\\t' || translate(address, E'\\n\\r\\t', '   ') || E'\\t'
               || latitude || E'\\t' || longitude
        FROM places WHERE country = '{country}'""")
    con.execute("CREATE OR REPLACE TEMP TABLE ref_places (id BIGINT, source VARCHAR, fsq_place_id VARCHAR, name VARCHAR, "
                "address VARCHAR, latitude DOUBLE, longitude DOUBLE)")
    con.executemany("INSERT INTO ref_places VALUES (?, ?, ?, ?, ?, ?, ?)",
                    [(int(a), b or None, c or None, d, e, float(f), float(g))
                     for a, b, c, d, e, f, g in (line.split("\t") for line in db.split("\n") if line)])
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE ref_matches AS
        SELECT p.id, p.source AS place_source, c.source, c.source_id, 'fsq' AS rule
        FROM ref_places p JOIN jp.candidates_all c ON c.source = 'fsq' AND c.source_id = p.fsq_place_id
        UNION ALL
        SELECT p.id, p.source, c.source, c.source_id, 'tuple'
        FROM ref_places p JOIN jp.candidates_all c
          ON c.source = p.source AND translate(left(c.name, 255), chr(10) || chr(13) || chr(9), '   ') = p.name
         AND translate(coalesce(c.address, ''), chr(10) || chr(13) || chr(9), '   ') = p.address
         AND abs(c.lat - p.latitude) < {SAME_COORDINATE} AND abs(c.lon - p.longitude) < {SAME_COORDINATE}
    """)
    # 원천 행 하나는 한 장소에만 잇는다. 같은 원천 행에 DB 행이 여럿이면 어느 쪽인지 몰라 잇지 않는다.
    # 같은 원천 행이 fsq·tuple로 같은 장소에 두 번 맞는 것은 하나로 센다.
    con.execute("""
        CREATE OR REPLACE TEMP TABLE source_refs AS
        WITH m AS (SELECT id, place_source, source, source_id, min(rule) AS rule FROM ref_matches GROUP BY 1, 2, 3, 4)
        SELECT * FROM m QUALIFY count(DISTINCT id) OVER (PARTITION BY source, source_id) = 1
    """)
    summary = {
        "places": con.execute("SELECT count(*) FROM ref_places").fetchone()[0],
        "refs": dict(con.execute("SELECT rule, count(*) FROM source_refs GROUP BY 1").fetchall()),
        "mapped_places": con.execute("SELECT count(DISTINCT id) FROM source_refs").fetchone()[0],
        "ambiguous_places": con.execute("SELECT count(DISTINCT id) FROM ref_matches WHERE id NOT IN "
                                        "(SELECT id FROM source_refs)").fetchone()[0],
        "unmatched_by_source": dict(con.execute(
            "SELECT coalesce(source, '?'), count(*) FROM ref_places WHERE id NOT IN (SELECT id FROM ref_matches) "
            "GROUP BY 1").fetchall()),
    }
    return "source_refs", summary


def check_load_order(con, budget_db, policy):
    """load_country.core 적재 순서로 매긴 id ↔ 원천 행과 견준다. 반환은 (같음, 다름, 매핑에 없음)."""
    con.execute(f"ATTACH '{budget_db}' AS old (READ_ONLY)")
    con.execute(f"""CREATE OR REPLACE TEMP TABLE load_order AS
        SELECT row_number() OVER (ORDER BY k.source, k.source_id) AS id, k.source, k.source_id
        FROM old.{policy} k JOIN old.budget_destinations d USING (source, source_id)
        WHERE k.kept AND (k.source, k.source_id) NOT IN (SELECT source, source_id FROM old.mislocated_jp)""")
    same, other, missing = con.execute("""
        SELECT count(*) FILTER (WHERE r.id = o.id), count(*) FILTER (WHERE r.id IS NOT NULL AND r.id <> o.id),
               count(*) FILTER (WHERE r.id IS NULL)
        FROM load_order o LEFT JOIN source_refs r ON r.source = o.source AND r.source_id = o.source_id""").fetchone()
    con.execute("DETACH old")
    return same, other, missing


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--database", required=True, help="장소 DB(운영 복사본이면 그 로컬 DB)")
    parser.add_argument("--country-db", required=True, help="이 장소를 적재한 원천 행이 든 나라 DB(candidates_all)")
    parser.add_argument("--country", default="JP")
    parser.add_argument("--out", required=True)
    parser.add_argument("--check-budget-db", help="load_country.core로 적재한 예산 DB. 적재 순서 매핑과 견준다")
    parser.add_argument("--policy", default="budget_50000_55_40_5_dest")
    args = parser.parse_args()
    if not args.database.startswith(load_country.EXPERIMENT_DB_PREFIX):
        raise SystemExit("로컬 실험 DB·운영 복사본에서만 읽는다")
    con = duckdb.connect(config={"memory_limit": "4GB", "threads": 4})
    con.execute(f"ATTACH '{args.country_db}' AS jp (READ_ONLY)")
    table, summary = derive(con, args.database, args.country)
    if args.check_budget_db:
        summary["load_order"] = dict(zip(("same", "other", "missing"),
                                         check_load_order(con, args.check_budget_db, args.policy)))
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    con.execute(f"COPY (SELECT * FROM {table} ORDER BY id, source, source_id) TO '{args.out}' (HEADER)")
    print(json.dumps(summary, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
