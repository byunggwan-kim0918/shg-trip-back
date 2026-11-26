"""
파이프라인을 다시 돌린 결과를 이미 적재·보강된 실험 DB core 행에 맞춘다. 장소 id는 지우거나 새로 매기지 않고, 보강을 다시
돌리지 않는다. 운영·공용 로컬 DB에는 쓰지 않는다. 기본은 dry-run(건수·사례만), --apply로 쓴다.
  core id ↔ 원천 행 : --core-refs(bench/source_refs.py가 FSQ id·적재 때 원천 값으로 만든 매핑)로 잇는다. 장소 하나에 원천 행이
                      여럿이면 그 장소 출처의 행 → 새 선별에 담긴 행 → 풀 행 → 작은 원천 id 순으로 하나를 고른다. 없으면 core를
                      적재한 예산 DB(--core-budget-db)로 load_country.core와 같은 순서로 다시 매긴다(로컬 실험 DB). 둘 다 주면
                      두 매핑이 다를 때 멈춘다. 어느 쪽이든 이름이 모두 같을 때만 진행한다.
  원천 id 매핑 : --core-refs의 행과 넣은 본체의 원천 행을 place_source_refs에 잇는다(있는 매핑은 그대로 둔다). 카탈로그 승격이
                 대표 원천 행과 core 행이 달라도 구성원 원천 id로 core 행을 돌려준다. --core-refs를 쓰면 매핑된 행 전부(앞서 넣은
                 본체·승격 행 포함)가 core 행이고, 대응표 밖 활성 행이 UNMAPPED_MAX_SHARE를 넘으면 멈춘다.
  새 결과에서 행의 상태(새 예산 DB --budget-db, 새 나라 DB --country-db)
    kept · out_of_budget : 그대로 둔다. 예산 밖이어도 선별을 통과한 장소다.
    merged               : 같은 장소 묶음의 대표가 아닌 구성원이다. 대표가 core에 있으면 merged_into_id로 잇는다(SAME).
                           없으면 이 행이 그 장소를 맡는다. 대표가 FSQ 행이고 이 행에 fsq_place_id가 없으면 대표의 id를
                           단다. 카탈로그 승격은 대표 원천 행의 구성원 원천 id로 이 행을 돌려준다. 그런 구성원이 여럿이면
                           가장 작은 id가 맡고 나머지는 그 행에 잇는다.
                           대표가 선별에서 빠졌으면 구성원도 끈다.
    held · not_selected · mislocated : 풀에서 빠졌거나 선별·좌표 검사를 통과하지 못한다. 'policy'로 끈다(카탈로그 승격이
                           되살리지 않는다).
    needs_selection      : 풀에는 있지만 선별 표에 없다(다른 묶음 구성원이던 행이 새 병합에서 따로 남는다). 선별을 다시
                           돌릴 때까지 그대로 둔다.
  QID     : 새 증거의 연결(구성원은 묶음 대표의 연결)과 다르면 wikidata_id·wikidata_sitelinks를 바꾼다.
  name_ko : 바꾸면 새 연결의 한글 라벨 → 규칙 읽기로 바꾼다. 둘 다 없으면 무효화 배치의 깨진 이름 처리와 같이 이름을 비우고
            보강을 다시 묻게 한다(비우기만 하면 보강된 행은 다시 보강되지 않아 이름 없이 남는다).
            not_korean      : 적재 때 파이프라인이 넣은 이름(--core-csv의 name_ko)이 한국어 표시 이름 검사를 통과하지 못한다.
                              보강이 지은 깨진 이름은 바꾸지 않고 haiku_broken으로 세기만 한다. 한글이 있으면 무효화
                              배치가 다시 묻고, 한글이 없으면 보강 쪽에서 다뤄야 한다.
            inherited_label : 이름이 다른 항목(부속 시설이 물려받은 본체)의 한국어 라벨이다.
            wrong_link_name : 적재 때 이어졌다가 지금 규칙으로 끊긴 항목의 이름(한국어 라벨·가나 읽기, 그 항목과 본체 행
                              이름의 한자음)이다. 본체 QID를 단 채 보강된 부속 행은 본체 이름을 받는다(鹿苑寺 方丈 → 금각사).
            reading_differs : 보강이 지은 이름이 이 장소 자체인 항목의 가나 이름(P1814) 읽기와 소리가 전혀 다르다.
  본체    : --areas를 주면 언어판 PROMOTE_MIN_SITELINKS개 이상 항목에 이어진 선별 행 가운데 core 어느 행도 그 항목을 갖지
            않는 행을 새 장소 행으로 넣는다(보강·임베딩 없음, 지명 항목 제외). 부속 행만 core에 있고 본체가 카탈로그에만
            있으면 이름 연결이 부속 행을 본체로 고른다. 넣은 행 id는 <work>/sync-promoted-<시각>.csv에 남긴다.
--apply는 바꿀 행의 이전 값을 <work>/sync-backup-<시각>.csv로 남기고 한 트랜잭션으로 쓴다. --write-sql은 같은 변경을 운영에서
psql -f로 돌릴 스크립트로만 쓴다(실행 시점 백업, 복사본과 지문이 다르면 중단, 넣은 행 id 기록). 바뀐 한국어 이름은 무효화·임베딩
배치(SPRING_PROFILES_ACTIVE=batch, 보강 끔)를 돌려야 임베딩에 반영된다. 대표로 잇는 합침은 판정 캐시에 basis PIPELINE으로도
남겨(V72 이후 스키마) 장소 연결 배치(PlaceLinkJob)가 풀지 않는다. 이번 묶음에 없는 옛 PIPELINE 판정은 지우고(이전 판정은
<work>/sync-verdicts-<시각>.csv), 연결 배치가 다른 곳·딸린 곳으로 판정한 쌍은 잇지 않는다. V72 이전 jar로 링크 배치를 돌리면
PIPELINE 판정을 지문 불일치로 보고 다시 물어 합침을 푼다.

실행: python bench/sync_core.py --core-budget-db build/budget/budget.duckdb --budget-db build/identity/v3/budget.duckdb \
        --country-db build/identity/v3/country.duckdb --core-csv build/jp/trip_jp-core.csv \
        --areas build/identity/japan-area-qids.parquet --wikidata build/country/raw/wikidata/japan-v2.parquet \
        [--apply | --write-sql apply.sql]
"""
import argparse
import difflib
import hashlib
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from bench import load_country, replay_places  # noqa: E402
from pipeline import korean_names  # noqa: E402
from pipeline.names import bare_key, korean_label  # noqa: E402

_GENERIC = re.compile(r"(공원|정원|신사|신궁|사찰|해변|폭포|박물관|미술관|시장|거리|상점가|다리|호수|연못|온천|역|성|강|산|섬|만|사)$")
# 보강 이름과 규칙 읽기의 소리 비율이 이보다 낮으면 다른 읽기다. 한 음절만 달라도(가와히라/가비라) 이 위다.
SAME_READING_RATIO = 0.5
# 같은 이름의 신사·사찰이 흔해 이 거리 밖 항목은 이름이 같아도 다른 곳일 수 있다.
SAME_PLACE_METERS = 200
DEACTIVATED_STATES = ("held", "not_selected", "mislocated")
# 일정 초안이 이름으로 부르는 유명 명소의 하한. 그 밑은 생성 중 카탈로그 승격으로 충분하다.
PROMOTE_MIN_SITELINKS = 10
# PlaceCatalogPromoter.SAME_COORDINATE와 같다. 넣은 값으로 넣은 행을 다시 찾는다.
SAME_COORDINATE = 1e-6
# 대응표 밖 활성 행은 끄기·QID·이름 동기화에서 빠진다. 파이프라인 밖 행(공항 게이트웨이)과 DB 안 완전 중복만 남아야 한다.
UNMAPPED_MAX_SHARE = 0.01
_INSERT_COLUMNS = ("name", "address", "latitude", "longitude", "category", "region", "country", "source", "fsq_place_id",
                   "wikidata_id", "wikidata_sitelinks", "wikidata_matched_at", "name_ko")
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


def core_map(con, core_budget_db, policy, core_refs=None):
    """core_map(id, source, source_id, name). core_refs가 있으면 원천 id 매핑으로, 없으면 적재 순서로 만든다."""
    if core_budget_db:
        con.execute(f"ATTACH '{core_budget_db}' AS old (READ_ONLY)")
        con.execute(f"""CREATE OR REPLACE TEMP TABLE load_order_map AS
            SELECT row_number() OVER (ORDER BY k.source, k.source_id) AS id, k.source, k.source_id,
                   translate(left(k.name, 255), chr(10) || chr(13) || chr(9), '   ') AS name
            FROM old.{policy} k JOIN old.budget_destinations d USING (source, source_id)
            WHERE k.kept AND (k.source, k.source_id) NOT IN (SELECT source, source_id FROM old.mislocated_jp)""")
        con.execute("DETACH old")
    if not core_refs:
        con.execute("CREATE OR REPLACE TEMP TABLE core_map AS SELECT * FROM load_order_map")
        return "core_map"
    con.execute(f"CREATE OR REPLACE TEMP TABLE core_refs AS SELECT * FROM read_csv('{core_refs}', header = true, "
                "columns = {'id': 'BIGINT', 'place_source': 'VARCHAR', 'source': 'VARCHAR', 'source_id': 'VARCHAR', "
                "'rule': 'VARCHAR'})")
    # 대표 FSQ id를 받은 Overture 행은 FSQ 원천 행에도 이어지지만 그 장소를 적재한 원천 행은 Overture 행이다.
    con.execute(f"""CREATE OR REPLACE TEMP TABLE core_map AS
        SELECT r.id, r.source, r.source_id, translate(left(c.name, 255), chr(10) || chr(13) || chr(9), '   ') AS name
        FROM core_refs r JOIN jp.candidates_all c USING (source, source_id)
        QUALIFY row_number() OVER (PARTITION BY r.id ORDER BY r.source IS NOT DISTINCT FROM r.place_source DESC,
            (r.source, r.source_id) IN (SELECT source, source_id FROM {policy} WHERE kept) DESC,
            (r.source, r.source_id) IN (SELECT source, source_id FROM jp.evidence) DESC, r.source, r.source_id) = 1""")
    unmapped = con.execute("SELECT count(*) FROM (SELECT DISTINCT id FROM core_refs) WHERE id NOT IN (SELECT id FROM core_map)"
                           ).fetchone()[0]
    if unmapped:
        print(f"매핑에 있으나 원천 행을 못 찾은 장소 {unmapped}건(sync에서 빠짐)")
    if core_budget_db:
        missing = con.execute("SELECT count(*) FROM load_order_map WHERE id NOT IN (SELECT id FROM core_map)").fetchone()[0]
        print(f"적재 순서 매핑에 있으나 원천 id 매핑에 없는 core 행 {missing}건(sync에서 빠짐)")
        differ = con.execute("""SELECT count(*) FROM load_order_map o JOIN core_map m USING (id)
                                WHERE (o.source, o.source_id) <> (m.source, m.source_id)""").fetchone()[0]
        if differ:
            raise RuntimeError(f"원천 id 매핑과 적재 순서 매핑이 다르다({differ}건)")
    return "core_map"


def states(con, core_budget_db, policy, core_refs=None):
    """core_states(id, source, source_id, name, state, rep_source, rep_id, rep_core_id). con은 새 예산 DB, jp는 새 나라 DB다."""
    core_map(con, core_budget_db, policy, core_refs)
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
                    WHEN b.source IS NULL AND (m.source, m.source_id) IN (SELECT source, source_id FROM jp.evidence)
                         THEN 'needs_selection'
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
    return "core_states"


def plan(con, database, core_csv):
    db = load_country._psql(database, "SELECT id || E'\\t' || translate(name, E'\\n\\r\\t', '   ') || E'\\t' "
                                      "|| coalesce(name_ko, '') || E'\\t' || coalesce(wikidata_id, '') || E'\\t' || active "
                                      "|| E'\\t' || coalesce(merged_into_id::text, '') || E'\\t' || coalesce(fsq_place_id, '') "
                                      "FROM places")
    con.execute("CREATE OR REPLACE TEMP TABLE dbp (id BIGINT, name VARCHAR, name_ko VARCHAR, qid VARCHAR, active BOOLEAN, "
                "merged_into_id BIGINT, fsq_place_id VARCHAR)")
    con.executemany("INSERT INTO dbp VALUES (?, ?, ?, ?, ?, ?, ?)",
                    [(int(a), b, c or None, d or None, e in ("t", "true"), int(f) if f else None, g or None)
                     for a, b, c, d, e, f, g in ((line.split("\t") + [""] * 7)[:7] for line in db.split("\n") if line)])
    _load_db_refs(con, database)
    # 앞선 sync가 넣은 본체처럼 원천 id가 이미 이어진 행은 core가 아니어도 같은 장소를 찾을 수 있다.
    active = con.execute("SELECT count(*) FROM dbp WHERE active").fetchone()[0]
    outside = con.execute("SELECT count(*) FROM dbp WHERE active AND id NOT IN (SELECT id FROM core_states) "
                          "AND id NOT IN (SELECT place_id FROM db_refs)").fetchone()[0]
    print(f"core 대응표·원천 id 매핑 밖 활성 행 {outside}건 / {active}건(동기화하지 않음)")
    if active and outside > active * UNMAPPED_MAX_SHARE:
        raise RuntimeError(f"core 대응표·원천 id 매핑 밖 활성 행이 {UNMAPPED_MAX_SHARE:.0%}를 넘는다. 원천 id 매핑을 먼저 확인한다")
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
    con.execute(f"CREATE OR REPLACE TEMP TABLE core_load AS SELECT id::BIGINT AS id, name, nullif(wikidata_id, '') AS load_qid, "
                f"nullif(name_ko, '') AS load_ko FROM read_csv('{core_csv}', header = true, all_varchar = true)")
    wrong_load = con.execute("SELECT count(*) FROM core_load l JOIN dbp d USING (id) "
                             "WHERE translate(l.name, chr(10) || chr(13) || chr(9), '   ') <> d.name").fetchone()[0]
    if wrong_load:
        raise RuntimeError(f"--core-csv가 이 DB를 적재한 파일이 아니다(이름 다름 {wrong_load}건)")
    lost_names = _lost_item_names(con)
    rows = con.execute(f"""
        SELECT d.id, d.name, d.name_ko, d.qid, l.qid, w.sitelinks, ow.ko, wikidata_korean_label(ow.ko, d.name), r.wd_ko,
               r.reading,
               exact_key(regexp_replace(d.name, '[（(][^）)]*[）)]', '', 'g')) = exact_key(w.ja) AS item_is_row,
               exact_key(regexp_replace(split_part(d.name, ' / ', 1), '[（(][^）)]*[）)]', '', 'g'))
                   IN (exact_key(ow.ja), exact_key(ow.en)) AS old_item_is_row,
               korean(k.kana) AS item_reading,
               6371000 * 2 * asin(sqrt(pow(sin(radians(w.lat - c.lat) / 2), 2) + cos(radians(c.lat))
                   * cos(radians(w.lat)) * pow(sin(radians(w.lon - c.lon) / 2), 2))) AS item_meters,
               coalesce(starts_with(c.category, 'Dining and Drinking') OR starts_with(c.category, 'Retail'), false) AS store,
               cl.load_qid, cl.load_ko
        FROM core_states s JOIN dbp d USING (id) JOIN sync_rows USING (source, source_id)
        LEFT JOIN core_load cl USING (id)
        LEFT JOIN sync_links l USING (source, source_id)
        LEFT JOIN read_parquet('{replay_places.WIKIDATA}') w ON w.qid = l.qid
        LEFT JOIN read_parquet('{replay_places.WIKIDATA}') ow ON ow.qid = d.qid
        LEFT JOIN {rd} r USING (source, source_id)
        LEFT JOIN jp.candidates_all c USING (source, source_id)
        LEFT JOIN (SELECT * FROM read_parquet('{load_country.KANA_ALL}')
                   UNION SELECT * FROM read_parquet('{replay_places.KANA}')) k ON k.qid = l.qid
    """).fetchall()
    qid_updates, name_updates, haiku_broken = [], [], []
    for (pid, name, name_ko, old_qid, new_qid, sitelinks, old_ko, old_ko_label, wd_ko, reads, item_is_row, old_item_is_row,
         item_reading, item_meters, store, load_qid, load_ko) in rows:
        if old_qid != new_qid:
            qid_updates.append((pid, new_qid, sitelinks))
        reading = wd_ko or reads
        reason = None
        if name_ko and korean_label(name_ko, name) is None:
            if name_ko == load_ko:
                reason = "not_korean"
            else:
                haiku_broken.append((pid, name, name_ko))
                continue
        elif name_ko and old_qid != new_qid and old_ko and name_ko in (old_ko, old_ko_label) and not old_item_is_row:
            # 항목이 같은 장소의 다른 중복 행으로 옮겨 갔으면 이 행의 이름은 그대로 맞다.
            reason = "inherited_label"
        elif name_ko and load_qid and load_qid != new_qid and _names_item(name_ko, name, lost_names.get(load_qid)):
            reason = "wrong_link_name"
        elif (name_ko and item_is_row and item_reading and not store and not wd_ko and name_ko != item_reading
              and item_meters is not None and item_meters <= SAME_PLACE_METERS
              and differs(name_ko, item_reading)):
            # 항목이 이 장소 자체이고 그 항목의 가나 이름(P1814)으로 읽었을 때만 보강 이름보다 믿는다. 영어 라벨은 번역·약칭이 섞인다.
            reading, reason = item_reading, "reading_differs"
        if reason and reading != name_ko and (reading or reason != "reading_differs"):
            name_updates.append((pid, name, name_ko, reading, reason))
    deactivate = [r[0] for r in con.execute(
        f"SELECT s.id FROM core_states s JOIN dbp d USING (id) WHERE d.active AND s.state IN {DEACTIVATED_STATES}").fetchall()]
    # 이미 적용한 합침·FSQ id는 다시 계획하지 않는다. 같은 sync를 두 번 돌려도 바뀌는 행이 없어야 한다.
    # 꺼졌거나 다른 곳에 합쳐진 대표에는 잇지 않는다. 구성원이 그 장소를 맡는다.
    merge = con.execute("SELECT s.id, s.rep_core_id FROM core_states s JOIN dbp d USING (id) "
                        "JOIN dbp r ON r.id = s.rep_core_id AND r.active AND r.merged_into_id IS NULL "
                        "WHERE s.state = 'merged' AND d.merged_into_id IS DISTINCT FROM s.rep_core_id").fetchall()
    # 장소 연결 배치가 다른 곳·딸린 곳으로 판정한 쌍은 파이프라인 판정으로 덮지 않는다. 그 판정이 묶음을 다시 푼다.
    judged = {tuple(map(int, line.split("\t"))) for line in load_country._psql(
        database, "SELECT low_id || E'\\t' || high_id FROM place_pair_verdicts "
                  "WHERE relation <> 'SAME' AND basis <> 'PIPELINE'").split("\n") if line}
    conflicts = [m for m in merge if (min(m), max(m)) in judged]
    merge = [m for m in merge if (min(m), max(m)) not in judged]
    if conflicts:
        print(f"연결 배치가 다른 곳으로 판정해 잇지 않는 묶음 구성원 {len(conflicts)}건: {conflicts[:20]}")
    fsq_ids = con.execute(f"SELECT id, rep_id FROM core_states JOIN dbp d USING (id) "
                          f"WHERE {_TAKES_REP_FSQ_ID} AND d.fsq_place_id IS NULL").fetchall()
    return qid_updates, name_updates, deactivate, merge, fsq_ids, haiku_broken


def _lost_item_names(con):
    """적재 때 이어졌다가 새 연결에서 그 행과 끊긴 항목마다 (한국어 이름들, 한자 이름들). 한자 이름은 항목 라벨과 그 항목에
    이어진 본체 행 이름·괄호 병기다."""
    rows = con.execute(f"""
        WITH lost AS (
            SELECT DISTINCT cl.load_qid AS qid FROM core_load cl JOIN core_states s USING (id)
            LEFT JOIN sync_links l USING (source, source_id)
            WHERE cl.load_qid IS NOT NULL AND cl.load_qid IS DISTINCT FROM l.qid
        ),
        bodies AS (
            SELECT e.wd_qid AS qid, list(DISTINCT c.name) AS names FROM evidence e JOIN lost ON lost.qid = e.wd_qid
            JOIN jp.candidates_all c USING (source, source_id) GROUP BY 1
        )
        SELECT lost.qid, wikidata_korean_label(w.ko, w.ja), korean(k.kana), w.ja, w.en, b.names
        FROM lost JOIN read_parquet('{replay_places.WIKIDATA}') w USING (qid)
        LEFT JOIN bodies b USING (qid)
        LEFT JOIN (SELECT * FROM read_parquet('{load_country.KANA_ALL}')
                   UNION SELECT * FROM read_parquet('{replay_places.KANA}')) k USING (qid)
    """).fetchall()
    out = {}
    for qid, ko, kana_ko, ja, en, names in rows:
        han = [ja] + [x for n in (names or []) for x in [n] + re.findall(r"[(（]([^)）]*)[)）]", n)]
        keys = {bare_key(x) for x in [ja, en] + list(names or []) if x}
        out[qid] = ({x for x in (ko, kana_ko) if x}, [h for h in han if h], keys)
    return out


def _names_item(name_ko, row_name, item):
    """이 행이 끊긴 항목의 부속 행(이름이 항목·본체 이름과 다름)이고 name_ko가 그 항목의 이름이다."""
    if not item:
        return False
    korean, han, keys = item
    compact = name_ko.replace(" ", "")
    if bare_key(row_name) in keys or korean_names.reads_as_hanja(name_ko, row_name):
        return False
    return any(compact == k.replace(" ", "") for k in korean) or any(korean_names.reads_as_hanja(name_ko, h) for h in han)


def promotions(con, areas):
    """core 어느 행도 갖지 않는 유명 항목의 본체 선별 행. 열은 _INSERT_COLUMNS와 source, source_id다."""
    con.execute(f"CREATE OR REPLACE TEMP TABLE promote_areas AS SELECT qid FROM read_parquet('{areas}')")
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE promote_rows AS
        SELECT l.source, l.source_id, l.qid, w.sitelinks
        FROM jp.wikidata_links l JOIN read_parquet('{replay_places.WIKIDATA}') w USING (qid)
        JOIN base s USING (source, source_id) JOIN budget_destinations d USING (source, source_id)
        WHERE s.kept AND w.sitelinks >= {PROMOTE_MIN_SITELINKS} AND l.qid NOT IN (SELECT qid FROM promote_areas)
          AND (l.source, l.source_id) NOT IN (SELECT source, source_id FROM core_states)
          AND (l.source, l.source_id) NOT IN (SELECT rep_source, rep_id FROM core_states WHERE rep_id IS NOT NULL)
          AND (l.source, l.source_id) NOT IN (SELECT source, source_id FROM mislocated_jp)
          AND l.qid NOT IN (SELECT qid FROM sync_links)
          AND l.qid NOT IN (SELECT d.qid FROM dbp d JOIN core_states s USING (id)
                            WHERE d.active AND d.qid IS NOT NULL
                              AND (s.source, s.source_id) NOT IN (SELECT source, source_id FROM sync_rows))
          -- core 밖 행(앞서 넣은 본체)이 가진 항목은 다시 넣지 않는다. 이름·주소 유일 제약이 없어 두 번 넣으면 중복 행이 생긴다.
          AND l.qid NOT IN (SELECT qid FROM dbp WHERE active AND qid IS NOT NULL AND id NOT IN (SELECT id FROM core_states))
        QUALIFY row_number() OVER (PARTITION BY l.qid ORDER BY l.rule = 'contains', l.meters, l.source, l.source_id) = 1
    """)
    con.execute("CREATE OR REPLACE TEMP VIEW promote_links AS SELECT source, source_id, qid FROM promote_rows")
    rd = load_country._readings(con, "promote_rows", "promote_links")
    return con.execute(f"""
        SELECT translate(left(b.name, 255), chr(10) || chr(13) || chr(9), '   '), coalesce(c.address, ''), b.lat, b.lon, coalesce(b.pool_category, 'Unknown'), d.region,
               '{load_country.COUNTRY}', b.source, CASE WHEN b.source = 'fsq' THEN b.source_id END, r.qid, r.sitelinks,
               NULL, coalesce(n.wd_ko, n.reading), b.source, b.source_id
        FROM promote_rows r JOIN base b USING (source, source_id) JOIN budget_destinations d USING (source, source_id)
        LEFT JOIN jp.candidates_all c USING (source, source_id) LEFT JOIN {rd} n USING (source, source_id)
        ORDER BY r.sitelinks DESC, b.source, b.source_id
    """).fetchall()


def _load_db_refs(con, database):
    """DB의 place_source_refs를 db_refs로 읽는다. V73 전 DB는 빈 표다."""
    db = load_country._psql(database, "SELECT CASE WHEN to_regclass('place_source_refs') IS NOT NULL THEN 1 END")
    rows = load_country._psql(database, "SELECT source || E'\\t' || source_record_id || E'\\t' || place_id "
                                        "FROM place_source_refs") if db else ""
    con.execute("CREATE OR REPLACE TEMP TABLE db_refs (source VARCHAR, source_id VARCHAR, place_id BIGINT)")
    linked = [(a, b, int(c)) for a, b, c in (line.split("\t") for line in rows.split("\n") if line)]
    if linked:
        con.executemany("INSERT INTO db_refs VALUES (?, ?, ?)", linked)


def ref_plan(con):
    """place_source_refs에 새로 이을 (place id, source, source_id, 장소 이름). 이미 이어진 원천 행은 빼고, 다른 장소에 이어진 것은 센다."""
    moved = con.execute("SELECT count(*) FROM core_refs r JOIN db_refs d USING (source, source_id) "
                        "WHERE d.place_id <> r.id").fetchone()[0]
    if moved:
        print(f"다른 장소에 이미 이어진 원천 행 {moved}건(옮기지 않음)")
    return con.execute("""SELECT r.id, r.source, r.source_id, d.name FROM core_refs r JOIN dbp d USING (id)
                          WHERE (r.source, r.source_id) NOT IN (SELECT source, source_id FROM db_refs)
                          ORDER BY r.id, r.source, r.source_id""").fetchall()


def apply(database, work, country, qid_updates, name_updates, deactivate, merge, fsq_ids, promote=(), refs=()):
    ids = _touched_ids(qid_updates, name_updates, deactivate, merge, fsq_ids)
    if not ids and not promote and not refs:
        return "바꿀 행 없음"
    backup = os.path.join(work, f"sync-backup-{time.strftime('%Y%m%d-%H%M%S')}.csv")
    with open(backup, "w", encoding="utf-8") as f:
        f.write(load_country._psql(database, f"COPY ({_backup_select(ids)}) TO STDOUT WITH CSV HEADER") + "\n")
    verdict_backup = backup.replace("sync-backup-", "sync-verdicts-")
    with open(verdict_backup, "w", encoding="utf-8") as f:
        f.write(load_country._psql(database, f"COPY ({_verdict_backup(country)}) TO STDOUT WITH CSV HEADER") + "\n")
    sql = ["BEGIN;", *_change_statements(country, qid_updates, name_updates, deactivate, merge, fsq_ids, promote, refs=refs),
           "COMMIT;"]
    out = load_country._psql(database, "\n".join(sql))
    inserted = _record_promoted(work, backup, out, promote)
    linked = _record_refs(backup, out)
    return f"적용, 이전 값 {backup}, 이전 파이프라인 판정 {verdict_backup}, 본체 넣음 {inserted}건, 원천 id 이음 {linked}건"


def write_sql(database, path, country, qid_updates, name_updates, deactivate, merge, fsq_ids, promote=(), refs=()):
    """운영에서 psql -f로 돌릴 스크립트를 쓴다. database는 운영을 그대로 복사한 로컬 DB다.
    바꿀 행·대표 행·그 쌍의 판정 지문이 이 복사본과 다르면(복사 뒤 운영이 바뀌었거나 이미 적용함) 백업도 쓰지 않고 멈춘다."""
    ids = _touched_ids(qid_updates, name_updates, deactivate, merge, fsq_ids)
    checked = _id_list(sorted(set(ids) | {m[1] for m in merge}))
    expected = load_country._psql(database, f"SELECT {_FINGERPRINT.format(ids=checked)}")
    guard = f"""DO $$ BEGIN
  IF (SELECT {_FINGERPRINT.format(ids=checked)}) IS DISTINCT FROM '{expected}' THEN
    RAISE EXCEPTION '바꿀 행이 계획을 만든 복사본과 다르다. 운영을 다시 복사해 계획을 새로 만든다';
  END IF;
END $$;"""
    stamp = time.strftime("%Y%m%d-%H%M%S")
    # 백업 앞에서 한 번 더 본다. 적용 뒤 다시 돌리면 백업 파일이 적용된 값으로 덮인다.
    lines = ["\\set ON_ERROR_STOP on", "\\pset tuples_only on", guard,
             f"\\copy ({_backup_select(ids)}) TO 'sync-backup-{stamp}.csv' WITH CSV HEADER",
             f"\\copy ({_verdict_backup(country)}) TO 'sync-verdicts-{stamp}.csv' WITH CSV HEADER",
             "BEGIN;", guard,
             *_change_statements(country, qid_updates, name_updates, deactivate, merge, fsq_ids, promote,
                                 f"sync-promoted-{stamp}.txt", refs, f"sync-refs-{stamp}.txt"),
             "COMMIT;"]
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    return (f"운영 적용 스크립트 {path}: 바꿀 행 {len(ids)}개, 넣을 본체 {len(promote)}건, 이을 원천 id {len(refs)}건, "
            f"지문 {expected}")


def _touched_ids(qid_updates, name_updates, deactivate, merge, fsq_ids):
    return sorted({u[0] for u in qid_updates} | {u[0] for u in name_updates} | set(deactivate) | {m[0] for m in merge}
                  | {f[0] for f in fsq_ids})


def _id_list(ids):
    return ", ".join(map(str, ids or [0]))


def _backup_select(ids):
    return f"SELECT {', '.join(_BACKUP_COLUMNS)} FROM places WHERE id IN ({_id_list(ids)}) ORDER BY id"


def _country_pairs(country):
    """그 나라 장소 쌍의 판정만 고른다. 다른 나라 sync가 남긴 PIPELINE 판정을 지우지 않는다."""
    return f"low_id IN (SELECT id FROM places WHERE country = {_lit(country)})"


def _verdict_backup(country):
    return f"SELECT * FROM place_pair_verdicts WHERE basis = 'PIPELINE' AND {_country_pairs(country)} ORDER BY 1, 2"


# 바꿀 행·대표 행이 계획을 만든 복사본과 같은지 보는 지문. 바꾸는 열, 보강·좌표 상태, 그 행이 낀 판정을 함께 본다.
# row(...)::text는 NULL 자리도 남겨 열 사이로 값이 옮겨 간 경우를 가른다.
_FINGERPRINT = ("md5(coalesce((SELECT string_agg(row(id, active, merged_into_id, wikidata_id, name_ko, fsq_place_id, "
                "enriched_at IS NOT NULL, latitude, longitude)::text, ',' ORDER BY id) FROM places WHERE id IN ({ids})), '') "
                "|| coalesce((SELECT string_agg(row(low_id, high_id, relation, basis)::text, ',' ORDER BY low_id, high_id) "
                "FROM place_pair_verdicts WHERE low_id IN ({ids}) OR high_id IN ({ids})), ''))")


def _change_statements(country, qid_updates, name_updates, deactivate, merge, fsq_ids, promote, promoted_out=None,
                       refs=(), refs_out=None):
    sql = []
    for pid, qid, sitelinks in qid_updates:
        sitelinks_sql = "NULL" if sitelinks is None else int(sitelinks)
        sql.append(f"UPDATE places SET wikidata_id = {_lit(qid)}, wikidata_sitelinks = {sitelinks_sql}, updated_at = now() "
                   f"WHERE id = {pid};")
    for pid, _, _, reading, _ in name_updates:
        if reading:
            sql.append(f"UPDATE places SET name_ko = {_lit(reading)}, updated_at = now() WHERE id = {pid};")
        else:
            # PlaceInvalidationJob.reaskNameKo와 같다. 비방문 행은 보강 대상이 아니라 이름만 비운다.
            sql.append(f"""UPDATE places SET name_ko = NULL, enrich_attempts = 0,
                enriched_at = CASE WHEN place_role = 'NOT_VISITABLE' THEN enriched_at END,
                enrich_input_fp = CASE WHEN place_role = 'NOT_VISITABLE' THEN enrich_input_fp END,
                embedding = NULL, embedding_text_fp = NULL, updated_at = now() WHERE id = {pid};""")
    if deactivate:
        sql.append("UPDATE places SET active = false, deactivated_at = now(), deactivated_reason = 'policy', "
                   f"updated_at = now() WHERE active AND id IN ({', '.join(map(str, deactivate))});")
    for pid, fsq_id in fsq_ids:
        sql.append(f"UPDATE places SET fsq_place_id = {_lit(fsq_id)}, updated_at = now() WHERE id = {pid} AND fsq_place_id IS NULL;")
    run = time.strftime("%Y%m%d%H%M")
    # 이번 묶음에 없는 옛 파이프라인 판정은 거둔다. 남기면 장소 연결 배치가 지난 묶음을 계속 되살린다.
    keep = ", ".join(f"({min(m)}, {max(m)})" for m in merge) or "(0, 0)"
    sql.append(f"DELETE FROM place_pair_verdicts WHERE basis = 'PIPELINE' AND {_country_pairs(country)} "
               f"AND (low_id, high_id) NOT IN ({keep});")
    for pid, rep in merge:
        low, high = min(pid, rep), max(pid, rep)
        # 연결 배치가 다른 곳·딸린 곳으로 판정한 쌍과 꺼졌거나 다른 곳에 합쳐진 대표에는 잇지 않는다.
        mergeable = (f"EXISTS (SELECT 1 FROM places r WHERE r.id = {rep} AND r.active AND r.merged_into_id IS NULL) "
                     f"AND NOT EXISTS (SELECT 1 FROM place_pair_verdicts v WHERE v.low_id = {low} AND v.high_id = {high} "
                     f"AND v.relation <> 'SAME' AND v.basis <> 'PIPELINE')")
        sql.append(f"UPDATE places SET merged_into_id = {rep}, merge_relation = 'SAME', updated_at = now() "
                   f"WHERE id = {pid} AND merged_into_id IS DISTINCT FROM {rep} AND {mergeable};")
        sql.append(f"INSERT INTO place_pair_verdicts (low_id, high_id, relation, basis, input_key) "
                   f"SELECT {low}, {high}, 'SAME', 'PIPELINE', {_lit(run)} WHERE {mergeable} "
                   f"ON CONFLICT (low_id, high_id) DO UPDATE SET relation = 'SAME', basis = 'PIPELINE', "
                   f"input_key = EXCLUDED.input_key, judged_at = now() "
                   f"WHERE (place_pair_verdicts.relation = 'SAME' OR place_pair_verdicts.basis = 'PIPELINE') "
                   f"AND (place_pair_verdicts.relation, place_pair_verdicts.basis) IS DISTINCT FROM ('SAME', 'PIPELINE');")
    if promote:
        values = ", ".join("(" + ", ".join("now()" if col == "wikidata_matched_at" else _lit(v)
                                           for col, v in zip(_INSERT_COLUMNS, row)) + ")" for row in promote)
        if promoted_out:
            sql.append(f"\\o {promoted_out}")
        sql.append(f"INSERT INTO places ({', '.join(_INSERT_COLUMNS)}) VALUES {values} ON CONFLICT DO NOTHING "
                   f"RETURNING 'promoted' || E'\\t' || id || E'\\t' || md5(name || chr(1) || address);")
        if promoted_out:
            sql.append("\\o")
    # 넣은 본체는 넣은 값(출처·이름·주소·좌표)으로 다시 찾아 원천 id를 잇는다. 이미 있어 넣지 않은 본체는 그 행에 잇는다.
    body_refs = [(r[_INSERT_COLUMNS.index("source")], r[-1], r[0], r[1], r[2], r[3]) for r in promote]
    if refs or body_refs:
        if refs_out:
            sql.append(f"\\o {refs_out}")
        if refs:
            values = ", ".join(f"({_lit(s)}, {_lit(sid)}, {int(pid)}, {_lit(name)})" for pid, s, sid, name in refs)
            sql.append("INSERT INTO place_source_refs (source, source_record_id, place_id) "
                       f"SELECT v.source, v.source_id, v.id FROM (VALUES {values}) AS v(source, source_id, id, name) "
                       "JOIN places p ON p.id = v.id AND p.name = v.name ON CONFLICT DO NOTHING "
                       "RETURNING 'ref' || E'\\t' || source || E'\\t' || source_record_id || E'\\t' || place_id;")
        if body_refs:
            values = ", ".join(f"({_lit(s)}, {_lit(sid)}, {_lit(n)}, {_lit(a)}, {float(lat)}, {float(lon)})"
                               for s, sid, n, a, lat, lon in body_refs)
            sql.append("INSERT INTO place_source_refs (source, source_record_id, place_id) "
                       f"SELECT DISTINCT ON (v.source, v.source_id) v.source, v.source_id, p.id "
                       f"FROM (VALUES {values}) AS v(source, source_id, name, address, lat, lon) "
                       f"JOIN places p ON p.source = v.source AND p.name = v.name AND p.address = v.address "
                       f"AND abs(p.latitude - v.lat) < {SAME_COORDINATE} AND abs(p.longitude - v.lon) < {SAME_COORDINATE} "
                       "ORDER BY v.source, v.source_id, p.active DESC, p.id ON CONFLICT DO NOTHING "
                       "RETURNING 'ref' || E'\\t' || source || E'\\t' || source_record_id || E'\\t' || place_id;")
        if refs_out:
            sql.append("\\o")
    return sql


def _record_refs(backup, psql_out):
    """이은 원천 id를 <work>/sync-refs-<시각>.csv로 남긴다. 되돌릴 때는 이 행을 place_source_refs에서 지운다."""
    linked = [line.split("\t")[1:] for line in psql_out.split("\n") if line.startswith("ref\t")]
    with open(backup.replace("sync-backup-", "sync-refs-"), "w", encoding="utf-8") as f:
        f.write("source,source_record_id,place_id\n" + "".join(",".join(x) + "\n" for x in linked))
    return len(linked)


def _record_promoted(work, backup, psql_out, promote):
    """넣은 행 id를 <work>/sync-promoted-<시각>.csv로 남긴다. 되돌릴 때는 place_source_refs에서 이 id의 매핑(승격이 뒤에 이은 것 포함)을
    먼저 지우고 이 id를 지운다(외래 키). 카탈로그에서는 빼지 않는다.
    같은 원천 행이라 카탈로그 승격이 이 행을 돌려주고, 빼면 그 행에 붙은 합쳐진 원천 행 별칭도 함께 사라진다."""
    source_of = {hashlib.md5(f"{row[0]}\x01{row[1]}".encode()).hexdigest(): (row[-2], row[-1]) for row in promote}
    inserted = []
    for line in psql_out.split("\n"):
        parts = line.split("\t")
        if len(parts) == 3 and parts[0] == "promoted" and parts[2] in source_of:
            inserted.append((int(parts[1]), *source_of[parts[2]]))
    if len(inserted) < len(promote):
        print(f"넣지 않은 본체 {len(promote) - len(inserted)}건(같은 이름·주소 행이 이미 있음)")
    path = backup.replace("sync-backup-", "sync-promoted-")
    with open(path, "w", encoding="utf-8") as f:
        f.write("id,source,source_id\n" + "".join(f"{i},{s},{sid}\n" for i, s, sid in inserted))
    return len(inserted)


def _lit(v):
    return "NULL" if v is None else "'" + str(v).replace("'", "''") + "'"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--core-budget-db", help="core를 적재한 예산 DB(적재 순서 매핑, --core-refs와 함께 주면 견준다)")
    parser.add_argument("--core-refs", help="bench/source_refs.py 매핑 CSV. 주면 core 대응표로 쓰고 place_source_refs에 잇는다")
    parser.add_argument("--budget-db", required=True, help="다시 돌린 예산 DB")
    parser.add_argument("--country-db", required=True, help="다시 돌린 나라 DB")
    parser.add_argument("--policy", default="budget_50000_55_40_5_dest")
    parser.add_argument("--database", default="trip_jp")
    parser.add_argument("--work", default="build/jp")
    parser.add_argument("--core-csv", required=True,
                        help="core 적재 CSV(load_country.core). 적재 때의 QID·한국어 이름으로 이름 출처를 가른다")
    parser.add_argument("--areas", help="지명 항목 Parquet(열 qid). 주면 core에 없는 유명 본체를 넣는다")
    parser.add_argument("--country", default="JP", help="이 나라 장소 쌍의 PIPELINE 판정만 정리한다")
    parser.add_argument("--wikidata", default=replay_places.WIKIDATA, help="--country-db를 만든 Wikidata 스냅샷")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--write-sql", help="쓰지 않고 운영 적용 psql 스크립트만 만든다(--database는 운영 복사본)")
    args = parser.parse_args()
    if not args.database.startswith(load_country.EXPERIMENT_DB_PREFIX):
        raise SystemExit("실험 DB에서만 읽고 쓴다")
    # 연결을 만든 스냅샷과 다른 스냅샷의 라벨을 읽으면 별칭으로 이은 항목의 이름·지명도가 비거나 어긋난다.
    replay_places.WIKIDATA = args.wikidata
    con = load_country._connect(args.budget_db, args.country_db)
    if not (args.core_budget_db or args.core_refs):
        raise SystemExit("--core-refs 또는 --core-budget-db가 있어야 한다")
    states(con, args.core_budget_db, args.policy, args.core_refs)
    print("상태", con.execute("SELECT state, count(*), count(rep_core_id) FROM core_states GROUP BY 1 ORDER BY 2 DESC").fetchall())
    qid_updates, name_updates, deactivate, merge, fsq_ids, haiku_broken = plan(con, args.database, args.core_csv)
    promote = promotions(con, args.areas) if args.areas else []
    refs = ref_plan(con) if args.core_refs else []
    reasons = {}
    for u in name_updates:
        reasons[u[4]] = reasons.get(u[4], 0) + 1
    print(f"끄기 {len(deactivate)}건, 대표로 잇기 {len(merge)}건, 대표 FSQ id 달기 {len(fsq_ids)}건, QID 변경 {len(qid_updates)}건, "
          f"한국어 이름 변경 {len(name_updates)}건 {reasons}, 본체 넣기 {len(promote)}건, "
          f"보강이 지은 깨진 이름(바꾸지 않음) {len(haiku_broken)}건, 원천 id 잇기 {len(refs)}건")
    hangul = sum(1 for u in haiku_broken if re.search("[가-힣]", u[2]))
    print(f"   haiku_broken 한글 있음 {hangul}건(무효화 배치가 다시 묻는다), 한글 없음 {len(haiku_broken) - hangul}건")
    for u in haiku_broken[:20]:
        print("   haiku_broken |", u[1], "|", u[2])
    for u in promote[:20]:
        print("   본체 |", u[0], "|", u[9], u[10], "|", u[12])
    for u in name_updates[:40]:
        print("  ", u[4], "|", u[1], "|", u[2], "→", u[3])
    os.makedirs(args.work, exist_ok=True)
    for u in qid_updates[:80]:
        print("   QID", u)
    if args.write_sql:
        print(write_sql(args.database, args.write_sql, args.country, qid_updates, name_updates, deactivate, merge, fsq_ids,
                        promote, refs))
    elif args.apply:
        print(apply(args.database, args.work, args.country, qid_updates, name_updates, deactivate, merge, fsq_ids, promote,
                    refs))


if __name__ == "__main__":
    main()
