"""원본 스냅샷(L0) 저장과 실행 계측. 스냅샷마다 manifest를 남겨 같은 입력으로 다시 돌릴 수 있게 한다."""
import hashlib
import json
import os
import platform
import resource
import time
from datetime import datetime, timezone


def pull(con, source, select_sql, out_path, scope, release):
    """select_sql 결과를 Parquet으로 쓰고 manifest(행 수·크기·sha256·소요·전송량·메모리)를 옆에 둔다."""
    _check_path(out_path)
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    con.execute("CALL enable_logging('HTTP')")
    con.execute("CALL truncate_duckdb_logs()")
    started = time.monotonic()
    con.execute(f"COPY ({select_sql}) TO '{out_path}' (FORMAT parquet, COMPRESSION zstd)")
    seconds = time.monotonic() - started
    requests, http_bytes = http_transfer(con)
    con.execute("CALL disable_logging()")
    manifest = {
        "source": source.name,
        "license": source.license,
        "terms_url": source.terms_url,
        "release": release,
        "scope": scope,
        "fetched_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "rows": con.execute(f"SELECT count(*) FROM read_parquet('{out_path}')").fetchone()[0],
        "file_bytes": os.path.getsize(out_path),
        "sha256": sha256(out_path),
        "seconds": round(seconds, 1),
        "http_requests": requests,
        "http_bytes": http_bytes,
        "max_rss_mb": max_rss_mb(),
    }
    with open(out_path + ".manifest.json", "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    return manifest


def http_transfer(con):
    """HTTP 로그의 GET 응답 크기 합. 응답 크기 헤더가 없는 요청은 Range 헤더 길이로 센다."""
    rows = con.execute("""
        SELECT request.headers, response.headers FROM duckdb_logs_parsed('HTTP') WHERE request.type = 'GET'
    """).fetchall()
    total = 0
    for req_headers, resp_headers in rows:
        size = _header(resp_headers, "content-length")
        if size is not None and size.isdigit():
            total += int(size)
            continue
        span = _header(req_headers, "range")
        if span and span.startswith("bytes=") and "-" in span:
            start, end = span[6:].split("-", 1)
            if start.isdigit() and end.isdigit():
                total += int(end) - int(start) + 1
    return len(rows), total


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def max_rss_mb():
    # macOS는 바이트, Linux는 KB로 돌려준다.
    peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return round(peak / (1 << 20) if platform.system() == "Darwin" else peak / 1024, 1)


def _header(headers, name):
    for key, value in (headers or {}).items():
        if key.lower() == name:
            return value
    return None


def _check_path(path):
    if "'" in path:
        raise ValueError(f"경로에 작은따옴표를 쓸 수 없다: {path}")
