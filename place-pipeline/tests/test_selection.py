"""역할·판정. 합성 테이블에 후보·증거부터 다시 만든다."""
from pipeline import candidates, evidence, selection

from test_candidates import OTARU, RECENT, con, fs, ov  # noqa: F401


def at(i):
    return (OTARU[0] + i * 0.1, OTARU[1])


def run(con, wikidata=None):
    candidates.build(con, "ov", "2026-11-18.0", "fs", "test")
    if wikidata:
        con.execute("CREATE TABLE IF NOT EXISTS no_items (qid VARCHAR)")
        con.execute("CREATE TABLE IF NOT EXISTS no_kinds (qid VARCHAR, kind VARCHAR)")
    evidence.build(con, "candidates", "fs", wikidata_raw=wikidata, wikidata_areas="no_items" if wikidata else None,
                   wikidata_brands="no_items" if wikidata else None,
                   wikidata_businesses="no_items" if wikidata else None, wikidata_kinds="no_kinds" if wikidata else None)
    selection.build(con, "evidence", "candidates", "fs")
    cur = con.execute("SELECT source_id, role, outcome, reason FROM selection")
    return {r[0]: r[1:] for r in cur.fetchall()}


def test_same_category_splits_notable_sight_from_neighborhood_facility(con):
    ov(con, "garden_park", "兼六園公園", hierarchy=("geographic_entities", "park"), coord=at(0))
    ov(con, "kids_park", "緑町児童公園", hierarchy=("geographic_entities", "park"), coord=at(1))
    ov(con, "bridge", "新橋", hierarchy=("geographic_entities", "bridge"), coord=at(2))
    ov(con, "national", "名勝 猊鼻渓", hierarchy=("geographic_entities", "bridge"), coord=at(3))
    rows = run(con)
    assert rows["kids_park"][1:] == ("exclude", "general_facility")
    assert rows["bridge"][1:] == ("exclude", "general_facility")
    assert rows["national"][1] == "recommend"


def test_wikidata_item_is_one_signal_not_the_only_one(con):
    ov(con, "falls", "無名の滝", hierarchy=("geographic_entities", "waterfall"), coord=at(0))
    con.execute("UPDATE ov SET phones = ['+81134000000'] WHERE id = 'falls'")
    ov(con, "bare_falls", "名もなき滝", hierarchy=("geographic_entities", "waterfall"), coord=at(2))
    fs(con, "f_falls", "別の滝", coord=at(5), label="Landmarks and Outdoors > Waterfall")
    rows = run(con)
    assert rows["falls"] == ("destination", "recommend", "destination")
    assert rows["f_falls"] == ("destination", "recommend", "destination")
    assert rows["bare_falls"] == ("destination", "review", "sparse_info")


def test_sparse_information_goes_to_review_not_exclusion(con):
    ov(con, "shrine", "稲荷社", hierarchy=("religious_organization", "shinto_shrine"), coord=at(0))
    con.execute("UPDATE ov SET sources = [] WHERE id = 'shrine'")
    rows = run(con)
    assert rows["shrine"][1:] == ("review", "unclear_notability")


def test_food_is_judged_by_its_own_quality_and_local_scarcity(con):
    for i in range(3):
        ov(con, f"rural{i}", f"そば処{i}", hierarchy=("food_and_drink", "restaurant", "japanese_restaurant"),
           coord=(OTARU[0] + 1, OTARU[1] + i * 0.001), fsq_id=f"fr{i}")
        fs(con, f"fr{i}", f"そば処{i}", coord=(OTARU[0] + 1, OTARU[1] + i * 0.001), refreshed=RECENT)
    con.execute("UPDATE ov SET phones = ['+8113400001' || id[6:]] WHERE id LIKE 'rural%'")
    ov(con, "chain", "Starbucks", hierarchy=("food_and_drink", "cafe"), brand="Starbucks", coord=at(4))
    ov(con, "sparse_food", "謎の食堂", hierarchy=("food_and_drink", "restaurant"), coord=at(6))
    rows = run(con)
    assert all(rows[f"rural{i}"][1] == "recommend" for i in range(3))
    assert rows["chain"][1:] == ("situational", "chain")
    assert rows["sparse_food"][1:] == ("review", "sparse_info")


def test_closure_conflict_is_reviewed_and_temples_ignore_fsq_closure(con):
    ov(con, "listed", "政寿司", fsq_id="f1", coord=at(0))
    fs(con, "f1", "政寿司", coord=at(0), closed="2024-01-01")
    ov(con, "temple", "妙照寺", fsq_id="f2", hierarchy=("religious_organization", "buddhist_temple"), coord=at(1))
    fs(con, "f2", "妙照寺", coord=at(1), closed="2024-01-01",
       label="Community and Government > Spiritual Center > Buddhist Temple")
    rows = run(con)
    assert rows["listed"][1:] == ("review", "closure_conflict")
    assert rows["temple"][1] != "exclude"


def test_massage_spa_is_excluded_but_bath_spa_is_recommended(con):
    ov(con, "massage", "リラクゼーション 天照", hierarchy=("lifestyle_services", "wellness_service", "spa"), coord=at(0))
    ov(con, "onsen", "しあわせの湯 温泉", hierarchy=("lifestyle_services", "wellness_service", "spa"), coord=at(1))
    rows = run(con)
    assert rows["massage"][1:] == ("exclude", "general_facility")
    assert rows["onsen"][1] == "recommend"


def test_plain_park_is_not_excluded_and_sento_counts_as_bath(con):
    ov(con, "park", "住吉公園", hierarchy=("geographic_entities", "park"), coord=at(0))
    ov(con, "sento", "柳川湯", hierarchy=("lifestyle_services", "wellness_service", "spa"), coord=at(1))
    rows = run(con)
    assert rows["park"][1] in ("situational", "review")
    assert rows["sento"][1] == "recommend"


def test_infrastructure_wiki_entry_does_not_make_a_bridge_recommended(con):
    ov(con, "bridge", "宮之浦大橋", hierarchy=("geographic_entities", "bridge"), coord=at(0))
    ov(con, "famous", "錦帯橋", hierarchy=("geographic_entities", "bridge"), coord=at(2))
    con.execute(f"""CREATE TABLE wd AS SELECT * FROM (VALUES
        ('Q1', {at(0)[0]}, {at(0)[1]}, 1, '宮之浦大橋', NULL, NULL, true, false),
        ('Q2', {at(2)[0]}, {at(2)[1]}, 12, '錦帯橋', NULL, NULL, true, true))
        AS t(qid, lat, lon, sitelinks, ja, en, ko, jawiki, kowiki)""")
    rows = run(con, wikidata="wd")
    assert rows["bridge"][1] != "recommend"
    assert rows["famous"][1] == "recommend"


def test_dish_overrepresented_in_its_region_lowers_the_food_bar(con):
    for i in range(25):
        ov(con, f"udon{i}", f"うどん{i}", hierarchy=("food_and_drink", "restaurant", "udon_restaurant"),
           coord=(OTARU[0], OTARU[1] + i * 0.0002))
    for i in range(120):
        ov(con, f"ramen{i}", f"ラーメン{i}", hierarchy=("food_and_drink", "restaurant", "ramen_restaurant"),
           coord=(OTARU[0] + 1, OTARU[1] + i * 0.0002))
    con.execute("UPDATE ov SET phones = ['+8113400' || lpad(id[5:], 4, '0')] WHERE id LIKE 'udon%' OR id LIKE 'ramen%'")
    rows = run(con)
    flags = dict(con.execute("SELECT source_id, local_specialty FROM selection").fetchall())
    assert flags["udon0"] and not flags["ramen0"]
    assert rows["udon0"][1:] == ("recommend", "local_specialty")
    assert rows["ramen0"][1:] == ("situational", "food")
