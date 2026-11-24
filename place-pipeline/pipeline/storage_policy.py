"""
저장 정책: 선별 표(selection.build 결과)에서 운영 DB에 담을 행을 고른다. 장소 수 상한은 두지 않는다.
같은 격자·같은 종류가 많을수록 하나를 더 담는 가치가 줄어든다고 보고, quota_roles의 격자·종류마다 체감 몫을 둔다.
  몫 = max(floor, ceil(n ** exponent))   n은 그 격자·종류에서 담을 수 있는 등급의 후보 수(quota_basis=eligible)
                                        또는 추천 등급 후보 수(quota_basis=recommend: 상황 활용은 추천이 모자란 곳만 채운다)
  몫 안에서는 추천 → 상황 활용 순으로, 같은 등급 안에서는 여행자 지명도 → 본점·지역 특색 → 체인 아님 → 품질 순으로 채운다.
후보가 적은 곳(소도시·섬)은 몫이 후보 수보다 커서 다 남고, 같은 종류가 몰린 대도시에서만 줄어든다.
추천 목적지·경험·일반 시설은 몫을 적용하지 않는다. 숙박·교통(support)은 일정의 거점이라 모든 정책이 몫 없이 담는다.
"""
from dataclasses import dataclass

from .selection import FOOD_CELL, SPECIALTY_BAR_RELIEF

# 목적지·경험·일반 시설은 이동 반경이 식음료보다 넓어 몫을 더 넓은 격자(약 5km)에 매긴다.
SIGHT_CELL = 0.05
STORED_ROLES = ("destination", "experience", "food", "general")


@dataclass(frozen=True)
class Policy:
    """tiers는 담을 등급, food_tiers는 식음료만 다르게 담을 때의 등급이다. exponent가 없으면 몫 없이 다 담는다."""
    name: str
    tiers: tuple = ("recommend",)
    food_tiers: tuple = None
    exponent: float = None
    quota_roles: tuple = STORED_ROLES
    floor: int = 3
    quota_basis: str = "eligible"
    food_bar_raise: float = 0.0
    include_osm: bool = False


def build(con, policy, selection="selection", table=None):
    """selection의 모든 행에 kept 열을 단 표를 만든다. 반환은 표 이름."""
    table = table or f"stored_{policy.name}"
    tiers = ", ".join(f"'{t}'" for t in policy.tiers)
    food_tiers = ", ".join(f"'{t}'" for t in (policy.food_tiers or policy.tiers))
    roles = ", ".join(f"'{r}'" for r in STORED_ROLES)
    osm = "true" if policy.include_osm else "source <> 'osm'"
    # 식음료 추천 기준을 근거별 기준선에서 같은 폭만큼 올린다. 지역 특색은 낮춘 기준선에서, 여행자 지명도(notable_food)는 그대로 둔다.
    raised = (f"AND NOT (role = 'food' AND ((reason = 'food_quality' AND food_quality < food_bar + {policy.food_bar_raise}) "
              f"OR (reason = 'local_specialty' AND food_quality < food_bar - {SPECIALTY_BAR_RELIEF} + {policy.food_bar_raise})))"
              ) if policy.food_bar_raise else ""
    eligible = (f"({osm} AND ((role = 'support' AND outcome IN ('recommend', 'situational')) "
                f"OR (role = 'food' AND outcome IN ({food_tiers})) "
                f"OR (role IN ({roles}) AND role <> 'food' AND outcome IN ({tiers}))) {raised})")
    if policy.exponent is None:
        con.execute(f"CREATE OR REPLACE TABLE {table} AS SELECT *, {eligible} AS kept FROM {selection}")
        return table
    quota_roles = ", ".join(f"'{r}'" for r in policy.quota_roles)
    if policy.quota_basis not in ("eligible", "recommend"):
        raise ValueError(f"quota_basis: {policy.quota_basis}")
    counted = "eligible AND outcome = 'recommend'" if policy.quota_basis == "recommend" else "eligible"
    exempt = f"(role NOT IN ({quota_roles}) OR (outcome = 'recommend' AND role <> 'food'))"
    con.execute(f"""
        CREATE OR REPLACE TABLE {table} AS
        WITH e AS (
            SELECT *, {eligible} AS eligible,
                   CASE WHEN role = 'food' THEN floor(lat / {FOOD_CELL})::BIGINT || ':' || floor(lon / {FOOD_CELL})::BIGINT
                        ELSE floor(lat / {SIGHT_CELL})::BIGINT || ':' || floor(lon / {SIGHT_CELL})::BIGINT END
                       || '|' || role || '|' || coalesce(dish, pool_category, '') AS quota_group
            FROM {selection}
        ),
        r AS (
            SELECT *,
                   count(*) FILTER (WHERE {counted}) OVER (PARTITION BY quota_group) AS group_size,
                   row_number() OVER (PARTITION BY quota_group, eligible ORDER BY
                       outcome = 'recommend' DESC,
                       coalesce(sig_wikivoyage, false) OR coalesce(sig_wikidata, false) DESC,
                       coalesce(flagship, false) OR coalesce(local_specialty, false) DESC,
                       NOT coalesce(chain, false) DESC,
                       CASE notability WHEN 'high' THEN 3 WHEN 'medium' THEN 2 WHEN 'low' THEN 1 ELSE 0 END DESC,
                       food_quality DESC NULLS LAST,
                       CASE info_level WHEN 'rich' THEN 3 WHEN 'partial' THEN 2 ELSE 1 END DESC,
                       hash(source_id)) AS group_rank
            FROM e
        )
        SELECT * EXCLUDE (eligible, quota_group, group_size, group_rank),
               eligible AND ({exempt} OR group_rank <= greatest({policy.floor}, ceil(pow(group_size, {policy.exponent}))))
                   AS kept
        FROM r
    """)
    return table
