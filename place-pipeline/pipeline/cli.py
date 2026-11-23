"""
실행: cd place-pipeline && python -m pipeline.cli pilot --areas pilot/areas.tsv --out build/pilot
      python -m pipeline.cli destinations --geonames build/geonames/JP.txt --out build/destinations.csv
FSQ를 함께 받으려면 FSQ_ACCESS_TOKEN을 환경변수로 넣는다. 원본은 --out 아래에만 쓴다.
"""
import argparse
import logging
import os
from datetime import date

import duckdb

from . import candidates, geonames, identity, report, snapshot, sources
from .areas import load_areas

log = logging.getLogger("place-pipeline")


def pilot(args):
    areas = load_areas(args.areas)
    wanted = [s.strip() for s in args.sources.split(",") if s.strip()]
    unknown = set(wanted) - set(sources.SOURCES)
    if unknown:
        raise SystemExit(f"모르는 공급처: {sorted(unknown)}")
    fsq_release = args.fsq_release or f"os-latest@{date.today().isoformat()}"
    con = duckdb.connect()
    if args.memory_limit:
        con.execute(f"SET memory_limit = '{_size(args.memory_limit)}'")
    candidates.register_functions(con)
    connected = set()
    for area in areas:
        raw = {}
        for name in wanted:
            path = os.path.join(args.out, "raw", name, f"{area.name}.parquet")
            if not (args.reuse and os.path.exists(path)):
                if name not in connected:
                    (sources.connect_overture if name == "overture" else sources.connect_fsq)(con)
                    connected.add(name)
                select = (sources.overture_select(args.overture_release, area.overture_where()) if name == "overture"
                          else sources.fsq_select(area.fsq_where()))
                release = args.overture_release if name == "overture" else fsq_release
                try:
                    m = snapshot.pull(con, sources.SOURCES[name], select, path, area.name, release)
                except duckdb.HTTPException as e:
                    # FSQ 카탈로그가 내준 파일 접근 자격은 실행 도중 만료된다. 다시 붙어 한 번만 더 받는다.
                    if name != "fsq":
                        raise
                    log.warning("%s fsq 파일 접근 실패, 카탈로그에 다시 붙는다: %s", area.name, str(e)[:120])
                    sources.connect_fsq(con)
                    m = snapshot.pull(con, sources.SOURCES[name], select, path, area.name, release)
                log.info("%s %s: %d행 %.0f초 전송 %.1fMB", area.name, name, m["rows"], m["seconds"],
                         m["http_bytes"] / (1 << 20))
            raw[name] = f"read_parquet('{path}')"
        table = candidates.build(con, raw.get("overture"), args.overture_release, raw.get("fsq"), fsq_release)
        links = identity.build_links(con, table, "source_links")
        for name, tbl in ((f"candidates/{area.name}.parquet", table), (f"links/{area.name}.parquet", links)):
            path = os.path.join(args.out, name)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            con.execute(f"COPY {tbl} TO '{path}' (FORMAT parquet, COMPRESSION zstd)")
    write_report(con, args, areas)


def write_report(con, args, areas):
    candidates.register_functions(con)
    marks = report.load_landmarks(args.landmarks)
    results = {}
    for area in areas:
        c = f"read_parquet('{os.path.join(args.out, 'candidates', area.name + '.parquet')}')"
        l = f"read_parquet('{os.path.join(args.out, 'links', area.name + '.parquet')}')"
        results[area.name] = report.area_metrics(con, c, l, marks.get(area.name, []))
    text = report.render(results, report.read_manifests(os.path.join(args.out, "raw")),
                         report.load_db_counts(args.db_counts))
    report.write(os.path.join(args.out, "pilot-report.md"), text)
    log.info("리포트: %s", os.path.join(args.out, "pilot-report.md"))


def destinations(args):
    counts = geonames.convert(args.geonames, args.out)
    log.info("여행지 후보 %d건 %s → %s", sum(counts.values()), counts, args.out)


def _size(value):
    if not value[:-2].isdigit() or value[-2:].upper() not in ("MB", "GB"):
        raise SystemExit(f"--memory-limit 형식은 예: 3GB, 512MB ({value})")
    return value


def main(argv=None):
    logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
    parser = argparse.ArgumentParser(prog="place-pipeline")
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("pilot", help="범위별 원본 스냅샷 → 공통 후보 → 출처 간 연결 → 리포트")
    p.add_argument("--areas", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--sources", default="overture,fsq")
    p.add_argument("--overture-release", default="2026-11-18.0")
    p.add_argument("--fsq-release")
    p.add_argument("--landmarks")
    p.add_argument("--db-counts")
    p.add_argument("--memory-limit")
    p.add_argument("--reuse", action="store_true", help="이미 받은 원본 스냅샷을 다시 받지 않는다")
    d = sub.add_parser("destinations", help="GeoNames 국가 덤프 → 여행지 후보 CSV")
    d.add_argument("--geonames", nargs="+", required=True, help="CC.txt 경로(국가별)")
    d.add_argument("--out", required=True)
    args = parser.parse_args(argv)
    if args.command == "destinations":
        destinations(args)
        return
    if "'" in args.out:
        raise SystemExit("--out 경로에 작은따옴표를 쓸 수 없다")
    pilot(args)


if __name__ == "__main__":
    main()
