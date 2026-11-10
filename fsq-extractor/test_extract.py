"""
build_extract_query 규칙 테스트 — Iceberg 대신 places_os 스키마의 합성 테이블에 돌린다.

실행: cd fsq-extractor && python -m pytest test_extract.py
(이미지에는 extract.py만 들어가므로 런타임 의존성에 pytest를 넣지 않았다.)
"""
from datetime import date, timedelta

import duckdb
import pytest

from extract import CHAIN_MIN_BRANCHES, build_extract_query, detected_chains

FRESH = (date.today() - timedelta(days=30)).isoformat()
STALE_3Y = (date.today() - timedelta(days=365 * 3)).isoformat()
STALE_6Y = (date.today() - timedelta(days=365 * 6)).isoformat()

SEOUL = (37.57, 126.98)      # 광화문
BUSAN = (35.16, 129.16)      # 해운대
JEJU = (33.50, 126.53)       # 제주시
SUWON = (37.28, 127.01)      # 수원 팔달
GWANGJU = (35.15, 126.85)    # 광주 서구
DAEJEON = (36.35, 127.38)

RESTAURANT = ["Dining and Drinking > Restaurant"]
CAFE = ["Dining and Drinking > Cafe, Coffee, and Tea House"]
LANDMARK = ["Landmarks and Outdoors > Historic and Protected Site"]
HOTEL = ["Travel and Transportation > Lodging > Hotel"]


@pytest.fixture
def con():
    c = duckdb.connect()
    c.execute("""
        CREATE TABLE t (
            fsq_place_id VARCHAR, name VARCHAR, latitude DOUBLE, longitude DOUBLE,
            address VARCHAR, locality VARCHAR, region VARCHAR, country VARCHAR,
            date_closed VARCHAR, date_refreshed VARCHAR, website VARCHAR,
            fsq_category_labels VARCHAR[], unresolved_flags VARCHAR[])
    """)
    return c


def add(con, pid, name, coord, locality=None, region=None, cat=RESTAURANT,
        refreshed=FRESH, flags=None, closed=None):
    con.execute(
        "INSERT INTO t VALUES (?, ?, ?, ?, '', ?, ?, 'KR', ?, ?, '', ?, ?)",
        [pid, name, coord[0], coord[1], locality, region, closed, refreshed, cat, flags or []])


def run(con):
    return {r[0]: r[1] for r in con.execute(
        f"SELECT fsq_place_id, region FROM ({build_extract_query('t')})").fetchall()}


class TestCityMatching:
    def test_korean_locality_spellings_map_to_canonical_name(self, con):
        add(con, "a", "식당A", SEOUL, locality="서울특별시")
        add(con, "b", "식당B", SEOUL, locality="서울시")
        add(con, "c", "식당C", SEOUL, locality="Seoul")
        assert run(con) == {"a": "Seoul", "b": "Seoul", "c": "Seoul"}

    def test_neighbor_city_inside_bbox_is_excluded(self, con):
        # 서울 좌표 범위 안이지만 경기도 고양시 — 이전에 좌표만 썼다면 서울로 섞였을 행
        add(con, "a", "식당A", SEOUL, locality="고양시", region="경기도")
        assert run(con) == {}

    def test_no_text_falls_back_to_bbox(self, con):
        add(con, "a", "식당A", SEOUL)
        add(con, "b", "식당B", SEOUL, region="대한민국")
        assert run(con) == {"a": "Seoul", "b": "Seoul"}

    def test_district_locality_uses_region(self, con):
        add(con, "a", "식당A", SEOUL, locality="강남구")
        add(con, "b", "식당B", BUSAN, locality="해운대구", region="부산광역시")
        add(con, "c", "식당C", BUSAN, locality="Haeundae - gu")
        assert run(con) == {"a": "Seoul", "b": "Busan", "c": "Busan"}

    def test_jeju_both_cities(self, con):
        add(con, "a", "식당A", JEJU, locality="제주시", region="제주특별자치도")
        add(con, "b", "식당B", JEJU, locality="서귀포시")
        add(con, "c", "식당C", JEJU, locality="Jeju-si")
        assert run(con) == {"a": "Jeju", "b": "Jeju", "c": "Jeju"}

    def test_city_level_requires_locality(self, con):
        add(con, "a", "식당A", SUWON, locality="수원시", region="경기도")
        add(con, "b", "식당B", SUWON, locality="장안구", region="경기도")
        # 수원 범위 안의 용인 구 — 구 이름만으로 수원이라 하면 안 된다
        add(con, "c", "식당C", SUWON, locality="기흥구", region="경기도")
        # 도 이름만 있으면 수원인지 용인인지 모른다
        add(con, "d", "식당D", SUWON, region="경기도")
        assert run(con) == {"a": "Suwon", "b": "Suwon"}

    def test_gwangju_merged_region_label(self, con):
        label = "Jeonnam-Gwangju Special Metropolitan City"
        add(con, "a", "식당A", GWANGJU, locality="서구", region=label)
        # 같은 표기가 전남 군에도 붙는다 — 광주 아님
        add(con, "b", "식당B", GWANGJU, locality="화순군", region=label)
        assert run(con) == {"a": "Gwangju"}

    def test_sejong_inside_daejeon_bbox_is_excluded(self, con):
        add(con, "a", "식당A", DAEJEON, locality="세종특별자치시", region="세종특별자치시")
        assert run(con) == {}

    def test_geocoding_error_is_excluded(self, con):
        # 텍스트는 서울인데 좌표는 부산 — 어느 도시에도 넣지 않는다
        add(con, "a", "식당A", BUSAN, locality="서울특별시", region="서울특별시")
        assert run(con) == {}


class TestQualityFilters:
    def test_flagged_places_are_excluded(self, con):
        add(con, "a", "식당A", SEOUL, locality="서울", flags=["closed"])
        add(con, "b", "식당B", SEOUL, locality="서울", flags=["duplicate"])
        add(con, "c", "식당C", SEOUL, locality="서울", closed="2025-01-01")
        add(con, "d", "식당D", SEOUL, locality="서울")
        assert run(con) == {"d": "Seoul"}

    def test_landmarks_get_a_longer_freshness_window(self, con):
        add(con, "a", "오래된 식당", SEOUL, locality="서울", refreshed=STALE_3Y)
        add(con, "b", "첨성대", SEOUL, locality="서울", cat=LANDMARK, refreshed=STALE_3Y)
        add(con, "c", "아주 오래된 명소", SEOUL, locality="서울", cat=LANDMARK, refreshed=STALE_6Y)
        assert run(con) == {"b": "Seoul"}

    def test_franchise_dining_is_excluded(self, con):
        for i in range(CHAIN_MIN_BRANCHES):
            add(con, f"s{i}", f"스타벅스 지점{i}점", SEOUL, locality="서울", cat=CAFE)
        add(con, "bare", "스타벅스", SEOUL, locality="서울", cat=CAFE)
        add(con, "local", "동네커피 본점", SEOUL, locality="서울", cat=CAFE)
        assert run(con) == {"local": "Seoul"}

    def test_brand_below_threshold_is_kept(self, con):
        for i in range(CHAIN_MIN_BRANCHES - 1):
            add(con, f"s{i}", f"성심당 지점{i}점", SEOUL, locality="서울")
        assert len(run(con)) == CHAIN_MIN_BRANCHES - 1

    def test_latin_name_chain_is_excluded(self, con):
        # 영문 체인은 "…점" 없이 같은 이름으로 반복된다
        for i in range(CHAIN_MIN_BRANCHES - 1):
            add(con, f"s{i}", "Starbucks", SEOUL, locality="서울", cat=CAFE)
        add(con, "branch", "Starbucks 광화문점", SEOUL, locality="서울", cat=CAFE)
        add(con, "suffix", "Starbucks Coffee (Reserve)", SEOUL, locality="서울", cat=CAFE)
        add(con, "upper", "STARBUCKS", SEOUL, locality="서울", cat=CAFE)
        add(con, "glued", "Starbucksy", SEOUL, locality="서울", cat=CAFE)
        assert run(con) == {"glued": "Seoul"}

    def test_latin_brand_below_threshold_is_kept(self, con):
        for i in range(CHAIN_MIN_BRANCHES - 1):
            add(con, f"s{i}", "Fritz Coffee", SEOUL, locality="서울", cat=CAFE)
        assert len(run(con)) == CHAIN_MIN_BRANCHES - 1

    def test_generic_latin_name_is_not_a_brand(self, con):
        for i in range(CHAIN_MIN_BRANCHES):
            add(con, f"c{i}", "Cafe", SEOUL, locality="서울", cat=CAFE)
        add(con, "other", "Cafe Onion", SEOUL, locality="서울", cat=CAFE)
        assert len(run(con)) == CHAIN_MIN_BRANCHES + 1

    def test_common_korean_name_without_branch_suffix_is_kept(self, con):
        # 같은 한글 상호가 반복돼도 지점명이 없으면 체인이 아니다(고향집·서울식당 같은 흔한 이름)
        for i in range(CHAIN_MIN_BRANCHES + 5):
            add(con, f"k{i}", "고향집", SEOUL, locality="서울")
        assert len(run(con)) == CHAIN_MIN_BRANCHES + 5

    def test_latin_lodging_chain_is_kept(self, con):
        for i in range(CHAIN_MIN_BRANCHES):
            add(con, f"h{i}", "Lotte Hotel", SEOUL, locality="서울", cat=HOTEL)
        assert len(run(con)) == CHAIN_MIN_BRANCHES

    def test_detected_chains_lists_both_kinds(self, con):
        for i in range(CHAIN_MIN_BRANCHES):
            add(con, f"s{i}", f"파리바게뜨 지점{i}점", SEOUL, locality="서울")
            add(con, f"e{i}", "Paris Baguette", SEOUL, locality="서울")
        assert sorted(detected_chains(con, "t")) == [
            ("ko", "파리바게뜨", CHAIN_MIN_BRANCHES), ("latin", "paris baguette", CHAIN_MIN_BRANCHES)]

    def test_lodging_chain_is_kept(self, con):
        for i in range(CHAIN_MIN_BRANCHES):
            add(con, f"h{i}", f"신라스테이 지점{i}점", SEOUL, locality="서울", cat=HOTEL)
        assert len(run(con)) == CHAIN_MIN_BRANCHES

    def test_newlines_are_flattened(self, con):
        add(con, "a", "줄바꿈\n식당", SEOUL, locality="서울")
        name = con.execute(f"SELECT name FROM ({build_extract_query('t')})").fetchone()[0]
        assert name == "줄바꿈 식당"


# ── 해외 ─────────────────────────────────────────────────────────────────────
import unicodedata  # noqa: E402

from extract import (BRAND_PER_DESTINATION, MASS_CHAIN_MIN_BRANCHES, build_output_query,  # noqa: E402
                     build_overseas_query, build_scored_query, country_boxes, create_destination_table,
                     extract_countries, load_destinations)

SHANGHAI = (31.2304, 121.4737)
SUZHOU = (31.2989, 120.5853)
OSAKA = (34.6937, 135.5023)
HANOI = (21.0285, 105.8542)
OLD = (date.today() - timedelta(days=365 * 8)).isoformat()
STALE_18M = (date.today() - timedelta(days=548)).isoformat()

TEMPLE = ["Community and Government > Spiritual Center > Buddhist Temple"]
RESIDENTIAL = ["Community and Government > Residential Building > Apartment or Condo"]
CINEMA = ["Arts and Entertainment > Movie Theater"]
BANK = ["Business and Professional Services > Bank"]
STATION = ["Travel and Transportation > Transport Hub > Rail Station"]
HOTPOT = ["Dining and Drinking > Restaurant > Asian Restaurant > Chinese Restaurant > Hotpot Restaurant"]

DESTS = [
    {"country": "CN", "name": "Shanghai", "lat": SHANGHAI[0], "lng": SHANGHAI[1], "radiusKm": 30},
    {"country": "CN", "name": "Suzhou", "lat": SUZHOU[0], "lng": SUZHOU[1], "radiusKm": 15},
    {"country": "JP", "name": "Osaka", "lat": OSAKA[0], "lng": OSAKA[1], "radiusKm": 20},
    {"country": "JP", "name": "Kobe", "lat": 34.69, "lng": 135.20, "radiusKm": 15},
    {"country": "VN", "name": "Hanoi", "lat": HANOI[0], "lng": HANOI[1], "radiusKm": 20},
]


def create_overseas_tables(c):
    c.execute("""
        CREATE TABLE o (
            fsq_place_id VARCHAR, name VARCHAR, latitude DOUBLE, longitude DOUBLE, address VARCHAR, country VARCHAR,
            date_closed VARCHAR, date_created VARCHAR, date_refreshed VARCHAR, website VARCHAR, tel VARCHAR,
            instagram VARCHAR, facebook_id BIGINT, fsq_category_labels VARCHAR[], unresolved_flags VARCHAR[])
    """)
    create_destination_table(c, DESTS)
    return c


@pytest.fixture
def ocon():
    return create_overseas_tables(duckdb.connect())


def oadd(con, pid, name, coord, country="CN", cat=RESTAURANT, refreshed=FRESH, created=FRESH,
         tel=None, website=None, instagram=None, flags=None):
    con.execute("INSERT INTO o VALUES (?, ?, ?, ?, '', ?, NULL, ?, ?, ?, ?, ?, NULL, ?, ?)",
                [pid, name, coord[0], coord[1], country, created, refreshed, website, tel, instagram,
                 cat, flags or []])


def orun(con):
    return {r[0]: r[1] for r in con.execute(
        f"SELECT fsq_place_id, region FROM ({build_overseas_query('o', 'dest')})").fetchall()}


def offset(coord, km_north):
    return (coord[0] + km_north / 111.0, coord[1])


class TestOverseasPlacement:
    def test_assigned_to_destination_within_radius(self, ocon):
        oadd(ocon, "a", "饭店A", SHANGHAI)
        oadd(ocon, "b", "饭店B", SUZHOU)
        oadd(ocon, "far", "饭店C", offset(SHANGHAI, 40))
        oadd(ocon, "jp", "食堂", OSAKA, country="JP")
        assert orun(ocon) == {"a": "Shanghai", "b": "Suzhou", "jp": "Osaka"}

    def test_overlap_goes_to_relatively_nearer_destination(self, ocon):
        # 오사카 반경(20km) 안이지만 고베 중심이 반경 대비 더 가깝다
        oadd(ocon, "a", "食堂", (34.69, 135.30), country="JP")
        assert orun(ocon) == {"a": "Kobe"}

    def test_country_must_match(self, ocon):
        oadd(ocon, "a", "饭店", SHANGHAI, country="TW")
        assert orun(ocon) == {}


class TestOverseasFilters:
    def test_dining_needs_one_year_freshness(self, ocon):
        oadd(ocon, "old_food", "饭店", SHANGHAI, refreshed=STALE_18M)
        oadd(ocon, "old_temple", "静安寺", SHANGHAI, cat=TEMPLE, refreshed=STALE_18M)
        oadd(ocon, "old_landmark", "外滩", SHANGHAI, cat=LANDMARK, refreshed=STALE_3Y)
        assert orun(ocon) == {"old_temple": "Shanghai", "old_landmark": "Shanghai"}

    def test_non_travel_roles_are_dropped(self, ocon):
        oadd(ocon, "res", "公寓", SHANGHAI, cat=RESIDENTIAL)
        oadd(ocon, "cinema", "电影院", SHANGHAI, cat=CINEMA)
        oadd(ocon, "bank", "银行", SHANGHAI, cat=BANK)
        oadd(ocon, "temple", "玉佛寺", SHANGHAI, cat=TEMPLE)
        oadd(ocon, "hotel", "酒店", SHANGHAI, cat=HOTEL)
        assert orun(ocon) == {"temple": "Shanghai", "hotel": "Shanghai"}

    def test_sub_facility_names_are_dropped_but_not_restaurants(self, ocon):
        oadd(ocon, "platform", "大阪駅 3番線", OSAKA, country="JP", cat=STATION)
        oadd(ocon, "exit", "Namba Station Exit 14", OSAKA, country="JP", cat=STATION)
        oadd(ocon, "station", "大阪駅", OSAKA, country="JP", cat=STATION)
        oadd(ocon, "cn_exit", "人民广场站A口", SHANGHAI, cat=STATION)
        oadd(ocon, "ramen", "出口ラーメン", OSAKA, country="JP")
        assert orun(ocon) == {"station": "Osaka", "ramen": "Osaka"}

    def test_flagged_and_closed_are_dropped(self, ocon):
        oadd(ocon, "flag", "饭店", SHANGHAI, flags=["closed"])
        oadd(ocon, "ok", "饭店2", SHANGHAI)
        assert orun(ocon) == {"ok": "Shanghai"}


class TestOverseasChains:
    def test_global_chains_in_any_script_are_dropped(self, ocon):
        oadd(ocon, "zh", "星巴克(南京西路店)", SHANGHAI, cat=CAFE)
        oadd(ocon, "en", "Starbucks Coffee", SHANGHAI, cat=CAFE)
        oadd(ocon, "both", "KFC 肯德基", SHANGHAI)
        oadd(ocon, "jp", "マクドナルド 難波店", OSAKA, country="JP")
        oadd(ocon, "roastery", "Starbucks Reserve Roastery", SHANGHAI, cat=CAFE)
        assert orun(ocon) == {"roastery": "Shanghai"}

    def test_domestic_mass_chain_is_dropped(self, ocon):
        for i in range(MASS_CHAIN_MIN_BRANCHES):
            oadd(ocon, f"s{i}", f"すき家 店舗{i}店", OSAKA, country="JP")
        oadd(ocon, "local", "お好み焼き 千房", OSAKA, country="JP")
        assert orun(ocon) == {"local": "Osaka"}

    def test_tourist_chain_survives_but_is_capped_per_destination(self, ocon):
        for i in range(MASS_CHAIN_MIN_BRANCHES):
            oadd(ocon, f"i{i}", f"一蘭 店舗{i}店", OSAKA, country="JP")
        assert len(orun(ocon)) == BRAND_PER_DESTINATION

    def test_brand_cap_keeps_strongest_signals(self, ocon):
        for i in range(5):
            oadd(ocon, f"weak{i}", f"小杨生煎({i}店)", SHANGHAI)
        for i in range(BRAND_PER_DESTINATION):
            oadd(ocon, f"strong{i}", f"小杨生煎(强{i}店)", SHANGHAI, tel="021-0000", website="x.cn", created=OLD)
        assert set(orun(ocon)) == {f"strong{i}" for i in range(BRAND_PER_DESTINATION)}

    def test_brand_cap_is_per_destination(self, ocon):
        for i in range(BRAND_PER_DESTINATION + 1):
            oadd(ocon, f"sh{i}", f"小杨生煎({i}店)", SHANGHAI)
            oadd(ocon, f"sz{i}", f"小杨生煎(苏{i}店)", SUZHOU)
        regions = list(orun(ocon).values())
        assert regions.count("Shanghai") == regions.count("Suzhou") == BRAND_PER_DESTINATION

    def test_leading_bracket_names_are_not_one_brand(self, ocon):
        names = ["【公式】たこ焼き十八番", "（株）丸福珈琲", "(本店)お好み焼き美津の", "【予約可】焼肉一番", "(仮)カフェ"]
        for i, n in enumerate(names):
            oadd(ocon, f"b{i}", n, OSAKA, country="JP")
        assert len(orun(ocon)) == len(names)

    def test_dash_branch_suffix_counts_as_one_brand(self, ocon):
        for i in range(MASS_CHAIN_MIN_BRANCHES):
            oadd(ocon, f"h{i}", f"Highlands Coffee - Chi nhánh {i}", HANOI, country="VN", cat=CAFE)
        assert orun(ocon) == {}

    def test_decomposed_vietnamese_matches_tourist_chain(self, ocon):
        nfd = unicodedata.normalize("NFD", "Cộng Cà Phê")
        for i in range(MASS_CHAIN_MIN_BRANCHES):
            oadd(ocon, f"c{i}", f"{nfd} - {i}", HANOI, country="VN", cat=CAFE)
        assert len(orun(ocon)) == BRAND_PER_DESTINATION

    def test_chain_rules_do_not_touch_lodging(self, ocon):
        for i in range(MASS_CHAIN_MIN_BRANCHES):
            oadd(ocon, f"h{i}", "東横INN", OSAKA, country="JP", cat=HOTEL)
        assert len(orun(ocon)) == MASS_CHAIN_MIN_BRANCHES


class TestOverseasSignal:
    def test_signal_counts_contact_longevity_and_specific_category(self, ocon):
        oadd(ocon, "full", "老火锅", SHANGHAI, cat=HOTPOT, tel="1", website="w", instagram="i", created=OLD)
        oadd(ocon, "bare", "饭店", SHANGHAI)
        oadd(ocon, "bad_date", "饭店2", SHANGHAI, created="")
        rows = dict(ocon.execute(
            f"SELECT fsq_place_id, signal FROM ({build_overseas_query('o', 'dest')})").fetchall())
        assert rows == {"full": 6, "bare": 0, "bad_date": 0}

    def test_output_columns_extend_korean_format(self, ocon):
        oadd(ocon, "a", "饭店", SHANGHAI)
        cols = [d[0] for d in ocon.execute(f"SELECT * FROM ({build_overseas_query('o', 'dest')})").description]
        assert cols == ["fsq_place_id", "name", "latitude", "longitude", "country", "region", "category",
                        "address", "tags", "description", "signal"]


class TestDestinationsFile:
    def test_names_are_unique_ascii_and_parents_exist(self):
        from extract import DESTINATIONS_FILE, load_destinations
        dests = load_destinations(DESTINATIONS_FILE, None)
        names = [d["name"] for d in dests]
        assert len(names) == len({n.lower() for n in names})
        assert all(n.isascii() and n.strip() == n for n in names)
        assert all(d.get("parent") in names for d in dests if d.get("parent"))
        assert all(d["radiusKm"] > 0 and d["nameKo"] for d in dests)


class TestOverseasPlumbing:
    def test_extract_countries_is_opt_in(self, monkeypatch):
        monkeypatch.delenv("EXTRACT_COUNTRIES", raising=False)
        assert extract_countries() == set()
        monkeypatch.setenv("EXTRACT_COUNTRIES", "all")
        assert extract_countries() is None
        monkeypatch.setenv("EXTRACT_COUNTRIES", " jp, cn ")
        assert extract_countries() == {"JP", "CN"}

    def test_unknown_country_fails_instead_of_running_korea_only(self, tmp_path):
        f = tmp_path / "d.json"
        f.write_text('[{"country": "JP", "name": "Osaka", "lat": 34.7, "lng": 135.5, "radiusKm": 20}]')
        with pytest.raises(ValueError):
            load_destinations(str(f), {"JPN"})
        assert [d["name"] for d in load_destinations(str(f), {"JP"})] == ["Osaka"]

    def test_country_box_covers_every_radius(self):
        b = country_boxes(DESTS)["JP"]
        for d in (x for x in DESTS if x["country"] == "JP"):
            assert b[0] <= d["lat"] - d["radiusKm"] / 111.0 and d["lat"] + d["radiusKm"] / 111.0 <= b[1]

    def test_output_query_combines_korea_and_overseas(self, con):
        create_overseas_tables(con)
        add(con, "k", "식당", SEOUL, locality="서울")
        oadd(con, "s", "饭店", SHANGHAI)
        con.execute(f"CREATE TEMP TABLE scored AS {build_scored_query('o', 'dest')}")
        cur = con.execute(f"SELECT * FROM ({build_output_query('t', 'scored')}) ORDER BY fsq_place_id")
        assert [d[0] for d in cur.description] == [
            "fsq_place_id", "name", "latitude", "longitude", "country", "region", "category",
            "address", "tags", "description", "signal"]
        assert [(r[0], r[5], r[10]) for r in cur.fetchall()] == [("k", "Seoul", None), ("s", "Shanghai", 0)]

    def test_output_query_without_overseas_keeps_signal_column(self, con):
        add(con, "k", "식당", SEOUL, locality="서울")
        cur = con.execute(f"SELECT * FROM ({build_output_query('t')})")
        assert cur.description[-1][0] == "signal" and len(cur.fetchall()) == 1


class TestOverseasCaps:
    def test_large_destination_caps_sights_and_food_by_signal(self, ocon, monkeypatch):
        import extract
        monkeypatch.setattr(extract, "DESTINATION_CAPS", ((10, 2, 3),))
        for i in range(6):
            oadd(ocon, f"t{i}", f"寺{i}", OSAKA, country="JP", cat=TEMPLE, website="w" if i < 2 else None)
            oadd(ocon, f"f{i}", f"食堂{i}", OSAKA, country="JP", tel="1" if i >= 3 else None)
        for i in range(4):
            oadd(ocon, f"h{i}", f"ホテル{i}", OSAKA, country="JP", cat=HOTEL)
        kept = set(orun(ocon))
        assert {k for k in kept if k.startswith("t")} == {"t0", "t1"}
        assert {k for k in kept if k.startswith("f")} == {"f3", "f4", "f5"}
        assert {k for k in kept if k.startswith("h")} == {"h0", "h1", "h2", "h3"}

    def test_small_destination_is_not_capped(self, ocon, monkeypatch):
        import extract
        monkeypatch.setattr(extract, "DESTINATION_CAPS", ((100, 1, 1),))
        for i in range(5):
            oadd(ocon, f"f{i}", f"食堂{i}", OSAKA, country="JP")
        assert len(orun(ocon)) == 5
