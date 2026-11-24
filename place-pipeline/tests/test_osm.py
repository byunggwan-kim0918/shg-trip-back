"""OSM 추출. ST_ReadOSM과 같은 열을 가진 합성 표로 돌린다."""
import duckdb

from pipeline import osm


def test_named_pois_are_kept_and_ways_get_an_approximate_center(tmp_path):
    con = duckdb.connect()
    con.execute("""
        CREATE TABLE src (kind VARCHAR, id BIGINT, tags MAP(VARCHAR, VARCHAR), refs BIGINT[], ref_types VARCHAR[], lat DOUBLE, lon DOUBLE)
    """)
    con.execute("""INSERT INTO src VALUES
        ('node', 1, MAP {'amenity': 'restaurant', 'name': 'かね八', 'cuisine': 'sushi'}, NULL, NULL, 43.0, 141.0),
        ('node', 2, MAP {'amenity': 'bench'}, NULL, NULL, 43.0, 141.0),
        ('node', 3, MAP {'highway': 'bus_stop', 'name': '停留所'}, NULL, NULL, 43.0, 141.0),
        ('node', 4, MAP {'waterway': 'waterfall', 'name': '華厳の滝'}, NULL, NULL, 36.7, 139.5),
        ('node', 5, MAP {'waterway': 'stream', 'name': '小川'}, NULL, NULL, 36.7, 139.5),
        ('node', 10, MAP {}, NULL, NULL, 43.10, 141.10),
        ('node', 11, MAP {}, NULL, NULL, 43.12, 141.12),
        ('way', 100, MAP {'leisure': 'park', 'name': '小樽公園', 'wikidata': 'Q1'}, [10, 11], NULL, NULL, NULL)""")
    out = str(tmp_path / "pois.parquet")
    m = osm.extract(con, "missing.pbf", out, source="src", log=lambda *_: None)
    rows = {r[0]: r[1:] for r in con.execute(
        f"SELECT name, osm_type, round(lat, 2), primary_key, cuisine, wikidata FROM read_parquet('{out}')").fetchall()}
    assert set(rows) == {"かね八", "小樽公園", "華厳の滝"}
    assert rows["華厳の滝"][2] == "waterway"
    assert rows["かね八"] == ("node", 43.0, "amenity", "sushi", None)
    assert rows["小樽公園"] == ("way", 43.11, "leisure", None, "Q1")
    assert m["rows"] == 3


def test_relations_get_a_member_center_and_only_travel_boundaries_are_kept(tmp_path):
    con = duckdb.connect()
    con.execute("""
        CREATE TABLE src (kind VARCHAR, id BIGINT, tags MAP(VARCHAR, VARCHAR), refs BIGINT[], ref_types VARCHAR[],
                          lat DOUBLE, lon DOUBLE)
    """)
    con.execute("""INSERT INTO src VALUES
        ('node', 10, MAP {}, NULL, NULL, 36.90, 139.20),
        ('node', 11, MAP {}, NULL, NULL, 36.92, 139.24),
        ('node', 12, MAP {}, NULL, NULL, 36.94, 139.26),
        ('way', 200, MAP {}, [10, 11, 12], NULL, NULL, NULL),
        ('relation', 1, MAP {'natural': 'wetland', 'name': '尾瀬ヶ原', 'type': 'multipolygon'},
            [200], ['way'], NULL, NULL),
        ('relation', 2, MAP {'route': 'hiking', 'name': '尾瀬沼探勝路'}, [200, 10], ['way', 'node'], NULL, NULL),
        ('relation', 3, MAP {'boundary': 'protected_area', 'protect_class': '2', 'name': '尾瀬国立公園'},
            [200], ['way'], NULL, NULL),
        ('relation', 4, MAP {'boundary': 'protected_area', 'protect_class': '4', 'name': '鳥獣保護区'},
            [200], ['way'], NULL, NULL),
        ('relation', 5, MAP {'route': 'bus', 'name': '路線バス'}, [200], ['way'], NULL, NULL),
        ('relation', 6, MAP {'public_transport': 'stop_area', 'name': '尾瀬戸倉'}, [10], ['node'], NULL, NULL)""")
    out = str(tmp_path / "pois.parquet")
    osm.extract(con, "missing.pbf", out, source="src", log=lambda *_: None)
    rows = {r[0]: r[1:] for r in con.execute(
        f"SELECT name, osm_type, primary_key, round(lat, 2), extent_m > 1000 FROM read_parquet('{out}')").fetchall()}
    assert set(rows) == {"尾瀬ヶ原", "尾瀬沼探勝路", "尾瀬国立公園"}
    assert rows["尾瀬ヶ原"] == ("relation", "natural", 36.91, True)
    assert rows["尾瀬沼探勝路"][1] == "route"
    assert rows["尾瀬国立公園"][1] == "boundary"
