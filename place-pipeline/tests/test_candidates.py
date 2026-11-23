"""공통 후보 변환·적재 적합성·출처 간 연결. 공급처 스키마의 합성 테이블에 돌린다(외부 호출 없음)."""
from datetime import date, timedelta

import duckdb
import pytest

from pipeline import candidates, identity

RECENT = (date.today() - timedelta(days=30)).isoformat()
OLD_3Y = (date.today() - timedelta(days=365 * 3)).isoformat()
OLD_6Y = (date.today() - timedelta(days=365 * 6)).isoformat()

OTARU = (43.1976, 141.0025)


@pytest.fixture
def con():
    c = duckdb.connect()
    candidates.register_functions(c)
    c.execute("""
        CREATE TABLE ov (
          id VARCHAR, version INTEGER,
          names STRUCT("primary" VARCHAR, common MAP(VARCHAR, VARCHAR),
                       rules STRUCT(variant VARCHAR, "language" VARCHAR, "value" VARCHAR)[]),
          taxonomy STRUCT("primary" VARCHAR, hierarchy VARCHAR[], alternates VARCHAR[]),
          basic_category VARCHAR, confidence DOUBLE, operating_status VARCHAR,
          addresses STRUCT(freeform VARCHAR, locality VARCHAR, postcode VARCHAR, region VARCHAR, country VARCHAR)[],
          websites VARCHAR[], phones VARCHAR[],
          brand STRUCT(wikidata VARCHAR, "names" STRUCT("primary" VARCHAR)),
          sources STRUCT(property VARCHAR, dataset VARCHAR, license VARCHAR, record_id VARCHAR, update_time VARCHAR)[],
          bbox STRUCT(xmin DOUBLE, xmax DOUBLE, ymin DOUBLE, ymax DOUBLE))
    """)
    c.execute("""
        CREATE TABLE fs (
          fsq_place_id VARCHAR, name VARCHAR, latitude DOUBLE, longitude DOUBLE, address VARCHAR, locality VARCHAR,
          region VARCHAR, country VARCHAR, date_created VARCHAR, date_refreshed VARCHAR, date_closed VARCHAR,
          fsq_category_labels VARCHAR[], unresolved_flags VARCHAR[], website VARCHAR, tel VARCHAR)
    """)
    return c


def ov(con, pid, name, coord=OTARU, hierarchy=("food_and_drink", "restaurant"), status=None, brand=None,
       fsq_id=None, updated=RECENT, common=None):
    sources = [("", "meta", "CDLA-Permissive-2.0", "m" + pid, updated)]
    if fsq_id:
        sources.append(("", "Foursquare", "Apache-2.0", fsq_id, updated))
    con.execute("""
        INSERT INTO ov SELECT ?, 1,
          struct_pack("primary" := ?, common := ?::MAP(VARCHAR, VARCHAR),
                      rules := []::STRUCT(variant VARCHAR, "language" VARCHAR, "value" VARCHAR)[]),
          struct_pack("primary" := ?, hierarchy := ?, alternates := []::VARCHAR[]),
          NULL, 0.9, ?,
          [struct_pack(freeform := 'addr', locality := 'Otaru', postcode := NULL, region := NULL, country := 'JP')],
          [], [], struct_pack(wikidata := NULL, "names" := struct_pack("primary" := ?)),
          list_transform(?, s -> struct_pack(property := s[1], dataset := s[2], license := s[3],
                                             record_id := s[4], update_time := s[5])),
          struct_pack(xmin := ?, xmax := ?, ymin := ?, ymax := ?)
    """, [pid, name, common or {}, hierarchy[-1], list(hierarchy), status, brand,
          [list(s) for s in sources], coord[1], coord[1], coord[0], coord[0]])


def fs(con, pid, name, coord=OTARU, label="Dining and Drinking > Restaurant", refreshed=RECENT, closed=None,
       flags=None):
    con.execute("INSERT INTO fs VALUES (?, ?, ?, ?, '', '', '', 'JP', ?, ?, ?, ?, ?, '', '')",
                [pid, name, coord[0], coord[1], refreshed, refreshed, closed, [label], flags or []])


def build(con, overture=True, fsq=True):
    candidates.build(con, "ov" if overture else None, "2026-11-18.0", "fs" if fsq else None, "test")
    return {(r[0], r[1]): r[2] for r in con.execute("SELECT source, source_id, exclusion FROM candidates").fetchall()}


class TestNormalization:
    def test_overture_row_maps_to_fsq_label_and_keeps_names(self, con):
        ov(con, "a", "小樽運河", hierarchy=("geographic_entities", "canal"), common={"ko": "오타루 운하", "en": "Otaru Canal"})
        build(con, fsq=False)
        row = con.execute("SELECT category, country, lat, lon, names, license FROM candidates").fetchone()
        assert row[0] == "Landmarks and Outdoors > Waterfront"
        assert row[1] == "JP" and row[2] == OTARU[0] and row[3] == OTARU[1]
        assert {(n["value"], n["lang"], n["kind"]) for n in row[4]} == {
            ("小樽運河", "und", "primary"), ("오타루 운하", "ko", "common"), ("Otaru Canal", "en", "common")}
        assert row[5] == "CDLA-Permissive-2.0"

    def test_fsq_row_keeps_label(self, con):
        fs(con, "f1", "北一硝子", label="Retail > Gift Store")
        build(con, overture=False)
        assert con.execute("SELECT category, refreshed_at FROM candidates").fetchone()[0] == "Retail > Gift Store"


class TestEligibility:
    def test_reasons(self, con):
        ov(con, "ok", "千春鮨", hierarchy=("food_and_drink", "restaurant", "sushi_restaurant"))
        ov(con, "dentist", "歯科", hierarchy=("health_care", "dentist"))
        ov(con, "unmapped", "謎", hierarchy=("mystery",))
        ov(con, "closed", "閉店", status="permanently_closed")
        ov(con, "chain", "Starbucks", hierarchy=("food_and_drink", "cafe"), brand="Starbucks")
        ov(con, "tourist_chain", "一蘭 小樽店", hierarchy=("food_and_drink", "restaurant", "ramen_restaurant"),
           brand="Ichiran")
        ov(con, "platform", "小樽駅 2番線", hierarchy=("travel_and_transportation", "train_station"))
        ov(con, "noname", " · ")
        fs(con, "flagged", "偽物", flags=["doesnt_exist"])
        fs(con, "stale_food", "古い食堂", refreshed=OLD_3Y)
        fs(con, "old_temple", "古い寺", label="Community and Government > Spiritual Center > Buddhist Temple",
           refreshed=OLD_3Y)
        fs(con, "very_old_temple", "廃寺", label="Community and Government > Spiritual Center > Buddhist Temple",
           refreshed=OLD_6Y)
        fs(con, "parking", "駐車", label="Travel and Transportation > Parking")
        result = build(con)
        assert result[("overture", "ok")] is None
        assert result[("overture", "dentist")] == "non_travel_category"
        assert result[("overture", "unmapped")] == "unmapped_category"
        assert result[("overture", "closed")] == "closed"
        assert result[("overture", "chain")] == "chain"
        assert result[("overture", "tourist_chain")] is None
        assert result[("overture", "platform")] == "junk_name"
        assert result[("overture", "noname")] == "no_name"
        assert result[("fsq", "flagged")] == "flagged"
        assert result[("fsq", "stale_food")] == "stale"
        assert result[("fsq", "old_temple")] is None
        assert result[("fsq", "very_old_temple")] == "stale"
        assert result[("fsq", "parking")] == "non_travel_category"

    def test_popularity_signals_do_not_exclude(self, con):
        ov(con, "quiet", "小さな食堂")
        assert build(con, fsq=False)[("overture", "quiet")] is None


class TestLinks:
    def test_source_id_link_is_auto(self, con):
        ov(con, "o1", "Cafe A", fsq_id="f1", hierarchy=("food_and_drink", "cafe"))
        fs(con, "f1", "カフェA", label="Dining and Drinking > Cafe, Coffee, and Tea House")
        build(con)
        identity.build_links(con)
        assert con.execute("SELECT a_id, b_id, basis, decision FROM source_links").fetchall() == [
            ("o1", "f1", "source_id", "AUTO")]

    def test_exact_name_nearby_links_once(self, con):
        ov(con, "o1", "Le Marathon")
        fs(con, "f1", "le marathon", coord=(OTARU[0] + 0.0002, OTARU[1]))
        fs(con, "far", "Le Marathon", coord=(OTARU[0] + 0.01, OTARU[1]))
        build(con)
        identity.build_links(con)
        assert con.execute("SELECT a_id, b_id, basis, decision FROM source_links").fetchall() == [
            ("f1", "o1", "exact_name", "AUTO")]

    def test_exact_name_with_different_top_category_needs_review(self, con):
        ov(con, "o1", "Otaru Canal", hierarchy=("geographic_entities", "canal"))
        fs(con, "f1", "Otaru Canal", label="Dining and Drinking > Restaurant")
        build(con)
        identity.build_links(con)
        assert con.execute("SELECT decision FROM source_links").fetchall() == [("REVIEW",)]

    def test_id_link_is_not_duplicated_by_name_link(self, con):
        ov(con, "o1", "Same", fsq_id="f1")
        fs(con, "f1", "Same")
        build(con)
        identity.build_links(con)
        assert con.execute("SELECT basis FROM source_links").fetchall() == [("source_id",)]
