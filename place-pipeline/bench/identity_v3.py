"""
기존 나라 DB·예산 DB 사본에 지금 Wikidata 연결·중복 병합 규칙만 다시 적용한다. 선별·저장 정책은 다시 돌리지 않는다.
LLM·네트워크 호출 없음. 원본 DB는 읽기만 하고 --out 폴더의 사본을 고친다.
  연결 : 나라 DB evidence 풀 행을 identity.wikidata_links로 다시 잇고 wd_* 열을 바꾼다.
  병합 : dedup.build를 다시 돌려 place_clusters·place_relations·evidence_dedup을 바꾼다. 대표는 선별 표(base)에 있는
         구성원 가운데 이름이 이어진 항목의 라벨인 행을 먼저 고른다. 선별을 다시 돌리지 않아 선별 표에 없는 대표는
         sync_core가 찾지 못한다.
  예산 : 새로 합쳐진 구성원은 kept를 끄고 대표는 구성원 가운데 하나라도 담겼으면 켠다. 예산 evidence의 wd_* 열을 바꾼다.
  재검토 : 잃은 항목의 지명도로 담겼을 수 있는 행(담김 + 잃은 항목 언어판 3개 이상 + 새 항목 없음)을 review_kept 표로 남긴다.
결과: <out>/country.duckdb, <out>/budget.duckdb, <out>/identity-v3.json

실행: python bench/identity_v3.py --country-db build/country/japan-v2.duckdb --budget-db build/budget-v2/budget.duckdb \
        --wikidata build/country/raw/wikidata/japan.parquet --areas build/identity/japan-area-qids.parquet \
        --brands build/identity/japan-brand-qids.parquet --businesses build/identity/japan-business-qids.parquet \
        --kinds build/identity/japan-kind-qids.parquet \
        --out build/identity/v3
"""
import argparse
import json
import os
import shutil
import sys

import duckdb

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from pipeline import candidates, dedup, identity, names  # noqa: E402

REVIEW_MIN_SITELINKS = 3


def run(country_db, budget_db, wikidata, areas, brands, businesses, kinds, out, policy):
    os.makedirs(out, exist_ok=True)
    country, budget = os.path.join(out, "country.duckdb"), os.path.join(out, "budget.duckdb")
    shutil.copyfile(country_db, country)
    shutil.copyfile(budget_db, budget)
    con = duckdb.connect(country, config={"memory_limit": "8GB", "threads": 4,
                                          "temp_directory": os.path.join(out, "tmp")})
    candidates.register_functions(con)
    con.create_function("bare_key", names.bare_key, ["VARCHAR"], "VARCHAR", null_handling="special")
    con.execute(f"ATTACH '{budget}' AS b")
    con.execute(f"CREATE OR REPLACE TEMP TABLE areas AS SELECT qid FROM read_parquet('{areas}')")
    con.execute(f"CREATE OR REPLACE TEMP TABLE brands AS SELECT qid FROM read_parquet('{brands}')")
    con.execute(f"CREATE OR REPLACE TEMP TABLE businesses AS SELECT qid FROM read_parquet('{businesses}')")
    con.execute(f"CREATE OR REPLACE TEMP TABLE kinds AS SELECT qid, kind FROM read_parquet('{kinds}')")
    con.execute(f"CREATE OR REPLACE TEMP VIEW wd AS SELECT * FROM read_parquet('{wikidata}')")
    con.execute("CREATE OR REPLACE TEMP TABLE pool AS SELECT source, source_id FROM evidence")
    con.execute("CREATE OR REPLACE TABLE wikidata_links_v2 AS SELECT * FROM wikidata_links")
    identity.wikidata_links(con, "pool", "candidates_all", "wd", "wikidata_links", "areas", "brands", "businesses",
                            "kinds")
    con.execute("ALTER TABLE evidence ADD COLUMN IF NOT EXISTS wd_meters DOUBLE")
    con.execute("""
        UPDATE evidence e SET wd_qid = l.qid, wd_sitelinks = w.sitelinks,
               wd_name_ko = wikidata_korean_label(w.ko::VARCHAR, c.name), wd_kowiki = w.kowiki, wd_meters = l.meters
        FROM candidates_all c LEFT JOIN wikidata_links l USING (source, source_id) LEFT JOIN wd w ON w.qid = l.qid
        WHERE c.source = e.source AND c.source_id = e.source_id
    """)
    summary = {"links": dict(con.execute("SELECT rule, count(*) FROM wikidata_links GROUP BY 1").fetchall())}
    run_id = dedup.build(con, "evidence", "candidates_all", "selection", work_dir=os.path.join(out, "tmp"))
    summary["dedup_run_id"] = run_id
    summary["clusters"] = dict(con.execute("SELECT status, count(*) FROM place_clusters GROUP BY 1").fetchall())
    con.execute("""CREATE OR REPLACE TEMP TABLE item_labels AS SELECT DISTINCT qid, strip_accents(name_key(l)) AS k
                   FROM wd, unnest([ja::VARCHAR, en::VARCHAR, ko::VARCHAR]) AS u(l) WHERE l IS NOT NULL""")
    summary["representatives_moved"] = _pick_listed_representatives(con, policy)
    # 대표를 옮기면 묶음 근거(QID·한국어 이름)도 새 대표 행에 다시 모아야 sync가 옛 연결을 core 행에 되살리지 않는다.
    dedup._merged_evidence(con, "evidence")
    summary["budget"] = _apply_to_budget(con, policy)
    summary["representative_qid_mismatch"] = con.execute("""
        SELECT count(*) FROM place_clusters c JOIN b.evidence e USING (source, source_id)
        JOIN (SELECT cluster_id, any_value(d.wd_qid) AS qid FROM place_clusters k
              JOIN evidence_dedup d USING (source, source_id) WHERE k.is_representative GROUP BY 1) q USING (cluster_id)
        WHERE c.status = 'merged' AND c.is_representative AND e.wd_qid IS DISTINCT FROM q.qid
    """).fetchone()[0]
    if summary["representative_qid_mismatch"]:
        raise RuntimeError(f"대표 행 예산 근거가 묶음과 다르다: {summary['representative_qid_mismatch']}건")
    summary["review_kept"] = _review_kept(con, policy)
    con.close()
    with open(os.path.join(out, "identity-v3.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)
    return summary


def _pick_listed_representatives(con, policy):
    """묶음 대표를 선별 표에 있는 구성원으로 고른다. 이름이 이어진 항목의 라벨인 행 → dedup이 고른 행 → 예산에 담긴 행 →
    선별에 담긴 행 순이다. 별칭으로 이어진 행(剣先公園, 金閣寺)은 그 장소의 일부·다른 표기라 대표 이름으로 쓰지 않는다."""
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE new_rep AS
        SELECT c.cluster_id, arg_min(c.source || chr(0) || c.source_id,
                   [CASE WHEN lb.k IS NOT NULL THEN 0 ELSE 1 END,
                    CASE WHEN c.is_representative THEN 0 ELSE 1 END, CASE WHEN p.kept THEN 0 ELSE 1 END,
                    CASE WHEN s.kept THEN 0 ELSE 1 END, coalesce(e.wd_meters, 1e9)::BIGINT]) AS pick
        FROM place_clusters c
        JOIN b.base s USING (source, source_id) LEFT JOIN b.{policy} p USING (source, source_id)
        LEFT JOIN evidence e USING (source, source_id)
        LEFT JOIN candidates_all n USING (source, source_id)
        LEFT JOIN item_labels lb ON lb.qid = e.wd_qid
             AND lb.k IN (strip_accents(name_key(n.name)), strip_accents(bare_key(n.name)))
        WHERE c.status = 'merged' GROUP BY 1
    """)
    moved = con.execute("""
        SELECT count(*) FROM place_clusters c JOIN new_rep r USING (cluster_id)
        WHERE c.is_representative AND c.source || chr(0) || c.source_id <> r.pick
    """).fetchone()[0]
    con.execute("""
        UPDATE place_clusters c SET is_representative = (c.source || chr(0) || c.source_id = r.pick)
        FROM new_rep r WHERE r.cluster_id = c.cluster_id
    """)
    return moved


def _apply_to_budget(con, policy):
    con.execute("""
        CREATE OR REPLACE TEMP TABLE cluster_kept AS
        SELECT c.cluster_id, bool_or(p.kept) AS policy_kept, bool_or(s.kept) AS base_kept
        FROM place_clusters c LEFT JOIN b.base s USING (source, source_id)
        LEFT JOIN b.""" + policy + """ p USING (source, source_id)
        WHERE c.status = 'merged' GROUP BY 1
    """)
    out = {}
    for table, col in ((f"b.{policy}", "policy_kept"), ("b.base", "base_kept")):
        out[f"{table}_members_off"] = con.execute(f"""
            UPDATE {table} t SET kept = false FROM place_clusters c
            WHERE c.status = 'merged' AND NOT c.is_representative AND t.kept
              AND t.source = c.source AND t.source_id = c.source_id
        """).fetchone()[0]
        out[f"{table}_representatives_on"] = con.execute(f"""
            UPDATE {table} t SET kept = true FROM place_clusters c JOIN cluster_kept k USING (cluster_id)
            WHERE c.is_representative AND k.{col} AND NOT t.kept AND t.source = c.source AND t.source_id = c.source_id
        """).fetchone()[0]
    out["destinations_added"] = con.execute("""
        INSERT INTO b.budget_destinations BY NAME
        SELECT DISTINCT ON (r.source, r.source_id) r.source, r.source_id, d.* EXCLUDE (source, source_id)
        FROM place_clusters r JOIN place_clusters m ON m.cluster_id = r.cluster_id AND NOT m.is_representative
        JOIN b.budget_destinations d ON d.source = m.source AND d.source_id = m.source_id
        WHERE r.is_representative AND r.status = 'merged'
          AND NOT EXISTS (SELECT 1 FROM b.budget_destinations x WHERE x.source = r.source AND x.source_id = r.source_id)
        ORDER BY r.source, r.source_id, m.source, m.source_id
    """).fetchone()[0]
    # 묶음 구성원은 evidence_dedup에 없어 행 자체 연결로 먼저 덮고, 대표·홀로 남은 행은 묶음 근거로 덮는다.
    out["evidence_updated"] = 0
    for source in ("evidence", "evidence_dedup"):
        out["evidence_updated"] += con.execute(f"""
            UPDATE b.evidence e SET wd_qid = d.wd_qid, wd_sitelinks = d.wd_sitelinks, wd_name_ko = d.wd_name_ko
            FROM {source} d WHERE d.source = e.source AND d.source_id = e.source_id
              AND (e.wd_qid IS DISTINCT FROM d.wd_qid OR e.wd_name_ko IS DISTINCT FROM d.wd_name_ko)
        """).fetchone()[0]
    return out


def _review_kept(con, policy):
    con.execute(f"""
        CREATE OR REPLACE TABLE review_kept AS
        SELECT o.source, o.source_id, c.name, o.qid AS lost_qid, w.sitelinks AS lost_sitelinks
        FROM wikidata_links_v2 o JOIN b.{policy} p USING (source, source_id) JOIN wd w ON w.qid = o.qid
        JOIN candidates_all c USING (source, source_id)
        WHERE p.kept AND w.sitelinks >= {REVIEW_MIN_SITELINKS}
          AND NOT EXISTS (SELECT 1 FROM wikidata_links n WHERE n.source = o.source AND n.source_id = o.source_id)
    """)
    return con.execute("SELECT count(*) FROM review_kept").fetchone()[0]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--country-db", required=True)
    parser.add_argument("--budget-db", required=True)
    parser.add_argument("--wikidata", required=True)
    parser.add_argument("--areas", required=True)
    parser.add_argument("--brands", required=True)
    parser.add_argument("--businesses", required=True)
    parser.add_argument("--kinds", required=True, help="종류별 항목 Parquet(열 qid, kind, wikidata.write_kind_qids)")
    parser.add_argument("--policy", default="budget_50000_55_40_5_dest")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    print(json.dumps(run(args.country_db, args.budget_db, args.wikidata, args.areas, args.brands, args.businesses, args.kinds,
                         args.out, args.policy),
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
