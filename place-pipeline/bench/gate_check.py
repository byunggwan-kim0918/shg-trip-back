"""
생성 요청마다 장소 부족 관문을 넘는지 미리 본다. 생성기(TripFeasibility.enoughPlaceData)와 같은 기준이다:
날마다 관광(SIGHT·ACTIVITY) 1곳, 끼니(MEAL) 2곳, 숙소(LODGING) 1곳 이상. 여행지와 그 하위 여행지(parent)의 행을 센다.
LLM 호출 없음.

실행: python bench/gate_check.py --requests ../../eval-records/jp15/requests.jsonl --database trip_jp
"""
import argparse
import datetime as dt
import json
import os
import subprocess

CONTAINER = "shg-budget-replay"
DESTINATIONS = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main", "resources", "destinations.json")


def regions_of(name_ko, items):
    """한국어 여행지 이름 → 그 여행지와 하위 여행지의 영어 이름(DB region)."""
    root = next((d for d in items if d["nameKo"] == name_ko), None)
    if root is None:
        return []
    return [root["name"]] + [d["name"] for d in items if d.get("parent") == root["name"]]


def role_counts(database):
    out = subprocess.run(["docker", "exec", CONTAINER, "psql", "-U", "weShg", "-d", database, "-At", "-F", "\t", "-c",
                          "SELECT region, place_role, count(*) FROM places WHERE active AND merged_into_id IS NULL "
                          "AND place_role IS NOT NULL GROUP BY 1, 2"], check=True, capture_output=True, text=True).stdout
    counts = {}
    for line in out.splitlines():
        region, role, n = line.split("\t")
        counts.setdefault(region, {})[role] = int(n)
    return counts


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--requests", required=True)
    parser.add_argument("--database", default="trip_jp")
    args = parser.parse_args()
    with open(DESTINATIONS, encoding="utf-8") as f:
        data = json.load(f)
    items = data if isinstance(data, list) else data["destinations"]
    counts = role_counts(args.database)
    failed = 0
    for line in open(args.requests, encoding="utf-8"):
        row = json.loads(line)
        req = row["request"]
        days = (dt.date.fromisoformat(req["endDate"]) - dt.date.fromisoformat(req["startDate"])).days + 1
        roles = {}
        for city in req["destination"].split(", "):
            for region in regions_of(city, items):
                for role, n in counts.get(region, {}).items():
                    roles[role] = roles.get(role, 0) + n
        sights = roles.get("SIGHT", 0) + roles.get("ACTIVITY", 0)
        ok = sights >= days and roles.get("MEAL", 0) >= 2 * days and roles.get("LODGING", 0) >= 1
        failed += not ok
        print(f"{row['id']} {req['destination']:6s} {days}일 관광 {sights:5d} 끼니 {roles.get('MEAL', 0):5d} "
              f"숙소 {roles.get('LODGING', 0):4d} {'통과' if ok else '막힘'}")
    print(f"막힘 {failed}건")


if __name__ == "__main__":
    main()
