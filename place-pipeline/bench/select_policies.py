"""
저장 정책 비교 실험. 선별 표(selection_plus)에 정책마다 kept 열을 달고 같은 지표로 잰다. LLM·임베딩 호출 없음.
평가 자료:
  eval/jp-draft-places.tsv : 일정 초안(eval-records/m4)이 이름으로 고른 장소 → 일본어 이름(정답 표)
  GeoNames 국가 덤프       : 인구 1천~5만 거주지(소도시)
  Wikidata 스냅샷          : 언어판이 많은 항목(유명 명소, 지명 항목 제외)

실행: python bench/select_policies.py --db build/country/japan.duckdb --geonames build/geonames/JP.txt --out build/select
"""
import argparse
import csv
import json
import os
import sys

import duckdb

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from pipeline import candidates, metrics, storage_policy  # noqa: E402
from pipeline.storage_policy import Policy  # noqa: E402

RS = ("recommend", "situational")
FG = ("food", "general")
POLICIES = [
    Policy("rec"),
    Policy("all", tiers=RS),
    Policy("bar05", food_bar_raise=0.05),
    Policy("bar10", food_bar_raise=0.10),
    Policy("qR50", exponent=0.5),
    Policy("qRS50", tiers=RS, exponent=0.5),
    Policy("S+Frec", tiers=RS, food_tiers=("recommend",), exponent=0.5, quota_roles=("general",)),
    Policy("S+FrecQ50", tiers=RS, food_tiers=("recommend",), exponent=0.5, quota_roles=FG),
    Policy("S+FQ65", tiers=RS, exponent=0.65, quota_roles=FG),
    Policy("S+FQ50", tiers=RS, exponent=0.5, quota_roles=FG),
    Policy("S+FQ65r", tiers=RS, exponent=0.65, quota_roles=FG, quota_basis="recommend"),
    Policy("S+FQ50r", tiers=RS, exponent=0.5, quota_roles=FG, quota_basis="recommend"),
    Policy("S+FQ40r", tiers=RS, exponent=0.4, quota_roles=FG, quota_basis="recommend"),
    Policy("S+FQ50r-f5", tiers=RS, exponent=0.5, quota_roles=FG, quota_basis="recommend", floor=5),
    Policy("S+FQ50r+osm", tiers=RS, exponent=0.5, quota_roles=FG, quota_basis="recommend", include_osm=True),
    Policy("rec+osm", include_osm=True),
]
# 초안 도시 범위(위도·경도 상자). 같은 이름의 다른 도시 장소를 잇지 않기 위한 경계다.
DRAFT_CITY_BOXES = {"sapporo": (42.9, 43.25, 140.9, 141.5), "fukuoka": (33.3, 33.75, 130.1, 130.7),
                    "tokyo": (35.5, 35.82, 139.55, 139.95)}
TOWN_POPULATION = (1000, 50000)
_GEONAMES_COLUMNS = ("{'id': 'BIGINT', 'name': 'VARCHAR', 'ascii': 'VARCHAR', 'alt': 'VARCHAR', 'lat': 'DOUBLE', "
                     "'lon': 'DOUBLE', 'fclass': 'VARCHAR', 'fcode': 'VARCHAR', 'cc': 'VARCHAR', 'cc2': 'VARCHAR', "
                     "'a1': 'VARCHAR', 'a2': 'VARCHAR', 'a3': 'VARCHAR', 'a4': 'VARCHAR', 'pop': 'BIGINT', "
                     "'elev': 'VARCHAR', 'dem': 'VARCHAR', 'tz': 'VARCHAR', 'mod': 'VARCHAR'}")


def draft_places(con, tsv, selection="selection_plus", names="pool_candidates", table="eval_draft_places",
                 wikidata=None):
    """정답 표의 장소(kind=place)마다 풀 행. 도시 안에 이름이 같은 Wikidata 항목이 있으면 항목 좌표 둘레에서 이름이
    맞는 행들, 없으면 이름이 정확히 같은 행들, 그것도 없으면 이름에 품은 행들이다."""
    with open(tsv, encoding="utf-8") as f:
        rows = [r for r in csv.DictReader((line for line in f if not line.startswith("#")), delimiter="\t")]
    con.execute("CREATE OR REPLACE TEMP TABLE draft_gold (city VARCHAR, ja VARCHAR)")
    con.executemany("INSERT INTO draft_gold VALUES (?, ?)", [(r["city"], r["ja"]) for r in rows if r["kind"] == "place"])
    boxes = " UNION ALL ".join(f"SELECT '{k}' AS city, {a} AS a, {b} AS b, {x} AS x, {y} AS y"
                               for k, (a, b, x, y) in DRAFT_CITY_BOXES.items())
    con.execute(f"""
        CREATE OR REPLACE TABLE {table} AS
        WITH g AS (SELECT city, city || ':' || split_part(ja, '|', 1) AS place_key,
                          name_key(unnest(string_split(ja, '|'))) AS k FROM draft_gold),
        p AS (SELECT s.source, s.source_id, b.city, name_key(v) AS k
              FROM {selection} s JOIN {names} c USING (source, source_id), ({boxes}) b,
                   unnest(list_transform(c.names, n -> n.value) || [c.name]) AS u(v)
              WHERE s.lat BETWEEN b.a AND b.b AND s.lon BETWEEN b.x AND b.y),
        {_anchored(wikidata, selection, names, boxes)}
        exact AS (SELECT DISTINCT g.place_key, p.source, p.source_id FROM g JOIN p ON p.city = g.city AND p.k = g.k
                  WHERE g.place_key NOT IN (SELECT place_key FROM anchored)),
        contained AS (SELECT DISTINCT g.place_key, p.source, p.source_id FROM g JOIN p
                      ON p.city = g.city AND length(g.k) >= 3 AND contains(p.k, g.k) AND p.k <> g.k
                      WHERE g.place_key NOT IN (SELECT place_key FROM anchored))
        SELECT * FROM anchored
        UNION ALL SELECT * FROM exact
        UNION ALL SELECT * FROM contained WHERE place_key NOT IN (SELECT place_key FROM exact)
    """)
    return table


# 이름만으로 고르면 좌표가 수 km 틀린 같은 이름 행이 잡힌다. 항목 좌표에서 이 거리 안의 행만 그 장소로 본다.
DRAFT_ANCHOR_METERS = 500


def _anchored(wikidata, selection, names, boxes):
    """정답 이름과 같은 이름의 Wikidata 항목(도시 안, 언어판 최다)을 닻으로 삼아 둘레에서 이름이 맞는 행을 잇는 CTE."""
    if not wikidata:
        return "anchored AS (SELECT NULL::VARCHAR AS place_key, NULL::VARCHAR AS source, NULL::VARCHAR AS source_id WHERE false),"
    near = metrics._HAVERSINE.replace("a.", "i.").replace("b.", "s.")
    return f"""
        item AS (
            SELECT g.place_key, w.lat, w.lon,
                   [strip_accents(name_key(w.ja)), strip_accents(name_key(coalesce(w.en, '')))] AS item_keys
            FROM g JOIN {wikidata} w ON strip_accents(name_key(w.ja)) = strip_accents(g.k)
                                     OR strip_accents(name_key(coalesce(w.en, ''))) = strip_accents(g.k)
            JOIN ({boxes}) b ON b.city = g.city AND w.lat BETWEEN b.a AND b.b AND w.lon BETWEEN b.x AND b.y
            QUALIFY row_number() OVER (PARTITION BY g.place_key ORDER BY w.sitelinks DESC, w.qid) = 1
        ),
        anchored AS (
            SELECT DISTINCT i.place_key, s.source, s.source_id
            FROM item i JOIN {selection} s ON abs(s.lat - i.lat) < 0.01 AND abs(s.lon - i.lon) < 0.012
            JOIN {names} c USING (source, source_id), unnest(list_transform(c.names, n -> n.value) || [c.name]) AS u(v),
                 unnest(i.item_keys) AS ik(item_key)
            WHERE {near} <= {DRAFT_ANCHOR_METERS} AND length(item_key) >= 3
              AND (strip_accents(name_key(v)) = item_key
                   OR (contains(strip_accents(name_key(v)), item_key) AND NOT ({_TENANT.format(c="s.pool_category")}))
                   OR (length(strip_accents(name_key(v))) >= 3 AND contains(item_key, strip_accents(name_key(v)))))
        ),"""


# 명소 이름을 품은 식음료·매장(입점 가게)은 그 명소가 아니다. 상업 시설 자체(몰·백화점·시장)는 명소로 본다.
_TENANT = ("(starts_with(coalesce({c}, ''), 'Dining and Drinking') OR (starts_with(coalesce({c}, ''), 'Retail') "
           "AND NOT starts_with(coalesce({c}, ''), 'Retail > Shopping Mall') "
           "AND NOT starts_with(coalesce({c}, ''), 'Retail > Department Store') "
           "AND NOT starts_with(coalesce({c}, ''), 'Retail > Market')))")


def towns(con, geonames_txt, table="eval_towns"):
    lo, hi = TOWN_POPULATION
    con.execute(f"""
        CREATE OR REPLACE TABLE {table} AS
        SELECT id AS town_id, name, lat, lon, pop
        FROM read_csv('{geonames_txt}', delim = '\t', header = false, quote = '', columns = {_GEONAMES_COLUMNS})
        WHERE fclass = 'P' AND pop BETWEEN {lo} AND {hi}
    """)
    return table


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", required=True)
    parser.add_argument("--geonames", required=True)
    parser.add_argument("--wikidata", default="build/country/raw/wikidata/japan.parquet")
    parser.add_argument("--areas", default="build/country/raw/wikidata/japan-area-qids.parquet")
    parser.add_argument("--draft-places", default="eval/jp-draft-places.tsv")
    parser.add_argument("--bytes-per-place", type=int, default=metrics.BYTES_PER_PLACE)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    con = duckdb.connect(args.db, config={"memory_limit": "2GB", "threads": 2, "temp_directory": "build/tmp",
                                          "preserve_insertion_order": False})
    candidates.register_functions(con)
    con.execute(f"CREATE OR REPLACE TEMP TABLE area_items AS SELECT qid FROM read_parquet('{args.areas}')")
    metrics.famous_item_matches(con, f"read_parquet('{args.wikidata}')", "selection_plus", area_items="area_items")
    draft_places(con, args.draft_places, wikidata=f"read_parquet('{args.wikidata}')")
    towns(con, args.geonames)
    tables = {p.name: storage_policy.build(con, p, "selection_plus", "stored_" + _table_name(p.name)) for p in POLICIES}
    results = metrics.compare(con, tables, "pool_evidence", "wikivoyage_links", "eval_draft_places", "eval_towns",
                              "famous_items", args.bytes_per_place)
    with open(os.path.join(args.out, "policy-metrics.json"), "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=1)
    with open(os.path.join(args.out, "policy-metrics.md"), "w", encoding="utf-8") as f:
        for keys in (["places", "sights", "food", "support", "osm_rows", "storage_mb", "enrich_embed_usd"],
                     ["famous_items", "famous_wv", "famous_food", "hidden", "hidden_food", "draft_places"],
                     ["cells_destination_cover", "cells_food_cover", "dishes", "dishes_per_cell", "food_chain_share",
                      "meal_median", "meal_ok", "town_ok", "town_food_kept20"]):
            f.write(metrics.render(results, keys) + "\n\n")
    print(open(os.path.join(args.out, "policy-metrics.md"), encoding="utf-8").read())


def _table_name(name):
    return "".join(ch if ch.isalnum() else "_" for ch in name)


if __name__ == "__main__":
    main()
