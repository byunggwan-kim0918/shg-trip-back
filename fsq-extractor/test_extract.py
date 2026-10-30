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
