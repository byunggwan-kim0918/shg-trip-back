"""예산 정책. 기준 선별 모양의 합성 표에 돌린다."""
import duckdb

from pipeline import budget_policy, candidates

BASE = ("source VARCHAR, source_id VARCHAR, name VARCHAR, lat DOUBLE, lon DOUBLE, role VARCHAR, outcome VARCHAR, "
        "info_level VARCHAR, food_quality DOUBLE, notability VARCHAR, chain BOOLEAN, flagship BOOLEAN, "
        "local_specialty BOOLEAN, dish VARCHAR, pool_category VARCHAR, osm_signal VARCHAR, kept BOOLEAN")


def setup():
    con = duckdb.connect()
    candidates.register_functions(con)
    con.execute(f"CREATE TABLE base ({BASE})")
    con.execute("CREATE TABLE evidence (source VARCHAR, source_id VARCHAR, wd_qid VARCHAR, wd_sitelinks INTEGER, "
                "independent_confirm BOOLEAN, web_kind VARCHAR)")
    con.execute("CREATE TABLE wikivoyage (source VARCHAR, source_id VARCHAR, wv_articles BIGINT)")
    con.execute("CREATE TABLE settlements (lat DOUBLE, lon DOUBLE, pop BIGINT)")
    con.execute("CREATE TABLE wikidata (qid VARCHAR, sitelinks INTEGER, kowiki BOOLEAN, lat DOUBLE, lon DOUBLE, "
                "ja VARCHAR, en VARCHAR)")
    return con


def place(con, sid, name, lat, role="food", dish="Dining and Drinking > Restaurant > Ramen Restaurant",
          category=None, quality=0.7, kept=True, outcome="recommend"):
    con.execute("INSERT INTO base VALUES ('overture', ?, ?, ?, 141.0, ?, ?, 'partial', ?, 'low', false, false, false, "
                "?, ?, NULL, ?)", [sid, name, lat, role, outcome, quality if role == "food" else None,
                                   dish if role == "food" else None, category or dish, kept])


def kept(con, places, shares):
    budget_policy.values(con, "base", "evidence", "wikivoyage", "settlements", "wikidata")
    budget_policy.build(con, "base", "budget_values", places, "out", shares)
    return {r[0] for r in con.execute("SELECT source_id FROM out WHERE kept").fetchall()}


def test_famous_sight_first_and_sparse_cell_before_dense_repeats():
    con = setup()
    for i in range(10):
        place(con, f"dense{i}", f"ラーメン{i}", 43.001, quality=0.8)
    place(con, "sparse", "田舎ラーメン", 43.501, quality=0.6)
    place(con, "temple", "浅草寺", 43.001, role="destination", category="Community and Government > Spiritual Center")
    place(con, "park", "名もない公園", 43.002, role="destination", category="Landmarks and Outdoors > Park")
    con.execute("INSERT INTO wikidata VALUES ('Q1', 40, true, 43.001, 141.0, '浅草寺', 'Senso-ji')")
    out = kept(con, 4, {"sight": 0.25, "food": 0.75, "support": 0})
    assert "temple" in out and "park" not in out
    assert "sparse" in out and len({k for k in out if k.startswith("dense")}) == 2


def test_tenant_does_not_take_landmark_fame_and_one_row_is_best():
    con = setup()
    place(con, "tower", "東京タワー", 43.001, role="destination", category="Landmarks and Outdoors > Monument")
    place(con, "tower_dup", "東京タワー", 43.0012, role="destination", category="Landmarks and Outdoors > Monument")
    place(con, "shop", "東京タワー店 ラーメン", 43.001)
    con.execute("INSERT INTO wikidata VALUES ('Q2', 60, false, 43.001, 141.0, '東京タワー', 'Tokyo Tower')")
    budget_policy.values(con, "base", "evidence", "wikivoyage", "settlements", "wikidata")
    rows = dict(con.execute("SELECT source_id, famous FROM budget_values").fetchall())
    assert rows == {"tower": True, "tower_dup": False, "shop": False}


def test_unused_share_moves_to_other_groups():
    assert budget_policy._quotas(10, {"sight": 0.5, "food": 0.5, "support": 0}, {"sight": 2, "food": 100}) == \
        {"sight": 2, "food": 8, "support": 0}


def test_rows_outside_baseline_never_kept():
    con = setup()
    place(con, "in", "店A", 43.0)
    place(con, "out", "店B", 43.0, kept=False)
    assert kept(con, 10, budget_policy.DEFAULT_SHARES) == {"in"}


def test_small_destination_gets_floor_and_rows_outside_destinations_are_dropped():
    con = setup()
    for i in range(20):
        place(con, f"city{i}", f"都会ラーメン{i}", 43.0 + i * 0.001, quality=0.9)
        place(con, f"sight{i}", f"名所{i}", 43.0 + i * 0.001, role="destination",
              category="Landmarks and Outdoors > Park")
    place(con, "town_meal", "村の食堂", 44.0, quality=0.4)
    place(con, "town_sight", "村の滝", 44.0, role="destination", category="Landmarks and Outdoors > Waterfall")
    place(con, "nowhere", "山奥の茶屋", 45.0, quality=0.95)
    con.execute("CREATE TABLE dest (name VARCHAR, lat DOUBLE, lon DOUBLE, km DOUBLE)")
    con.execute("INSERT INTO dest VALUES ('City', 43.0, 141.0, 10), ('Town', 44.0, 141.0, 5)")
    budget_policy.values(con, "base", "evidence", "wikivoyage", "settlements", "wikidata")
    budget_policy.assign_destinations(con, "base", "dest", "regions")
    budget_policy.build(con, "base", "budget_values", 6, "out", {"sight": 0.5, "food": 0.5, "support": 0},
                        regions="regions", floors={"sight": 1, "meal": 1, "cafe": 0, "lodging": 0})
    kept = {r[0] for r in con.execute("SELECT source_id FROM out WHERE kept").fetchall()}
    assert {"town_meal", "town_sight"} <= kept
    assert "nowhere" not in kept
    assert len(kept) == 6
