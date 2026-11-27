"""GTFS를 영역(bbox)과 운행 기간으로 잘라 지역 OTP 그래프 입력을 만든다.

광역·전국 피드를 지역 OSM과 그대로 합치면 영역 밖 정류장이 길과 이어지지 않은 채
그래프 메모리만 차지한다. 영역 안 정류장을 두 곳 이상 지나는 운행만 남긴다.

사용: python3 -I gtfs_clip.py IN.zip OUT.zip --bbox minLat,minLng,maxLat,maxLng --from 20261120 --to 20270131
      [--route-type-map 0:3,2:4] [--drop-route-types 4,6] [--drop-route-name-regex '^\\[KTX\\]']

표준이 아닌 노선 종류 코드를 쓰는 피드는 --route-type-map으로 표준 코드로 바꾸고,
다른 피드와 겹치는 노선은 종류(--drop-route-types)나 이름 규칙(--drop-route-name-regex)으로 뺀다.
"""
import argparse
import csv
import re
import io
import zipfile

KEEP_AS_IS = {"agency.txt", "feed_info.txt", "translations.txt", "attributions.txt", "levels.txt", "booking_rules.txt"}


PRIMARY_KEYS = {"agency.txt": ("agency_id",), "stops.txt": ("stop_id",), "routes.txt": ("route_id",),
                "trips.txt": ("trip_id",), "calendar.txt": ("service_id",), "calendar_dates.txt": ("service_id", "date"),
                "stop_times.txt": ("trip_id", "stop_sequence"), "feed_info.txt": ()}


class FeedZip:
    """GTFS 파일이 압축 안 하위 폴더에 들어 있어도 맨 위에 있는 것처럼 읽는다."""

    def __init__(self, path):
        self.zip = zipfile.ZipFile(path)
        stops = sorted((n for n in self.zip.namelist() if n.rsplit("/", 1)[-1] == "stops.txt"), key=lambda n: n.count("/"))
        if len(stops) > 1 and stops[0].count("/") == stops[1].count("/"):
            raise ValueError("several GTFS folders at the same depth")
        self.prefix = stops[0][:-len("stops.txt")] if stops else ""

    def namelist(self):
        return [n[len(self.prefix):] for n in self.zip.namelist()
                if n.startswith(self.prefix) and "/" not in n[len(self.prefix):]]

    def open(self, name):
        return self.zip.open(self.prefix + name)


def reader(z, name):
    """열 수가 머리글과 다른 행과 기본 키가 겹치는 행을 뺀다. 깨진 행 하나가 OTP 빌드 전체를 멈춘다."""
    raw = csv.DictReader(io.TextIOWrapper(z.open(name), "utf-8-sig", errors="replace"))
    key = PRIMARY_KEYS.get(name)
    seen = set()

    class Rows:
        fieldnames = raw.fieldnames

        def __iter__(self):
            for row in raw:
                if None in row or any(v is None for v in row.values()):
                    continue
                if key:
                    # 전국 피드는 stop_times가 수천만 행이라 키 대신 해시만 들고 있는다.
                    k = hash(tuple(row.get(c, "") for c in key))
                    if k in seen:
                        continue
                    seen.add(k)
                yield row
    return Rows()


def write(out, name, fields, rows):
    count = 0
    with out.open(name, "w", force_zip64=True) as raw, io.TextIOWrapper(raw, "utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields, lineterminator="\n")
        w.writeheader()
        for r in rows:
            w.writerow(r)
            count += 1
    return count


def main():
    p = argparse.ArgumentParser()
    p.add_argument("src")
    p.add_argument("dst")
    p.add_argument("--bbox", required=True)
    p.add_argument("--from", dest="date_from", required=True)
    p.add_argument("--to", dest="date_to", required=True)
    p.add_argument("--route-type-map", default="")
    p.add_argument("--drop-route-types", default="")
    p.add_argument("--drop-route-name-regex", default="")
    # 운행일이 형식상인 대표 시각표는 기간을 잘라야 그래프 운행 기간이 실제 달력 피드를 넘지 않는다.
    p.add_argument("--clamp-calendar", action="store_true")
    a = p.parse_args()
    type_map = dict(pair.split(":") for pair in a.route_type_map.split(",") if pair)
    drop_types = {t for t in a.drop_route_types.split(",") if t}
    drop_names = re.compile(a.drop_route_name_regex) if a.drop_route_name_regex else None
    min_lat, min_lng, max_lat, max_lng = map(float, a.bbox.split(","))

    z = FeedZip(a.src)
    names = set(z.namelist())

    stops = list(reader(z, "stops.txt"))
    stop_fields = reader(z, "stops.txt").fieldnames
    inside = set()
    for s in stops:
        try:
            lat, lng = float(s["stop_lat"]), float(s["stop_lon"])
        except (TypeError, ValueError):
            continue
        if min_lat <= lat <= max_lat and min_lng <= lng <= max_lng:
            inside.add(s["stop_id"])
    # 역 안 승강장(location_type 0)이 부모 역(station)을 가리키면 부모도 남긴다. 파일에 없는 부모는 지운다.
    known = {s["stop_id"] for s in stops}
    for s in stops:
        if s.get("parent_station") and s["parent_station"] not in known:
            s["parent_station"] = ""
    parents = {s.get("parent_station") for s in stops if s["stop_id"] in inside and s.get("parent_station")}
    inside |= parents

    all_routes = [r["route_id"] for r in reader(z, "routes.txt")]
    route_ids = {r["route_id"] for r in reader(z, "routes.txt") if r["route_type"] not in drop_types
                 and not (drop_names and drop_names.search(r.get("route_short_name", "") + " " + r.get("route_long_name", "")))}
    dropped_routes = len(set(all_routes) - route_ids)
    service_ids = set()
    if "calendar.txt" in names:
        service_ids |= {r["service_id"] for r in reader(z, "calendar.txt")
                        if r["end_date"] >= a.date_from and r["start_date"] <= a.date_to}
    if "calendar_dates.txt" in names:
        service_ids |= {r["service_id"] for r in reader(z, "calendar_dates.txt")
                        if a.date_from <= r["date"] <= a.date_to}

    # stop_times는 두 번 읽는다. 한 번에 행을 모아 두면 전국 피드에서 메모리가 십수 GB가 된다.
    inside_count = {}
    st_fields = reader(z, "stop_times.txt").fieldnames
    for r in reader(z, "stop_times.txt"):
        if r["stop_id"] in inside:
            inside_count[r["trip_id"]] = inside_count.get(r["trip_id"], 0) + 1
    trips_kept = {t for t, n in inside_count.items() if n >= 2}
    del inside_count

    services = set()
    trip_rows = []
    trip_fields = reader(z, "trips.txt").fieldnames
    for t in reader(z, "trips.txt"):
        if t["trip_id"] in trips_kept and t["route_id"] in route_ids and t["service_id"] in service_ids:
            trip_rows.append(t)
            services.add(t["service_id"])
    trips_kept = {t["trip_id"] for t in trip_rows}
    routes_kept = {t["route_id"] for t in trip_rows}

    with zipfile.ZipFile(a.dst, "w", zipfile.ZIP_DEFLATED) as out:
        for n in KEEP_AS_IS & names:
            # 옛 GTFS-JP 번역 파일(trans_id·lang·translation)은 table_name이 없어 OTP가 읽다 멈춘다.
            if n == "translations.txt" and "table_name" not in (reader(z, n).fieldnames or []):
                continue
            write(out, n, reader(z, n).fieldnames, reader(z, n))
        n_stops = write(out, "stops.txt", stop_fields, (s for s in stops if s["stop_id"] in inside))
        write(out, "routes.txt", reader(z, "routes.txt").fieldnames,
              (dict(r, route_type=type_map.get(r["route_type"], r["route_type"]))
               for r in reader(z, "routes.txt") if r["route_id"] in routes_kept))
        n_trips = write(out, "trips.txt", trip_fields, trip_rows)
        n_st = write(out, "stop_times.txt", st_fields,
                     (r for r in reader(z, "stop_times.txt") if r["trip_id"] in trips_kept and r["stop_id"] in inside))
        days = []
        if "calendar.txt" in names:
            kept_cal = [r for r in reader(z, "calendar.txt") if r["service_id"] in services
                        and r["end_date"] >= a.date_from and r["start_date"] <= a.date_to]
            days += [r["start_date"] for r in kept_cal] + [r["end_date"] for r in kept_cal]
            if a.clamp_calendar:
                kept_cal = [dict(r, start_date=max(r["start_date"], a.date_from), end_date=min(r["end_date"], a.date_to))
                            for r in kept_cal]
            write(out, "calendar.txt", reader(z, "calendar.txt").fieldnames, kept_cal)
        if "calendar_dates.txt" in names:
            kept_dates = [r for r in reader(z, "calendar_dates.txt") if r["service_id"] in services
                          and a.date_from <= r["date"] <= a.date_to]
            write(out, "calendar_dates.txt", reader(z, "calendar_dates.txt").fieldnames, kept_dates)
            days += [r["date"] for r in kept_dates if r.get("exception_type") == "1"]
        # 참조 열이 있으면 남긴 정류장·운행·노선만 가리켜야 한다. 하나라도 빠지면 OTP 빌드가 멈춘다.
        refs = {"stop_id": inside, "from_stop_id": inside, "to_stop_id": inside,
                "trip_id": trips_kept, "from_trip_id": trips_kept, "to_trip_id": trips_kept,
                "route_id": routes_kept, "from_route_id": routes_kept, "to_route_id": routes_kept}

        def kept(row):
            return all(not row.get(col) or row[col] in allowed for col, allowed in refs.items())

        for n in ("frequencies.txt", "transfers.txt", "fare_attributes.txt", "fare_rules.txt", "pathways.txt"):
            if n in names:
                write(out, n, reader(z, n).fieldnames, (r for r in reader(z, n) if kept(r)))
        if "shapes.txt" in names:
            shape_ids = {t.get("shape_id") for t in trip_rows if t.get("shape_id")}
            write(out, "shapes.txt", reader(z, "shapes.txt").fieldnames,
                  (r for r in reader(z, "shapes.txt") if r["shape_id"] in shape_ids))
    span = f"{min(days)}-{max(days)}" if days else "none"
    print(f"stops={n_stops} trips={n_trips} stop_times={n_st} services={len(services)} service_days={span} "
          f"dropped_routes={dropped_routes}")


if __name__ == "__main__":
    main()
