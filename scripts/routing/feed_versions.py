"""피드 판 보관과 수명 관리(Feed Lifecycle Manager).

받은 GTFS는 내용 해시로 판마다 따로 두고 덮어쓰지 않는다.

  <root>/<피드 id>/<sha256 앞 16자>.zip   받은 원본 그대로(읽기 전용)
  <root>/<피드 id>/versions.json           판 목록: 원천·운영기관·라이선스·받은 시각·실제 운행 기간·품질

그래프에 실을 판은 plan()이 고른다. 오늘 운행하는 정상 판을 쓰고, 다음 판이 안전하게 이어지면 함께 싣고,
이어지지 않으면 기존 판만 싣고 빈 기간을 공백으로 남긴다.
"""
import datetime
import hashlib
import json
import os
import re
import shutil
import tempfile
import urllib.parse

import feed_compare
import gtfs_clip

# 운행 종료가 이만큼 남으면 갱신 우선순위를 올린다(매일 다시 받고 다음 판을 찾는다).
EXPIRY_WARN_DAYS = 14
EXPIRING_REFRESH_DAYS = 1
# 새 판 운행 수가 직전 정상 판의 이 비율 밑이면 일부만 올라온 판으로 보고 쓰지 않는다.
TRIP_DROP_LIMIT = 0.5
# 다음 판과 겹치는 정류장 id 중 이 비율 넘게 위치·이름이 바뀌면 id를 다시 매긴 판이라 이어 붙이지 않는다.
STOP_CHANGE_LIMIT = 0.05
REQUIRED_FILES = ("agency.txt", "stops.txt", "routes.txt", "trips.txt", "stop_times.txt")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def write_json(path, data):
    """반쯤 쓴 목록이 남으면 그 피드의 모든 판을 잃는다."""
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=1)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)


def quality(z, prof):
    """그래프에 실을 수 있는 판인지. 문제가 있어도 판은 보관하고 plan()이 고르지 않는다."""
    names = set(z.namelist())
    problems = [f"missing {n}" for n in REQUIRED_FILES if n not in names]
    if not {"calendar.txt", "calendar_dates.txt"} & names:
        problems.append("missing calendar")
    if not prof["trips"]:
        problems.append("no running trips")
    if not feed_compare.stop_keys(z):
        problems.append("no stops with coordinates")
    return problems


class FeedVersions:

    def __init__(self, root):
        self.root = root

    def folder(self, fid):
        return os.path.join(self.root, fid)

    def index(self, fid):
        """판 목록. 파일이 깨졌으면 예외를 낸다. 빈 목록으로 보고 새 판을 쓰면 그 피드의 판 기록을 모두 덮는다."""
        path = os.path.join(self.folder(fid), "versions.json")
        if not os.path.exists(path):
            return {"feedId": fid, "versions": []}
        with open(path, encoding="utf-8") as f:
            return json.load(f)

    def feeds(self):
        if not os.path.isdir(self.root):
            return []
        return sorted(d for d in os.listdir(self.root) if os.path.exists(os.path.join(self.root, d, "versions.json")))

    def path(self, fid, version):
        return os.path.join(self.folder(fid), version["file"])

    def register(self, fid, src, origin=None, edition=None, now=None, allow_trip_drop=False):
        """src를 판으로 보관한다. 같은 내용이면 받은 시각만 더하고 (판, False), 새 내용이면 (판, True)."""
        digest = sha256(src)
        idx = self.index(fid)
        now = (now or datetime.datetime.now()).isoformat(timespec="seconds")
        for v in idx["versions"]:
            if v["sha256"] == digest:
                v["lastSeenAt"] = now
                if edition and not v.get("edition"):
                    v["edition"] = edition
                if allow_trip_drop and v.get("problems"):
                    v["problems"] = [x for x in v["problems"] if not x.startswith("trips ")]
                # 다시 받기 전에 남긴 사본은 원천을 모를 수 있어, 같은 판을 원천과 함께 다시 보면 채운다.
                for key in ("source", "catalog", "license", "official"):
                    if v.get(key) is None and (origin or {}).get(key) is not None:
                        v[key] = origin[key]
                write_json(os.path.join(self.folder(fid), "versions.json"), idx)
                return v, False
        os.makedirs(self.folder(fid), exist_ok=True)
        name = digest[:16] + ".zip"
        target = os.path.join(self.folder(fid), name)
        if not os.path.exists(target):
            fd, tmp = tempfile.mkstemp(dir=self.folder(fid), suffix=".part")
            os.close(fd)
            try:
                shutil.copyfile(src, tmp)
                os.chmod(tmp, 0o444)
                os.replace(tmp, target)
            finally:
                if os.path.exists(tmp):
                    os.remove(tmp)
        try:
            z = gtfs_clip.FeedZip(target)
            prof = feed_compare.profile(z)
            problems = quality(z, prof)
        except Exception as e:  # 읽지 못하는 판도 원본은 남기고 쓰지 않는다.
            prof, problems = {}, [f"unreadable {type(e).__name__}"]
        previous = self.latest_ok(fid, idx)
        # allow_trip_drop은 노선 분할·감편으로 정당하게 줄어든 피드를 설정에서 받아들일 때만 켠다.
        if previous and not allow_trip_drop and prof.get("trips") is not None and previous.get("trips") \
                and prof["trips"] < previous["trips"] * TRIP_DROP_LIMIT:
            problems.append(f"trips {previous['trips']}->{prof['trips']} vs {previous['sha256'][:12]}")
        origin = origin or {}
        version = dict(prof, sha256=digest, file=name, fetchedAt=now, lastSeenAt=now,
                       source=origin.get("source"), catalog=origin.get("catalog"), license=origin.get("license"),
                       official=origin.get("official"), edition=edition, problems=problems)
        idx["versions"].append(version)
        write_json(os.path.join(self.folder(fid), "versions.json"), idx)
        return version, True

    def latest_ok(self, fid, idx=None):
        ok = [v for v in (idx or self.index(fid))["versions"] if not v.get("problems")]
        return max(ok, key=lambda v: v["fetchedAt"]) if ok else None

    def usable(self, fid):
        return [v for v in self.index(fid)["versions"] if not v.get("problems") and v.get("serviceFrom")]

    def active(self, fid, today):
        """오늘 운행하는 정상 판. 여럿이면 나중에 받은 판이다. 오늘 운행하는 판이 없으면 가장 늦게 끝나는 지난 판."""
        t = today.strftime("%Y%m%d")
        ok = self.usable(fid)
        running = [v for v in ok if v["serviceFrom"] <= t <= v["serviceTo"]]
        if running:
            # 원천이 예전 판으로 되돌리면 그 판이 가장 최근에 보인 판이다.
            return max(running, key=lambda v: (v.get("lastSeenAt") or v["fetchedAt"], v["fetchedAt"]))
        past = [v for v in ok if v["serviceTo"] < t]
        if past:
            return max(past, key=lambda v: (v["serviceTo"], v["fetchedAt"]))
        future = [v for v in ok if v["serviceFrom"] > t]
        return min(future, key=lambda v: v["serviceFrom"]) if future else None

    def successor(self, fid, version):
        """version 뒤에 나온 다음 판: 더 늦게 시작하고 더 늦게 끝나며 version보다 나중에 받은 정상 판 중 가장 먼저 시작하는 판.
        먼저 받은 판은 다음 판이 아니다. 개정으로 짧아진 새 판 뒤에 예전의 긴 판이 끼어들면 새 판이 지워진다."""
        later = [v for v in self.usable(fid) if v["sha256"] != version["sha256"] and v["serviceFrom"] > version["serviceFrom"]
                 and v["serviceTo"] > version["serviceTo"] and v["fetchedAt"] >= version["fetchedAt"]]
        return min(later, key=lambda v: (v["serviceFrom"], v["fetchedAt"])) if later else None

    def status(self, fid, today):
        """판 상태: ok · expiring(EXPIRY_WARN_DAYS 안) · expired · none. 다음 판은 이어 실을 수 있을 때만 만료를 늦춘다."""
        active = self.active(fid, today)
        if not active:
            return {"feedId": fid, "state": "none"}
        nxt = self.successor(fid, active)
        link_error = None
        try:
            decision = self.link_decision(fid, active, nxt) if nxt else None
        except Exception as e:  # 판 zip을 못 읽으면 잇지 않은 것으로 보고 지금 판 끝으로 만료를 센다.
            decision, link_error = None, type(e).__name__
        unlinked = bool(nxt) and decision.get("decision") != "link" if decision else bool(nxt)
        end = feed_compare.day((active if unlinked or not nxt else nxt)["serviceTo"])
        left = (end - today).days
        state = "expired" if left < 0 else "expiring" if left <= EXPIRY_WARN_DAYS else "ok"
        newest = max(self.index(fid)["versions"], key=lambda v: v["fetchedAt"])
        return {"feedId": fid, "state": state, "daysLeft": left, "serviceTo": feed_compare.ymd(end),
                "active": active["sha256"][:16], "next": nxt["sha256"][:16] if nxt else None, "nextUnlinked": unlinked,
                "gapDays": (decision or {}).get("gapDays") if not unlinked else None, "linkError": link_error,
                "newestRejected": newest["problems"] if newest.get("problems") else None}

    def link_decision(self, fid, old, new):
        """판 연결 검사 결과. 판 목록에 남겨 다시 쓰므로 운행 지문을 매번 만들지 않는다."""
        key = old["sha256"][:16] + ".." + new["sha256"][:16]
        cached = self.index(fid).get("links", {}).get(key)
        if cached and "decision" in cached and "gapDays" in cached:
            return cached
        report = link(gtfs_clip.FeedZip(self.path(fid, old)), old, gtfs_clip.FeedZip(self.path(fid, new)), new)
        self.save_link(fid, key, report)
        return report

    def save_link(self, fid, key, report):
        # 운행 지문을 만드는 동안 빌드가 새 판을 더했을 수 있어 쓰기 직전에 다시 읽고 연결 결과만 더한다.
        idx = self.index(fid)
        idx.setdefault("links", {})[key] = report
        write_json(os.path.join(self.folder(fid), "versions.json"), idx)

    def refresh_days(self, fid, base_days, today):
        """만료가 가까운 피드는 매일 다시 받는다. 나머지는 원래 주기."""
        state = self.status(fid, today)["state"]
        return EXPIRING_REFRESH_DAYS if state in ("expiring", "expired") else base_days

    def plan(self, fid, today):
        """그래프에 실을 판과 판마다 싣는 기간(시작, 끝 YYYYMMDD, None은 제한 없음), 연결 검사 결과."""
        active = self.active(fid, today)
        if not active:
            return [], None
        nxt = self.successor(fid, active)
        if not nxt:
            return [(active, None, None)], None
        cached = self.link_decision(fid, active, nxt)
        report = dict(cached, reasons=list(cached["reasons"]))
        cut = feed_compare.ymd(feed_compare.day(nxt["serviceFrom"]) - datetime.timedelta(days=1))
        # 오늘 이전에서 끊으면 지금 운행하는 판이 그래프에서 빠진다. 날마다 바뀌는 판정이라 연결 결과에 남기지 않는다.
        if report["decision"] == "link" and cut < today.strftime("%Y%m%d"):
            report["decision"] = "separate"
            report["reasons"].append(f"cut {cut} is before today")
        if report["decision"] != "link":
            return [(active, None, None)], report
        return [(active, None, min(active["serviceTo"], cut)), (nxt, nxt["serviceFrom"], None)], report


def link(old_z, old, new_z, new):
    """두 판을 한 그래프에 이어 실어도 되는지 본다. 판은 OTP 피드 id를 따로 쓰므로 id가 겹쳐도 섞이지 않지만,
    정류장 id가 다른 곳을 가리키면 실시간·정류장 질의가 엉뚱한 정류장에 붙어 잇지 않는다."""
    reasons = []
    if not (feed_compare.comparable(old_z) and feed_compare.comparable(new_z)):
        return link_large(old_z, old, new_z, new)
    a, b = feed_compare.Fingerprint(old_z), feed_compare.Fingerprint(new_z)
    stops = feed_compare.compare_stops(feed_compare.stop_keys(old_z), feed_compare.stop_keys(new_z))
    changed = stops["moved"] + stops["renamed"]
    if stops["shared"] and changed / stops["shared"] > STOP_CHANGE_LIMIT:
        reasons.append(f"stop ids reused for other stops ({changed}/{stops['shared']})")
    if not stops["shared"]:
        reasons.append("no shared stop ids")
    old_end, new_start = feed_compare.day(old["serviceTo"]), feed_compare.day(new["serviceFrom"])
    gap = (new_start - old_end).days - 1
    overlap = [d for d in sorted(set().union(*a.dates.values()) & set().union(*b.dates.values()))
               if d >= new_start] if gap < 0 else []
    duplicates = sum(sum((a.on(d) & b.on(d)).values()) for d in overlap)
    routes = route_collisions(old_z, new_z)
    shared_trips = a.patterns.keys() & b.patterns.keys()
    trip_collisions = sum(1 for t in shared_trips if a.patterns[t] != b.patterns[t] or a.services[t] != b.services[t])
    shared_services = a.dates.keys() & b.dates.keys()
    service_collisions = sum(1 for s in shared_services if a.dates[s] != b.dates[s])
    linked = trips_by_day(a, end=new_start - datetime.timedelta(days=1)) + trips_by_day(b, start=new_start)
    span = [old_end + datetime.timedelta(days=1 + i) for i in range(max(gap, 0))]
    return {
        "decision": "separate" if reasons else "link",
        "reasons": reasons,
        # id가 다른 내용으로 다시 쓰였으면 한 피드로 합칠 수 없다. 이어 실을 때도 판마다 피드 id를 따로 쓴다.
        "mergeable": not (routes["differentContent"] or trip_collisions or service_collisions),
        "old": {"sha256": old["sha256"][:16], "serviceFrom": old["serviceFrom"], "serviceTo": old["serviceTo"]},
        "new": {"sha256": new["sha256"][:16], "serviceFrom": new["serviceFrom"], "serviceTo": new["serviceTo"]},
        "gapDays": max(gap, 0),
        "gapDates": [feed_compare.ymd(d) for d in span][:31],
        "overlapDays": len(overlap),
        "duplicateTripsInOverlap": duplicates,
        "stops": stops,
        "routes": routes,
        "tripIds": {"shared": len(shared_trips), "differentContent": trip_collisions},
        "serviceIds": {"shared": len(shared_services), "differentDates": service_collisions},
        "linkedDays": {"from": feed_compare.ymd(min(linked)) if linked else None,
                       "to": feed_compare.ymd(max(linked)) if linked else None,
                       "withService": sum(1 for n in linked.values() if n),
                       "withoutService": missing_days(linked)},
    }


def link_large(old_z, old, new_z, new):
    """stop_times가 커서 운행 지문을 만들지 않는 피드. 기간과 정류장 id만 보고 운행 단위 검사는 건너뛰었다고 남긴다."""
    stops = feed_compare.compare_stops(feed_compare.stop_keys(old_z), feed_compare.stop_keys(new_z))
    reasons = []
    changed = stops["moved"] + stops["renamed"]
    if not stops["shared"]:
        reasons.append("no shared stop ids")
    elif changed / stops["shared"] > STOP_CHANGE_LIMIT:
        reasons.append(f"stop ids reused for other stops ({changed}/{stops['shared']})")
    gap = (feed_compare.day(new["serviceFrom"]) - feed_compare.day(old["serviceTo"])).days - 1
    return {"decision": "separate" if reasons else "link", "reasons": reasons, "mergeable": False,
            "tripChecks": "skipped (stop_times too large)",
            "old": {"sha256": old["sha256"][:16], "serviceFrom": old["serviceFrom"], "serviceTo": old["serviceTo"]},
            "new": {"sha256": new["sha256"][:16], "serviceFrom": new["serviceFrom"], "serviceTo": new["serviceTo"]},
            "gapDays": max(gap, 0), "stops": stops, "routes": route_collisions(old_z, new_z)}


def trips_by_day(fp, start=None, end=None):
    out = feed_compare.collections.Counter()
    for t in fp.running():
        for d in fp.dates[fp.services[t]]:
            if (start is None or d >= start) and (end is None or d <= end):
                out[d] += 1
    return out


def missing_days(counts):
    """연결한 기간 안에서 운행이 하나도 없는 날짜."""
    if not counts:
        return []
    d, last, out = min(counts), max(counts), []
    while d <= last:
        if not counts.get(d):
            out.append(feed_compare.ymd(d))
        d += datetime.timedelta(days=1)
    return out


def route_collisions(old_z, new_z):
    def routes(z):
        return {r["route_id"]: ((r.get("route_type") or "").strip(), (r.get("route_short_name") or "").strip(),
                                (r.get("route_long_name") or "").strip()) for r in feed_compare.rows(z, "routes.txt")}
    a, b = routes(old_z), routes(new_z)
    shared = a.keys() & b.keys()
    different = sorted(r for r in shared if a[r] != b[r])
    return {"shared": len(shared), "added": len(b.keys() - a.keys()), "removed": len(a.keys() - b.keys()),
            "differentContent": len(different), "differentSample": different[:5]}


def next_edition_rule(url, rules):
    host = urllib.parse.urlsplit(url or "").netloc.lower()
    return next((r for r in rules or [] if r["host"] == host), None)


def next_edition_url(url, version, rules):
    """판을 쿼리 값으로 고르는 원천이면 다음 판(지금 판 끝 다음 날) 주소. 규칙이 없으면 None."""
    if not url or not version or not version.get("serviceTo"):
        return None
    parts = urllib.parse.urlsplit(url)
    rule = next_edition_rule(url, rules)
    if rule:
        start = feed_compare.day(version["serviceTo"]) + datetime.timedelta(days=1)
        query = [(k, v) for k, v in urllib.parse.parse_qsl(parts.query) if k != rule["param"]]
        query.append((rule["param"], start.strftime(rule["format"])))
        return urllib.parse.urlunsplit(parts._replace(query=urllib.parse.urlencode(query, safe=":")))
    return None


def edition_of(url, rules):
    """주소의 판 쿼리 값(예: date=20261201). 판 목록에 남겨 어느 판을 받았는지 가린다."""
    rule = next_edition_rule(url, rules)
    value = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(url or "").query)).get(rule["param"]) if rule else None
    return value if value and re.fullmatch(r"\d{6,8}", value) else None
