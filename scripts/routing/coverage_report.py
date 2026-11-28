"""운영사별 커버리지를 데이터 존재 → 피드 확보 → OTP 적재 → 경로 조회 성공 네 단계로 나눠 적는다.

  python3 -I coverage_report.py agencies jp-kanto            # 떠 있는 지역 서버의 운영사·수단 목록을 저장
  python3 -I coverage_report.py report coverage-targets.json  # 대상 운영사 표(마크다운)
  python3 -I coverage_report.py areas [out.json]              # 지은 그래프의 행정구역·수단별 시간표 범위(서버 불필요)

경로 조회 성공은 RouteCompareRunner 결과(build/eval-route/route-compare.md)의 기대 판정에서 읽는다.
"""
import csv
import datetime
import glob
import json
import math
import os
import re
import sys
import urllib.request
import io
import importlib.util

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", "build", "otp"))
SOURCES = os.path.join(ROOT, "sources")
AGENCY_QUERY = "{ agencies { name routes { mode } } }"


def feed_zip(path):
    spec = importlib.util.spec_from_file_location("gtfs_clip", os.path.join(HERE, "gtfs_clip.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.FeedZip(path)


def regions():
    with open(os.path.join(HERE, "regions.json"), encoding="utf-8") as f:
        return json.load(f)["regions"]


def save_agencies(name):
    port = regions()[name]["port"]
    req = urllib.request.Request(f"http://127.0.0.1:{port}/otp/gtfs/v1", json.dumps({"query": AGENCY_QUERY}).encode(),
                                 {"Content-Type": "application/json"})
    data = json.load(urllib.request.urlopen(req, timeout=120))["data"]["agencies"]
    out = {a["name"]: sorted({r["mode"] for r in a["routes"]}) for a in data}
    with open(os.path.join(ROOT, name, "agencies.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(name, len(out), "agencies")


def catalog_names():
    """카탈로그별로 운영사·피드 이름을 모은다. 이름이 있으면 데이터가 존재한다고 본다."""
    names = {"mdb": set(), "jp-rail": set(), "odpt": set(), "atlas": set()}
    path = os.path.join(SOURCES, "catalog", "feeds_v2.csv")
    if os.path.exists(path):
        for r in csv.DictReader(open(path, encoding="utf-8")):
            names["mdb"].add(r["provider"] + " " + r["name"])
    rail = os.path.join(SOURCES, "gtfs", "jp-rail.gtfs.zip")
    if os.path.exists(rail):
        z = feed_zip(rail)
        names["jp-rail"] = {r["agency_name"] for r in csv.DictReader(io.TextIOWrapper(z.open("agency.txt"), "utf-8-sig"))}
    with open(os.path.join(HERE, "regions.json"), encoding="utf-8") as f:
        names["odpt"] = {x["id"] for x in json.load(f).get("odpt", {}).get("feeds", [])}
    # 피드 id 규칙이 바뀌면 옛 이름 파일이 sources에 남으므로 빌드가 실제로 본 Atlas 피드만 센다.
    names["atlas"] = {line.split()[1].rstrip(":") for p in glob.glob(os.path.join(ROOT, "*", "graph", "feeds-report.txt"))
                      for line in open(p, encoding="utf-8") if len(line.split()) > 1 and line.split()[1].startswith("atlas-")}
    return names


def loaded_agencies():
    out = {}
    for p in glob.glob(os.path.join(ROOT, "*", "agencies.json")):
        region = os.path.basename(os.path.dirname(p))
        for agency, modes in json.load(open(p, encoding="utf-8")).items():
            out.setdefault(agency, []).append((region, modes))
    return out


def used_feeds():
    used = {}
    for p in glob.glob(os.path.join(ROOT, "*", "graph", "feeds-report.txt")):
        region = os.path.basename(os.path.dirname(os.path.dirname(p)))
        for line in open(p, encoding="utf-8"):
            if line.startswith("use "):
                used.setdefault(line.split()[1].rstrip(":"), []).append(region)
    return used


def route_checks():
    path = os.path.normpath(os.path.join(HERE, "..", "..", "build", "eval-route", "route-compare.md"))
    out = {}
    if not os.path.exists(path):
        return out
    for line in open(path, encoding="utf-8"):
        cells = [c.strip() for c in line.split("|")]
        if len(cells) > 10 and cells[1] and not cells[1].startswith(("id", "---")):
            out[cells[1]] = cells[9]
    return out


def report(targets_file):
    targets = json.load(open(os.path.join(HERE, targets_file), encoding="utf-8"))
    names, loaded, used, checks = catalog_names(), loaded_agencies(), used_feeds(), route_checks()
    print("| 구분 | 운영사·노선 | 데이터 존재 | 피드 확보 | OTP 적재 | 경로 조회 |")
    print("|---|---|---|---|---|---|")
    for t in targets:
        pattern = re.compile(t["match"])
        mode = t.get("mode")
        # 전국 철도 피드는 철도 운영사 근거다. 같은 교통국의 버스·배까지 있다고 보면 안 된다.
        exists = [k for k, v in names.items() if (k != "jp-rail" or mode in (None, "RAIL"))
                  and any(pattern.search(n) for n in v)]
        feeds = sorted({r for fid, rs in used.items() if pattern.search(fid) or fid in t.get("feeds", []) for r in rs})
        if "jp-rail" in exists:
            feeds = feeds or ["jp-rail(전국 철도)"]
        rail_modes = {"RAIL", "SUBWAY", "TRAM", "MONORAIL", "FUNICULAR"}
        wanted = rail_modes if mode == "RAIL" else {mode} if mode else None
        hits = [(a, [(r, m) for r, m in rs if wanted is None or wanted & set(m)]) for a, rs in loaded.items()
                if pattern.search(a)]
        hits = [(a, rs) for a, rs in hits if rs]
        load = ", ".join(sorted({r for _, rs in hits for r, _ in rs})) or "-"
        check = "; ".join(f"{c}: {checks[c]}" for c in t.get("cases", []) if c in checks) or "-"
        print(f"| {t['group']} | {t['label']} | {', '.join(exists) or '없음'} | {', '.join(feeds) or '-'} | {load} | {check} |")


ADMIN_AREAS = os.path.normpath(os.path.join(HERE, "..", "..", "src", "main", "resources", "routing", "admin-areas.json"))
# nearest_within이 이웃 띠 하나씩만 보므로 띠 폭(약 5.5km)이 AREA_BUFFER_KM보다 넓어야 한다.
BAND_DEG = 0.05
# application-routing.yml routing.area-buffer-km과 같은 값이어야 엔진이 구역 밖으로 보는 정류장을 같게 센다.
AREA_BUFFER_KM = 3
KM_PER_DEG = 111.32
EXPIRY_WARN_DAYS = 14


def otp_region():
    spec = importlib.util.spec_from_file_location("otp_region", os.path.join(HERE, "otp_region.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class AreaIndex:
    """행정구역 고리마다 위도 띠별 변 목록을 두어 점 하나가 변 몇 개만 본다.
    admin-areas.json은 구멍을 버려 서울이 경기 고리 안에도 들므로 점을 품은 가장 작은 고리를 고른다."""

    def __init__(self, path):
        self.rings = []
        for code, rings in json.load(open(path, encoding="utf-8")).items():
            for ring in rings:
                area = abs(sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(ring, ring[1:] + ring[:1]))) / 2
                bands = {}
                for a, b in zip(ring, ring[1:] + ring[:1]):
                    for k in range(int(min(a[0], b[0]) // BAND_DEG), int(max(a[0], b[0]) // BAND_DEG) + 1):
                        bands.setdefault(k, []).append((a, b))
                lats, lngs = [p[0] for p in ring], [p[1] for p in ring]
                self.rings.append((area, code, (min(lats), min(lngs), max(lats), max(lngs)), bands))
        self.rings.sort(key=lambda r: r[0])

    def locate(self, lat, lng):
        for _, code, box, bands in self.rings:
            if not (box[0] <= lat <= box[2] and box[1] <= lng <= box[3]):
                continue
            inside = False
            for (y1, x1), (y2, x2) in bands.get(int(lat // BAND_DEG), ()):
                if (y1 > lat) != (y2 > lat) and lng < x1 + (lat - y1) * (x2 - x1) / (y2 - y1):
                    inside = not inside
            if inside:
                return code
        return self.nearest_within(lat, lng)

    def nearest_within(self, lat, lng):
        """경로 엔진(AdminAreas)처럼 경계에서 AREA_BUFFER_KM 안이면 그 구역으로 본다."""
        pad, k = AREA_BUFFER_KM / KM_PER_DEG, math.cos(math.radians(lat))
        best, found = AREA_BUFFER_KM, None
        for _, code, box, bands in self.rings:
            if not (box[0] - pad <= lat <= box[2] + pad and box[1] - pad / k <= lng <= box[3] + pad / k):
                continue
            band = int(lat // BAND_DEG)
            for (y1, x1), (y2, x2) in (e for b in (band - 1, band, band + 1) for e in bands.get(b, ())):
                ax, ay, dx, dy = (x1 - lng) * k, y1 - lat, (x2 - x1) * k, y2 - y1
                t = max(0.0, min(1.0, -(ax * dx + ay * dy) / (dx * dx + dy * dy))) if dx or dy else 0.0
                d = math.hypot(ax + t * dx, ay + t * dy) * KM_PER_DEG
                if d <= best:
                    best, found = d, code
        return found


def area_coverage(out_path=None, today=None):
    """지역 그래프에 실은 피드의 정류장을 행정구역에 넣어 구역·그래프·수단별 피드·노선·정류장 수와 가장 이른 피드 종료일을 낸다.
    대표 시각표 피드(representativeCalendar)는 운행일을 확정하지 않아 종료일 계산에서 뺀다.
    경계에서 AREA_BUFFER_KM 넘게 떨어진 정류장은 엔진도 영역 밖으로 보므로 "-"로 센다."""
    otp = otp_region()
    rows = otp.feed_compare.rows
    cfg = otp.config()
    today = today or datetime.date.today()
    warn = (today + datetime.timedelta(days=EXPIRY_WARN_DAYS)).strftime("%Y%m%d")
    index = AreaIndex(ADMIN_AREAS)
    out = {}
    for region in cfg["regions"]:
        graph = os.path.join(ROOT, region, "graph")
        manifest_path = os.path.join(graph, "feeds-manifest.json")
        if not os.path.exists(manifest_path):
            continue
        for fid, entry in json.load(open(manifest_path, encoding="utf-8")).items():
            path = os.path.join(graph, fid + ".gtfs.zip")
            if not os.path.exists(path):
                continue
            z = feed_zip(path)
            modes = {r["route_id"]: otp.route_mode(r.get("route_type")) for r in rows(z, "routes.txt")}
            trips = {r["trip_id"]: r["route_id"] for r in rows(z, "trips.txt")}
            stops = {}
            for r in rows(z, "stops.txt"):
                try:
                    stops[r["stop_id"]] = (float(r["stop_lat"]), float(r["stop_lon"]))
                except (KeyError, TypeError, ValueError):
                    continue
            served = {}
            for r in rows(z, "stop_times.txt"):
                route = trips.get(r["trip_id"])
                if route in modes and r["stop_id"] in stops:
                    served.setdefault(r["stop_id"], set()).add(route)
            base = entry.get("versionOf") or fid
            representative = bool(cfg["feeds"].get(base, {}).get("representativeCalendar") or entry.get("calendarSuspect"))
            end = min(filter(None, (entry.get("serviceTo"), entry.get("loadedTo"))), default=None)
            for sid, routes in served.items():
                code = index.locate(*stops[sid]) or "-"
                for route in routes:
                    m = out.setdefault(code, {}).setdefault(region, {}).setdefault(modes[route], {
                        "feeds": set(), "routes": set(), "stops": set(), "earliestEnd": None, "endingSoon": set()})
                    m["feeds"].add(fid)
                    m["routes"].add((fid, route))
                    m["stops"].add((fid, sid))
                    if end and not representative:
                        m["earliestEnd"] = min(filter(None, (m["earliestEnd"], end)))
                        if end <= warn:
                            m["endingSoon"].add(fid)
    result = {code: {region: {mode: {"feeds": len(v["feeds"]), "routes": len(v["routes"]), "stops": len(v["stops"]),
                                     "earliestEnd": v["earliestEnd"], "endingSoon": sorted(v["endingSoon"])}
                              for mode, v in sorted(modes.items())} for region, modes in by_region.items()}
              for code, by_region in sorted(out.items())}
    if out_path:
        with open(out_path, "w", encoding="utf-8") as f:
            json.dump({"date": today.isoformat(), "areas": result}, f, ensure_ascii=False, indent=1)
    for code, by_region in result.items():
        for region, modes in by_region.items():
            print(code, region, " ".join(f"{m}={v['feeds']}/{v['routes']}/{v['stops']}" for m, v in modes.items()))
    return result


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "agencies":
        save_agencies(sys.argv[2])
    elif len(sys.argv) == 3 and sys.argv[1] == "report":
        report(sys.argv[2])
    elif len(sys.argv) in (2, 3) and sys.argv[1] == "areas":
        area_coverage(sys.argv[2] if len(sys.argv) == 3 else None)
    else:
        sys.exit("usage: coverage_report.py agencies <region> | report <targets.json> | areas [out.json]")
