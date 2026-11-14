"""같은 입력을 두 확정 단계로 재실행한 report.csv를 견주고 G1 기준으로 판정한다.

python3 -I scripts/eval/compare-arms.py v1/report.csv v2/report.csv
앞이 기준(A), 뒤가 견줄 쪽(B)이다. 국내 실기록의 재실행 줄만 본다.
전체는 지표마다 기준으로 판정하고, 4일 이상은 일정 수가 적어 일정별 점수가 오른 수와 내린 수로 판정한다.
이른 종료·숙소 수·유명도는 기준 없이 값만 낸다.
기준을 하나라도 못 넘으면 종료 코드가 1이다.
"""
import csv
import math
import statistics
import sys

# 기준은 계획의 G1이다. 나빠지지 않음은 측정 흔들림을 넘는 차이만 본다.
RETENTION_MIN = 0.75
DROPPED_SHARE_MAX = 0.15
BACK_FRONT_MIN = 0.95
UNKNOWN_MEAL_SLACK = 0.03
SCORE_SLACK = 1.0
# 일정 한 건이 오가는 차이는 측정 흔들림으로 본다.
COUNT_SLACK = 1
VISITS_SLACK = 0.1
FLOOR_SLACK = 0.02
GAP_SLACK_MINUTES = 10
SIGHT_FLOOR = {"tight": 3, "normal": 2, "relaxed": 1}


def load(path):
    with open(path, encoding="utf-8") as f:
        return {r["itinerary_id"]: r for r in csv.DictReader(f)
                if r["source"] == "replayed" and r["country"] == "KR" and r["joined"] == "false"}


def count(rows, *rules):
    return sum(1 for r in rows if sum(int(r[rule]) for rule in rules) > 0)


def mean(values):
    values = [v for v in values if v is not None]
    return statistics.mean(values) if values else float("nan")


def per_day(row, column):
    """일차 순서의 값. 빈 날이 있으면 순서가 일차와 어긋나 쓰지 않는다."""
    values = row[column].split(";") if row[column] else []
    return values if len(values) == int(row["days"]) else None


def every_day_present(row):
    return per_day(row, "day_visits") is not None and per_day(row, "day_scores") is not None


def floor_share(rows):
    met = total = 0
    for r in rows:
        visits = per_day(r, "day_visits")
        floor = SIGHT_FLOOR.get(r["pace"], 2)
        for i, v in enumerate(visits):
            edge = i in (0, len(visits) - 1)
            total += 1
            met += int(v) >= (math.ceil(floor / 2) if edge else floor)
    return met / total if total else float("nan")


def back_front(rows):
    ratios = []
    for r in rows:
        scores = per_day(r, "day_scores")
        if len(scores) < 4:
            continue
        front = statistics.mean(float(s) for s in scores[:2])
        if front > 0:
            ratios.append(statistics.mean(float(s) for s in scores[-2:]) / front)
    return mean(ratios)


def late_prominence(rows):
    """뒤 2일과 앞 2일의 유명도 차이. 음수면 앞날이 유명한 곳을 가져간 것이다."""
    gaps = []
    for r in rows:
        days = [float(p) for p in per_day(r, "day_prominence") or [] if p]
        if len(days) >= 4:
            gaps.append(statistics.mean(days[-2:]) - statistics.mean(days[:2]))
    return mean(gaps)


def measure(rows, daily_rows):
    """daily_rows는 일차별 지표를 낼 일정이다. 한쪽에만 빈 날이 있는 일정을 넣으면 두 팔의 분모가 달라진다."""
    meals = sum(int(r["meals"]) for r in rows)
    picked = sum(int(r["picked"] or 0) for r in rows)
    return {
        "일정": len(rows),
        "점수": mean(float(r["total"]) for r in rows),
        "같은 날 유지": mean(float(r["selectionRetention"]) if r["selectionRetention"] else None for r in rows),
        "HARD": sum(r["rules_hard"] == "true" for r in rows),
        "조정 필요": sum(r["rules_core"] == "true" or r["rules_hard"] == "true" for r in rows),
        "거리 위반 일정": count(rows, "LEG_OVER_CAP", "DAY_DISTANCE_OVER_CAP"),
        "끼니 누락 일정": count(rows, "LUNCH_MISSING", "DINNER_MISSING"),
        "숙소로 끝나지 않는 일정": count(rows, "NOT_ENDING_AT_LODGING"),
        "식사 외 활동 없는 일정": count(rows, "NO_NON_FOOD_ACTIVITY"),
        "같은 종류 끼니 건수": sum(int(r["SAME_DAY_MEAL_KIND"]) + int(r["MEAL_KIND_REPEATED"]) for r in rows),
        "종류 미상 끼니 비율": sum(int(r["unknown_meals"]) for r in rows) / meals if meals else float("nan"),
        "하루 관광": mean(float(r["visitsPerDay"]) for r in rows),
        "관광 하한 충족 비율": floor_share(daily_rows),
        "최대 공백(분)": mean(float(r["maxGapMinutes"]) for r in rows),
        "뒤/앞 점수": back_front(daily_rows),
        "빠진 선택 비율": sum(int(r["dropped"] or 0) for r in rows) / picked if picked else float("nan"),
        "이른 종료 건수": sum(int(r["EARLY_FINISH"]) for r in rows),
        "숙소 수": mean(float(r["lodgings"]) for r in rows),
        "뒤-앞 유명도": late_prominence(daily_rows),
    }


def no_more_than(a, b, key):
    return f"A + {COUNT_SLACK} 이하", b[key] <= a[key] + COUNT_SLACK


def verdicts(a, b):
    """지표 → (기준 문구, 통과 여부). 견줄 값이 없는 지표는 판정하지 않는다."""
    checks = {
        "같은 날 유지": (f"{RETENTION_MIN} 이상", b["같은 날 유지"] >= RETENTION_MIN),
        "거리 위반 일정": ("A의 절반 이하", b["거리 위반 일정"] <= a["거리 위반 일정"] / 2),
        "끼니 누락 일정": no_more_than(a, b, "끼니 누락 일정"),
        "숙소로 끝나지 않는 일정": no_more_than(a, b, "숙소로 끝나지 않는 일정"),
        "식사 외 활동 없는 일정": no_more_than(a, b, "식사 외 활동 없는 일정"),
        "같은 종류 끼니 건수": no_more_than(a, b, "같은 종류 끼니 건수"),
        "종류 미상 끼니 비율": (f"A + {UNKNOWN_MEAL_SLACK} 이하",
                         b["종류 미상 끼니 비율"] <= a["종류 미상 끼니 비율"] + UNKNOWN_MEAL_SLACK),
        "뒤/앞 점수": (f"{BACK_FRONT_MIN} 이상", b["뒤/앞 점수"] >= BACK_FRONT_MIN),
        "빠진 선택 비율": (f"{DROPPED_SHARE_MAX} 이하", b["빠진 선택 비율"] <= DROPPED_SHARE_MAX),
        "HARD": ("0", b["HARD"] == 0),
        "조정 필요": no_more_than(a, b, "조정 필요"),
        "점수": (f"A - {SCORE_SLACK} 이상", b["점수"] >= a["점수"] - SCORE_SLACK),
        "하루 관광": (f"A - {VISITS_SLACK} 이상", b["하루 관광"] >= a["하루 관광"] - VISITS_SLACK),
        "관광 하한 충족 비율": (f"A - {FLOOR_SLACK} 이상", b["관광 하한 충족 비율"] >= a["관광 하한 충족 비율"] - FLOOR_SLACK),
        "최대 공백(분)": (f"A + {GAP_SLACK_MINUTES} 이하", b["최대 공백(분)"] <= a["최대 공백(분)"] + GAP_SLACK_MINUTES),
    }
    # 뒤/앞 점수는 4일 이상 일정이 없으면 값이 없다. 빠진 선택 비율은 v2에만 있어 A가 비어도 판정한다.
    return {k: v for k, v in checks.items()
            if not math.isnan(b[k]) and (k == "빠진 선택 비율" or not math.isnan(a[k]))}


def score_moves(a_rows, b_rows):
    """일정별 점수가 SCORE_SLACK 넘게 오른 수와 내린 수."""
    gaps = [float(b["total"]) - float(a["total"]) for a, b in zip(a_rows, b_rows)]
    return sum(g > SCORE_SLACK for g in gaps), sum(g < -SCORE_SLACK for g in gaps)


def shown(value):
    if isinstance(value, float):
        return "-" if math.isnan(value) else f"{value:.3f}".rstrip("0").rstrip(".")
    return str(value)


def report(title, a_rows, b_rows, by_itinerary=False):
    """by_itinerary면 지표는 값만 내고 HARD와 일정별 점수로만 판정한다."""
    daily = [i for i in range(len(a_rows)) if every_day_present(a_rows[i]) and every_day_present(b_rows[i])]
    a = measure(a_rows, [a_rows[i] for i in daily])
    b = measure(b_rows, [b_rows[i] for i in daily])
    checks = verdicts(a, b)
    up, down = score_moves(a_rows, b_rows)
    if by_itinerary:
        checks = {"HARD": checks["HARD"], "일정별 점수": ("내린 수가 오른 수 이하", down <= up)}
    print(f"\n### {title}\n")
    if len(daily) < len(a_rows):
        print(f"일차별 지표(관광 하한, 뒤/앞 점수)는 두 쪽 모두 빈 날이 없는 {len(daily)}건으로 낸다.\n")
    print("| 지표 | A | B | 기준 | 판정 |")
    print("|---|---|---|---|---|")
    for key in a:
        rule, passed = checks.get(key, ("", None))
        mark = "" if passed is None else ("통과" if passed else "**실패**")
        print(f"| {key} | {shown(a[key])} | {shown(b[key])} | {rule} | {mark} |")
    rule, passed = checks.get("일정별 점수", ("", None))
    mark = "" if passed is None else ("통과" if passed else "**실패**")
    print(f"| 일정별 점수({SCORE_SLACK}점 넘는 차이) | - | 오름 {up}, 내림 {down} | {rule} | {mark} |")
    failed = [k for k, (_, passed) in checks.items() if not passed]
    print(f"\n실패 {len(failed)}건" + (": " + ", ".join(failed) if failed else ""))
    return not failed


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    a, b = load(sys.argv[1]), load(sys.argv[2])
    if set(a) != set(b):
        raise SystemExit(f"두 보고서의 일정이 다르다: A {len(a)}건, B {len(b)}건, 공통 {len(set(a) & set(b))}건")
    if not any(r["picked"] for r in b.values()):
        raise SystemExit("B에 날짜별 확정 집계가 없다. --eval.pipeline=v2로 재실행한 보고서를 뒤에 준다")
    ids = sorted(a, key=int)
    long_ids = [i for i in ids if int(a[i]["days"]) >= 4]
    passed = report("국내 전체", [a[i] for i in ids], [b[i] for i in ids])
    passed &= report("국내 4일 이상", [a[i] for i in long_ids], [b[i] for i in long_ids], by_itinerary=True)
    print("\nG1 " + ("통과" if passed else "실패"))
    if not passed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
