"""Natural Earth 1:10m 행정구역(admin-1) 경계에서 고른 나라만 단순화해 경로 공급자 영역 리소스를 만든다.

  python3 -I admin_areas.py ne_10m_admin_1_states_provinces.geojson ../../src/main/resources/routing/admin-areas.json JP KR DE

출력은 {"JP-13": [[[lat, lng], ...], ...]}이고, 고리 하나가 다각형 바깥 경계 하나다(구멍은 버린다).
"""
import json
import sys

TOLERANCE_DEG = 0.005


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


def main():
    src, dst, countries = sys.argv[1], sys.argv[2], set(sys.argv[3:])
    data = json.load(open(src, encoding="utf-8"))
    out = {}
    for f in data["features"]:
        p = f["properties"]
        if p.get("iso_a2") not in countries or not p.get("iso_3166_2"):
            continue
        g = f["geometry"]
        polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]
        rings = []
        for poly in polys:
            ring = [(round(lat, 5), round(lng, 5)) for lng, lat in poly[0]]
            s = simplify(ring, TOLERANCE_DEG)
            if len(s) >= 4:
                rings.append([list(x) for x in s])
        out.setdefault(p["iso_3166_2"], []).extend(rings)
    with open(dst, "w", encoding="utf-8") as fh:
        json.dump(out, fh, separators=(",", ":"))
    print(len(out), "areas", sum(len(r) for v in out.values() for r in v), "points")


if __name__ == "__main__":
    main()
