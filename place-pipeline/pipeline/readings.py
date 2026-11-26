"""
장소 이름의 규칙 읽기. 초안이 쓴 한국어 이름이 그 장소를 가리키는지 생성기가 대조하는 근거다(장소 신원의 근거는 아니다).
  readings : 한글 읽기. 이어진 Wikidata 항목의 한국어 라벨(names.wikidata_korean_label을 통과할 때)·가나 이름(P1814)·영어 라벨과 출처 영어 이름·
             괄호 병기·로마자 이름·가나 이름을 korean_names 규칙으로 옮긴 것. 로마자 표기는 낱말마다도 옮기고, 이름 속
             가나 부분(小樽オルゴール堂의 オルゴール)도 따로 옮긴다. 옮길 수 없으면 넣지 않는다.
  reading  : 이름 전체를 옮긴 첫 읽기(가나 이름 → 영어 라벨 → 출처 영어 이름 → 괄호 병기 → 로마자·가나 이름 순). 한국어 이름 후보다.
  latin    : 로마자로 읽히지 않는 영어 낱말(HEP FIVE의 FIVE, Museum). 영어로 지은 이름은 읽기 대신 이 낱말과 맞춘다.
"""
import re

from . import korean_names
from .candidates import register_functions

_PAREN_LATIN = r"[(（]([A-Za-z][^)）]*)[)）]"
_LATIN_NAME = r"^[A-Za-z][A-Za-z\s\-.'’ōūāēī]*$"
_KANA_RUN = re.compile(r"[ぁ-ゖァ-ヺー・]{2,}")
_WORD = re.compile(r"[A-Za-zōūāēīÕŌŪĀĒĪ'’\-]+")


def kana_runs(name):
    """이름 속 가나 부분의 한글 읽기. 한자와 섞인 이름은 이름 전체로는 옮길 수 없다."""
    out = []
    for run in _KANA_RUN.findall(name or ""):
        ko = korean_names.from_kana(run)
        if ko and ko not in out:
            out.append(ko)
    return out


def word_readings(texts):
    """로마자 표기의 낱말별 한글 읽기와 로마자로 읽히지 않는 영어 낱말."""
    generic = set(korean_names.ENGLISH_GENERIC.values()) | set(korean_names.ROMAJI_GENERIC.values())
    readings, english = [], []
    for text in texts:
        for word in _WORD.findall(text or ""):
            ko = korean_names.from_romaji(word)
            if ko in generic:
                continue
            if ko and re.search("[가-힣]", ko):
                if ko not in readings:
                    readings.append(ko)
            elif re.fullmatch(r"[A-Za-z]{3,}", word) and word not in english:
                english.append(word)
    return readings, english


def build(con, rows, links, candidates, wikidata_raw, kana=None, table="name_readings"):
    """rows(source, source_id)마다 한 줄. links(source, source_id, qid)는 행이 이어진 항목이고, kana(qid, kana)는 없어도 된다."""
    register_functions(con)
    if not con.execute("SELECT count(*) FROM duckdb_functions() WHERE function_name = 'ko_kana_runs'").fetchone()[0]:
        con.create_function("ko_kana", korean_names.from_kana, ["VARCHAR"], "VARCHAR", null_handling="special")
        con.create_function("ko_romaji", korean_names.from_romaji, ["VARCHAR"], "VARCHAR", null_handling="special")
        con.create_function("ko_kana_runs", kana_runs, ["VARCHAR"], "VARCHAR[]", null_handling="special")
        con.create_function("ko_words", lambda texts: word_readings(texts)[0], ["VARCHAR[]"], "VARCHAR[]",
                            null_handling="special")
        con.create_function("en_words", lambda texts: word_readings(texts)[1], ["VARCHAR[]"], "VARCHAR[]",
                            null_handling="special")
    kana_join = f"LEFT JOIN {kana} k ON k.qid = l.qid" if kana else ""
    kana_col = "k.kana" if kana else "NULL::VARCHAR"
    latin_name = _LATIN_NAME.replace("'", "''")
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE {table} AS
        WITH src AS (
            SELECT r.source, r.source_id, c.name, l.qid,
                   wikidata_korean_label(w.ko::VARCHAR, c.name) AS wd_ko,
                   w.en AS wd_en, {kana_col} AS wd_kana,
                   list_filter(c.names, n -> n.lang = 'en')[1].value AS en_name,
                   nullif(regexp_extract(c.name, '{_PAREN_LATIN}', 1), '') AS paren
            FROM {rows} r JOIN {candidates} c USING (source, source_id)
            LEFT JOIN {links} l USING (source, source_id)
            LEFT JOIN {wikidata_raw} w ON w.qid = l.qid
            {kana_join}
        ),
        texts AS (
            SELECT *, list_filter([wd_en, en_name, paren,
                                   CASE WHEN regexp_matches(name, '[A-Za-z]{{3,}}') THEN name END],
                                  x -> x IS NOT NULL) AS latin_texts
            FROM src
        )
        SELECT source, source_id, wd_ko,
               list_filter([ko_kana(wd_kana), ko_romaji(wd_en), ko_romaji(en_name), ko_romaji(paren),
                            CASE WHEN regexp_matches(name, '{latin_name}') THEN ko_romaji(name) END, ko_kana(name)],
                           x -> x IS NOT NULL)[1] AS reading,
               list_distinct(list_filter(list_concat(
                   [wd_ko, ko_kana(wd_kana), ko_romaji(wd_en), ko_romaji(en_name), ko_romaji(paren),
                    CASE WHEN regexp_matches(name, '{latin_name}') THEN ko_romaji(name) END, ko_kana(name)],
                   ko_kana_runs(name), ko_words(latin_texts)), x -> x IS NOT NULL)) AS readings,
               en_words(latin_texts) AS latin
        FROM texts
    """)
    return table
