#!/usr/bin/env python3
"""기록된 초안을 여러 방식으로 다시 지은 결과(draft-itineraries.jsonl)를 같은 기준으로 견준다.

usage: route-compare.py 이름=<jsonl> 이름=<jsonl> ...

첫 번째가 기준이다. 모두 같은 기록·같은 DB·같은 근처 검색 설정이어야 한다(--eval.offline-embeddings 포함).
"""
import collections
import json
import re
import sys

# 근거 갈래. KTDB는 날짜 없는 평일 대표 시각표라 otp-kr의 미검증 시간표로 나온다.
BUCKETS = ["공식 날짜별 확인", "GTFS 시간표", "KTDB 대표 시간표", "기타 미검증 시간표", "도로망", "엔진 추정(빌린 시간표 등)",
           "거리 추정"]
SUBSTITUTION = re.compile(r"taxi saves|taxi chosen|transit waits|no transit connection")


def load(path):
    with open(path, encoding="utf-8") as f:
        return {r["id"]: r for r in (json.loads(line) for line in f if line.strip())}


def pct(part, whole):
    return f"{part}/{whole} ({100 * part / whole:.1f}%)" if whole else "0/0"


def quantile(values, q):
    if not values:
        return None
    s = sorted(values)
    return s[min(len(s) - 1, max(0, int(round(q * (len(s) - 1)))))]


def minutes(hhmm):
    h, m = hhmm.split(":")
    return int(h) * 60 + int(m)


def bucket(route):
    if not route or route.get("status") != "FOUND":
        return "거리 추정"
    basis, conf = route.get("basis"), route.get("confidence")
    if conf == "OFFICIAL_SCHEDULE":
        return "공식 날짜별 확인"
    if basis in ("GTFS_SCHEDULE", "REALTIME") and conf == "GTFS_SCHEDULE":
        return "GTFS 시간표"
    if basis == "UNVERIFIED_SCHEDULE":
        return "KTDB 대표 시간표" if route.get("provider") == "otp-kr" else "기타 미검증 시간표"
    if basis == "ROAD_NETWORK":
        return "도로망"
    return "엔진 추정(빌린 시간표 등)"


def legs(trip):
    return [s for s in trip["steps"] if s.get("mode")]


def timing_problems(trip):
    """앞 스텝 끝 + 이동 시간보다 일찍 시작하거나, 근거의 출발 시각이 앞 스텝 끝과 다른 구간."""
    bad = 0
    steps = trip["steps"]
    for prev, cur in zip(steps, steps[1:]):
        if prev["day"] != cur["day"] or not cur.get("minutes"):
            continue
        if minutes(cur["start"]) < minutes(prev["end"]) + cur["minutes"]:
            bad += 1
        r = cur.get("route")
        if r and r.get("departAt") and r["departAt"][11:16] != prev["end"]:
            bad += 1
    return bad


SEATS = {"TAXI": 4}
CAR_SEATS = 5
FARE_BASES = ["FREE", "CONFIRMED", "OFFICIAL_TABLE", "PUBLIC_DATA", "ESTIMATED", "UNKNOWN", "근거 없음"]


def fare_checks(trip):
    """요금 근거 갈래와 인원 반영·이중 합산 검사. 비용은 평가 러너가 저장 때처럼 인원을 반영한 값이다."""
    party = trip["request"].get("partySize") or 1
    out = collections.Counter()
    for s in legs(trip):
        fare = (s.get("route") or {}).get("fare")
        if not fare:
            out["근거 없음"] += 1
            continue
        # 도보 무료를 확인 운임으로 적던 기록도 같은 갈래로 센다.
        out["FREE" if s["mode"] == "WALK" and fare.get("amount") == 0 else fare["basis"]] += 1
        seats = SEATS.get(s["mode"], CAR_SEATS)
        expect = -(-party // seats) if fare["per"] == "VEHICLE" else party
        if fare["units"] != expect:
            out["units_wrong"] += 1
        won = fare.get("totalWon")
        cost = s.get("cost")
        if (won is None) != (cost is None) or won is not None and abs(float(won) - float(cost)) > 0.5:
            out["cost_mismatch"] += 1
        if fare.get("currency") and fare["currency"] != "KRW" and won is not None:
            out["converted"] += 1
    return out


def trip_cost(trip):
    places = sum(float(s.get("placeCost") or 0) for s in trip["steps"])
    known = sum(float(s["cost"]) for s in legs(trip) if s.get("cost") is not None)
    unknown = sum(1 for s in legs(trip) if s.get("cost") is None)
    return places + known, unknown


def summarize(trips):
    out = {"trips": len(trips)}
    all_legs = [s for t in trips.values() for s in legs(t)]
    out["legs"] = len(all_legs)
    out["buckets"] = collections.Counter(bucket(s.get("route")) for s in all_legs)
    out["unreachable"] = sum(1 for s in all_legs if (s.get("route") or {}).get("unreachable"))
    out["modes"] = collections.Counter(s["mode"] for s in all_legs)
    subst = collections.Counter()
    for t in trips.values():
        pref = t["request"].get("transportPref") or "any"
        for s in legs(t):
            note = (s.get("route") or {}).get("note") or ""
            if s["mode"] == "TAXI" and SUBSTITUTION.search(note):
                subst["any" if pref in ("any", "taxi") else "restricted"] += 1
    out["subst"] = subst
    wants = sum(t["intent"]["wants"] for t in trips.values())
    met = sum(t["intent"]["wantsMet"] for t in trips.values())
    out["wants"] = (met, wants)
    out["omissions"] = sum(len(t["omissions"]) for t in trips.values())
    out["rules"] = collections.Counter((v["severity"], v["rule"]) for t in trips.values() for v in t["violations"])
    out["severity"] = collections.Counter(v["severity"] for t in trips.values() for v in t["violations"])
    out["timing"] = sum(timing_problems(t) for t in trips.values())
    out["lateLodging"] = sum(1 for t in trips.values() for s in t["steps"]
                             if s.get("role") == "LODGING" and minutes(s["start"]) > 23 * 60)
    out["lodgingSteps"] = sum(1 for t in trips.values() for s in t["steps"] if s.get("role") == "LODGING")
    out["ms"] = (quantile([t["ms"] for t in trips.values()], .5), quantile([t["ms"] for t in trips.values()], .95))
    stats = [t.get("routes") or {} for t in trips.values()]
    calls, hits = collections.Counter(), collections.Counter()
    for s in stats:
        calls.update(s.get("providerCalls", {}))
        hits.update(s.get("cacheHits", {}))
    out["calls"], out["hits"] = calls, hits
    for k in ("memoHits", "taxiFallbacks", "taxiAlternatives", "budgetSkips", "retimes"):
        out[k] = sum(s.get(k, 0) for s in stats)
    routing = [s.get("routingMs", 0) for s in stats if s]
    out["routingMs"] = (quantile(routing, .5), quantile(routing, .95))
    out["gaps"] = [g for s in stats for g in s.get("estimateGaps", [])]
    fares = collections.Counter()
    for t in trips.values():
        fares.update(fare_checks(t))
    out["fares"] = fares
    over = incomplete = complete = 0
    transport = 0.0
    for t in trips.values():
        total, unknown = trip_cost(t)
        transport += sum(float(s["cost"]) for s in legs(t) if s.get("cost") is not None)
        budget = t["request"].get("budget")
        complete += unknown == 0
        if budget is None:
            continue
        if total > float(budget):
            over += 1
        elif unknown:
            incomplete += 1
    out["budgetOver"], out["budgetUnknown"], out["costComplete"] = over, incomplete, complete
    out["transportWon"] = round(transport)
    return out


def report(runs):
    names = list(runs)
    sums = {n: summarize(runs[n]) for n in names}
    def row(label, f):
        print("| " + label + " | " + " | ".join(str(f(sums[n])) for n in names) + " |")
    print("| 지표 | " + " | ".join(names) + " |\n|---|" + "---|" * len(names))
    row("일정 수", lambda a: a["trips"])
    row("이동 구간", lambda a: a["legs"])
    row("실제 경로 반영(거리 추정 아님)", lambda a: pct(a["legs"] - a["buckets"]["거리 추정"], a["legs"]))
    for b in BUCKETS:
        row("  " + b, lambda a, b=b: pct(a["buckets"][b], a["legs"]))
    row("그 시각 갈 수 없음(충돌 표시)", lambda a: a["unreachable"])
    row("수단 조건 있는 여행에서 택시로 바꾼 구간", lambda a: a["subst"]["restricted"])
    row("수단 제한 없는 여행에서 택시로 바꾼 구간", lambda a: a["subst"]["any"])
    row("사용자 요구 반영", lambda a: pct(*a["wants"]))
    row("넣지 못한 요청 장소", lambda a: a["omissions"])
    row("HARD 위반", lambda a: a["severity"]["HARD"])
    row("SOFT 위반", lambda a: a["severity"]["SOFT"])
    row("시각·이동 불일치 구간", lambda a: a["timing"])
    row("숙소 스텝 / 23시 넘어 도착", lambda a: f"{a['lodgingSteps']} / {a['lateLodging']}")
    row("생성 시간 p50/p95(ms)", lambda a: "%s / %s" % a["ms"])
    row("경로 조회 시간 p50/p95(ms)", lambda a: "%s / %s" % a["routingMs"])
    row("공급자 호출", lambda a: sum(a["calls"].values()))
    row("캐시 적중률", lambda a: pct(sum(a["hits"].values()), sum(a["hits"].values()) + sum(a["calls"].values())))
    row("일정 안 같은 구간 재사용", lambda a: a["memoHits"])
    row("시간 상한으로 묻지 못한 구간", lambda a: a["budgetSkips"])
    for b in FARE_BASES:
        row("  요금 " + b, lambda a, b=b: pct(a["fares"][b], a["legs"]))
    row("요금 인원·대수 반영 오류", lambda a: a["fares"]["units_wrong"])
    row("요금과 이동 비용 불일치(이중 합산)", lambda a: a["fares"]["cost_mismatch"])
    row("현지 통화를 원화로 바꾼 구간", lambda a: a["fares"]["converted"])
    row("교통비를 모두 아는 일정", lambda a: pct(a["costComplete"], a["trips"]))
    row("아는 교통비 합(원)", lambda a: f"{a['transportWon']:,}")
    row("예산 초과(아는 금액만으로) / 판단 못 함", lambda a: f"{a['budgetOver']} / {a['budgetUnknown']}")
    print()
    for n in names:
        a = sums[n]
        if a["gaps"]:
            g = a["gaps"]
            print(f"{n}: 거리 추정 구간을 그 시각 실제 경로로 물으면(실제-추정) |차| 중앙값 {quantile([abs(x) for x in g], .5)}분, "
                  f"10분 넘게 김 {pct(sum(1 for x in g if x > 10), len(g))}, 10분 넘게 짧음 {pct(sum(1 for x in g if x < -10), len(g))}")
        if a["calls"]:
            print(f"{n}: 공급자별 호출 {dict(a['calls'])}, 캐시 적중 {dict(a['hits'])}")
    print()
    rules = sorted(set().union(*[sums[n]["rules"].keys() for n in names]))
    print("| 규칙 | " + " | ".join(names) + " |\n|---|" + "---|" * len(names))
    for r in rules:
        print(f"| {r[0]} {r[1]} | " + " | ".join(str(sums[n]["rules"][r]) for n in names) + " |")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    report({a.split("=", 1)[0]: load(a.split("=", 1)[1]) for a in sys.argv[1:]})
