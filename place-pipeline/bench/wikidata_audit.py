"""
Wikidata 연결 규칙 전후 비교. 나라 DB의 같은 풀 행(evidence)을 지금 규칙(identity.wikidata_links)으로 다시 잇고 이전 연결 표와
견준다. 네트워크·LLM 호출 없음. 나라 DB는 읽기만 하고 결과는 --out 폴더의 audit.duckdb에 쓴다.
  rules     : 규칙별(exact·contains) 연결 행 수와 항목 수
  changes   : 잃은 연결·얻은 연결·항목이 바뀐 연결. 잃은 연결은 이유를 하나 붙인다.
              brand(브랜드·체인 항목), area_shop(가게에 붙은 지명 항목), sub_facility(항목 이름 뒤에 다른 말이 붙은 포함
              연결), far_namesake(흔한 이름의 먼 같은 이름 행), other
  shared    : 이어진 행이 둘 이상인 항목 수·행 수와, 그 가운데 항목 기준 행에서 identity.NAMESAKE_NEAR_METERS 밖 행 수
  name_ko   : 이어진 항목의 한국어 라벨 가운데 한글 한 글자 규칙과 names.wikidata_korean_label이 다르게 본 수
결과: <out>/summary.json, <out>/lost.tsv(잃은 연결 전부, 이유·행 이름·항목 이름).

실행: python bench/wikidata_audit.py --country-db build/country/japan-v2.duckdb --before wikidata_links \
        --wikidata build/country/raw/wikidata/japan.parquet --areas build/identity/japan-area-qids.parquet \
        --brands build/identity/japan-brand-qids.parquet --businesses build/identity/japan-business-qids.parquet \
        --kinds build/identity/japan-kind-qids.parquet \
        --out build/identity/audit
"""
import argparse
import json
import os
import sys

import duckdb

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from pipeline import candidates, identity  # noqa: E402

LOST_REASON = f"""
    CASE WHEN b.qid IN (SELECT qid FROM brands) THEN 'brand'
         WHEN b.qid IN (SELECT qid FROM areas) AND coalesce({" OR ".join(
             f"starts_with(c.category, '{p}')" for p in identity.CONTAINED_IGNORED_CATEGORIES)}, false) THEN 'area_shop'
         WHEN b.rule = 'contains' THEN 'sub_facility'
         WHEN b.rule = 'exact' THEN 'far_namesake'
         ELSE 'other' END"""


def run(country_db, before, wikidata, areas, brands, businesses, kinds, out):
    os.makedirs(out, exist_ok=True)
    con = duckdb.connect(os.path.join(out, "audit.duckdb"),
                         config={"memory_limit": "6GB", "threads": 4, "temp_directory": os.path.join(out, "tmp")})
    candidates.register_functions(con)
    con.execute(f"ATTACH '{country_db}' AS src (READ_ONLY)")
    con.execute(f"CREATE OR REPLACE TABLE areas AS SELECT qid FROM read_parquet('{areas}')")
    con.execute(f"CREATE OR REPLACE TABLE brands AS SELECT qid FROM read_parquet('{brands}')")
    con.execute(f"CREATE OR REPLACE TABLE businesses AS SELECT qid FROM read_parquet('{businesses}')")
    con.execute(f"CREATE OR REPLACE TABLE kinds AS SELECT qid, kind FROM read_parquet('{kinds}')")
    con.execute(f"CREATE OR REPLACE VIEW wd AS SELECT * FROM read_parquet('{wikidata}')")
    con.execute("CREATE OR REPLACE TABLE pool AS SELECT source, source_id FROM src.evidence")
    con.execute("CREATE OR REPLACE VIEW cand AS SELECT * FROM src.candidates_all")
    con.execute(f"CREATE OR REPLACE TABLE before_links AS SELECT * FROM src.{before}")
    identity.wikidata_links(con, "pool", "cand", "wd", "after_links", "areas", "brands", "businesses", "kinds")
    summary = {"pool_rows": con.execute("SELECT count(*) FROM pool").fetchone()[0], "rules": {}}
    for name in ("before", "after"):
        summary["rules"][name] = {r: {"rows": n, "items": q} for r, n, q in con.execute(
            f"SELECT rule, count(*), count(DISTINCT qid) FROM {name}_links GROUP BY 1 ORDER BY 1").fetchall()}
    con.execute(f"""
        CREATE OR REPLACE TABLE lost AS
        SELECT {LOST_REASON} AS reason, b.rule, b.source, b.source_id, c.name, c.category, b.qid,
               coalesce(w.ja, w.en) AS item_name, w.sitelinks, round(b.meters) AS meters, a.qid AS new_qid
        FROM before_links b JOIN cand c USING (source, source_id) LEFT JOIN wd w ON w.qid = b.qid
        LEFT JOIN after_links a USING (source, source_id)
        WHERE a.qid IS DISTINCT FROM b.qid
    """)
    summary["lost"] = dict(con.execute("SELECT reason, count(*) FROM lost GROUP BY 1 ORDER BY 2 DESC").fetchall())
    summary["gained"] = con.execute("""
        SELECT count(*) FROM after_links a
        WHERE NOT EXISTS (SELECT 1 FROM before_links b WHERE b.source = a.source AND b.source_id = a.source_id)
    """).fetchone()[0]
    summary["changed_item"] = con.execute("SELECT count(*) FROM lost WHERE new_qid IS NOT NULL").fetchone()[0]
    for name in ("before", "after"):
        summary[f"shared_{name}"] = _shared(con, f"{name}_links")
    summary["name_ko"] = dict(zip(("hangul_rule", "korean_label", "differ"), con.execute("""
        SELECT count(*) FILTER (WHERE regexp_matches(w.ko, '[가-힣]')),
               count(wikidata_korean_label(w.ko, c.name)),
               count(*) FILTER (WHERE regexp_matches(w.ko, '[가-힣]') IS DISTINCT FROM (wikidata_korean_label(w.ko, c.name) IS NOT NULL)
                                    OR (wikidata_korean_label(w.ko, c.name) IS NOT NULL AND wikidata_korean_label(w.ko, c.name) <> w.ko))
        FROM after_links a JOIN cand c USING (source, source_id) JOIN wd w USING (qid) WHERE w.ko IS NOT NULL
    """).fetchone()))
    con.execute(f"COPY (SELECT * EXCLUDE (source) FROM lost ORDER BY reason, sitelinks DESC NULLS LAST) "
                f"TO '{os.path.join(out, 'lost.tsv')}' (HEADER, DELIMITER '\t')")
    with open(os.path.join(out, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)
    return summary


def _shared(con, links):
    h = identity._HAVERSINE_M.replace("a.", "x.").replace("b.", "r.")
    return dict(zip(("items", "rows", "rows_beyond_near"), con.execute(f"""
        WITH r AS (SELECT l.qid, c.lat, c.lon, l.meters FROM {links} l JOIN cand c USING (source, source_id)),
        multi AS (SELECT qid FROM r GROUP BY 1 HAVING count(*) > 1),
        x AS (SELECT qid, arg_min(lat, meters) AS lat, arg_min(lon, meters) AS lon FROM r GROUP BY 1)
        SELECT count(DISTINCT r.qid), count(*), count(*) FILTER (WHERE {h} > {identity.NAMESAKE_NEAR_METERS})
        FROM r JOIN multi USING (qid) JOIN x USING (qid)
    """).fetchone()))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--country-db", required=True)
    parser.add_argument("--before", default="wikidata_links", help="나라 DB 안의 이전 연결 표")
    parser.add_argument("--wikidata", required=True)
    parser.add_argument("--areas", required=True)
    parser.add_argument("--brands", required=True)
    parser.add_argument("--businesses", required=True)
    parser.add_argument("--kinds", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    print(json.dumps(run(args.country_db, args.before, args.wikidata, args.areas, args.brands, args.businesses,
                         args.kinds, args.out),
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
