"""
예산 정책 비교 실험. 기준 선별(S+FQ50r)을 장소 수 상한마다 줄여 같은 지표로 잰다. LLM·임베딩 호출 없음.
나라 DuckDB는 읽기만 하고, 필요한 표를 따로 만든 실험 DB에 복사해 쓴다.

실행: python bench/budget_policies.py --db build/country/japan.duckdb --geonames build/geonames/JP.txt \
        --budgets 30000,50000,80000,100000 --out build/budget
"""
import argparse
import json
import os
import sys

import duckdb

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from pipeline import budget_policy, candidates, metrics  # noqa: E402
from bench.select_policies import _GEONAMES_COLUMNS, draft_places  # noqa: E402

BASELINE = "stored_S_FQ50r"
COPIED = {
    "base": f"SELECT * FROM jp.{BASELINE}",
    "evidence": "SELECT source, source_id, wd_qid, wd_sitelinks, wd_name_ko, independent_confirm, web_kind "
                "FROM jp.pool_evidence",
    "wikivoyage": "SELECT * FROM jp.wikivoyage_links",
    "famous_items": "SELECT * FROM jp.famous_items",
    "names": "SELECT source, source_id, name, names FROM jp.pool_candidates",
    "towns": "SELECT * FROM jp.eval_towns",
}


def prepare(con, country_db, geonames_txt, draft_tsv, wikidata):
    con.execute(f"ATTACH '{country_db}' AS jp (READ_ONLY)")
    for name, query in COPIED.items():
        con.execute(f"CREATE OR REPLACE TABLE {name} AS {query}")
    con.execute(f"""
        CREATE OR REPLACE TABLE settlements AS
        SELECT lat, lon, pop FROM read_csv('{geonames_txt}', delim = '\t', header = false, quote = '',
                                           columns = {_GEONAMES_COLUMNS})
        WHERE fclass = 'P'
    """)
    con.execute("DETACH jp")
    draft_places(con, draft_tsv, "base", "names", "draft_places", wikidata)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", required=True)
    parser.add_argument("--geonames", required=True)
    parser.add_argument("--budgets", default="30000,50000,80000,100000")
    parser.add_argument("--out", required=True)
    parser.add_argument("--wikidata", default="build/country/raw/wikidata/japan.parquet")
    parser.add_argument("--areas", default="build/country/raw/wikidata/japan-area-qids.parquet")
    parser.add_argument("--draft-places", default="eval/jp-draft-places.tsv")
    parser.add_argument("--shares", default="50:45:5", help="명소:식음료:숙박·교통 몫(%%), 쉼표로 여러 벌")
    parser.add_argument("--destinations", help="destinations.json. 주면 여행지 반경 안에서만 배분하고 여행지별 최소치를 먼저 담는다")
    parser.add_argument("--country", default="JP")
    parser.add_argument("--reuse", action="store_true", help="실험 DB에 복사해 둔 표를 다시 쓴다")
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    con = duckdb.connect(os.path.join(args.out, "budget.duckdb"),
                         config={"memory_limit": "3GB", "threads": 2, "temp_directory": "build/tmp",
                                 "preserve_insertion_order": False})
    candidates.register_functions(con)
    if not args.reuse:
        prepare(con, args.db, args.geonames, args.draft_places, f"read_parquet('{args.wikidata}')")
    budget_policy.values(con, "base", "evidence", "wikivoyage", "settlements", f"read_parquet('{args.wikidata}')",
                         f"(SELECT qid FROM read_parquet('{args.areas}'))")
    regions = _regions(con, args.destinations, args.country) if args.destinations else None
    tables = {"S+FQ50r": "base"}
    for n in (int(b) for b in args.budgets.split(",")):
        for spec in args.shares.split(","):
            sight, food, support = (int(x) / 100 for x in spec.split(":"))
            suffix = "-dest" if regions else ""
            name = f"B{n // 1000}k-s{spec.split(':')[0]}{suffix}"
            tables[name] = budget_policy.build(con, "base", "budget_values", n,
                                               f"budget_{n}_{spec.replace(':', '_')}{suffix.replace('-', '_')}",
                                               {"sight": sight, "food": food, "support": support}, regions)
    results = metrics.compare(con, tables, "evidence", "wikivoyage", "draft_places", "towns", "famous_items")
    if regions:
        for name, table in tables.items():
            results[name].update(_destination_cover(con, table, regions))
    with open(os.path.join(args.out, "budget-metrics.json"), "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=1)
    with open(os.path.join(args.out, "budget-metrics.md"), "w", encoding="utf-8") as f:
        for keys in (["places", "sights", "food", "support", "storage_mb", "enrich_embed_usd"],
                     ["famous_items", "famous_wd", "famous_wv", "famous_food", "hidden", "hidden_food", "draft_places"],
                     ["cells_destination_cover", "cells_food_cover", "dishes", "dishes_per_cell", "food_chain_share",
                      "meal_median", "meal_ok", "town_ok", "town_food_median", "town_food_kept20"],
                     ["in_destinations", "destinations_gate6", "destinations_floor", "min_sight", "min_meal",
                      "min_lodging"]):
            f.write(metrics.render(results, keys) + "\n\n")
    print(open(os.path.join(args.out, "budget-metrics.md"), encoding="utf-8").read())


def _regions(con, destinations_json, country):
    """여행지 사전(destinations.json)의 country 여행지 반경에 기준 선별 행을 배정한 표 이름."""
    with open(destinations_json, encoding="utf-8") as f:
        data = json.load(f)
    items = data if isinstance(data, list) else data["destinations"]
    con.execute("CREATE OR REPLACE TABLE destinations (name VARCHAR, lat DOUBLE, lon DOUBLE, km DOUBLE)")
    con.executemany("INSERT INTO destinations VALUES (?, ?, ?, ?)",
                    [(d["name"], d["lat"], d["lng"], d["radiusKm"]) for d in items if d["country"] == country])
    con.execute("CREATE OR REPLACE TEMP TABLE base_kept AS SELECT * FROM base WHERE kept AND source <> 'osm'")
    return budget_policy.assign_destinations(con, "base_kept", "destinations", "budget_destinations")


# 장소 부족 관문을 6일 일정에서 넘는 최소치(날마다 관광 1·끼니 2·숙소 1).
GATE_DAYS = 6


def _destination_cover(con, table, regions):
    """여행지 반경 안에 담긴 행 비율, 장소 부족 관문·최소치를 넘는 여행지 수, 여행지별 최소 개수."""
    floors = budget_policy.DESTINATION_FLOORS
    row = con.execute(f"""
        WITH k AS (SELECT t.*, r.region, v.kind_class FROM {table} t LEFT JOIN {regions} r USING (source, source_id)
                   LEFT JOIN budget_values v USING (source, source_id) WHERE t.kept),
        d AS (SELECT name AS region FROM destinations),
        c AS (SELECT d.region,
                     count(k.source) FILTER (WHERE k.role IN ('destination', 'experience', 'general')) AS sight,
                     count(k.source) FILTER (WHERE k.role = 'food' AND k.kind_class <> 'cafe') AS meal,
                     count(k.source) FILTER (WHERE k.kind_class = 'lodging') AS lodging
              FROM d LEFT JOIN k USING (region) GROUP BY 1)
        SELECT (SELECT round(avg((region IS NOT NULL)::INT), 3) FROM k),
               count(*) FILTER (WHERE sight >= {GATE_DAYS} AND meal >= {2 * GATE_DAYS} AND lodging >= 1),
               count(*) FILTER (WHERE sight >= {floors['sight']} AND meal >= {floors['meal']}
                                AND lodging >= {floors['lodging']}),
               min(sight), min(meal), min(lodging), count(*)
        FROM c
    """).fetchone()
    return {"in_destinations": row[0], "destinations_gate6": f"{row[1]}/{row[6]}", "destinations_floor": f"{row[2]}/{row[6]}",
            "min_sight": row[3], "min_meal": row[4], "min_lodging": row[5]}


if __name__ == "__main__":
    main()
