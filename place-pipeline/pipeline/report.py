"""파일럿 비교 리포트. 숫자는 표본 bbox 실측이고 지역 전체나 전 세계 품질로 일반화하지 않는다."""
import csv
import json
import os

from .names import exact_key


def load_landmarks(path):
    """TSV: area, name. 지역마다 사람이 고른 대표 명소 이름(편의 표본)."""
    if not path or not os.path.exists(path):
        return {}
    marks = {}
    with open(path, encoding="utf-8") as f:
        for row in csv.DictReader((line for line in f if not line.startswith("#")), delimiter="\t"):
            marks.setdefault(row["area"], []).append(row["name"])
    return marks


def load_db_counts(path):
    """TSV: area, active. 같은 bbox의 운영(또는 복사본) DB 활성 행 수."""
    if not path or not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as f:
        return {r["area"]: int(r["active"]) for r in csv.DictReader(f, delimiter="\t")}


def area_metrics(con, candidates, links, landmarks):
    by_source = con.execute(f"""
        SELECT source, count(*) rows_total, count(*) FILTER (WHERE exclusion IS NULL) eligible,
               count(*) FILTER (WHERE exclusion IS NULL AND list_contains(list_transform(names, n -> n.lang), 'ko')) with_ko,
               count(*) FILTER (WHERE exclusion IS NULL AND list_contains(list_transform(names, n -> n.lang), 'en')) with_en
        FROM {candidates} GROUP BY 1 ORDER BY 1
    """).fetchall()
    exclusions = con.execute(f"""
        SELECT source, exclusion, count(*) FROM {candidates} WHERE exclusion IS NOT NULL GROUP BY 1, 2 ORDER BY 1, 3 DESC
    """).fetchall()
    tops = con.execute(f"""
        SELECT source, split_part(category, ' > ', 1) top, count(*) FROM {candidates}
        WHERE exclusion IS NULL GROUP BY 1, 2 ORDER BY 1, 3 DESC
    """).fetchall()
    unmapped = con.execute(f"""
        SELECT category_raw, count(*) FROM {candidates} WHERE exclusion = 'unmapped_category'
        GROUP BY 1 ORDER BY 2 DESC LIMIT 15
    """).fetchall()
    link_counts = con.execute(f"SELECT basis, decision, count(*) FROM {links} GROUP BY 1, 2 ORDER BY 1, 2").fetchall()
    coverage = con.execute(f"""
        WITH linked AS (SELECT a_source s, a_id id FROM {links} UNION SELECT b_source, b_id FROM {links})
        SELECT c.source, count(*) eligible, count(l.id) linked
        FROM {candidates} c LEFT JOIN linked l ON l.s = c.source AND l.id = c.source_id
        WHERE c.exclusion IS NULL GROUP BY 1 ORDER BY 1
    """).fetchall()
    found = []
    for mark in landmarks:
        key = exact_key(mark)
        hits = con.execute(f"""
            SELECT source, bool_or(exclusion IS NULL) FROM {candidates}
            WHERE list_has_any(list_transform(names, n -> name_key(n.value)), [?])
               OR contains(name_key, ?)
            GROUP BY 1
        """, [key, key]).fetchall()
        found.append((mark, dict(hits)))
    return {"by_source": by_source, "exclusions": exclusions, "tops": tops, "unmapped": unmapped,
            "links": link_counts, "coverage": coverage, "landmarks": found}


def render(results, manifests, db_counts):
    out = ["# 장소 공급처 파일럿", "",
           "표본 bbox 실측이다. 지역 전체·전 세계 커버리지나 정확도로 일반화하지 않는다.",
           "eligible은 적재 적합성 통과 행, 연결은 출처 간 같은 장소 후보(source_id·exact_name)다.", ""]
    out += ["## 수집 계측", "", "| 범위 | 공급처 | 행 | 파일 | 소요(초) | HTTP 요청 | 전송 | 최대 메모리 |",
            "|---|---|---:|---:|---:|---:|---:|---:|"]
    for m in manifests:
        out.append(f"| {m['scope']} | {m['source']} | {m['rows']:,} | {_mb(m['file_bytes'])} | {m['seconds']} | "
                   f"{m['http_requests']:,} | {_mb(m['http_bytes'])} | {m['max_rss_mb']} MB |")
    out.append("")
    for area, r in results.items():
        out += [f"## {area}", ""]
        if area in db_counts:
            out += [f"현재 DB 같은 bbox 활성 행: {db_counts[area]:,}", ""]
        out += ["| 공급처 | 전체 | eligible | 한국어 이름 | 영어 이름 | 연결된 eligible |", "|---|---:|---:|---:|---:|---:|"]
        coverage = {s: (e, l) for s, e, l in r["coverage"]}
        for source, total, eligible, ko, en in r["by_source"]:
            linked = coverage.get(source, (0, 0))[1]
            out.append(f"| {source} | {total:,} | {eligible:,} | {ko:,} | {en:,} | {linked:,} ({_pct(linked, eligible)}) |")
        out += ["", "연결: " + (", ".join(f"{b}/{d} {n:,}" for b, d, n in r["links"]) or "없음"), ""]
        out.append("eligible 대분류: " + "; ".join(f"{s}·{t or '(없음)'} {n:,}" for s, t, n in r["tops"]))
        out.append("")
        out.append("제외 사유: " + "; ".join(f"{s}·{e} {n:,}" for s, e, n in r["exclusions"]))
        if r["unmapped"]:
            out += ["", "매핑 없는 Overture 분류(상위): " + ", ".join(f"{c} {n}" for c, n in r["unmapped"])]
        if r["landmarks"]:
            out += ["", "| 대표 명소(수동 표본) | overture | fsq |", "|---|---|---|"]
            for mark, hits in r["landmarks"]:
                out.append(f"| {mark} | {_mark(hits.get('overture'))} | {_mark(hits.get('fsq'))} |")
        out.append("")
    return "\n".join(out)


def write(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def read_manifests(root):
    found = []
    for dirpath, _, files in os.walk(root):
        for name in sorted(files):
            if name.endswith(".manifest.json"):
                with open(os.path.join(dirpath, name), encoding="utf-8") as f:
                    found.append(json.load(f))
    return sorted(found, key=lambda m: (m["scope"], m["source"]))


def _mark(hit):
    if hit is None:
        return "없음"
    return "eligible" if hit else "있음(제외됨)"


def _mb(n):
    return "-" if n is None else f"{n / (1 << 20):,.1f} MB"


def _pct(part, whole):
    return "-" if not whole else f"{100 * part / whole:.0f}%"
