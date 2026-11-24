"""저장 정책 비교 지표."""
import duckdb

from pipeline import metrics


def test_retention_coverage_diversity_and_travel_utility():
    con = duckdb.connect()
    con.execute("""CREATE TABLE ev AS SELECT * FROM (VALUES
        ('o', 'famous', 10, false, NULL::VARCHAR), ('o', 'hidden', NULL, true, NULL), ('o', 'soba', NULL, true, NULL),
        ('o', 'ramen', NULL, false, NULL), ('o', 'chain', NULL, false, NULL))
        AS t(source, source_id, wd_sitelinks, independent_confirm, web_kind)""")
    con.execute("""CREATE TABLE st AS SELECT * FROM (VALUES
        ('o', 'famous', 'destination', 'recommend', 'Landmarks and Outdoors > Castle', NULL::VARCHAR, NULL::VARCHAR, false, true),
        ('o', 'hidden', 'destination', 'situational', 'Landmarks and Outdoors > Park', NULL, NULL, false, false),
        ('o', 'soba', 'food', 'recommend', 'Dining and Drinking > Restaurant > Soba Restaurant', NULL, NULL, false, true),
        ('o', 'ramen', 'food', 'situational', 'Dining and Drinking > Restaurant', 'ramen', NULL, false, true),
        ('o', 'chain', 'food', 'situational', 'Dining and Drinking > Cafe, Coffee, and Tea House', NULL, NULL, true, false))
        AS t(source, source_id, role, outcome, pool_category, osm_cuisine, osm_signal, chain, kept)""")
    con.execute("ALTER TABLE st ADD COLUMN lat DOUBLE DEFAULT 43.0")
    con.execute("ALTER TABLE st ADD COLUMN lon DOUBLE DEFAULT 141.0")
    con.execute("ALTER TABLE st ADD COLUMN notability VARCHAR DEFAULT 'low'")
    con.execute("CREATE TABLE dp AS SELECT * FROM (VALUES ('castle', 'o', 'famous'), ('park', 'o', 'hidden')) "
                "AS t(place_key, source, source_id)")
    con.execute("CREATE TABLE towns AS SELECT 1 AS town_id, 43.001 AS lat, 141.0 AS lon")
    con.execute("ALTER TABLE st ADD COLUMN name VARCHAR")
    con.execute("UPDATE st SET name = CASE source_id WHEN 'famous' THEN '熊本城 天守閣' WHEN 'hidden' THEN '熊本城' END")
    con.execute("""CREATE TABLE wd AS SELECT * FROM (VALUES ('Q1', 43.0, 141.0, 40, '熊本城', 'Kumamoto Castle'),
                   ('Q2', 43.0, 141.0, 40, 'ラーメン', NULL)) AS t(qid, lat, lon, sitelinks, ja, en)""")
    con.create_function("name_key", lambda v: v.lower() if v else "", ["VARCHAR"], "VARCHAR")
    metrics.famous_item_matches(con, "wd", "st")
    assert {r[0] for r in con.execute("SELECT qid FROM famous_items").fetchall()} == {"Q1"}
    r = metrics.compare(con, {"a": "st"}, "ev", draft_places="dp", towns="towns", famous_items="famous_items")["a"]
    assert r["famous_items"] == 1.0
    assert r["places"] == 3 and r["food"] == 2
    assert r["famous_wd"] == 1.0 and r["hidden"] == 0.0 and r["hidden_food"] == 1.0
    assert r["dishes"] == 2 and r["food_chain_share"] == 0.0
    assert r["draft_places"] == 0.5 and r["meal_median"] == 2
    assert r["town_food_median"] == 2 and r["town_ok"] == 0.0
    assert "| a |" in metrics.render({"a": r})


def test_draft_place_counts_only_rows_around_its_best_match():
    con = duckdb.connect()
    con.execute("CREATE TABLE ev AS SELECT 'o' AS source, 'x' AS source_id, NULL::INTEGER AS wd_sitelinks, "
                "false AS independent_confirm, NULL::VARCHAR AS web_kind WHERE false")
    con.execute("""CREATE TABLE st AS SELECT * FROM (VALUES
        ('o', 'shrine', 'destination', 'recommend', 'high', 43.0, 141.0, false),
        ('o', 'far_shop', 'general', 'situational', 'low', 43.2, 141.0, true))
        AS t(source, source_id, role, outcome, notability, lat, lon, kept)""")
    for col in ("pool_category", "osm_cuisine", "osm_signal", "name"):
        con.execute(f"ALTER TABLE st ADD COLUMN {col} VARCHAR")
    con.execute("ALTER TABLE st ADD COLUMN chain BOOLEAN DEFAULT false")
    con.execute("CREATE TABLE dp AS SELECT 'kushida' AS place_key, 'o' AS source, unnest(['shrine', 'far_shop']) AS source_id")
    r = metrics.compare(con, {"a": "st"}, "ev", draft_places="dp")["a"]
    assert r["draft_places"] == 0.0
