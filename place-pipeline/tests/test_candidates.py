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
        fs(con, "flight", "KE790 FUK-ICN / Korean Air", label="Travel and Transportation > Transport Hub > Airport > Plane")
        fs(con, "train_service", "スカイライナー22号", label="Travel and Transportation > Train")
        fs(con, "station", "小樽駅", label="Travel and Transportation > Transport Hub > Rail Station")
        fs(con, "michinoeki", "道の駅スタープラザ芦別", label="Travel and Transportation > Rest Area")
        ov(con, "park_toilet", "小樽公園 公衆トイレ", hierarchy=("geographic_entities", "park"))
        ov(con, "bus_depot", "北海道中央バス 小樽営業所", hierarchy=("travel_and_transportation", "bus_station"))
        ov(con, "branch_food", "政寿司 支店", hierarchy=("food_and_drink", "restaurant", "sushi_restaurant"))
        ov(con, "apartment", "シティマンション小笹", hierarchy=("attractions_and_activities", "historic_site"))
        ov(con, "weekly", "ウィークリーマンション小樽", hierarchy=("lodging", "hotel"))
        ov(con, "maison", "マ・メゾン", hierarchy=("food_and_drink", "restaurant", "french_restaurant"))
        ov(con, "company", "サクラコーポレーション", hierarchy=("shopping", "souvenir_store"))
        ov(con, "home_store", "ユニバーサルホーム小樽店", hierarchy=("shopping", "discount_store"))
        ov(con, "konbini", "ローソン 小樽駅前店", hierarchy=("shopping", "specialty_store", "flowers_and_gifts_store"))
        result = build(con)
        assert result[("overture", "ok")] is None
        assert result[("overture", "dentist")] == "non_travel_category"
        assert result[("overture", "unmapped")] == "unmapped_category"
        assert result[("overture", "closed")] == "closed"
        assert result[("overture", "chain")] is None
        assert result[("overture", "tourist_chain")] is None
        assert result[("overture", "platform")] == "junk_name"
        assert result[("overture", "noname")] == "no_name"
        assert result[("fsq", "flagged")] == "flagged"
        assert result[("fsq", "stale_food")] == "stale"
        assert result[("fsq", "old_temple")] is None
        assert result[("fsq", "very_old_temple")] == "stale"
        assert result[("fsq", "parking")] == "non_travel_category"
        assert result[("fsq", "flight")] == "non_travel_category"
        assert result[("fsq", "train_service")] == "non_travel_category"
        assert result[("fsq", "station")] is None
        assert result[("fsq", "michinoeki")] is None
        assert result[("overture", "park_toilet")] == "junk_name"
        assert result[("overture", "bus_depot")] == "junk_name"
        assert result[("overture", "branch_food")] is None
        assert result[("overture", "home_store")] is None
        assert result[("overture", "apartment")] == "junk_name"
        assert result[("overture", "weekly")] is None
        assert result[("overture", "maison")] is None
        assert result[("overture", "company")] is None
        assert result[("overture", "konbini")] == "junk_name"

    def test_certain_and_review_tiers(self, con):
        ov(con, "old_branch", "旧早川支店", hierarchy=("attractions_and_activities", "historic_site"))
        ov(con, "shop_branch", "古梅園京都支店", hierarchy=("shopping", "arts_crafts_and_hobby_store"))
        ov(con, "trailhead", "大株歩道入口", hierarchy=("geographic_entities", "nature_reserve"))
        ov(con, "exit", "天神南駅 1番出口", hierarchy=("travel_and_transportation", "train_station"))
        ov(con, "boat", "羅臼観光船乗り場", hierarchy=("travel_and_transportation", "pier"))
        ov(con, "onsen_office", "羅臼温泉", hierarchy=("services_and_business", "corporate_or_business_office",
                                                    "travel_company"))
        ov(con, "grocery", "スーパー", hierarchy=("shopping", "food_and_beverage_store", "grocery_store"))
        ov(con, "unknown", "いなか浜", hierarchy=("mystery",))
        fs(con, "inappropriate", "祇園菓舎", flags=["inappropriate"])
        fs(con, "closed_flag", "大喜", flags=["closed"])
        fs(con, "scenic_road", "西部林道", label="Travel and Transportation > Road")
        fs(con, "intersection", "天神交差点", label="Travel and Transportation > Road > Intersection")
        fs(con, "transport_root", "日本海ロジテック", label="Travel and Transportation")
        build(con)
        rows = {r[0]: r[1:] for r in con.execute("SELECT source_id, exclusion, exclusion_tier FROM candidates").fetchall()}
        assert rows["old_branch"] == (None, None)
        assert rows["shop_branch"] == ("review_name", "review")
        assert rows["trailhead"] == (None, None)
        assert rows["exit"] == ("junk_name", "certain")
        assert rows["boat"] == ("review_name", "review")
        assert rows["onsen_office"] == ("review_category", "review")
        assert rows["grocery"] == ("non_travel_category", "certain")
        assert rows["unknown"] == ("unmapped_category", "review")
        assert rows["inappropriate"] == (None, None)
        assert rows["closed_flag"] == ("review_flag", "review")
        assert rows["scenic_road"] == ("review_category", "review")
        assert rows["intersection"] == ("non_travel_category", "certain")
        assert rows["transport_root"] == ("review_category", "review")

    def test_mixed_store_categories_keep_travel_leaves(self, con):
        ov(con, "vintage", "古着屋", hierarchy=("shopping", "second_hand_store"))
        ov(con, "pawn", "質屋", hierarchy=("shopping", "second_hand_store", "pawn_shop"))
        ov(con, "spa", "Ubud Traditional Spa", hierarchy=("lifestyle_services", "wellness_service", "spa"))
        ov(con, "salon", "美容室", hierarchy=("lifestyle_services", "beauty_salon"))
        fs(con, "sento", "神明湯", label="Business and Professional Services > Health and Beauty Service > Bath House")
        result = build(con)
        assert result[("overture", "vintage")] is None
        assert result[("overture", "pawn")] == "non_travel_category"
        assert result[("overture", "spa")] is None
        assert result[("overture", "salon")] == "non_travel_category"
        assert result[("fsq", "sento")] is None

    def test_only_dated_sources_set_refreshed_at(self, con):
        ov(con, "meta_only", "かね八", updated=OLD_6Y)
        ov(con, "fsq_old", "政寿司", fsq_id="f9", updated=OLD_3Y)
        result = build(con, fsq=False)
        assert result[("overture", "meta_only")] is None
        assert result[("overture", "fsq_old")] == "stale"
        assert con.execute("SELECT refreshed_at FROM candidates WHERE source_id = 'meta_only'").fetchone()[0] is None

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
            ("o1", "f1", "exact_name", "AUTO")]

    def test_unique_phone_links_names_that_contain_each_other(self, con):
        ov(con, "o1", "博多一双中洲店", coord=(OTARU[0] + 0.005, OTARU[1]))
        ov(con, "o2", "Tsubame Donut", coord=(OTARU[0] + 0.001, OTARU[1]))
        fs(con, "f1", "博多一双")
        fs(con, "f2", "ツバメドーナツ")
        con.execute("UPDATE ov SET phones = ['+81 92-262-6787'] WHERE id = 'o1'")
        con.execute("UPDATE ov SET phones = ['+81924092222'] WHERE id = 'o2'")
        con.execute("UPDATE fs SET tel = '092-262-6787' WHERE fsq_place_id = 'f1'")
        con.execute("UPDATE fs SET tel = '092-409-2222' WHERE fsq_place_id = 'f2'")
        build(con)
        identity.build_links(con)
        assert sorted(con.execute("SELECT a_id, b_id, basis, decision FROM source_links").fetchall()) == [
            ("o1", "f1", "phone_name", "AUTO"), ("o2", "f2", "phone", "REVIEW")]

    def test_name_containing_the_other_links_unless_generic(self, con):
        ov(con, "o1", "韓国家庭料理・韓味", coord=(OTARU[0] + 0.0001, OTARU[1]))
        fs(con, "f1", "韓味")
        ov(con, "o2", "Bar", coord=(OTARU[0] + 0.003, OTARU[1]))
        fs(con, "f2", "Bar Eau-de-Vie", coord=(OTARU[0] + 0.0031, OTARU[1]), label="Dining and Drinking > Bar")
        ov(con, "o3", "R&E", coord=(OTARU[0] + 0.006, OTARU[1]))
        fs(con, "f3", "Boon Coon Records", coord=(OTARU[0] + 0.0061, OTARU[1]))
        ov(con, "o4", "アル・プラザ津幡", hierarchy=("shopping", "shopping_mall"), coord=(OTARU[0] + 0.009, OTARU[1]))
        fs(con, "f4", "メガネの三城アル・プラザ津幡店", coord=(OTARU[0] + 0.0091, OTARU[1]), label="Retail > Eyecare Store")
        build(con)
        identity.build_links(con)
        assert con.execute("SELECT a_id, b_id, basis FROM source_links").fetchall() == [("o1", "f1", "name_contains")]

    def test_shared_phone_does_not_link(self, con):
        ov(con, "gucci", "Gucci")
        ov(con, "cafe", "うまか食堂")
        fs(con, "f1", "阪急うまか")
        con.execute("UPDATE ov SET phones = ['+81924611381']")
        con.execute("UPDATE fs SET tel = '092-461-1381'")
        build(con)
        identity.build_links(con)
        assert con.execute("SELECT count(*) FROM source_links").fetchone()[0] == 0

    def test_unmapped_row_links_by_exact_name(self, con):
        ov(con, "o1", "いなか浜", hierarchy=("mystery",))
        fs(con, "f1", "いなか浜", label="Landmarks and Outdoors > Beach")
        build(con)
        identity.build_links(con)
        assert con.execute("SELECT basis, decision FROM source_links").fetchall() == [("exact_name", "AUTO")]

    def test_excluded_rows_are_still_linked(self, con):
        ov(con, "o1", "歯科", hierarchy=("health_care", "dentist"))
        fs(con, "f1", "歯科", label="Health and Medicine > Dentist")
        build(con)
        identity.build_links(con)
        assert con.execute("SELECT a_id, b_id FROM source_links").fetchall() == [("o1", "f1")]

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


def test_country_rules_apply_only_to_their_country(con):
    ov(con, "jp_toilet", "公衆トイレ", hierarchy=("geographic_entities", "park"))
    ov(con, "kr_toilet", "公衆トイレ", hierarchy=("geographic_entities", "park"), coord=(OTARU[0] + 0.01, OTARU[1]))
    con.execute("UPDATE ov SET addresses = [struct_pack(freeform := 'addr', locality := 'Busan', postcode := NULL, "
                "region := NULL, country := 'KR')] WHERE id = 'kr_toilet'")
    candidates.build(con, "ov", "2026-11-18.0", None, None)
    rows = dict(con.execute("SELECT source_id, exclusion FROM candidates").fetchall())
    assert rows == {"jp_toilet": "junk_name", "kr_toilet": None}
