"""풀 안 같은 장소 판정·병합. 합성 테이블에 후보·증거·역할부터 다시 만든다."""
from pipeline import candidates, dedup, evidence, selection

from test_candidates import OTARU, con, fs, ov  # noqa: F401


def near(m):
    return (OTARU[0] + m / 111000, OTARU[1])


def run(con, wikidata=None):
    candidates.build(con, "ov", "2026-11-18.0", "fs", "test")
    evidence.build(con, "candidates", "fs", wikidata_raw=wikidata)
    selection.build(con, "evidence", "candidates", "fs")
    dedup.build(con, "evidence", "candidates", "selection")
    pairs = {}
    for a, b, decision in con.execute("SELECT a_name, b_name, relation || ':' || rule FROM dedup_pairs").fetchall():
        pairs[(a, b)] = pairs[(b, a)] = decision
    kept = {r[0] for r in con.execute("SELECT source_id FROM evidence_dedup").fetchall()}
    return pairs, kept


def test_same_source_duplicates_merge_and_keep_their_ids(con):
    for i in range(3):
        ov(con, f"g{i}", "ゴジラ岩", hierarchy=("geographic_entities", "rock_formation"), coord=near(i * 10))
    _, kept = run(con)
    assert len(kept & {"g0", "g1", "g2"}) == 1
    members = con.execute("SELECT source_id FROM place_clusters WHERE status = 'merged' ORDER BY 1").fetchall()
    assert [m[0] for m in members] == ["g0", "g1", "g2"]


def test_gate_is_part_of_shrine_and_both_stay(con):
    ov(con, "shrine", "櫛田神社", hierarchy=("religious_organization", "shinto_shrine"))
    ov(con, "gate", "櫛田神社 中神門", hierarchy=("religious_organization", "shinto_shrine"), coord=near(30))
    pairs, kept = run(con)
    assert pairs[("櫛田神社", "櫛田神社 中神門")] == "PART_OF:part_marker"
    assert kept >= {"shrine", "gate"}
    selection.build(con, "evidence_dedup", "candidates", "fs", table="selection_dedup")
    gate = con.execute("SELECT outcome, reason FROM selection_dedup WHERE source_id = 'gate'").fetchone()
    assert gate == ("situational", "part_of")


def test_same_name_apart_or_different_role_is_not_merged(con):
    ov(con, "branch_a", "ほっともっと", coord=near(0))
    ov(con, "branch_b", "ほっともっと", coord=near(120))
    ov(con, "temple", "清水寺", hierarchy=("religious_organization", "buddhist_temple"), coord=near(2000))
    ov(con, "cafe", "清水寺", hierarchy=("food_and_drink", "cafe"), coord=near(2020))
    pairs, kept = run(con)
    assert pairs[("ほっともっと", "ほっともっと")] == "REVIEW:name_apart"
    assert pairs[("清水寺", "清水寺")] == "DISTINCT:role_conflict"
    assert kept >= {"branch_a", "branch_b", "temple", "cafe"}


def test_cross_source_same_place_merges_with_combined_evidence(con):
    ov(con, "o1", "かね八", coord=near(0))
    fs(con, "f1", "かね八 本店", coord=near(20), label="Dining and Drinking > Restaurant")
    con.execute("UPDATE ov SET phones = ['+81134220001'] WHERE id = 'o1'")
    con.execute("UPDATE fs SET tel = '0134-22-0001' WHERE fsq_place_id = 'f1'")
    candidates.build(con, "ov", "2026-11-18.0", "fs", "test")
    evidence.build(con, "candidates", "fs")
    assert con.execute("SELECT count(*) FROM evidence").fetchone()[0] == 1


def test_shop_inside_mall_is_part_of_it(con):
    ov(con, "mall", "サンモール", hierarchy=("shopping", "shopping_mall"))
    ov(con, "shop", "雑貨店 サンモール", hierarchy=("shopping", "gift_shop"), coord=near(15))
    pairs, kept = run(con)
    assert pairs[("サンモール", "雑貨店 サンモール")] == "PART_OF:inside_container"
    assert kept >= {"mall", "shop"}


def test_same_wikidata_item_merges_even_with_different_names(con):
    ov(con, "ja", "櫛田神社", hierarchy=("religious_organization", "shinto_shrine"), common={"en": "Kushida Shrine"})
    ov(con, "en", "Kushida Shrine", hierarchy=("religious_organization", "shinto_shrine"), coord=near(80))
    con.execute(f"""CREATE TABLE wd AS SELECT * FROM (VALUES
        ('Q865839', {OTARU[0]}, {OTARU[1]}, 7, '櫛田神社', 'Kushida Shrine', NULL, true, false))
        AS t(qid, lat, lon, sitelinks, ja, en, ko, jawiki, kowiki)""")
    _, kept = run(con, wikidata="wd")
    assert len(kept & {"ja", "en"}) == 1


def test_rows_of_two_different_wikidata_items_are_not_merged(con):
    ov(con, "a", "春日神社", hierarchy=("religious_organization", "shinto_shrine"))
    ov(con, "b", "春日神社", hierarchy=("religious_organization", "shinto_shrine"), coord=near(20))
    con.execute(f"""CREATE TABLE wd AS SELECT * FROM (VALUES
        ('Q1', {OTARU[0]}, {OTARU[1]}, 3, '春日神社', NULL, NULL, true, false),
        ('Q2', {near(20)[0]}, {near(20)[1]}, 2, '春日神社', NULL, NULL, true, false))
        AS t(qid, lat, lon, sitelinks, ja, en, ko, jawiki, kowiki)""")
    _, kept = run(con, wikidata="wd")
    assert kept >= {"a", "b"}
    assert con.execute("SELECT DISTINCT status FROM place_clusters").fetchall() == [("conflicting_ids",)]


def test_facility_sharing_the_parents_phone_is_not_merged(con):
    ov(con, "park", "川越運動公園", hierarchy=("geographic_entities", "park"))
    ov(con, "gym", "川越運動公園総合体育館", hierarchy=("sports_and_recreation", "bowling_alley"), coord=near(80))
    con.execute("UPDATE ov SET phones = ['+81492222222']")
    pairs, kept = run(con)
    assert pairs[("川越運動公園", "川越運動公園総合体育館")] == "PART_OF:inside_container"
    assert kept >= {"park", "gym"}


def test_notable_part_stays_when_its_parent_is_less_notable(con):
    ov(con, "temple", "遊行寺", hierarchy=("religious_organization", "buddhist_temple"))
    ov(con, "hall", "遊行寺 本堂", hierarchy=("religious_organization", "buddhist_temple"), coord=near(40))
    con.execute(f"""CREATE TABLE wd AS SELECT * FROM (VALUES
        ('Q1', {near(40)[0]}, {near(40)[1]}, 4, '遊行寺 本堂', NULL, NULL, true, false))
        AS t(qid, lat, lon, sitelinks, ja, en, ko, jawiki, kowiki)""")
    run(con, wikidata="wd")
    selection.build(con, "evidence_dedup", "candidates", "fs", table="selection_dedup")
    assert con.execute("SELECT outcome FROM selection_dedup WHERE source_id = 'hall'").fetchone()[0] == "recommend"
