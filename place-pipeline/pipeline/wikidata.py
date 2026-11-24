"""
Wikidata 좌표 항목 스냅샷. 범위를 격자 칸으로 나눠 SPARQL로 받고, 칸이 크면(시간 초과·행 많음) 4등분한다.
행: qid, lat, lon, sitelinks, ja, ko, en, jawiki, kowiki. 국가 속성(P17)이 빠진 항목도 좌표로 잡도록 P17로 거르지 않는다.
"""
import http.client
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

from . import snapshot

ENDPOINT = "https://query.wikidata.org/sparql"
# Wikidata는 User-Agent 없는 요청을 거부한다. 운영 배치(WikidataSitelinksJob)와 같은 값을 쓴다.
USER_AGENT = "shg-trip/1.0 (travel itinerary planner; +https://github.com/byunggwan-kim0918/shg-trip-back)"
# WDQS 공개 엔드포인트 예절 범위. 동시 요청 없이 초당 한 번.
MIN_INTERVAL_SECONDS = 1.0
REQUEST_TIMEOUT_SECONDS = 75
MAX_RETRIES = 4
# 한 칸 결과가 이만큼이면 잘렸을 수 있어 4등분한다.
SPLIT_ROWS = 15000
MIN_TILE_DEGREES = 0.0625
START_TILE_DEGREES = 1.0

_QUERY = """
SELECT ?item ?lat ?lon ?sitelinks ?ja ?ko ?en ?jawiki ?kowiki WHERE {{
  SERVICE wikibase:box {{
    ?item wdt:P625 ?coord .
    bd:serviceParam wikibase:cornerSouthWest "Point({lon0} {lat0})"^^geo:wktLiteral .
    bd:serviceParam wikibase:cornerNorthEast "Point({lon1} {lat1})"^^geo:wktLiteral .
  }}
  ?item wikibase:sitelinks ?sitelinks .
  BIND(geof:latitude(?coord) AS ?lat) BIND(geof:longitude(?coord) AS ?lon)
  OPTIONAL {{ ?item rdfs:label ?ja FILTER(lang(?ja) = "ja") }}
  OPTIONAL {{ ?item rdfs:label ?ko FILTER(lang(?ko) = "ko") }}
  OPTIONAL {{ ?item rdfs:label ?en FILTER(lang(?en) = "en") }}
  OPTIONAL {{ ?jawiki schema:about ?item ; schema:isPartOf <https://ja.wikipedia.org/> }}
  OPTIONAL {{ ?kowiki schema:about ?item ; schema:isPartOf <https://ko.wikipedia.org/> }}
}}
"""


# 지명(거주지·행정 구역·동네) 항목. 이 이름이 시설 이름에 들어 있어도 그 시설의 항목이 아니다.
# 인구(P1082)가 있거나 P31이 한 단계 안에서 이 클래스에 닿으면 지명이다.
# 하위 클래스를 끝까지 따라가면(P279*) 성·탑·사찰까지 행정 구역 계통에 닿아 한 단계만 본다.
AREA_CLASSES = ("Q486972", "Q56061", "Q123705")
AREA_BATCH = 200
_AREA_QUERY = """
SELECT DISTINCT ?item WHERE {{
  VALUES ?item {{ {items} }}
  {{ ?item wdt:P1082 ?population . }}
  UNION
  {{ VALUES ?class {{ {classes} }} ?item wdt:P31/wdt:P279? ?class . }}
}}
"""


def area_qids(qids, fetch=None):
    """qids 가운데 지명 항목인 것의 집합. fetch(query) → SPARQL 결과 bindings 목록. 시간 초과한 묶음은 반으로 나눠 다시 묻는다."""
    fetch = fetch or _Fetcher().run
    qids = sorted(set(qids))
    queue = [qids[i:i + AREA_BATCH] for i in range(0, len(qids), AREA_BATCH)]
    found = set()
    while queue:
        batch = queue.pop()
        query = _AREA_QUERY.format(items=" ".join(f"wd:{q}" for q in batch),
                                   classes=" ".join(f"wd:{c}" for c in AREA_CLASSES))
        try:
            found.update(b["item"]["value"].rsplit("/", 1)[-1] for b in fetch(query))
        except TileTooLarge:
            if len(batch) == 1:
                raise
            queue += [batch[:len(batch) // 2], batch[len(batch) // 2:]]
    return found


def write_area_qids(qids, out_path, fetch=None):
    """area_qids 결과를 Parquet(열 qid)과 manifest로 쓴다. 반환은 manifest."""
    import duckdb

    snapshot._check_path(out_path)
    found = area_qids(qids, fetch)
    con = duckdb.connect()
    con.execute("CREATE TABLE a AS SELECT unnest(?::VARCHAR[]) AS qid", [sorted(found)])
    con.execute(f"COPY a TO '{out_path}' (FORMAT parquet)")
    manifest = {"source": "wikidata", "license": "CC0-1.0", "classes": list(AREA_CLASSES), "asked": len(set(qids)),
                "rows": len(found), "fetched_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                "sha256": snapshot.sha256(out_path)}
    with open(out_path + ".manifest.json", "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    return manifest


class TileTooLarge(Exception):
    """칸 결과가 시간 안에 오지 않았다. 나눠서 다시 받는다."""


def query_for(tile):
    lon0, lat0, lon1, lat1 = tile
    return _QUERY.format(lon0=lon0, lat0=lat0, lon1=lon1, lat1=lat1)


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
    fetch = fetch or _Fetcher()
    queue = list(tiles(area))
    rows, seen = [], set()
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
            # 칸 경계 위 항목과 좌표가 여러 개인 항목은 여러 칸에서 온다.
            if row["qid"] not in seen:
                seen.add(row["qid"])
                rows.append(row)
        if got:
            log(f"{tile} {len(got)}행, 누적 {len(rows)}")
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
              sitelinks: 'INTEGER', ja: 'VARCHAR', ko: 'VARCHAR', en: 'VARCHAR', jawiki: 'BOOLEAN', kowiki: 'BOOLEAN'}}))
        TO '{out_path}' (FORMAT parquet, COMPRESSION zstd)
    """)
    os.remove(jsonl)
    manifest = {
        "source": "wikidata", "license": "CC0-1.0", "terms_url": "https://www.wikidata.org/wiki/Wikidata:Licensing",
        "release": datetime.now(timezone.utc).date().isoformat(), "scope": area.name,
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
    def __init__(self):
        self.stats = {"requests": 0, "bytes": 0}
        self._last = 0.0

    def __call__(self, tile):
        try:
            return [_row(b) for b in self.run(query_for(tile))]
        except RuntimeError as e:
            raise RuntimeError(f"{e}: {tile}") from e

    def run(self, query):
        body = urllib.parse.urlencode({"query": query, "format": "json"}).encode()
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
            "jawiki": "jawiki" in binding, "kowiki": "kowiki" in binding}
