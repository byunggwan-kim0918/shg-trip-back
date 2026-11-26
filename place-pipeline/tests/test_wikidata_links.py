"""Wikidata 연결 오류 회귀: 부속 시설 오연결, 기업·브랜드·지명 항목, 흔한 이름의 먼 행, 나라·문자별 이름 머리말."""
import pytest

from pipeline import candidates, identity

from test_candidates import OTARU, con, ov  # noqa: F401

TEMPLE = ("religious_organization", "buddhist_temple")
LANDMARK = ("attractions_and_activities", "historic_site")
SHOP = ("shopping", "souvenir_store")
# 위도 0.001도는 약 111m다.
M = 0.001 / 111


def at(meters_north):
    return (OTARU[0] + meters_north * M, OTARU[1])


def items(con, *rows):
    con.execute("CREATE TABLE wd (qid VARCHAR, lat DOUBLE, lon DOUBLE, sitelinks INTEGER, ja VARCHAR, en VARCHAR,"
                " ko VARCHAR, jawiki BOOLEAN, kowiki BOOLEAN)")
    for qid, meters, sitelinks, ja, en in rows:
        lat, lon = at(meters)
        con.execute("INSERT INTO wd VALUES (?, ?, ?, ?, ?, ?, NULL, true, false)", [qid, lat, lon, sitelinks, ja, en])


def link(con, areas=(), brands=(), countries=None, businesses=None, kinds=None):
    candidates.build(con, "ov", "2026-11-18.0", None, None)
    for source_id, country in (countries or {}).items():
        con.execute("UPDATE candidates SET country = ? WHERE source_id = ?", [country, source_id])
    con.execute("CREATE TABLE rows AS SELECT source, source_id FROM candidates")
    con.execute("CREATE TABLE areas AS SELECT unnest(?::VARCHAR[]) AS qid", [list(areas)])
    con.execute("CREATE TABLE brands AS SELECT unnest(?::VARCHAR[]) AS qid", [list(brands)])
    if businesses is not None:
        con.execute("CREATE TABLE businesses AS SELECT unnest(?::VARCHAR[]) AS qid", [list(businesses)])
    if kinds is not None:
        con.execute("CREATE TABLE kinds (qid VARCHAR, kind VARCHAR)")
        con.executemany("INSERT INTO kinds VALUES (?, ?)", list(kinds.items()))
    identity.wikidata_links(con, "rows", "candidates", "wd", area_items="areas", brand_items="brands",
                            business_items="businesses" if businesses is not None else None,
                            kind_items="kinds" if kinds is not None else None)
    return dict(con.execute("SELECT source_id, qid FROM wikidata_links").fetchall())


class TestSubFacility:
    def test_sub_building_does_not_take_the_item_when_the_main_row_is_missing(self, con):
        ov(con, "hall", "鹿苑寺 方丈", hierarchy=TEMPLE)
        ov(con, "jizo", "大涌谷延命地蔵尊", hierarchy=LANDMARK, coord=at(5000))
        items(con, ("Q270983", 10, 58, "鹿苑寺", "Kinkaku-ji Temple"), ("Q1134429", 5010, 14, "大涌谷", "Ōwakudani"))
        assert link(con) == {}

    def test_station_exit_and_institution_facility_do_not_take_the_item(self, con):
        ov(con, "exit", "京王線 新宿駅 臨時口", hierarchy=LANDMARK)
        ov(con, "lib", "京都大学 歴史展示室", hierarchy=LANDMARK, coord=at(5000))
        items(con, ("Q1", 10, 38, "新宿駅", None), ("Q2", 5010, 85, "京都大学", None))
        assert link(con) == {}

    def test_qualifier_before_the_item_name_and_ruins_suffix_still_link(self, con):
        ov(con, "senso", "比叡山 延暦寺", hierarchy=TEMPLE)
        ov(con, "castle", "安土城跡", hierarchy=LANDMARK, coord=at(5000))
        items(con, ("Q1", 10, 32, "延暦寺", None), ("Q2", 5010, 29, "安土城", None))
        assert link(con) == {"senso": "Q1", "castle": "Q2"}

    def test_latin_names_need_a_same_kind_word_since_the_head_comes_first(self, con):
        ov(con, "temple", "Kinkakuji Temple", hierarchy=TEMPLE)
        ov(con, "deck", "Tokyo Metropolitan Building South Observatory", hierarchy=LANDMARK, coord=at(5000))
        ov(con, "museum", "Museum of Kyoto University", hierarchy=LANDMARK, coord=at(10000))
        ov(con, "campus", "Kyoto University Museum", hierarchy=LANDMARK, coord=at(10050))
        ov(con, "park", "Osaka Castle Park", hierarchy=LANDMARK, coord=at(15000))
        items(con, ("Q1", 10, 58, None, "Kinkakuji"), ("Q2", 5010, 30, None, "Tokyo Metropolitan Building"),
              ("Q3", 10010, 85, None, "Kyoto University"), ("Q4", 15010, 40, None, "Osaka Castle"))
        assert link(con) == {"temple": "Q1"}

    def test_korean_and_chinese_names_follow_their_own_head_and_ruins_words(self, con):
        ov(con, "gate", "경복궁 매표소", hierarchy=LANDMARK)
        ov(con, "palace", "서울 경복궁", hierarchy=LANDMARK, coord=at(5000))
        ov(con, "site", "황룡사지", hierarchy=LANDMARK, coord=at(10000))
        ov(con, "ruin", "圆明园遗址", hierarchy=LANDMARK, coord=at(15000))
        items(con, ("Q1", 10, 40, None, None), ("Q2", 5010, 40, None, None), ("Q3", 10010, 10, None, None),
              ("Q4", 15010, 30, "圆明园", None))
        con.execute("UPDATE wd SET ja = '경복궁' WHERE qid IN ('Q1', 'Q2')")
        con.execute("UPDATE wd SET ja = '황룡사' WHERE qid = 'Q3'")
        countries = {"gate": "KR", "palace": "KR", "site": "KR", "ruin": "CN"}
        assert link(con, countries=countries) == {"palace": "Q2", "site": "Q3", "ruin": "Q4"}

    def test_slash_separated_bilingual_name_links_exactly(self, con):
        ov(con, "island", "端島 (軍艦島) / Hashima (Gunkanjima) Island", hierarchy=LANDMARK)
        items(con, ("Q1", 10, 53, "端島", "Hashima Island"))
        links = link(con)
        assert links == {"island": "Q1"}
        assert con.execute("SELECT rule FROM wikidata_links").fetchone()[0] == "exact"


class TestItemKind:
    def test_brand_item_is_not_linked_to_a_store_but_a_single_site_business_is(self, con):
        ov(con, "store", "無印良品", hierarchy=SHOP)
        ov(con, "brewery", "東酒造", hierarchy=SHOP, coord=at(5000))
        items(con, ("Q708789", 300, 40, "無印良品", "Muji"), ("Q2", 5010, 1, "東酒造", None))
        assert link(con, brands=("Q708789",)) == {"brewery": "Q2"}

    def test_area_item_is_not_linked_to_a_shop_named_after_the_city(self, con):
        ov(con, "liquor", "仙台市", hierarchy=SHOP)
        ov(con, "district", "嵐山", hierarchy=LANDMARK, coord=at(5000))
        items(con, ("Q46747", 300, 120, "仙台市", None), ("Q2859566", 5010, 37, "嵐山", "Arashiyama"))
        assert link(con, areas=("Q46747", "Q2859566")) == {"district": "Q2859566"}


class TestNamesakes:
    def test_far_row_with_a_common_name_is_another_place(self, con):
        ov(con, "near", "熊野神社", hierarchy=TEMPLE)
        ov(con, "far", "熊野神社", hierarchy=TEMPLE, coord=at(1800))
        items(con, ("Q1", 20, 2, "熊野神社", None), ("Q2", 30000, 1, "熊野神社", None))
        assert link(con) == {"near": "Q1"}

    def test_far_row_with_a_rare_name_is_the_same_place_with_shifted_coordinates(self, con):
        ov(con, "near", "京都国立近代美術館", hierarchy=LANDMARK)
        ov(con, "far", "京都国立近代美術館", hierarchy=LANDMARK, coord=at(1400))
        items(con, ("Q1055628", 20, 17, "京都国立近代美術館", None))
        assert link(con) == {"near": "Q1055628", "far": "Q1055628"}

    def test_far_copy_of_a_famous_place_with_a_common_name_keeps_its_item(self, con):
        ov(con, "main", "清水寺", hierarchy=TEMPLE)
        ov(con, "copy", "清水寺", hierarchy=TEMPLE, coord=at(650))
        items(con, ("Q221716", 20, 77, "清水寺", None), ("Q2", 30000, 1, "清水寺", None))
        assert link(con) == {"main": "Q221716", "copy": "Q221716"}

    @pytest.mark.parametrize("meters", [40, identity.NAMESAKE_NEAR_METERS - 10])
    def test_source_copies_near_the_anchor_keep_a_common_name_item(self, con, meters):
        ov(con, "a", "熊野神社", hierarchy=TEMPLE)
        ov(con, "b", "熊野神社", hierarchy=TEMPLE, coord=at(meters))
        items(con, ("Q1", 0, 2, "熊野神社", None), ("Q2", 30000, 1, "熊野神社", None))
        assert link(con) == {"a": "Q1", "b": "Q1"}


def test_quoted_name_inside_a_place_is_not_the_place(con):
    ov(con, "dog", "八幡神社「狛犬」", hierarchy=LANDMARK)
    items(con, ("Q1", 10, 1, "八幡神社", None))
    assert link(con) == {}


def test_leading_article_is_the_same_place_in_every_language(con):
    ov(con, "house", "The International House of Japan", hierarchy=LANDMARK)
    ov(con, "louvre", "Le Louvre", hierarchy=LANDMARK, coord=at(5000))
    ov(con, "ja", "ザ 国際文化会館", hierarchy=LANDMARK, coord=at(10000))
    items(con, ("Q1", 10, 5, None, "International House of Japan"), ("Q2", 5010, 90, None, "Louvre"),
          ("Q3", 10010, 5, "国際文化会館", None))
    assert link(con, countries={"louvre": "FR"}) == {"house": "Q1", "louvre": "Q2", "ja": "Q3"}


class TestOtherLanguages:
    def test_bilingual_segments_link_but_location_and_stop_suffixes_do_not(self, con):
        ov(con, "rockbund", "外滩美术馆 | Rockbund Art Museum (RAM)", hierarchy=LANDMARK)
        ov(con, "museum", "대림미술관ㅣ디뮤지엄", hierarchy=LANDMARK, coord=at(5000))
        ov(con, "gallery", "Friends Forever Galerie - Berlin", hierarchy=LANDMARK, coord=at(10000))
        ov(con, "stop", "H Georgenstraße / Am Kupfergraben", hierarchy=("travel", "bus_station"), coord=at(15000))
        items(con, ("Q1", 10, 4, None, "Rockbund Art Museum"), ("Q2", 5010, 3, "대림미술관", None),
              ("Q3", 10300, 2, None, "Berlin"), ("Q5", 15010, 8, None, "Am Kupfergraben"))
        countries = {"rockbund": "CN", "museum": "KR", "gallery": "DE", "stop": "DE"}
        assert link(con, countries=countries) == {"rockbund": "Q1", "museum": "Q2"}

    def test_one_leading_word_in_head_initial_names_is_the_same_place(self, con):
        ov(con, "church", "Evangelische Christuskirche", hierarchy=TEMPLE)
        ov(con, "dog", "AKC Museum of the Dog", hierarchy=LANDMARK, coord=at(5000))
        ov(con, "geyser", "Old Faithful Geyser", hierarchy=LANDMARK, coord=at(10000))
        ov(con, "uni", "Museum of Kyoto University", hierarchy=LANDMARK, coord=at(15000))
        items(con, ("Q1", 10, 5, None, "Christuskirche"), ("Q2", 5010, 5, None, "Museum of the Dog"),
              ("Q3", 10010, 34, None, "Old Faithful"), ("Q4", 15010, 85, None, "Kyoto University"))
        countries = {"church": "AT", "dog": "US", "geyser": "US", "uni": "US"}
        assert link(con, countries=countries) == {"church": "Q1", "dog": "Q2", "geyser": "Q3"}

    def test_hall_inside_a_temple_and_comma_location_suffix_do_not_take_the_temple(self, con):
        ov(con, "buddha", "Liegender Buddha Im Wat Pho Tempel", hierarchy=LANDMARK)
        ov(con, "arun", "Wat Arun Temple,Bankok,Thailand", hierarchy=TEMPLE, coord=at(5000))
        ov(con, "temple", "Wat Arun Temple", hierarchy=TEMPLE, coord=at(5020))
        items(con, ("Q1", 10, 28, None, "Wat Pho"), ("Q2", 5010, 45, None, "Wat Arun"))
        countries = {"buddha": "TH", "arun": "TH", "temple": "TH"}
        assert link(con, countries=countries) == {"temple": "Q2"}


def named_items(con, *rows):
    """현지어 라벨·별칭 열이 있는 스냅샷. 행: (qid, 북쪽 미터, 언어판 수, ja, en, labels, aliases)."""
    con.execute("CREATE TABLE wd (qid VARCHAR, lat DOUBLE, lon DOUBLE, sitelinks INTEGER, ja VARCHAR, en VARCHAR,"
                " ko VARCHAR, jawiki BOOLEAN, kowiki BOOLEAN, labels VARCHAR[], aliases VARCHAR[])")
    for qid, meters, sitelinks, ja, en, labels, aliases in rows:
        lat, lon = at(meters)
        con.execute("INSERT INTO wd VALUES (?, ?, ?, ?, ?, ?, NULL, false, false, ?, ?)",
                    [qid, lat, lon, sitelinks, ja, en, list(labels), list(aliases)])


class TestLocationTails:
    def test_head_links_when_the_tail_after_a_dash_or_comma_is_a_place_name_or_filler(self, con):
        ov(con, "garden", "Japanese garden, Monte Carlo", hierarchy=LANDMARK)
        ov(con, "church", "Saint Joseph's Church, Shanghai", hierarchy=TEMPLE, coord=at(5000))
        ov(con, "museum", "Musée des Lettres et Manuscrits - officiel", hierarchy=LANDMARK, coord=at(10000))
        ov(con, "house", "Russisches Haus in Berlin", hierarchy=LANDMARK, coord=at(15000))
        items(con, ("Q1", 10, 11, None, "Japanese Garden"), ("Q9", 500, 40, None, "Monte Carlo"),
              ("Q2", 5010, 8, None, "Saint Joseph's Church"), ("Q8", 6000, 200, None, "Shanghai"),
              ("Q3", 10010, 5, None, "Musée des Lettres et Manuscrits"),
              ("Q4", 15010, 6, None, "Russisches Haus"), ("Q7", 15500, 300, None, "Berlin"))
        countries = {"garden": "MC", "church": "CN", "museum": "FR", "house": "DE"}
        assert link(con, areas=("Q9", "Q8", "Q7"), countries=countries) == {
            "garden": "Q1", "church": "Q2", "museum": "Q3", "house": "Q4"}

    def test_tail_matching_the_row_address_city_is_a_place_name_without_an_area_item(self, con):
        ov(con, "chapel", "Seaside Chapel, Otaru", hierarchy=LANDMARK)
        ov(con, "hall", "Music Hall in Otaru", hierarchy=LANDMARK, coord=at(5000))
        items(con, ("Q1", 10, 4, None, "Seaside Chapel"), ("Q2", 5010, 4, None, "Music Hall"))
        assert link(con) == {"chapel": "Q1", "hall": "Q2"}

    def test_head_does_not_link_when_the_tail_is_a_part_or_another_place(self, con):
        ov(con, "room", "Musée d'Orsay - Salle 32", hierarchy=LANDMARK)
        ov(con, "exit", "新宿駅 - 東口", hierarchy=LANDMARK, coord=at(5000))
        ov(con, "tour", "Louvre, Pyramid Entrance", hierarchy=LANDMARK, coord=at(10000))
        items(con, ("Q1", 10, 60, None, "Musée d'Orsay"), ("Q2", 5010, 38, "新宿駅", None),
              ("Q3", 10010, 90, None, "Louvre"))
        assert link(con, countries={"room": "FR", "tour": "FR"}) == {}

    def test_city_name_alone_does_not_link_a_shop_or_a_segment_to_another_item_of_that_name(self, con):
        ov(con, "club", "Le District | Paris", hierarchy=LANDMARK)
        ov(con, "shop", "Berlin", hierarchy=SHOP, coord=at(5000))
        ov(con, "sculpture", "Berlin", hierarchy=LANDMARK, coord=at(5030))
        items(con, ("Q1", 20, 2, None, "Paris"), ("Q90", 1200, 367, "パリ", "Paris"),
              ("Q2", 5020, 2, None, "Berlin"), ("Q64", 5200, 335, "ベルリン", "Berlin"))
        assert link(con, areas=("Q90", "Q64"), countries={"club": "FR", "shop": "DE"}) == {"sculpture": "Q2"}


class TestLocalNames:
    def test_local_label_and_alias_link_exactly(self, con):
        ov(con, "wat", "วัดอรุณราชวราราม", hierarchy=TEMPLE)
        ov(con, "kinkaku", "金閣寺", hierarchy=TEMPLE, coord=at(5000))
        ov(con, "palace", "Palais Princier de Monaco - Prince's Palace of Monaco", hierarchy=LANDMARK, coord=at(10000))
        named_items(con, ("Q1", 10, 45, None, "Wat Arun", ["วัดอรุณราชวราราม"], []),
                    ("Q2", 5010, 58, "鹿苑寺", "Kinkaku-ji", [], ["金閣寺", "金閣"]),
                    ("Q3", 10010, 32, "モナコ大公宮殿", "Prince's Palace of Monaco", ["Palais du Prince"],
                     ["palais princier de Monaco"]))
        countries = {"wat": "TH", "palace": "MC"}
        assert link(con, countries=countries) == {"wat": "Q1", "kinkaku": "Q2", "palace": "Q3"}

    def test_common_name_alias_of_an_obscure_item_is_not_used_but_a_famous_items_alias_is(self, con):
        ov(con, "shrine", "宇賀神社", hierarchy=TEMPLE)
        ov(con, "kinkaku", "金閣寺", hierarchy=TEMPLE, coord=at(5000))
        named_items(con, ("Q1", 1400, 1, "荒熊白髭稲荷神社", None, [], ["宇賀神社"]),
                    ("Q9", 30000, 2, "宇賀神社", None, [], []),
                    ("Q2", 5010, 58, "鹿苑寺", None, [], ["金閣寺"]), ("Q8", 40000, 1, "金閣寺", None, [], []))
        assert link(con) == {"kinkaku": "Q2"}

    def test_alias_is_not_used_for_containment(self, con):
        ov(con, "garden", "新チュイルリー庭園", hierarchy=LANDMARK)
        named_items(con, ("Q2", 50, 59, "チュイルリー宮殿", "Tuileries Palace", [], ["チュイルリー庭園"]))
        assert link(con) == {}

    def test_full_name_item_beats_a_nearer_item_of_the_name_without_brackets(self, con):
        ov(con, "bridge", "Potsdamer Brücke (Hornwerksgraben)", hierarchy=LANDMARK)
        named_items(con, ("Q1", 300, 2, None, "Potsdamer Brücke (Hornwerksgraben)", [], []),
                    ("Q2", 50, 5, None, "Potsdamer Brücke", [], []))
        assert link(con, countries={"bridge": "DE"}) == {"bridge": "Q1"}

    def test_label_beats_a_nearer_alias_such_as_a_former_name(self, con):
        ov(con, "theatre", "Comédie-Française", hierarchy=LANDMARK)
        ov(con, "station", "品川駅", hierarchy=("travel", "train_station"), coord=at(5000))
        named_items(con, ("Q1", 17, 44, None, "Comédie-Française", [], []),
                    ("Q2", 11, 8, None, "Salle Richelieu", [], ["Comédie-Française"]),
                    ("Q3", 6029, 40, "品川駅", None, [], []),
                    ("Q4", 5473, 12, "北品川駅", None, [], ["品川駅"]))
        assert link(con, countries={"theatre": "FR"}) == {"theatre": "Q1", "station": "Q3"}

    def test_row_near_any_coordinate_of_a_river_takes_it(self, con):
        ov(con, "river", "高野川", hierarchy=("attractions_and_activities", "river"))
        con.execute("CREATE TABLE wd (qid VARCHAR, lat DOUBLE, lon DOUBLE, coords DOUBLE[][], sitelinks INTEGER, ja VARCHAR,"
                    " en VARCHAR, ko VARCHAR, jawiki BOOLEAN, kowiki BOOLEAN)")
        far, near = at(13000), at(10)
        con.execute("INSERT INTO wd VALUES ('Q1', ?, ?, ?, 8, '高野川', 'Takano River', NULL, true, false)",
                    [far[0], far[1], [list(far), list(near)]])
        assert link(con) == {"river": "Q1"}


def test_shop_far_from_a_landmark_of_its_name_is_another_place_but_far_from_its_business_item_is_the_same(con):
    ov(con, "neva", "Нева", hierarchy=("food_and_drink", "restaurant"))
    ov(con, "riad", "Riad El Ouarda", hierarchy=("accommodation", "hotel"), coord=at(5000))
    ov(con, "mall", "Fontvieille Shopping Centre", hierarchy=("retail", "shopping_mall"), coord=at(10000))
    ov(con, "tower", "Rudolfsturm", hierarchy=("food_and_drink", "restaurant"), coord=at(15000))
    items(con, ("Q1", 659, 96, None, "Нева"), ("Q2", 6700, 1, None, "Riad El Ouarda"),
          ("Q3", 10600, 4, None, "Fontvieille Shopping Centre"), ("Q4", 15200, 5, None, "Rudolfsturm"))
    countries = {"neva": "RU", "riad": "MA", "mall": "MC", "tower": "AT"}
    assert link(con, countries=countries, businesses=("Q2",)) == {"riad": "Q2", "mall": "Q3", "tower": "Q4"}


def test_chain_branch_links_only_to_the_item_next_to_it(con):
    ov(con, "far", "McDonald's", brand="McDonald's")
    ov(con, "near", "McDonald's", brand="McDonald's", coord=at(5000))
    ov(con, "tour", "外滩", hierarchy=("travel", "tour_operator"), coord=at(10000))
    ov(con, "hotel", "Waldorf Astoria Shanghai on the Bund", hierarchy=("accommodation", "hotel"), brand="Waldorf Astoria",
       coord=at(15000))
    items(con, ("Q1", 1025, 2, None, "McDonald's"), ("Q2", 5040, 2, None, "McDonald's"), ("Q3", 10417, 36, "外滩", None),
          ("Q4", 15881, 5, None, "Waldorf Astoria Shanghai on the Bund"))
    assert link(con, businesses=("Q1", "Q2", "Q4"), countries={"tour": "CN", "hotel": "CN"}) == {"near": "Q2", "hotel": "Q4"}


def test_operator_prefixed_station_takes_the_station_item_but_exits_and_stops_do_not(con):
    station = ("travel", "train_station")
    ov(con, "jr", "JR 新宿駅 (JR Shinjuku Sta.)", hierarchy=station)
    ov(con, "exit", "京王線 新宿駅 臨時口", hierarchy=station, coord=at(30))
    ov(con, "stop", "新宿駅西口", hierarchy=("travel", "bus_station"), coord=at(60))
    ov(con, "other", "東新宿駅", hierarchy=station, coord=at(900))
    items(con, ("Q801447", 140, 38, "新宿駅", "Shinjuku Station"), ("Q112503", 950, 12, "東新宿駅", None))
    assert link(con) == {"jr": "Q801447", "other": "Q112503"}


def test_local_script_name_followed_by_its_romanization_links_by_the_local_part(con):
    ov(con, "temple", "醍醐寺 Daigo-ji Temple", hierarchy=TEMPLE)
    ov(con, "gate", "흥례문 Heungnyemun", hierarchy=LANDMARK, coord=at(5000))
    ov(con, "lounge", "ホテル日航大阪 ロビーラウンジ Hotel Nikko Osaka Lobby Lounge", hierarchy=LANDMARK, coord=at(10000))
    ov(con, "arcade", "BIG JOY 心斎橋", hierarchy=LANDMARK, coord=at(15000))
    items(con, ("Q1157535", 20, 23, "醍醐寺", "Daigo-ji Temple"), ("Q2", 5010, 3, "興禮門", "Heungnyemun"),
          ("Q3", 10020, 1, "ホテル日航大阪", "Hotel Nikko Osaka"), ("Q4", 15200, 20, "心斎橋", "Shinsaibashi"))
    con.execute("UPDATE wd SET ko = '흥례문' WHERE qid = 'Q2'")
    assert link(con, countries={"gate": "KR"}) == {"temple": "Q1157535", "gate": "Q2"}


class TestAliasOfAnotherPlace:
    def test_katakana_spelling_variant_takes_the_garden_label_over_the_palace_alias(self, con):
        ov(con, "garden", "チュイルリー庭園", hierarchy=("attractions_and_activities", "park"))
        named_items(con, ("Q234364", 130, 59, "テュイルリー宮殿", "Tuileries Palace", ["Palais des Tuileries"],
                          ["テュイルリー庭園", "チュイルリー庭園"]),
                    ("Q818517", 500, 48, "テュイルリー庭園", "Tuileries Garden", ["jardin des Tuileries"], ["チュイルリー公園"]))
        assert link(con, countries={"garden": "FR"}) == {"garden": "Q818517"}

    def test_rest_area_row_takes_an_alias_only_from_a_rest_area_item(self, con):
        rest = ("travel", "rest_stop")
        ov(con, "pa", "湊町PA", hierarchy=rest)
        ov(con, "hall", "なんばHatch", hierarchy=("arts_and_entertainment", "music_venue"), coord=at(30))
        ov(con, "kurosaki", "黒埼PA", hierarchy=rest, coord=at(5000))
        ov(con, "oasis", "淡路ハイウェイオアシス", hierarchy=rest, coord=at(10000))
        named_items(con, ("Q1177386", 40, 4, "湊町リバープレイス", "Minatomachi River Place", [],
                          ["なんばHatch", "湊町PA", "湊町パーキングエリア"]),
                    ("Q11178964", 5010, 2, "黒埼パーキングエリア", None, [], ["黒埼PA"]),
                    ("Q11393056", 10010, 3, "淡路ハイウェイオアシス", None, [], []))
        assert link(con, kinds={"Q11178964": "rest_area"}) == {"hall": "Q1177386", "kurosaki": "Q11178964",
                                                               "oasis": "Q11393056"}
