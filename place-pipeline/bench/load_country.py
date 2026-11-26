"""
나라 하나를 실험 DB에 운영 경로처럼 적재한다. 운영·공용 로컬 DB에는 쓰지 않는다.
  core    : 예산 정책 표(여행지 모드)의 담긴 행 → places. 보강 전 상태로 넣는다(역할·태그·소개는 보강 배치가 채운다).
            한국어 이름은 Wikidata 한글 라벨, 없으면 가나·로마자 규칙 읽기다. 이미 있는 한국어 이름은 보강이 다시 묻지 않아
            읽기를 모르는 행만 보강이 짓는다.
            Wikidata 매칭은 파이프라인 연결 값으로 채운다(매칭 전 행은 보강이 미룬다).
  kana    : 카탈로그 행과 짝지은 Wikidata 대표 항목의 가나 이름(P1814)을 받는다(초당 1회).
  catalog : 여행지 반경 안 기준 선별 행 → PlaceCatalog Parquet(일본어·한국어 이름 키, 규칙 읽기·로마자 이름, 합쳐진 원천 행
            이름 키 alias_keys, 이 행과 합쳐진 원천 행의 원천 id source_ref·member_refs, 임베딩 없음).
            대표 원천 행과 DB 행(core)이 달라도 승격이 member_refs로 그 DB 행을 찾는다(place_source_refs, sync_core가 채운다).
좌표가 틀린 동명 행(mislocated)은 둘 다에서 뺀다.

실행: python bench/load_country.py core --policy budget_50000_55_40_5_dest --database trip_jp
      python bench/load_country.py kana
      python bench/load_country.py catalog --out build/jp/catalog
"""
import argparse
import os
import subprocess
import sys

import duckdb

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from pipeline import budget_policy, candidates, names, readings, wikidata  # noqa: E402
from bench import replay_places  # noqa: E402

CONTAINER = "shg-budget-replay"
# 이 도구가 쓰는 DB는 이름이 이렇게 시작하는 실험 DB뿐이다. core는 places를 비우고 다시 넣는다.
EXPERIMENT_DB_PREFIX = "trip_jp"
COUNTRY = "JP"
KANA_ALL = "build/country/raw/wikidata/japan-kana.parquet"
CORE_COLUMNS = ("id", "name", "address", "latitude", "longitude", "category", "region", "country", "source",
                "fsq_place_id", "wikidata_id", "wikidata_sitelinks", "wikidata_matched_at", "name_ko")


def _links(con):
    con.execute("CREATE OR REPLACE TEMP VIEW wd_links AS SELECT source, source_id, wd_qid AS qid FROM evidence "
                 "WHERE wd_qid IS NOT NULL")
    return "wd_links"


def _readings(con, rows, links):
    return readings.build(con, rows, links, "jp.candidates_all", f"read_parquet('{replay_places.WIKIDATA}')",
                          f"(SELECT * FROM read_parquet('{KANA_ALL}') UNION SELECT * FROM read_parquet('{replay_places.KANA}'))")


def _connect(budget_db, country_db):
    con = duckdb.connect(budget_db, read_only=True, config={"memory_limit": "3GB", "threads": 2,
                                                             "temp_directory": "build/tmp"})
    candidates.register_functions(con)
    con.create_function("exact_key", names.exact_key, ["VARCHAR"], "VARCHAR", null_handling="special")
    con.create_function("bare_key", names.bare_key, ["VARCHAR"], "VARCHAR", null_handling="special")
    con.execute(f"ATTACH '{country_db}' AS jp (READ_ONLY)")
    return con


def core(budget_db, country_db, policy, database, work):
    if not database.startswith(EXPERIMENT_DB_PREFIX):
        raise SystemExit("실험 DB에만 쓴다")
    con = _connect(budget_db, country_db)
    path = os.path.join(work, f"{database}-core.csv")
    os.makedirs(work, exist_ok=True)
    links = _links(con)
    con.execute(f"CREATE OR REPLACE TEMP TABLE core_rows AS SELECT source, source_id FROM {policy} WHERE kept")
    rd = _readings(con, "core_rows", links)
    con.execute(f"""
        COPY (
            SELECT row_number() OVER (ORDER BY k.source, k.source_id) AS id, left(k.name, 255) AS name,
                   coalesce(c.address, '') AS address, k.lat AS latitude, k.lon AS longitude,
                   coalesce(k.pool_category, 'Unknown') AS category, d.region, '{COUNTRY}' AS country, k.source,
                   CASE WHEN k.source = 'fsq' THEN k.source_id END AS fsq_place_id,
                   l.qid AS wikidata_id, w.sitelinks AS wikidata_sitelinks, now() AS wikidata_matched_at,
                   coalesce(rd.wd_ko, rd.reading) AS name_ko
            FROM {policy} k
            JOIN budget_destinations d USING (source, source_id)
            LEFT JOIN {links} l USING (source, source_id)
            LEFT JOIN read_parquet('{replay_places.WIKIDATA}') w ON w.qid = l.qid
            LEFT JOIN {rd} rd USING (source, source_id)
            LEFT JOIN jp.candidates_all c USING (source, source_id)
            WHERE k.kept AND (k.source, k.source_id) NOT IN (SELECT source, source_id FROM mislocated_jp)
        ) TO '{path}' (HEADER, DELIMITER ',')
    """)
    _psql(database, "TRUNCATE places RESTART IDENTITY CASCADE")
    _copy(database, path, f"places ({', '.join(CORE_COLUMNS)})")
    _psql(database, "SELECT setval('places_id_seq', (SELECT max(id) FROM places)); ANALYZE places;")
    return _psql(database, "SELECT count(*) || ' rows, ' || count(DISTINCT region) || ' regions, name_ko ' "
                           "|| count(name_ko) FROM places")


def _catalog_rows(con):
    con.execute("""
        CREATE OR REPLACE TEMP TABLE catalog_rows AS
        SELECT b.source, b.source_id FROM base b JOIN budget_destinations USING (source, source_id)
        WHERE b.kept AND (b.source, b.source_id) NOT IN (SELECT source, source_id FROM mislocated_jp)
    """)
    return "catalog_rows"


def kana(budget_db, country_db):
    """카탈로그 행의 Wikidata 대표 항목 가운데 가나 이름을 아직 안 받은 항목만 받아 KANA_ALL에 합친다."""
    con = _connect(budget_db, country_db)
    rows = _catalog_rows(con)
    con.execute(f"CREATE OR REPLACE TEMP TABLE kr AS SELECT b.* FROM base b JOIN {rows} USING (source, source_id)")
    budget_policy.item_fame(con, "kr", f"read_parquet('{replay_places.WIKIDATA}')",
                            f"(SELECT qid FROM read_parquet('{replay_places.AREA_QIDS}'))")
    qids = [r[0] for r in con.execute("SELECT DISTINCT qid FROM budget_item_fame WHERE best").fetchall()]
    known = set()
    for path in (replay_places.KANA, KANA_ALL):
        if os.path.exists(path):
            known |= {r[0] for r in con.execute(f"SELECT qid FROM read_parquet('{path}')").fetchall()}
    # 앞선 조회에서 가나 이름이 없던 항목도 다시 묻지 않도록 물은 항목 목록을 따로 둔다.
    asked_path = KANA_ALL + ".asked"
    asked = set(open(asked_path).read().split()) if os.path.exists(asked_path) else set()
    todo = sorted(set(qids) - known - asked)
    found = wikidata.kana_names(todo) if todo else {}
    previous = {}
    for path in (replay_places.KANA, KANA_ALL):
        if os.path.exists(path):
            previous.update(dict(con.execute(f"SELECT qid, kana FROM read_parquet('{path}')").fetchall()))
    previous.update(found)
    out = duckdb.connect()
    out.execute("CREATE TABLE k (qid VARCHAR, kana VARCHAR)")
    out.executemany("INSERT INTO k VALUES (?, ?)", sorted(previous.items()))
    out.execute(f"COPY k TO '{KANA_ALL}' (FORMAT parquet)")
    with open(asked_path, "w") as f:
        f.write("\n".join(sorted(asked | set(todo))))
    return {"items": len(qids), "asked": len(todo), "found": len(found), "total_kana": len(previous)}


def catalog(budget_db, country_db, out_dir):
    con = _connect(budget_db, country_db)
    rows = _catalog_rows(con)
    links = _links(con)
    rd = _readings(con, rows, links)
    # 합쳐진 원천 행은 카탈로그에 없어 그 현지어 이름으로는 대표 행을 찾지 못한다(PlaceCatalog.findByExactKey가 별칭으로 본다).
    con.execute("""
        CREATE OR REPLACE TEMP TABLE catalog_aliases AS
        SELECT r.source, r.source_id,
               list_distinct(list(exact_key(c.name)) || list(bare_key(c.name))) AS alias_keys,
               list_sort(list(m.source || ':' || m.source_id)) AS member_refs
        FROM jp.place_clusters r JOIN jp.place_clusters m ON m.cluster_id = r.cluster_id AND NOT m.is_representative
        JOIN jp.candidates_all c ON c.source = m.source AND c.source_id = m.source_id
        WHERE r.is_representative AND r.status = 'merged' GROUP BY 1, 2
    """)
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, f"{COUNTRY.lower()}.parquet")
    con.execute(f"""
        COPY (
            SELECT left(b.name, 255) AS name, exact_key(b.name) AS name_key, bare_key(b.name) AS bare_key, n.name_ko,
                   exact_key(n.name_ko) AS name_ko_key,
                   n.readings, n.latin,
                   coalesce(c.address, '') AS address, b.lat AS latitude, b.lon AS longitude,
                   coalesce(b.pool_category, 'Unknown') AS category, d.region, '{COUNTRY}' AS country, b.source,
                   CASE WHEN b.source = 'fsq' THEN b.source_id END AS fsq_place_id,
                   l.qid AS wikidata_id,
                   w.sitelinks AS wikidata_sitelinks, NULL::VARCHAR AS place_role, NULL::VARCHAR AS cuisine,
                   NULL::VARCHAR AS description, []::VARCHAR[] AS tags, NULL::VARCHAR AS embedding,
                   coalesce(al.alias_keys, []::VARCHAR[]) AS alias_keys, b.source || ':' || b.source_id AS source_ref,
                   coalesce(al.member_refs, []::VARCHAR[]) AS member_refs
            FROM {rows} r JOIN base b USING (source, source_id)
            LEFT JOIN catalog_aliases al USING (source, source_id)
            JOIN budget_destinations d USING (source, source_id)
            LEFT JOIN {links} l USING (source, source_id)
            LEFT JOIN read_parquet('{replay_places.WIKIDATA}') w ON w.qid = l.qid
            LEFT JOIN jp.candidates_all c USING (source, source_id)
            LEFT JOIN (SELECT source, source_id, coalesce(wd_ko, reading) AS name_ko, readings, latin FROM {rd}) n
                USING (source, source_id)
            ORDER BY d.region, floor(b.lat / 0.05), b.lon
        ) TO '{path}' (FORMAT parquet, ROW_GROUP_SIZE 8192, COMPRESSION zstd)
    """)
    stats = duckdb.sql(f"SELECT count(*), count(name_ko), count(DISTINCT region) FROM read_parquet('{path}')").fetchone()
    return {"path": path, "bytes": os.path.getsize(path), "rows": stats[0], "name_ko": stats[1], "regions": stats[2]}


def _psql(database, sql):
    done = subprocess.run(["docker", "exec", "-i", CONTAINER, "psql", "-U", "weShg", "-d", database, "-At", "-v",
                           "ON_ERROR_STOP=1"], input=sql, capture_output=True, text=True)
    if done.returncode:
        raise RuntimeError(f"psql 실패: {done.stderr.strip()[:500]}")
    return done.stdout.strip()


def _copy(database, path, target):
    with open(path, "rb") as f:
        done = subprocess.run(["docker", "exec", "-i", CONTAINER, "psql", "-U", "weShg", "-d", database, "-v",
                               "ON_ERROR_STOP=1", "-c", f"\\copy {target} FROM STDIN WITH CSV HEADER"],
                              stdin=f, capture_output=True)
    if done.returncode:
        raise RuntimeError(f"copy 실패: {done.stderr.decode()[:500]}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["core", "kana", "catalog"])
    parser.add_argument("--budget-db", default="build/budget/budget.duckdb")
    parser.add_argument("--country-db", default="build/country/japan.duckdb")
    parser.add_argument("--policy", default="budget_50000_55_40_5_dest")
    parser.add_argument("--database", default="trip_jp")
    parser.add_argument("--work", default="build/jp")
    parser.add_argument("--out", default="build/jp/catalog")
    args = parser.parse_args()
    if args.command == "core":
        print(core(args.budget_db, args.country_db, args.policy, args.database, args.work))
    elif args.command == "kana":
        print(kana(args.budget_db, args.country_db))
    else:
        print(catalog(args.budget_db, args.country_db, args.out))


if __name__ == "__main__":
    main()
