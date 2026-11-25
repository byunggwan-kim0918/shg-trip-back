"""
예산 정책: 저장 상한(장소 수)이 있을 때 기준 선별(storage_policy 결과의 kept 행)에서 운영 DB에 담을 행을 고른다.
행마다 "이 행을 하나 더 담으면 여행 선택지가 얼마나 늘어나는가"를 매기고 큰 것부터 상한까지 담는다.
  한계 가치 = 고유 가치 × 수요 × Π (같은 묶음 안 순위) ** -α
  고유 가치 : 여행자 지명도(Wikidata 언어판·Wikivoyage 수록), 현장 근거, 식음료 품질·지역 특색.
  수요      : 식음료·숙박만. 둘레(약 3km)의 명소 (1 + 지명도) 합과 거주지 인구 항의 합 ** demand_exponent.
  순위 할인 : 같은 격자·같은 종류에서 k번째 행은 k ** -α만큼만 값을 친다. 좁은 격자와 넓은 격자를 함께 할인해
              한 동네에 같은 종류가 몰리지 않고, 넓은 지역마다 종류별 첫 선택지가 먼저 들어간다.
묶음마다 순위에 따라 줄어드는 효용의 합은 한계 가치가 큰 순서로 고르는 탐욕 선택이 최적해라, 한계 가치 순으로 자른다.
유명 명소는 한계 가치와 상관없이 먼저 담는다. 몫은 역할 묶음(명소·식음료·숙박 교통)에만 두고 나라별 몫은 두지 않는다.
같은 한계 가치 기준선을 모든 나라에 쓰면 나라·지역별 행 수는 데이터에서 정해진다.
"""
from dataclasses import dataclass, field

from .metrics import FAMOUS_ITEM_METERS, _HAVERSINE

FAMOUS_SITELINKS = 10
# 지명도를 줄 Wikidata 항목의 최소 언어판 수. 이름을 품기만 한 연결은 1~2개 항목(지역 사업체·행정)에서 틀리기 쉽다.
ITEM_MIN_SITELINKS = 1
ITEM_MIN_SITELINKS_CONTAINED = 3
SIGHT_ROLES = ("destination", "experience", "general")
CAFE_PREFIXES = ("Dining and Drinking > Cafe", "Dining and Drinking > Bakery", "Dining and Drinking > Dessert Shop",
                 "Dining and Drinking > Tea Room", "cuisine:coffee", "cuisine:cake", "cuisine:ice_cream")
BAR_PREFIXES = ("Dining and Drinking > Bar", "Dining and Drinking > Brewery", "Dining and Drinking > Winery",
                "Dining and Drinking > Distillery", "Dining and Drinking > Night Club", "cuisine:bar", "cuisine:pub")
LODGING_PREFIX = "Travel and Transportation > Lodging"
# 이름에 명소 이름을 품은 식음료·매장은 그 명소 안 입점 가게라 명소의 지명도를 받지 않는다.
TENANT_PREFIXES = ("Dining and Drinking", "Retail")


@dataclass(frozen=True)
class Groups:
    """순위 할인 묶음 하나: 격자 크기(도), 종류 열, 할인 지수."""
    cell: float
    kind: str
    alpha: float


@dataclass(frozen=True)
class Weights:
    role_base: dict = field(default_factory=lambda: {"destination": 1.0, "experience": 0.8, "general": 0.5,
                                                     "food": 1.0, "lodging": 0.8, "transit": 0.05})
    wikivoyage: float = 1.0
    # 한국어 위키 문서가 있는 항목은 한국인 여행자가 찾는 곳이다.
    korean: float = 0.7
    recommend: float = 0.5
    # 선별 단계의 지명도 판정(여러 약한 근거의 합). Wikidata·Wikivoyage에 없는 상점가·시장을 가른다.
    notability: float = 1.0
    local_evidence: float = 0.4
    rich_info: float = 0.2
    specialty: float = 0.6
    chain_factor: float = 0.4
    # 같은 항목에 맞는 대표 행 말고 나머지 행(본전·문·부속 시설·중복 행)이 받는 지명도 비율.
    part_share: float = 0.3
    demand_cell: float = 0.01
    # 끼니 k번째의 값이 수요 ** β × k ** -α라 격자별 식음료 수는 수요 ** (β/α)에 비례한다.
    demand_exponent: float = 0.5
    sight_groups: tuple = (Groups(0.05, "kind", 0.5), Groups(0.25, "kind", 0.25))
    food_groups: tuple = (Groups(0.01, "kind", 0.7), Groups(0.01, "kind_class", 0.5), Groups(0.1, "kind", 0.2))
    support_groups: tuple = (Groups(0.02, "kind_class", 1.0), Groups(0.1, "kind_class", 0.3))


def item_fame(con, rows, wikidata, area_items=None, table="budget_item_fame"):
    """행마다 300m 안에서 이름이 같거나 한쪽이 다른 쪽을 품은 Wikidata 항목(qid, sitelinks, best).
    best는 항목마다 하나, 이름이 같고 명소 역할이고 가까운 행이다. 행 연결이 빠진 명소도 지명도를 받는다."""
    areas = f"AND qid NOT IN (SELECT qid FROM {area_items})" if area_items else ""
    tenant = " OR ".join(f"starts_with(coalesce(a.pool_category, ''), '{p}')" for p in TENANT_PREFIXES)
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE {table} AS
        WITH w AS (
            SELECT qid, sitelinks, coalesce(kowiki, false) AS kowiki, lat, lon, strip_accents(name_key(label)) AS k,
                   floor(lat / 0.01)::BIGINT AS cy, floor(lon / 0.01)::BIGINT AS cx
            FROM {wikidata}, unnest([ja::VARCHAR, en::VARCHAR]) AS l(label)
            WHERE label IS NOT NULL AND sitelinks >= {ITEM_MIN_SITELINKS} {areas}
        ),
        p AS (
            SELECT source, source_id, role, pool_category, lat, lon, strip_accents(name_key(name)) AS k,
                   floor(lat / 0.01)::BIGINT + dy AS cy, floor(lon / 0.01)::BIGINT + dx AS cx
            FROM {rows}, (SELECT unnest([-1, 0, 1]) AS dy), (SELECT unnest([-1, 0, 1]) AS dx)
        ),
        m AS (
            SELECT a.source, a.source_id, a.role, b.qid, b.sitelinks, b.kowiki, a.k = b.k AS exact, {_HAVERSINE} AS meters
            FROM p a JOIN w b USING (cy, cx)
            WHERE length(b.k) >= 3 AND (a.k = b.k OR b.sitelinks >= {ITEM_MIN_SITELINKS_CONTAINED})
              AND (a.k = b.k OR (contains(a.k, b.k) AND NOT ({tenant}))
                   OR (length(a.k) >= 3 AND contains(b.k, a.k) AND a.role <> 'food'))
              AND {_HAVERSINE} <= {FAMOUS_ITEM_METERS}
        ),
        one AS (SELECT * FROM m QUALIFY row_number() OVER (PARTITION BY source, source_id, qid ORDER BY meters) = 1)
        SELECT source, source_id, qid, sitelinks, kowiki,
               row_number() OVER (PARTITION BY qid ORDER BY exact DESC, role IN ({_sql_list(SIGHT_ROLES)}) DESC,
                                  meters, source_id) = 1 AS best
        FROM one
    """)
    return table


def values(con, base, evidence, wikivoyage, settlements, wikidata, area_items=None, weights=Weights(),
           table="budget_values"):
    """base의 kept 행마다 한계 가치(value)와 유명 여부(famous)를 단 표. settlements는 (lat, lon, pop) 표다."""
    w = weights
    con.execute(f"CREATE OR REPLACE TEMP TABLE budget_kept AS SELECT * FROM {base} WHERE kept")
    item_fame(con, "budget_kept", wikidata, area_items)
    cafe = " OR ".join(f"starts_with(kind, '{p}')" for p in CAFE_PREFIXES)
    bar = " OR ".join(f"starts_with(kind, '{p}')" for p in BAR_PREFIXES)
    rb = w.role_base
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE budget_rows AS
        WITH f AS (
            SELECT source, source_id, max(sitelinks) FILTER (WHERE best) AS best_sitelinks,
                   bool_or(kowiki) FILTER (WHERE best) AS kowiki,
                   max(sitelinks) FILTER (WHERE NOT best) AS part_sitelinks
            FROM budget_item_fame GROUP BY 1, 2
        ),
        claimed AS (SELECT DISTINCT qid FROM budget_item_fame WHERE best),
        b AS (
            SELECT s.source, s.source_id, s.lat, s.lon, s.role, s.outcome, s.info_level, s.food_quality, s.notability,
                   coalesce(s.chain, false) AS chain, coalesce(s.flagship, false) OR coalesce(s.local_specialty, false)
                       AS specialty,
                   coalesce(s.dish, s.pool_category, '') AS kind,
                   -- 행 자신의 연결이 다른 행이 대표인 항목이면 그 항목의 부속·중복 행이다.
                   CASE WHEN e.wd_qid IN (SELECT qid FROM claimed) AND f.best_sitelinks IS NULL THEN 0
                        ELSE coalesce(e.wd_sitelinks, 0) END AS own_sitelinks,
                   coalesce(f.best_sitelinks, 0) AS best_sitelinks,
                   greatest(coalesce(f.part_sitelinks, 0),
                            CASE WHEN e.wd_qid IN (SELECT qid FROM claimed) AND f.best_sitelinks IS NULL
                                 THEN coalesce(e.wd_sitelinks, 0) ELSE 0 END) AS part_sitelinks,
                   coalesce(v.wv_articles, 0) AS wv, coalesce(f.kowiki, false) AS kowiki,
                   coalesce(e.independent_confirm, false) OR coalesce(e.web_kind = 'own', false)
                       OR coalesce(s.osm_signal IN ('attraction', 'nature', 'heritage'), false) AS local_evidence
            FROM budget_kept s
            LEFT JOIN {evidence} e USING (source, source_id)
            LEFT JOIN f USING (source, source_id)
            LEFT JOIN (SELECT source, source_id, max(wv_articles) AS wv_articles FROM {wikivoyage} GROUP BY ALL) v
                USING (source, source_id)
        )
        SELECT *,
               CASE WHEN role = 'food' THEN CASE WHEN {cafe} THEN 'cafe' WHEN {bar} THEN 'bar' ELSE 'meal' END
                    WHEN role = 'support' THEN CASE WHEN starts_with(kind, '{LODGING_PREFIX}') THEN 'lodging'
                                                    ELSE 'transit' END
                    ELSE role END AS kind_class,
               greatest(ln(1 + greatest(own_sitelinks, best_sitelinks)), {w.part_share} * ln(1 + part_sitelinks))
                   + {w.wikivoyage} * ln(1 + wv) + {w.korean} * kowiki::INT AS fame
        FROM b
    """)
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE budget_demand AS
        WITH m AS (
            SELECT floor(lat / {w.demand_cell})::BIGINT AS cy, floor(lon / {w.demand_cell})::BIGINT AS cx,
                   sum(1 + fame) AS mass
            FROM budget_rows WHERE role IN ('destination', 'experience') GROUP BY 1, 2
            UNION ALL
            SELECT floor(lat / {w.demand_cell})::BIGINT, floor(lon / {w.demand_cell})::BIGINT,
                   sum(ln(1 + coalesce(pop, 0) / 1000.0) + 0.1)
            FROM {settlements} GROUP BY 1, 2
        )
        SELECT cy + dy AS cy, cx + dx AS cx, pow(1 + sum(mass), {w.demand_exponent}) AS demand
        FROM m, (SELECT unnest([-1, 0, 1]) AS dy), (SELECT unnest([-1, 0, 1]) AS dx)
        GROUP BY 1, 2
    """)
    intrinsic = f"""CASE
        WHEN role IN ({_sql_list(SIGHT_ROLES)}) THEN
            CASE role {" ".join(f"WHEN '{r}' THEN {rb[r]}" for r in SIGHT_ROLES)} END
            * (1 + fame + {w.recommend} * (outcome = 'recommend')::INT + {w.local_evidence} * local_evidence::INT
               + {w.notability} * CASE notability WHEN 'high' THEN 1 WHEN 'medium' THEN 0.5 ELSE 0 END
               + {w.rich_info} * (info_level = 'rich')::INT)
        WHEN role = 'food' THEN {rb['food']}
            * (1 + fame + {w.specialty} * specialty::INT + coalesce(food_quality, 0.5)
               + {w.recommend} * (outcome = 'recommend')::INT)
            * CASE WHEN chain THEN {w.chain_factor} ELSE 1 END
        WHEN kind_class = 'lodging' THEN {rb['lodging']} * (1 + fame + {w.rich_info} * (info_level = 'rich')::INT)
        ELSE {rb['transit']} * (1 + fame) END"""
    discounts = []
    for role_filter, groups in ((f"role IN ({_sql_list(SIGHT_ROLES)})", w.sight_groups),
                                ("role = 'food'", w.food_groups), ("role = 'support'", w.support_groups)):
        factor = " * ".join(
            f"pow(row_number() OVER (PARTITION BY role IN ({_sql_list(SIGHT_ROLES)}), role = 'food', "
            f"floor(lat / {g.cell})::BIGINT, floor(lon / {g.cell})::BIGINT, {g.kind} "
            f"ORDER BY intrinsic DESC, hash(source_id)), -{g.alpha})" for g in groups)
        discounts.append(f"WHEN {role_filter} THEN {factor}")
    con.execute(f"""
        CREATE OR REPLACE TABLE {table} AS
        WITH i AS (SELECT r.*, {intrinsic} AS intrinsic,
                          CASE WHEN role IN ('food', 'support') THEN coalesce(d.demand, 1) ELSE 1 END AS demand
                   FROM budget_rows r
                   LEFT JOIN budget_demand d ON d.cy = floor(r.lat / {w.demand_cell})::BIGINT
                                            AND d.cx = floor(r.lon / {w.demand_cell})::BIGINT)
        SELECT source, source_id, role, kind_class, fame, intrinsic, demand,
               (role IN ({_sql_list(SIGHT_ROLES)}) AND (best_sitelinks >= {FAMOUS_SITELINKS}
                                                       OR own_sitelinks >= {FAMOUS_SITELINKS} OR wv > 0))
                   OR (role = 'food' AND wv > 0) AS famous,
               intrinsic * demand * CASE {" ".join(discounts)} END AS value
        FROM i
    """)
    return table


# 역할 묶음별 몫(전체 상한 대비). 가치 척도가 역할마다 달라 한 줄로 세우면 한쪽이 몰리므로 역할 안에서만 한계 가치로 겨룬다.
DEFAULT_SHARES = {"sight": 0.5, "food": 0.45, "support": 0.05}


# 여행지마다 먼저 담는 최소치. 장소 부족 관문(날마다 관광 1·끼니 2·숙소 1)을 가장 긴 일정(6일)에서 넘고 같은 날 대안을
# 고를 여유를 둔다. 후보가 이보다 적은 여행지는 있는 만큼 담는다.
DESTINATION_FLOORS = {"sight": 40, "meal": 60, "cafe": 10, "lodging": 10}


def assign_destinations(con, rows, destinations, table="budget_destinations", temp=False):
    """rows(source, source_id, lat, lon)를 반경 안 여행지(destinations: name, lat, lon, km)에 배정한다.
    여럿에 들면 반경 대비 가장 가까운 곳이다. 어느 반경에도 없는 행은 결과에 없다. temp면 임시 표로 만든다."""
    km = ("2 * 6371 * asin(sqrt(pow(sin(radians(r.lat - d.lat) / 2), 2) + cos(radians(d.lat)) * cos(radians(r.lat)) "
          "* pow(sin(radians(r.lon - d.lon) / 2), 2)))")
    con.execute(f"""
        CREATE OR REPLACE {"TEMP TABLE" if temp else "TABLE"} {table} AS
        SELECT r.source, r.source_id, d.name AS region
        FROM {rows} r JOIN {destinations} d
            ON abs(r.lat - d.lat) < d.km / 111.0 AND abs(r.lon - d.lon) < d.km / (111.0 * cos(radians(d.lat)))
        WHERE {km} <= d.km
        QUALIFY row_number() OVER (PARTITION BY r.source, r.source_id ORDER BY {km} / d.km, d.name) = 1
    """)
    return table


def build(con, base, value_table, places, table, shares=None, regions=None, floors=None):
    """base의 모든 행에 kept 열을 단 표. 역할 묶음마다 places × 몫까지, 유명 행부터 한계 가치 순으로 담는다.
    한 묶음의 후보가 몫보다 적으면 남는 몫은 다른 묶음이 몫 비율대로 나눠 쓴다.
    regions(source, source_id, region)를 주면 여행지에 배정된 행만 담고, 여행지마다 floors 최소치를 먼저 담는다.
    생성기는 여행지 반경 안 장소만 검색해 반경 밖 행은 담아도 쓰이지 않는다."""
    shares = shares or DEFAULT_SHARES
    group = f"CASE WHEN role IN ({_sql_list(SIGHT_ROLES)}) THEN 'sight' ELSE role END"
    if regions:
        floors = DESTINATION_FLOORS if floors is None else floors
        floor_group = ("CASE WHEN role IN (" + _sql_list(SIGHT_ROLES) + ") THEN 'sight' "
                       "WHEN role = 'food' AND kind_class = 'cafe' THEN 'cafe' WHEN role = 'food' THEN 'meal' "
                       "WHEN kind_class = 'lodging' THEN 'lodging' END")
        floor_limit = " ".join(f"WHEN '{g}' THEN {n}" for g, n in floors.items())
        con.execute(f"""
            CREATE OR REPLACE TEMP TABLE budget_candidates AS
            SELECT v.*, r.region, {group} AS budget_group, {floor_group} AS floor_group
            FROM {value_table} v JOIN {regions} r USING (source, source_id)
        """)
        con.execute(f"""
            CREATE OR REPLACE TEMP TABLE budget_floor AS
            SELECT source, source_id, budget_group FROM budget_candidates
            WHERE floor_group IS NOT NULL
            QUALIFY row_number() OVER (PARTITION BY region, floor_group ORDER BY famous DESC, value DESC, hash(source_id))
                    <= CASE floor_group {floor_limit} ELSE 0 END
        """)
    else:
        con.execute(f"CREATE OR REPLACE TEMP TABLE budget_candidates AS SELECT v.*, {group} AS budget_group FROM {value_table} v")
        con.execute("CREATE OR REPLACE TEMP TABLE budget_floor AS SELECT source, source_id, budget_group "
                    "FROM budget_candidates WHERE false")
    sizes = dict(con.execute("SELECT budget_group, count(*) FROM budget_candidates GROUP BY 1").fetchall())
    quota = _quotas(int(places), shares, sizes)
    floor_counts = dict(con.execute("SELECT budget_group, count(*) FROM budget_floor GROUP BY 1").fetchall())
    limits = " ".join(f"WHEN '{g}' THEN {max(0, n - floor_counts.get(g, 0))}" for g, n in quota.items())
    con.execute(f"""
        CREATE OR REPLACE TABLE {table} AS
        WITH r AS (SELECT c.source, c.source_id, c.budget_group,
                          row_number() OVER (PARTITION BY c.budget_group ORDER BY c.famous DESC, c.value DESC,
                                             hash(c.source_id)) AS budget_rank
                   FROM budget_candidates c ANTI JOIN budget_floor f USING (source, source_id)),
        chosen AS (SELECT source, source_id FROM budget_floor
                   UNION ALL
                   SELECT source, source_id FROM r WHERE budget_rank <= CASE budget_group {limits} ELSE 0 END)
        SELECT s.* EXCLUDE (kept), (c.source IS NOT NULL) AS kept
        FROM {base} s LEFT JOIN chosen c USING (source, source_id)
    """)
    return table


def _quotas(places, shares, sizes):
    quota = {g: 0 for g in shares}
    left, open_groups = places, set(shares)
    while left > 0 and open_groups:
        total = sum(shares[g] for g in open_groups)
        given = 0
        for g in sorted(open_groups):
            n = min(sizes.get(g, 0) - quota[g], round(left * shares[g] / total))
            quota[g] += n
            given += n
        open_groups = {g for g in open_groups if quota[g] < sizes.get(g, 0)}
        left -= given
        if given == 0:
            break
    return quota


def _sql_list(values_):
    return ", ".join(f"'{v}'" for v in values_)
