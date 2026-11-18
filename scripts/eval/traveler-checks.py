"""여행자가 보고 바로 문제라고 느끼는 결함을 일정마다 센다. LLM 없이 일정 스텝과 후보 사실만 본다.

python3 -I scripts/eval/traveler-checks.py --traces a.jsonl,b.jsonl [--dump DIR] [--ids 1,2] [--examples 3]
--traces  EvalRunner files 모드가 읽는 생성 기록(후보 좌표·종류·영업시간). --dump가 없으면 기록의 저장 스텝을 본다.
--dump    재실행 덤프 디렉터리(steps-<id>.txt의 "== 재실행" 줄). 국내 기록만 센다.

같은 곳 쪼개기: 같은 날 0.5km 안 두 볼거리 사이에 1km 넘게 떨어진 곳을 들렀다 온다. 저녁에 돌아오는 야경·야시장은 뺀다.
어두운 야외: 야외 명소가 그날 해 진 뒤 30분 넘어 끝난다. 저녁 의도·밤 시간대·야경·일몰 장소는 뺀다.
볼 것 없는 곳: 학교·도서관·경기장·전시장·낚시터·기념품점 같은 곳을 관광으로 넣었다.
관광 없는 날: 점심과 저녁 시간을 모두 걸치는데 식사·카페·숙소·이동 말고 간 곳이 없다.
긴 공백: 스텝 사이가 150분 넘게 빈다.
늦은 종료: 그날 마지막 스텝이 22:30 넘어 끝난다.
같은 음식 3번: 같은 음식 종류(cuisine)가 여행에서 3끼 이상이다.
개점 전 쇼핑: 영업시간을 모르는 백화점·쇼핑몰·상점을 10시 전에 시작한다(시장 제외).
카페 2곳 이상: 하루에 카페·베이커리·디저트가 2곳 이상이다. 판정 규칙(CAFE_PER_DAY_CAP, 3곳부터)보다 엄격한 여행자 기준이다.
끼니 누락: 점심·저녁 시간을 걸치는 날에 그 시간 식사가 없다.
영업 안 하는 때 방문: 영업시간을 아는 곳을 닫힌 시각·휴무일에 간다.
요청 장소 누락: 사용자가 고르거나 요청 의도로 정한 장소가 일정에 없다.
도보 과다: 도보 여행의 하루 직선 이동이 12km를 넘는다(실제 걷는 거리는 1.3배 안팎).
되돌아가는 동선: 하루 이동 합이 가장 먼 곳까지 왕복의 2.5배를 넘는다.
식사 원정: 앞뒤 장소 사이 동선에서 식당 때문에 도보 3km·차 15km 넘게 돌아간다.
이동이 하루 절반: 6시간 넘는 날에 머무는 시간보다 이동·대기가 길다.
관광 수 페이스 밖: 도착·출발이 없는 온전한 날의 관광 수가 페이스 범위보다 적거나 한 곳 넘게 많다.
이른 시작: 첫 볼거리가 8:30 전에 시작한다.
권역 여러 곳: 하루 볼거리가 서로 떨어진 권역 셋 이상에 흩어져 있다(권역 반경 도보 3km·차 15km·그 밖 8km).
동행에 비해 과함: 부모님 동반은 하루 관광 4곳 이상이거나 9시간을 넘고, 아이 동반은 관광 3곳 이상이다(--requests가 있을 때).
출발 여유 없음: 출발 시각이 있는 날 마지막 볼거리가 출발 2시간 전보다 늦게 끝난다.

--requests  실생성 요청과 결과(cases.json:results.json). 동행·출발 시각을 일정과 짝짓는다.
"""
import argparse
import datetime
import json
import math
import re
from collections import defaultdict
from pathlib import Path

SAME_SPOT_KM = 0.5
DETOUR_KM = 1.0
GAP_MIN = 150
LATE_END = 22 * 60 + 30
DARK_MARGIN = 30
OUTDOOR = re.compile(r"Park|Beach|Mountain|Hiking|Trail|Forest|Island|Waterfall|Garden|Nature|Volcano|"
                     r"Historic and Protected Site|Temple|Lake|River|Reservoir|Farm|Field")
NIGHT_NAME = re.compile(r"야경|야시장|불빛|조명|나이트|night|Night|별빛|빛축제|분수")
LOW_VALUE = re.compile(r"학교|도서관|경기장|운동장|체육관|벡스코|BEXCO|낚시|기념품|College|University|School|Library|"
                       r"Stadium|Convention|Exhibition Center|Fishing|Souvenir|Gift Shop|Office Building|City Hall")
# 이름에 경기장이 든 항구·사적지·박물관은 볼거리다.
NOT_LOW = re.compile(r"Harbor|Marina|Historic|Museum|Monument|Zoo|Park")
CAFE = re.compile(r"Cafe|Coffee|Bakery|Dessert|Donut|Tea House|Gelato|Ice Cream|Juice")
NOT_VISIT = re.compile(r"Dining and Drinking|Lodging|Transport Hub|Travel and Transportation")
SHOP = re.compile(r"Department Store|Shopping Mall|Shopping Plaza|Outlet|Clothing|Boutique|Electronics")
LINE = re.compile(r"d(\d+) (\d\d):(\d\d)-(\d\d):(\d\d) (.*?) \| (.*?) \|")
CHECKS = ["같은 곳 쪼개기", "어두운 야외", "볼 것 없는 곳", "관광 없는 날", "긴 공백", "늦은 종료", "같은 음식 3번",
          "개점 전 쇼핑", "카페 2곳 이상", "끼니 누락", "영업 안 하는 때 방문", "요청 장소 누락", "도보 과다",
          "되돌아가는 동선", "식사 원정", "이동이 하루 절반", "관광 수 페이스 밖", "이른 시작", "권역 여러 곳",
          "동행에 비해 과함", "출발 여유 없음"]
AREA_KM = {"walk": 3.0, "car": 15.0, "any": 8.0}
LUNCH = (11 * 60 + 30, 13 * 60 + 30)
DINNER = (17 * 60 + 30, 19 * 60 + 30)
WALK_DAY_KM = {"walk": 12.0}
SIGHTS = {"relaxed": (1, 2), "normal": (2, 3), "tight": (3, 5)}
DAYS = ["월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일"]
TIME_RANGE = re.compile(r"(\d{1,2}):(\d{2})\s*~\s*(\d{1,2}):(\d{2})")


def open_ranges(hours, weekday):
    """"요일: HH:MM ~ HH:MM" 형식에서 그 요일 영업 구간(분). 휴무면 [], 알 수 없으면 None."""
    if not hours:
        return None
    for part in hours.split(","):
        part = part.strip()
        if part.startswith(DAYS[weekday]):
            if "휴무" in part:
                return []
            out = []
            for m in TIME_RANGE.finditer(part):
                a, b = int(m.group(1)) * 60 + int(m.group(2)), int(m.group(3)) * 60 + int(m.group(4))
                out.append((a, b if b > a else b + 1440))
            return out or None
    return None


def km(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * 6371 * math.asin(math.sqrt(h))


def sunset_minutes(date, lat, lng):
    """NOAA 근사식. 한국 시간(UTC+9) 분."""
    n = date.timetuple().tm_yday
    g = 2 * math.pi / 365 * (n - 1)
    eq = 229.18 * (0.000075 + 0.001868 * math.cos(g) - 0.032077 * math.sin(g) - 0.014615 * math.cos(2 * g)
                   - 0.040849 * math.sin(2 * g))
    decl = (0.006918 - 0.399912 * math.cos(g) + 0.070257 * math.sin(g) - 0.006758 * math.cos(2 * g)
            + 0.000907 * math.sin(2 * g) - 0.002697 * math.cos(3 * g) + 0.00148 * math.sin(3 * g))
    ha = math.degrees(math.acos(math.cos(math.radians(90.833)) / (math.cos(math.radians(lat)) * math.cos(decl))
                                - math.tan(math.radians(lat)) * math.tan(decl)))
    return 720 - 4 * (lng - ha) - eq + 9 * 60


def load_traces(paths):
    out = {}
    for p in paths:
        for line in open(p, encoding="utf-8"):
            d = json.loads(line)
            out.setdefault(str(d["itineraryId"]), d)
    return out


def steps_from_dump(path):
    text = Path(path).read_text(encoding="utf-8").split("== 재실행")[-1]
    steps = []
    for line in text.strip().splitlines():
        m = LINE.match(line)
        if m:
            steps.append(dict(day=int(m.group(1)), start=int(m.group(2)) * 60 + int(m.group(3)),
                              end=int(m.group(4)) * 60 + int(m.group(5)), name=m.group(6), category=m.group(7)))
    return steps


def steps_from_trace(d):
    steps = []
    for s in d.get("savedSteps") or []:
        p = s.get("place") or {}
        st, en = s.get("startTime"), s.get("endTime")
        if not st or not en:
            continue
        steps.append(dict(day=s["dayNumber"], start=int(st[:2]) * 60 + int(st[3:5]),
                          end=int(en[:2]) * 60 + int(en[3:5]), name=p.get("name") or "", category=p.get("category") or ""))
    return steps


def areas(points, radius):
    """반경 안에서 서로 이어지는 점끼리 한 권역으로 묶어 권역 수를 센다."""
    left, count = list(points), 0
    while left:
        group, count = [left.pop()], count + 1
        while True:
            near = [p for p in left if any(km(p, g) <= radius for g in group)]
            if not near:
                break
            for p in near:
                left.remove(p)
            group += near
    return count


def check(d, steps, request=None):
    by_name = {}
    for c in d.get("candidates") or []:
        for key in (c.get("name"), c.get("nameKo")):
            if key:
                by_name.setdefault(key, c)
    start = datetime.date.fromisoformat(d["startDate"])
    found = defaultdict(list)
    days = defaultdict(list)
    for s in sorted(steps, key=lambda s: (s["day"], s["start"])):
        days[s["day"]].append(s)

    def coords(s):
        c = by_name.get(s["name"])
        return (c["latitude"], c["longitude"]) if c and c.get("latitude") is not None else None

    def night_spot(s):
        c = by_name.get(s["name"]) or {}
        tags = " ".join(c.get("tags") or [])
        return (NIGHT_NAME.search(s["name"]) or c.get("intentTime") == "evening"
                or "night" in (c.get("recommendedTimeSlots") or []) or re.search(r"야경|일몰|노을", tags))

    def visit(s):
        return not NOT_VISIT.search(s["category"]) and not CAFE.search(s["category"])

    meal_kinds = defaultdict(list)
    for day, ds in days.items():
        tag = f"{day}일차"
        visits = [s for s in ds if visit(s)]
        for i, a in enumerate(ds):
            for j in range(i + 2, len(ds)):
                b = ds[j]
                if not (visit(a) and visit(b)) or coords(a) is None or coords(b) is None:
                    continue
                # 낮에 본 곳 근처 야경·야시장으로 저녁에 돌아오는 것은 하루 흐름이다.
                if km(coords(a), coords(b)) > SAME_SPOT_KM or night_spot(b) or b["start"] >= 18 * 60:
                    continue
                between = [x for x in ds[i + 1:j] if coords(x) is not None]
                if any(km(coords(a), coords(x)) > DETOUR_KM for x in between):
                    found["같은 곳 쪼개기"].append(f"{tag} {a['name']} → {between[0]['name']} → {b['name']}")
        for s in visits:
            if not OUTDOOR.search(s["category"]) or night_spot(s):
                continue
            xy = coords(s)
            if xy is None:
                continue
            sunset = sunset_minutes(start + datetime.timedelta(days=day - 1), xy[0], xy[1])
            if s["end"] > sunset + DARK_MARGIN:
                found["어두운 야외"].append(f"{tag} {s['name']} ~{s['end'] // 60}:{s['end'] % 60:02d}"
                                          f"(일몰 {int(sunset) // 60}:{int(sunset) % 60:02d})")
        for s in visits:
            if (LOW_VALUE.search(s["name"]) or LOW_VALUE.search(s["category"])) and not NOT_LOW.search(s["category"]):
                found["볼 것 없는 곳"].append(f"{tag} {s['name']}")
        if ds and ds[0]["start"] <= 11 * 60 + 30 and ds[-1]["end"] >= 17 * 60 + 30 and not visits:
            found["관광 없는 날"].append(f"{tag} " + ", ".join(s["name"] for s in ds))
        for a, b in zip(ds, ds[1:]):
            if b["start"] - a["end"] > GAP_MIN:
                found["긴 공백"].append(f"{tag} {a['name']} {a['end'] // 60}:{a['end'] % 60:02d} → "
                                       f"{b['name']} {b['start'] // 60}:{b['start'] % 60:02d}")
        if ds and ds[-1]["end"] > LATE_END:
            found["늦은 종료"].append(f"{tag} {ds[-1]['name']} {ds[-1]['end'] // 60}:{ds[-1]['end'] % 60:02d}")
        for s in ds:
            c = by_name.get(s["name"]) or {}
            if SHOP.search(s["category"]) and not c.get("openingHours") and s["start"] < 10 * 60:
                found["개점 전 쇼핑"].append(f"{tag} {s['name']} {s['start'] // 60}:{s['start'] % 60:02d}")
            if "Dining and Drinking" in s["category"] and not CAFE.search(s["category"]) and c.get("cuisine"):
                meal_kinds[c["cuisine"]].append(f"{tag} {s['name']}")
        cafes = [s["name"] for s in ds if CAFE.search(s["category"])]
        if len(cafes) >= 2:
            found["카페 2곳 이상"].append(f"{tag} " + ", ".join(cafes))
    args = d.get("scheduleArgs") or {}
    transport, pace = args.get("transportPref") or "any", args.get("pace") or "normal"
    sel = d.get("selection") or {}
    cands = d.get("candidates") or []
    names_in_plan = {s["name"] for s in steps}
    wanted = [c for c in cands if c.get("userSelected") or c.get("intentProtected")]
    wanted += [cands[i - 1] for i in (sel.get("highlightIndices") or []) if 0 < i <= len(cands)]
    for c in {c["name"]: c for c in wanted}.values():
        if "Lodging" in (c.get("category") or "") or "Transport" in (c.get("category") or ""):
            continue
        if c["name"] not in names_in_plan and (c.get("nameKo") or "") not in names_in_plan:
            found["요청 장소 누락"].append(c.get("nameKo") or c["name"])
    last_day = max(days) if days else 0
    for day, ds in days.items():
        tag = f"{day}일차"
        weekday = (start + datetime.timedelta(days=day - 1)).weekday()
        meals = [s for s in ds if "Dining and Drinking" in s["category"] and not CAFE.search(s["category"])]
        span = (ds[0]["start"], ds[-1]["end"]) if ds else (0, 0)
        for name, (a, b) in (("점심", LUNCH), ("저녁", DINNER)):
            if span[0] <= a and span[1] >= b and not any(a - 30 <= m["start"] <= b for m in meals):
                found["끼니 누락"].append(f"{tag} {name}")
        for s in ds:
            c = by_name.get(s["name"]) or {}
            if "Lodging" in s["category"] or "Transport" in s["category"]:
                continue
            ranges = open_ranges(c.get("openingHours"), weekday)
            if ranges is not None and not any(a <= s["start"] and s["end"] <= b for a, b in ranges):
                found["영업 안 하는 때 방문"].append(f"{tag} {s['name']} {s['start'] // 60}:{s['start'] % 60:02d}"
                                               + (" (휴무)" if ranges == [] else ""))
        pts = [coords(s) for s in ds if coords(s) is not None]
        legs = [km(a, b) for a, b in zip(pts, pts[1:])]
        if transport in WALK_DAY_KM and sum(legs) > WALK_DAY_KM[transport]:
            found["도보 과다"].append(f"{tag} 직선 {sum(legs):.1f}km")
        if len(pts) >= 4:
            straight = km(pts[0], pts[-1])
            far = max(km(pts[0], p) for p in pts)
            if sum(legs) > 2.5 * max(far * 2, straight, 3.0):
                found["되돌아가는 동선"].append(f"{tag} 이동 {sum(legs):.1f}km, 가장 먼 곳 {far:.1f}km")
        for i, m in enumerate(ds):
            if m not in meals or i == 0 or i == len(ds) - 1:
                continue
            a, b, x = coords(ds[i - 1]), coords(ds[i + 1]), coords(m)
            if a and b and x:
                detour = km(a, x) + km(x, b) - km(a, b)
                limit = 3.0 if transport == "walk" else 15.0
                if detour > limit:
                    found["식사 원정"].append(f"{tag} {m['name']} 우회 {detour:.1f}km")
        busy = sum(s["end"] - s["start"] for s in ds)
        if ds and span[1] - span[0] > 360 and (span[1] - span[0] - busy) > (span[1] - span[0]) / 2:
            found["이동이 하루 절반"].append(f"{tag} 머문 {busy}분 / 하루 {span[1] - span[0]}분")
        n_sights = len([s for s in ds if visit(s)])
        lo, hi = SIGHTS.get(pace, (2, 3))
        full_day = ds and span[0] <= 10 * 60 and span[1] >= 18 * 60 and day not in (1, last_day)
        if full_day and (n_sights < lo or n_sights > hi + 1):
            found["관광 수 페이스 밖"].append(f"{tag} {pace} 관광 {n_sights}곳")
        first = next((s for s in ds if visit(s)), None)
        if first and first["start"] < 8 * 60 + 30:
            found["이른 시작"].append(f"{tag} {first['name']} {first['start'] // 60}:{first['start'] % 60:02d}")
    companion = (request or {}).get("companion")
    departure = (request or {}).get("departureTime") or args.get("departureTime")
    for day, ds in days.items():
        tag = f"{day}일차"
        sights = [s for s in ds if visit(s)]
        pts = [coords(s) for s in sights if coords(s) is not None]
        if areas(pts, AREA_KM.get(transport, 8.0)) >= 3:
            found["권역 여러 곳"].append(f"{tag} 권역 {areas(pts, AREA_KM.get(transport, 8.0))}곳")
        span = ds[-1]["end"] - ds[0]["start"] if ds else 0
        if companion == "parents" and (len(sights) >= 4 or span > 9 * 60):
            found["동행에 비해 과함"].append(f"{tag} 부모님 관광 {len(sights)}곳, {span // 60}시간")
        if companion == "family" and len(sights) >= 3:
            found["동행에 비해 과함"].append(f"{tag} 아이 동반 관광 {len(sights)}곳")
        if departure and day == last_day and sights:
            deadline = int(departure[:2]) * 60 + int(departure[3:5]) - 120
            if sights[-1]["end"] > deadline:
                found["출발 여유 없음"].append(f"{tag} {sights[-1]['name']} ~{sights[-1]['end'] // 60}:"
                                          f"{sights[-1]['end'] % 60:02d}, 출발 {departure}")
    for kind, places in meal_kinds.items():
        if len(places) >= 3:
            found["같은 음식 3번"].append(f"{kind} {len(places)}끼: " + ", ".join(places))
    return found


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--traces", required=True)
    ap.add_argument("--dump")
    ap.add_argument("--ids")
    ap.add_argument("--examples", type=int, default=3)
    ap.add_argument("--requests")
    a = ap.parse_args()
    traces = load_traces(a.traces.split(","))
    requests = {}
    if a.requests:
        for pair in a.requests.split(","):
            cases_path, results_path = pair.split(":")
            cases = {c["id"]: c for c in json.load(open(cases_path, encoding="utf-8"))}
            for cid, r in json.load(open(results_path, encoding="utf-8")).items():
                iid = (r.get("complete") or {}).get("itineraryId")
                if iid is not None and cid in cases:
                    requests[str(iid)] = dict(cases[cid], case=cid)
    ids = a.ids.split(",") if a.ids else list(traces)
    per = {c: [] for c in CHECKS}
    total = 0
    for i in ids:
        d = traces.get(i)
        if d is None or (d.get("scheduleArgs") or {}).get("country") not in (None, "KR"):
            continue
        if a.dump:
            p = Path(a.dump) / f"steps-{i}.txt"
            if not p.exists():
                continue
            steps = steps_from_dump(p)
        else:
            steps = steps_from_trace(d)
        if not steps:
            continue
        total += 1
        for c, hits in check(d, steps, requests.get(i)).items():
            per[c].append((i, hits))
    print(f"일정 {total}건")
    print("| 결함 | 일정 수 | 건수 | 예 |")
    print("|---|---|---|---|")
    for c in CHECKS:
        rows = per[c]
        examples = "; ".join(f"[{i}] {h[0]}" for i, h in rows[:a.examples])
        print(f"| {c} | {len(rows)} | {sum(len(h) for _, h in rows)} | {examples} |")


if __name__ == "__main__":
    main()
