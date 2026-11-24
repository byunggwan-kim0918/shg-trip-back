"""Overture 후보 증거(FSQ 연결·체인·웹사이트). 합성 테이블에 돌린다."""
from pipeline import candidates, evidence, identity

from test_candidates import OLD_6Y, OTARU, RECENT, con, fs, ov  # noqa: F401

NEAR = (OTARU[0] + 0.0001, OTARU[1])
FAR = (OTARU[0] + 0.01, OTARU[1])


def run(con, fsq=True):
    candidates.build(con, "ov", "2026-11-18.0", "fs", "test")
    evidence.build(con, "candidates", "fs" if fsq else None)
    cur = con.execute("SELECT * FROM evidence")
    cols = [d[0] for d in cur.description]
    return {r[1]: dict(zip(cols, r)) for r in cur.fetchall()}


class TestFsqLink:
    def test_closed_fsq_row_is_linked_as_evidence(self, con):
        ov(con, "a", "千春鮨", fsq_id="f1")
        fs(con, "f1", "千春鮨", closed="2024-01-01")
        r = run(con)["a"]
        assert r["fsq_basis"] == "source_id" and r["fsq_closed"]

    def test_exact_name_within_50m_links_only_same_top_category(self, con):
        ov(con, "food", "かね八", coord=NEAR)
        fs(con, "f_food", "かね八", refreshed=OLD_6Y)
        ov(con, "shop", "石黒商店", hierarchy=("shopping", "souvenir_store"))
        fs(con, "f_shop", "石黒商店", label="Community and Government > Government Building")
        ov(con, "far", "政寿司", coord=FAR)
        fs(con, "f_far", "政寿司")
        rows = run(con)
        assert rows["food"]["fsq_basis"] == "exact_name"
        assert str(rows["food"]["fsq_created"])[:10] == OLD_6Y
        assert rows["shop"]["fsq_basis"] is None and rows["shop"]["fsq_review_links"] == 1
        assert rows["far"]["fsq_basis"] is None

    def test_one_open_record_keeps_place_open(self, con):
        ov(con, "a", "ぽんた", fsq_id="old")
        fs(con, "old", "ぽんた", closed="2020-01-01")
        fs(con, "new", "ぽんた", coord=NEAR)
        assert not run(con)["a"]["fsq_closed"]


class TestPool:
    def test_unlinked_fsq_row_joins_pool_but_near_similar_name_is_held(self, con):
        ov(con, "o1", "博多一双中洲店", hierarchy=("shopping", "souvenir_store"))
        fs(con, "dup", "博多一双", coord=NEAR, label="Retail > Gift Store")
        fs(con, "new", "とんかつ丸七", coord=FAR, refreshed=RECENT)
        rows = run(con)
        assert "dup" not in rows
        assert rows["new"]["source"] == "fsq" and rows["new"]["fsq_basis"] == "self"
        assert str(rows["new"]["fsq_refreshed"])[:10] == RECENT
        status = dict(con.execute("SELECT source_id, status FROM fsq_only").fetchall())
        assert status == {"dup": "possible_duplicate", "new": "only"}

    def test_independent_confirm_needs_a_non_fsq_overture_source(self, con):
        ov(con, "meta", "かね八")
        fs(con, "f_meta", "かね八", coord=NEAR)
        ov(con, "copied", "政寿司", fsq_id="f_copied", coord=FAR)
        con.execute("UPDATE ov SET sources = list_filter(sources, s -> s.dataset = 'Foursquare') WHERE id = 'copied'")
        fs(con, "f_copied", "政寿司", coord=FAR)
        rows = run(con)
        assert rows["meta"]["independent_confirm"]
        assert rows["copied"]["fsq_basis"] == "source_id" and not rows["copied"]["independent_confirm"]


class TestChain:
    def test_same_name_counts_branches_but_not_temples(self, con):
        for i in range(15):
            ov(con, f"s{i}", f"ほっともっと 支所{i}店", coord=(OTARU[0] + i * 0.01, OTARU[1]))
            ov(con, f"t{i}", "西岸寺", hierarchy=("religious_organization", "buddhist_temple"),
               coord=(OTARU[0] + i * 0.01, OTARU[1]))
        ov(con, "local", "山田精肉店")
        rows = run(con, fsq=False)
        assert rows["s0"]["same_name_count"] == 15
        assert rows["t0"]["same_name_count"] == 0
        assert rows["local"]["same_name_count"] == 1


class TestWebsite:
    def test_host_kinds(self, con):
        ov(con, "own", "かね八")
        ov(con, "tabelog", "政寿司")
        con.execute("UPDATE ov SET websites = ['https://www.kanehachi.jp/menu'] WHERE id = 'own'")
        con.execute("UPDATE ov SET websites = ['https://tabelog.com/hokkaido/123', "
                    "'https://ja.wikipedia.org/wiki/x'] WHERE id = 'tabelog'")
        for i in range(evidence.CHAIN_MIN_SHARED_HOST):
            ov(con, f"b{i}", f"ブランド{i}")
            con.execute(f"UPDATE ov SET websites = ['https://brand.co.jp/shop/{i}'] WHERE id = 'b{i}'")
            ov(con, f"st{i}", f"駅{i}", hierarchy=("travel_and_transportation", "train_station"))
            con.execute(f"UPDATE ov SET websites = ['https://jr.co.jp/{i}'] WHERE id = 'st{i}'")
        for i in range(evidence.SHARED_HOST_MIN_PLACES):
            ov(con, f"m{i}", f"蜂楽饅頭{i}")
            con.execute(f"UPDATE ov SET websites = ['https://houraku.co.jp/{i}'] WHERE id = 'm{i}'")
        ov(con, "page", "Meta食堂")
        ov(con, "insta", "Insta食堂")
        con.execute("ALTER TABLE ov ADD COLUMN socials VARCHAR[]")
        con.execute("UPDATE ov SET socials = ['https://www.facebook.com/9865699'] WHERE id = 'page'")
        con.execute("UPDATE ov SET socials = ['https://www.facebook.com/1', 'https://instagram.com/insta'] "
                    "WHERE id = 'insta'")
        rows = run(con, fsq=False)
        assert rows["page"]["web_kind"] is None and not rows["page"]["web_sns"]
        assert rows["insta"]["web_kind"] == "sns" and rows["insta"]["web_sns"]
        assert (rows["own"]["web_kind"], rows["own"]["web_host"]) == ("own", "kanehachi.jp")
        assert rows["tabelog"]["web_kind"] == "wiki" and rows["tabelog"]["web_wiki"]
        assert rows["b0"]["web_kind"] == "shared" and rows["b0"]["chain_shared_host"]
        assert rows["m0"]["web_kind"] == "shared" and not rows["m0"]["chain_shared_host"]
        assert rows["st0"]["web_kind"] == "shared" and not rows["st0"]["chain_shared_host"]


class TestWikidataLinks:
    def test_nearest_same_name_item_within_radius(self, con):
        ov(con, "shrine", "伏見稲荷大社", hierarchy=("religious_organization", "shinto_shrine"),
           common={"en": "Fushimi Inari Taisha"})
        ov(con, "inari", "稲荷神社", coord=FAR)
        con.execute("""CREATE TABLE wd AS SELECT * FROM (VALUES
            ('Q1', 43.1980, 141.0030, 120, '伏見稲荷大社', NULL, NULL, true, true),
            ('Q2', 43.1976 + 0.01, 141.0025 + 0.0001, 3, '稲荷神社', NULL, NULL, true, false),
            ('Q3', 43.1976 + 0.01 + 0.03, 141.0025, 2, '稲荷神社', NULL, NULL, true, false))
            AS t(qid, lat, lon, sitelinks, ja, en, ko, jawiki, kowiki)""")
        candidates.build(con, "ov", "2026-11-18.0", None, None)
        con.execute("CREATE TABLE rows AS SELECT source, source_id FROM candidates")
        identity.wikidata_links(con, "rows", "candidates", "wd")
        assert dict(con.execute("SELECT source_id, qid FROM wikidata_links").fetchall()) == {
            "shrine": "Q1", "inari": "Q2"}

    def test_long_formal_name_and_accented_label_still_link(self, con):
        ov(con, "senso", "浅草観音金龍山浅草寺", hierarchy=("religious_organization", "buddhist_temple"))
        ov(con, "hall", "浅草寺六角堂", hierarchy=("religious_organization", "buddhist_temple"), coord=(43.1983, 141.0025))
        ov(con, "shop", "浅草寺前 煎餅店", hierarchy=("food_and_drink", "restaurant"), coord=(43.1977, 141.0025))
        ov(con, "dazaifu", "Dazaifu Tenmangu", hierarchy=("religious_organization", "shinto_shrine"), coord=FAR)
        ov(con, "hall", "天童市市民文化会館", hierarchy=("arts_and_entertainment", "theatre"), coord=(43.1976, 141.2))
        con.execute("""CREATE TABLE wd AS SELECT * FROM (VALUES
            ('Q615183', 43.1976, 141.0025, 32, '浅草寺', NULL, NULL, true, true),
            ('Q710786', 43.1976 + 0.01, 141.0025 + 0.0001, 15, NULL, 'Dazaifu Tenman-gū', NULL, true, true),
            ('Q9', 43.1977, 141.2, 40, '天童市', NULL, NULL, true, true))
            AS t(qid, lat, lon, sitelinks, ja, en, ko, jawiki, kowiki)""")
        con.execute("CREATE TABLE areas AS SELECT 'Q9' AS qid")
        candidates.build(con, "ov", "2026-11-18.0", None, None)
        con.execute("CREATE TABLE rows AS SELECT source, source_id FROM candidates")
        identity.wikidata_links(con, "rows", "candidates", "wd", area_items="areas")
        assert dict(con.execute("SELECT source_id, qid FROM wikidata_links").fetchall()) == {
            "senso": "Q615183", "dazaifu": "Q710786"}

    def test_held_fsq_row_of_an_unlinked_wikidata_item_is_promoted(self, con):
        ov(con, "gate", "櫛田神社 中神門", hierarchy=("religious_organization", "shinto_shrine"))
        fs(con, "main", "櫛田神社", coord=NEAR, label="Community and Government > Spiritual Center > Shrine")
        con.execute("""CREATE TABLE wd AS SELECT * FROM (VALUES
            ('Q865839', 43.1977, 141.0025, 7, '櫛田神社', 'Kushida Shrine', NULL, true, false))
            AS t(qid, lat, lon, sitelinks, ja, en, ko, jawiki, kowiki)""")
        candidates.build(con, "ov", "2026-11-18.0", "fs", "test")
        evidence.build(con, "candidates", "fs", wikidata_raw="wd")
        rows = dict(con.execute("SELECT source_id, pool_basis FROM evidence").fetchall())
        assert rows == {"gate": "overture", "main": "fsq_notable"}
