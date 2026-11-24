"""
위키보야주 덤프(pages-articles.xml.bz2)에서 여행 기사 속 장소 목록(see·do·eat·vCard·marker 틀)을 뽑고 풀 행에 잇는다.
여행 기사 작성자가 골라 적은 장소라 "여행자에게 알려진 곳"의 근거로 쓴다. 목록에 없다고 감점하지 않는다.
  wikivoyage_listings : 목록 한 줄마다 위키·기사·절·종류·이름·좌표·wikidata. 원문 설명은 길이만 남긴다(CC BY-SA 본문을 옮기지 않는다).
  wikivoyage_links    : 풀 행 ↔ 목록. wikidata가 같거나, 같은 이름이 가까우면 잇는다.
"""
import bz2
import csv
import json
import os
import re
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

from . import identity, snapshot
from .rules import literal as _literal

LICENSE = "CC BY-SA 4.0"
_SECTIONS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "wikivoyage_sections.tsv")
# 종류가 틀 이름에 들어 있는 틀과, type·group 값이나 절 제목으로 종류를 정하는 틀.
KIND_TEMPLATES = ("see", "do", "buy", "eat", "drink", "sleep", "go")
GENERIC_TEMPLATES = ("listing", "vcard", "marker")
# 좌표가 있는 목록은 같은 이름이 이만큼 안이면 잇는다. 기사 작성자가 입구·대표 지점을 찍어 출처마다 수백 m 다르다.
SAME_NAME_METERS = 500
_CELL = 0.005
_TEMPLATE_START = re.compile(r"\{\{\s*(" + "|".join(KIND_TEMPLATES + GENERIC_TEMPLATES) + r")\s*\|", re.IGNORECASE)
_HEADING = re.compile(r"^(={2,4})\s*(.*?)\s*\1\s*$", re.MULTILINE)
_LINK = re.compile(r"\[\[(?:[^\]|]*\|)?([^\]]*)\]\]")
_MARKUP = re.compile(r"<[^>]+>|'{2,}")
_QID = re.compile(r"^Q[1-9][0-9]*$")


def load_kinds(path=_SECTIONS_FILE):
    """{(wiki, match, value): kind}. wiki='*'은 모든 언어판에 쓴다. value는 소문자로 맞춘다."""
    kinds = {}
    with open(path, encoding="utf-8") as f:
        for row in csv.DictReader((line for line in f if not line.startswith("#")), delimiter="\t"):
            key = (row["wiki"], row["match"], row["value"].strip().lower())
            if key in kinds:
                raise ValueError(f"중복 줄: {key}")
            kinds[key] = row["kind"]
    return kinds


def parse(dump_path, out_path, wiki, log=print):
    """덤프 하나를 목록 Parquet 하나로. wiki는 언어판 코드(en·ja)다. 반환은 manifest."""
    import duckdb
    snapshot._check_path(out_path)
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    kinds = load_kinds()
    started = time.monotonic()
    rows = []
    pages = 0
    with bz2.open(dump_path, "rb") as f:
        for title, page_id, text in _pages(f):
            pages += 1
            rows.extend(listings(text, wiki, title, page_id, kinds))
            if pages % 20000 == 0:
                log(f"{wiki} 기사 {pages:,} 목록 {len(rows):,} ({time.monotonic() - started:.0f}s)")
    con = duckdb.connect()
    con.execute("""CREATE TABLE l (wiki VARCHAR, page_id BIGINT, page_title VARCHAR, section VARCHAR, template VARCHAR,
                   kind VARCHAR, name VARCHAR, alt VARCHAR, lat DOUBLE, lon DOUBLE, wikidata VARCHAR, wikipedia VARCHAR,
                   url VARCHAR, description_chars INTEGER, lastedit VARCHAR, page_lat DOUBLE, page_lon DOUBLE)""")
    con.executemany("INSERT INTO l VALUES (" + ", ".join(["?"] * 17) + ")", rows)
    con.execute(f"COPY l TO '{_literal(out_path)}' (FORMAT parquet, COMPRESSION zstd)")
    manifest = {
        "source": f"{wiki}wikivoyage", "license": LICENSE, "terms_url": "https://foundation.wikimedia.org/wiki/Terms_of_Use",
        "input": os.path.basename(dump_path), "input_sha256": snapshot.sha256(dump_path),
        "parsed_at": datetime.now(timezone.utc).isoformat(timespec="seconds"), "pages": pages, "rows": len(rows),
        "file_bytes": os.path.getsize(out_path), "sha256": snapshot.sha256(out_path),
        "seconds": round(time.monotonic() - started, 1), "max_rss_mb": snapshot.max_rss_mb(),
    }
    with open(out_path + ".manifest.json", "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    return manifest


def _pages(stream):
    """본문 이름공간(ns=0) 기사만 (제목, id, 위키텍스트)로 내준다. 넘겨주기 문서는 건너뛴다."""
    title = page_id = ns = None
    redirect = False
    for event, el in ET.iterparse(stream, events=("end",)):
        tag = el.tag.rsplit("}", 1)[-1]
        if tag == "title":
            title = el.text
        elif tag == "ns":
            ns = el.text
        elif tag == "id" and page_id is None:
            page_id = int(el.text)
        elif tag == "redirect":
            redirect = True
        elif tag == "text":
            if ns == "0" and not redirect and el.text:
                yield title, page_id, el.text
        elif tag == "page":
            title = page_id = ns = None
            redirect = False
            el.clear()


def listings(text, wiki, title, page_id, kinds):
    """기사 하나의 목록 줄들. 종류는 틀 이름 → type·group 값 → 절 제목 순으로 정한다."""
    page_lat, page_lon = _page_geo(text)
    headings = [(m.start(), m.group(2)) for m in _HEADING.finditer(text) if len(m.group(1)) == 2]
    out = []
    for m in _TEMPLATE_START.finditer(text):
        body = _template_body(text, m.end())
        if body is None:
            continue
        template = m.group(1).lower()
        params = _params(body)
        name = _clean(params.get("name"))
        if not name:
            continue
        section = next((h for pos, h in reversed(headings) if pos < m.start()), None)
        kind = (template if template in KIND_TEMPLATES else
                _kind(kinds, wiki, "type", params.get("type")) or _kind(kinds, wiki, "type", params.get("group"))
                or _kind(kinds, wiki, "section", section) or "other")
        qid = (params.get("wikidata") or "").strip()
        out.append((wiki, page_id, title, section, template, kind, name, _clean(params.get("alt")) or None,
                    _coord(params.get("lat")), _coord(params.get("long") or params.get("lon")),
                    qid if _QID.match(qid) else None, _clean(params.get("wikipedia")) or None,
                    (params.get("url") or "").strip() or None, len((params.get("content") or params.get("description")
                                                                    or "").strip()),
                    (params.get("lastedit") or "").strip() or None, page_lat, page_lon))
    return out


def _kind(kinds, wiki, match, value):
    if not value:
        return None
    v = value.strip().strip('"').lower()
    return kinds.get((wiki, match, v)) or kinds.get(("*", match, v))


def _template_body(text, start):
    """start부터 짝이 맞는 '}}' 앞까지. 안쪽 틀·링크의 '|'를 나누지 않으려고 깊이를 센다."""
    depth, i, n = 1, start, len(text)
    while i < n - 1:
        two = text[i:i + 2]
        if two == "{{":
            depth += 1
            i += 2
        elif two == "}}":
            depth -= 1
            if depth == 0:
                return text[start:i]
            i += 2
        else:
            i += 1
    return None


def _params(body):
    parts, depth, cur, i = [], 0, [], 0
    while i < len(body):
        two = body[i:i + 2]
        if two in ("{{", "[["):
            depth += 1
            cur.append(two)
            i += 2
        elif two in ("}}", "]]"):
            depth -= 1
            cur.append(two)
            i += 2
        elif body[i] == "|" and depth == 0:
            parts.append("".join(cur))
            cur = []
            i += 1
        else:
            cur.append(body[i])
            i += 1
    parts.append("".join(cur))
    params = {}
    for p in parts:
        if "=" in p:
            k, v = p.split("=", 1)
            params.setdefault(k.strip().lower(), v.strip())
    return params


def _clean(value):
    if not value:
        return ""
    return _MARKUP.sub("", _LINK.sub(r"\1", value)).strip()


def _coord(value):
    try:
        v = float((value or "").strip())
    except ValueError:
        return None
    return v if v != 0 else None


def _page_geo(text):
    m = re.search(r"\{\{\s*geo\s*\|\s*([-0-9.]+)\s*\|\s*([-0-9.]+)", text, re.IGNORECASE)
    return (_coord(m.group(1)), _coord(m.group(2))) if m else (None, None)


def link(con, listings_raw, rows="evidence", candidates="candidates", table="wikivoyage_links", extra_names=None,
         wikidata_raw=None):
    """풀 행 ↔ 목록. 숙소·교통·기타 목록은 잇지 않는다(여행 기사가 고른 볼거리·먹을거리만 근거로 쓴다).
    영어판 목록은 영어 이름이 많아 extra_names(source, source_id, value: OSM 다국어 이름)와 위키데이터 영어·일본어 이름도 키로 쓴다."""
    h = identity._HAVERSINE_M
    extra = (f"UNION ALL SELECT source, source_id, value FROM {extra_names}" if extra_names else "")
    if wikidata_raw:
        extra += f"""
            UNION ALL SELECT r.source, r.source_id, unnest([w.en, w.ja]) FROM {rows} r
            JOIN (SELECT qid, any_value(en) AS en, any_value(ja) AS ja FROM {wikidata_raw} GROUP BY 1) w ON w.qid = r.wd_qid"""
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE wv AS
        SELECT row_number() OVER () AS wv_rid, *, name_key(name) AS k, name_key(alt) AS k_alt
        FROM {listings_raw} WHERE kind IN ('see', 'do', 'buy', 'eat', 'drink')
    """)
    con.execute(f"""
        CREATE OR REPLACE TABLE {table} AS
        WITH pool AS (
            SELECT r.source, r.source_id, r.wd_qid, c.lat, c.lon,
                   list_distinct(list_filter(list_transform(c.names, n -> name_key(n.value)), k -> length(k) >= 2)
                                 || [c.name_key]) AS keys
            FROM {rows} r JOIN {candidates} c USING (source, source_id)
        ),
        more AS (
            SELECT source, source_id, name_key(value) AS k FROM (SELECT NULL::VARCHAR AS source, NULL::VARCHAR AS source_id,
                   NULL::VARCHAR AS value WHERE false {extra})
            WHERE value IS NOT NULL
        ),
        by_qid AS (
            SELECT p.source, p.source_id, w.wv_rid, 'wikidata' AS rule FROM pool p JOIN wv w ON w.wikidata = p.wd_qid
        ),
        pk AS (SELECT source, source_id, lat, lon, k, floor(lat / {_CELL})::BIGINT AS cy,
                      floor(lon / {_CELL})::BIGINT AS cx
               FROM (SELECT source, source_id, lat, lon, unnest(keys) AS k FROM pool
                     UNION SELECT p.source, p.source_id, p.lat, p.lon, m.k FROM pool p JOIN more m USING (source, source_id)
                     WHERE length(m.k) >= 2)),
        wk AS (SELECT wv_rid, lat, lon, k, floor(lat / {_CELL})::BIGINT + dy AS cy, floor(lon / {_CELL})::BIGINT + dx AS cx
               FROM (SELECT wv_rid, lat, lon, unnest(list_filter([k, k_alt], x -> length(x) >= 2)) AS k FROM wv
                     WHERE lat IS NOT NULL AND lon IS NOT NULL),
                    (SELECT unnest([-1, 0, 1]) AS dy), (SELECT unnest([-1, 0, 1]) AS dx)),
        by_name AS (
            SELECT DISTINCT a.source, a.source_id, b.wv_rid, 'name_near' AS rule
            FROM pk a JOIN wk b USING (k, cy, cx) WHERE {h} <= {SAME_NAME_METERS}
        ),
        pairs AS (SELECT * FROM by_qid UNION ALL SELECT * FROM by_name)
        SELECT p.source, p.source_id, arg_min(p.rule, p.rule <> 'wikidata') AS rule,
               count(DISTINCT w.wiki || ':' || w.page_id) AS wv_articles, list_distinct(list(w.kind)) AS wv_kinds,
               list_distinct(list(w.wiki)) AS wv_wikis, max(w.description_chars) AS wv_description_chars,
               '{LICENSE}' AS license
        FROM pairs p JOIN wv w USING (wv_rid)
        GROUP BY 1, 2
    """)
    return table
