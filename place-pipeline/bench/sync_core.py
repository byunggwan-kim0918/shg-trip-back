"""
파이프라인을 다시 돌린 결과를 이미 적재·보강된 실험 DB core 행에 맞춘다. 장소 id는 지우거나 새로 매기지 않고, 보강을 다시
돌리지 않는다. 운영·공용 로컬 DB에는 쓰지 않는다. 기본은 dry-run(건수·사례만), --apply로 쓴다.
  core id ↔ 원천 행 : core를 적재한 예산 DB(--core-budget-db)로 load_country.core와 같은 순서로 다시 매기고, 이름이 모두
                      같을 때만 진행한다. places에는 Overture 원천 id가 없다.
  새 결과에서 행의 상태(새 예산 DB --budget-db, 새 나라 DB --country-db)
    kept · out_of_budget : 그대로 둔다. 예산 밖이어도 선별을 통과한 장소다.
    merged               : 같은 장소 묶음의 대표가 아닌 구성원이다. 대표가 core에 있으면 merged_into_id로 잇는다(SAME).
                           없으면 이 행이 그 장소를 맡는다. 대표가 FSQ 행이고 이 행에 fsq_place_id가 없으면 대표의 id를
                           달아 카탈로그 승격이 이 행을 돌려주게 하고, 그 밖의 대표 행은 core_twins.parquet에 적어
                           카탈로그에서 뺀다. 그런 구성원이 여럿이면 가장 작은 id가 맡고 나머지는 그 행에 잇는다.
                           대표가 선별에서 빠졌으면 구성원도 끈다.
    held · not_selected · mislocated : 풀에서 빠졌거나 선별·좌표 검사를 통과하지 못한다. 'policy'로 끈다(카탈로그 승격이
                           되살리지 않는다).
  QID     : 새 증거의 연결(구성원은 묶음 대표의 연결)과 다르면 wikidata_id·wikidata_sitelinks를 바꾼다.
  name_ko : 한글이 아니거나, 이름이 다른 항목(부속 시설이 물려받은 본체)의 한국어 라벨이면 새 연결의 한글 라벨 → 규칙
            읽기로 바꾸고 없으면 비운다. 보강이 지은 이름은 이 장소 자체인 항목의 가나 이름(P1814) 읽기와 소리가 전혀
            다를 때만 바꾼다.
--apply는 바꿀 행의 이전 값을 <work>/sync-backup-<시각>.csv로 남기고 한 트랜잭션으로 쓴다. 바뀐 한국어 이름은 무효화·임베딩
배치(SPRING_PROFILES_ACTIVE=batch, 보강 끔)를 돌려야 임베딩에 반영된다. 장소 연결 배치(PlaceLinkJob)는 판정 캐시에 없는 합침을
풀기 때문에 이 도구를 쓴 실험 DB에서는 돌리지 않는다.

실행: python bench/sync_core.py --core-budget-db build/budget/budget.duckdb --budget-db build/budget-v2/budget.duckdb \
        --country-db build/country/japan-v2.duckdb [--apply]
"""
import argparse
import difflib
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from bench import load_country, replay_places  # noqa: E402
from pipeline import korean_names  # noqa: E402

_GENERIC = re.compile(r"(공원|정원|신사|신궁|사찰|해변|폭포|박물관|미술관|시장|거리|상점가|다리|호수|연못|온천|역|성|강|산|섬|만|사)$")
# 보강 이름과 규칙 읽기의 소리 비율이 이보다 낮으면 다른 읽기다. 한 음절만 달라도(가와히라/가비라) 이 위다.
SAME_READING_RATIO = 0.5
# 같은 이름의 신사·사찰이 흔해 이 거리 밖 항목은 이름이 같아도 다른 곳일 수 있다.
SAME_PLACE_METERS = 200
DEACTIVATED_STATES = ("held", "not_selected", "mislocated")
_BACKUP_COLUMNS = ("id", "active", "deactivated_at", "deactivated_reason", "merged_into_id", "merge_relation",
                   "wikidata_id", "wikidata_sitelinks", "name_ko", "fsq_place_id")
# core는 FSQ 행에만 fsq_place_id를 단다. Overture 행은 비어 있어 대표 FSQ 행의 id를 받을 수 있다.
_TAKES_REP_FSQ_ID = "state = 'merged' AND rep_core_id IS NULL AND rep_source = 'fsq' AND source = 'overture'"


def _sounds(text):
    words = [_GENERIC.sub("", w) for w in re.split(r"\s+", text or "") if w]
    return "".join(w for w in words if re.search("[가-힣]", w))


def differs(haiku, reading):
    a, b = _sounds(haiku), _sounds(reading)
    if not a or not b:
        return False
    return difflib.SequenceMatcher(None, a, b).ratio() < SAME_READING_RATIO


def states(con, core_budget_db, policy):
    """core_states(id, source, source_id, name, state, rep_source, rep_id, rep_core_id). con은 새 예산 DB, jp는 새 나라 DB다."""
    con.execute(f"ATTACH '{core_budget_db}' AS old (READ_ONLY)")
    con.execute(f"""CREATE OR REPLACE TEMP TABLE core_map AS
        SELECT row_number() OVER (ORDER BY k.source, k.source_id) AS id, k.source, k.source_id,
               translate(left(k.name, 255), chr(10) || chr(13) || chr(9), '   ') AS name
        FROM old.{policy} k JOIN old.budget_destinations d USING (source, source_id)
        WHERE k.kept AND (k.source, k.source_id) NOT IN (SELECT source, source_id FROM old.mislocated_jp)""")
    con.execute(f"""CREATE OR REPLACE TEMP TABLE core_states AS
        WITH kept AS (SELECT source, source_id FROM {policy} WHERE kept
                      AND (source, source_id) NOT IN (SELECT source, source_id FROM mislocated_jp)),
        members AS (
            SELECT c.source, c.source_id, rep.source AS rep_source, rep.source_id AS rep_id
            FROM jp.place_clusters c
            JOIN jp.place_clusters rep ON rep.cluster_id = c.cluster_id AND rep.is_representative
            WHERE c.status = 'merged' AND NOT c.is_representative
        )
        SELECT m.id, m.source, m.source_id, m.name,
               CASE WHEN (m.source, m.source_id) IN (SELECT * FROM kept) THEN 'kept'
                    WHEN p.rep_id IS NOT NULL THEN 'merged'
                    WHEN b.source IS NULL THEN 'held'
                    WHEN (m.source, m.source_id) IN (SELECT source, source_id FROM mislocated_jp) THEN 'mislocated'
                    WHEN NOT b.kept THEN 'not_selected'
                    ELSE 'out_of_budget' END AS state,
               p.rep_source, p.rep_id, rc.id AS rep_core_id
        FROM core_map m
        LEFT JOIN members p USING (source, source_id)
        LEFT JOIN base b USING (source, source_id)
        LEFT JOIN core_map rc ON rc.source = p.rep_source AND rc.source_id = p.rep_id""")
    # 대표가 선별에서 빠졌으면 그 장소가 빠진 것이라 구성원도 끈다.
    con.execute("""UPDATE core_states s SET state = 'not_selected'
        WHERE state = 'merged' AND NOT EXISTS (SELECT 1 FROM base b WHERE b.source = s.rep_source
                                                AND b.source_id = s.rep_id AND b.kept)""")
    # 대표가 core 밖인데 구성원이 여럿이면 가장 작은 id가 그 장소를 맡고 나머지는 그 행에 잇는다.
    con.execute("""UPDATE core_states s SET rep_core_id = x.keeper
        FROM (SELECT rep_source, rep_id, min(id) AS keeper FROM core_states
              WHERE state = 'merged' AND rep_core_id IS NULL GROUP BY 1, 2 HAVING count(*) > 1) x
        WHERE s.state = 'merged' AND s.rep_core_id IS NULL AND s.rep_source = x.rep_source AND s.rep_id = x.rep_id
          AND s.id <> x.keeper""")
    con.execute("DETACH old")
    return "core_states"


def plan(con, database):
    db = load_country._psql(database, "SELECT id || E'\\t' || translate(name, E'\\n\\r\\t', '   ') || E'\\t' "
                                      "|| coalesce(name_ko, '') || E'\\t' || coalesce(wikidata_id, '') || E'\\t' || active "
                                      "FROM places")
    con.execute("CREATE OR REPLACE TEMP TABLE dbp (id BIGINT, name VARCHAR, name_ko VARCHAR, qid VARCHAR, active BOOLEAN)")
    con.executemany("INSERT INTO dbp VALUES (?, ?, ?, ?, ?)",
                    [(int(a), b, c or None, d or None, e in ("t", "true"))
                     for a, b, c, d, e in ((line.split("\t") + ["", "", ""])[:5] for line in db.split("\n") if line)])
    mismatched = con.execute("SELECT count(*) FROM core_states s JOIN dbp d USING (id) WHERE s.name <> d.name").fetchone()[0]
    missing = con.execute("SELECT count(*) FROM core_states WHERE id NOT IN (SELECT id FROM dbp)").fetchone()[0]
    if mismatched or missing:
        raise RuntimeError(f"core id와 원천 행이 맞지 않는다(이름 다름 {mismatched}건, 없는 id {missing}건)")
    # 구성원 행은 묶음 대표가 가진 연결을 쓴다. 대표의 증거에 묶음 전체의 Wikidata 근거가 모여 있다.
    con.execute("""CREATE OR REPLACE TEMP TABLE sync_links AS
        SELECT s.source, s.source_id, e.wd_qid AS qid
        FROM core_states s JOIN evidence e
          ON e.source = coalesce(s.rep_source, s.source) AND e.source_id = coalesce(s.rep_id, s.source_id)
        WHERE s.state IN ('kept', 'out_of_budget', 'merged') AND s.rep_core_id IS NULL AND e.wd_qid IS NOT NULL""")
    con.execute("CREATE OR REPLACE TEMP TABLE sync_rows AS SELECT source, source_id FROM core_states "
                "WHERE state IN ('kept', 'out_of_budget', 'merged') AND rep_core_id IS NULL")
    rd = load_country._readings(con, "sync_rows", "sync_links")
    con.create_function("korean", korean_names.from_kana, ["VARCHAR"], "VARCHAR", null_handling="special")
    rows = con.execute(f"""
        SELECT d.id, d.name, d.name_ko, d.qid, l.qid, w.sitelinks, ow.ko, r.wd_ko, r.reading,
               exact_key(regexp_replace(d.name, '[（(][^）)]*[）)]', '', 'g')) = exact_key(w.ja) AS item_is_row,
               exact_key(regexp_replace(split_part(d.name, ' / ', 1), '[（(][^）)]*[）)]', '', 'g'))
                   IN (exact_key(ow.ja), exact_key(ow.en)) AS old_item_is_row,
               korean(k.kana) AS item_reading,
               6371000 * 2 * asin(sqrt(pow(sin(radians(w.lat - c.lat) / 2), 2) + cos(radians(c.lat))
                   * cos(radians(w.lat)) * pow(sin(radians(w.lon - c.lon) / 2), 2))) AS item_meters,
               coalesce(starts_with(c.category, 'Dining and Drinking') OR starts_with(c.category, 'Retail'), false) AS store
        FROM core_states s JOIN dbp d USING (id) JOIN sync_rows USING (source, source_id)
        LEFT JOIN sync_links l USING (source, source_id)
        LEFT JOIN read_parquet('{replay_places.WIKIDATA}') w ON w.qid = l.qid
        LEFT JOIN read_parquet('{replay_places.WIKIDATA}') ow ON ow.qid = d.qid
        LEFT JOIN {rd} r USING (source, source_id)
        LEFT JOIN jp.candidates_all c USING (source, source_id)
        LEFT JOIN (SELECT * FROM read_parquet('{load_country.KANA_ALL}')
                   UNION SELECT * FROM read_parquet('{replay_places.KANA}')) k ON k.qid = l.qid
    """).fetchall()
    qid_updates, name_updates = [], []
    for (pid, name, name_ko, old_qid, new_qid, sitelinks, old_ko, wd_ko, reads, item_is_row, old_item_is_row, item_reading,
         item_meters, store) in rows:
        if old_qid != new_qid:
            qid_updates.append((pid, new_qid, sitelinks))
        reading = wd_ko or reads
        reason = None
        if name_ko and not re.search("[가-힣]", name_ko):
            reason = "not_hangul"
        elif name_ko and old_qid != new_qid and old_ko and name_ko == old_ko and not old_item_is_row:
            # 항목이 같은 장소의 다른 중복 행으로 옮겨 갔으면 이 행의 이름은 그대로 맞다.
            reason = "inherited_label"
        elif (name_ko and item_is_row and item_reading and not store and not wd_ko and name_ko != item_reading
              and item_meters is not None and item_meters <= SAME_PLACE_METERS
              and differs(name_ko, item_reading)):
            # 항목이 이 장소 자체이고 그 항목의 가나 이름(P1814)으로 읽었을 때만 보강 이름보다 믿는다. 영어 라벨은 번역·약칭이 섞인다.
            reading, reason = item_reading, "reading_differs"
        if reason and (reading or reason != "reading_differs"):
            name_updates.append((pid, name, name_ko, reading, reason))
    deactivate = [r[0] for r in con.execute(
        f"SELECT s.id FROM core_states s JOIN dbp d USING (id) WHERE d.active AND s.state IN {DEACTIVATED_STATES}").fetchall()]
    merge = con.execute("SELECT id, rep_core_id FROM core_states WHERE state = 'merged' AND rep_core_id IS NOT NULL").fetchall()
    fsq_ids = con.execute(f"SELECT id, rep_id FROM core_states WHERE {_TAKES_REP_FSQ_ID}").fetchall()
    return qid_updates, name_updates, deactivate, merge, fsq_ids


def apply(database, work, qid_updates, name_updates, deactivate, merge, fsq_ids):
    ids = sorted({u[0] for u in qid_updates} | {u[0] for u in name_updates} | set(deactivate) | {m[0] for m in merge}
                 | {f[0] for f in fsq_ids})
    if not ids:
        return "바꿀 행 없음"
    backup = os.path.join(work, f"sync-backup-{time.strftime('%Y%m%d-%H%M%S')}.csv")
    dump = load_country._psql(database, f"COPY (SELECT {', '.join(_BACKUP_COLUMNS)} FROM places WHERE id IN "
                                        f"({', '.join(map(str, ids))}) ORDER BY id) TO STDOUT WITH CSV HEADER")
    with open(backup, "w", encoding="utf-8") as f:
        f.write(dump + "\n")
    sql = ["BEGIN;"]
    for pid, qid, sitelinks in qid_updates:
        sitelinks_sql = "NULL" if sitelinks is None else int(sitelinks)
        sql.append(f"UPDATE places SET wikidata_id = {_lit(qid)}, wikidata_sitelinks = {sitelinks_sql} WHERE id = {pid};")
    for pid, _, _, reading, _ in name_updates:
        sql.append(f"UPDATE places SET name_ko = {_lit(reading)} WHERE id = {pid};")
    if deactivate:
        sql.append("UPDATE places SET active = false, deactivated_at = now(), deactivated_reason = 'policy', "
                   f"updated_at = now() WHERE active AND id IN ({', '.join(map(str, deactivate))});")
    for pid, fsq_id in fsq_ids:
        sql.append(f"UPDATE places SET fsq_place_id = {_lit(fsq_id)}, updated_at = now() WHERE id = {pid} AND fsq_place_id IS NULL;")
    for pid, rep in merge:
        sql.append(f"UPDATE places SET merged_into_id = {rep}, merge_relation = 'SAME', updated_at = now() WHERE id = {pid};")
    sql.append("COMMIT;")
    load_country._psql(database, "\n".join(sql))
    return f"적용, 이전 값 {backup}"


def write_twins(con, work):
    path = os.path.join(work, "core_twins.parquet")
    con.execute(f"""COPY (SELECT rep_source AS source, rep_id AS source_id, id AS core_id FROM core_states
                         WHERE state = 'merged' AND rep_core_id IS NULL AND NOT ({_TAKES_REP_FSQ_ID}))
                     TO '{path}' (FORMAT parquet)""")
    return path


def _lit(v):
    return "NULL" if v is None else "'" + str(v).replace("'", "''") + "'"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--core-budget-db", required=True, help="core를 적재한 예산 DB")
    parser.add_argument("--budget-db", required=True, help="다시 돌린 예산 DB")
    parser.add_argument("--country-db", required=True, help="다시 돌린 나라 DB")
    parser.add_argument("--policy", default="budget_50000_55_40_5_dest")
    parser.add_argument("--database", default="trip_jp")
    parser.add_argument("--work", default="build/jp")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    if not args.database.startswith(load_country.EXPERIMENT_DB_PREFIX):
        raise SystemExit("실험 DB에만 쓴다")
    con = load_country._connect(args.budget_db, args.country_db)
    states(con, args.core_budget_db, args.policy)
    print("상태", con.execute("SELECT state, count(*), count(rep_core_id) FROM core_states GROUP BY 1 ORDER BY 2 DESC").fetchall())
    qid_updates, name_updates, deactivate, merge, fsq_ids = plan(con, args.database)
    reasons = {}
    for u in name_updates:
        reasons[u[4]] = reasons.get(u[4], 0) + 1
    print(f"끄기 {len(deactivate)}건, 대표로 잇기 {len(merge)}건, 대표 FSQ id 달기 {len(fsq_ids)}건, QID 변경 {len(qid_updates)}건, "
          f"한국어 이름 변경 {len(name_updates)}건 {reasons}")
    for u in name_updates[:40]:
        print("  ", u[4], "|", u[1], "|", u[2], "→", u[3])
    os.makedirs(args.work, exist_ok=True)
    for u in qid_updates[:80]:
        print("   QID", u)
    print("카탈로그에서 뺄 대표 행", write_twins(con, args.work))
    if args.apply:
        print(apply(args.database, args.work, qid_updates, name_updates, deactivate, merge, fsq_ids))


if __name__ == "__main__":
    main()
