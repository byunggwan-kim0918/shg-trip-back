"""sync_core의 이름 판정(끊긴 항목 이름을 받은 부속 행만 고르고 제 이름은 지킨다)과 운영 적용 스크립트."""
import duckdb
import pytest

from bench import sync_core
from bench.sync_core import _names_item
from pipeline.korean_names import reads_as_hanja


def test_hanja_reading_matches_each_character_and_rejects_unknown_hanja():
    assert reads_as_hanja("금각사", "金閣寺") and reads_as_hanja("녹원사 방장", "鹿苑寺 方丈")
    assert not reads_as_hanja("금각사", "鹿苑寺 方丈")
    assert not reads_as_hanja("금성", "金沢城")


def test_sub_facility_holding_the_main_name_is_picked_but_its_own_name_is_kept():
    item = ({"로쿠온지"}, ["鹿苑寺", "鹿苑寺 (金閣寺) (Kinkaku-Ji Temple)", "金閣寺"], {"鹿苑寺", "kinkakujitemple"})
    assert _names_item("금각사", "鹿苑寺 方丈", item)
    assert _names_item("로쿠온지", "鹿苑寺 方丈", item)
    assert not _names_item("녹원사 방장", "鹿苑寺 方丈", item)
    assert not _names_item("로쿠온지", "鹿苑寺", item)
    assert not _names_item("금각사", "鹿苑寺 方丈", None)


def _script(tmp_path, monkeypatch, merge, names=(), refs=()):
    asked = []
    monkeypatch.setattr(sync_core.load_country, "_psql", lambda db, sql: asked.append(sql) or "f00d")
    values = {"name": "鹿苑寺", "address": "金閣寺町1", "latitude": 35.0394, "longitude": 135.7292, "source": "fsq"}
    promote = [tuple(values.get(c, f"v{i}") for i, c in enumerate(sync_core._INSERT_COLUMNS)) + ("fsq", "x1")]
    path = tmp_path / "apply.sql"
    sync_core.write_sql("trip_jp_prodcopy", str(path), "JP", [(7, "Q1", 10)], list(names), [], merge, [], promote,
                        list(refs))
    return path.read_text(encoding="utf-8"), asked


def test_production_script_checks_before_backup_and_inside_the_transaction_and_records_inserted_ids(tmp_path, monkeypatch):
    text, asked = _script(tmp_path, monkeypatch, [(8, 7)])
    guard = "IS DISTINCT FROM 'f00d'"
    assert text.startswith("\\set ON_ERROR_STOP on\n\\pset tuples_only on\nDO $$")
    assert text.index(guard) < text.index("\\copy") < text.index("BEGIN;") < text.rindex(guard) < text.index("UPDATE places")
    assert text.index("\\o sync-promoted-") < text.index("INSERT INTO places") < text.rindex("\\o\n") < text.index("COMMIT;")
    assert "WHERE id IN (7, 8)" in asked[0] and "low_id IN (7, 8)" in asked[0]


def test_production_script_keeps_other_countries_verdicts_and_skips_merges_the_link_job_refused(tmp_path, monkeypatch):
    text, _ = _script(tmp_path, monkeypatch, [])
    assert "DELETE FROM place_pair_verdicts WHERE basis = 'PIPELINE' AND low_id IN (SELECT id FROM places WHERE country = 'JP') AND (low_id, high_id) NOT IN ((0, 0));" in text
    text, _ = _script(tmp_path, monkeypatch, [(8, 7)])
    assert "v.low_id = 7 AND v.high_id = 8 AND v.relation <> 'SAME' AND v.basis <> 'PIPELINE'" in text
    assert "r.id = 7 AND r.active AND r.merged_into_id IS NULL" in text


def test_name_without_a_reading_is_asked_again_like_the_invalidation_job(tmp_path, monkeypatch):
    text, _ = _script(tmp_path, monkeypatch, [], names=[(9, "x", "y", None, "inherited_label")])
    statement = text[text.index("UPDATE places SET name_ko = NULL"):]
    assert "enrich_attempts = 0" in statement and "embedding = NULL" in statement and "WHERE id = 9;" in statement


def test_production_script_links_source_ids_only_to_the_planned_row_and_never_moves_a_link(tmp_path, monkeypatch):
    text, _ = _script(tmp_path, monkeypatch, [], refs=[(7, "overture", "gers-7", "金閣寺")])
    core = text.index("('overture', 'gers-7', 7, '金閣寺')")
    body = text.index("('fsq', 'x1', '鹿苑寺', '金閣寺町1', 35.0394, 135.7292)")
    assert text.index("INSERT INTO places") < text.index("\\o sync-refs-") < core < body < text.index("COMMIT;")
    assert "JOIN places p ON p.id = v.id AND p.name = v.name ON CONFLICT DO NOTHING" in text
    assert text.count("INSERT INTO place_source_refs") == 2 and text.count("ON CONFLICT DO NOTHING RETURNING 'ref'") == 2


def test_core_map_from_source_ids_takes_the_row_of_the_place_source_and_must_agree_with_the_load_order(tmp_path):
    con = duckdb.connect()
    con.execute("CREATE SCHEMA jp")
    con.execute("CREATE TABLE jp.candidates_all AS SELECT * FROM (VALUES ('overture', 'o1', '嵐山'), ('fsq', 'f1', '嵐山 (Arashiyama)'),"
                " ('overture', 'o2', '清水寺')) AS t(source, source_id, name)")
    con.execute("CREATE TABLE jp.evidence AS SELECT source, source_id FROM jp.candidates_all")
    con.execute("CREATE TABLE policy AS SELECT source, source_id, true AS kept FROM jp.candidates_all")
    refs = tmp_path / "refs.csv"
    refs.write_text("id,place_source,source,source_id,rule\n1,overture,fsq,f1,fsq\n1,overture,overture,o1,tuple\n"
                    "2,overture,overture,o2,tuple\n", encoding="utf-8")
    sync_core.core_map(con, None, "policy", str(refs))
    assert con.execute("SELECT id, source, source_id, name FROM core_map ORDER BY id").fetchall() == [
        (1, "overture", "o1", "嵐山"), (2, "overture", "o2", "清水寺")]

    old = tmp_path / "old.duckdb"
    with duckdb.connect(str(old)) as o:
        o.execute("CREATE TABLE policy AS SELECT * FROM (VALUES ('overture', 'o1', '嵐山', true), "
                  "('overture', 'o2', '清水寺', true)) AS t(source, source_id, name, kept)")
        o.execute("CREATE TABLE budget_destinations AS SELECT source, source_id FROM policy")
        o.execute("CREATE TABLE mislocated_jp AS SELECT source, source_id FROM policy WHERE false")
    sync_core.core_map(con, str(old), "policy", str(refs))
    swapped = tmp_path / "swapped.csv"
    swapped.write_text("id,place_source,source,source_id,rule\n1,overture,overture,o2,tuple\n2,overture,overture,o1,tuple\n",
                       encoding="utf-8")
    with pytest.raises(RuntimeError, match="적재 순서"):
        sync_core.core_map(con, str(old), "policy", str(swapped))
