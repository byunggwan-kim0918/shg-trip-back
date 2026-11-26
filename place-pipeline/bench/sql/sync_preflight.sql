-- sync_core 반영 전 읽기 전용 점검. 운영과 운영 복사본(로컬)에서 똑같이 돌려 결과를 견준다.
-- 실행: psql -v country="'JP'" -v changed='1,2,3' -v merged='2,3' -f bench/sql/sync_preflight.sql
--   changed : sync_core가 바꿀 행 id(QID·이름·합침·FSQ id), merged : 대표로 이어질 구성원 id
\set ON_ERROR_STOP on

-- 1. 나라 전체 지문. 복사 뒤 운영이 바뀌었으면 값이 달라 계획을 다시 만든다.
SELECT count(*) AS places, count(*) FILTER (WHERE active) AS active, max(id) AS max_id,
       md5(string_agg(concat_ws('|', id, active, merged_into_id, wikidata_id, name_ko, latitude, longitude,
                                enriched_at IS NOT NULL, embedding IS NOT NULL), ',' ORDER BY id)) AS fingerprint
FROM places WHERE country = :country;

-- 2. 바꿀 행을 참조하는 사용자 데이터. 일정은 장소를 id로 조인해 보여 줘서 고친 이름이 기존 일정에도 보인다.
SELECT 'itinerary_steps' AS ref, count(*) AS rows, count(DISTINCT itinerary_id) AS itineraries
FROM itinerary_steps WHERE place_id IN (:changed)
UNION ALL
SELECT 'alternative_options', count(*), count(DISTINCT step_id) FROM alternative_options WHERE place_id IN (:changed)
UNION ALL
SELECT 'itineraries.cover_place_id', count(*), count(*) FROM itineraries WHERE cover_place_id IN (:changed)
UNION ALL
SELECT 'place_enrich_requests', count(*), count(*) FROM place_enrich_requests WHERE place_id IN (:changed);

-- 3. 구성원이 될 행 가운데 지금 일정이 쓰는 행. 행·활성은 그대로라 일정은 깨지지 않고, 새 검색에만 대표가 나온다.
SELECT p.id, p.name, p.name_ko, count(s.id) AS steps
FROM places p JOIN itinerary_steps s ON s.place_id = p.id
WHERE p.id IN (:merged) GROUP BY 1, 2, 3 ORDER BY steps DESC LIMIT 50;

-- 4. 이미 다른 곳으로 합쳐졌거나 꺼진 행(계획과 어긋남).
SELECT id, name, active, merged_into_id FROM places
WHERE id IN (:changed) AND (NOT active OR merged_into_id IS NOT NULL) ORDER BY id LIMIT 50;

-- 5. 원천 id 매핑(V73). 카탈로그 v7은 이 매핑으로 대표 원천 행과 다른 core 행을 찾아서, 매핑을 채우기 전에 올리면 그 장소를 새로 넣는다.
SELECT count(*) AS places, count(*) FILTER (WHERE EXISTS (SELECT 1 FROM place_source_refs r WHERE r.place_id = p.id)) AS mapped
FROM places p WHERE country = :country;
