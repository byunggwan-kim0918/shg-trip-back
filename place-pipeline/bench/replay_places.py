"""
일정 재생(DraftEvalRunner)용 장소 적재. 예산 정책 실험 DB(budget.duckdb)의 기준 선별 행 가운데 재생할 여행지 반경 안의 행을
별도 재생 컨테이너(shg-budget-replay)의 places 표에 넣고, 정책마다 담긴 행을 replay_policy 표에 기록한다.
정책 하나를 재생할 때는 activate()로 그 정책 행만 active로 켠다. 운영·공용 로컬 DB에는 쓰지 않는다.
보강(Haiku)을 돌리지 않으므로 같은 FSQ id의 로컬 운영 복사본 보강 값을 옮기고, 한국어 이름은 Wikidata 라벨과
정답 표(초안 이름 → 일본어 이름)에서 채운다. 모든 정책이 같은 값을 받아 정책 사이 비교에는 치우침이 없다.

실행: python bench/replay_places.py load --budget-db build/budget/budget.duckdb --policies budget_30000,...
      python bench/replay_places.py activate --policy budget_50000
"""
import argparse
import csv
import json
import os
import subprocess
import sys

import duckdb

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from pipeline import candidates  # noqa: E402
from pipeline.metrics import _HAVERSINE  # noqa: E402

CONTAINER = "shg-budget-replay"
SOURCE_CONTAINER = "shg-trip-postgres"
SOURCE_DB = "trip_prodcopy"
DESTINATIONS = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main", "resources", "destinations.json")
REPLAY_DESTINATIONS = ("Tokyo", "Fukuoka", "Sapporo", "Otaru")
CITY_OF_GOLD = {"tokyo": "Tokyo", "fukuoka": "Fukuoka", "sapporo": "Sapporo"}
ENRICHED = ("name_ko", "place_role", "cuisine", "description", "tags", "recommended_duration_minutes", "opening_hours",
            "traveler_fame", "recommended_time_slots", "attributes", "enriched_at", "enrich_version")
COLUMNS = ("id", "name", "address", "latitude", "longitude", "category", "region", "country", "source", "fsq_place_id",
           "wikidata_id", "wikidata_sitelinks") + ENRICHED


def _replay_out(budget_db, country_db, gold_tsv, enriched_csv):
    """재생 여행지 반경 안의 기준 선별 행(replay_out). id는 (source, source_id) 순서라 같은 입력이면 같다."""
    con = duckdb.connect(budget_db, read_only=True)
    candidates.register_functions(con)
    con.execute(f"ATTACH '{country_db}' AS jp (READ_ONLY)")
    dests = [d for d in _destinations() if d["name"] in REPLAY_DESTINATIONS]
    con.execute("CREATE TEMP TABLE dest (name VARCHAR, lat DOUBLE, lon DOUBLE, km DOUBLE)")
    con.executemany("INSERT INTO dest VALUES (?, ?, ?, ?)", [(d["name"], d["lat"], d["lng"], d["radiusKm"]) for d in dests])
    gold = _gold_ko(gold_tsv)
    con.execute("CREATE TEMP TABLE gold_ko (place_key VARCHAR, ko VARCHAR)")
    con.executemany("INSERT INTO gold_ko VALUES (?, ?)", list(gold.items()))
    near = _HAVERSINE.replace("a.", "d.").replace("b.", "s.")
    con.execute(f"""
        CREATE TEMP TABLE replay_rows AS
        WITH s AS (
            SELECT s.source, s.source_id, s.name, s.lat, s.lon, s.pool_category,
                   d.name AS region, {near} / 1000 AS km_from, d.km
            FROM base s JOIN dest d ON abs(s.lat - d.lat) < 0.3 AND abs(s.lon - d.lon) < 0.4
            WHERE s.kept AND s.source <> 'osm'
        )
        SELECT * EXCLUDE (km_from, km) FROM s WHERE km_from <= km
        QUALIFY row_number() OVER (PARTITION BY source, source_id ORDER BY km_from / km) = 1
    """)
    con.execute(f"""
        CREATE TEMP TABLE replay_out AS
        SELECT row_number() OVER (ORDER BY r.source, r.source_id) AS id, r.source, r.source_id,
               left(r.name, 255) AS name, coalesce(c.address, '') AS address, r.lat, r.lon,
               coalesce(r.pool_category, 'Unknown') AS category, r.region,
               CASE WHEN r.source = 'fsq' THEN r.source_id END AS fsq_place_id,
               e.wd_qid AS wikidata_id, e.wd_sitelinks AS wikidata_sitelinks,
               coalesce(p.name_ko, g.ko, e.wd_name_ko) AS name_ko,
               p.* EXCLUDE (fsq_place_id, name_ko)
        FROM replay_rows r
        LEFT JOIN jp.candidates c USING (source, source_id)
        LEFT JOIN evidence e USING (source, source_id)
        LEFT JOIN read_csv('{enriched_csv}', header = true, all_varchar = true) p
            ON p.fsq_place_id = CASE WHEN r.source = 'fsq' THEN r.source_id
                                     ELSE list_filter(c.links, l -> l.source = 'fsq')[1].id END
        LEFT JOIN (SELECT d.source, d.source_id, any_value(k.ko) AS ko FROM draft_places d JOIN gold_ko k USING (place_key)
                   GROUP BY ALL) g USING (source, source_id)
    """)
    return con


def policies(budget_db, country_db, gold_tsv, names, work):
    """적재한 places는 그대로 두고 정책별 행 목록(replay_policy)만 다시 쓴다. 적재 때와 id가 같은지 이름으로 확인한다."""
    con = _replay_out(budget_db, country_db, gold_tsv, os.path.join(work, "prodcopy-enriched.csv"))
    ids_csv = os.path.join(work, "replay-policy.csv")
    unions = " UNION ALL ".join(f"SELECT o.id, '{p}' AS policy FROM replay_out o JOIN {p} t USING (source, source_id) "
                                f"WHERE t.kept" for p in ["base"] + [n for n in names if n != "base"])
    con.execute(f"COPY ({unions}) TO '{ids_csv}' (HEADER, DELIMITER ',')")
    check = os.path.join(work, "replay-names.csv")
    con.execute(f"COPY (SELECT id, name FROM replay_out) TO '{check}' (HEADER, DELIMITER ',')")
    mismatched = _psql_with_copy(check, "CREATE TEMP TABLE c (id BIGINT, name TEXT);", "c (id, name)",
                                 "SELECT count(*) FROM c LEFT JOIN places p USING (id) WHERE p.name IS DISTINCT FROM c.name")
    if mismatched != "0":
        raise RuntimeError(f"적재 때와 id가 다르다: {mismatched}행")
    _psql("TRUNCATE replay_policy")
    _copy(ids_csv, "replay_policy (place_id, policy)")
    return _psql("SELECT policy || ':' || count(*) FROM replay_policy GROUP BY policy ORDER BY policy")


def _psql_with_copy(path, before, target, after):
    """path를 컨테이너에 옮겨 한 연결에서 before → target으로 복사 → after를 돌리고 마지막 결과 줄을 돌려준다."""
    subprocess.run(["docker", "cp", path, f"{CONTAINER}:/tmp/check.csv"], check=True, capture_output=True)
    done = subprocess.run(["docker", "exec", CONTAINER, "psql", "-U", "weShg", "-d", "trip", "-At", "-v", "ON_ERROR_STOP=1",
                           "-c", before, "-c", f"\\copy {target} FROM '/tmp/check.csv' WITH CSV HEADER", "-c", after],
                          capture_output=True, text=True)
    if done.returncode:
        raise RuntimeError(f"확인 실패: {done.stderr[:500]}")
    return done.stdout.strip().splitlines()[-1]


def load(budget_db, country_db, gold_tsv, policies, work):
    os.makedirs(work, exist_ok=True)
    enriched_csv = os.path.join(work, "prodcopy-enriched.csv")
    with open(enriched_csv, "wb") as f:
        subprocess.run(["docker", "exec", SOURCE_CONTAINER, "psql", "-U", "weShg", "-d", SOURCE_DB, "-c",
                        f"\\copy (SELECT fsq_place_id, {', '.join(ENRICHED)} FROM places WHERE country = 'JP' "
                        f"AND fsq_place_id IS NOT NULL AND enriched_at IS NOT NULL) TO STDOUT WITH CSV HEADER"],
                       check=True, stdout=f)
    con = _replay_out(budget_db, country_db, gold_tsv, enriched_csv)
    places_csv = os.path.join(work, "replay-places.csv")
    select = ", ".join({"latitude": "lat", "longitude": "lon", "country": "'JP'"}.get(c, c) for c in COLUMNS)
    con.execute(f"COPY (SELECT {select} FROM replay_out) TO '{places_csv}' (HEADER, DELIMITER ',')")
    ids_csv = os.path.join(work, "replay-policy.csv")
    unions = " UNION ALL ".join(f"SELECT o.id, '{p}' AS policy FROM replay_out o JOIN {p} t USING (source, source_id) "
                                f"WHERE t.kept" for p in policies)
    con.execute(f"COPY ({unions}) TO '{ids_csv}' (HEADER, DELIMITER ',')")
    counts = con.execute("SELECT region, count(*), count(name_ko), count(place_role) FROM replay_out GROUP BY 1").fetchall()
    _psql("TRUNCATE places RESTART IDENTITY CASCADE; DROP TABLE IF EXISTS replay_policy; "
          "CREATE TABLE replay_policy (place_id BIGINT, policy VARCHAR(64), PRIMARY KEY (place_id, policy));")
    _copy(places_csv, f"places ({', '.join(COLUMNS)})")
    _copy(ids_csv, "replay_policy (place_id, policy)")
    _psql("SELECT setval('places_id_seq', (SELECT max(id) FROM places)); ANALYZE places;")
    return counts


def catalog(work, out_dir):
    """재생 DB의 기준 선별 행 전체를 장소 카탈로그 Parquet(PlaceCatalog 열)으로 쓴다. 이름 키는 DB place_name_key로 만든다."""
    os.makedirs(out_dir, exist_ok=True)
    csv_path = os.path.join(work, "catalog.csv")
    with open(csv_path, "wb") as f:
        subprocess.run(["docker", "exec", CONTAINER, "psql", "-U", "weShg", "-d", "trip", "-c",
                        "\\copy (SELECT p.name, place_name_key(p.name) AS name_key, p.name_ko, "
                        "place_name_key(p.name_ko) AS name_ko_key, p.address, p.latitude, p.longitude, p.category, p.region, "
                        "p.country, p.source, p.fsq_place_id, p.wikidata_id, p.wikidata_sitelinks, p.place_role, p.cuisine, "
                        "p.description, array_to_string(p.tags, chr(31)) AS tags, p.embedding::text AS embedding "
                        "FROM places p JOIN replay_policy r ON r.place_id = p.id AND r.policy = 'base') "
                        "TO STDOUT WITH CSV HEADER"], check=True, stdout=f)
    path = os.path.join(out_dir, "jp.parquet")
    con = duckdb.connect()
    con.execute(f"""
        COPY (SELECT * REPLACE (CASE WHEN tags IS NULL OR tags = '' THEN []::VARCHAR[] ELSE string_split(tags, chr(31)) END
                                AS tags, latitude::DOUBLE AS latitude, longitude::DOUBLE AS longitude)
              FROM read_csv('{csv_path}', header = true, all_varchar = true,
                            types = {{'wikidata_sitelinks': 'INTEGER'}})
              ORDER BY region, floor(latitude::DOUBLE / 0.05), longitude::DOUBLE)
        TO '{path}' (FORMAT parquet, ROW_GROUP_SIZE 8192, COMPRESSION zstd)
    """)
    os.remove(csv_path)
    return path, os.path.getsize(path), con.execute(f"SELECT count(*) FROM read_parquet('{path}')").fetchone()[0]


def activate(policy):
    """policy 행과 적재 밖 행(관문 등)만 켠다. policy가 all이면 적재한 행을 모두 켠다."""
    if policy == "all":
        sql = "UPDATE places SET active = true WHERE NOT active"
    else:
        sql = (f"UPDATE places p SET active = (p.id IN (SELECT place_id FROM replay_policy WHERE policy = '{policy}') "
               f"OR p.id NOT IN (SELECT place_id FROM replay_policy)) "
               f"WHERE active IS DISTINCT FROM (p.id IN (SELECT place_id FROM replay_policy WHERE policy = '{policy}') "
               f"OR p.id NOT IN (SELECT place_id FROM replay_policy))")
    _psql(sql + "; ANALYZE places;")
    return _psql("SELECT region || ':' || count(*) FROM places WHERE active GROUP BY region ORDER BY 1")


def _gold_ko(tsv):
    """정답 표의 place_key(도시:첫 일본어 이름) → 첫 한국어 이름."""
    out = {}
    with open(tsv, encoding="utf-8") as f:
        for r in csv.DictReader((line for line in f if not line.startswith("#")), delimiter="\t"):
            if r["kind"] == "place":
                out.setdefault(f"{r['city']}:{r['ja'].split('|')[0]}", r["ko"])
    return out


def _destinations():
    with open(DESTINATIONS, encoding="utf-8") as f:
        data = json.load(f)
    return data if isinstance(data, list) else data["destinations"]


def _psql(sql):
    done = subprocess.run(["docker", "exec", "-i", CONTAINER, "psql", "-U", "weShg", "-d", "trip", "-At", "-v",
                           "ON_ERROR_STOP=1"], input=sql, capture_output=True, text=True)
    if done.returncode:
        raise RuntimeError(f"psql 실패: {done.stderr.strip()[:500]}")
    return done.stdout.strip()


def _copy(path, target):
    with open(path, "rb") as f:
        done = subprocess.run(["docker", "exec", "-i", CONTAINER, "psql", "-U", "weShg", "-d", "trip", "-v",
                               "ON_ERROR_STOP=1", "-c", f"\\copy {target} FROM STDIN WITH CSV HEADER"],
                              stdin=f, capture_output=True)
    if done.returncode:
        raise RuntimeError(f"copy 실패: {done.stderr.decode()[:500]}")


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("load")
    p.add_argument("--budget-db", default="build/budget/budget.duckdb")
    p.add_argument("--country-db", default="build/country/japan.duckdb")
    p.add_argument("--gold", default="eval/jp-draft-places.tsv")
    p.add_argument("--policies", required=True)
    p.add_argument("--work", default="build/replay")
    q = sub.add_parser("policies")
    q.add_argument("--budget-db", default="build/budget/budget.duckdb")
    q.add_argument("--country-db", default="build/country/japan.duckdb")
    q.add_argument("--gold", default="eval/jp-draft-places.tsv")
    q.add_argument("--policies", required=True)
    q.add_argument("--work", default="build/replay")
    k = sub.add_parser("catalog")
    k.add_argument("--work", default="build/replay")
    k.add_argument("--out", default="build/replay/catalog")
    a = sub.add_parser("activate")
    a.add_argument("--policy", required=True)
    args = parser.parse_args()
    if args.command == "load":
        print(load(args.budget_db, args.country_db, args.gold, args.policies.split(","), args.work))
    elif args.command == "policies":
        print(policies(args.budget_db, args.country_db, args.gold, args.policies.split(","), args.work))
    elif args.command == "catalog":
        print(catalog(args.work, args.out))
    else:
        print(activate(args.policy))


if __name__ == "__main__":
    main()
