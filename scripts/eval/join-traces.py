"""도시가 다른 생성 기록 두 건을 이어 붙여 여러 도시 여행의 replay 입력을 만든다.

python3 -I scripts/eval/join-traces.py traces.jsonl 19+23 16+32 24+33:near > joined.jsonl
결과는 EvalRunner --eval.mode=files가 읽는 TraceFile JSON 줄이다.

A+B는 A 도시를 먼저 돌고 B 도시로 옮기는 여행이다(도시 구간이 나뉜다).
A+B:near는 하루에 오갈 수 있는 두 지역을 한 풀로 합친 여행이다(도시 구간이 없다).

도시마다 따로 고른 선택 결과를 그대로 쓰는 근사다. 앞 도시의 마지막 날은 여행의 마지막 날로 골라져 가볍고,
저장본이 없어 savedSteps는 비어 있다. 두 기록의 pace와 이동수단이 같아야 하고 같은 장소가 양쪽에 있으면 안 된다.
"""
import collections
import datetime
import json
import statistics
import sys


def day_count(trace):
    start = datetime.date.fromisoformat(trace["startDate"])
    return (datetime.date.fromisoformat(trace["endDate"]) - start).days + 1


def shifted(indices, offset):
    return [i + offset for i in indices or [] if i is not None]


def shift_selection(selection, index_offset, day_offset):
    days = []
    for d in selection.get("days") or []:
        days.append({
            "dayNumber": d["dayNumber"] + day_offset,
            "placeIndices": shifted(d.get("placeIndices"), index_offset),
            "accommodationIndex": None if d.get("accommodationIndex") is None
            else d["accommodationIndex"] + index_offset,
            "arrivalHubIndex": None if d.get("arrivalHubIndex") is None else d["arrivalHubIndex"] + index_offset,
            "departureHubIndex": None if d.get("departureHubIndex") is None
            else d["departureHubIndex"] + index_offset,
        })
    return {
        "days": sorted(days, key=lambda d: d["dayNumber"]),
        "pairs": [shifted(p, index_offset) for p in selection.get("pairs") or []],
        "spareIndices": shifted(selection.get("spareIndices"), index_offset),
        "highlightIndices": shifted(selection.get("highlightIndices"), index_offset),
        "restIndices": shifted(selection.get("restIndices"), index_offset),
        "timing": [{"index": t["index"] + index_offset, "when": t["when"]} for t in selection.get("timing") or []],
    }


def city_of(trace):
    """구간을 나누는 기준점. 이상치 좌표에 끌려가지 않게 후보 좌표의 중앙값을 쓴다."""
    located = [c for c in trace["candidates"] if c.get("latitude") and c.get("longitude")]
    region = collections.Counter(c.get("region") for c in located).most_common(1)[0][0]
    if not region:
        raise SystemExit(f"{trace['itineraryId']}: 후보에 region이 없다")
    return {
        "name": region,
        "nameKo": trace["destination"],
        "country": trace["scheduleArgs"].get("country"),
        "lat": statistics.median(c["latitude"] for c in located),
        "lng": statistics.median(c["longitude"] for c in located),
    }


def merged(first, second):
    return list(dict.fromkeys((first or []) + (second or [])))


def join(a, b, near):
    args_a, args_b = a["scheduleArgs"], b["scheduleArgs"]
    for key in ("pace", "transportPref", "country"):
        if args_a.get(key) != args_b.get(key):
            raise SystemExit(f"{a['itineraryId']}+{b['itineraryId']}: {key}가 다르다")

    # 후보는 번호가 아니라 목록 위치로 찾는다.
    for trace in (a, b):
        if [c["index"] for c in trace["candidates"]] != list(range(1, len(trace["candidates"]) + 1)):
            raise SystemExit(f"{trace['itineraryId']}: 후보 번호가 1부터 이어지지 않는다")
    if {c.get("placeId") for c in a["candidates"]} & {c.get("placeId") for c in b["candidates"]} - {None}:
        raise SystemExit(f"{a['itineraryId']}+{b['itineraryId']}: 같은 장소가 양쪽 후보에 있다")

    days_a, days_b = day_count(a), day_count(b)
    offset = len(a["candidates"])
    candidates = a["candidates"] + [dict(c, index=c["index"] + offset) for c in b["candidates"]]

    sel_a = shift_selection(a["selection"], 0, 0)
    sel_b = shift_selection(b["selection"], offset, days_a)
    # 여행 중간 날에 출발·도착 거점이 남으면 그날이 역에서 끝나고 역에서 시작한다.
    if sel_a["days"]:
        sel_a["days"][-1]["departureHubIndex"] = None
    if sel_b["days"]:
        sel_b["days"][0]["arrivalHubIndex"] = None
    selection = {key: sel_a[key] + sel_b[key] for key in sel_a}
    selection["concept"] = f"{a['selection'].get('concept', '')} / {b['selection'].get('concept', '')}"

    cities = None
    if not near:
        city_a, city_b = city_of(a), city_of(b)
        if city_a["name"] == city_b["name"]:
            raise SystemExit(f"{a['itineraryId']}+{b['itineraryId']}: 같은 지역이다")
        day_city = {str(d): city_a["name"] for d in range(1, days_a + 1)}
        day_city.update({str(d): city_b["name"] for d in range(days_a + 1, days_a + days_b + 1)})
        cities = {"cities": [city_a, city_b], "dayCity": day_city}

    hub_a, hub_b = args_a.get("hub") or {}, args_b.get("hub") or {}
    hub = None
    if hub_a.get("arrivalHub") or hub_b.get("departureHub"):
        hub = {"hubType": hub_a.get("hubType") or hub_b.get("hubType"),
               "arrivalHub": hub_a.get("arrivalHub"), "departureHub": hub_b.get("departureHub")}

    start = datetime.date.fromisoformat(a["startDate"])
    if b["itineraryId"] >= 100000:
        raise SystemExit(f"{b['itineraryId']}: 이어 붙인 번호를 만들 수 없다")
    return {
        "joined": True,
        "itineraryId": a["itineraryId"] * 100000 + b["itineraryId"],
        "destination": f"{a['destination']} {b['destination']}",
        "startDate": a["startDate"],
        "endDate": (start + datetime.timedelta(days=days_a + days_b - 1)).isoformat(),
        "savedSteps": [],
        "candidates": candidates,
        "selection": selection,
        "scheduleArgs": dict(
            args_a,
            hub=hub,
            themes=merged(args_a.get("themes"), args_b.get("themes")),
            categories=merged(args_a.get("categories"), args_b.get("categories")),
            compact=bool(args_a.get("compact") or args_b.get("compact")),
            departureTime=args_b.get("departureTime"),
            cities=cities,
            resumesAfterTransfer=False,
            continuesAfter=False,
        ),
    }


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    traces = {}
    with open(sys.argv[1], encoding="utf-8") as f:
        for line in f:
            if line.strip():
                trace = json.loads(line)
                traces[trace["itineraryId"]] = trace
    for spec in sys.argv[2:]:
        pair, _, mode = spec.partition(":")
        first, second = (int(x) for x in pair.split("+"))
        print(json.dumps(join(traces[first], traces[second], mode == "near"), ensure_ascii=False))


if __name__ == "__main__":
    main()
