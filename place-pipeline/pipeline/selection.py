"""
풀(evidence)의 장소마다 역할과 판정을 매긴다. 수량을 줄이는 것이 아니라 일정 생성에 쓸 장소를 고르는 것이 목표다.
  역할 role : destination(관광 목적지) experience(지역 경험) food(식음료) support(숙박·교통·편의) general(일반 시설)
  판정 outcome : recommend(추천 목적지) situational(상황에 따라 활용) review(추가 검토) exclude(일정에 쓰지 않음)
관광·경험 쪽은 지명도(이름 근거·위키 항목·두 출처 확인·고유 도메인·명소 밀집)로, 식음료는 실재 품질로 따로 본다.
정보가 부족한 것은 가치가 낮은 것과 구분해 info_level=sparse로 두고 검토로 보낸다. 위키 항목·출처 수는 근거 하나일 뿐이다.
주변 선택지가 적은 곳일수록 추천 기준을 낮춘다(희소성). 격자마다 고정 개수로 자르지 않는다. 기준값은 표본 판정으로 다시 맞춘다.
"""
import csv
import os

from .rules import literal as _literal
from .rules import load_name_rules, match_sql

_ROLES_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "roles.tsv")

# 근처 알려진 명소가 있으면 관광 동선 안이라는 근거로 본다(m).
SIGHT_CLUSTER_METERS = 300
# 지명도를 높음으로 보는 위키백과 언어판 수. 일반 시설 분류(다리·건축물·비석·매장)는 인프라·업체 항목이 흔해 더 많이 요구한다.
NOTABLE_SITELINKS = 1
NOTABLE_SITELINKS_GENERAL = 3
# 식음료는 위키데이터 항목을 지명도로 보지 않는다. 체인 점포마다 봇이 만든 항목이 있고, 지명과 같은 이름의 가게가 지명 항목에 붙는다.
# 명소 이름 근거를 보지 않는 분류. 가게 이름에 지명(神宮道)이 들어간다.
NOTABLE_NAME_IGNORED = ("Dining and Drinking", "Retail")
# 지명도 중간으로 올리는 약한 근거 개수(두 출처 확인·고유 도메인·명소 밀집 가운데).
MEDIUM_NOTABILITY_SIGNALS = 2
# 이 격자(약 5km) 안에 지명도 높은 목적지가 이보다 적으면 중간 목적지도 추천한다.
SCARCE_DESTINATION_CELL = 0.05
SCARCE_DESTINATIONS = 3

# 식음료 품질 가중치와 추천 기준. 기준은 주변(약 1km) 식음료 수가 많을수록 높아진다.
FOOD_WEIGHTS = {"independent": 0.25, "confidence": 0.15, "recent": 0.15, "longevity": 0.2, "specific": 0.1,
                "contact": 0.15}
FOOD_CELL = 0.01
FOOD_BAR_SPARSE = 0.55
FOOD_BAR_DENSE = 0.75
FOOD_DENSE_COUNT = 500

NEUTRAL = 0.5
UNKNOWN_LONGEVITY = 0.3
LONGEVITY_FULL_YEARS = 5
RECENT_DAYS = 365
# FSQ 행의 이 비율 넘게 같은 확인일을 가지면 일괄 갱신 날짜로 보고 확인 근거로 치지 않는다.
BULK_DATE_MIN_SHARE = 0.02
# 지역 특색 음식: 이 격자(약 25km)에서 한 음식 종류의 비중이 나라 전체 비중의 SPECIALTY_RATIO배 이상이고
# SPECIALTY_MIN_PLACES곳 이상이면 그 지역 특색으로 본다. 특색 음식점은 추천 기준을 SPECIALTY_BAR_RELIEF만큼 낮춘다.
# 2배면 대도시에 몰린 업종(와인바·케밥)까지 특색으로 잡는다.
SPECIALTY_CELL = 0.25
SPECIALTY_RATIO = 3.0
SPECIALTY_MIN_PLACES = 20
SPECIALTY_BAR_RELIEF = 0.1
# 그 자체로는 무엇을 파는지 알 수 없는 식음료 분류.
GENERIC_FOOD = ("Dining and Drinking", "Dining and Drinking > Restaurant", "Dining and Drinking > Bar",
                "Dining and Drinking > Cafe, Coffee, and Tea House")
# FSQ 폐업 기록을 탈락 근거로 쓰지 않는 역할. 자연·종교 시설·역은 사라지지 않는데 FSQ에는 폐업으로 찍힌 사찰이 있다.
CLOSURE_IGNORED_ROLES = ("destination", "support")
# 이 출처에만 있는 행은 분류와 상관없이 지명도로 가른다. 지도용으로 그린 지형·시설이라 출처에 있다는 것만으로 방문 근거가 되지 않는다.
# 그 행의 관광지·문화재 태그와 명소 이름(城跡·古墳)은 행이 있는 이유라 독립 근거가 아니어서 약한 근거 하나씩으로만 센다.
NOTABILITY_REQUIRED_BASES = ("osm_only",)


def load_roles(path=_ROLES_FILE):
    with open(path, encoding="utf-8") as f:
        rows = list(csv.DictReader((line for line in f if not line.startswith("#")), delimiter="\t"))
    seen = set()
    for r in rows:
        if r["label_prefix"] in seen:
            raise ValueError(f"중복 접두어: {r['label_prefix']}")
        seen.add(r["label_prefix"])
    return rows


def build(con, evidence="evidence", candidates="candidates", fsq_raw=None, anchors_csv=None, table="selection",
          osm=None, wikivoyage=None):
    """evidence 행마다 역할·지명도·정보 수준·식음료 품질·판정·사유 한 줄.
    osm은 osm_link.osm_evidence, wikivoyage는 wikivoyage.link 표다(둘 다 없어도 되고, 연결이 없는 행은 판정이 그대로다)."""
    _roles_table(con)
    _bulk_dates(con, fsq_raw)
    _sight_anchors(con, evidence, candidates, anchors_csv)
    rules = load_name_rules()
    columns = {r[0] for r in con.execute(f"DESCRIBE SELECT * FROM {evidence}").fetchall()}
    # 중복 제거 전 풀에는 PART_OF 관계가 없다.
    evidence_cols = "e.*" if "part_of" in columns else "e.*, NULL::VARCHAR AS part_of"
    closure_ignored = ", ".join(f"'{r}'" for r in CLOSURE_IGNORED_ROLES)
    name_ignored = " OR ".join(f"starts_with(b.pool_category, '{_literal(p)}')" for p in NOTABLE_NAME_IGNORED)
    generic_food = ", ".join(f"'{_literal(g)}'" for g in GENERIC_FOOD)
    w = FOOD_WEIGHTS
    # OSM 근거는 더하기만 한다. 표가 없거나 연결이 없는 행은 OSM 열이 NULL이고 판정이 그대로다.
    osm_join = f"LEFT JOIN {osm} o USING (source, source_id)" if osm else ""
    osm_cols = ("o.osm_id, o.osm_tag, o.osm_signal, o.osm_cuisine, o.osm_opening_hours, o.osm_brand, "
                "o.osm_wd_sitelinks, o.osm_heritage, o.osm_website, o.osm_phone" if osm else
                "NULL::BIGINT AS osm_id, NULL::VARCHAR AS osm_tag, NULL::VARCHAR AS osm_signal, "
                "NULL::VARCHAR AS osm_cuisine, NULL::VARCHAR AS osm_opening_hours, NULL::VARCHAR AS osm_brand, "
                "NULL::INTEGER AS osm_wd_sitelinks, NULL::VARCHAR AS osm_heritage, NULL::VARCHAR AS osm_website, "
                "NULL::VARCHAR AS osm_phone")
    osm_phone = "o.osm_phone IS NOT NULL" if osm else "false"
    wv_join = f"LEFT JOIN {wikivoyage} v USING (source, source_id)" if wikivoyage else ""
    wv_listed = "v.wv_articles IS NOT NULL" if wikivoyage else "false"
    # 풀 분류가 일반 시설(광장·건축물)로 붙었어도 OSM이 자연 지형·관광지·문화재라면 관광 목적지로 다시 본다.
    osm_sight = "coalesce(o.osm_signal IN ('nature', 'attraction', 'heritage'), false)" if osm else "false"
    mapped_only = "e.pool_basis IN (" + ", ".join(f"'{b}'" for b in NOTABILITY_REQUIRED_BASES) + ")"
    # OSM 브랜드는 식음료·매장에서만 체인 근거다(명소의 brand 태그는 운영 주체다).
    osm_chain = ("(o.osm_brand IS NOT NULL AND (starts_with(e.pool_category, 'Dining and Drinking') "
                 "OR starts_with(e.pool_category, 'Retail')))") if osm else "false"
    con.execute(f"""
        CREATE OR REPLACE TABLE {table} AS
        WITH base AS (
            SELECT {evidence_cols}, {osm_cols}, c.name, c.name_key, c.lat, c.lon, c.country, c.confidence,
                   len(c.phones) > 0 OR {osm_phone} AS has_phone,
                   CASE WHEN r.role = 'general' AND {osm_sight} THEN 'destination' ELSE r.role END AS role,
                   CASE WHEN r.role = 'general' AND {osm_sight} THEN 'yes'
                        WHEN {mapped_only} AND r.role IN ('destination', 'experience') THEN 'yes'
                        ELSE r.needs_notability END AS needs_notability,
                   CASE WHEN r.role = 'general' AND {osm_sight} THEN 'situational'
                        WHEN {mapped_only} AND r.when_low = '-' THEN 'situational'
                        ELSE r.when_low END AS when_low,
                   e.chain_brand IS NOT NULL OR e.chain_dataset OR e.chain_shared_host OR {osm_chain} AS chain,
                   {wv_listed} AS sig_wikivoyage, {mapped_only} AS mapped_only
            FROM {evidence} e JOIN {candidates} c USING (source, source_id) {osm_join} {wv_join}
            LEFT JOIN (
                SELECT e2.source, e2.source_id, arg_max(m.role, length(m.label_prefix)) AS role,
                       arg_max(m.needs_notability, length(m.label_prefix)) AS needs_notability,
                       arg_max(m.when_low, length(m.label_prefix)) AS when_low
                FROM {evidence} e2 JOIN role_map m ON starts_with(e2.pool_category, m.label_prefix)
                GROUP BY 1, 2
            ) r USING (source, source_id)
        ),
        signals AS (
            SELECT b.*, a.meters AS sight_cluster_m,
                   CASE WHEN b.independent_confirm OR b.wd_qid IS NOT NULL OR b.osm_wd_sitelinks IS NOT NULL THEN 'rich'
                        WHEN b.fsq_created IS NOT NULL OR b.has_phone OR b.web_kind IN ('own', 'sns', 'wiki')
                             OR b.osm_id IS NOT NULL THEN 'partial'
                        ELSE 'sparse' END AS info_level,
                   greatest(coalesce(b.wd_sitelinks, 0), coalesce(b.osm_wd_sitelinks, 0))
                       >= CASE WHEN b.role = 'general' THEN {NOTABLE_SITELINKS_GENERAL}
                               ELSE {NOTABLE_SITELINKS} END AS sig_wikidata,
                   (b.osm_signal IN ('attraction', 'heritage') OR b.osm_heritage IS NOT NULL)
                       AND NOT ({name_ignored}) AND NOT b.mapped_only AS sig_osm_attraction,
                   {match_sql('b.name', 'b.country', rules['sight_notable'])}
                       AND NOT ({name_ignored}) AS notable_name,
                   {match_sql('b.name', 'b.country', rules['sight_general'])} AS sig_general_name,
                   {match_sql('b.name', 'b.country', rules['flagship'])}
                       OR (b.chain_brand IS NOT NULL AND starts_with(b.name_key, name_key(b.chain_brand))
                           AND {match_sql("replace(b.name_key, name_key(b.chain_brand), '')", 'b.country',
                                          rules['flagship_suffix'])}) AS flagship,
                   (CASE WHEN b.independent_confirm THEN 1 ELSE 0 END
                    + CASE WHEN b.web_kind = 'own' OR b.web_wiki THEN 1 ELSE 0 END
                    + CASE WHEN a.meters <= {SIGHT_CLUSTER_METERS} THEN 1 ELSE 0 END
                    + CASE WHEN b.osm_signal = 'nature' THEN 1 ELSE 0 END
                    + CASE WHEN b.mapped_only AND b.osm_signal IN ('attraction', 'heritage') THEN 1 ELSE 0 END)
                       AS weak_signals
            FROM base b LEFT JOIN sight_near a USING (source, source_id)
        ),
        weighed AS (
            SELECT * EXCLUDE (notable_name, weak_signals), notable_name AND NOT mapped_only AS sig_notable_name,
                   weak_signals + CASE WHEN notable_name AND mapped_only THEN 1 ELSE 0 END AS weak_signals
            FROM signals
        ),
        notability AS (
            SELECT *,
                   CASE WHEN sig_wikidata OR sig_wikivoyage THEN 'high'
                        WHEN sig_general_name THEN 'low'
                        WHEN sig_notable_name OR sig_osm_attraction THEN 'high'
                        WHEN weak_signals >= {MEDIUM_NOTABILITY_SIGNALS} THEN 'medium'
                        ELSE 'low' END AS notability,
                   {w['independent']} * CASE WHEN independent_confirm THEN 1 ELSE {NEUTRAL} END
                   + {w['confidence']} * coalesce(least(confidence, 1), {NEUTRAL})
                   + {w['recent']} * CASE WHEN fsq_refreshed IS NULL OR fsq_refreshed IN (SELECT day FROM bulk_dates)
                                          THEN {NEUTRAL}
                                          WHEN fsq_refreshed >= current_date - INTERVAL {RECENT_DAYS} DAY THEN 1
                                          ELSE 0 END
                   + {w['longevity']} * CASE WHEN fsq_created IS NULL THEN {UNKNOWN_LONGEVITY}
                        ELSE least(date_diff('day', fsq_created, current_date) / 365.0 / {LONGEVITY_FULL_YEARS}, 1) END
                   + {w['specific']} * CASE WHEN pool_category IN ({generic_food}) AND osm_cuisine IS NULL THEN 0
                                            ELSE 1 END
                   + {w['contact']} * (CASE WHEN has_phone THEN 0.5 ELSE 0 END
                                       + CASE WHEN (web_kind IS NOT NULL AND web_kind <> 'invalid')
                                                   OR osm_website IS NOT NULL THEN 0.5 ELSE 0 END)
                       AS food_quality,
                   floor(lat / {FOOD_CELL})::BIGINT || ':' || floor(lon / {FOOD_CELL})::BIGINT AS food_cell,
                   {dish_sql()} AS dish,
                   floor(lat / {SPECIALTY_CELL})::BIGINT || ':' || floor(lon / {SPECIALTY_CELL})::BIGINT AS region_cell,
                   floor(lat / {SCARCE_DESTINATION_CELL})::BIGINT || ':'
                       || floor(lon / {SCARCE_DESTINATION_CELL})::BIGINT AS area_cell
            FROM weighed
        ),
        with_parent AS (
            SELECT n.*, p.notability AS parent_notability
            FROM notability n LEFT JOIN notability p ON p.source_id = n.part_of AND p.source_id IS NOT NULL
        ),
        context AS (
            SELECT *,
                   count(*) FILTER (WHERE role = 'food') OVER (PARTITION BY food_cell) AS food_nearby,
                   count(*) FILTER (WHERE role = 'destination' AND notability = 'high') OVER (PARTITION BY area_cell)
                       AS notable_destinations_nearby,
                   count(dish) OVER (PARTITION BY country, region_cell, dish) AS region_dish,
                   count(dish) OVER (PARTITION BY country, region_cell) AS region_dishes,
                   count(dish) OVER (PARTITION BY country, dish) AS country_dish,
                   count(dish) OVER (PARTITION BY country) AS country_dishes
            FROM with_parent
        ),
        specialty AS (
            SELECT *, dish IS NOT NULL AND region_dish >= {SPECIALTY_MIN_PLACES}
                      AND region_dish * country_dishes >= {SPECIALTY_RATIO} * country_dish * region_dishes AS local_specialty
            FROM context
        ),
        judged AS (
            SELECT *,
                   {FOOD_BAR_SPARSE} + ({FOOD_BAR_DENSE} - {FOOD_BAR_SPARSE})
                       * least(ln(1 + food_nearby) / ln(1 + {FOOD_DENSE_COUNT}), 1) AS food_bar,
                   CASE
                     WHEN coalesce(fsq_flagged, false) THEN 'exclude:fsq_flagged'
                     WHEN coalesce(fsq_closed, false) AND role NOT IN ({closure_ignored}) AND NOT independent_confirm
                          THEN 'exclude:fsq_closed'
                     WHEN coalesce(fsq_closed, false) AND role NOT IN ({closure_ignored}) THEN 'review:closure_conflict'
                     WHEN role IS NULL THEN 'review:unknown_role'
                     WHEN role = 'food' THEN NULL
                     WHEN part_of IS NOT NULL AND role IN ('destination', 'general')
                          AND {_rank('parent_notability')} >= {_rank('notability')} THEN 'situational:part_of'
                     WHEN needs_notability = 'no' AND role = 'general' THEN 'exclude:general_facility'
                     WHEN needs_notability = 'no' AND role = 'support' THEN 'situational:support'
                     WHEN needs_notability = 'no' AND role = 'destination' AND info_level = 'sparse'
                          THEN 'review:sparse_info'
                     WHEN needs_notability = 'no' AND role = 'destination' THEN 'recommend:destination'
                     WHEN needs_notability = 'no' AND role = 'experience' AND notability = 'high'
                          THEN 'recommend:notable_experience'
                     WHEN needs_notability = 'no' AND role = 'experience' AND chain THEN 'situational:chain'
                     WHEN needs_notability = 'no' AND role = 'experience' AND info_level = 'sparse'
                          THEN 'review:sparse_info'
                     WHEN needs_notability = 'no' AND role = 'experience' THEN 'situational:experience'
                     WHEN notability = 'high' THEN 'recommend:notable'
                     WHEN notability = 'medium' AND role = 'destination'
                          AND notable_destinations_nearby < {SCARCE_DESTINATIONS} THEN 'recommend:scarce_area'
                     WHEN notability = 'medium' THEN 'situational:medium_notability'
                     WHEN sig_general_name OR when_low = 'exclude' THEN 'exclude:general_facility'
                     WHEN info_level = 'sparse' OR when_low = 'review' THEN 'review:unclear_notability'
                     ELSE 'situational:low_notability'
                   END AS non_food_decision
            FROM specialty
        )
        SELECT source, source_id, name, lat, lon, pool_basis, pool_category, part_of, role, notability, info_level,
               sig_wikidata, sig_wikivoyage, sig_notable_name, sig_general_name, sig_osm_attraction, weak_signals, chain,
               flagship, dish, local_specialty,
               osm_id, osm_tag, osm_signal, osm_cuisine, osm_opening_hours, osm_brand,
               round(food_quality, 3) AS food_quality, round(food_bar, 3) AS food_bar, food_nearby,
               notable_destinations_nearby,
               CASE WHEN role <> 'food' OR non_food_decision IS NOT NULL THEN non_food_decision
                    WHEN sig_wikivoyage THEN 'recommend:notable_food'
                    WHEN chain AND NOT flagship THEN 'situational:chain'
                    WHEN info_level = 'sparse' THEN 'review:sparse_info'
                    WHEN food_quality >= food_bar THEN 'recommend:food_quality'
                    WHEN local_specialty AND food_quality >= food_bar - {SPECIALTY_BAR_RELIEF}
                         THEN 'recommend:local_specialty'
                    ELSE 'situational:food' END AS decision
        FROM judged
    """)
    con.execute(f"""
        CREATE OR REPLACE TABLE {table} AS
        SELECT * EXCLUDE (decision), split_part(decision, ':', 1) AS outcome, split_part(decision, ':', 2) AS reason
        FROM {table}
    """)
    return table


def dish_sql(role="role", category="pool_category", cuisine="osm_cuisine"):
    """식음료 행의 음식 종류. 분류가 총칭이면 OSM 요리 종류(첫 값)를 쓰고, 둘 다 없으면 NULL이다."""
    generic = ", ".join(f"'{_literal(g)}'" for g in GENERIC_FOOD)
    return (f"CASE WHEN {role} <> 'food' THEN NULL WHEN {category} NOT IN ({generic}) THEN {category} "
            f"WHEN {cuisine} IS NOT NULL THEN 'cuisine:' || lower(trim(split_part({cuisine}, ';', 1))) END")


def _rank(column):
    """지명도 비교용 순위. 부모가 모르는 행이면 가장 낮다."""
    return f"CASE {column} WHEN 'high' THEN 3 WHEN 'medium' THEN 2 WHEN 'low' THEN 1 ELSE 0 END"


def _roles_table(con):
    rows = load_roles()
    values = ", ".join(f"('{_literal(r['label_prefix'])}', '{r['role']}', '{r['needs_notability']}', '{r['when_low']}')"
                       for r in rows)
    con.execute(f"""CREATE OR REPLACE TEMP TABLE role_map AS SELECT * FROM (VALUES {values})
                    AS t(label_prefix, role, needs_notability, when_low)""")


def _bulk_dates(con, fsq_raw):
    if not fsq_raw:
        con.execute("CREATE OR REPLACE TEMP TABLE bulk_dates AS SELECT NULL::DATE AS day WHERE false")
        return
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE bulk_dates AS
        WITH d AS (SELECT try_cast(date_refreshed AS DATE) AS day, count(*) AS n FROM {fsq_raw}
                   WHERE date_closed IS NULL GROUP BY 1)
        SELECT day FROM d WHERE day IS NOT NULL AND n > {BULK_DATE_MIN_SHARE} * (SELECT sum(n) FROM d)
    """)


def _sight_anchors(con, evidence, candidates, anchors_csv):
    """행별 가장 가까운 운영 DB 명소까지의 거리(m). 운영 DB에 이미 있는 명소는 자기 자신이 잡혀 거리 0이 된다."""
    if anchors_csv:
        con.execute(f"""CREATE OR REPLACE TEMP TABLE sight_anchors AS
                        SELECT DISTINCT round(latitude::DOUBLE, 4) AS lat, round(longitude::DOUBLE, 4) AS lon
                        FROM read_csv_auto('{_literal(anchors_csv)}') WHERE kind = 'sight'""")
    else:
        con.execute("CREATE OR REPLACE TEMP TABLE sight_anchors AS SELECT NULL::DOUBLE AS lat, NULL::DOUBLE AS lon WHERE false")
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE sight_near AS
        WITH a AS (SELECT *, floor(lat / 0.01)::INT + dy AS gy, floor(lon / 0.01)::INT + dx AS gx
                   FROM sight_anchors, (SELECT unnest([-1, 0, 1]) AS dy), (SELECT unnest([-1, 0, 1]) AS dx)),
             c AS (SELECT e.source, e.source_id, c.lat, c.lon,
                          floor(c.lat / 0.01)::INT AS gy, floor(c.lon / 0.01)::INT AS gx
                   FROM {evidence} e JOIN {candidates} c USING (source, source_id))
        SELECT c.source, c.source_id,
               min(2 * 6371000 * asin(sqrt(pow(sin(radians(a.lat - c.lat) / 2), 2)
                   + cos(radians(c.lat)) * cos(radians(a.lat)) * pow(sin(radians(a.lon - c.lon) / 2), 2)))) AS meters
        FROM c JOIN a USING (gy, gx)
        GROUP BY 1, 2
    """)
