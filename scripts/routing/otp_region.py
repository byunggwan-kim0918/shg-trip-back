"""지역 OTP 그래프를 regions.json 정의대로 만들고 띄운다.

  python3 -I otp_region.py build jp-kanto     # 내려받기 → 자르기 → 임시 빌드 → 품질 검사 → 통과하면 교체
  python3 -I otp_region.py serve jp-kanto     # 127.0.0.1에만 바인딩
  python3 -I otp_region.py rollback jp-kanto  # 교체 전 그래프로 되돌린다

내려받은 원본은 build/otp/sources에 두고 다시 쓴다. 피드는 모두 gtfs_clip.py를 거쳐 참조가 깨진 행을 빼야
피드 하나 때문에 빌드 전체가 멈추지 않는다.
"""
import collections
import csv
import fcntl
import http.client
import signal
import hashlib
import re
import tarfile
import tempfile
import io
import datetime
import json
import os
import pathlib
import shutil
import subprocess
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import zipfile
import zoneinfo

HERE = os.path.dirname(os.path.abspath(__file__))
# -I 실행은 스크립트 폴더를 경로에 넣지 않는다. 같은 폴더의 파이프라인 모듈만 명시적으로 더한다.
sys.dont_write_bytecode = True
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import feed_compare  # noqa: E402
import feed_versions  # noqa: E402
import gtfs_clip  # noqa: E402

ROOT = os.path.normpath(os.path.join(HERE, "..", "..", "build", "otp"))
SOURCES = os.path.join(ROOT, "sources")
OTP_VERSION = "2.8.1"
OTP_JAR = os.path.join(ROOT, "bin", f"otp-shaded-{OTP_VERSION}.jar")
OTP_URL = f"https://repo1.maven.org/maven2/org/opentripplanner/otp-shaded/{OTP_VERSION}/otp-shaded-{OTP_VERSION}.jar"
GEOFABRIK = "https://download.geofabrik.de/"
MDB_CATALOG = "https://files.mobilitydatabase.org/feeds_v2.csv"
ATLAS_TARBALL = "https://codeload.github.com/transitland/transitland-atlas/tar.gz/refs/heads/main"
FEED_TIMEOUT_SECONDS = 60
FAILED_FEEDS = os.path.join(SOURCES, "catalog", "failed-feeds.json")
FAILED_FEED_RETRY_SECONDS = 24 * 3600
PAST_DAYS = 7
FETCH_RETRIES = 4
# 핵심 피드 운행 수가 이 비율 밑으로 줄면 원천 오류로 보고 교체하지 않는다.
TRIP_DROP_LIMIT = 0.5
CALENDAR_SPAN_SUSPECT_YEARS = 3
MAX_QUARANTINE = 5
FETCH_BACKOFF_SECONDS = 15
# 그래프에 싣는 운행일 범위. 피드 자체 유효기간이 더 짧으면 그쪽이 끝이다.
FUTURE_DAYS = 365


def config():
    with open(os.path.join(HERE, "regions.json"), encoding="utf-8") as f:
        return json.load(f)


def fetch(url, path, refresh=False, timeout=600, retries=FETCH_RETRIES, max_age_days=None):
    fresh = max_age_days is None or time.time() - os.path.getmtime(path) < max_age_days * 86400 if os.path.exists(path) else False
    # 깨진 사본(오류 페이지 등)은 받은 지 얼마 안 됐어도 다시 받는다.
    valid = not path.endswith(".zip") or usable_copy(path) if os.path.exists(path) else False
    if os.path.exists(path) and os.path.getsize(path) > 0 and not refresh and fresh and valid:
        return path
    if OFFLINE[0]:
        if os.path.exists(path) and os.path.getsize(path) > 0 and valid:
            return path
        raise OSError("offline: no local copy")
    if urllib.parse.urlsplit(url).scheme not in ("https", "http"):
        raise ValueError("unsupported url scheme")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".part"
    req = urllib.request.Request(quote_url(url), headers={"User-Agent": "shg-route-builder"})
    for attempt in range(retries):
        try:
            # urlopen의 timeout은 읽기 한 번마다라서 조금씩 보내는 서버를 끊지 못한다. 전체 기한을 따로 둔다.
            deadline = time.monotonic() + timeout
            with urllib.request.urlopen(req, timeout=min(timeout, 60)) as r, open(tmp, "wb") as out:
                expected = r.headers.get("Content-Length")
                received = 0
                while chunk := r.read(1 << 20):
                    out.write(chunk)
                    received += len(chunk)
                    if time.monotonic() > deadline:
                        raise TimeoutError("download deadline")
            # 연결이 일찍 끊기면 read가 예외 없이 끝나 잘린 OSM·jar가 정상 파일로 남는다.
            if expected and expected.isdigit() and int(expected) != received:
                raise http.client.IncompleteRead(b"", int(expected) - received)
            # 검증 전에 바꿔 넣으면 깨진 응답(HTML 등)이 좋은 이전 사본을 덮고, 새 파일로 보여 다시 받지도 않는다.
            if path.endswith(".zip"):
                check_zip(tmp)
            os.replace(tmp, path)
            return path
        except (OSError, http.client.HTTPException) as e:
            # HTTPError(4xx·5xx)는 다시 물어도 같은 답이라 바로 낸다.
            if attempt == retries - 1 or isinstance(e, urllib.error.HTTPError) and e.code < 500:
                raise
            time.sleep(FETCH_BACKOFF_SECONDS * 2 ** attempt)
        finally:
            if os.path.exists(tmp):
                os.remove(tmp)


# 기준선 확인처럼 받은 사본만으로 판정해야 할 때 켠다. 켜져 있으면 내려받지 않고 사본이 없으면 실패한다.
OFFLINE = [False]


def usable_copy(path):
    try:
        check_zip(path)
        return True
    except Exception:
        return False


def check_zip(path):
    with zipfile.ZipFile(path) as z:
        broken = z.testzip()
    if broken:
        raise zipfile.BadZipFile("crc " + broken)


# 같은 피드의 판·인증만 바꾸는 쿼리 값. 나머지 쿼리(예: drive.google.com/uc?id=)는 피드를 가른다.
VOLATILE_QUERY = {"date", "acl:consumerkey"}
# 호스트마다 판을 고르는 쿼리. GTFSデータリポジトリ는 rid로 판을 고르고, 다른 포털의 rid는 피드 식별자일 수 있다.
HOST_VOLATILE_QUERY = {"api.gtfs-data.jp": {"rid"}}


def source_key(url, aliases=None):
    """원천 주소 하나로 피드 정체를 정한다. 같은 피드가 여러 카탈로그에 실려 두 번 들어가지 않게 한다."""
    parts = urllib.parse.urlsplit(url.strip())
    host = parts.netloc.lower()
    host = (aliases or {}).get(host, host)
    volatile = VOLATILE_QUERY | HOST_VOLATILE_QUERY.get(host, set())
    query = sorted((k, v) for k, v in urllib.parse.parse_qsl(parts.query) if k.lower() not in volatile)
    return host + parts.path.rstrip("/").lower() + ("?" + urllib.parse.urlencode(query) if query else "")


def feed_zip(path):
    """하위 폴더에 든 GTFS도 맨 위에 있는 것처럼 읽는다."""
    return gtfs_clip.FeedZip(path)


S3_URI = re.compile(r"^s3://[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]/[A-Za-z0-9!_.*'()/-]+$")
S3_TIMEOUT_SECONDS = 1800


def fetch_s3(uri, path):
    """비공개 버킷에 둔 신청 자료를 받는다. 자격 증명은 실행 환경의 AWS 기본 설정을 쓴다."""
    if not S3_URI.match(uri):
        raise ValueError("invalid s3 uri")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".part"
    if OFFLINE[0]:
        if usable_copy(path):
            return path
        raise OSError("offline: no local copy")
    try:
        r = subprocess.run(["aws", "s3", "cp", uri, tmp, "--only-show-errors"], timeout=S3_TIMEOUT_SECONDS,
                           capture_output=True, text=True)
        if r.returncode != 0:
            raise OSError("s3 cp failed: " + tail(r.stderr))
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)
    return path


def tail(text, chars=200):
    return " ".join((text or "").split())[-chars:]


def edition_stale(feed, today=None):
    """판 기준월(YYYY-MM)이 editionMaxAgeDays보다 오래되면 새 판을 신청할 때다."""
    edition, max_age = feed.get("edition"), feed.get("editionMaxAgeDays")
    if not edition or not max_age:
        return False
    m = re.fullmatch(r"(\d{4})-(\d{2})", edition)
    if not m:
        raise ValueError(f"edition must be YYYY-MM: {edition}")
    year, month = map(int, m.groups())
    return ((today or datetime.date.today()) - datetime.date(year, month, 1)).days > max_age


def quote_url(url):
    parts = urllib.parse.urlsplit(url)
    return urllib.parse.urlunsplit(parts._replace(path=urllib.parse.quote(parts.path, safe="/%:@"),
                                                  query=urllib.parse.quote(parts.query, safe="=&%:@,+/")))


def normalize(subdivision):
    plain = unicodedata.normalize("NFKD", subdivision).encode("ascii", "ignore").decode().lower().strip()
    return plain[:-len(" prefecture")] if plain.endswith(" prefecture") else plain


def mdb_feeds(country, subdivisions, bbox=None):
    """카탈로그에서 지역에 든 운행 중·인증 없는 GTFS. subdivisions(현·주)가 있으면 그 이름으로, 없으면 카탈로그
    location.bounding_box가 지역 bbox와 겹치는지로 고른다. 이름 목록 없이 bbox만 적어도 새 나라를 더할 수 있다."""
    catalog = fetch(MDB_CATALOG, os.path.join(SOURCES, "catalog", "feeds_v2.csv"), max_age_days=catalog_refresh_days())
    wanted = {normalize(x) for x in subdivisions}
    out, realtime, replaces = [], {}, {}
    with open(catalog, encoding="utf-8") as f:
        rows = [r for r in csv.DictReader(f) if r["location.country_code"] == country]
    for r in rows:
        # 실시간 피드는 static_reference로 정적 피드에 붙는다. 갱신기는 설정에 적은 것만 켜고 여기서는 기록만 한다.
        if r["data_type"] == "gtfs_rt" and r.get("static_reference"):
            for ref in r["static_reference"].split("|"):
                realtime.setdefault(ref.strip(), []).append(
                    {"id": r["id"], "url": r["urls.direct_download"] or None, "features": r.get("features") or None,
                     "auth": r["urls.authentication_type"] not in ("", "0")})
        if r.get("redirect.id"):
            for target in r["redirect.id"].split("|"):
                replaces.setdefault(target.strip(), []).append(r["id"])
    for r in rows:
        if r["data_type"] != "gtfs":
            continue
        if r["status"] in ("inactive", "deprecated") or r["urls.authentication_type"] not in ("", "0"):
            continue
        if not r["urls.latest"]:
            continue
        if wanted and normalize(r["location.subdivision_name"]) not in wanted:
            continue
        if not wanted and bbox and not mdb_bbox_overlaps(r, bbox):
            continue
        ORIGINS[r["id"]] = {"source": r["urls.direct_download"] or r["urls.latest"], "catalog": "mobility-database",
                            "license": r.get("urls.license") or None,
                            "official": {"True": True, "False": False}.get(r.get("is_official", "")),
                            "realtime": realtime.get(r["id"]) or None, "replaces": replaces.get(r["id"]) or None}
        out.append((r["id"], r["urls.latest"]))
    return out


def mdb_bbox_overlaps(row, bbox):
    """카탈로그에 범위가 없는 피드는 빼지 않는다. 받은 뒤 자르기가 영역 밖 운행을 뺀다."""
    try:
        lo_lat, hi_lat = float(row["location.bounding_box.minimum_latitude"]), float(row["location.bounding_box.maximum_latitude"])
        lo_lng, hi_lng = float(row["location.bounding_box.minimum_longitude"]), float(row["location.bounding_box.maximum_longitude"])
    except (KeyError, ValueError, TypeError):
        return True
    return lo_lat <= bbox[2] and hi_lat >= bbox[0] and lo_lng <= bbox[3] and hi_lng >= bbox[1]


def mdb_known_sources(country, aliases=None):
    """카탈로그에 이미 있는 피드의 원천 주소 키와 id. ODPT·Atlas 피드와 겹치는지 가리는 데 쓴다."""
    catalog = fetch(MDB_CATALOG, os.path.join(SOURCES, "catalog", "feeds_v2.csv"), max_age_days=catalog_refresh_days())
    keys, ids = set(), set()
    with open(catalog, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if r["location.country_code"] == country:
                ids.add(r["id"])
                for col in ("urls.direct_download", "urls.latest"):
                    if r[col]:
                        keys.add(source_key(r[col], aliases))
    return keys, ids


def odpt_feeds(bbox, feeds, known, aliases=None, country="JP"):
    """ODPT 카탈로그 피드 중 정류장 중심이 bbox 안에 든 것. 토큰이 필요한 피드는 토큰이 있을 때만 받는다.
    known(원천 주소 키)에 있으면 건너뛰고, 받은 피드는 known에 더해 다음 카탈로그가 같은 피드를 다시 싣지 않게 한다."""
    token = os.environ.get("ODPT_ACCESS_TOKEN", "")
    _, mdb_ids = mdb_known_sources(country, aliases)
    out, report = [], []
    for feed in feeds:
        key = source_key(feed["url"], aliases)
        path = feed["url"].split("?")[0].split("/files/")[-1].removesuffix(".zip").replace("odpt/", "", 1)
        if key in known or "odpt-" + path.replace("/", "-") in mdb_ids:
            continue
        if feed["token"] and not token:
            report.append(f"skip {feed['id']}: needs ODPT_ACCESS_TOKEN")
            continue
        src = os.path.join(SOURCES, "gtfs", feed["id"] + ".gtfs.zip")
        url = feed["url"] + (("&" if "?" in feed["url"] else "?") + "acl:consumerKey=" + token if feed["token"] else "")
        try:
            catalog_fetch(feed["id"], url, src)
        except Exception as e:  # 피드 하나가 실패해도 나머지로 그래프를 만든다. 주소에 토큰이 있어 예외 문구는 남기지 않는다.
            report.append(f"skip {feed['id']}: download {type(e).__name__}")
            continue
        known.add(key)
        try:
            center = stops_center(src)
        except Exception as e:
            report.append(f"skip {feed['id']}: unreadable {type(e).__name__}")
            continue
        if center and bbox[0] <= center[0] <= bbox[2] and bbox[1] <= center[1] <= bbox[3]:
            # 토큰이 붙은 주소가 아니라 목록 주소를 남긴다.
            ORIGINS[feed["id"]] = {"source": feed["url"], "catalog": "odpt", "official": True}
            out.append((feed["id"], src))
    return out, report


def catalog_refresh_days():
    return config().get("catalogRefreshDays")


def load_failed_feeds():
    try:
        with open(FAILED_FEEDS) as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def catalog_fetch(fid, url, src):
    """카탈로그 피드 받기. 실패한 주소는 하루 동안 다시 시도하지 않는다(오래된 주소가 많다).
    다시 받기에 실패하면 전에 받은 파일을 쓴다. 기간이 지난 운행은 자르기에서 빠진다."""
    failed = load_failed_feeds()
    has_copy = usable_copy(src)
    if has_copy:
        keep_current(versions_store(), fid, src, ORIGINS.get(fid))
    if time.time() - failed.get(fid, 0) < FAILED_FEED_RETRY_SECONDS:
        if has_copy:
            return src
        raise TimeoutError("recently failed")
    try:
        return fetch(url, src, timeout=FEED_TIMEOUT_SECONDS, retries=1,
                     max_age_days=safe_refresh_days(versions_store(), fid, catalog_refresh_days()))
    except Exception:
        failed[fid] = time.time()
        # 반쯤 쓴 파일이 남으면 이후 모든 카탈로그 피드가 읽기 오류로 건너뛰어진다.
        tmp = FAILED_FEEDS + ".tmp"
        with open(tmp, "w") as f:
            json.dump(failed, f)
        os.replace(tmp, FAILED_FEEDS)
        if has_copy:
            return src
        raise


def atlas_feeds(bbox, match, country, known, aliases=None):
    """Transitland Atlas에서 match 정규식에 걸리고 인증이 없는 GTFS 중 정류장 중심이 bbox 안인 것.
    Atlas에는 위치가 없어 나라별 이름·주소 패턴으로 후보를 고른 뒤 받아서 위치를 본다."""
    tarball = fetch(ATLAS_TARBALL, os.path.join(SOURCES, "catalog", "transitland-atlas.tar.gz"),
                    max_age_days=catalog_refresh_days())
    pattern = re.compile(match)
    out, report = [], []
    with tarfile.open(tarball) as tar:
        for member in tar.getmembers():
            if not member.isfile() or "/feeds/" not in member.name or not member.name.endswith(".json"):
                continue
            try:
                doc = json.load(tar.extractfile(member))
            except ValueError:
                continue
            for feed in doc.get("feeds", []):
                url = (feed.get("urls") or {}).get("static_current")
                if feed.get("spec", "gtfs") != "gtfs" or not url or feed.get("authorization"):
                    continue
                key = source_key(url, aliases)
                if key in known:
                    continue
                if not pattern.search(feed["id"] + " " + url + " " + json.dumps(feed.get("operators", []), ensure_ascii=False)):
                    continue
                fid = "atlas-" + safe_id(feed["id"], 120)
                src = os.path.join(SOURCES, "gtfs", fid + ".gtfs.zip")
                try:
                    catalog_fetch(fid, url, src)
                except Exception as e:  # 오래된 주소가 많아 실패한 피드는 건너뛴다.
                    report.append(f"skip {fid}: download {type(e).__name__}")
                    continue
                known.add(key)
                try:
                    center = stops_center(src)
                except Exception as e:
                    report.append(f"skip {fid}: unreadable {type(e).__name__}")
                    continue
                if center and bbox[0] <= center[0] <= bbox[2] and bbox[1] <= center[1] <= bbox[3]:
                    ORIGINS[fid] = {"source": url, "catalog": "transitland-atlas", "catalogId": feed["id"],
                                    "license": feed.get("license")}
                    out.append((fid, src))
    return out, report


def stops_center(path):
    with feed_zip(path).open("stops.txt") as f:
        lats, lngs = [], []
        for r in csv.DictReader(io.TextIOWrapper(f, "utf-8-sig")):
            try:
                lats.append(float(r["stop_lat"]))
                lngs.append(float(r["stop_lon"]))
            except (KeyError, ValueError):
                continue
    return (sum(lats) / len(lats), sum(lngs) / len(lngs)) if lats else None


def clip(src, dst, bbox, feed=None, window=None):
    """window(시작, 끝 YYYYMMDD)가 있으면 그 기간으로 달력까지 자른다."""
    feed = feed or {}
    today = datetime.date.today()
    start = (today - datetime.timedelta(days=PAST_DAYS)).strftime("%Y%m%d")
    end = (today + datetime.timedelta(days=FUTURE_DAYS)).strftime("%Y%m%d")
    if window:
        start, end = max(start, window[0] or start), min(end, window[1] or end)
    code, out, err, peak = run_measured([sys.executable, "-I", os.path.join(HERE, "gtfs_clip.py"), src, dst,
                                         "--bbox", ",".join(map(str, bbox)), "--from", start, "--to", end,
                                         "--route-type-map", feed.get("routeTypeMap", ""),
                                         "--drop-route-types", feed.get("dropRouteTypes", ""),
                                         "--drop-route-name-regex", feed.get("dropRouteNameRegex", ""),
                                         *(["--clamp-calendar"] if window else [])])
    CLIP_PEAK[0] = max(CLIP_PEAK[0], peak)
    last = out.strip().splitlines()[-1] if code == 0 and out.strip() else ""
    reason = None
    if code != 0:
        reason = f"clip failed ({tail(err)})"
    elif "trips=0" in last:
        reason = "no trips in area/period"
    elif (feed.get("dropRouteNameRegex") or feed.get("dropRouteTypes")) and "dropped_routes=0" in last:
        # 제외 규칙이 하나도 안 걸리면 판이 바뀌어 노선 이름 형식이 달라진 것이라 겹치는 노선이 두 번 실린다.
        reason = "drop rule matched no route"
    if reason:
        if os.path.exists(dst):
            os.remove(dst)
        return None, reason
    return last, None


CLIP_PEAK = [0]


def run_measured(cmd, log=None):
    """자식 하나의 최대 메모리를 따로 잰다. RUSAGE_CHILDREN은 자르기와 OTP 빌드 최댓값이 섞인다."""
    with tempfile.TemporaryFile("w+") as out, tempfile.TemporaryFile("w+") as err:
        p = subprocess.Popen(cmd, stdout=log or out, stderr=subprocess.STDOUT if log else err, text=True)
        try:
            _, status, usage = os.wait4(p.pid, 0)
        except BaseException:
            # 빌드가 중단돼도 수 GB짜리 OTP JVM이 남아 다음 빌드·검사 포트를 막지 않게 한다.
            p.kill()
            p.wait()
            raise
        p.returncode = os.waitstatus_to_exitcode(status)
        out.seek(0)
        err.seek(0)
        # macOS ru_maxrss는 바이트, Linux는 KB다.
        peak = usage.ru_maxrss if sys.platform == "darwin" else usage.ru_maxrss * 1024
        return p.returncode, out.read(), err.read(), peak


TERMINATED = 143


def build(name, force=True, promote_graph=True):
    """force=False면 입력(피드 판·OSM·설정)이 지금 그래프와 같을 때 짓지 않는다. promote_graph=False면 검사만 하고 교체하지 않는다."""
    # 같은 지역을 두 번 돌리면 진행 중인 staging을 지우고, 지역끼리는 sources의 .part·실패 기록을 같이 쓴다.
    lock = build_lock()
    # 기본 SIGTERM은 예외 없이 끝나 OTP 자식이 남는다. 예외로 바꿔 run_measured가 자식을 정리하게 한다.
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(TERMINATED))
    try:
        return build_locked(name, force, promote_graph)
    finally:
        lock.close()


def build_locked(name, force=True, promote_graph=True):
    cfg = config()
    region = cfg["regions"][name]
    bbox = region["bbox"]
    graph = os.path.join(ROOT, name, "staging")
    shutil.rmtree(graph, ignore_errors=True)
    os.makedirs(graph)
    fetch(OTP_URL, OTP_JAR)
    osm = None
    if region.get("osm") or region.get("osmUrl"):
        url = region.get("osmUrl") or GEOFABRIK + region["osm"] + "-latest.osm.pbf"
        osm = fetch(url, os.path.join(SOURCES, "osm", name + ".osm.pbf"))
        os.symlink(osm, os.path.join(graph, "region.osm.pbf"))
    downloaded, report, origins = collect(name, region, cfg)
    downloaded, windows, versions, lifecycle = select_versions(downloaded, cfg, report, origins=origins)
    inputs = build_inputs(name, cfg, osm, versions, windows)
    if not force:
        reason = rebuild_reason(name, inputs, cfg)
        if not reason:
            shutil.rmtree(graph, ignore_errors=True)
            print(json.dumps({"region": name, "action": "reused", "inputs": inputs["digest"]}))
            return {"region": name, "action": "reused"}
        print(f"{name}: rebuild ({reason})")
    downloaded, duplicates, duplicate_sources, dropped = drop_same_feeds(downloaded, cfg, graph, versions)
    report += duplicates
    # 남긴 피드가 뒤에서 빠져도(기간 만료 등) 뺀 피드의 출처가 사라지지 않게 판정 결과를 따로 남긴다.
    with open(os.path.join(graph, "feeds-duplicates.json"), "w", encoding="utf-8") as f:
        json.dump(duplicate_sources, f, ensure_ascii=False, indent=1)
    with open(os.path.join(graph, "feeds-lifecycle.json"), "w", encoding="utf-8") as f:
        json.dump({"versions": versions, "links": lifecycle}, f, ensure_ascii=False, indent=1)
    report += clip_feeds(graph, region, cfg, downloaded, windows, versions)
    for line in report:
        if line.startswith("warn "):
            print(line)
    used_ids = [l[4:].split(":", 1)[0].strip() for l in report if l.startswith("use ")]
    missing = missing_core_feeds(name, used_ids)
    if missing:
        raise SystemExit(f"{name}: core feeds missing before build, current graph kept: {missing}")
    write_configs(graph, region, cfg, used_ids, versions)
    print(f"{name}: feeds used {len(used_ids)}/{len(report)}, bbox {bbox}")
    started = time.monotonic()

    def drop(fid):
        """격리한 피드 대신 그 피드와 같아서 뺀 피드를 다시 넣는다. 깨진 사본이 멀쩡한 사본을 밀어내지 않게 한다."""
        if fid in used_ids:
            used_ids.remove(fid)
        restored = restore_duplicates(graph, region, cfg, fid, duplicate_sources, dropped_sources, windows, versions)
        used_ids.extend(restored)
        report.extend(f"use  {rid}: {summary} restored-for={fid}" for rid, summary in restored.items())
        write_configs(graph, region, cfg, used_ids, versions)

    dropped_sources = dict(dropped)
    core = {fid for fid, _ in downloaded if versions.get(fid, {}).get("feed", fid) in region.get("feeds", [])}
    quarantined, build_peak = build_with_quarantine(graph, region["buildHeap"], drop, core)
    report = quarantine_report(report, quarantined)
    if quarantined:
        with open(os.path.join(graph, "feeds-duplicates.json"), "w", encoding="utf-8") as f:
            json.dump(duplicate_sources, f, ensure_ascii=False, indent=1)
    used = len(used_ids)
    with open(os.path.join(graph, "feeds-report.txt"), "w") as f:
        f.write("\n".join(report) + "\n")
    sources = {fid: src for fid, src in downloaded}
    sources.update(dropped_sources)
    # 격리 뒤 실제로 그래프에 든 피드로 비교해야 핵심 피드가 격리된 그래프를 막는다.
    manifest = feed_manifest(graph, report, sources, cfg, origins, duplicate_sources, versions)
    write_coverage(graph, manifest, cfg, versions)
    regressions = compare_with_current(name, manifest)
    if regressions:
        raise SystemExit(f"{name}: feed regression, current graph kept: {regressions}")
    metrics = {"region": name, "buildSeconds": round(time.monotonic() - started), "peakRssBytes": build_peak,
               "clipPeakRssBytes": CLIP_PEAK[0],
               "graphBytes": os.path.getsize(os.path.join(graph, "graph.obj")), "feedsUsed": used,
               "feedsSeen": sum(1 for l in report if not l.startswith("warn ")), "builtAt": datetime.datetime.now().isoformat(timespec="seconds")}
    smoke = smoke_test(name, region, graph)
    metrics["smoke"] = smoke
    with open(os.path.join(graph, "build-metrics.json"), "w") as f:
        json.dump(metrics, f, indent=2, ensure_ascii=False)
    if smoke["failed"]:
        print(json.dumps(metrics, ensure_ascii=False))
        raise SystemExit(f"{name}: smoke test failed, current graph kept ({smoke['failed']})")
    with open(os.path.join(graph, "inputs.json"), "w", encoding="utf-8") as f:
        json.dump(inputs, f, ensure_ascii=False, indent=1)
    if promote_graph:
        promote(name)
    else:
        print(f"{name}: checked, staging kept at {graph}, current graph unchanged")
    print(json.dumps(metrics, ensure_ascii=False))
    return {"region": name, "action": "built" if promote_graph else "checked", "metrics": metrics}


def collect(name, region, cfg):
    """설정 피드와 카탈로그(MDB·ODPT·Atlas) 피드를 받아 (피드 id, 원본 경로) 목록과 report 줄, 원천 주소를 돌려준다."""
    bbox = region["bbox"]
    report = []
    sources = [(fid, cfg["feeds"][fid].get("url")) for fid in region.get("feeds", [])]
    if region.get("mdb"):
        sources += mdb_feeds(region["mdb"], region.get("subdivisions", []), bbox)
    aliases = cfg.get("sourceHostAliases", {})
    known = mdb_known_sources(region["mdb"], aliases)[0] if region.get("mdb") else set()
    downloaded, origins = [], {}
    store = versions_store()
    for fid, url in sources:
        src = os.path.join(SOURCES, "gtfs", fid + ".gtfs.zip")
        feed = cfg["feeds"].get(fid, {})
        local = os.environ.get(feed.get("pathEnv", ""), "")
        if not local and not url:
            # 신청해서 받는 자료는 공개 주소가 없어, 경로가 없으면 이전 파일로 짓지 않고 빼서 회귀 검사가 교체를 막게 한다.
            report.append(f"skip {fid}: needs {feed.get('pathEnv') or 'url or pathEnv'}")
            continue
        base_days = feed.get("refreshDays", cfg.get("catalogRefreshDays"))
        keep_current(store, fid, src, ORIGINS.get(fid) or {"source": url}, bool(feed.get("allowTripDrop")))
        try:
            base_days = safe_refresh_days(store, fid, base_days)
            if local.startswith("s3://"):
                fetch_s3(local, src)
            elif local:
                copy_replace(local, src)
            else:
                fetch(url, src, max_age_days=base_days)
            check_zip(src)
        except Exception as e:  # 피드 하나가 실패해도 나머지로 그래프를 만든다.
            if local or not usable_copy(src):
                report.append(f"skip {fid}: download {type(e).__name__} {tail(str(e), 120)}")
                continue
            # 공개 피드는 다시 받기에 실패하면 전에 받은 사본으로 짓고, 기간이 지난 운행은 자르기에서 빠진다.
            report.append(f"warn {fid}: refresh failed ({type(e).__name__}), using copy from "
                          f"{datetime.date.fromtimestamp(os.path.getmtime(src))}")
        # 신청 자료는 판마다 파일 해시를 설정에 고정해, 다른 판이 들어오면 짓지 않는다.
        if feed.get("sha256") and sha256(src) != feed["sha256"]:
            report.append(f"skip {fid}: sha256 differs from edition {feed.get('edition')}")
            continue
        if edition_stale(feed):
            report.append(f"warn {fid}: edition {feed['edition']} is older than {feed['editionMaxAgeDays']} days, "
                          "apply for the next edition")
        origins[fid] = local if local.startswith("s3://") else ("local file" if local else url)
        if url:
            known.add(source_key(url, aliases))
        downloaded.append((fid, src))
    if region.get("odpt"):
        extra, skipped = odpt_feeds(bbox, cfg["odpt"]["feeds"], known, aliases)
        downloaded += extra
        report += skipped
    if region.get("atlas"):
        atlas = cfg["atlas"][region["atlas"]]
        extra, skipped = atlas_feeds(bbox, atlas["match"], atlas["country"], known, aliases)
        downloaded += extra
        report += skipped
    return downloaded, report, origins


def keep_current(store, fid, src, origin=None, allow_trip_drop=False):
    """다시 받기 전에 지금 사본을 판으로 남긴다. 새로 받은 파일이 같은 경로를 덮어도 이전 판이 사라지지 않는다.
    판 목록이 깨졌으면 보관만 건너뛴다. 판 정리는 select_versions가 경고로 남긴다."""
    if not usable_copy(src):
        return
    try:
        store.register(fid, src, origin, now=datetime.datetime.fromtimestamp(os.path.getmtime(src)),
                       allow_trip_drop=allow_trip_drop)
    except Exception:
        pass


def safe_refresh_days(store, fid, base_days):
    """판 상태를 못 읽어도 원래 주기로 받는다."""
    try:
        return store.refresh_days(fid, base_days, datetime.date.today())
    except Exception:
        return base_days


def copy_replace(src, dst):
    """판 보관소가 같은 파일을 가리켜도 기존 내용을 건드리지 않도록 새 파일로 바꿔 넣는다."""
    tmp = dst + ".part"
    try:
        shutil.copyfile(src, tmp)
        os.replace(tmp, dst)
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)


def versions_store():
    return feed_versions.FeedVersions(os.path.join(SOURCES, "versions"))


def select_versions(downloaded, cfg, report, today=None, origins=None):
    """받은 피드를 판으로 보관하고 그래프에 실을 판을 고른다.
    돌려주는 것: (OTP에 실을 피드 id, 판 경로) 목록, 피드 id별 싣는 기간, 피드 id별 판 기록, 판 연결 검사 결과."""
    today = today or datetime.date.today()
    store = versions_store()
    rules = cfg.get("nextEdition", [])
    out, windows, versions, links = [], {}, {}, {}
    for fid, src in downloaded:
        feed = cfg["feeds"].get(fid, {})
        origin = dict(ORIGINS.get(fid) or {"source": (origins or {}).get(fid) or feed.get("url"),
                                           "license": feed.get("license"), "official": feed.get("official")})
        try:
            version, created = store.register(fid, src, origin, feed_versions.edition_of(origin.get("source"), rules),
                                              now=datetime.datetime.fromtimestamp(os.path.getmtime(src)),
                                              allow_trip_drop=bool(feed.get("allowTripDrop")))
        except Exception as e:  # 판 목록이 깨져도 받은 사본으로 짓는다. 판 목록은 고치기 전까지 건드리지 않는다.
            report.append(f"warn {fid}: version store unusable ({type(e).__name__}), using downloaded copy")
            out.append((fid, src))
            versions[fid] = {"feed": fid, "sha256": sha256(src), "fetchedAt": None, "serviceFrom": None,
                             "serviceTo": None, "edition": None, "representative": bool(feed.get("representativeCalendar"))}
            continue
        if created and version["problems"]:
            report.append(f"warn {fid}: new version {version['sha256'][:12]} not used ({'; '.join(version['problems'])})")
        if not OFFLINE[0] and not (feed.get("representativeCalendar") or version.get("calendarSuspect")):
            try:
                report += prefetch_next(fid, store, origin.get("source"), rules, today)
            except Exception as e:  # 다음 판을 못 찾아도 지금 판으로 짓는다.
                report.append(f"warn {fid}: next edition check failed ({type(e).__name__})")
        # 대표 시각표·형식상 달력은 운행일로 판을 가를 수 없어 마지막 정상 판을 쓴다.
        if feed.get("representativeCalendar") or version.get("calendarSuspect"):
            chosen = version if not version["problems"] else store.latest_ok(fid)
            plan, link_report = ([(chosen, None, None)] if chosen else []), None
        else:
            try:
                plan, link_report = store.plan(fid, today)
            except Exception as e:  # 판 연결 검사가 실패해도 지금 판으로 짓는다.
                report.append(f"warn {fid}: version link check failed ({type(e).__name__}), using current version only")
                active = store.active(fid, today)
                plan, link_report = ([(active, None, None)] if active else []), None
        if not plan:
            report.append(f"skip {fid}: no usable version ({'; '.join(version['problems']) or 'no service days'})")
            continue
        if plan[0][0]["sha256"] != version["sha256"]:
            report.append(f"warn {fid}: using version {plan[0][0]['sha256'][:12]} from {plan[0][0]['fetchedAt'][:10]} "
                          f"instead of newest download")
        if link_report:
            links[fid] = link_report
            if link_report["decision"] != "link":
                report.append(f"warn {fid}: next version kept separate ({'; '.join(link_report['reasons'])}), "
                              f"no timetable after {plan[0][0]['serviceTo']}")
        for i, (v, start, end) in enumerate(plan):
            vid = fid if i == 0 else f"{fid}-v{v['serviceFrom']}"
            out.append((vid, store.path(fid, v)))
            if start or end:
                windows[vid] = (start, end)
            versions[vid] = {"feed": fid, "sha256": v["sha256"], "fetchedAt": v["fetchedAt"], "edition": v.get("edition"),
                             "serviceFrom": v.get("serviceFrom"), "serviceTo": v.get("serviceTo"),
                             "representative": bool(feed.get("representativeCalendar") or v.get("calendarSuspect"))}
    return out, windows, versions, links


NEXT_CHECKS = "next-checks.json"


def prefetch_next(fid, store, url, rules, today):
    """만료가 가까운 피드의 다음 판을 미리 받는다. 하루에 한 번만 묻고, 실패해도 지금 판은 그대로 쓴다."""
    status = store.status(fid, today)
    if status["state"] not in ("expiring", "expired") or status.get("next"):
        return []
    rule = feed_versions.next_edition_rule(url, rules)
    target = feed_versions.next_edition_url(url, store.active(fid, today), rules)
    if not target:
        return [f"warn {fid}: timetable ends {status['serviceTo']}, no next-edition rule for its source"]
    checks_path = os.path.join(store.root, NEXT_CHECKS)
    try:
        with open(checks_path) as f:
            checks = json.load(f)
    except (OSError, ValueError):
        checks = {}
    if time.time() - checks.get(fid, {}).get("at", 0) < feed_versions.EXPIRING_REFRESH_DAYS * 86400:
        return [f"warn {fid}: timetable ends {status['serviceTo']}, next edition checked {checks[fid]['result']}"]
    token = os.environ.get(rule.get("tokenEnv", ""), "") if rule.get("tokenEnv") else ""
    if rule.get("tokenEnv") and not token:
        return [f"warn {fid}: timetable ends {status['serviceTo']}, next edition needs {rule['tokenEnv']}"]
    fetch_url = target + (f"&{rule['tokenParam']}={token}" if token else "")
    incoming = os.path.join(store.root, ".incoming", safe_id(fid, 120) + ".zip")
    os.makedirs(os.path.dirname(incoming), exist_ok=True)
    try:
        fetch(fetch_url, incoming, refresh=True, timeout=FEED_TIMEOUT_SECONDS, retries=1)
        version, _ = store.register(fid, incoming, {"source": target}, feed_versions.edition_of(target, rules))
        result = "found " + (version.get("serviceFrom") or "?") + ("" if not version["problems"] else " with problems")
    except Exception as e:  # 다음 판이 아직 없으면 404나 오류 문서가 온다. 주소에 토큰이 있을 수 있어 문구는 남기지 않는다.
        result = "not published (" + type(e).__name__ + ")"
    finally:
        if os.path.exists(incoming):
            os.remove(incoming)
    checks[fid] = {"at": time.time(), "result": result, "url": target}
    feed_versions.write_json(checks_path, checks)
    return [f"warn {fid}: timetable ends {status['serviceTo']}, next edition {result}"]

ORIGINS = {}
REQUIRED_FILES = ("agency.txt", "stops.txt", "routes.txt", "trips.txt", "stop_times.txt")
# 경로·운행일·환승·요금 계산에 들어가지 않는 파일. 발행처·라이선스(feed_info·attributions)는 따로 보존한다.
NON_ROUTING_FILES = {"feed_info.txt", "translations.txt", "attributions.txt"}


def feed_fingerprint(path):
    """경로 계산에 들어가는 모든 GTFS 파일의 이름·CRC·크기. 서비스 조건까지 같아야 같은 피드다(압축을 풀지 않는다)."""
    z = feed_zip(path)
    infos = {i.filename[len(z.prefix):]: i for i in z.zip.infolist()
             if i.filename.startswith(z.prefix) and not i.is_dir() and "/" not in i.filename[len(z.prefix):]}
    if not all(n in infos for n in REQUIRED_FILES) or not ({"calendar.txt", "calendar_dates.txt"} & infos.keys()):
        return None
    return tuple(sorted((n, i.CRC, i.file_size) for n, i in infos.items()
                        if (n.endswith(".txt") or n.endswith(".geojson")) and n not in NON_ROUTING_FILES))


def routing_files(z):
    return sorted(n for n in z.namelist() if (n.endswith(".txt") or n.endswith(".geojson")) and n not in NON_ROUTING_FILES)


def same_routing_content(a, b):
    """CRC·크기 후보를 파일 내용 SHA-256으로 최종 확인한다. CRC32는 다른 내용이 같은 값을 가질 수 있다."""
    za, zb = feed_zip(a), feed_zip(b)
    names = routing_files(za)
    if names != routing_files(zb):
        return False
    for n in names:
        digests = []
        for z in (za, zb):
            h = hashlib.sha256()
            with z.open(n) as f:
                for chunk in iter(lambda: f.read(1 << 20), b""):
                    h.update(chunk)
            digests.append(h.hexdigest())
        if digests[0] != digests[1]:
            return False
    return True


METADATA_FILES = ("feed_info.txt", "translations.txt", "attributions.txt")


def keep_metadata(graph, fid, src):
    """뺀 피드의 발행처·라이선스·다국어 이름 파일을 그래프 폴더에 남긴다. 경로 계산 파일만 같고 이 파일들은 다를 수 있다."""
    z = feed_zip(src)
    kept = []
    for n in METADATA_FILES:
        if n in z.namelist():
            target = os.path.join(graph, "duplicates-meta", fid, n)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with z.open(n) as f, open(target, "wb") as out:
                shutil.copyfileobj(f, out)
            kept.append(os.path.relpath(target, graph))
    return kept


def provenance(fid, src, cfg):
    """뺀 피드도 원천·운영기관·발행처·라이선스를 남는 피드의 피드 목록에 기록한다."""
    z = feed_zip(src)
    read = lambda n: list(csv.DictReader(io.TextIOWrapper(z.open(n), "utf-8-sig", errors="replace"))) \
        if n in z.namelist() else []
    info = next(iter(read("feed_info.txt")), {})
    origin = ORIGINS.get(fid) or {"source": cfg["feeds"].get(fid, {}).get("url") or fid}
    return dict(origin, feedId=fid, agencies=sorted({a.get("agency_name", "") for a in read("agency.txt")}),
                publisher=info.get("feed_publisher_name"), publisherUrl=info.get("feed_publisher_url"),
                attributions=[a.get("organization_name") for a in read("attributions.txt")])


# 운행 지문을 비교할 후보: 정류장(이름·좌표)이 작은 쪽 기준 이만큼 겹치는 피드 쌍.
CONTAIN_STOP_OVERLAP = 0.9
# 운행 지문이 이만큼 겹치면 같은 피드일 수 있어 기록만 하고 둘 다 싣는다.
SIMILAR_CANDIDATE = 0.95


def drop_same_feeds(downloaded, cfg, graph=None, versions=None):
    """같은 운행이 두 번 실리지 않게 피드를 뺀다. 먼저 온 피드(설정 → MDB → ODPT → Atlas 순)를 남긴다.

    1. 경로 계산 파일이 SHA-256까지 모두 같으면 뺀다.
    2. 운행 지문(실제 운행일·정류장 순서·시각·승하차 제한·노선)이 다른 피드에 모두 들어 있으면 뺀다.
       남는 피드가 품질 문제(형식상 달력·운행 없음)가 있거나 공식 피드를 비공식 피드로 바꾸게 되면 빼지 않는다.
    3. 일부만 겹치거나 SIMILAR_CANDIDATE 이상 비슷하면 후보로 기록만 한다. 운영기관이 같다는 것만으로는 빼지 않는다.
    돌려주는 것: 남긴 피드, report 줄, 남은 피드 id별 뺀 피드 출처, 뺀 피드 (id, 경로)."""
    seen, kept, report, dropped, removed = {}, [], [], {}, []

    def drop(fid, src, first, line):
        report.append(line)
        try:
            entry = provenance(fid, src, cfg)
            if graph:
                entry["metadataFiles"] = keep_metadata(graph, fid, src)
        except Exception as e:
            entry = {"feedId": fid, "provenanceError": type(e).__name__}
        dropped.setdefault(first, []).append(entry)
        removed.append((fid, src))

    for fid, src in downloaded:
        try:
            key = feed_fingerprint(src)
        except Exception:
            key = None
        if key and key in seen:
            first, first_src = seen[key]
            if not same_routing_content(src, first_src):
                report.append(f"warn {fid}: same CRC and size as {first} but different content, kept both")
                kept.append((fid, src))
                continue
            drop(fid, src, first, f"dup  {fid}: same routing files as {first} (sha256)")
            continue
        if key:
            seen[key] = (fid, src)
        kept.append((fid, src))
    kept, contained = drop_contained(kept, cfg, versions)
    for fid, src, first, line in contained:
        drop(fid, src, first, line)
    report += [line for line in SIMILAR_REPORT]
    return kept, report, dropped, removed


SIMILAR_REPORT = []


def drop_contained(feeds, cfg, versions=None):
    """운행이 다른 피드에 모두 들어 있는 피드를 고른다. 정류장이 크게 겹치는 쌍만 운행 지문을 만든다."""
    SIMILAR_REPORT.clear()
    stops, prints = {}, {}
    for fid, src in feeds:
        try:
            z = feed_zip(src)
            stops[fid] = set(feed_compare.stop_keys(z).values()) if feed_compare.comparable(z) else None
        except Exception:
            stops[fid] = None
    order = [fid for fid, _ in feeds]
    path = dict(feeds)
    gone, out = set(), []

    def fingerprint(fid):
        if fid not in prints:
            try:
                prints[fid] = feed_compare.Fingerprint(feed_zip(path[fid]))
            except Exception:
                prints[fid] = None
        return prints[fid]

    for i, a in enumerate(order):
        for b in order:
            if a == b or b in gone or a in gone or not stops.get(a) or not stops.get(b):
                continue
            if feed_compare.stop_overlap(stops[a], stops[b]) < CONTAIN_STOP_OVERLAP:
                continue
            fa, fb = fingerprint(a), fingerprint(b)
            if not fa or not fb:
                continue
            # 서로 포함(같은 운행)이면 먼저 온 피드를 남긴다.
            if feed_compare.contained(fa, fb) and not (feed_compare.contained(fb, fa) and order.index(b) > i):
                problem = keeper_problem(a, b, path, cfg, versions)
                if problem:
                    SIMILAR_REPORT.append(f"warn {a}: service contained in {b} but kept ({problem})")
                    continue
                gone.add(a)
                out.append((a, path[a], b, f"dup  {a}: all {len(fa.running())} trips contained in {b} (service fingerprint)"))
                break
            if order.index(b) > i:
                ratio = feed_compare.similarity(fa, fb)
                if ratio >= SIMILAR_CANDIDATE:
                    SIMILAR_REPORT.append(f"warn {a}: {ratio:.1%} service overlap with {b}, candidate only, kept both")
    return [(fid, src) for fid, src in feeds if fid not in gone], out


# 운행 지문에 들지 않지만 경로·요금 계산에 쓰이는 파일. 뺄 피드에만 있으면 남길 피드가 대신하지 못한다.
EXTRA_ROUTING_FILES = ("transfers.txt", "pathways.txt", "fare_attributes.txt", "fare_rules.txt", "fare_products.txt",
                       "fare_leg_rules.txt", "fare_transfer_rules.txt")


def official(fid, cfg):
    return ORIGINS.get(fid, {}).get("official") or cfg["feeds"].get(fid, {}).get("official")


def keeper_problem(fid, keeper, path, cfg, versions=None):
    """남길 피드가 뺄 피드를 대신할 수 없는 이유. 품질 문제가 있는 피드가 정상 피드를 밀어내지 않게 한다.
    이어 실은 판(<피드>-v<시작일>)은 원래 피드의 설정·공식 여부로 판정한다."""
    base = lambda x: (versions or {}).get(x, {}).get("feed", x)
    if base(fid) in cfg["feeds"]:
        return f"{fid} is a configured feed"
    keeper_cfg = cfg["feeds"].get(base(keeper), {})
    # 남길 피드는 자르기에서 노선을 더 뺄 수 있어, 원본 기준 포함이 실린 결과의 포함이 아니다.
    if any(keeper_cfg.get(k) for k in ("dropRouteNameRegex", "dropRouteTypes", "routeTypeMap")):
        return f"{keeper} has clip rules"
    try:
        kz, fz = feed_zip(path[keeper]), feed_zip(path[fid])
        prof = feed_compare.profile(kz)
    except Exception as e:
        return f"{keeper} unreadable {type(e).__name__}"
    if prof["calendarSuspect"] or keeper_cfg.get("representativeCalendar"):
        return f"{keeper} has no confirmed calendar"
    if official(base(fid), cfg) and not official(base(keeper), cfg):
        return f"{fid} is official, {keeper} is not"
    only = sorted(set(EXTRA_ROUTING_FILES) & set(fz.namelist()) - set(kz.namelist()))
    if only:
        return f"only {fid} has {', '.join(only)}"
    return None


def restore_duplicates(graph, region, cfg, fid, duplicate_sources, dropped_sources, windows, versions):
    """격리한 피드 때문에 뺀 피드를 잘라 다시 싣는다. {피드 id: 자르기 요약}"""
    restored = {}
    for entry in duplicate_sources.pop(fid, []):
        rid = entry.get("feedId")
        src = dropped_sources.get(rid)
        if not src:
            continue
        feed = cfg["feeds"].get(versions.get(rid, {}).get("feed", rid))
        result, _ = clip(src, os.path.join(graph, rid + ".gtfs.zip"), region["bbox"], feed,
                         narrow(CORE_WINDOW[0], windows.get(rid)))
        if result:
            restored[rid] = result
    return restored


CORE_WINDOW = [None]


def default_window(today=None):
    today = today or datetime.date.today()
    return ((today - datetime.timedelta(days=PAST_DAYS)).strftime("%Y%m%d"),
            (today + datetime.timedelta(days=FUTURE_DAYS)).strftime("%Y%m%d"))


def narrow(window, version_window):
    """핵심 피드 기간과 판 기간이 함께 걸리는 기간. 둘 다 없으면 None(자르기 기본 기간)."""
    if not version_window:
        return window
    start, end = window or default_window()
    vs, ve = version_window
    return max(start, vs or start), min(end, ve or end)


def clip_feeds(graph, region, cfg, downloaded, windows=None, versions=None):
    """피드를 잘라 그래프 폴더에 두고 report 줄을 돌려준다.
    실제 달력이 있는 핵심 피드를 먼저 자르고, 나머지 피드는 핵심 피드가 모두 운행하는 기간으로 달력까지 자른다.
    핵심 피드(전국 철도 등)가 없는 날짜를 다른 피드가 기간 안으로 만들면 철도가 빠진 경로가 확정 시간표로 나간다.
    판을 이어 실은 피드는 windows의 판별 기간으로도 자른다."""
    windows, versions = windows or {}, versions or {}
    bbox = region["bbox"]
    report = []
    source = lambda fid: versions.get(fid, {}).get("feed", fid)
    settings = lambda fid: cfg["feeds"].get(source(fid))
    representative = lambda fid: (settings(fid) or {}).get("representativeCalendar")
    core = [(fid, src) for fid, src in downloaded if source(fid) in region.get("feeds", []) and not representative(fid)]
    clipped = {}
    for fid, src in core:
        clipped[fid], reason = clip(src, os.path.join(graph, fid + ".gtfs.zip"), bbox, settings(fid), windows.get(fid))
        report.append(f"use  {fid}: {clipped[fid]}" if clipped[fid] else f"skip {fid}: {reason}")
    window = calendar_window(merge_versions({fid: clipped.get(fid) for fid, _ in core}, source))
    CORE_WINDOW[0] = window
    for fid, src in [x for x in downloaded if x not in core]:
        if not window and representative(fid):
            # 기간 없이 실으면 형식상 달력이 그래프 운행 기간을 실제 시간표보다 길게 만든다.
            report.append(f"skip {fid}: no calendar window from core feeds")
            continue
        w = narrow(window, windows.get(fid))
        result, reason = clip(src, os.path.join(graph, fid + ".gtfs.zip"), bbox, settings(fid), w)
        tag = f" calendar_window={w[0]}-{w[1]}" if w else ""
        report.append(f"use  {fid}: {result}{tag}" if result else f"skip {fid}: {reason}")
    return report


def merge_versions(results, source):
    """같은 피드의 이어 실은 판들은 운행 기간을 하나로 합친다. 판끼리 겹치는 기간을 구하면 비어 버린다.
    판 사이에 빈 날이 있으면 첫 판 구간까지만 쓴다. 공백까지 덮으면 핵심 피드가 없는 날을 다른 피드가 채운다."""
    spans = {}
    for fid, result in results.items():
        m = re.search(r"service_days=(\d{8})-(\d{8})", result or "")
        if m:
            spans.setdefault(source(fid), []).append(m.groups())
    out = []
    for parts in spans.values():
        parts.sort()
        start, end = parts[0]
        for s, e in parts[1:]:
            day_after = (datetime.date(int(end[:4]), int(end[4:6]), int(end[6:])) + datetime.timedelta(days=1)).strftime("%Y%m%d")
            if s > day_after:
                break
            end = max(end, e)
        out.append(f"service_days={start}-{end}")
    return out


def missing_core_feeds(name, used_ids):
    """지금 그래프에 있는 핵심 피드가 이번에 빠졌으면 오래 걸리는 OTP 빌드 전에 멈춘다."""
    current = os.path.join(ROOT, name, "graph", "feeds-manifest.json")
    if not os.path.exists(current):
        return []
    old = json.load(open(current, encoding="utf-8"))
    return [fid for fid in regions()[name].get("feeds", []) if old.get(fid, {}).get("trips") and fid not in used_ids]


def build_lock():
    os.makedirs(ROOT, exist_ok=True)
    lock = open(os.path.join(ROOT, ".build.lock"), "w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise SystemExit("another otp_region build or rollback is running")
    return lock


def calendar_window(results):
    """실제 달력이 있는 핵심 피드가 모두 운행하는 기간. 대표 시각표 피드는 이 기간 안으로만 싣는다."""
    spans = [re.search(r"service_days=(\d{8})-(\d{8})", r) for r in results if r]
    # 형식상 달력(수년~수십 년)이 섞이면 기간이 사실상 무제한이 되어 자르기가 꺼진다.
    spans = [m.groups() for m in spans if m and int(m.group(2)[:4]) - int(m.group(1)[:4]) <= CALENDAR_SPAN_SUSPECT_YEARS]
    if not spans:
        return None
    start, end = max(s for s, _ in spans), min(e for _, e in spans)
    return (start, end) if start <= end else None


def write_configs(graph, region, cfg, used_ids, versions=None):
    """피드 id를 정해 두어야 실시간 갱신기가 어느 시간표에 붙는지 맞출 수 있다."""
    # 대소문자만 다른 id는 대소문자를 가리지 않는 파일 시스템에서 같은 파일이 된다.
    ids = [feed_id(fid).casefold() for fid in used_ids]
    duplicates = sorted({i for i in ids if ids.count(i) > 1})
    if duplicates:
        raise SystemExit(f"duplicate OTP feed ids: {duplicates}")
    feeds = [{"type": "gtfs", "source": pathlib.Path(graph, fid + ".gtfs.zip").as_uri(), "feedId": feed_id(fid)}
             for fid in used_ids]
    with open(os.path.join(graph, "build-config.json"), "w") as f:
        # 운영사 시간대가 피드마다 다르면 GtfsModule이 모든 피드를 읽은 뒤 실패한다. 지역 시간대로 고정한다.
        json.dump({"osmDefaults": {"timeZone": region["timezone"]}, "transitModelTimeZone": region["timezone"],
                   "transitServiceStart": f"-P{PAST_DAYS}D", "transitServiceEnd": f"P{FUTURE_DAYS}D",
                   "transitFeeds": feeds}, f, indent=2)
    # 이어 실은 다음 판도 같은 실시간 피드를 받아야 판이 바뀐 뒤에도 실시간이 끊기지 않는다.
    base = lambda fid: (versions or {}).get(fid, {}).get("feed", fid)
    updaters = [{"type": "stop-time-updater", "frequency": cfg["feeds"][base(fid)]["realtime"].get("frequency", "30s"),
                 "url": cfg["feeds"][base(fid)]["realtime"]["url"], "feedId": feed_id(fid)}
                for fid in used_ids if cfg["feeds"].get(base(fid), {}).get("realtime")]
    with open(os.path.join(graph, "router-config.json"), "w") as f:
        json.dump({"server": {"apiProcessingTimeout": "10s"}, "updaters": updaters}, f, indent=2)


def quarantine_report(report, quarantined):
    """격리한 피드의 use 줄을 quarantined로 바꿔 피드 목록·커버리지 표가 쓰지 않은 피드를 세지 않게 한다."""
    gone = set(quarantined)
    out = []
    for line in report:
        fid = line[4:].split(":", 1)[0].strip() if line.startswith("use ") else None
        out.append(f"quarantined {fid}: OTP build error" if fid in gone else line)
    return out


SMOKE_QUERY = """query($o: PlanLabeledLocationInput!, $d: PlanLabeledLocationInput!, $t: OffsetDateTime!) {
  planConnection(origin: $o, destination: $d, dateTime: {earliestDeparture: $t}, first: 3) {
    edges { node { legs { mode transitLeg route { shortName longName } } } } } }"""
SMOKE_PORT_OFFSET = 1000
SMOKE_START_SECONDS = 600


def smoke_test(name, region, graph):
    """임시 포트로 새 그래프를 띄워 지역에 적은 대표 구간이 대중교통으로 나오는지 본다. 하나라도 안 나오면 교체하지 않는다."""
    cases = region.get("smoke", [])
    if not cases:
        return {"cases": 0, "failed": []}
    port = region["port"] + SMOKE_PORT_OFFSET
    log = open(os.path.join(graph, "smoke-serve.log"), "w")
    server = subprocess.Popen(["java", f"-Xmx{region['heap']}", "-jar", OTP_JAR, "--load", "--bindAddress", "127.0.0.1",
                               "--port", str(port), graph], stdout=log, stderr=subprocess.STDOUT)
    try:
        if not wait_started(log.name, server):
            return {"cases": len(cases), "failed": ["server did not start"]}
        return {"cases": len(cases), "failed": smoke_cases(region, port)}
    finally:
        server.terminate()
        try:
            server.wait(timeout=60)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait()
        log.close()


def wait_started(log_path, server, seconds=SMOKE_START_SECONDS):
    deadline = time.monotonic() + seconds
    while "Grizzly server running" not in open(log_path, encoding="utf-8", errors="replace").read():
        if server.poll() is not None or time.monotonic() > deadline:
            return False
        time.sleep(2)
    return True


def smoke_cases(region, port):
    """지역 대표 구간을 port의 OTP 서버에 묻고 실패한 구간을 돌려준다."""
    failed = []
    day = datetime.date.fromisoformat(next_weekday())
    zone = zoneinfo.ZoneInfo(region["timezone"])
    for c in region.get("smoke", []):
        hour, minute = map(int, c["time"].split(":"))
        t = datetime.datetime.combine(day, datetime.time(hour, minute), zone).isoformat()
        body = {"query": SMOKE_QUERY, "variables": {"o": smoke_place(c, "from"), "d": smoke_place(c, "to"), "t": t}}
        req = urllib.request.Request(f"http://127.0.0.1:{port}/otp/gtfs/v1", json.dumps(body).encode(),
                                     {"Content-Type": "application/json"})
        try:
            edges = json.load(urllib.request.urlopen(req, timeout=60))["data"]["planConnection"]["edges"]
        except Exception as e:  # 검사 질의 실패도 검사 실패로 센다.
            failed.append(f"{c['name']}: {type(e).__name__}")
            continue
        legs = [l for e in edges for l in e["node"]["legs"] if l["transitLeg"]]
        if not legs:
            failed.append(f"{c['name']}: no transit itinerary")
        elif c.get("expectRoute") and not any(re.search(c["expectRoute"], " ".join(
                filter(None, ((l.get("route") or {}).get("shortName"), (l.get("route") or {}).get("longName")))))
                for l in legs):
            failed.append(f"{c['name']}: no route matching {c['expectRoute']}")
    return failed


def smoke_place(case, end):
    """역→역 그래프는 길이 없어 좌표 대신 정류장 id(피드id:정류장id)로 묻는다."""
    stop = case.get(end + "Stop")
    if stop:
        return {"location": {"stopLocation": {"stopLocationId": stop}}}
    return {"location": {"coordinate": {"latitude": case[end][0], "longitude": case[end][1]}}}


def next_weekday():
    d = datetime.date.today() + datetime.timedelta(days=1)
    while d.weekday() >= 5:
        d += datetime.timedelta(days=1)
    return d.isoformat()


def promote(name):
    """staging → graph, 기존 graph → previous. 그 전 previous는 지우지 않고 archive/<시각>으로 옮긴다."""
    base = os.path.join(ROOT, name)
    previous, current, staging = (os.path.join(base, d) for d in ("previous", "graph", "staging"))
    if os.path.exists(previous):
        archive = os.path.join(base, "archive", datetime.datetime.now().strftime("%Y%m%dT%H%M%S"))
        os.makedirs(os.path.dirname(archive), exist_ok=True)
        os.rename(previous, archive)
        prune_archive(os.path.dirname(archive), config().get("archiveKeep"))
    if os.path.exists(current):
        os.rename(current, previous)
    os.rename(staging, current)
    release(name)
    restart_notice(name)


def prune_archive(folder, keep):
    """archiveKeep을 설정한 경우에만 오래된 보관본을 지운다. 설정이 없으면 모두 남긴다."""
    if not keep:
        return []
    old = sorted(os.listdir(folder))[:-keep]
    for name in old:
        shutil.rmtree(os.path.join(folder, name))
    return old


def rollback(name):
    lock = build_lock()
    base = os.path.join(ROOT, name)
    previous, current = os.path.join(base, "previous"), os.path.join(base, "graph")
    if not os.path.exists(previous):
        sys.exit(f"{name}: no previous graph")
    # 교체가 중간에 끊겨 graph가 없으면 이전 그래프만 되살린다.
    if not os.path.exists(current):
        os.rename(previous, current)
        print(f"{name}: restored previous graph")
        return
    swap = os.path.join(base, "swap")
    os.rename(current, swap)
    os.rename(previous, current)
    os.rename(swap, previous)
    hold(name, "rollback")
    print(f"{name}: rolled back, refresh holds this region until build or release")
    restart_notice(name)


HOLD = "hold.json"


def hold(name, reason):
    """되돌린 지역을 정기 갱신이 같은 입력으로 다시 지어 되돌리기 전 그래프로 바꾸지 않게 한다."""
    feed_versions.write_json(os.path.join(ROOT, name, HOLD), {"reason": reason,
                                                           "at": datetime.datetime.now().isoformat(timespec="seconds")})


def held(name):
    path = os.path.join(ROOT, name, HOLD)
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def release(name):
    path = os.path.join(ROOT, name, HOLD)
    if os.path.exists(path):
        os.remove(path)


def restart_notice(name):
    """교체·롤백은 파일만 바꾼다. OTP는 기동할 때만 그래프를 읽어 서버를 다시 띄워야 새 그래프를 쓴다."""
    port = regions()[name].get("port")
    if port and listening_pids(port):
        print(f"{name}: OTP on port {port} still serves the old graph, run: otp_region.py restart {name}")


def feed_id(fid):
    """OTP 피드 id는 정류장 id 앞에 붙어 ':'와 공백을 쓸 수 없다."""
    return safe_id(fid, 60, "A-Za-z0-9_-")


def safe_id(raw, limit, allowed="A-Za-z0-9_.-"):
    """바꾼 글자가 있거나 잘리면 원래 id 해시를 붙인다. 일본어 이름은 모두 '_'가 되어 서로 다른 피드가 같은 id가 된다."""
    safe = re.sub(f"[^{allowed}]", "_", raw)
    if safe == raw and len(safe) <= limit:
        return safe
    return safe[:limit - 9] + "-" + hashlib.sha1(raw.encode("utf-8")).hexdigest()[:8]


def feed_manifest(graph, report, sources, cfg, origins=None, duplicates=None, versions=None):
    """피드마다 원천·받은 시각·해시·feed_info 기간·실제 운행일 범위·운행 수를 남긴다. 오래된 피드를 찾는 근거다."""
    entries = {}
    for line in report:
        if not line.startswith("use "):
            continue
        fid, stats = line[4:].split(": ", 1)
        fid = fid.strip()
        fields = dict(x.split("=", 1) for x in stats.split() if "=" in x)
        src = sources.get(fid)
        info = {}
        if src:
            try:
                z = feed_zip(src)
                if "feed_info.txt" in z.namelist():
                    info = next(csv.DictReader(io.TextIOWrapper(z.open("feed_info.txt"), "utf-8-sig")), {}) or {}
            except (zipfile.BadZipFile, OSError, ValueError):
                pass
        version = (versions or {}).get(fid, {})
        base = version.get("feed", fid)
        span = fields.get("service_days", "none")
        suspect = "-" in span and int(span.split("-")[1][:4]) - int(span.split("-")[0][:4]) > CALENDAR_SPAN_SUSPECT_YEARS
        entries[fid] = {
            # 원천 주소(운영기관·카탈로그 직접 주소)와 실제로 받은 주소(카탈로그 사본 등)를 나눈다. 다음 판은 원천 주소로 묻는다.
            "source": ORIGINS.get(base, {}).get("source") or (origins or {}).get(base) or cfg["feeds"].get(base, {}).get("url") or fid,
            "downloadedFrom": (origins or {}).get(base),
            "catalog": ORIGINS.get(base, {}).get("catalog"),
            "license": ORIGINS.get(base, {}).get("license"),
            "sameFeedsDropped": (duplicates or {}).get(fid, []),
            "otpFeedId": feed_id(fid),
            "downloadedAt": version.get("fetchedAt") or (datetime.datetime.fromtimestamp(os.path.getmtime(src))
                                                         .isoformat(timespec="seconds") if src else None),
            "sha256": version.get("sha256") or (sha256(src) if src else None),
            "feedStart": info.get("feed_start_date") or None, "feedEnd": info.get("feed_end_date") or None,
            "feedVersion": info.get("feed_version") or None,
            "serviceFrom": span.split("-")[0] if "-" in span else None,
            "serviceTo": span.split("-")[1] if "-" in span else None,
            "trips": int(fields.get("trips", 0)),
            # 운영 시간표 달력은 보통 1년 안팎이다. 수년~수십 년짜리는 분석용·형식상 달력이라 운행일을 확정하지 못한다.
            "calendarSuspect": suspect,
            "basis": cfg["feeds"].get(base, {}).get("basis"),
            "edition": cfg["feeds"].get(base, {}).get("edition"),
            "loadedFrom": fields["calendar_window"].split("-")[0] if "calendar_window" in fields else None,
            "loadedTo": fields["calendar_window"].split("-")[1] if "calendar_window" in fields else None,
            "editionStale": edition_stale(cfg["feeds"].get(base, {})),
            "official": ORIGINS.get(base, {}).get("official"),
            "realtimeFeeds": ORIGINS.get(base, {}).get("realtime"),
            "replaces": ORIGINS.get(base, {}).get("replaces"),
            "versionOf": base if base != fid else None,
            "versionServiceFrom": version.get("serviceFrom"),
            "versionServiceTo": version.get("serviceTo"),
        }
    with open(os.path.join(graph, "feeds-manifest.json"), "w", encoding="utf-8") as f:
        json.dump(entries, f, ensure_ascii=False, indent=1)
    return entries


# GTFS route_type → 경로 구간 수단(LegMode). 확장 코드는 백 단위 묶음으로 본다.
ROUTE_TYPE_MODES = {0: "TRAM", 1: "SUBWAY", 2: "RAIL", 3: "BUS", 4: "FERRY", 5: "CABLE", 6: "CABLE", 7: "CABLE",
                    11: "BUS", 12: "RAIL"}
ROUTE_TYPE_GROUPS = {1: "RAIL", 2: "BUS", 4: "SUBWAY", 7: "BUS", 8: "BUS", 9: "TRAM", 10: "FERRY", 11: "AIR",
                     12: "FERRY", 13: "CABLE", 14: "CABLE"}


def route_mode(route_type):
    try:
        t = int(route_type)
    except (TypeError, ValueError):
        return "OTHER"
    return ROUTE_TYPE_MODES.get(t) or (ROUTE_TYPE_GROUPS.get(t // 100) if t >= 100 else None) or "OTHER"


def write_coverage(graph, manifest, cfg, versions):
    """경로 엔진이 날짜·위치마다 시간표가 있는지 가리는 피드별 범위. 피드가 끝난 날에는 그 지역·수단 신뢰도를 낮춘다.
    대표 시각표·형식상 달력은 운행일을 확정하지 않아 넣지 않는다(미검증으로 따로 표시된다)."""
    groups = {}
    for fid, entry in manifest.items():
        base = entry.get("versionOf") or fid
        if entry.get("calendarSuspect") or cfg["feeds"].get(base, {}).get("representativeCalendar") \
                or versions.get(fid, {}).get("representative") or not entry.get("serviceFrom"):
            continue
        path = os.path.join(graph, fid + ".gtfs.zip")
        if not os.path.exists(path):
            continue
        z = feed_zip(path)
        lats, lngs = [], []
        for _, lat, lng in feed_compare.stop_keys(z).values():
            lats.append(lat)
            lngs.append(lng)
        modes = {route_mode(r.get("route_type")) for r in feed_compare.rows(z, "routes.txt")}
        start = max(filter(None, (entry["serviceFrom"], entry.get("loadedFrom"))))
        end = min(filter(None, (entry["serviceTo"], entry.get("loadedTo"))))
        g = groups.setdefault(base, {"feed": base, "otpFeedIds": [], "modes": set(), "bbox": None, "periods": []})
        g["otpFeedIds"].append(entry["otpFeedId"])
        g["modes"] |= modes
        if lats:
            box = [min(lats), min(lngs), max(lats), max(lngs)]
            g["bbox"] = box if not g["bbox"] else [min(g["bbox"][0], box[0]), min(g["bbox"][1], box[1]),
                                                     max(g["bbox"][2], box[2]), max(g["bbox"][3], box[3])]
        g["periods"].append([start, end])
    feeds = [dict(g, modes=sorted(g["modes"]), periods=sorted(g["periods"])) for g in groups.values() if g["bbox"]]
    with open(os.path.join(graph, "feeds-coverage.json"), "w", encoding="utf-8") as f:
        json.dump({"builtAt": datetime.datetime.now().isoformat(timespec="seconds"), "feeds": feeds}, f,
                  ensure_ascii=False, indent=1)
    return feeds


# 그래프 결과에 영향을 주는 파이프라인 동작을 바꾸면 올린다. 입력이 같아도 모든 지역을 다시 짓게 한다.
GRAPH_INPUTS_VERSION = 1
# 입력이 그대로여도 이보다 오래된 그래프는 다시 지어 싣는 기간(오늘-7일 ~ 1년 뒤)을 앞으로 민다.
GRAPH_MAX_AGE_DAYS = 30


def build_inputs(name, cfg, osm, versions, windows):
    """그래프를 다시 지을지 정하는 입력 목록. 피드 판 해시·싣는 기간·OSM·지역 설정·피드 설정·OTP 판이다."""
    region = cfg["regions"][name]
    feeds = {fid: v["sha256"] for fid, v in sorted(versions.items())}
    settings = {v["feed"]: cfg["feeds"][v["feed"]] for v in versions.values() if v["feed"] in cfg["feeds"]}
    stat = os.stat(osm) if osm else None
    body = {"format": GRAPH_INPUTS_VERSION, "otp": OTP_VERSION, "region": region, "feedSettings": settings,
            "osm": {"size": stat.st_size, "mtime": int(stat.st_mtime)} if stat else None,
            "feeds": feeds, "windows": {k: list(v) for k, v in sorted(windows.items())}}
    body["digest"] = hashlib.sha256(json.dumps(body, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
    return body


def rebuild_reason(name, inputs, cfg, today=None):
    """지금 그래프를 다시 지어야 하는 이유. 다시 지을 필요가 없으면 None."""
    current = os.path.join(ROOT, name, "graph")
    try:
        with open(os.path.join(current, "inputs.json"), encoding="utf-8") as f:
            old = json.load(f)
    except (OSError, ValueError):
        return "no inputs record for current graph"
    if old.get("digest") == inputs["digest"]:
        built = datetime.datetime.fromtimestamp(os.path.getmtime(os.path.join(current, "inputs.json"))).date()
        age = ((today or datetime.date.today()) - built).days
        limit = cfg.get("graphMaxAgeDays", GRAPH_MAX_AGE_DAYS)
        return f"graph is {age} days old (limit {limit})" if age > limit else None
    reasons = []
    for key in ("format", "otp", "region", "feedSettings", "osm", "windows"):
        if old.get(key) != inputs.get(key):
            reasons.append(f"{key} changed")
    a, b = old.get("feeds", {}), inputs["feeds"]
    added, removed = sorted(b.keys() - a.keys()), sorted(a.keys() - b.keys())
    changed = sorted(f for f in a.keys() & b.keys() if a[f] != b[f])
    for label, ids in (("new feeds", added), ("removed feeds", removed), ("new versions", changed)):
        if ids:
            reasons.append(f"{label} {len(ids)} ({', '.join(ids[:3])}{'…' if len(ids) > 3 else ''})")
    return "; ".join(reasons) or "inputs changed"


def baseline(name):
    """입력 기록(inputs.json)이 없는 지금 그래프의 기준선을 만든다. 첫 refresh가 모든 지역을 다시 짓지 않게 한다.
    내려받지 않고 받은 사본으로 지금 파이프라인의 수집·판 선택·중복 제외·자르기를 다시 돌려, 그래프에 든 피드와
    피드별 자르기 결과(정류장·운행·stop_times·서비스·운행일 수)·원본 해시가 모두 같을 때만 기록한다.
    하나라도 다르면 기록하지 않아 다음 refresh가 그 지역을 다시 짓는다. 호출부가 빌드 잠금을 잡는다."""
    region = regions()[name]
    current = os.path.join(ROOT, name, "graph")
    if not os.path.exists(os.path.join(current, "graph.obj")):
        return {"region": name, "action": "no graph"}
    if os.path.exists(os.path.join(current, "inputs.json")):
        return {"region": name, "action": "exists"}
    cfg = config()
    work = os.path.join(ROOT, name, "baseline-check")
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(work)
    OFFLINE[0] = True
    try:
        osm = os.path.join(SOURCES, "osm", name + ".osm.pbf") if region.get("osm") or region.get("osmUrl") else None
        if osm and not os.path.exists(osm):
            return {"region": name, "action": "mismatch", "problems": ["osm source missing"]}
        downloaded, report, origins = collect(name, region, cfg)
        downloaded, windows, versions, _ = select_versions(downloaded, cfg, report, origins=origins)
        inputs = build_inputs(name, cfg, osm, versions, windows)
        downloaded, duplicates, _, _ = drop_same_feeds(downloaded, cfg, work, versions)
        report += duplicates
        report += clip_feeds(work, region, cfg, downloaded, windows, versions)
        problems = baseline_problems(current, report, versions, osm)
    except (SystemExit, Exception) as e:  # 기준선을 못 만들면 다시 짓게 둔다.
        return {"region": name, "action": "error", "problems": [tail(str(e) or type(e).__name__, 200)]}
    finally:
        OFFLINE[0] = False
        shutil.rmtree(work, ignore_errors=True)
    if problems:
        return {"region": name, "action": "mismatch", "problems": problems[:20], "count": len(problems)}
    inputs["baseline"] = {"verifiedAt": datetime.datetime.now().isoformat(timespec="seconds"),
                          "method": "offline collect, dedupe and clip matched feeds-report and manifest sha256"}
    feed_versions.write_json(os.path.join(current, "inputs.json"), inputs)
    return {"region": name, "action": "baselined", "feeds": len(inputs["feeds"]), "digest": inputs["digest"][:16]}


# 날짜가 지나며 달라지는 자르기 요약 값. 운행 수가 같으면 그래프에 든 운행이 같다.
BASELINE_IGNORED_FIELDS = ("calendar_window",)
# 예전 파이프라인 요약에 없던 값. 지금 값이 기본값이면 같은 결과다.
BASELINE_DEFAULT_FIELDS = {"dropped_routes": "0"}


def baseline_problems(current, report, versions, osm):
    """지금 그래프와 다시 돌린 결과가 다른 점. 격리된 피드는 OTP가 다시 격리하므로 비교에서 뺀다."""
    def uses(lines):
        out = {}
        for line in lines:
            if line.startswith("use "):
                fid, stats = line[4:].split(": ", 1)
                out[fid.strip()] = {k: v for k, v in (x.split("=", 1) for x in stats.split() if "=" in x)
                                    if k not in BASELINE_IGNORED_FIELDS}
        return out

    def same(a, b):
        keys = a.keys() | b.keys()
        return all(a.get(k, BASELINE_DEFAULT_FIELDS.get(k)) == b.get(k, BASELINE_DEFAULT_FIELDS.get(k)) for k in keys)

    def reason(fid):
        return next((l for l in report if l[4:].split(":", 1)[0].strip() == fid or l.startswith(f"warn {fid}:")), "no line")
    with open(os.path.join(current, "feeds-report.txt"), encoding="utf-8") as f:
        old_lines = f.read().splitlines()
    quarantined = {l[len("quarantined "):].split(":", 1)[0].strip() for l in old_lines if l.startswith("quarantined ")}
    old, new = uses(old_lines), {k: v for k, v in uses(report).items() if k not in quarantined}
    problems = [f"only in current graph: {f} ({tail(reason(f), 160)})" for f in sorted(old.keys() - new.keys())]
    problems += [f"new in pipeline: {f}" for f in sorted(new.keys() - old.keys())]
    problems += [f"clip differs: {f} ({old[f]} -> {new[f]})" for f in sorted(old.keys() & new.keys()) if not same(old[f], new[f])]
    with open(os.path.join(current, "feeds-manifest.json"), encoding="utf-8") as f:
        manifest = json.load(f)
    problems += [f"source differs: {f}" for f in sorted(old.keys() & new.keys())
                 if manifest.get(f, {}).get("sha256") != versions.get(f, {}).get("sha256")]
    with open(os.path.join(current, "build-metrics.json"), encoding="utf-8") as f:
        metrics = json.load(f)
    if metrics.get("smoke", {}).get("failed"):
        problems.append("current graph smoke failed")
    built = datetime.datetime.fromisoformat(metrics["builtAt"])
    if osm and datetime.datetime.fromtimestamp(os.path.getmtime(osm)) > built:
        problems.append("osm source newer than graph")
    return problems


def refresh(names, check_only=False):
    """정기 갱신: 지역을 하나씩 돌며 입력이 바뀐 지역만 다시 짓는다. 실패한 지역은 지금 그래프를 그대로 둔다.
    하나라도 실패하면 종료 코드 1이라 스케줄러가 실패 알림을 보낸다."""
    summary = []
    for name in names:
        if held(name):
            summary.append({"region": name, "action": "held", "reason": held(name)["reason"]})
            continue
        try:
            result = build(name, force=False, promote_graph=not check_only)
            summary.append({"region": name, "action": result["action"]})
        except (SystemExit, Exception) as e:  # 한 지역 실패가 다른 지역 갱신을 막지 않는다.
            if isinstance(e, SystemExit) and e.code == TERMINATED:
                raise
            summary.append({"region": name, "action": "failed", "reason": tail(str(e) or type(e).__name__, 300)})
    print(json.dumps({"refresh": summary}, ensure_ascii=False))
    return 1 if any(s["action"] == "failed" for s in summary) else 0


def lifecycle_report(today=None, prefetch=False):
    """보관한 피드 판 상태: 만료 예정(EXPIRY_WARN_DAYS 안)·만료·다음 판. prefetch면 다음 판을 미리 받는다."""
    today = today or datetime.date.today()
    store = versions_store()
    rules = config().get("nextEdition", [])
    out = {"date": today.isoformat(), "feeds": 0, "states": {}, "expiring": [], "expired": [], "prefetch": []}
    direct = mdb_direct_sources()
    for fid in store.feeds():
        idx = store.index(fid)
        latest = max(idx["versions"], key=lambda v: v["fetchedAt"]) if idx["versions"] else {}
        source = direct.get(fid) or latest.get("source")
        if latest.get("calendarSuspect"):
            continue
        try:
            status = store.status(fid, today)
        except Exception as e:  # 판 목록 하나가 깨져도 나머지 피드 상태를 낸다.
            out.setdefault("unreadable", []).append({"feedId": fid, "error": type(e).__name__})
            continue
        out["feeds"] += 1
        out["states"][status["state"]] = out["states"].get(status["state"], 0) + 1
        if status.get("newestRejected"):
            out.setdefault("newestRejected", []).append({"feedId": fid, "problems": status["newestRejected"]})
        if status.get("nextUnlinked"):
            out.setdefault("nextUnlinked", []).append(fid)
        if status["state"] in ("expiring", "expired"):
            out[status["state"]].append({"feedId": fid, "serviceTo": status.get("serviceTo"),
                                         "daysLeft": status.get("daysLeft"), "versions": len(idx["versions"])})
            if prefetch:
                out["prefetch"] += prefetch_next(fid, store, source, rules, today)
    return out


def mdb_direct_sources():
    """MDB 피드 id → 원천 직접 주소. 그래프 없이 판 상태만 볼 때 카탈로그 사본 주소 대신 원천 주소로 다음 판을 묻는다."""
    path = os.path.join(SOURCES, "catalog", "feeds_v2.csv")
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as f:
        return {r["id"]: r["urls.direct_download"] for r in csv.DictReader(f) if r.get("urls.direct_download")}


def import_versions():
    """지금 sources/gtfs에 있는 원본을 판 보관소에 넣는다. 원천 정보는 그래프 피드 목록에서 가져온다."""
    origins = {}
    for manifest in sorted(pathlib.Path(ROOT).glob("*/graph/feeds-manifest.json")):
        for fid, entry in json.load(open(manifest, encoding="utf-8")).items():
            origins.setdefault(fid, {k: entry.get(k) for k in ("source", "catalog", "license")})
    store, added, seen = versions_store(), 0, 0
    folder = os.path.join(SOURCES, "gtfs")
    for name in sorted(os.listdir(folder)) if os.path.isdir(folder) else []:
        if not name.endswith(".gtfs.zip") or not usable_copy(os.path.join(folder, name)):
            continue
        path = os.path.join(folder, name)
        fid = name[:-len(".gtfs.zip")]
        _, created = store.register(fid, path, origins.get(fid), now=datetime.datetime.fromtimestamp(os.path.getmtime(path)),
                                    allow_trip_drop=bool(config()["feeds"].get(fid, {}).get("allowTripDrop")))
        added += created
        seen += 1
    print(json.dumps({"copies": seen, "newVersions": added}))


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def compare_with_current(name, manifest):
    """지금 쓰는 그래프보다 핵심 피드 운행 수가 크게 줄었거나 핵심 피드가 빠졌으면 교체하지 않는다."""
    current = os.path.join(ROOT, name, "graph", "feeds-manifest.json")
    if not os.path.exists(current):
        return []
    old = json.load(open(current, encoding="utf-8"))
    core = set(regions()[name].get("feeds", []))
    problems = []
    for fid in core:
        before, after = old.get(fid, {}).get("trips", 0), manifest.get(fid, {}).get("trips", 0)
        if before and after < before * TRIP_DROP_LIMIT:
            problems.append(f"{fid} trips {before}->{after}")
    return problems


def regions():
    return config()["regions"]


# 그때 읽던 피드 탓이 아닌 실패. 격리하면 멀쩡한 피드만 빠진다.
NOT_FEED_ERRORS = ("Duplicate feed id", "Failed to parse config", "OutOfMemoryError")
# OTP가 GTFS 파일을 읽다 실패하면 스택에 이 패키지가 남는다.
GTFS_READ_FRAMES = ("org.onebusaway", "org.opentripplanner.gtfs.mapping", "de.siegmar.fastcsv")


def build_with_quarantine(graph, heap, drop, core=()):
    """빌드가 피드 오류로 멈추면 로그에서 그때 읽던 피드를 격리하고 drop(피드 id)으로 설정에서 뺀 뒤 다시 짓는다.
    격리한 피드 id와 OTP 빌드 최대 메모리(바이트)를 돌려준다."""
    quarantined, peak = [], 0
    for _ in range(MAX_QUARANTINE + 1):
        log = os.path.join(graph, "build.log")
        with open(log, "w") as f:
            code, _, _, used = run_measured(["java", f"-Xmx{heap}", "-jar", OTP_JAR, "--build", "--save", graph], f)
        peak = max(peak, used)
        if code == 0:
            return quarantined, peak
        text = open(log, encoding="utf-8", errors="replace").read()
        bad = quarantine_target(text)
        fid = os.path.basename(bad)[:-len(".gtfs.zip")] if bad else None
        if not bad or not os.path.exists(bad) or fid in core:
            raise subprocess.CalledProcessError(code, f"otp build (not quarantined: {fid or 'no feed error'})")
        quarantine = os.path.join(graph, "..", "quarantine")
        os.makedirs(quarantine, exist_ok=True)
        shutil.move(bad, os.path.join(quarantine, os.path.basename(bad)))
        shutil.copyfile(log, os.path.join(quarantine, fid + ".log"))
        drop(fid)
        quarantined.append(fid)
    raise subprocess.CalledProcessError(1, "otp build: too many broken feeds")


def quarantine_target(log_text):
    """마지막으로 읽던 GTFS 묶음이 실패 원인일 때만 그 경로를 돌려준다."""
    if any(marker in log_text for marker in NOT_FEED_ERRORS):
        return None
    reading = list(re.finditer(r"reading GTFS bundle at (\S+\.gtfs\.zip)", log_text))
    if not reading:
        return None
    after = log_text[reading[-1].end():]
    return reading[-1].group(1) if any(frame in after for frame in GTFS_READ_FRAMES) else None


SERVING = "serving"


def serve(name):
    """서버가 읽는 그래프와 같은 피드 범위 파일을 serving/에 둔다. 교체로 graph/가 바뀌어도 서버를 다시 띄우기 전까지
    경로 엔진은 지금 서버 그래프의 범위로 판정한다."""
    region = config()["regions"][name]
    graph = os.path.join(ROOT, name, "graph")
    serving = os.path.join(ROOT, name, SERVING)
    os.makedirs(serving, exist_ok=True)
    coverage = os.path.join(graph, "feeds-coverage.json")
    target = os.path.join(serving, "feeds-coverage.json")
    if os.path.exists(coverage):
        copy_replace(coverage, target)
    elif os.path.exists(target):
        os.remove(target)
    metrics = os.path.join(graph, "build-metrics.json")
    built = json.load(open(metrics)).get("builtAt") if os.path.exists(metrics) else None
    feed_versions.write_json(os.path.join(serving, "graph.json"), {"builtAt": built, "startedAt":
                             datetime.datetime.now().isoformat(timespec="seconds"), "port": region["port"]})
    os.execvp("java", ["java", f"-Xmx{region['heap']}", "-jar", OTP_JAR, "--load", "--bindAddress", "127.0.0.1",
                       "--port", str(region["port"]), graph])


def listening_pids(port):
    r = subprocess.run(["lsof", "-nP", f"-iTCP:{port}", "-sTCP:LISTEN", "-t"], capture_output=True, text=True)
    return [int(x) for x in r.stdout.split()]


RESTART_STOP_SECONDS = 60


def restart(name):
    """지역 OTP 서버를 지금 graph/로 다시 띄우고 대표 구간을 묻는다. 그 포트를 이 지역 OTP가 아닌 프로세스가 쓰면 멈춘다."""
    lock = build_lock()
    region = regions()[name]
    port = region["port"]
    graph = os.path.join(ROOT, name, "graph")
    for pid in listening_pids(port):
        cmd = subprocess.run(["ps", "-o", "command=", "-p", str(pid)], capture_output=True, text=True).stdout
        if OTP_JAR not in cmd or f"/{name}/graph" not in cmd:
            raise SystemExit(f"{name}: port {port} is used by another process (pid {pid}), not restarted")
        os.kill(pid, signal.SIGTERM)
        deadline = time.monotonic() + RESTART_STOP_SECONDS
        while pid in listening_pids(port) and time.monotonic() < deadline:
            time.sleep(1)
        if pid in listening_pids(port):
            os.kill(pid, signal.SIGKILL)
    log_path = os.path.join(ROOT, name, "serve.log")
    started = time.monotonic()
    with open(log_path, "w") as log:
        server = subprocess.Popen([sys.executable, "-I", os.path.abspath(__file__), "serve", name], stdout=log,
                                  stderr=subprocess.STDOUT, start_new_session=True)
    if not wait_started(log_path, server):
        lock.close()
        raise SystemExit(f"{name}: OTP did not start on port {port}, see {log_path}")
    result = {"region": name, "pid": server.pid, "port": port, "startSeconds": round(time.monotonic() - started, 1),
              "graphBuiltAt": json.load(open(os.path.join(graph, "build-metrics.json"))).get("builtAt"),
              "smokeFailed": smoke_cases(region, port)}
    lock.close()
    print(json.dumps(result, ensure_ascii=False))
    if result["smokeFailed"]:
        raise SystemExit(f"{name}: restarted but smoke failed {result['smokeFailed']}")
    return result


def stop(name):
    """이 지역 OTP 서버만 멈춘다."""
    port = regions()[name]["port"]
    for pid in listening_pids(port):
        cmd = subprocess.run(["ps", "-o", "command=", "-p", str(pid)], capture_output=True, text=True).stdout
        if OTP_JAR in cmd and f"/{name}/graph" in cmd:
            os.kill(pid, signal.SIGTERM)


def catalog_summary(country):
    """나라 하나의 카탈로그 피드 형식·상태·공식 여부와 MDB·Atlas 원천 주소 중복. 새 형식(NeTEx 등) 대응 여부를 정하는 근거다."""
    cfg = config()
    aliases = cfg.get("sourceHostAliases", {})
    catalog = fetch(MDB_CATALOG, os.path.join(SOURCES, "catalog", "feeds_v2.csv"), max_age_days=catalog_refresh_days())
    with open(catalog, encoding="utf-8") as f:
        rows = [r for r in csv.DictReader(f) if r["location.country_code"] == country]
    count = lambda key: dict(sorted(collections.Counter(key(r) for r in rows).items()))
    mdb_keys = {source_key(r[c], aliases) for r in rows for c in ("urls.direct_download", "urls.latest") if r[c]}
    out = {"country": country, "mdb": {
        "feeds": len(rows), "byFormat": count(lambda r: r["data_type"]),
        "gtfsByStatus": count(lambda r: (r["status"] or "unknown") if r["data_type"] == "gtfs" else "-"),
        "official": count(lambda r: r.get("is_official") or "unknown"),
        "auth": count(lambda r: "none" if r["urls.authentication_type"] in ("", "0") else "required"),
        "withBoundingBox": sum(1 for r in rows if r.get("location.bounding_box.minimum_latitude")),
        "redirects": sum(1 for r in rows if r.get("redirect.id")),
        "realtimeWithStaticReference": sum(1 for r in rows if r["data_type"] == "gtfs_rt" and r.get("static_reference"))}}
    atlas = [a for a in cfg.get("atlas", {}).values() if isinstance(a, dict) and a.get("country") == country]
    if atlas:
        tarball = fetch(ATLAS_TARBALL, os.path.join(SOURCES, "catalog", "transitland-atlas.tar.gz"),
                        max_age_days=catalog_refresh_days())
        pattern = re.compile(atlas[0]["match"])
        specs, overlap, total = collections.Counter(), 0, 0
        with tarfile.open(tarball) as tar:
            for member in tar.getmembers():
                if not member.isfile() or "/feeds/" not in member.name or not member.name.endswith(".json"):
                    continue
                try:
                    doc = json.load(tar.extractfile(member))
                except ValueError:
                    continue
                for feed in doc.get("feeds", []):
                    url = (feed.get("urls") or {}).get("static_current") or ""
                    if not pattern.search(feed.get("id", "") + " " + url + " " + json.dumps(feed.get("operators", []), ensure_ascii=False)):
                        continue
                    specs[feed.get("spec", "gtfs")] += 1
                    if url:
                        total += 1
                        overlap += source_key(url, aliases) in mdb_keys
        out["atlas"] = {"feeds": sum(specs.values()), "bySpec": dict(specs), "staticUrls": total,
                        "sameSourceAsMdb": overlap}
    out["formats"] = cfg.get("formats", {})
    return out


PROVIDERS_FILE = os.path.normpath(os.path.join(HERE, "..", "..", "src", "main", "resources", "routing", "providers.json"))


def provider_inventory():
    """공급자 목록과 인증정보 환경변수가 설정됐는지(값은 읽기만 하고 출력하지 않는다)."""
    with open(PROVIDERS_FILE, encoding="utf-8") as f:
        inventory = json.load(f)
    return [dict(p, keys={k: bool(os.environ.get(k)) for k in p.get("keyEnv", [])}) for p in inventory["providers"]]


USAGE = """usage:
  otp_region.py build <region>             수요 기반: 입력과 상관없이 짓고 검사를 통과하면 교체
  otp_region.py check <region>             짓고 검사만 한다(교체하지 않음)
  otp_region.py refresh [region ...] [--check]  정기 갱신: 입력이 바뀐 지역만 다시 짓는다(기본 모든 지역)
  otp_region.py lifecycle [--prefetch]     피드 판 만료 상태, --prefetch면 만료 예정 피드의 다음 판을 받는다
  otp_region.py import-versions            sources/gtfs 원본을 판 보관소에 넣는다
  otp_region.py catalog <country>          카탈로그 피드 형식·중복 집계
  otp_region.py providers                  공급자 목록·역할·인증정보 설정 여부(값은 출력하지 않음)
  otp_region.py baseline [region ...]      지금 그래프에 입력 기록이 없으면 내려받기 없이 같은 입력인지 확인해 기록한다
  otp_region.py restart <region>           OTP 서버를 지금 graph/로 다시 띄우고 대표 구간을 묻는다(교체·롤백 뒤)
  otp_region.py release <region>           롤백 뒤 정기 갱신 보류를 푼다
  otp_region.py serve|stop|rollback <region>"""


def main():
    args = sys.argv[1:]
    command = args[0] if args else ""
    regions_cfg = config()["regions"]
    if command in ("build", "check", "serve", "rollback", "restart", "stop", "release"):
        if len(args) != 2 or args[1] not in regions_cfg:
            sys.exit(USAGE)
        name = args[1]
        if command == "build":
            build(name)
        elif command == "check":
            build(name, force=True, promote_graph=False)
        else:
            {"serve": serve, "rollback": rollback, "restart": restart, "stop": stop, "release": release}[command](name)
    elif command == "baseline":
        names = [a for a in args[1:] if not a.startswith("--")] or list(regions_cfg)
        lock = build_lock()
        results = [baseline(n) for n in names]
        lock.close()
        print(json.dumps({"baseline": results}, ensure_ascii=False, indent=1))
        sys.exit(1 if any(r["action"] == "error" for r in results) else 0)
    elif command == "refresh":
        names = [a for a in args[1:] if not a.startswith("--")] or list(regions_cfg)
        unknown = [n for n in names if n not in regions_cfg]
        if unknown:
            sys.exit(f"unknown region: {unknown}")
        sys.exit(refresh(names, check_only="--check" in args))
    elif command == "lifecycle":
        # 판 목록을 고치는 동안 빌드가 같은 목록을 고치면 한쪽 판 항목이 사라진다.
        lock = build_lock() if "--prefetch" in args else None
        print(json.dumps(lifecycle_report(prefetch="--prefetch" in args), ensure_ascii=False, indent=1))
        if lock:
            lock.close()
    elif command == "import-versions":
        lock = build_lock()
        import_versions()
        lock.close()
    elif command == "catalog" and len(args) == 2:
        print(json.dumps(catalog_summary(args[1].upper()), ensure_ascii=False, indent=1))
    elif command == "providers":
        print(json.dumps(provider_inventory(), ensure_ascii=False, indent=1))
    else:
        sys.exit(USAGE)


if __name__ == "__main__":
    main()
