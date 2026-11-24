"""OSM 연결·보강. 합성 풀과 OSM 추출 표에 돌린다."""
from pipeline import candidates, evidence, osm_link, selection

from test_candidates import OTARU, con, ov  # noqa: F401

COLUMNS = ("osm_type VARCHAR, osm_id BIGINT, lat DOUBLE, lon DOUBLE, extent_m DOUBLE, name VARCHAR, name_ja VARCHAR, name_en VARCHAR, "
           "name_ko VARCHAR, primary_key VARCHAR, primary_value VARCHAR, tag_count INTEGER, cuisine VARCHAR, "
           "opening_hours VARCHAR, website VARCHAR, \"contact:website\" VARCHAR, phone VARCHAR, \"contact:phone\" VARCHAR, "
           "heritage VARCHAR, wikipedia VARCHAR, wikidata VARCHAR, brand VARCHAR, \"brand:wikidata\" VARCHAR, "
           "tags MAP(VARCHAR, VARCHAR)")


def at(meters_north, km_east=0):
    return (OTARU[0] + meters_north / 111000, OTARU[1] + km_east / 81)


def osm_row(con, osm_type, osm_id, coord, name, key, value, name_ko=None, cuisine=None, brand=None, wikidata=None,
            extent_m=0, tags=None):
    con.execute("INSERT INTO osm VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?, 5, ?, NULL, NULL, NULL, NULL, NULL, "
                "NULL, NULL, ?, ?, NULL, ?)",
                [osm_type, osm_id, coord[0], coord[1], extent_m, name, name_ko, key, value, cuisine, wikidata, brand, tags or {}])


def run(con, with_osm=True):
    con.execute(f"CREATE TABLE IF NOT EXISTS osm ({COLUMNS})")
    candidates.build(con, "ov", "2026-11-18.0", None, None)
    evidence.build(con, "candidates", None)
    selection.build(con, "evidence", "candidates", None)
    if with_osm:
        osm_link.build(con, "evidence", "candidates", "osm", None, "selection", lat_range=(42.0, 44.0))
        selection.build(con, "evidence", "candidates", None, table="selection_osm", osm="osm_evidence")
    return {r[0]: r[1:] for r in con.execute(
        f"SELECT source_id, outcome, reason FROM {'selection_osm' if with_osm else 'selection'}").fetchall()}


def test_links_and_relations(con):
    con.execute(f"CREATE TABLE osm ({COLUMNS})")
    ov(con, "shrine", "稲荷社", hierarchy=("religious_organization", "shinto_shrine"), coord=at(0))
    osm_row(con, "node", 1, at(20), "稲荷社", "amenity", "place_of_worship", name_ko="이나리샤")
    ov(con, "park", "手宮公園", hierarchy=("geographic_entities", "park"), coord=at(0, 2))
    osm_row(con, "way", 2, at(300, 2), "手宮公園", "leisure", "park")
    ov(con, "cafe", "白樺", hierarchy=("food_and_drink", "cafe"), coord=at(0, 4))
    osm_row(con, "node", 3, at(10, 4), "白樺", "natural", "tree")
    ov(con, "temple", "龍宮寺", hierarchy=("religious_organization", "buddhist_temple"), coord=at(0, 6))
    osm_row(con, "node", 4, at(20, 6), "龍宮寺 山門", "historic", "gate")
    ov(con, "onsen", "雪割草の湯", hierarchy=("geographic_entities", "hot_springs"), coord=at(0, 8))
    osm_row(con, "node", 5, at(20, 8), "雪割草の湯", "amenity", "parking")
    run(con)
    assert con.execute("SELECT count(*) FROM osm_links WHERE source_id = 'onsen'").fetchone()[0] == 0
    pairs = {(r[0], r[1]) for r in con.execute(
        "SELECT p.source_id, o.relation || ':' || o.rule FROM osm_pairs o JOIN pool_rows p USING (pool_rid)").fetchall()}
    assert ("shrine", "SAME:name_near") in pairs
    assert ("park", "SAME:name_area") in pairs
    assert ("cafe", "DISTINCT:role_conflict") in pairs
    assert ("temple", "PART_OF:part_marker") in pairs
    assert con.execute("SELECT value FROM place_names WHERE source_id = 'shrine' AND lang = 'ko'").fetchone() == ("이나리샤",)


def test_osm_signals_only_add(con):
    con.execute(f"CREATE TABLE osm ({COLUMNS})")
    ov(con, "rock", "ゴジラ岩", hierarchy=("geographic_entities", "monument"), coord=at(0))
    osm_row(con, "node", 1, at(10), "ゴジラ岩", "tourism", "attraction")
    ov(con, "plain", "名もなき岩", hierarchy=("geographic_entities", "monument"), coord=at(0, 3))
    ov(con, "starbucks", "スターバックス", hierarchy=("food_and_drink", "cafe"), coord=at(0, 6))
    osm_row(con, "node", 2, at(5, 6), "スターバックス", "amenity", "cafe", brand="Starbucks")
    before = run(con, with_osm=False)
    after = run(con)
    assert after["rock"][0] == "recommend" and before["rock"][0] != "recommend"
    assert after["plain"] == before["plain"]
    assert after["starbucks"] == ("situational", "chain")


def test_landform_links_far_and_lifts_misfiled_sight_and_flagship(con):
    con.execute(f"CREATE TABLE osm ({COLUMNS})")
    ov(con, "aso", "阿蘇山", hierarchy=("geographic_entities", "mountain"), coord=at(0))
    osm_row(con, "node", 1, at(1500), "阿蘇山", "natural", "volcano", wikidata="Q733710")
    ov(con, "valley", "地獄谷", hierarchy=("geographic_entities", "public_plaza"), coord=at(0, 5))
    osm_row(con, "node", 2, at(30, 5), "地獄谷", "tourism", "attraction")
    ov(con, "honten", "赤福 本店", hierarchy=("food_and_drink", "restaurant", "japanese_restaurant"),
       brand="Akafuku", coord=at(0, 10))
    after = run(con)
    rules = dict(con.execute("SELECT source_id, rule FROM osm_links").fetchall())
    assert rules["aso"] == "name_landform"
    assert after["valley"][0] == "recommend"
    assert after["honten"][1] != "chain"


def test_wide_relation_links_within_its_extent_but_not_to_shops(con):
    con.execute(f"CREATE TABLE osm ({COLUMNS})")
    ov(con, "oze", "尾瀬ヶ原", hierarchy=("geographic_entities", "nature_reserve"), coord=at(0))
    osm_row(con, "relation", 1, at(3500), "尾瀬ヶ原", "natural", "wetland", extent_m=4000)
    ov(con, "far", "尾瀬ヶ原", hierarchy=("geographic_entities", "nature_reserve"), coord=at(9000))
    ov(con, "shop", "大雪", hierarchy=("food_and_drink", "restaurant"), coord=at(0, 20))
    osm_row(con, "relation", 2, at(3000, 20), "大雪", "boundary", "national_park", extent_m=40000)
    run(con)
    rules = dict(con.execute("SELECT source_id, rule FROM osm_links").fetchall())
    assert rules == {"oze": "name_extent"}


def test_wikidata_shared_by_differently_named_objects_is_not_place_evidence(con):
    con.execute(f"CREATE TABLE osm ({COLUMNS})")
    ov(con, "works", "トープラ東海工場", hierarchy=("geographic_entities", "monument"), coord=at(0))
    osm_row(con, "node", 1, at(10), "トープラ東海工場", "tourism", "attraction", wikidata="Q9")
    osm_row(con, "node", 2, at(10, 30), "トープラ大阪工場", "tourism", "attraction", wikidata="Q9")
    ov(con, "peak", "鋸山", hierarchy=("geographic_entities", "mountain"), coord=at(0, 10))
    osm_row(con, "node", 3, at(10, 10), "鋸山", "natural", "peak", wikidata="Q5")
    osm_row(con, "way", 4, at(30, 10), "鋸山", "tourism", "attraction", wikidata="Q5")
    con.execute("CREATE TABLE wd AS SELECT * FROM (VALUES ('Q9', 4), ('Q5', 2)) AS t(qid, sitelinks)")
    run(con)
    osm_link.build(con, "evidence", "candidates", "osm", "wd", "selection", lat_range=(42.0, 44.0))
    sitelinks = dict(con.execute("SELECT source_id, osm_wd_sitelinks FROM osm_evidence").fetchall())
    assert sitelinks == {"works": None, "peak": 2}
