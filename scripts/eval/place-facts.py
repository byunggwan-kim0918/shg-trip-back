"""관광 장소의 여행자용 사실을 Haiku로 채워 place_facts에 넣는다.

사용: set -a; . ./.env; set +a; python scripts/eval/place-facts.py --db trip_prodcopy [--port 5432] [--scope overseas] [--limit N] [--threads 8]
이미 채운 장소는 건너뛰므로 끊겨도 다시 실행하면 이어서 한다. --count는 모델을 부르지 않고 대상 수와 예상 비용만 낸다.
"""
import argparse
import json
import os
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import anthropic
import psycopg

REGIONS = ['Seoul', 'Busan', 'Jeju', 'Gyeongju', 'Gangneung', 'Jeonju', 'Yeosu', 'Daegu', 'Daejeon', 'Gwangju',
           'Suwon', 'Incheon']
OVERSEAS_REGIONS = [d["name"] for d in json.loads(
    (Path(__file__).resolve().parents[2] / "src/main/resources/destinations.json").read_text(encoding="utf-8"))]
MODEL = 'claude-haiku-5-5'
BATCH = 15
# 견적용 장소당 비용. 국내 2.7만 곳을 채운 비용 기준이다.
COST_PER_PLACE = 0.92 / 27_137

OVERSEAS_INTRO = """너는 한국인 여행자를 위한 해외 여행 작가다. 장소는 일본·중국·대만·베트남에 있다. 대표 명소의 예는 기요미즈데라, 센소지, 타이베이 101, 호이안 구시가지다.
상위 장소를 쓸 때는 대표 장소의 한국어 이름(현지 발음 한글 표기)을 쓴다.
"""

SYSTEM = """너는 한국 국내 여행을 잘 아는 여행 작가다. 장소 목록을 받아 장소마다 여행자에게 필요한 사실을 report_facts로 낸다.
모르는 것은 추측하지 말고 기본값을 쓴다. 장소마다 따로 판단한다.
- v 방문 가치. 3: 그 도시를 대표하는 명소(경복궁, 국립중앙박물관, 불국사, 해운대해수욕장, 성산일출봉). 2: 그 도시 여행에서 찾아갈 만한 명소. 1: 근처에 있으면 들를 만한 곳. 0: 여행자가 일부러 갈 곳이 아님(관공서, 회사, 학교 시설, 공연·경기·행사가 없으면 볼 것이 없는 공연장·경기장·컨벤션센터, 동네 근린공원·체육시설, 일반 매장, 장소가 아닌 지역명).
- p 상위 장소. 이 장소가 다른 관광지 안의 시설이거나(한빛탑 → 엑스포과학공원, 천마총 → 대릉원, 어진박물관 → 경기전) 같은 곳을 다르게 부른 이름이면(경주 불국사 [유네스코 세계유산] → 불국사, 石窟庵と仏国寺 → 불국사) 대표 장소의 한국어 이름을 쓴다. 자기 자신이 대표 장소면 null이다. 역사유적지구·한옥마을 전체·○○동 같은 넓은 구역 이름은 상위 장소로 쓰지 않는다.
- c 정기 휴무 요일. 공식 안내로 확실히 아는 경우만 ["월"]처럼 쓴다. 조금이라도 불확실하면 []이다.
- r 일반 관광객이 들어갈 수 있으면 true. 정기 배편이 없는 섬, 출입 금지 구역, 신자·회원 전용이면 false. 모르면 true.
- t 가야 의미 있는 때. 야경·야시장처럼 밤이 핵심이면 night, 해 진 뒤 볼 것이 없는 야외·궁·박물관이면 day, 둘 다면 any."""

TOOL = {
    "name": "report_facts",
    "description": "장소별 여행자용 사실",
    "input_schema": {
        "type": "object",
        "properties": {
            "places": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "id": {"type": "integer"},
                        "v": {"type": "integer", "enum": [0, 1, 2, 3]},
                        "p": {"type": ["string", "null"]},
                        "c": {"type": "array", "items": {"type": "string",
                                                         "enum": ["월", "화", "수", "목", "금", "토", "일"]}},
                        "r": {"type": "boolean"},
                        "t": {"type": "string", "enum": ["day", "night", "any"]},
                    },
                    "required": ["id", "v", "p", "c", "r", "t"],
                },
            }
        },
        "required": ["places"],
    },
}

lock = threading.Lock()
usage = {"in": 0, "out": 0, "done": 0, "failed": 0}


def targets(conn, limit, regions):
    sql = """
        select p.id, coalesce(p.name_ko, p.name), p.category, p.region, p.address
        from places p
        where p.region = any(%s)
          and (p.place_role in ('SIGHT','ACTIVITY','MARKET')
               or (p.place_role = 'SHOPPING' and coalesce(p.wikidata_sitelinks, 0) >= 1))
          and not exists (select 1 from place_facts f where f.place_id = p.id)
        order by coalesce(p.wikidata_sitelinks, 0) desc, p.id
    """ + (" limit %s" % int(limit) if limit else "")
    with conn.cursor() as cur:
        cur.execute(sql, (regions,))
        return cur.fetchall()


def ask(client, rows, system):
    lines = "\n".join(f"{r[0]} | {r[1]} | {r[2]} | {r[3]} | {r[4]}" for r in rows)
    msg = client.messages.create(
        model=MODEL, max_tokens=4000, system=system, tools=[TOOL],
        tool_choice={"type": "tool", "name": "report_facts"},
        thinking={"type": "disabled"},
        messages=[{"role": "user", "content": "id | 이름 | 분류 | 도시 | 주소\n" + lines}])
    with lock:
        usage["in"] += msg.usage.input_tokens
        usage["out"] += msg.usage.output_tokens
    block = next(b for b in msg.content if b.type == "tool_use")
    return block.input.get("places", [])


def work(client, dsn, rows, system):
    ids = {r[0] for r in rows}
    try:
        facts = [f for f in ask(client, rows, system) if f.get("id") in ids]
    except Exception as e:  # 한 묶음 실패는 다음 실행에서 다시 묻는다
        with lock:
            usage["failed"] += len(rows)
        print("실패:", e, file=sys.stderr)
        return
    with psycopg.connect(dsn, autocommit=True) as conn, conn.cursor() as cur:
        for f in facts:
            pid = f.pop("id")
            cur.execute("insert into place_facts(place_id, facts, model) values (%s, %s, %s) "
                        "on conflict (place_id) do update set facts = excluded.facts, model = excluded.model, "
                        "updated_at = now()", (pid, json.dumps(f, ensure_ascii=False), MODEL))
    with lock:
        usage["done"] += len(facts)
        if usage["done"] // 1000 != (usage["done"] - len(facts)) // 1000:
            print(f"진행 {usage['done']}건, 입력 {usage['in']} / 출력 {usage['out']} 토큰", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--port", type=int, default=5432)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--threads", type=int, default=8)
    ap.add_argument("--scope", choices=["domestic", "overseas"], default="domestic")
    ap.add_argument("--count", action="store_true")
    args = ap.parse_args()
    overseas = args.scope == "overseas"
    regions = OVERSEAS_REGIONS if overseas else REGIONS
    # 국내 프롬프트의 판정 기준은 그대로 두고 첫 문단만 바꾼다. 기준이 달라지면 국내와 해외 사실을 같은 값으로 읽지 못한다.
    system = OVERSEAS_INTRO + SYSTEM.split("\n", 1)[1] if overseas else SYSTEM
    if args.db == "trip":
        sys.exit("trip DB에는 쓰지 않는다. 복사본을 지정한다.")
    dsn = f"host=localhost port={args.port} dbname={args.db} user=weShg password={os.environ['DB_PASSWORD']}"
    with psycopg.connect(dsn, autocommit=True) as conn:
        rows = targets(conn, args.limit, regions)
    print(f"대상 {len(rows)}건, 예상 약 ${len(rows) * COST_PER_PLACE:.2f}", flush=True)
    if args.count:
        return
    client = anthropic.Anthropic(max_retries=4)
    batches = [rows[i:i + BATCH] for i in range(0, len(rows), BATCH)]
    with ThreadPoolExecutor(args.threads) as pool:
        for b in batches:
            pool.submit(work, client, dsn, b, system)
    cost = usage["in"] * 0.10 / 1e6 + usage["out"] * 0.50 / 1e6
    print(f"완료 {usage['done']}건, 실패 {usage['failed']}건, 입력 {usage['in']} / 출력 {usage['out']} 토큰, "
          f"약 ${cost:.2f}")


if __name__ == "__main__":
    main()
