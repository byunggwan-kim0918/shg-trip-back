"""지역 OTP 그래프를 regions.json 정의대로 만들고 띄운다.

  python3 -I otp_region.py build jp-kanto     # 내려받기 → 자르기 → 임시 빌드 → 품질 검사 → 통과하면 교체
  python3 -I otp_region.py serve jp-kanto     # 127.0.0.1에만 바인딩
  python3 -I otp_region.py rollback jp-kanto  # 교체 전 그래프로 되돌린다

내려받은 원본은 build/otp/sources에 두고 다시 쓴다. 피드는 모두 gtfs_clip.py를 거쳐 참조가 깨진 행을 빼야
피드 하나 때문에 빌드 전체가 멈추지 않는다.
"""
import csv
import fcntl
import http.client
import signal
import hashlib
import importlib.util
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


def source_key(url, aliases=None):
    """원천 주소 하나로 피드 정체를 정한다. 같은 피드가 여러 카탈로그에 실려 두 번 들어가지 않게 한다."""
    parts = urllib.parse.urlsplit(url.strip())
    host = parts.netloc.lower()
    host = (aliases or {}).get(host, host)
    query = sorted((k, v) for k, v in urllib.parse.parse_qsl(parts.query) if k.lower() not in VOLATILE_QUERY)
    return host + parts.path.rstrip("/").lower() + ("?" + urllib.parse.urlencode(query) if query else "")


_GTFS_CLIP = []


def feed_zip(path):
    """gtfs_clip.FeedZip(하위 폴더에 든 GTFS도 읽음). -I 실행이라 같은 폴더 모듈을 경로로 불러온다."""
    if not _GTFS_CLIP:
        spec = importlib.util.spec_from_file_location("gtfs_clip", os.path.join(HERE, "gtfs_clip.py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        _GTFS_CLIP.append(module)
    return _GTFS_CLIP[0].FeedZip(path)


S3_URI = re.compile(r"^s3://[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]/[A-Za-z0-9!_.*'()/-]+$")
S3_TIMEOUT_SECONDS = 1800


def fetch_s3(uri, path):
    """비공개 버킷에 둔 신청 자료를 받는다. 자격 증명은 실행 환경의 AWS 기본 설정을 쓴다."""
    if not S3_URI.match(uri):
        raise ValueError("invalid s3 uri")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".part"
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


def mdb_feeds(country, subdivisions):
    """카탈로그에서 지역의 현·주에 든 운행 중·인증 없는 GTFS."""
    catalog = fetch(MDB_CATALOG, os.path.join(SOURCES, "catalog", "feeds_v2.csv"), max_age_days=catalog_refresh_days())
    wanted = {normalize(x) for x in subdivisions}
    out = []
    with open(catalog, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if r["data_type"] != "gtfs" or r["location.country_code"] != country:
                continue
            if r["status"] in ("inactive", "deprecated") or r["urls.authentication_type"] not in ("", "0"):
                continue
            if r["urls.latest"] and (not wanted or normalize(r["location.subdivision_name"]) in wanted):
                ORIGINS[r["id"]] = {"source": r["urls.direct_download"] or r["urls.latest"], "catalog": "mobility-database",
                                    "license": r.get("urls.license") or None}
                out.append((r["id"], r["urls.latest"]))
    return out


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
            ORIGINS[feed["id"]] = {"source": feed["url"], "catalog": "odpt"}
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
    if time.time() - failed.get(fid, 0) < FAILED_FEED_RETRY_SECONDS:
        if has_copy:
            return src
        raise TimeoutError("recently failed")
    try:
        return fetch(url, src, timeout=FEED_TIMEOUT_SECONDS, retries=1, max_age_days=catalog_refresh_days())
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
        start, end = max(start, window[0]), min(end, window[1])
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


def build(name):
    # 같은 지역을 두 번 돌리면 진행 중인 staging을 지우고, 지역끼리는 sources의 .part·실패 기록을 같이 쓴다.
    lock = build_lock()
    # 기본 SIGTERM은 예외 없이 끝나 OTP 자식이 남는다. 예외로 바꿔 run_measured가 자식을 정리하게 한다.
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    cfg = config()
    region = cfg["regions"][name]
    bbox = region["bbox"]
    graph = os.path.join(ROOT, name, "staging")
    shutil.rmtree(graph, ignore_errors=True)
    os.makedirs(graph)
    fetch(OTP_URL, OTP_JAR)
    if region.get("osm") or region.get("osmUrl"):
        url = region.get("osmUrl") or GEOFABRIK + region["osm"] + "-latest.osm.pbf"
        osm = fetch(url, os.path.join(SOURCES, "osm", name + ".osm.pbf"))
        os.symlink(osm, os.path.join(graph, "region.osm.pbf"))
    report = []
    sources = [(fid, cfg["feeds"][fid].get("url")) for fid in region.get("feeds", [])]
    if region.get("mdb"):
        sources += mdb_feeds(region["mdb"], region.get("subdivisions", []))
    aliases = cfg.get("sourceHostAliases", {})
    known = mdb_known_sources(region["mdb"], aliases)[0] if region.get("mdb") else set()
    downloaded, origins = [], {}
    for fid, url in sources:
        src = os.path.join(SOURCES, "gtfs", fid + ".gtfs.zip")
        feed = cfg["feeds"].get(fid, {})
        local = os.environ.get(feed.get("pathEnv", ""), "")
        if not local and not url:
            # 신청해서 받는 자료는 공개 주소가 없어, 경로가 없으면 이전 파일로 짓지 않고 빼서 회귀 검사가 교체를 막게 한다.
            report.append(f"skip {fid}: needs {feed.get('pathEnv') or 'url or pathEnv'}")
            continue
        try:
            if local.startswith("s3://"):
                fetch_s3(local, src)
            elif local:
                shutil.copyfile(local, src)
            else:
                fetch(url, src, max_age_days=feed.get("refreshDays", cfg.get("catalogRefreshDays")))
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
    downloaded, duplicates, duplicate_sources = drop_same_feeds(downloaded, cfg, graph)
    report += duplicates
    # 남긴 피드가 뒤에서 빠져도(기간 만료 등) 뺀 피드의 출처가 사라지지 않게 판정 결과를 따로 남긴다.
    with open(os.path.join(graph, "feeds-duplicates.json"), "w", encoding="utf-8") as f:
        json.dump(duplicate_sources, f, ensure_ascii=False, indent=1)
    report += clip_feeds(graph, region, cfg, downloaded)
    for line in report:
        if line.startswith("warn "):
            print(line)
    used_ids = [l[4:].split(":", 1)[0].strip() for l in report if l.startswith("use ")]
    missing = missing_core_feeds(name, used_ids)
    if missing:
        raise SystemExit(f"{name}: core feeds missing before build, current graph kept: {missing}")
    write_configs(graph, region, cfg, used_ids)
    print(f"{name}: feeds used {len(used_ids)}/{len(report)}, bbox {bbox}")
    started = time.monotonic()

    def drop(fid):
        if fid in used_ids:
            used_ids.remove(fid)
        write_configs(graph, region, cfg, used_ids)

    quarantined, build_peak = build_with_quarantine(graph, region["buildHeap"], drop, set(region.get("feeds", [])))
    report = quarantine_report(report, quarantined)
    used = len(used_ids)
    with open(os.path.join(graph, "feeds-report.txt"), "w") as f:
        f.write("\n".join(report) + "\n")
    # 격리 뒤 실제로 그래프에 든 피드로 비교해야 핵심 피드가 격리된 그래프를 막는다.
    manifest = feed_manifest(graph, report, {fid: src for fid, src in downloaded}, cfg, origins, duplicate_sources)
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
    promote(name)
    print(json.dumps(metrics, ensure_ascii=False))


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


def drop_same_feeds(downloaded, cfg, graph=None):
    """먼저 온 피드(설정 → MDB → ODPT → Atlas 순)를 남기고 경로 계산 파일이 모두 같은 피드를 뺀다.
    두 번 실으면 같은 운행이 중복된다. 뺀 피드의 출처와 메타데이터 파일은 남는 피드 기준으로 돌려준다."""
    seen, kept, report, dropped = {}, [], [], {}
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
            report.append(f"dup  {fid}: same routing files as {first} (sha256)")
            try:
                entry = provenance(fid, src, cfg)
                if graph:
                    entry["metadataFiles"] = keep_metadata(graph, fid, src)
            except Exception as e:
                entry = {"feedId": fid, "provenanceError": type(e).__name__}
            dropped.setdefault(first, []).append(entry)
            continue
        if key:
            seen[key] = (fid, src)
        kept.append((fid, src))
    return kept, report, dropped


def clip_feeds(graph, region, cfg, downloaded):
    """피드를 잘라 그래프 폴더에 두고 report 줄을 돌려준다.
    실제 달력이 있는 핵심 피드를 먼저 자르고, 나머지 피드는 핵심 피드가 모두 운행하는 기간으로 달력까지 자른다.
    핵심 피드(전국 철도 등)가 없는 날짜를 다른 피드가 기간 안으로 만들면 철도가 빠진 경로가 확정 시간표로 나간다."""
    bbox = region["bbox"]
    report = []
    representative = lambda fid: cfg["feeds"].get(fid, {}).get("representativeCalendar")
    core = [(fid, src) for fid, src in downloaded if fid in region.get("feeds", []) and not representative(fid)]
    clipped = {}
    for fid, src in core:
        clipped[fid], reason = clip(src, os.path.join(graph, fid + ".gtfs.zip"), bbox, cfg["feeds"].get(fid))
        report.append(f"use  {fid}: {clipped[fid]}" if clipped[fid] else f"skip {fid}: {reason}")
    window = calendar_window([clipped.get(fid) for fid, _ in core])
    for fid, src in [x for x in downloaded if x not in core]:
        if not window and representative(fid):
            # 기간 없이 실으면 형식상 달력이 그래프 운행 기간을 실제 시간표보다 길게 만든다.
            report.append(f"skip {fid}: no calendar window from core feeds")
            continue
        result, reason = clip(src, os.path.join(graph, fid + ".gtfs.zip"), bbox, cfg["feeds"].get(fid), window)
        tag = f" calendar_window={window[0]}-{window[1]}" if window else ""
        report.append(f"use  {fid}: {result}{tag}" if result else f"skip {fid}: {reason}")
    return report


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


def write_configs(graph, region, cfg, used_ids):
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
    updaters = [{"type": "stop-time-updater", "frequency": cfg["feeds"][fid]["realtime"].get("frequency", "30s"),
                 "url": cfg["feeds"][fid]["realtime"]["url"], "feedId": feed_id(fid)}
                for fid in used_ids if cfg["feeds"].get(fid, {}).get("realtime")]
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
    failed = []
    try:
        deadline = time.monotonic() + SMOKE_START_SECONDS
        while "Grizzly server running" not in open(log.name, encoding="utf-8", errors="replace").read():
            if server.poll() is not None or time.monotonic() > deadline:
                return {"cases": len(cases), "failed": ["server did not start"]}
            time.sleep(2)
        day = datetime.date.fromisoformat(next_weekday())
        zone = zoneinfo.ZoneInfo(region["timezone"])
        for c in cases:
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
    finally:
        server.terminate()
        try:
            server.wait(timeout=60)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait()
        log.close()
    return {"cases": len(cases), "failed": failed}


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
    print(f"{name}: rolled back")


def feed_id(fid):
    """OTP 피드 id는 정류장 id 앞에 붙어 ':'와 공백을 쓸 수 없다."""
    return safe_id(fid, 60, "A-Za-z0-9_-")


def safe_id(raw, limit, allowed="A-Za-z0-9_.-"):
    """바꾼 글자가 있거나 잘리면 원래 id 해시를 붙인다. 일본어 이름은 모두 '_'가 되어 서로 다른 피드가 같은 id가 된다."""
    safe = re.sub(f"[^{allowed}]", "_", raw)
    if safe == raw and len(safe) <= limit:
        return safe
    return safe[:limit - 9] + "-" + hashlib.sha1(raw.encode("utf-8")).hexdigest()[:8]


def feed_manifest(graph, report, sources, cfg, origins=None, duplicates=None):
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
        span = fields.get("service_days", "none")
        suspect = "-" in span and int(span.split("-")[1][:4]) - int(span.split("-")[0][:4]) > CALENDAR_SPAN_SUSPECT_YEARS
        entries[fid] = {
            "source": (origins or {}).get(fid) or ORIGINS.get(fid, {}).get("source") or cfg["feeds"].get(fid, {}).get("url") or fid,
            "catalog": ORIGINS.get(fid, {}).get("catalog"),
            "license": ORIGINS.get(fid, {}).get("license"),
            "sameFeedsDropped": (duplicates or {}).get(fid, []),
            "otpFeedId": feed_id(fid),
            "downloadedAt": datetime.datetime.fromtimestamp(os.path.getmtime(src)).isoformat(timespec="seconds") if src else None,
            "sha256": sha256(src) if src else None,
            "feedStart": info.get("feed_start_date") or None, "feedEnd": info.get("feed_end_date") or None,
            "feedVersion": info.get("feed_version") or None,
            "serviceFrom": span.split("-")[0] if "-" in span else None,
            "serviceTo": span.split("-")[1] if "-" in span else None,
            "trips": int(fields.get("trips", 0)),
            # 운영 시간표 달력은 보통 1년 안팎이다. 수년~수십 년짜리는 분석용·형식상 달력이라 운행일을 확정하지 못한다.
            "calendarSuspect": suspect,
            "basis": cfg["feeds"].get(fid, {}).get("basis"),
            "edition": cfg["feeds"].get(fid, {}).get("edition"),
            "loadedFrom": fields["calendar_window"].split("-")[0] if "calendar_window" in fields else None,
            "loadedTo": fields["calendar_window"].split("-")[1] if "calendar_window" in fields else None,
            "editionStale": edition_stale(cfg["feeds"].get(fid, {})),
        }
    with open(os.path.join(graph, "feeds-manifest.json"), "w", encoding="utf-8") as f:
        json.dump(entries, f, ensure_ascii=False, indent=1)
    return entries


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


def serve(name):
    region = config()["regions"][name]
    graph = os.path.join(ROOT, name, "graph")
    os.execvp("java", ["java", f"-Xmx{region['heap']}", "-jar", OTP_JAR, "--load", "--bindAddress", "127.0.0.1",
                       "--port", str(region["port"]), graph])


def main():
    if len(sys.argv) != 3 or sys.argv[1] not in ("build", "serve", "rollback"):
        sys.exit("usage: otp_region.py build|serve|rollback <region>")
    name = sys.argv[2]
    if name not in config()["regions"]:
        sys.exit(f"unknown region: {name}")
    {"build": build, "serve": serve, "rollback": rollback}[sys.argv[1]](name)


if __name__ == "__main__":
    main()
