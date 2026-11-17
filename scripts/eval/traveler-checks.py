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
          "개점 전 쇼핑", "카페 2곳 이상"]


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


def check(d, steps):
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
    a = ap.parse_args()
    traces = load_traces(a.traces.split(","))
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
        for c, hits in check(d, steps).items():
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
