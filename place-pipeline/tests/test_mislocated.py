"""좌표가 틀린 동명 중복 행. 합성 표에 돌린다."""
import duckdb

from pipeline import candidates, mislocated

ROWS = ("source VARCHAR, source_id VARCHAR, name VARCHAR, lat DOUBLE, lon DOUBLE, role VARCHAR, pool_category VARCHAR, "
        "address VARCHAR")


def setup():
    con = duckdb.connect()
    candidates.register_functions(con)
    con.execute(f"CREATE TABLE r ({ROWS})")
    con.execute("CREATE TABLE w (qid VARCHAR, sitelinks INTEGER, lat DOUBLE, lon DOUBLE, ja VARCHAR, en VARCHAR)")
    return con


def row(con, sid, name, lat, address, category="Arts and Entertainment > Museum"):
    con.execute("INSERT INTO r VALUES ('overture', ?, ?, ?, 139.73, 'destination', ?, ?)", [sid, name, lat, category, address])


def flagged(con):
    mislocated.find(con, "r", "w")
    return {x[0] for x in con.execute("SELECT source_id FROM mislocated").fetchall()}


def test_far_row_with_same_street_as_near_row_is_flagged():
    con = setup()
    con.execute("INSERT INTO w VALUES ('Q1', 12, 35.6605, 139.73, '森美術館', 'Mori Art Museum')")
    row(con, "near", "森美術館 (Mori Art Museum)", 35.6604, "六本木6-10-1")
    row(con, "far", "森美術館", 35.70, "港区六本木６−１０−１")
    assert flagged(con) == {"far"}


def test_same_name_elsewhere_with_other_street_is_kept():
    con = setup()
    con.execute("INSERT INTO w VALUES ('Q2', 6, 34.274, 139.73, '淡嶋神社', NULL)")
    row(con, "near", "淡嶋神社", 34.274, "加太118", "Community and Government > Spiritual Center")
    row(con, "other", "淡嶋神社", 35.574, "根岸2-7-1", "Community and Government > Spiritual Center")
    assert flagged(con) == set()


def test_name_shared_by_two_items_and_large_features_are_skipped():
    con = setup()
    con.execute("INSERT INTO w VALUES ('Q3', 10, 35.0, 139.73, '大通公園', NULL), ('Q4', 5, 36.0, 139.73, '大通公園', NULL)")
    row(con, "near", "大通公園", 35.0, "大通西1", "Landmarks and Outdoors > Plaza")
    row(con, "far", "大通公園", 35.1, "大通西7", "Landmarks and Outdoors > Plaza")
    con.execute("INSERT INTO w VALUES ('Q5', 20, 35.3, 139.73, '富士山', NULL)")
    row(con, "summit", "富士山", 35.3, "富士山1", "Landmarks and Outdoors > Mountain")
    row(con, "trail", "富士山", 35.4, "富士山2", "Landmarks and Outdoors > Mountain")
    assert flagged(con) == set()
