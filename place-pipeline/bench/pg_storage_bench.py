"""
장소 수별 Postgres 저장 용량·HNSW 생성 시간·검색 지연 실측. 운영 RDS에는 붙지 않는다.
메모리를 운영 인스턴스(db.t4g.medium, RAM 4GB)와 같게 묶은 별도 pgvector 컨테이너를 띄우고,
로컬 운영 복사본(trip_prodcopy)의 places 행을 읽어 와 배수로 늘려 가며 잰다.
늘린 행은 원본 행의 이름·좌표·임베딩에 작은 잡음을 더한 것이라 행 크기·색인 크기는 실제와 같고, 검색 결과의 의미는 없다.

실행: python bench/pg_storage_bench.py --multiples 1,2,4,8 --out build/bench
끝나면 컨테이너와 볼륨을 지운다(--keep이면 남긴다).
"""
import argparse
import json
import os
import subprocess
import time

IMAGE = "pgvector/pgvector:pg16"
CONTAINER = "shg-select-bench"
SOURCE_CONTAINER = "shg-trip-postgres"
SOURCE_DB = "trip_prodcopy"
SOURCE_USER = "weShg"
# 운영 인스턴스 메모리와 RDS 기본 파라미터(shared_buffers = RAM의 25%).
MEMORY = "4g"
PG_SETTINGS = {"shared_buffers": "1GB", "effective_cache_size": "3GB", "maintenance_work_mem": "1GB",
               "max_parallel_maintenance_workers": "2", "work_mem": "16MB"}
# 운영 검색과 같은 HNSW 파라미터(PgVectorPlaceSearchService).
EF_SEARCH = 200
MAX_SCAN_TUPLES = 100_000
QUERIES = 60
# 늘린 행 임베딩에 더하는 잡음의 노름. 단위 벡터 이웃 구조를 유지하면서 원본과 겹치지 않게 한다.
NOISE_NORM = 0.15
NOISE_VECTORS = 1000


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--multiples", default="1,2,4,8")
    parser.add_argument("--out", required=True)
    parser.add_argument("--keep", action="store_true")
    args = parser.parse_args()
    multiples = [int(m) for m in args.multiples.split(",")]
    os.makedirs(args.out, exist_ok=True)
    dump = os.path.abspath(os.path.join(args.out, "places.copy"))
    results = []
    try:
        _start()
        _schema()
        _load(dump)
        base = int(_sql("SELECT count(*) FROM places"))
        # 균등분포 U(-h, h)의 표준편차는 h/√3이라, 768차원 노름이 NOISE_NORM이 되는 h를 쓴다.
        half_width = NOISE_NORM * (3 / 768) ** 0.5
        _sql(f"""SELECT setseed(0.7);
                 CREATE TABLE noise AS SELECT i, (SELECT array_agg(((random() * 2 - 1) * {half_width})::real)::vector(768)
                 FROM generate_series(1, 768) WHERE i >= 0) AS v FROM generate_series(0, {NOISE_VECTORS - 1}) i""")
        _sql("CREATE TABLE base_ids AS SELECT id FROM places")
        current = 1
        for m in multiples:
            while current < m:
                current += 1
                _grow(current, base)
            results.append(_measure(m, base * m, base))
            with open(os.path.join(args.out, "pg-storage-bench.json"), "w") as f:
                json.dump(results, f, indent=1)
            print(json.dumps(results[-1]), flush=True)
    finally:
        if not args.keep:
            subprocess.run(["docker", "rm", "-f", "-v", CONTAINER], capture_output=True)
        if os.path.exists(dump):
            os.remove(dump)
    return results


def _start():
    subprocess.run(["docker", "rm", "-f", "-v", CONTAINER], capture_output=True)
    # 병렬 VACUUM·색인 생성은 /dev/shm을 쓰는데 Docker 기본값(64MB)으로는 모자란다.
    command = ["docker", "run", "-d", "--name", CONTAINER, "--memory", MEMORY, "--shm-size", "1g", "-e", "POSTGRES_PASSWORD=bench",
               "-e", "POSTGRES_USER=bench", "-e", "POSTGRES_DB=bench", IMAGE]
    for k, v in PG_SETTINGS.items():
        command += ["-c", f"{k}={v}"]
    subprocess.run(command, check=True, capture_output=True)
    for _ in range(60):
        if subprocess.run(["docker", "exec", CONTAINER, "pg_isready", "-U", "bench"], capture_output=True).returncode == 0:
            time.sleep(2)
            return
        time.sleep(1)
    raise RuntimeError("벤치 컨테이너가 뜨지 않았다")


def _schema():
    ddl = subprocess.run(["docker", "exec", SOURCE_CONTAINER, "pg_dump", "-U", SOURCE_USER, "-d", SOURCE_DB,
                          "--schema-only", "--no-owner", "--no-privileges", "-t", "places"],
                         check=True, capture_output=True, text=True).stdout
    _sql("CREATE EXTENSION IF NOT EXISTS vector")
    subprocess.run(["docker", "exec", "-i", CONTAINER, "psql", "-U", "bench", "-d", "bench", "-q", "-v",
                    "ON_ERROR_STOP=1"], input=ddl, check=True, capture_output=True, text=True)
    _sql("DROP INDEX IF EXISTS idx_places_embedding_hnsw")


def _load(dump):
    with open(dump, "wb") as f:
        subprocess.run(["docker", "exec", SOURCE_CONTAINER, "psql", "-U", SOURCE_USER, "-d", SOURCE_DB, "-c",
                        "\\copy places TO STDOUT"], check=True, stdout=f)
    with open(dump, "rb") as f:
        subprocess.run(["docker", "exec", "-i", CONTAINER, "psql", "-U", "bench", "-d", "bench", "-c",
                        "\\copy places FROM STDIN"], check=True, stdin=f, capture_output=True)
    _sql("VACUUM ANALYZE places")


def _grow(copy, base):
    """원본 행을 한 벌 더 넣는다. 이름·좌표·임베딩을 조금씩 바꿔 유일 색인과 HNSW가 실제와 비슷하게 동작하게 한다."""
    offset = (copy - 1) * 10_000_000
    _sql("DROP INDEX IF EXISTS idx_places_embedding_hnsw")
    columns = [c for c in _sql("SELECT string_agg(column_name, ',' ORDER BY ordinal_position) FROM "
                               "information_schema.columns WHERE table_name = 'places'").split(",")]
    replaced = {"id": f"p.id + {offset}", "name": f"p.name || ' #{copy}'",
                "latitude": f"p.latitude + (random() - 0.5) * 0.01", "longitude": f"p.longitude + (random() - 0.5) * 0.01",
                "fsq_place_id": "NULL", "google_place_id": "NULL", "tourapi_content_id": "NULL",
                "embedding": "l2_normalize(p.embedding::vector + n.v)::halfvec(768)"}
    select = ", ".join(replaced.get(c, f"p.{c}") for c in columns)
    _sql(f"""INSERT INTO places ({", ".join(columns)})
             SELECT {select} FROM places p JOIN base_ids b ON b.id = p.id JOIN noise n ON n.i = (p.id + {copy} * 7919) % {NOISE_VECTORS}""")
    _sql("VACUUM ANALYZE places")


def _measure(multiple, rows, base):
    started = time.monotonic()
    _sql("""CREATE INDEX idx_places_embedding_hnsw ON places USING hnsw (embedding halfvec_cosine_ops)
            WITH (m = 16, ef_construction = 64)""")
    build_seconds = round(time.monotonic() - started, 1)
    sizes = _sql("""SELECT pg_total_relation_size('places') || ',' || pg_relation_size('places') || ','
                    || pg_indexes_size('places') || ',' || pg_relation_size('idx_places_embedding_hnsw') || ','
                    || coalesce(pg_total_relation_size(reltoastrelid), 0) FROM pg_class WHERE relname = 'places'""")
    total, heap, indexes, hnsw, toast = (int(x) for x in sizes.split(","))
    regions = _sql("""SELECT string_agg(region || ':' || n, ',') FROM (
                        (SELECT region, count(*) n FROM places WHERE region IS NOT NULL GROUP BY 1 ORDER BY 2 DESC LIMIT 1)
                        UNION ALL
                        (SELECT region, count(*) n FROM places WHERE region IS NOT NULL GROUP BY 1
                         HAVING count(*) > 2000 ORDER BY 2 LIMIT 1)) t""")
    latency = {}
    for label, where in [("country", "")] + [(f"region:{r.split(':')[0]}:{r.split(':')[1]}",
                                              f"AND p.region = '{r.split(':')[0]}'") for r in regions.split(",")]:
        country = _sql(f"SELECT country FROM places p WHERE true {where} GROUP BY 1 ORDER BY count(*) DESC LIMIT 1")
        latency[label] = {"first": _latency(country, where, base), "warm": _latency(country, where, base)}
    return {"multiple": multiple, "rows": rows, "total_bytes": total, "heap_bytes": heap, "toast_bytes": toast,
            "index_bytes": indexes, "hnsw_bytes": hnsw, "bytes_per_row": round(total / rows),
            "hnsw_bytes_per_row": round(hnsw / rows), "hnsw_build_seconds": build_seconds, "latency_ms": latency}


def _latency(country, where, base):
    """운영 검색과 같은 설정으로 QUERIES번 질의한 ms 분포. 질의 벡터는 임의 행의 임베딩이다."""
    out = _sql(f"""
        DO $$
        DECLARE q halfvec; t0 timestamptz; ms double precision[] := '{{}}';
        BEGIN
          PERFORM set_config('hnsw.ef_search', '{EF_SEARCH}', true);
          PERFORM set_config('hnsw.iterative_scan', 'relaxed_order', true);
          PERFORM set_config('hnsw.max_scan_tuples', '{MAX_SCAN_TUPLES}', true);
          FOR i IN 1..{QUERIES} LOOP
            SELECT embedding INTO q FROM places WHERE id = (SELECT id FROM base_ids OFFSET floor(random() * {base}) LIMIT 1);
            t0 := clock_timestamp();
            PERFORM id FROM (
              SELECT p.id FROM places p
              WHERE p.embedding IS NOT NULL AND p.active = true AND p.merged_into_id IS NULL AND p.country = '{country}'
                {where}
              ORDER BY p.embedding <=> q LIMIT 40) s;
            ms := ms || extract(epoch FROM clock_timestamp() - t0) * 1000;
          END LOOP;
          RAISE NOTICE 'LAT %', (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY x) || ',' ||
                                        percentile_cont(0.95) WITHIN GROUP (ORDER BY x) FROM unnest(ms) x);
        END $$;
    """, notices=True)
    p50, p95 = (float(x) for x in out.split("LAT ")[-1].split()[0].split(","))
    return {"p50": round(p50, 1), "p95": round(p95, 1)}


def _sql(sql, notices=False):
    done = subprocess.run(["docker", "exec", "-i", CONTAINER, "psql", "-U", "bench", "-d", "bench", "-At", "-v",
                           "ON_ERROR_STOP=1"], input=sql, capture_output=True, text=True)
    if done.returncode:
        raise RuntimeError(f"psql 실패: {done.stderr.strip()[:500]}")
    return (done.stderr if notices else done.stdout).strip()


if __name__ == "__main__":
    main()
