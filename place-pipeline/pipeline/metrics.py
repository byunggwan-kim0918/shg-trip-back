"""
저장 정책 비교 지표. 같은 풀에 kept 열을 단 표(storage_policy.build 결과) 여러 개를 같은 기준으로 잰다.
수치는 정책 사이 비교용이고 정답 판정이 아니다.
  저장·비용      : 담긴 행 수 × 행당 크기(로컬 Postgres 실측), 보강(Haiku)·임베딩 비용(행당 단가).
  유명 보존      : 위키데이터 언어판이 많은(famous_wd) / 위키보야주에 실린(famous_wv) 목적지·경험, 위키보야주 식음료(famous_food).
                   위키보야주를 판정 근거로 쓰는 선별은 famous_wv가 저절로 오르니 famous_wd로도 본다.
                   famous_items는 행의 연결 여부와 상관없이 항목 쪽에서 센다(연결이 빠진 명소도 분모에 든다).
  숨은 장소      : 유명 근거가 없지만 현장 근거(두 출처 확인·고유 도메인·OSM 관광·자연 표시)가 있는 목적지·경험(hidden)과
                   체인이 아닌 식음료(hidden_food).
  지역 다양성    : 첫 정책에서 후보가 있던 격자 가운데 목적지·식음료가 기준 수 이상 담긴 비율, 담긴 음식 종류 수, 체인 비율.
  여행 활용      : 일정 초안이 이름으로 고른 장소가 담긴 비율(draft_places), 그 장소 둘레 식음료 선택지(meal_*),
                   소도시 둘레 식음료·목적지 선택지(town_*).
"""
from .selection import dish_sql

# 행당 저장 크기(places 표·TOAST·색인·halfvec(768) HNSW m=16 포함). bench/pg_storage_bench.py 결과로 맞춘다.
BYTES_PER_PLACE = 4546
# 보강(Haiku 배치) 행당 비용과 임베딩(text-embedding-3-small 100만 토큰 0.02달러 × 행당 약 120토큰) 행당 비용.
ENRICH_USD_PER_PLACE = 13 / 240000
EMBED_USD_PER_PLACE = 0.02 * 120 / 1_000_000
COVERAGE_CELL = 0.1
# 격자에 후보가 이만큼은 있어야 커버리지 분모에 넣는다(바다·산간 빈 격자 제외).
COVERAGE_MIN_POOL = 20
COVERAGE_MIN_DESTINATIONS = 3
COVERAGE_MIN_FOOD = 5
FAMOUS_SITELINKS = 5
FAMOUS_ITEM_SITELINKS = 10
FAMOUS_ITEM_METERS = 300
SIGHT_ROLES = ("destination", "experience")
# 일정 장소에서 걸어서 식사하러 갈 거리(m)와, 소도시에서 식사·관광 선택지를 세는 거리(m).
# 초안 이름에 맞는 행들 가운데 대표 행에서 이 거리 안의 행만 같은 장소로 본다(같은 이름의 다른 곳·이름을 품은 가게 제외).
DRAFT_SAME_PLACE_METERS = 1000
MEAL_METERS = 800
TOWN_FOOD_METERS = 3000
TOWN_SIGHT_METERS = 5000
_HAVERSINE = ("2 * 6371000 * asin(sqrt(pow(sin(radians(b.lat - a.lat) / 2), 2) + cos(radians(a.lat)) "
              "* cos(radians(b.lat)) * pow(sin(radians(b.lon - a.lon) / 2), 2)))")
# TOWN_SIGHT_METERS를 이웃 칸까지 덮는 칸 크기(도). 위도 50°에서도 경도 0.07°가 5km를 넘는다.
_NEAR_CELL = 0.07


def famous_item_matches(con, wikidata_raw, selection, table="famous_items", area_items=None):
    """언어판이 많은 Wikidata 항목마다 가까운 풀 행 가운데 이름이 같거나 한쪽이 다른 쪽을 품은 행(qid, source, source_id).
    숙박·교통·식음료 행만 맞는 항목(역·호텔·식당 이름)과 area_items(열 qid)의 지명 항목은 명소가 아니라서 넣지 않는다."""
    areas = f"AND qid NOT IN (SELECT qid FROM {area_items})" if area_items else ""
    con.execute(f"""
        CREATE OR REPLACE TABLE {table} AS
        WITH w AS (
            SELECT qid, lat, lon, strip_accents(name_key(label)) AS k,
                   floor(lat / 0.01)::BIGINT AS cy, floor(lon / 0.01)::BIGINT AS cx
            FROM {wikidata_raw}, unnest([ja::VARCHAR, en::VARCHAR]) AS l(label)
            WHERE label IS NOT NULL AND sitelinks >= {FAMOUS_ITEM_SITELINKS} {areas}
        ),
        p AS (
            SELECT source, source_id, role, lat, lon, strip_accents(name_key(name)) AS k,
                   floor(lat / 0.01)::BIGINT + dy AS cy, floor(lon / 0.01)::BIGINT + dx AS cx
            FROM {selection}, (SELECT unnest([-1, 0, 1]) AS dy), (SELECT unnest([-1, 0, 1]) AS dx)
        ),
        m AS (
            SELECT DISTINCT b.qid, a.source, a.source_id, a.role FROM p a JOIN w b USING (cy, cx)
            WHERE length(b.k) >= 3 AND (a.k = b.k OR contains(a.k, b.k) OR (length(a.k) >= 3 AND contains(b.k, a.k)))
              AND {_HAVERSINE} <= {FAMOUS_ITEM_METERS}
        )
        SELECT qid, source, source_id FROM m
        WHERE qid IN (SELECT qid FROM m GROUP BY 1 HAVING bool_or(role NOT IN ('support', 'food')))
    """)
    return table


def compare(con, stored, evidence, wikivoyage=None, draft_places=None, towns=None, famous_items=None,
            bytes_per_place=BYTES_PER_PLACE):
    """stored: {이름: kept 열이 있는 표}. evidence는 모든 표의 행을 덮는 풀이다.
    draft_places: (place_key, source, source_id) 표. 같은 place_key의 행 하나라도 담기면 그 장소가 남은 것이다.
    towns: (town_id, lat, lon) 표. famous_items: famous_item_matches 결과. 반환은 {이름: {지표: 값}}."""
    wv = (f"LEFT JOIN (SELECT source, source_id, true AS listed FROM {wikivoyage}) v USING (source, source_id)"
          if wikivoyage else "")
    listed = "coalesce(v.listed, false)" if wikivoyage else "false"
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE metric_marks AS
        SELECT e.source, e.source_id,
               coalesce(e.wd_sitelinks, 0) >= {FAMOUS_SITELINKS} AS famous_wd, {listed} AS famous_wv,
               coalesce(e.independent_confirm, false) OR coalesce(e.web_kind = 'own', false) AS local_evidence
        FROM {evidence} e {wv}
    """)
    first = next(iter(stored.values()))
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE metric_cells AS
        SELECT floor(lat / {COVERAGE_CELL})::BIGINT || ':' || floor(lon / {COVERAGE_CELL})::BIGINT AS cov_cell
        FROM {first} GROUP BY 1 HAVING count(*) >= {COVERAGE_MIN_POOL}
    """)
    return {name: _measure(con, table, draft_places, towns, famous_items, bytes_per_place)
            for name, table in stored.items()}


def _measure(con, table, draft_places, towns, famous_items, bytes_per_place):
    sight = ", ".join(f"'{r}'" for r in SIGHT_ROLES)
    columns = {c[0] for c in con.execute(f"DESCRIBE SELECT * FROM {table}").fetchall()}
    rows = "s.* EXCLUDE (dish)" if "dish" in columns else "s.*"
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE metric_rows AS
        SELECT {rows}, {dish_sql('s.role', 's.pool_category', 's.osm_cuisine')} AS dish,
               coalesce(m.famous_wd, false) AS famous_wd, coalesce(m.famous_wv, false) AS famous_wv,
               coalesce(m.local_evidence, false) OR coalesce(s.osm_signal IN ('attraction', 'nature', 'heritage'), false)
                   AS local_evidence,
               floor(s.lat / {COVERAGE_CELL})::BIGINT || ':' || floor(s.lon / {COVERAGE_CELL})::BIGINT AS cov_cell
        FROM {table} s LEFT JOIN metric_marks m USING (source, source_id)
    """)
    r = con.execute(f"""
        SELECT count(*) FILTER (WHERE kept),
               count(*) FILTER (WHERE kept AND role IN ({sight})),
               count(*) FILTER (WHERE kept AND role = 'food'),
               count(*) FILTER (WHERE kept AND role = 'support'),
               count(*) FILTER (WHERE kept AND source = 'osm'),
               {_share("famous_wd AND role IN (" + sight + ")")},
               {_share("famous_wv AND role IN (" + sight + ")")},
               {_share("famous_wv AND role = 'food'")},
               {_share("NOT famous_wd AND NOT famous_wv AND local_evidence AND role IN (" + sight + ")"
                       " AND outcome IN ('recommend', 'situational')")},
               {_share("NOT famous_wv AND role = 'food' AND NOT coalesce(chain, false) AND local_evidence"
                       " AND outcome IN ('recommend', 'situational')")},
               count(DISTINCT dish) FILTER (WHERE kept AND role = 'food'),
               count(*) FILTER (WHERE kept AND role = 'food' AND chain)
        FROM metric_rows
    """).fetchone()
    cov = con.execute(f"""
        WITH c AS (
            SELECT cov_cell, count(*) FILTER (WHERE kept AND role = 'destination') AS dest,
                   count(*) FILTER (WHERE kept AND role = 'food') AS food,
                   count(DISTINCT dish) FILTER (WHERE kept AND role = 'food') AS dishes
            FROM metric_cells LEFT JOIN metric_rows USING (cov_cell) GROUP BY 1
        )
        SELECT count(*), count(*) FILTER (WHERE dest >= {COVERAGE_MIN_DESTINATIONS}),
               count(*) FILTER (WHERE food >= {COVERAGE_MIN_FOOD}), avg(dishes) FILTER (WHERE food >= {COVERAGE_MIN_FOOD})
        FROM c
    """).fetchone()
    result = {
        "places": r[0], "sights": r[1], "food": r[2], "support": r[3], "osm_rows": r[4],
        "storage_mb": round(r[0] * bytes_per_place / (1 << 20)),
        "enrich_embed_usd": round(r[0] * (ENRICH_USD_PER_PLACE + EMBED_USD_PER_PLACE), 1),
        "famous_wd": r[5], "famous_wv": r[6], "famous_food": r[7], "hidden": r[8], "hidden_food": r[9],
        "cells_destination_cover": _ratio(cov[1], cov[0]), "cells_food_cover": _ratio(cov[2], cov[0]),
        "dishes": r[10], "dishes_per_cell": round(cov[3] or 0, 1), "food_chain_share": _ratio(r[11], r[2]),
    }
    if famous_items:
        result["famous_items"] = con.execute(f"""
            SELECT round(avg(kept::INT), 3) FROM (
                SELECT f.qid, bool_or(m.kept) AS kept FROM {famous_items} f JOIN metric_rows m USING (source, source_id)
                GROUP BY 1)
        """).fetchone()[0]
    if draft_places:
        result.update(_draft_utility(con, draft_places))
    if towns:
        result.update(_town_options(con, towns))
    return result


def _draft_utility(con, draft_places):
    """장소마다 대표 행(선별 등급·지명도가 가장 높은 행)과 그 둘레 행 가운데 하나라도 담겼는지, 대표 행 둘레 식음료 수."""
    row = con.execute(f"""
        WITH m AS (
            SELECT d.place_key, r.kept, r.lat, r.lon,
                   row_number() OVER (PARTITION BY d.place_key ORDER BY
                       CASE r.outcome WHEN 'recommend' THEN 3 WHEN 'situational' THEN 2 WHEN 'review' THEN 1 ELSE 0 END DESC,
                       CASE r.notability WHEN 'high' THEN 3 WHEN 'medium' THEN 2 WHEN 'low' THEN 1 ELSE 0 END DESC,
                       r.role = 'food', r.source, r.source_id) AS rank
            FROM {draft_places} d JOIN metric_rows r USING (source, source_id)
        ),
        d AS (
            SELECT a.place_key, a.lat, a.lon, bool_or(b.kept) AS kept
            FROM (SELECT * FROM m WHERE rank = 1) a JOIN m b USING (place_key)
            WHERE {_HAVERSINE} <= {DRAFT_SAME_PLACE_METERS}
            GROUP BY 1, 2, 3
        ),
        a AS (SELECT *, floor(lat / {_NEAR_CELL})::BIGINT AS cy, floor(lon / {_NEAR_CELL})::BIGINT AS cx FROM d),
        b AS (SELECT lat, lon, dish, floor(lat / {_NEAR_CELL})::BIGINT + dy AS cy, floor(lon / {_NEAR_CELL})::BIGINT + dx AS cx
              FROM metric_rows, (SELECT unnest([-1, 0, 1]) AS dy), (SELECT unnest([-1, 0, 1]) AS dx)
              WHERE kept AND role = 'food'),
        near AS (SELECT a.place_key, count(b.lat) AS food, count(DISTINCT b.dish) AS dishes
                 FROM a LEFT JOIN b ON a.cy = b.cy AND a.cx = b.cx AND {_HAVERSINE} <= {MEAL_METERS} GROUP BY 1)
        SELECT avg(d.kept::INT), median(near.food), median(near.dishes),
               avg((near.food >= 10 AND near.dishes >= 4)::INT)
        FROM d JOIN near USING (place_key)
    """).fetchone()
    return {"draft_places": _round(row[0]), "meal_median": row[1], "meal_dishes_median": row[2],
            "meal_ok": _round(row[3])}


def _town_options(con, towns):
    row = con.execute(f"""
        WITH a AS (SELECT town_id, lat, lon, floor(lat / {_NEAR_CELL})::BIGINT AS cy, floor(lon / {_NEAR_CELL})::BIGINT AS cx
                   FROM {towns}),
        b AS (SELECT lat, lon, role, kept, floor(lat / {_NEAR_CELL})::BIGINT + dy AS cy,
                     floor(lon / {_NEAR_CELL})::BIGINT + dx AS cx
              FROM metric_rows, (SELECT unnest([-1, 0, 1]) AS dy), (SELECT unnest([-1, 0, 1]) AS dx)
              WHERE role IN ('food', 'destination', 'experience') AND outcome IN ('recommend', 'situational')),
        near AS (
            SELECT a.town_id,
                   count(*) FILTER (WHERE b.kept AND b.role = 'food' AND {_HAVERSINE} <= {TOWN_FOOD_METERS}) AS food,
                   count(*) FILTER (WHERE b.role = 'food' AND {_HAVERSINE} <= {TOWN_FOOD_METERS}) AS food_pool,
                   count(*) FILTER (WHERE b.kept AND b.role <> 'food' AND {_HAVERSINE} <= {TOWN_SIGHT_METERS}) AS sights
            FROM a LEFT JOIN b ON a.cy = b.cy AND a.cx = b.cx GROUP BY 1
        )
        SELECT avg((food >= 3 AND sights >= 1)::INT), median(food),
               avg(CASE WHEN food_pool > 0 THEN least(food, 20) / least(food_pool, 20)::DOUBLE END)
        FROM near
    """).fetchone()
    return {"town_ok": _round(row[0]), "town_food_median": row[1], "town_food_kept20": _round(row[2])}


def _share(condition):
    return (f"round(count(*) FILTER (WHERE {condition} AND kept) "
            f"/ nullif(count(*) FILTER (WHERE {condition}), 0), 3)")


def render(results, keys=None):
    """비교 결과 Markdown 표."""
    keys = keys or list(next(iter(results.values())).keys())
    lines = ["| 정책 | " + " | ".join(keys) + " |", "|" + "---|" * (len(keys) + 1)]
    for name, result in results.items():
        lines.append(f"| {name} | " + " | ".join(str(result.get(k)) for k in keys) + " |")
    return "\n".join(lines)


def _round(value):
    return round(value, 3) if value is not None else None


def _ratio(part, whole):
    return round(part / whole, 3) if whole else None
