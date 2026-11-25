"""
일본 생성 테스트(jp15) 재생 결과 비교: 요구 장소·음식 반영, HARD, 짓기 시간, 요구 장소에 연결된 실제 장소 목록.

실행: python bench/jp15_score.py build/jp/out/base build/jp/out/base+catalog ...
"""
import json
import os
import statistics
import sys
from collections import Counter

HARD = {"DAY_MISSING", "CLOSED_DAY", "OUTSIDE_HOURS", "DEPARTURE_MISSED", "WANT_MISSING", "WANT_TIME", "CITY_DAYS",
        "AVOID", "UNREACHABLE", "TRAVEL_MISSING", "NOT_ENDING_AT_LODGING", "REQUIRED_NOT_FOUND", "REQUIRED_NO_SLOT"}
WANT_RULES = ("WANT_NOT_FOUND", "WANT_FOOD_MISSING", "WANT_MISSING", "REQUIRED_NOT_FOUND", "REQUIRED_NO_SLOT")


def score(directory):
    trips = [json.loads(line) for line in open(os.path.join(directory, "draft-itineraries.jsonl"), encoding="utf-8")
             if line.strip()]
    rules = Counter(v["rule"] for t in trips for v in t["violations"])
    ms = sorted(t["ms"] for t in trips)
    missing = sorted((t["id"], v["rule"], v["message"]) for t in trips for v in t["violations"] if v["rule"] in WANT_RULES)
    return {"trips": len(trips), "hard_trips": sum(any(v["rule"] in HARD for v in t["violations"]) for t in trips),
            "hard": sum(n for r, n in rules.items() if r in HARD),
            **{r.lower(): rules.get(r, 0) for r in WANT_RULES},
            "ms_median": int(statistics.median(ms)), "ms_p95": ms[max(0, int(len(ms) * 0.95) - 1)],
            "_missing": missing}


def main():
    results = {os.path.basename(d.rstrip("/")): score(d) for d in sys.argv[1:]}
    keys = [k for k in next(iter(results.values())) if not k.startswith("_")]
    print("| 실행 | " + " | ".join(keys) + " |")
    print("|" + "---|" * (len(keys) + 1))
    for name, r in results.items():
        print(f"| {name} | " + " | ".join(str(r[k]) for k in keys) + " |")
    names = list(results)
    if len(names) >= 2:
        before, after = set(results[names[0]]["_missing"]), set(results[names[-1]]["_missing"])
        print(f"\n{names[0]} → {names[-1]}: 해결 {len(before - after)}, 새로 생김 {len(after - before)}")
        for m in sorted(before - after):
            print("  해결", *m)
        for m in sorted(after - before):
            print("  새로", *m)


if __name__ == "__main__":
    main()
