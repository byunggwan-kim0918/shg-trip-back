"""Overture Maps 행정구역(divisions, OSM 기반·ODbL)의 육지 경계에서 고른 나라만 단순화해 경로 공급자 영역 리소스를 만든다.

육지 경계(is_land)만 쓰므로 섬은 들어오고 바다 좌표는 어느 구역에도 들지 않는다. 입력은 DuckDB로 내보낸 GeoJSON이다.

  -- duckdb (httpfs·spatial), 릴리스는 s3://overturemaps-us-west-2/release/ 목록에서 고른다
  copy (select id, country, region, ST_AsGeoJSON(geometry) geom
        from read_parquet('s3://overturemaps-us-west-2/release/<릴리스>/theme=divisions/type=division_area/*',
                          hive_partitioning=1)
        where country in ('JP','KR','DE','SG') and subtype='region' and is_land) to 'regions-land.parquet';

  python3 admin_areas.py regions-land.geojson ../../src/main/resources/routing/admin-areas.json JP KR DE SG

regions-land.geojson은 위 parquet의 행마다 {"type":"Feature","properties":{"id","country","region"},"geometry":geom}인
FeatureCollection이다(`to_geojson`). 출력은 {"JP-13": [[[lat, lng], ...], ...]}이고, 고리 하나가 다각형 바깥 경계 하나다
(구멍은 버린다). ISO 3166-2 코드가 없는 구역(행정 통합 직후 등)은 "나라-x<id 앞 8자>"로 두어 나라 단위 판정에만 쓴다.
"""
import json
import sys

TOLERANCE_DEG = 0.005
# 작은 섬은 고리 크기에 맞춰 덜 줄인다. 큰 허용 오차로 줄이면 섬이 점 세 개 밑으로 줄어 통째로 빠진다.
SMALL_RING_SHARE = 0.1
# 이보다 작은 바위섬은 버린다(약 200m). 사람이 머무는 섬은 이보다 크다.
MIN_EXTENT_DEG = 0.002


def simplify(points, tol):
    if len(points) < 4:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        a, b = stack.pop()
        (y1, x1), (y2, x2) = points[a], points[b]
        best, idx = -1.0, -1
        for i in range(a + 1, b):
            y0, x0 = points[i]
            dx, dy = x2 - x1, y2 - y1
            if dx == 0 and dy == 0:
                d = ((x0 - x1) ** 2 + (y0 - y1) ** 2) ** 0.5
            else:
                t = max(0, min(1, ((x0 - x1) * dx + (y0 - y1) * dy) / (dx * dx + dy * dy)))
                d = ((x0 - x1 - t * dx) ** 2 + (y0 - y1 - t * dy) ** 2) ** 0.5
            if d > best:
                best, idx = d, i
        if best > tol:
            keep[idx] = True
            stack += [(a, idx), (idx, b)]
    return [p for p, k in zip(points, keep) if k]


def ring_of(outer):
    """바깥 경계 하나를 [lat, lng] 고리로 줄인다. 너무 작은 고리는 None이다."""
    ring = [(round(lat, 5), round(lng, 5)) for lng, lat in outer]
    lats = [p[0] for p in ring]
    lngs = [p[1] for p in ring]
    extent = max(max(lats) - min(lats), max(lngs) - min(lngs))
    if extent < MIN_EXTENT_DEG:
        return None
    s = simplify(ring, min(TOLERANCE_DEG, extent * SMALL_RING_SHARE))
    if len(s) < 4:
        s = [(min(lats), min(lngs)), (min(lats), max(lngs)), (max(lats), max(lngs)), (max(lats), min(lngs)),
             (min(lats), min(lngs))]
    return [list(x) for x in s]


def area_key(props):
    if props.get("region"):
        return props["region"]
    return props["country"] + "-x" + str(props["id"]).replace("-", "")[:8]


def build(features, countries):
    out = {}
    for f in features:
        p = f["properties"]
        if p.get("country") not in countries:
            continue
        g = f["geometry"]
        polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]
        rings = [r for r in (ring_of(poly[0]) for poly in polys) if r]
        out.setdefault(area_key(p), []).extend(rings)
    return out


def main():
    src, dst, countries = sys.argv[1], sys.argv[2], set(sys.argv[3:])
    out = build(json.load(open(src, encoding="utf-8"))["features"], countries)
    with open(dst, "w", encoding="utf-8") as fh:
        json.dump(out, fh, separators=(",", ":"), sort_keys=True)
    print(len(out), "areas", sum(len(v) for v in out.values()), "rings",
          sum(len(r) for v in out.values() for r in v), "points")


if __name__ == "__main__":
    main()
