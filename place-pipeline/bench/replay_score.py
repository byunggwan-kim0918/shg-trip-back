"""
정책별 일정 재생 결과(DraftEvalRunner의 draft-itineraries.jsonl) 비교표. 같은 기록 초안을 정책마다 다시 지은 결과라
차이는 장소 집합에서만 온다.

실행: python bench/replay_score.py build/replay/out/base build/replay/out/budget_50000_55_40_5 ...
"""
import json
import os
import sys
from collections import Counter

MEAL_PREFIX = "Dining and Drinking"
LODGING_PREFIX = "Travel and Transportation > Lodging"
WATCHED = ("WANT_FOOD_MISSING", "LUNCH_MISSING", "DINNER_MISSING", "PACE_SHORT", "LONG_GAP", "WANT_NOT_FOUND", "WANT_MISSING",
           "NO_NON_FOOD_ACTIVITY", "DUPLICATE", "EARLY_FINISH", "LONG_LEG", "DAY_DISTANCE_OVER_CAP")
HARD = {"DAY_MISSING", "CLOSED_DAY", "OUTSIDE_HOURS", "DEPARTURE_MISSED", "WANT_MISSING", "WANT_TIME", "CITY_DAYS",
        "AVOID", "UNREACHABLE", "TRAVEL_MISSING", "NOT_ENDING_AT_LODGING", "REQUIRED_NOT_FOUND", "REQUIRED_NO_SLOT"}


def score(directory):
    trips = [json.loads(line) for line in open(os.path.join(directory, "draft-itineraries.jsonl"), encoding="utf-8")
             if line.strip()]
    rules = Counter()
    hard_trips = 0
    days = steps = unlinked = sights = meals = meal_unlinked = lodging = 0
    for t in trips:
        found = [v["rule"] for v in t["violations"]]
        rules.update(found)
        hard_trips += any(r in HARD for r in found)
        days += len({s["day"] for s in t["steps"]})
        for s in t["steps"]:
            category = s.get("category") or ""
            steps += 1
            linked = s.get("placeId") is not None
            unlinked += not linked
            if category.startswith(LODGING_PREFIX):
                lodging += 1
            elif category.startswith(MEAL_PREFIX):
                meals += 1
                meal_unlinked += not linked
            elif linked:
                sights += 1
    out = {"trips": len(trips), "hard_trips": hard_trips, "hard": sum(n for r, n in rules.items() if r in HARD),
           "days": days, "steps": steps, "unlinked_steps": unlinked, "linked_sights_per_day": round(sights / days, 2),
           "meals": meals, "meal_unlinked": meal_unlinked, "lodging_steps": lodging}
    out.update({r.lower(): rules.get(r, 0) for r in WATCHED})
    return out


def main():
    results = {os.path.basename(d.rstrip("/")): score(d) for d in sys.argv[1:]}
    keys = list(next(iter(results.values())).keys())
    print("| 정책 | " + " | ".join(keys) + " |")
    print("|" + "---|" * (len(keys) + 1))
    for name, r in results.items():
        print(f"| {name} | " + " | ".join(str(r[k]) for k in keys) + " |")
    with open(os.path.join(os.path.dirname(sys.argv[1].rstrip("/")), "replay-score.json"), "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()


def intent(directories, drafts_dir, gold_tsv, container="shg-budget-replay"):
    """초안이 이름으로 고른 장소(정답 표에 있는 것)가 일정에 들어간 비율, 관광 스텝 가운데 Wikidata 항목이 있는 비율,
    기준(첫 디렉터리) 일정과 장소가 겹치는 비율."""
    import csv
    import glob
    import re
    import subprocess
    norm = lambda s: re.sub(r"[\s\W_]+", "", (s or "").lower())
    gold = {}
    with open(gold_tsv, encoding="utf-8") as f:
        for r in csv.DictReader((line for line in f if not line.startswith("#")), delimiter="\t"):
            if r["kind"] == "place":
                gold[norm(r["ko"])] = [norm(j) for j in r["ja"].split("|")]
    wanted = {}
    for path in glob.glob(os.path.join(drafts_dir, "*.json")):
        d = json.load(open(path, encoding="utf-8"))
        names = [it["name"] for day in d["plan"]["days"] for it in (day.get("items") or []) if it.get("place_named")]
        wanted[d["id"]] = [gold[norm(n)] for n in names if norm(n) in gold]
    famous_ids = set(subprocess.run(["docker", "exec", container, "psql", "-U", "weShg", "-d", "trip", "-At", "-c",
                                     "SELECT id FROM places WHERE wikidata_id IS NOT NULL"],
                                    check=True, capture_output=True, text=True).stdout.split())
    base_ids = None
    out = {}
    for directory in directories:
        trips = {}
        for line in open(os.path.join(directory, "draft-itineraries.jsonl"), encoding="utf-8"):
            if line.strip():
                t = json.loads(line)
                trips[t["id"]] = t
        hit = total = sights = famous = 0
        ids = {}
        for tid, t in trips.items():
            names = [norm(s["name"]) for s in t["steps"]]
            for alts in wanted.get(tid, []):
                total += 1
                hit += any(a and any(a in n or (len(n) >= 3 and n in a) for n in names) for a in alts)
            for s in t["steps"]:
                c = s.get("category") or ""
                if s.get("placeId") and not c.startswith(MEAL_PREFIX) and not c.startswith(LODGING_PREFIX) \
                        and not c.startswith("Travel and Transportation"):
                    sights += 1
                    famous += str(s["placeId"]) in famous_ids
            ids[tid] = {s["placeId"] for s in t["steps"] if s.get("placeId")}
        if base_ids is None:
            base_ids = ids
        overlap = [len(ids[k] & base_ids.get(k, set())) / max(1, len(base_ids.get(k, set()))) for k in ids]
        out[os.path.basename(directory.rstrip("/"))] = {
            "named_in_trip": round(hit / total, 3) if total else None, "named_total": total,
            "sights_with_wikidata": round(famous / sights, 3) if sights else None,
            "same_as_base": round(sum(overlap) / len(overlap), 3) if overlap else None}
    return out
