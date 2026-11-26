"""
나라·언어가 다른 표본 범위에서 Wikidata 연결과 같은 장소 병합을 돌려 결과를 남기고, 두 실행(예: 이전 코드와 지금 코드)을 견준다.
LLM·네트워크 호출 없음. 입력은 pilot 실행의 후보(candidates/<범위>.parquet), FSQ 원본(raw/fsq/<범위>.parquet, 없어도 됨),
범위별 Wikidata 스냅샷과 지명·브랜드·사업체 항목 목록이다.
  run     : 범위마다 evidence.build → selection.build → dedup.build를 돌려 <out>/<범위>-links.parquet(행·항목·규칙)과
            <out>/<범위>-merges.parquet(묶음·구성원·상태)을 쓴다. 이전 코드(브랜드 목록을 받지 않는 evidence.build)도 돈다.
  compare : 두 실행 폴더를 범위별로 견줘 연결 수·잃은 연결·얻은 연결·새 병합·풀린 병합을 compare.json과 TSV로 쓴다.

실행: python bench/global_identity_check.py run --areas pilot/identity-areas.tsv --candidates build/global/candidates \
        --fsq build/pilot-v1/raw/fsq --wikidata build/global/raw/wikidata --area-items build/global/raw/area-qids.parquet \
        --brand-items build/global/raw/brand-qids.parquet --business-items build/global/raw/business-qids.parquet \
        --kind-items build/global/raw/kind-qids.parquet \
        --out build/global/after
      python bench/global_identity_check.py compare --before build/global/before --after build/global/after --out build/global/compare
"""
import argparse
import inspect
import json
import os
import sys

import duckdb

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from pipeline import candidates, dedup, evidence, selection  # noqa: E402
from pipeline.areas import load_areas  # noqa: E402


def run(areas_path, candidates_dir, fsq_dir, wikidata_dir, area_items, brand_items, business_items, kind_items, out):
    os.makedirs(out, exist_ok=True)
    summary = {}
    params = inspect.signature(evidence.build).parameters
    for area in load_areas(areas_path):
        path = os.path.join(candidates_dir, f"{area.name}.parquet")
        wd = os.path.join(wikidata_dir, f"{area.name}.parquet")
        if not (os.path.exists(path) and os.path.exists(wd)):
            summary[area.name] = "입력 없음"
            continue
        con = duckdb.connect()
        candidates.register_functions(con)
        con.execute(f"CREATE TABLE candidates AS SELECT * FROM read_parquet('{path}')")
        con.execute(f"CREATE TABLE wd AS SELECT * FROM read_parquet('{wd}')")
        con.execute(f"CREATE TABLE areas AS SELECT qid FROM read_parquet('{area_items}')")
        con.execute(f"CREATE TABLE brands AS SELECT qid FROM read_parquet('{brand_items}')")
        con.execute(f"CREATE TABLE businesses AS SELECT qid FROM read_parquet('{business_items}')")
        con.execute(f"CREATE TABLE kinds AS SELECT qid, kind FROM read_parquet('{kind_items}')")
        fsq = os.path.join(fsq_dir, f"{area.name}.parquet") if fsq_dir else None
        fsq_raw = None
        if fsq and os.path.exists(fsq):
            con.execute(f"CREATE TABLE fs AS SELECT * FROM read_parquet('{fsq}')")
            fsq_raw = "fs"
        kwargs = {"wikidata_raw": "wd", "wikidata_areas": "areas"}
        # 이전 코드(main)의 evidence.build는 브랜드·사업체·종류 목록을 받지 않는다.
        for name, tbl in (("wikidata_brands", "brands"), ("wikidata_businesses", "businesses"), ("wikidata_kinds", "kinds")):
            if name in params:
                kwargs[name] = tbl
        evidence.build(con, "candidates", fsq_raw, **kwargs)
        selection.build(con, "evidence", "candidates", fsq_raw)
        dedup.build(con, "evidence", "candidates", "selection", work_dir=os.path.join(out, "tmp"))
        con.execute(f"""COPY (SELECT l.source, l.source_id, c.name, c.category, l.qid, coalesce(w.ja, w.en, w.ko) AS item,
                                     w.sitelinks, l.rule, round(l.meters) AS meters
                              FROM wikidata_links l JOIN candidates c USING (source, source_id) LEFT JOIN wd w USING (qid))
                        TO '{os.path.join(out, area.name + "-links.parquet")}' (FORMAT parquet)""")
        con.execute(f"""COPY (SELECT k.cluster_id, k.source, k.source_id, c.name, c.category, c.lat, c.lon,
                                     k.is_representative, k.status
                              FROM place_clusters k JOIN candidates c USING (source, source_id))
                        TO '{os.path.join(out, area.name + "-merges.parquet")}' (FORMAT parquet)""")
        summary[area.name] = {
            "pool": con.execute("SELECT count(*) FROM evidence").fetchone()[0],
            "links": dict(con.execute("SELECT rule, count(*) FROM wikidata_links GROUP BY 1").fetchall()),
            "clusters": dict(con.execute("SELECT status, count(*) FROM place_clusters GROUP BY 1").fetchall()),
        }
        con.close()
    with open(os.path.join(out, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)
    return summary


def compare(before, after, out):
    os.makedirs(out, exist_ok=True)
    con = duckdb.connect()
    result = {}
    for name in sorted(f[:-len("-links.parquet")] for f in os.listdir(after) if f.endswith("-links.parquet")):
        b, a = (os.path.join(d, f"{name}-links.parquet") for d in (before, after))
        if not os.path.exists(b):
            continue
        con.execute(f"CREATE OR REPLACE TABLE lb AS SELECT * FROM read_parquet('{b}')")
        con.execute(f"CREATE OR REPLACE TABLE la AS SELECT * FROM read_parquet('{a}')")
        con.execute(f"""CREATE OR REPLACE TABLE changed AS
            SELECT coalesce(b.source, a.source) AS source, coalesce(b.source_id, a.source_id) AS source_id,
                   coalesce(b.name, a.name) AS name, coalesce(b.category, a.category) AS category,
                   b.item AS before_item, b.rule AS before_rule, b.sitelinks AS before_sitelinks, b.meters AS before_meters,
                   a.item AS after_item, a.rule AS after_rule
            FROM lb b FULL JOIN la a USING (source, source_id) WHERE b.qid IS DISTINCT FROM a.qid""")
        mb, ma = (os.path.join(d, f"{name}-merges.parquet") for d in (before, after))
        merge_sql = """SELECT source, source_id, name, list(name) OVER (PARTITION BY cluster_id) AS members
                       FROM read_parquet('{0}') WHERE status = 'merged' AND NOT is_representative"""
        con.execute(f"CREATE OR REPLACE TABLE mb AS {merge_sql.format(mb)}")
        con.execute(f"CREATE OR REPLACE TABLE ma AS {merge_sql.format(ma)}")
        result[name] = {
            "links_before": con.execute("SELECT count(*) FROM lb").fetchone()[0],
            "links_after": con.execute("SELECT count(*) FROM la").fetchone()[0],
            "lost": con.execute("SELECT count(*) FROM changed WHERE after_item IS NULL").fetchone()[0],
            "gained": con.execute("SELECT count(*) FROM changed WHERE before_item IS NULL").fetchone()[0],
            "switched": con.execute("SELECT count(*) FROM changed WHERE before_item IS NOT NULL AND after_item IS NOT NULL").fetchone()[0],
            "merged_before": con.execute("SELECT count(*) FROM mb").fetchone()[0],
            "merged_after": con.execute("SELECT count(*) FROM ma").fetchone()[0],
        }
        con.execute(f"COPY (SELECT '{name}' AS area, * FROM changed ORDER BY before_sitelinks DESC NULLS LAST) "
                    f"TO '{os.path.join(out, name + '-links.tsv')}' (HEADER, DELIMITER '\t')")
        con.execute(f"""COPY (SELECT '{name}' AS area, 'new' AS kind, a.name, a.members FROM ma a
                              WHERE (a.source, a.source_id) NOT IN (SELECT source, source_id FROM mb)
                              UNION ALL
                              SELECT '{name}', 'undone', b.name, b.members FROM mb b
                              WHERE (b.source, b.source_id) NOT IN (SELECT source, source_id FROM ma))
                        TO '{os.path.join(out, name + '-merges.tsv')}' (HEADER, DELIMITER '\t')""")
    with open(os.path.join(out, "compare.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    return result


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    r = sub.add_parser("run")
    r.add_argument("--areas", required=True)
    r.add_argument("--candidates", required=True)
    r.add_argument("--fsq")
    r.add_argument("--wikidata", required=True)
    r.add_argument("--area-items", required=True)
    r.add_argument("--brand-items", required=True)
    r.add_argument("--business-items", required=True)
    r.add_argument("--kind-items", required=True)
    r.add_argument("--out", required=True)
    c = sub.add_parser("compare")
    c.add_argument("--before", required=True)
    c.add_argument("--after", required=True)
    c.add_argument("--out", required=True)
    args = parser.parse_args()
    if args.command == "run":
        result = run(args.areas, args.candidates, args.fsq, args.wikidata, args.area_items, args.brand_items,
                     args.business_items, args.kind_items, args.out)
    else:
        result = compare(args.before, args.after, args.out)
    print(json.dumps(result, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
