"""풀에 없는 OSM 장소 추가 실험. 합성 풀과 OSM 추출 표에 돌린다."""
from pipeline import osm_link, osm_places, selection

from test_candidates import con, ov  # noqa: F401
from test_osm_link import COLUMNS, at, osm_row, run


def test_unpaired_travel_places_join_the_pool_and_go_through_selection(con):
    con.execute(f"CREATE TABLE osm ({COLUMNS})")
    ov(con, "lake", "知床五湖", hierarchy=("geographic_entities", "lake"), coord=at(0))
    osm_row(con, "way", 1, at(30), "知床五湖", "natural", "water")
    osm_row(con, "node", 2, at(0, 3), "羅臼湖", "natural", "water", wikidata="Q1", tags={"water": "lake"})
    osm_row(con, "way", 3, at(40, 3), "羅臼湖", "natural", "water")
    osm_row(con, "node", 7, at(0, 15), "長浜鼻", "natural", "cape")
    osm_row(con, "node", 8, at(0, 18), "ため池", "natural", "water", tags={"water": "reservoir"})
    osm_row(con, "node", 9, at(0, 21), "憩いゾーン", "tourism", "attraction")
    osm_row(con, "node", 4, at(0, 6), "知床駐車場", "amenity", "parking")
    osm_row(con, "node", 5, at(0, 9), "羅臼清掃社", "craft", "cleaning")
    ov(con, "cafe", "喫茶ポプラ", hierarchy=("food_and_drink", "cafe"), coord=at(0, 12))
    osm_row(con, "node", 6, at(10, 12), "Poplar", "amenity", "cafe")
    run(con)
    con.execute("CREATE TABLE wd AS SELECT 'Q1' AS qid, 3 AS sitelinks")
    osm_places.build(con, "JP", "2026-11-24", "wd")
    status = dict(con.execute("SELECT source_id, status FROM osm_places").fetchall())
    assert status == {"node/2": "new", "way/3": "osm_duplicate", "node/6": "possible_duplicate", "node/7": "new",
                      "node/8": "new", "node/9": "new"}
    selection.build(con, "pool_evidence", "pool_candidates", None, table="selection_plus", osm="pool_osm_evidence")
    added = {r[0]: r[1:] for r in con.execute(
        "SELECT source_id, pool_category, outcome, reason FROM selection_plus WHERE source = 'osm'").fetchall()}
    assert added["node/2"] == ("Landmarks and Outdoors > Lake", "recommend", "notable")
    assert added["node/7"][1:] == ("situational", "low_notability")
    assert added["node/8"][0] == "Landmarks and Outdoors > Reservoir"
    assert added["node/9"][1] != "recommend"
    assert con.execute("SELECT count(*) FROM selection_plus WHERE source <> 'osm'").fetchone()[0] == 2
