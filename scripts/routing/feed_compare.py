"""GTFS 운행 지문. 피드 중복 판정과 판 연결 검사가 같은 기준으로 운행을 비교한다.

운행 하나의 지문은 노선(종류·이름) · 정류장 순서(이름·좌표) · 도착·출발 시각 · 승하차 제한 · 배차 간격 운행이고,
실제 운행일 집합은 따로 들고 있는다. 정류장 id가 아니라 이름과 좌표로 비교해야 id 체계가 다른 사본도 같은 운행으로 본다.
"""
import collections
import csv
import datetime
import hashlib
import io

WEEKDAYS = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
# 소수 넷째 자리는 약 11m다. 같은 정류장 좌표의 반올림 차이는 흡수하고 다른 승강장은 가른다.
COORD_DECIMALS = 4
# 압축을 푼 stop_times가 이보다 크면 운행을 메모리에 모으지 않고 비교를 건너뛴다.
MAX_COMPARE_BYTES = 400 << 20
# 형식상 달력(수십 년)을 날짜마다 펼치지 않는다. 운영 시간표 달력은 1년 안팎이다.
MAX_CALENDAR_DAYS = 3 * 366


def rows(z, name):
    if name not in z.namelist():
        return
    with z.open(name) as f:
        yield from csv.DictReader(io.TextIOWrapper(f, "utf-8-sig", errors="replace"))


def day(value):
    return datetime.date(int(value[:4]), int(value[4:6]), int(value[6:8]))


def ymd(d):
    return d.strftime("%Y%m%d")


def service_dates(z):
    """service_id → 실제 운행일(date) 집합. calendar의 요일·기간에 calendar_dates 추가(1)·제외(2)를 반영한다."""
    dates = collections.defaultdict(set)
    for r in rows(z, "calendar.txt"):
        try:
            start, end = day(r["start_date"]), day(r["end_date"])
        except (KeyError, ValueError, TypeError):
            continue
        weekdays = {i for i, w in enumerate(WEEKDAYS) if (r.get(w) or "").strip() == "1"}
        end = min(end, start + datetime.timedelta(days=MAX_CALENDAR_DAYS))
        d = start
        while d <= end:
            if d.weekday() in weekdays:
                dates[r["service_id"]].add(d)
            d += datetime.timedelta(days=1)
    for r in rows(z, "calendar_dates.txt"):
        try:
            d = day(r["date"])
        except (KeyError, ValueError, TypeError):
            continue
        if (r.get("exception_type") or "").strip() == "1":
            dates[r["service_id"]].add(d)
        elif (r.get("exception_type") or "").strip() == "2":
            dates[r["service_id"]].discard(d)
    return dates


def trip_services(z):
    return {t["trip_id"]: (t.get("route_id", ""), t.get("service_id", "")) for t in rows(z, "trips.txt")}


def service_span(z, used):
    """운행이 있는 service_id의 첫 운행일과 마지막 운행일. 형식상 긴 달력도 펼치지 않고 끝 날짜를 그대로 본다."""
    first = last = None
    for r in rows(z, "calendar.txt"):
        weekdays = {i for i, w in enumerate(WEEKDAYS) if (r.get(w) or "").strip() == "1"}
        if r.get("service_id") not in used or not weekdays:
            continue
        try:
            s, e = day(r["start_date"]), day(r["end_date"])
        except (KeyError, ValueError, TypeError):
            continue
        while s <= e and s.weekday() not in weekdays:
            s += datetime.timedelta(days=1)
        while e >= s and e.weekday() not in weekdays:
            e -= datetime.timedelta(days=1)
        if s <= e:
            first, last = min(first or s, s), max(last or e, e)
    for r in rows(z, "calendar_dates.txt"):
        if r.get("service_id") in used and (r.get("exception_type") or "").strip() == "1":
            try:
                d = day(r["date"])
            except (KeyError, ValueError, TypeError):
                continue
            first, last = min(first or d, d), max(last or d, d)
    return first, last


# 운영 시간표 달력은 보통 1년 안팎이다. 이보다 길면 분석용·형식상 달력이라 운행일을 확정하지 못한다.
CALENDAR_SUSPECT_YEARS = 3


def profile(z):
    """실제 운행이 있는 날짜 범위·운행 수·운영기관. feed_info 기간이 아니라 달력과 운행으로 센다."""
    dates = service_dates(z)
    trips = trip_services(z)
    used = {sid for _, sid in trips.values()}
    first, last = service_span(z, used)
    suspect = bool(first and last.year - first.year > CALENDAR_SUSPECT_YEARS)
    info = next(rows(z, "feed_info.txt"), None) or {}
    return {
        "serviceFrom": ymd(first) if first else None,
        "serviceTo": ymd(last) if last else None,
        "serviceDays": None if suspect else len(set().union(*(dates[s] for s in used if s in dates))) if used else 0,
        "calendarSuspect": suspect,
        "trips": sum(1 for _, sid in trips.values() if dates.get(sid)),
        "agencies": sorted({a.get("agency_name", "").strip() for a in rows(z, "agency.txt")} - {""}),
        "feedStart": info.get("feed_start_date") or None,
        "feedEnd": info.get("feed_end_date") or None,
        "feedVersion": info.get("feed_version") or None,
        "publisher": info.get("feed_publisher_name") or None,
    }


def trips_per_day(z):
    """날짜 → 그날 다니는 운행 수."""
    dates = service_dates(z)
    out = collections.Counter()
    for _, sid in trip_services(z).values():
        for d in dates.get(sid, ()):
            out[d] += 1
    return out


def stop_keys(z):
    """stop_id → (이름, 위도, 경도). 좌표는 COORD_DECIMALS로 반올림한다."""
    out = {}
    for r in rows(z, "stops.txt"):
        try:
            out[r["stop_id"]] = ((r.get("stop_name") or "").strip(), round(float(r["stop_lat"]), COORD_DECIMALS),
                                 round(float(r["stop_lon"]), COORD_DECIMALS))
        except (KeyError, ValueError, TypeError):
            continue
    return out


def comparable(z):
    try:
        return z.zip.getinfo(z.prefix + "stop_times.txt").file_size <= MAX_COMPARE_BYTES
    except KeyError:
        return False


class Fingerprint:
    """피드 하나의 운행 지문.

    patterns: trip_id → 운행 지문(노선·정류장 순서·시각·승하차 제한·배차 간격)
    services: trip_id → service_id, dates: service_id → 실제 운행일 집합
    """

    def __init__(self, z):
        self.dates = service_dates(z)
        stops = stop_keys(z)
        routes = {r["route_id"]: ((r.get("route_type") or "").strip(), (r.get("route_short_name") or "").strip(),
                                  (r.get("route_long_name") or "").strip()) for r in rows(z, "routes.txt")}
        freq = collections.defaultdict(list)
        for r in rows(z, "frequencies.txt"):
            freq[r.get("trip_id")].append((clock(r.get("start_time")), clock(r.get("end_time")),
                                           (r.get("headway_secs") or "").strip(), (r.get("exact_times") or "0").strip()))
        trips = trip_services(z)
        seqs = collections.defaultdict(list)
        for r in rows(z, "stop_times.txt"):
            tid = r.get("trip_id")
            if tid not in trips:
                continue
            try:
                seq = int(r["stop_sequence"])
            except (KeyError, ValueError, TypeError):
                continue
            sid = r.get("stop_id", "")
            seqs[tid].append((seq, stops.get(sid, ("?", sid)), clock(r.get("arrival_time")),
                              clock(r.get("departure_time")), (r.get("pickup_type") or "0").strip() or "0",
                              (r.get("drop_off_type") or "0").strip() or "0"))
        self.patterns, self.services, self.routes = {}, {}, {}
        for tid, (rid, sid) in trips.items():
            st = seqs.pop(tid, None)
            if not st:
                continue
            st.sort(key=lambda x: x[0])
            body = repr((routes.get(rid), [x[1:] for x in st], sorted(freq.get(tid, []))))
            self.patterns[tid] = hashlib.blake2b(body.encode(), digest_size=16).digest()
            self.services[tid] = sid
            self.routes[tid] = rid

    def running(self):
        """실제 운행일이 하루라도 있는 운행."""
        return [t for t, s in self.services.items() if self.dates.get(s)]

    def pattern_counts(self):
        return collections.Counter(self.patterns[t] for t in self.running())

    def on(self, d):
        """그날 다니는 운행 지문 → 개수."""
        return collections.Counter(self.patterns[t] for t, s in self.services.items() if d in self.dates.get(s, ()))

    def by_pattern(self):
        """운행 지문 → 날짜별 운행 수."""
        out = collections.defaultdict(collections.Counter)
        for t in self.running():
            for d in self.dates[self.services[t]]:
                out[self.patterns[t]][d] += 1
        return out


def clock(value):
    """'8:05:00'과 '08:05:00'을 같은 시각으로 본다."""
    value = (value or "").strip()
    if not value:
        return ""
    parts = value.split(":")
    try:
        return ":".join(f"{int(p):02d}" for p in parts)
    except ValueError:
        return value


def contained(a, b):
    """a의 모든 운행이 같은 날짜에 b에도 있는지(같은 운행이 두 번이면 b에도 두 번 이상). a가 비면 False."""
    pa, pb = a.by_pattern(), b.by_pattern()
    if not pa:
        return False
    for pattern, days in pa.items():
        other = pb.get(pattern)
        if not other or any(other[d] < n for d, n in days.items()):
            return False
    return True


def similarity(a, b):
    """두 피드 운행 지문(날짜 제외)의 겹침 비율. 큰 쪽 운행 수가 분모다."""
    ca, cb = a.pattern_counts(), b.pattern_counts()
    total = max(sum(ca.values()), sum(cb.values()))
    return sum((ca & cb).values()) / total if total else 0.0


def stop_overlap(sa, sb):
    """정류장 (이름, 좌표) 집합이 작은 쪽 기준으로 얼마나 겹치는지. 운행 지문을 만들 후보를 싸게 고른다."""
    small = min(len(sa), len(sb))
    return len(sa & sb) / small if small else 0.0


# 같은 정류장 id의 좌표가 이보다 멀어지면 다른 정류장에 id를 다시 쓴 것으로 본다.
STOP_MOVED_METERS = 100
METERS_PER_DEGREE = 111_320


def compare_stops(old, new):
    shared = old.keys() & new.keys()
    moved = sorted(s for s in shared if max(abs(old[s][1] - new[s][1]), abs(old[s][2] - new[s][2]))
                   * METERS_PER_DEGREE > STOP_MOVED_METERS)
    renamed = sorted(s for s in shared if old[s][0] != new[s][0] and s not in moved)
    return {"shared": len(shared), "added": len(new.keys() - old.keys()), "removed": len(old.keys() - new.keys()),
            "moved": len(moved), "renamed": len(renamed), "movedSample": moved[:5], "renamedSample": renamed[:5]}
