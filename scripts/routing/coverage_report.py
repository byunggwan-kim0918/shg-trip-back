"""운영사별 커버리지를 데이터 존재 → 피드 확보 → OTP 적재 → 경로 조회 성공 네 단계로 나눠 적는다.

  python3 -I coverage_report.py agencies jp-kanto            # 떠 있는 지역 서버의 운영사·수단 목록을 저장
  python3 -I coverage_report.py report coverage-targets.json  # 대상 운영사 표(마크다운)

경로 조회 성공은 RouteCompareRunner 결과(build/eval-route/route-compare.md)의 기대 판정에서 읽는다.
"""
import csv
import glob
import json
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


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "agencies":
        save_agencies(sys.argv[2])
    elif len(sys.argv) == 3 and sys.argv[1] == "report":
        report(sys.argv[2])
    else:
        sys.exit("usage: coverage_report.py agencies <region> | report <targets.json>")
