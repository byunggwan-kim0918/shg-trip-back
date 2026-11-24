"""Wikidata 칸 나누기·중복 제거. 네트워크 없이 가짜 응답으로 돌린다."""
import pytest

from pipeline import wikidata
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
