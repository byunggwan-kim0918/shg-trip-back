"""위키보야주 목록 추출과 풀 연결."""
from pipeline import candidates, evidence, selection, wikivoyage

from test_candidates import OTARU, con, ov  # noqa: F401

EN = """{{geo|43.19|141.0}}
== See ==
* {{see | name=[[Otaru Canal]] | alt=小樽運河 | lat=43.1990 | long=141.0020 | wikidata=Q1 | content=Old canal.}}
== Eat ==
* {{listing | type=eat | name=Kanehachi | lat= | long= | content={{JPY|500}} sushi, ''cheap'' }}
* {{sleep | name=Hotel Otaru | lat=43.19 | long=141.0}}
"""
JA = """== 食べる ==
* {{vCard | name = 小樽かね八 | type = | lat = 43.1977 | long = 141.0026 | description = 寿司 }}
* {{marker | type=city | name=札幌 | lat=43.06 | long=141.35}}
"""


def test_listing_kind_comes_from_template_then_type_then_section():
    kinds = wikivoyage.load_kinds()
    en = {r[6]: r for r in wikivoyage.listings(EN, "en", "Otaru", 1, kinds)}
    assert en["Otaru Canal"][5] == "see" and en["Otaru Canal"][7] == "小樽運河" and en["Otaru Canal"][10] == "Q1"
    assert en["Kanehachi"][5] == "eat" and en["Kanehachi"][8] is None
    assert en["Kanehachi"][13] > 0
    assert en["Hotel Otaru"][5] == "sleep"
    assert en["Otaru Canal"][15:] == (43.19, 141.0)
    ja = {r[6]: r for r in wikivoyage.listings(JA, "ja", "小樽", 2, kinds)}
    assert ja["小樽かね八"][5] == "eat" and ja["札幌"][5] == "other"


def test_listed_places_count_as_known_to_travelers(con):
    ov(con, "canal", "小樽運河", hierarchy=("geographic_entities", "canal"), coord=(43.1992, 141.0021))
    ov(con, "sushi", "小樽かね八", hierarchy=("food_and_drink", "restaurant", "sushi_restaurant"), brand="Kanehachi",
       coord=(43.1976, 141.0025))
    ov(con, "other", "かね七", hierarchy=("food_and_drink", "restaurant", "sushi_restaurant"), brand="Kanehachi",
       coord=(43.1976, 141.0035))
    con.execute("""CREATE TABLE wv (wiki VARCHAR, page_id BIGINT, page_title VARCHAR, section VARCHAR, template VARCHAR,
                   kind VARCHAR, name VARCHAR, alt VARCHAR, lat DOUBLE, lon DOUBLE, wikidata VARCHAR, wikipedia VARCHAR,
                   url VARCHAR, description_chars INTEGER, lastedit VARCHAR, page_lat DOUBLE, page_lon DOUBLE)""")
    kinds = wikivoyage.load_kinds()
    con.executemany("INSERT INTO wv VALUES (" + ", ".join(["?"] * 17) + ")",
                    wikivoyage.listings(EN, "en", "Otaru", 1, kinds) + wikivoyage.listings(JA, "ja", "小樽", 2, kinds))
    candidates.build(con, "ov", "2026-11-18.0", None, None)
    evidence.build(con, "candidates", None)
    wikivoyage.link(con, "wv", "evidence", "candidates")
    links = {r[0]: r[1:] for r in con.execute("SELECT source_id, rule, wv_kinds FROM wikivoyage_links").fetchall()}
    assert set(links) == {"canal", "sushi"}
    assert links["canal"] == ("name_near", ["see"])
    selection.build(con, "evidence", "candidates", None, wikivoyage="wikivoyage_links")
    rows = {r[0]: r[1:] for r in con.execute("SELECT source_id, outcome, reason FROM selection").fetchall()}
    assert rows["sushi"] == ("recommend", "notable_food")
    assert rows["other"] == ("situational", "chain")


def test_english_listing_links_through_other_language_names(con):
    ov(con, "canal", "運河", hierarchy=("geographic_entities", "canal"), coord=(43.1992, 141.0021))
    con.execute("""CREATE TABLE wv AS SELECT 'en' AS wiki, 1 AS page_id, 'Otaru' AS page_title, 'See' AS section,
                   'see' AS template, 'see' AS kind, 'Otaru Canal' AS name, NULL::VARCHAR AS alt, 43.199 AS lat, 141.002 AS lon,
                   NULL::VARCHAR AS wikidata, NULL::VARCHAR AS wikipedia, NULL::VARCHAR AS url, 0 AS description_chars, NULL AS lastedit,
                   NULL AS page_lat, NULL AS page_lon""")
    con.execute("CREATE TABLE names AS SELECT 'overture' AS source, 'canal' AS source_id, 'Otaru Canal' AS value")
    candidates.build(con, "ov", "2026-11-18.0", None, None)
    evidence.build(con, "candidates", None)
    assert wikivoyage.link(con, "wv", "evidence", "candidates") and \
        con.execute("SELECT count(*) FROM wikivoyage_links").fetchone()[0] == 0
    wikivoyage.link(con, "wv", "evidence", "candidates", extra_names="names")
    assert con.execute("SELECT source_id FROM wikivoyage_links").fetchall() == [("canal",)]
