"""
재생 컨테이너(shg-budget-replay)의 미임베딩 장소를 병렬로 임베딩한다. 운영 배치(EmbeddingBatchJob)와 같은 모델·차원·입력 문장
(EmbeddingTextBuilder: 이름 · 이름과 다른 한국어 이름 · 분류 · 태그 · 소개 · 지역)을 쓰고, 요청만 여러 개를 동시에 보낸다.
끝나면 운영과 같은 HNSW 색인을 만든다. OPENAI_API_KEY는 환경변수로만 받는다.

실행: OPENAI_API_KEY=... python bench/replay_embed.py --threads 8
"""
import argparse
import base64
import json
import struct
import os
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

CONTAINER = "shg-budget-replay"
MODEL = "text-embedding-3-small"
DIMENSIONS = 768
USD_PER_MILLION_TOKENS = 0.02
TEXT_SQL = """concat_ws(' ', nullif(trim(name), ''), CASE WHEN name_ko <> name THEN nullif(trim(name_ko), '') END,
    nullif(trim(category), ''), nullif(trim(array_to_string(tags, ' ')), ''), nullif(trim(description), ''),
    nullif(trim(region), ''))"""


def pending(limit):
    out = _psql(f"COPY (SELECT id, {TEXT_SQL} FROM places WHERE embedding IS NULL AND active ORDER BY id LIMIT {limit}) "
                f"TO STDOUT WITH (FORMAT csv)")
    import csv
    import io
    return [(int(r[0]), r[1]) for r in csv.reader(io.StringIO(out))]


def embed(texts, key, retries=6):
    body = json.dumps({"model": MODEL, "dimensions": DIMENSIONS, "input": texts, "encoding_format": "base64"}).encode()
    for attempt in range(retries):
        request = urllib.request.Request("https://api.openai.com/v1/embeddings", data=body,
                                         headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
        try:
            with urllib.request.urlopen(request, timeout=120) as r:
                data = json.load(r)
            vectors = [struct.unpack(f"<{DIMENSIONS}f", base64.b64decode(d["embedding"]))
                       for d in sorted(data["data"], key=lambda d: d["index"])]
            return vectors, data["usage"]["total_tokens"]
        except OSError as e:
            status = getattr(e, "code", None)
            if status is not None and status < 500 and status != 429:
                raise
            time.sleep(2 ** attempt)
    raise RuntimeError("OpenAI 임베딩 요청이 계속 실패한다")


def store(rows):
    with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False) as f:
        for pid, vector in rows:
            f.write(f'{pid},"[{",".join(f"{x:.6f}" for x in vector)}]"\n')
        path = f.name
    subprocess.run(["docker", "cp", path, f"{CONTAINER}:/tmp/embed.csv"], check=True, capture_output=True)
    os.remove(path)
    _psql("CREATE TEMP TABLE e (id BIGINT, v TEXT); \\copy e FROM '/tmp/embed.csv' WITH CSV\n"
          "UPDATE places p SET embedding = e.v::halfvec(768) FROM e WHERE p.id = e.id;")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--threads", type=int, default=8)
    parser.add_argument("--batch", type=int, default=100)
    parser.add_argument("--max-usd", type=float, default=1.0)
    args = parser.parse_args()
    key = os.environ["OPENAI_API_KEY"]
    tokens = 0
    while True:
        rows = pending(args.threads * args.batch * 5)
        if not rows:
            break
        chunks = [rows[i:i + args.batch] for i in range(0, len(rows), args.batch)]
        done = []
        with ThreadPoolExecutor(args.threads) as pool:
            futures = {pool.submit(embed, [t for _, t in c], key): c for c in chunks}
            for future in as_completed(futures):
                vectors, used = future.result()
                tokens += used
                done += [(pid, v) for (pid, _), v in zip(futures[future], vectors)]
                if len(done) >= 2000:
                    store(done)
                    done = []
        if done:
            store(done)
        usd = tokens * USD_PER_MILLION_TOKENS / 1e6
        print(f"임베딩 {len(rows)}건, 누적 토큰 {tokens:,} (${usd:.3f})", flush=True)
        if usd > args.max_usd:
            raise RuntimeError(f"비용 상한 ${args.max_usd}를 넘었다")
    print("HNSW 색인 생성", flush=True)
    # 컨테이너 /dev/shm(1GB)으로는 병렬 색인 생성이 공유 메모리를 못 잡는다.
    _psql("SET maintenance_work_mem = '1GB'; SET max_parallel_maintenance_workers = 0; DROP INDEX IF EXISTS idx_places_embedding_hnsw; "
          "CREATE INDEX idx_places_embedding_hnsw ON places USING hnsw (embedding halfvec_cosine_ops) "
          "WITH (m = 16, ef_construction = 64); ANALYZE places;")
    print(f"끝: 토큰 {tokens:,}, ${tokens * USD_PER_MILLION_TOKENS / 1e6:.3f}")


def _psql(sql):
    done = subprocess.run(["docker", "exec", "-i", CONTAINER, "psql", "-U", "weShg", "-d", "trip", "-At", "-v",
                           "ON_ERROR_STOP=1"], input=sql, capture_output=True, text=True)
    if done.returncode:
        raise RuntimeError(f"psql 실패: {done.stderr.strip()[:500]}")
    return done.stdout


if __name__ == "__main__":
    main()
