"""저장 정책. 선별 표 모양의 합성 표에 돌린다."""
import duckdb

from pipeline import storage_policy
from pipeline.storage_policy import Policy

COLUMNS = ("source VARCHAR, source_id VARCHAR, role VARCHAR, outcome VARCHAR, reason VARCHAR, lat DOUBLE, lon DOUBLE, "
           "dish VARCHAR, pool_category VARCHAR, sig_wikivoyage BOOLEAN, sig_wikidata BOOLEAN, flagship BOOLEAN, "
           "local_specialty BOOLEAN, chain BOOLEAN, notability VARCHAR, food_quality DOUBLE, food_bar DOUBLE, "
           "info_level VARCHAR")


def row(con, sid, role, outcome, lat, dish="Ramen", quality=0.7, listed=False, source="overture", reason="food_quality"):
    con.execute("INSERT INTO sel VALUES (?, ?, ?, ?, ?, ?, 141.0, ?, ?, ?, false, false, false, false, 'low', ?, 0.65, "
                "'partial')", [source, sid, role, outcome, reason, lat, dish, dish, listed, quality])


def test_dense_groups_shrink_sparse_groups_keep_everything():
    con = duckdb.connect()
    con.execute(f"CREATE TABLE sel ({COLUMNS})")
    for i in range(30):
        row(con, f"city{i}", "food", "recommend", 43.001, quality=0.7 + i / 1000)
    row(con, "listed", "food", "recommend", 43.001, quality=0.66, listed=True)
    for i in range(2):
        row(con, f"town{i}", "food", "recommend", 44.001)
        row(con, f"town_sit{i}", "food", "situational", 44.001, quality=0.5)
    for i in range(10):
        row(con, f"temple{i}", "destination", "recommend", 43.001, dish=None)
    row(con, "hotel", "support", "situational", 43.001, dish="Hotel")
    row(con, "osm_lake", "destination", "recommend", 44.001, dish=None, source="osm")
    row(con, "meiji", "general", "recommend", 43.001, dish="Church")
    row(con, "bridge", "general", "situational", 45.001, dish="Bridge")
    storage_policy.build(con, Policy("q", tiers=("recommend", "situational"), exponent=0.5), "sel")
    kept = {r[0] for r in con.execute("SELECT source_id FROM stored_q WHERE kept").fetchall()}
    city = {k for k in kept if k.startswith("city")}
    assert len(city) + ("listed" in kept) == 6
    assert "listed" in kept and "city29" in kept and "city0" not in kept
    assert {"town0", "town1"} <= kept and len({k for k in kept if k.startswith("town_sit")}) == 1
    assert all(f"temple{i}" in kept for i in range(10))
    assert "hotel" in kept and "osm_lake" not in kept and {"meiji", "bridge"} <= kept
    storage_policy.build(con, Policy("all", tiers=("recommend", "situational"), include_osm=True), "sel")
    assert con.execute("SELECT count(*) FILTER (WHERE kept) FROM stored_all").fetchone()[0] == 49


def test_raised_food_bar_keeps_traveler_known_places():
    con = duckdb.connect()
    con.execute(f"CREATE TABLE sel ({COLUMNS})")
    row(con, "plain", "food", "recommend", 43.0, quality=0.68)
    row(con, "known", "food", "recommend", 43.0, quality=0.4, listed=True, reason="notable_food")
    row(con, "bridge", "general", "situational", 43.0, dish="Bridge")
    row(con, "specialty", "food", "recommend", 43.0, quality=0.62, reason="local_specialty")
    storage_policy.build(con, Policy("bar", food_bar_raise=0.05), "sel")
    assert dict(con.execute("SELECT source_id, kept FROM stored_bar").fetchall()) == {"plain": False, "known": True,
                                                                                            "bridge": False,
                                                                                            "specialty": True}


def test_quota_only_on_food_keeps_every_situational_sight():
    con = duckdb.connect()
    con.execute(f"CREATE TABLE sel ({COLUMNS})")
    for i in range(30):
        row(con, f"mall{i}", "experience", "situational", 43.001, dish="Shopping Mall")
        row(con, f"cafe{i}", "food", "situational", 43.001, dish="Cafe", quality=0.5)
    storage_policy.build(con, Policy("f", tiers=("recommend", "situational"), exponent=0.5, quota_roles=("food",)), "sel")
    kept = {r[0] for r in con.execute("SELECT source_id FROM stored_f WHERE kept").fetchall()}
    assert len({k for k in kept if k.startswith("mall")}) == 30
    assert len({k for k in kept if k.startswith("cafe")}) == 6


def test_recommend_basis_fills_with_situational_only_where_recommended_are_few():
    con = duckdb.connect()
    con.execute(f"CREATE TABLE sel ({COLUMNS})")
    for i in range(16):
        row(con, f"rec{i}", "food", "recommend", 43.001)
    for i in range(50):
        row(con, f"sit{i}", "food", "situational", 43.001, quality=0.5)
        row(con, f"rural{i}", "food", "situational", 44.001, quality=0.5)
    storage_policy.build(con, Policy("r", tiers=("recommend", "situational"), exponent=0.5, quota_roles=("food",),
                                     quota_basis="recommend"), "sel")
    kept = {r[0] for r in con.execute("SELECT source_id FROM stored_r WHERE kept").fetchall()}
    assert len({k for k in kept if k.startswith("rec")}) == 4 and not {k for k in kept if k.startswith("sit")}
    assert len({k for k in kept if k.startswith("rural")}) == 3
