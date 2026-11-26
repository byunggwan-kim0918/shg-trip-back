"""Wikidata 칸 나누기·중복 제거. 네트워크 없이 가짜 응답으로 돌린다."""
import pytest

from pipeline import rules, wikidata
from pipeline.areas import Area


def item(qid, lat, lon):
    return {"qid": qid, "lat": lat, "lon": lon, "sitelinks": 1, "ja": qid, "ko": None, "en": None,
            "jawiki": False, "kowiki": False}


def test_large_tile_is_split_and_items_on_borders_are_kept_once():
    area = Area("t", 140.0, 141.0, 35.0, 36.0)
    calls = []

    def fetch(tile):
        calls.append(tile)
        if tile[2] - tile[0] == 1.0:
            raise wikidata.TileTooLarge()
        return [item("Q1", 35.5, 140.5), item(f"Q{len(calls)}", tile[1], tile[0])]

    rows, _ = wikidata.collect(area, fetch, log=lambda *_: None)
    assert len(calls) == 5
    assert sorted(r["qid"] for r in rows) == ["Q1", "Q2", "Q3", "Q4", "Q5"]


def test_smallest_tile_timeout_fails_loudly():
    area = Area("t", 140.0, 140.0625, 35.0, 35.0625)

    def fetch(tile):
        raise wikidata.TileTooLarge()

    with pytest.raises(RuntimeError):
        wikidata.collect(area, fetch, log=lambda *_: None)


def test_query_uses_box_corners():
    q = wikidata.query_for((140.0, 35.0, 141.0, 36.0))
    assert "Point(140.0 35.0)" in q and "Point(141.0 36.0)" in q


def test_area_items_are_asked_in_batches():
    asked = []

    def fetch(query):
        asked.append(query)
        return [{"item": {"value": "http://www.wikidata.org/entity/Q2"}}] if "wd:Q2" in query else []

    assert wikidata.area_qids([f"Q{i}" for i in range(1, wikidata.AREA_BATCH + 2)], fetch) == {"Q2"}
    assert len(asked) == 2


def test_area_batch_that_times_out_is_split():
    def fetch(query):
        if query.split("VALUES ?class")[0].count("wd:Q") > 3:
            raise wikidata.TileTooLarge()
        return [{"item": {"value": "http://www.wikidata.org/entity/Q1"}}] if "wd:Q1 " in query else []

    assert wikidata.area_qids([f"Q{i}" for i in range(1, 5)], fetch) == {"Q1"}


def test_brand_items_need_a_chain_class_and_no_place_class():
    asked = []

    def fetch(query):
        asked.append(query)
        return [{"item": {"value": "http://www.wikidata.org/entity/Q708789"}}]

    assert wikidata.brand_qids(["Q708789", "Q1"], fetch) == {"Q708789"}
    assert "?item wdt:P31/wdt:P279? ?brand" in asked[0] and "FILTER(?other NOT IN (" in asked[0]
    assert all(f"wd:{c}" in asked[0] for c in wikidata.BRAND_CLASSES + wikidata.COMPANY_CLASSES
               + wikidata.LEGAL_FORM_CLASSES)


def test_class_lookup_rejects_values_that_are_not_qids():
    import pytest
    with pytest.raises(ValueError):
        wikidata.brand_qids(["Q1", "Q2 } ?x ?y ?z {"], lambda q: [])


def test_area_items_follow_one_subclass_step_and_population():
    asked = []
    wikidata.area_qids(["Q1"], lambda q: asked.append(q) or [])
    assert "wdt:P31/wdt:P279?" in asked[0] and "wdt:P1082" in asked[0]


def test_query_asks_local_labels_and_aliases_for_the_country():
    q = wikidata.query_for((100.0, 13.0, 101.0, 14.0), "TH")
    assert 'lang(?label) IN ("mul", "th")' in q
    assert 'lang(?alias) IN ("ja", "ko", "en", "th")' in q


def test_unknown_country_asks_common_labels_and_base_aliases_only():
    labels, aliases = wikidata.label_languages("")
    assert labels == ("mul",) and aliases == ("ja", "ko", "en")


def test_country_language_file_has_valid_codes_for_every_row():
    langs = wikidata.country_languages()
    assert langs["TW"][0] == "zh" and "he" in langs["IL"]
    assert all(langs.values())


def test_row_splits_joined_labels_and_drops_empty_ones():
    binding = {"item": {"value": "http://www.wikidata.org/entity/Q1"}, "lat": {"value": "1"}, "lon": {"value": "2"},
               "sitelinks": {"value": "3"}, "labels": {"value": "วัดอรุณ\x1fWat Arun"}, "aliases": {"value": ""}}
    row = wikidata._row(binding)
    assert row["labels"] == ["วัดอรุณ", "Wat Arun"] and row["aliases"] == [] and row["jawiki"] is False


def test_area_items_include_towns_and_leave_out_built_structures():
    asked = []
    wikidata.area_qids(["Q1"], lambda q: asked.append(q) or [])
    assert "wd:Q3957" in asked[0]
    assert "FILTER NOT EXISTS" in asked[0] and "wd:Q811979" in asked[0]


def test_repeated_gateway_timeouts_split_the_tile(monkeypatch):
    import io
    import urllib.error

    def fail(*_args, **_kwargs):
        raise urllib.error.HTTPError(wikidata.ENDPOINT, 504, "Gateway Timeout", {}, io.BytesIO(b""))

    monkeypatch.setattr(wikidata.urllib.request, "urlopen", fail)
    monkeypatch.setattr(wikidata.time, "sleep", lambda _s: None)
    with pytest.raises(wikidata.TileTooLarge):
        wikidata._Fetcher().run("SELECT 1")


def test_item_with_several_coordinates_keeps_all_and_the_same_point_in_any_order():
    area = Area("t", 140.0, 140.0625, 35.0, 35.0625)
    points = [item("Q1", 35.01, 140.01), item("Q1", 35.05, 140.05), item("Q1", 35.03, 140.03)]
    first, _ = wikidata.collect(area, lambda tile: points, log=lambda *_: None)
    second, _ = wikidata.collect(area, lambda tile: points[::-1], log=lambda *_: None)
    assert first[0]["coords"] == [[35.01, 140.01], [35.03, 140.03], [35.05, 140.05]]
    assert (first[0]["lat"], first[0]["lon"]) == (second[0]["lat"], second[0]["lon"]) == (35.03, 140.03)


def test_kind_items_pair_each_item_with_the_kind_whose_classes_it_reaches():
    asked = []

    def fetch(query):
        asked.append(query)
        return [{"item": {"value": "http://www.wikidata.org/entity/Q11178964"}}] if "wd:Q786014" in query else []

    assert wikidata.kind_qids(["Q11178964", "Q1177386"], fetch) == {("Q11178964", "rest_area")}
    assert "wd:Q785979" in asked[0] and "wdt:P31/wdt:P279?" in asked[0]


def test_item_kind_file_rows_have_a_category_prefix_and_qid_classes():
    kinds = rules.load_item_kinds()
    assert kinds and all(prefix and classes for _, prefix, classes in kinds)
