"""
공급처 사이 같은 장소 후보(L2). 자동으로 묶는 것은 확실한 근거가 있을 때만이다.
  source_id  : Overture 행이 FSQ 원본 ID를 출처로 들고 있다(같은 레코드).
  exact_name : 정확 키가 같고 EXACT_NAME_MAX_METERS 안이다. 대분류까지 같을 때만 자동, 아니면 검토.
이름 변형·포함 관계로 찾은 쌍은 여기서 만들지 않는다. 그 판정은 배치의 PlaceLinkJob이 한다.
"""

# 같은 이름 두 출처의 좌표 차이 상한. 같은 건물 안 다른 층 매장까지 붙지 않을 만큼 좁게 둔다.
EXACT_NAME_MAX_METERS = 50

_HAVERSINE_M = ("2 * 6371000 * asin(sqrt(pow(sin(radians(b.lat - a.lat) / 2), 2) "
                "+ cos(radians(a.lat)) * cos(radians(b.lat)) * pow(sin(radians(b.lon - a.lon) / 2), 2)))")


def build_links(con, candidates="candidates", table="source_links"):
    """eligible 후보끼리의 출처 간 연결. 열: a_source, a_id, b_source, b_id, basis, decision, meters."""
    con.execute(f"""
        CREATE OR REPLACE TABLE {table} AS
        WITH ok AS (SELECT * FROM {candidates} WHERE exclusion IS NULL),
        by_id AS (
            SELECT a.source AS a_source, a.source_id AS a_id, b.source AS b_source, b.source_id AS b_id,
                   'source_id' AS basis, 'AUTO' AS decision, {_HAVERSINE_M} AS meters
            FROM ok a, unnest(a.links) AS l(link)
            JOIN ok b ON b.source = link.source AND b.source_id = link.id
        ),
        by_name AS (
            SELECT a.source, a.source_id, b.source, b.source_id, 'exact_name',
                   CASE WHEN split_part(a.category, ' > ', 1) = split_part(b.category, ' > ', 1)
                        THEN 'AUTO' ELSE 'REVIEW' END,
                   {_HAVERSINE_M}
            FROM ok a JOIN ok b ON a.name_key = b.name_key AND a.source < b.source
            WHERE abs(a.lat - b.lat) < 0.001 AND abs(a.lon - b.lon) < 0.0015
              AND {_HAVERSINE_M} <= {EXACT_NAME_MAX_METERS}
              AND NOT EXISTS (SELECT 1 FROM by_id i
                              WHERE (i.a_id = a.source_id AND i.b_id = b.source_id)
                                 OR (i.a_id = b.source_id AND i.b_id = a.source_id))
        )
        SELECT * FROM by_id UNION ALL SELECT * FROM by_name
    """)
    return table


def within_source_duplicates(con, candidates="candidates"):
    """같은 공급처 안에서 정확 키가 같고 가까운 쌍 수(출처 자체의 중복 정도)."""
    return dict(con.execute(f"""
        WITH ok AS (SELECT * FROM {candidates} WHERE exclusion IS NULL)
        SELECT a.source, count(*)
        FROM ok a JOIN ok b ON a.source = b.source AND a.name_key = b.name_key AND a.source_id < b.source_id
        WHERE abs(a.lat - b.lat) < 0.001 AND abs(a.lon - b.lon) < 0.0015 AND {_HAVERSINE_M} <= {EXACT_NAME_MAX_METERS}
        GROUP BY 1
    """).fetchall())
