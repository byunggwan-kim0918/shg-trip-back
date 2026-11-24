"""
좌표가 틀린 동명 중복 행. 유명 Wikidata 항목과 이름이 같은 명소 행이 항목 좌표에서 멀리 떨어져 있는데 주소의 동네 이름이
항목 둘레의 같은 이름 행과 같으면, 같은 장소를 잘못된 좌표로 적은 행이다(주소 지오코딩 오류). 현지어 이름으로 찾을 때
이 행이 걸리면 일정이 엉뚱한 곳으로 안내한다.
같은 이름의 다른 장소(흔한 신사·절 이름, 분원)는 주소가 달라 걸리지 않는다. 그 이름을 쓰는 Wikidata 항목이 나라에 하나일
때만 보고, 넓은 면(공원·산·강)은 좌표가 면의 한 점이라 보지 않는다. 확인할 근거(가까운 행·주소)가 없으면 건드리지 않는다.
"""
from .budget_policy import SIGHT_ROLES
from .dedup import LARGE_FEATURE_PREFIXES
from .metrics import _HAVERSINE

MIN_SITELINKS = 3
MISLOCATED_METERS = 1500
# 항목 좌표가 시설 입구가 아닌 경내·건물 중심이라 제자리 행도 수백 m 떨어질 수 있다.
NEAR_METERS = 1000
# 괄호 안 설명("浅草寺(雷門)")은 같은 장소의 부분 이름이라 떼고 비교한다.
_BARE_KEY = "strip_accents(name_key(trim(regexp_replace({c}, '[(（][^)）]*[)）]', '', 'g'))))"
# 주소의 동네 이름: 첫 번지 숫자 바로 앞의 한자·가나 덩어리("太宰府市宰府1-12" → 太宰府市宰府). 도·시 이름이
# 붙기도 하고 빠지기도 해서, 비교할 때는 한쪽이 다른 쪽 끝과 같으면 같은 동네로 본다.
_STREET = ("nullif(regexp_extract(replace(nfc_normalize(coalesce({c}, '')), ' ', ''), "
           "'([\\p{{Han}}\\p{{Hiragana}}\\p{{Katakana}}ー]+)[0-9０-９]', 1), '')")


def find(con, rows, wikidata, area_items=None, reference=None, table="mislocated"):
    """rows(source, source_id, name, lat, lon, role, pool_category, address)에서 좌표가 틀린 동명 중복 행.
    reference(name, lat, lon, address)는 제자리 근거를 찾을 행이다. 제자리 행이 중복 보류로 풀 밖에 있을 수 있어
    원본 후보 전체를 준다. 없으면 rows에서 찾는다. 결과 열: source, source_id, qid, meters(항목 좌표에서), street."""
    areas = f"AND qid NOT IN (SELECT qid FROM {area_items})" if area_items else ""
    large = " OR ".join(f"starts_with(coalesce(r.pool_category, ''), '{p}')" for p in LARGE_FEATURE_PREFIXES)
    sights = ", ".join(f"'{r}'" for r in SIGHT_ROLES)
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE mislocated_items AS
        WITH labels AS (
            SELECT DISTINCT qid, sitelinks, lat, lon, strip_accents(name_key(label)) AS k
            FROM {wikidata}, unnest([ja::VARCHAR, en::VARCHAR]) AS l(label)
            WHERE label IS NOT NULL AND lat IS NOT NULL
        ),
        unique_keys AS (SELECT k FROM labels WHERE length(k) >= 3 GROUP BY k HAVING count(DISTINCT qid) = 1)
        SELECT qid, lat, lon, k FROM labels JOIN unique_keys USING (k)
        WHERE sitelinks >= {MIN_SITELINKS} {areas}
    """)
    con.execute(f"""
        CREATE OR REPLACE TABLE {table} AS
        WITH m AS (
            SELECT r.source, r.source_id, i.qid, {_STREET.format(c='r.address')} AS street,
                   {_HAVERSINE.replace('a.', 'r.').replace('b.', 'i.')} AS meters
            FROM {rows} r JOIN mislocated_items i ON {_BARE_KEY.format(c='r.name')} = i.k
            WHERE r.role IN ({sights}) AND NOT ({large})
        ),
        ref AS (
            SELECT i.qid, {_STREET.format(c='r.address')} AS street,
                   {_HAVERSINE.replace('a.', 'r.').replace('b.', 'i.')} AS meters
            FROM {reference or rows} r JOIN mislocated_items i ON {_BARE_KEY.format(c='r.name')} = i.k
            WHERE abs(r.lat - i.lat) < 0.02 AND abs(r.lon - i.lon) < 0.02
        ),
        near AS (SELECT DISTINCT qid, street FROM ref WHERE meters <= {NEAR_METERS} AND length(street) >= 2)
        SELECT m.source, m.source_id, m.qid, round(m.meters) AS meters, m.street
        FROM m JOIN near n ON n.qid = m.qid AND length(m.street) >= 2
                          AND (ends_with(m.street, n.street) OR ends_with(n.street, m.street))
        WHERE m.meters > {MISLOCATED_METERS}
        QUALIFY row_number() OVER (PARTITION BY m.source, m.source_id ORDER BY m.meters) = 1
    """)
    return table
