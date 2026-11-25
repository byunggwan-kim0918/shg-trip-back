"""장소 이름 규칙 읽기. 합성 테이블에 돌린다."""
from pipeline import candidates, readings

from test_candidates import con, fs, ov  # noqa: F401


def test_readings_come_from_linked_item_and_own_names(con):
    ov(con, "river", "浅野川")
    ov(con, "farm", "ファーム富田 (Farm Tomita)")
    ov(con, "shop", "ユーグレナモール")
    con.execute("""CREATE TABLE wd AS SELECT * FROM (VALUES
        ('Q1', 0.0, 0.0, 6, '浅野川', 'Asano River', 'Asano', true, false))
        AS t(qid, lat, lon, sitelinks, ja, en, ko, jawiki, kowiki)""")
    candidates.build(con, "ov", "2026-11-18.0", None, None)
    con.execute("CREATE TABLE rows AS SELECT source, source_id FROM candidates")
    con.execute("CREATE TABLE links AS SELECT 'overture' AS source, 'river' AS source_id, 'Q1' AS qid")
    readings.build(con, "rows", "links", "candidates", "wd")
    got = {r[0]: (r[1], r[2], set(r[3]), r[4])
           for r in con.execute("SELECT source_id, wd_ko, reading, readings, latin FROM name_readings").fetchall()}
    assert got["river"] == (None, "아사노 강", {"아사노 강", "아사노"}, [])
    assert got["farm"] == (None, None, {"파무", "도미타"}, ["Farm"])
    assert got["shop"] == (None, "유구레나모루", {"유구레나모루"}, [])
