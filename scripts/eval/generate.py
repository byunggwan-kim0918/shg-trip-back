"""평가 세트를 로컬 서버로 생성한다. 로컬 전용.

python3 -I scripts/eval/generate.py --base http://localhost:8081 --only jeju-kids-3d
결과: 케이스별 itineraryId를 scripts/eval/out/generated.json에 누적한다.

날짜는 실행일 기준 startInDays 뒤부터 nights박. 실행일이 바뀌면 요일도 바뀐다.
토큰은 서버와 같은 JWT_SECRET으로 만든다. 서버를 .env 없이 띄웠으면 환경변수 없이 실행하면 된다.
"""
import argparse
import base64
import datetime
import hashlib
import hmac
import json
import os
import pathlib
import subprocess
import time
import urllib.error
import urllib.request

HERE = pathlib.Path(__file__).parent
# application-local.yml의 jwt.secret 기본값
LOCAL_JWT_SECRET = "bG9jYWxfZGV2X3NlY3JldF9rZXlfZm9yX2p3dF90ZXN0aW5n"


def b64(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def token(user_id):
    secret = os.environ.get("JWT_SECRET") or LOCAL_JWT_SECRET
    key = base64.urlsafe_b64decode(secret + "=" * (-len(secret) % 4))
    now = int(time.time())
    head = b64(json.dumps({"alg": "HS256"}).encode())
    body = b64(json.dumps({"sub": str(user_id), "role": "USER", "iat": now, "exp": now + 3600}).encode())
    sig = b64(hmac.new(key, f"{head}.{body}".encode(), hashlib.sha256).digest())
    return f"{head}.{body}.{sig}"


def reset_quota(user_id):
    # 30일 쿼터와 실패 차단에 걸리지 않게 매번 비운다
    subprocess.run(["docker", "exec", "-i", "shg-trip-valkey", "valkey-cli", "del",
                    f"generation:quota:{user_id}", f"generation:fail:{user_id}"],
                   check=False, capture_output=True)


def generate(base, auth, case, timeout_sec):
    body = {k: case[k] for k in ("destination", "themes", "categories", "pace", "transportPref",
                                 "budget", "description")}
    start = datetime.date.today() + datetime.timedelta(days=case["startInDays"])
    body["startDate"] = start.isoformat()
    body["endDate"] = (start + datetime.timedelta(days=case["nights"])).isoformat()
    body["mode"] = "AUTO"
    req = urllib.request.Request(f"{base}/api/itineraries/generate", data=json.dumps(body).encode(),
                                 headers={"Authorization": f"Bearer {auth}",
                                          "Content-Type": "application/json"})
    job = json.load(urllib.request.urlopen(req))["data"]["jobId"]

    stream = urllib.request.Request(f"{base}/api/itineraries/generate/{job}/stream",
                                    headers={"Authorization": f"Bearer {auth}"})
    started = time.time()
    event = None
    with urllib.request.urlopen(stream, timeout=timeout_sec) as resp:
        for raw in resp:
            line = raw.decode("utf-8").rstrip("\n")
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:") and event in ("complete", "error"):
                data = json.loads(line[5:])
                return {"jobId": job, "event": event, "data": data,
                        "elapsedSec": round(time.time() - started, 1)}
    return {"jobId": job, "event": "closed", "elapsedSec": round(time.time() - started, 1)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8081")
    ap.add_argument("--user", type=int, default=1)
    ap.add_argument("--only", default="")
    ap.add_argument("--timeout", type=int, default=300)
    args = ap.parse_args()

    cases = json.loads((HERE / "eval-set.json").read_text())
    if args.only:
        wanted = set(args.only.split(","))
        cases = [c for c in cases if c["id"] in wanted]

    out_dir = HERE / "out"
    out_dir.mkdir(exist_ok=True)
    out_file = out_dir / "generated.json"
    results = json.loads(out_file.read_text()) if out_file.exists() else {}

    for case in cases:
        reset_quota(args.user)
        try:
            result = generate(args.base, token(args.user), case, args.timeout)
        except urllib.error.HTTPError as e:
            result = {"event": "http_error", "status": e.code, "error": e.read().decode("utf-8", "replace")}
        except Exception as e:
            result = {"event": "exception", "error": str(e)}
        results[case["id"]] = result
        out_file.write_text(json.dumps(results, ensure_ascii=False, indent=2))
        print(case["id"], result.get("event"), result.get("data") or result.get("error"),
              result.get("elapsedSec"), flush=True)


if __name__ == "__main__":
    main()
