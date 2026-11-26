"""
Wikidata 좌표 항목 스냅샷. 범위를 격자 칸으로 나눠 SPARQL로 받고, 칸이 크면(시간 초과·행 많음) 4등분한다.
행: qid, lat, lon, coords, sitelinks, ja, ko, en, jawiki, kowiki, labels(그 나라 언어·mul 라벨), aliases(ja·ko·en·그 나라 언어 별칭).
좌표가 여러 개인 항목(강의 발원지·하구)은 coords에 모두 두고, lat·lon은 받은 순서와 상관없는 가운데 좌표다.
국가 속성(P17)이 빠진 항목도 좌표로 잡도록 P17로 거르지 않는다. 나라별 언어는 country_languages.tsv에 둔다.
"""
import csv
import http.client
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

from . import snapshot
from .rules import load_item_kinds

ENDPOINT = "https://query.wikidata.org/sparql"
# Wikidata는 User-Agent 없는 요청을 거부한다. 운영 배치(WikidataSitelinksJob)와 같은 값을 쓴다.
USER_AGENT = "shg-trip/1.0 (travel itinerary planner; +https://github.com/byunggwan-kim0918/shg-trip-back)"
# WDQS 공개 엔드포인트 예절 범위. 동시 요청 없이 초당 한 번.
MIN_INTERVAL_SECONDS = 1.0
REQUEST_TIMEOUT_SECONDS = 75
MAX_RETRIES = 4
# 한 칸 결과가 이만큼이면 잘렸을 수 있어 4등분한다.
SPLIT_ROWS = 15000
# 도심(파리·도쿄)은 라벨·별칭이 많아 1km 칸까지 나눠야 시간 안에 온다.
MIN_TILE_DEGREES = 0.01
START_TILE_DEGREES = 1.0

_QUERY = """
SELECT ?item ?lat ?lon (SAMPLE(?sl) AS ?sitelinks) (SAMPLE(?ja0) AS ?ja) (SAMPLE(?ko0) AS ?ko) (SAMPLE(?en0) AS ?en)
       (SAMPLE(?jw) AS ?jawiki) (SAMPLE(?kw) AS ?kowiki)
       (GROUP_CONCAT(DISTINCT ?label; separator="\\u001F") AS ?labels)
       (GROUP_CONCAT(DISTINCT ?alias; separator="\\u001F") AS ?aliases) WHERE {{
  SERVICE wikibase:box {{
    ?item wdt:P625 ?coord .
    bd:serviceParam wikibase:cornerSouthWest "Point({lon0} {lat0})"^^geo:wktLiteral .
    bd:serviceParam wikibase:cornerNorthEast "Point({lon1} {lat1})"^^geo:wktLiteral .
  }}
  ?item wikibase:sitelinks ?sl .
  BIND(geof:latitude(?coord) AS ?lat) BIND(geof:longitude(?coord) AS ?lon)
  OPTIONAL {{ ?item rdfs:label ?ja0 FILTER(lang(?ja0) = "ja") }}
  OPTIONAL {{ ?item rdfs:label ?ko0 FILTER(lang(?ko0) = "ko") }}
  OPTIONAL {{ ?item rdfs:label ?en0 FILTER(lang(?en0) = "en") }}
  OPTIONAL {{ ?jw schema:about ?item ; schema:isPartOf <https://ja.wikipedia.org/> }}
  OPTIONAL {{ ?kw schema:about ?item ; schema:isPartOf <https://ko.wikipedia.org/> }}
  OPTIONAL {{ ?item rdfs:label ?label FILTER(lang(?label) IN ({labels})) }}
  OPTIONAL {{ ?item skos:altLabel ?alias FILTER(lang(?alias) IN ({aliases})) }}
}}
GROUP BY ?item ?lat ?lon
"""
# 따로 열이 있는 언어. 별칭은 이 언어도 받는다.
BASE_LANGUAGES = ("ja", "ko", "en")
# 언어 구분 없는 라벨(mul)은 고유명사 원문이라 어느 나라에서나 받는다.
COMMON_LABEL_LANGUAGES = ("mul",)
_COUNTRY_LANGUAGES_FILE = os.path.join(os.path.dirname(__file__), "country_languages.tsv")
_LANGUAGE = re.compile(r"^[a-z]{2,3}(-[a-z0-9]+)*$")
_SEP = "\x1f"


def country_languages(path=_COUNTRY_LANGUAGES_FILE):
    """{국가 코드: (Wikidata 언어 코드, ...)}. 라벨 언어는 그 나라에서 장소 이름을 쓰는 언어다."""
    with open(path, encoding="utf-8") as f:
        rows = csv.DictReader((line for line in f if not line.startswith("#")), delimiter="\t")
        out = {}
        for r in rows:
            langs = tuple(x.strip() for x in r["languages"].split(",") if x.strip())
            bad = [x for x in langs if not _LANGUAGE.match(x)]
            if bad:
                raise ValueError(f"잘못된 언어 코드 {bad}: {r['country']}")
            out[r["country"]] = langs
        return out


def label_languages(country):
    """(라벨 언어, 별칭 언어). 나라를 모르면 공통 라벨과 기본 언어 별칭만 받는다."""
    local = country_languages().get(country, ()) if country else ()
    labels = tuple(dict.fromkeys(COMMON_LABEL_LANGUAGES + tuple(x for x in local if x not in BASE_LANGUAGES)))
    return labels, tuple(dict.fromkeys(BASE_LANGUAGES + local))


# 지명(거주지·행정 구역·동네) 항목. 이 이름이 시설 이름에 들어 있어도 그 시설의 항목이 아니다.
# 인구(P1082)가 있거나 P31이 한 단계 안에서 이 클래스에 닿으면 지명이다.
# 하위 클래스를 끝까지 따라가면(P279*) 성·탑·사찰까지 행정 구역 계통에 닿아 한 단계만 본다.
# 도시·읍·마을 분류(Q515·Q3957·Q532 등)는 거주지에서 두 단계 아래라 따로 둔다. 인구가 없는 구역(Monte Carlo)이 여기 걸린다.
AREA_CLASSES = ("Q486972", "Q56061", "Q123705", "Q515", "Q3957", "Q532", "Q5084", "Q7930989", "Q2983893", "Q188509")
# 건축물·시설이기도 한 항목(주거·상업 단지)은 그 이름의 가게·몰 행 자체라 지명으로 보지 않는다.
NOT_AREA_CLASSES = ("Q811979", "Q13226383")
# 브랜드 항목은 P31이 브랜드·체인 분류(한 단계 하위까지)와 법인 분류뿐인 항목이다. 호텔·미술관이 business 하위라 법인은 상위로 따라가지 않는다.
# 법인 분류는 일반 법인 분류이거나 법적 형태(LEGAL_FORM_CLASSES)의 인스턴스인 분류(GmbH·kabushiki gaisha)다.
BRAND_CLASSES = ("Q431289", "Q65553774", "Q507619", "Q65553754", "Q1631129")
COMPANY_CLASSES = ("Q4830453", "Q783794", "Q6881511", "Q167037", "Q891723", "Q658255")
LEGAL_FORM_CLASSES = ("Q10541491", "Q1269299")
# 사업체(숙소·식당·가게·회사) 항목. 같은 이름 가게 행이 이 항목에서 멀면 좌표가 어긋난 같은 가게이고, 강·다리·명소 항목에서
# 멀면 이름을 딴 다른 가게다.
BUSINESS_CLASSES = ("Q4830453", "Q6881511", "Q783794", "Q27686", "Q11707", "Q213441", "Q30022", "Q212198", "Q187456",
                    "Q654772", "Q216107", "Q11315", "Q18534542")
AREA_BATCH = 200
_CLASS_QUERY = """
SELECT DISTINCT ?item WHERE {{
  VALUES ?item {{ {items} }}
  {population}
  {{ VALUES ?class {{ {classes} }} ?item wdt:P31/wdt:P279? ?class . }}
  {exclude}
}}
"""
_EXCLUDE = "FILTER NOT EXISTS {{ VALUES ?not {{ {classes} }} ?item wdt:P31/wdt:P279? ?not . }}"
_POPULATION = "{ ?item wdt:P1082 ?population . } UNION"
_BRAND_QUERY = """
SELECT DISTINCT ?item WHERE {{
  VALUES ?item {{ {items} }}
  VALUES ?brand {{ {brands} }}
  ?item wdt:P31/wdt:P279? ?brand .
  FILTER NOT EXISTS {{
    ?item wdt:P31 ?other .
    FILTER(?other NOT IN ({companies}))
    FILTER NOT EXISTS {{ VALUES ?b {{ {brands} }} ?other wdt:P279? ?b . }}
    FILTER NOT EXISTS {{ VALUES ?f {{ {legal_forms} }} ?other wdt:P31/wdt:P279? ?f . }}
  }}
}}
"""
_QID = re.compile(r"Q[0-9]+")


def area_qids(qids, fetch=None):
    """qids 가운데 지명 항목인 것의 집합. fetch(query) → SPARQL 결과 bindings 목록."""
    return class_qids(qids, AREA_CLASSES, fetch, population=True, exclude=NOT_AREA_CLASSES)


def business_qids(qids, fetch=None):
    """qids 가운데 사업체 항목인 것의 집합."""
    return class_qids(qids, BUSINESS_CLASSES, fetch)


def brand_qids(qids, fetch=None):
    """qids 가운데 브랜드·체인 항목인 것의 집합."""
    brands = " ".join(f"wd:{c}" for c in BRAND_CLASSES)
    companies = ", ".join(f"wd:{c}" for c in COMPANY_CLASSES)
    legal_forms = " ".join(f"wd:{c}" for c in LEGAL_FORM_CLASSES)
    return _batched(qids, lambda items: _BRAND_QUERY.format(items=items, brands=brands, companies=companies,
                                                            legal_forms=legal_forms), fetch)


def class_qids(qids, classes, fetch=None, population=False, exclude=()):
    """qids 가운데 P31이 한 단계 안에서 classes에 닿는(population이면 인구가 있는 것도) 항목. exclude에 닿는 항목은 뺀다."""
    names = " ".join(f"wd:{c}" for c in classes)
    excluded = _EXCLUDE.format(classes=" ".join(f"wd:{c}" for c in exclude)) if exclude else ""
    return _batched(qids, lambda items: _CLASS_QUERY.format(items=items, classes=names, exclude=excluded,
                                                            population=_POPULATION if population else ""), fetch)


def _batched(qids, query_for_items, fetch=None):
    """AREA_BATCH개씩 물어 고른 항목의 합집합. 시간 초과한 묶음은 반으로 나눠 다시 묻는다."""
    fetch = fetch or _Fetcher().run
    qids = sorted(set(qids))
    bad = [q for q in qids if not _QID.fullmatch(q)]
    if bad:
        raise ValueError(f"QID가 아닌 값: {bad[:5]}")
    queue = [qids[i:i + AREA_BATCH] for i in range(0, len(qids), AREA_BATCH)]
    found = set()
    while queue:
        batch = queue.pop()
        try:
            found.update(b["item"]["value"].rsplit("/", 1)[-1]
                         for b in fetch(query_for_items(" ".join(f"wd:{q}" for q in batch))))
        except TileTooLarge:
            if len(batch) == 1:
                raise
            queue += [batch[:len(batch) // 2], batch[len(batch) // 2:]]
    return found


def write_area_qids(qids, out_path, fetch=None):
    """area_qids 결과를 Parquet(열 qid)과 manifest로 쓴다. 반환은 manifest."""
    return _write_qids(area_qids(qids, fetch), qids, out_path, {"classes": list(AREA_CLASSES), "population": True,
                                                                 "not_classes": list(NOT_AREA_CLASSES)})


def write_brand_qids(qids, out_path, fetch=None):
    """brand_qids 결과를 Parquet(열 qid)과 manifest로 쓴다. 반환은 manifest."""
    return _write_qids(brand_qids(qids, fetch), qids, out_path,
                       {"brand_classes": list(BRAND_CLASSES), "company_classes": list(COMPANY_CLASSES),
                        "legal_form_classes": list(LEGAL_FORM_CLASSES)})


def write_business_qids(qids, out_path, fetch=None):
    """business_qids 결과를 Parquet(열 qid)과 manifest로 쓴다. 반환은 manifest."""
    return _write_qids(business_qids(qids, fetch), qids, out_path, {"classes": list(BUSINESS_CLASSES)})


def kind_qids(qids, fetch=None):
    """{(qid, 종류)}. 종류는 rules.load_item_kinds의 줄이고 한 항목이 여러 종류일 수 있다."""
    return {(q, kind) for kind, _, classes in load_item_kinds() for q in class_qids(qids, classes, fetch)}


def write_kind_qids(qids, out_path, fetch=None):
    """kind_qids 결과를 Parquet(열 qid, kind)과 manifest로 쓴다. 반환은 manifest."""
    import duckdb

    found = sorted(kind_qids(qids, fetch))
    snapshot._check_path(out_path)
    con = duckdb.connect()
    con.execute("CREATE TABLE a (qid VARCHAR, kind VARCHAR)")
    if found:
        con.executemany("INSERT INTO a VALUES (?, ?)", found)
    con.execute(f"COPY a TO '{out_path}' (FORMAT parquet)")
    manifest = {"source": "wikidata", "license": "CC0-1.0",
                "kinds": {k: list(c) for k, _, c in load_item_kinds()}, "asked": len(set(qids)), "rows": len(found),
                "fetched_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                "sha256": snapshot.sha256(out_path)}
    with open(out_path + ".manifest.json", "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    return manifest


def _write_qids(found, qids, out_path, rule):
    import duckdb

    snapshot._check_path(out_path)
    con = duckdb.connect()
    con.execute("CREATE TABLE a AS SELECT unnest(?::VARCHAR[]) AS qid", [sorted(found)])
    con.execute(f"COPY a TO '{out_path}' (FORMAT parquet)")
    manifest = {"source": "wikidata", "license": "CC0-1.0", **rule, "asked": len(set(qids)), "rows": len(found),
                "fetched_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                "sha256": snapshot.sha256(out_path)}
    with open(out_path + ".manifest.json", "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    return manifest


# 가나 표기 이름(P1814). 한자 이름의 읽기라 한국어 표기를 규칙으로 만들 수 있다.
_KANA_QUERY = """
SELECT ?item ?kana WHERE {{
  VALUES ?item {{ {items} }}
  ?item wdt:P1814 ?kana .
}}
"""


def kana_names(qids, fetch=None):
    """qids 가운데 가나 표기 이름이 있는 항목 → 첫 가나 이름. 시간 초과한 묶음은 반으로 나눠 다시 묻는다."""
    fetch = fetch or _Fetcher().run
    qids = sorted(set(qids))
    queue = [qids[i:i + AREA_BATCH] for i in range(0, len(qids), AREA_BATCH)]
    found = {}
    while queue:
        batch = queue.pop()
        try:
            rows = fetch(_KANA_QUERY.format(items=" ".join(f"wd:{q}" for q in batch)))
        except TileTooLarge:
            if len(batch) == 1:
                raise
            queue += [batch[:len(batch) // 2], batch[len(batch) // 2:]]
            continue
        for b in rows:
            found.setdefault(b["item"]["value"].rsplit("/", 1)[-1], b["kana"]["value"])
    return found


def write_kana_names(qids, out_path, fetch=None):
    """kana_names 결과를 Parquet(열 qid, kana)과 manifest로 쓴다. 반환은 manifest."""
    import duckdb

    snapshot._check_path(out_path)
    found = kana_names(qids, fetch)
    con = duckdb.connect()
    con.execute("CREATE TABLE k (qid VARCHAR, kana VARCHAR)")
    con.executemany("INSERT INTO k VALUES (?, ?)", sorted(found.items()))
    con.execute(f"COPY k TO '{out_path}' (FORMAT parquet)")
    manifest = {"source": "wikidata", "license": "CC0-1.0", "property": "P1814", "asked": len(set(qids)),
                "rows": len(found), "fetched_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                "sha256": snapshot.sha256(out_path)}
    with open(out_path + ".manifest.json", "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    return manifest


class TileTooLarge(Exception):
    """칸 결과가 시간 안에 오지 않았다. 나눠서 다시 받는다."""


def query_for(tile, country=""):
    lon0, lat0, lon1, lat1 = tile
    labels, aliases = label_languages(country)
    quoted = lambda langs: ", ".join(f'"{x}"' for x in langs)
    return _QUERY.format(lon0=lon0, lat0=lat0, lon1=lon1, lat1=lat1, labels=quoted(labels), aliases=quoted(aliases))


def tiles(area, size=START_TILE_DEGREES):
    lon = area.lon0
    while lon < area.lon1:
        lat = area.lat0
        while lat < area.lat1:
            yield (lon, lat, min(lon + size, area.lon1), min(lat + size, area.lat1))
            lat += size
        lon += size


def collect(area, fetch=None, log=print):
    """범위 안 항목 행 목록과 요청 통계. fetch(tile) → 행 목록이며 TileTooLarge를 던질 수 있다."""
    fetch = fetch or _Fetcher(area.country)
    queue = list(tiles(area))
    rows, by_qid = [], {}
    while queue:
        tile = queue.pop()
        try:
            got = fetch(tile)
        except TileTooLarge:
            got = None
        width = tile[2] - tile[0]
        if (got is None or len(got) >= SPLIT_ROWS) and width / 2 >= MIN_TILE_DEGREES:
            queue.extend(_quarters(tile))
            continue
        if got is None:
            raise RuntimeError(f"가장 작은 칸도 시간 안에 오지 않았다: {tile}")
        for row in got:
            # 칸 경계 위 항목과 좌표가 여러 개인 항목은 여러 칸·여러 행으로 온다.
            kept = by_qid.get(row["qid"])
            if kept is None:
                kept = by_qid[row["qid"]] = {**row, "coords": []}
                rows.append(kept)
            if [row["lat"], row["lon"]] not in kept["coords"]:
                kept["coords"].append([row["lat"], row["lon"]])
        if got:
            log(f"{tile} {len(got)}행, 누적 {len(rows)}")
    for row in rows:
        row["coords"].sort()
        row["lat"], row["lon"] = row["coords"][(len(row["coords"]) - 1) // 2]
    return rows, getattr(fetch, "stats", {})


def pull(area, out_path, fetch=None, log=print):
    """범위를 받아 Parquet과 manifest를 쓴다. 반환은 manifest."""
    import duckdb

    snapshot._check_path(out_path)
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    started = time.monotonic()
    rows, stats = collect(area, fetch, log)
    seconds = time.monotonic() - started
    jsonl = out_path + ".jsonl"
    with open(jsonl, "w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
    con = duckdb.connect()
    con.execute(f"""
        COPY (SELECT * FROM read_json('{jsonl}', columns = {{qid: 'VARCHAR', lat: 'DOUBLE', lon: 'DOUBLE',
              sitelinks: 'INTEGER', ja: 'VARCHAR', ko: 'VARCHAR', en: 'VARCHAR', jawiki: 'BOOLEAN', kowiki: 'BOOLEAN',
              labels: 'VARCHAR[]', aliases: 'VARCHAR[]', coords: 'DOUBLE[][]'}}))
        TO '{out_path}' (FORMAT parquet, COMPRESSION zstd)
    """)
    os.remove(jsonl)
    manifest = {
        "source": "wikidata", "license": "CC0-1.0", "terms_url": "https://www.wikidata.org/wiki/Wikidata:Licensing",
        "release": datetime.now(timezone.utc).date().isoformat(), "scope": area.name,
        "label_languages": list(label_languages(area.country)[0]), "alias_languages": list(label_languages(area.country)[1]),
        "fetched_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "rows": len(rows), "file_bytes": os.path.getsize(out_path), "sha256": snapshot.sha256(out_path),
        "seconds": round(seconds, 1), "http_requests": stats.get("requests", 0),
        "http_bytes": stats.get("bytes", 0), "max_rss_mb": snapshot.max_rss_mb(),
    }
    with open(out_path + ".manifest.json", "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    return manifest


def _quarters(tile):
    lon0, lat0, lon1, lat1 = tile
    mx, my = (lon0 + lon1) / 2, (lat0 + lat1) / 2
    return [(lon0, lat0, mx, my), (mx, lat0, lon1, my), (lon0, my, mx, lat1), (mx, my, lon1, lat1)]


class _Fetcher:
    def __init__(self, country=""):
        self.stats = {"requests": 0, "bytes": 0}
        self._last = 0.0
        self._country = country

    def __call__(self, tile):
        try:
            return [_row(b) for b in self.run(query_for(tile, self._country))]
        except RuntimeError as e:
            raise RuntimeError(f"{e}: {tile}") from e

    def run(self, query):
        body = urllib.parse.urlencode({"query": query, "format": "json"}).encode()
        gateway_timeouts = 0
        for attempt in range(MAX_RETRIES):
            wait = MIN_INTERVAL_SECONDS - (time.monotonic() - self._last)
            if wait > 0:
                time.sleep(wait)
            self._last = time.monotonic()
            self.stats["requests"] += 1
            request = urllib.request.Request(ENDPOINT, data=body, headers={
                "User-Agent": USER_AGENT, "Accept": "application/sparql-results+json",
                "Content-Type": "application/x-www-form-urlencoded"})
            try:
                with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
                    raw = response.read()
            except urllib.error.HTTPError as e:
                text = e.read()[:2000].decode("utf-8", "replace")
                if e.code == 500 and "TimeoutException" in text:
                    raise TileTooLarge() from e
                if e.code in (502, 504):
                    # 게이트웨이가 질의 시간 초과를 500 대신 이 코드로 돌려준다. 두 번이면 칸이 큰 것이다.
                    gateway_timeouts += 1
                    if gateway_timeouts >= 2:
                        raise TileTooLarge() from e
                if e.code in (429, 502, 503, 504):
                    time.sleep(_retry_after(e, attempt))
                    continue
                raise
            except TimeoutError as e:
                raise TileTooLarge() from e
            except (urllib.error.URLError, http.client.HTTPException, ConnectionError):
                time.sleep(5 * 2 ** attempt)
                continue
            self.stats["bytes"] += len(raw)
            return json.loads(raw)["results"]["bindings"]
        raise RuntimeError(f"재시도 {MAX_RETRIES}회 실패")


def _retry_after(error, attempt):
    header = error.headers.get("Retry-After") if error.headers else None
    if header and header.isdigit():
        return int(header)
    return 5 * 2 ** attempt


def _row(binding):
    value = lambda k: binding[k]["value"] if k in binding else None
    return {"qid": value("item").rsplit("/", 1)[-1], "lat": float(value("lat")), "lon": float(value("lon")),
            "sitelinks": int(value("sitelinks")), "ja": value("ja"), "ko": value("ko"), "en": value("en"),
            "jawiki": "jawiki" in binding, "kowiki": "kowiki" in binding,
            "labels": _split(value("labels")), "aliases": _split(value("aliases"))}


def _split(joined):
    return [x for x in (joined or "").split(_SEP) if x.strip()]
