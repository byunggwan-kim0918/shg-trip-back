"""대화형 입력 평가(ChatEvalRunner·DraftEvalRunner 결과)를 대화 정답(expect)으로 채점한다. LLM을 부르지 않는다.

python3 -I scripts/eval/chat-score.py requests requests.jsonl [다른 실행.jsonl ...]
    생성 요청 단계. 바란 것 도달, 철회한 말 잔존, 피할 조건·이동·예산이 최종 의사와 맞는지.
python3 -I scripts/eval/chat-score.py itinerary --outs out-1/draft-itineraries.jsonl,...
    최종 일정 단계. 검사기 위반(바란 것 누락·치명), 철회한 장소·음식이 일정에 남았는지, traveler-checks 결함 수.

expect 키
  wanted: 생성 요청(요청 문장·꼭 할 것·고른 장소 이름)에 닿아야 하는 낱말. 띄어쓰기로 나뉜 낱말이 모두 있어야 한다
  withdrawn: 철회했으므로 생성 요청과 일정 장소 이름에 남으면 안 되는 낱말
  restrictions / forbiddenRestrictions: 생성 요청에 있어야 할 / 없어야 할 피할 조건
  transport, budget: 생성 요청의 transportPref·budget
  askBack: 날짜 외 되묻기를 했어야(true)·안 했어야(false) 하는지
  askOrDrop: 되묻거나, 아니면 이 낱말이 생성 요청에 남지 않아야 한다
  rejected, locked: 거절된 답 수, 대화 잠김 여부
  notRequired, notAvoided: 꼭 갈 곳(꼭 할 것·고른 장소)·피할 장소 이름 목록에 없어야 할 이름
정답은 --expect(기본 scripts/eval/chat-conversations.jsonl)에서 읽어, 정답을 고친 뒤 예전 결과도 같은 기준으로 다시 잰다.
고른 장소 이름은 로컬 trip_prodcopy에서 읽는다.
"""
import argparse
import importlib.util
import json
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

DB = ["docker", "exec", "-i", "shg-trip-postgres", "psql", "-U", "weShg", "-d", "trip_prodcopy", "-Atc"]
TIME_WORDS = {"야경", "일출", "해돋이"}
DEFAULT_EXPECT = Path(__file__).with_name("chat-conversations.jsonl")
NEGATIVE_FRAGMENTS = ("못 먹거나 피할 음식", "피하고 싶은 활동")


def load_expects(path):
    return {r["id"]: r.get("expect") or {} for r in (json.loads(l) for l in open(path, encoding="utf-8") if l.strip())}


def norm(s):
    return re.sub(r"\s", "", s or "")


def place_names(ids, cache={}):
    missing = [int(i) for i in ids or [] if int(i) not in cache]
    if missing:
        q = "select id, name || ' ' || coalesce(name_ko, '') from places where id in (%s)" % ",".join(map(str, missing))
        for line in subprocess.run(DB + [q], capture_output=True, text=True).stdout.splitlines():
            pid, _, name = line.partition("|")
            cache[int(pid)] = name
    return [cache.get(int(i), "") for i in ids or []]


def request_text(req, positive_only=False):
    """positive_only면 요청 문장의 피할 것 조각을 뺀다. 피하겠다는 말에 나온 이름은 철회 잔존이 아니다."""
    if not req:
        return ""
    fragments = [f for f in (req.get("description") or "").split(" / ") if f]
    parts = [f for f in fragments if not (positive_only and f.startswith(NEGATIVE_FRAGMENTS))]
    parts += [x.get("text", "") for x in req.get("requiredExperiences") or []]
    parts += place_names(req.get("selectedPlaceIds"))
    return " ".join(parts)


def reaches(word, blob):
    tokens = [t for t in word.split() if t not in TIME_WORDS] or [word]
    return all(norm(t) in norm(blob) for t in tokens)


PROMISE = re.compile(r"드릴게요|해 ?드릴|추천해|예약해|찾아 ?드")


def asked_back(row):
    return any(a.get("kind") == "FOLLOW_UP" and a.get("slot") != "DATES" for a in row.get("asked") or [])


def score_behavior(row, exp, out):
    if "askBack" in exp and asked_back(row) != exp["askBack"]:
        out["wrong"].append("되묻기 " + ("없음" if exp["askBack"] else "불필요"))
    if "rejected" in exp and len(row.get("rejected") or []) != exp["rejected"]:
        out["wrong"].append("거절 %d≠%d" % (len(row.get("rejected") or []), exp["rejected"]))
    if "locked" in exp and bool(row.get("locked")) != exp["locked"]:
        out["wrong"].append("잠금 %s≠%s" % (row.get("locked"), exp["locked"]))
    for a in row.get("asked") or []:
        if a.get("lead") and PROMISE.search(a["lead"]):
            out["wrong"].append("약속 문장: " + a["lead"])


def score_request(row, exp):
    req = row.get("request")
    out = {"wanted": 0, "wantedHit": 0, "withdrawnLeft": [], "missed": [], "wrong": []}
    score_behavior(row, exp, out)
    if not req:
        if not exp.get("locked"):
            out["wrong"].append("요청 없음 " + ",".join(row.get("blockers") or []))
        return out
    blob = request_text(req)
    for w in exp.get("wanted", []):
        out["wanted"] += 1
        if reaches(w, blob):
            out["wantedHit"] += 1
        else:
            out["missed"].append(w)
    positive = request_text(req, positive_only=True)
    for w in exp.get("withdrawn", []):
        if norm(w) in norm(positive):
            out["withdrawnLeft"].append(w)
    if not asked_back(row):
        for w in exp.get("askOrDrop", []):
            if norm(w) in norm(positive):
                out["withdrawnLeft"].append(w + "(되묻지도 않음)")
    required = " ".join([x.get("text", "") for x in req.get("requiredExperiences") or []]
                        + place_names(req.get("selectedPlaceIds")))
    for w in exp.get("notRequired", []):
        if norm(w) in norm(required):
            out["wrong"].append("꼭 갈 곳에 남음 " + w)
    for w in exp.get("notAvoided", []):
        if any(norm(w) in norm(n) for n in req.get("avoidPlaceNames") or []):
            out["wrong"].append("피할 장소로 감 " + w)
    have = set(req.get("restrictions") or [])
    for r in exp.get("restrictions", []):
        if r not in have:
            out["wrong"].append("피할 조건 없음 " + r)
    for r in exp.get("forbiddenRestrictions", []):
        if r in have:
            out["wrong"].append("철회된 피할 조건 " + r)
    if "transport" in exp and req.get("transportPref") != exp["transport"]:
        out["wrong"].append("이동 %s≠%s" % (req.get("transportPref"), exp["transport"]))
    if "budget" in exp and int(req.get("budget") or 0) != exp["budget"]:
        out["wrong"].append("예산 %s≠%s" % (req.get("budget"), exp["budget"]))
    return out


def cmd_requests(paths, expects):
    for path in paths:
        rows = [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()]
        wanted = hit = left = wrong = 0
        print("==", path)
        for r in rows:
            s = score_request(r, expects.get(r["id"], {}))
            wanted, hit = wanted + s["wanted"], hit + s["wantedHit"]
            left += len(s["withdrawnLeft"])
            wrong += len(s["wrong"])
            flags = []
            if s["missed"]:
                flags.append("빠짐 " + ",".join(s["missed"]))
            if s["withdrawnLeft"]:
                flags.append("철회 잔존 " + ",".join(s["withdrawnLeft"]))
            flags += s["wrong"]
            print(f"  {r['id']:8} {'; '.join(flags) or '-'}")
        print(f"  바란 것 도달 {hit}/{wanted} · 철회 잔존 {left} · 조건 불일치 {wrong}")


def cmd_replies(paths):
    """사람이 읽을 대화록: 여행자 답과 그 뒤에 나온 반응 문장·질문, 거절 메시지."""
    for path in paths:
        for line in open(path, encoding="utf-8"):
            if not line.strip():
                continue
            r = json.loads(line)
            print(f"== {r['id']} ({r.get('status')}{', 잠김' if r.get('locked') else ''})")
            for a in r.get("asked") or []:
                print(f"   [{a.get('kind')}:{a.get('slot')}] {a.get('lead') or ''} / {a.get('title')}")
            for t in r.get("turns") or []:
                print(f"   답: {t['a']}")
            for x in r.get("rejected") or []:
                print(f"   거절({x['code']}): {x['text']} → {x['message']}")


def load_traveler_checks():
    spec = importlib.util.spec_from_file_location("traveler_checks", Path(__file__).with_name("traveler-checks.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def as_trace(row):
    """DraftEvalRunner 결과 줄을 traveler-checks가 읽는 생성 기록 모양으로 옮긴다. 후보 태그는 없다."""
    req = row["request"]
    candidates, steps = [], []
    for s in row["steps"]:
        candidates.append({"name": s["name"], "latitude": s.get("lat"), "longitude": s.get("lon"),
                           "openingHours": s.get("openingHours")})
        steps.append({"dayNumber": s["day"], "startTime": s["start"], "endTime": s["end"],
                      "place": {"name": s["name"], "category": s.get("category") or ""}})
    return {"startDate": req["startDate"], "scheduleArgs": {"country": "KR"}, "candidates": candidates,
            "savedSteps": steps, "transportPref": req.get("transportPref")}


def cmd_itinerary(outs, expects):
    tc = load_traveler_checks()
    for out in outs:
        rows = [json.loads(l) for l in open(out, encoding="utf-8") if l.strip()]
        want_missing = fatal = withdrawn_in = 0
        defects = Counter()
        print("==", out)
        for r in rows:
            v = r["violations"]
            wm = [x["message"] for x in v if x["code"] == "WANT_MISSING"]
            fa = [x["code"] for x in v if x.get("severity") == "FATAL"]
            names = " ".join(s["name"] for s in r["steps"])
            left = [w for w in expects.get(r["id"], {}).get("withdrawn", []) if norm(w) in norm(names)]
            trace = as_trace(r)
            found = tc.check(trace, tc.steps_from_trace(trace))
            for c, hits in found.items():
                defects[c] += len(hits)
            want_missing, fatal, withdrawn_in = want_missing + len(wm), fatal + len(fa), withdrawn_in + len(left)
            flags = wm + (["치명 " + ",".join(fa)] if fa else []) + (["철회 반영 " + ",".join(left)] if left else [])
            print(f"  {r['id']:8} {'; '.join(flags) or '-'}")
        print(f"  {len(rows)}건 · 바란 것 누락 {want_missing} · 치명 {fatal} · 철회 반영 {withdrawn_in}"
              f" · 여행자 결함 {sum(defects.values())} {dict(defects.most_common(6))}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--expect", default=str(DEFAULT_EXPECT))
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("requests")
    r.add_argument("paths", nargs="+")
    t = sub.add_parser("replies")
    t.add_argument("paths", nargs="+")
    i = sub.add_parser("itinerary")
    i.add_argument("--outs", required=True)
    a = ap.parse_args()
    expects = load_expects(a.expect)
    if a.cmd == "requests":
        cmd_requests(a.paths, expects)
    elif a.cmd == "replies":
        cmd_replies(a.paths)
    else:
        cmd_itinerary(a.outs.split(","), expects)


if __name__ == "__main__":
    sys.exit(main())
