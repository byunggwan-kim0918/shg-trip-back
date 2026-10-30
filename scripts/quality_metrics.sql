-- 일정 품질 측정 — 개선 전후 대조용 고정 지표
-- 사용: docker exec -i shg-trip-postgres psql -U weShg -d trip -v ids='92,93,94,95' < scripts/quality_metrics.sql
\set idlist :ids

\echo '=== 1) 일일 이동거리 (표시 km)'
SELECT itinerary_id AS id, day_number AS d, round(sum(transportation_distance)) AS km
FROM itinerary_steps WHERE itinerary_id IN (:idlist)
GROUP BY 1,2 ORDER BY 1,2;

\echo '=== 2) 순공백 (이동시간 제외, 60분 이상)'
WITH s AS (
  SELECT itinerary_id, day_number, step_order, end_time::time AS et,
    lead(start_time::time) OVER (PARTITION BY itinerary_id, day_number ORDER BY step_order) AS nst,
    lead(transportation_duration) OVER (PARTITION BY itinerary_id, day_number ORDER BY step_order) AS nmv
  FROM itinerary_steps WHERE itinerary_id IN (:idlist))
SELECT itinerary_id AS id, day_number AS d,
  (EXTRACT(epoch FROM nst - et)/60)::int - coalesce(nmv,0) AS 순공백분
FROM s WHERE nst IS NOT NULL
  AND (EXTRACT(epoch FROM nst - et)/60)::int - coalesce(nmv,0) >= 60
ORDER BY 3 DESC;

\echo '=== 3) 하루 종료 시각'
SELECT itinerary_id AS id, day_number AS d, min(start_time) AS 시작, max(end_time) AS 종료
FROM itinerary_steps WHERE itinerary_id IN (:idlist) GROUP BY 1,2 ORDER BY 1,2;

\echo '=== 4) 구성비 (관광 : 식음료)'
WITH t AS (SELECT s.itinerary_id AS id,
  CASE WHEN p.category LIKE 'Dining%' THEN '식음료'
       WHEN p.category LIKE '%Lodging%' THEN '숙박'
       WHEN p.category LIKE '%Transport%' THEN '교통'
       ELSE '관광' END AS kind
  FROM itinerary_steps s JOIN places p ON p.id=s.place_id WHERE s.itinerary_id IN (:idlist))
SELECT id, count(*) FILTER (WHERE kind='관광') AS 관광,
       count(*) FILTER (WHERE kind='식음료') AS 식음료,
       count(*) FILTER (WHERE kind='숙박') AS 숙박, count(*) FILTER (WHERE kind='교통') AS 교통
FROM t GROUP BY 1 ORDER BY 1;

\echo '=== 5) 동일 cuisine 반복 (돼지고기 기준)'
SELECT s.itinerary_id AS id,
  count(*) FILTER (WHERE p.name ~ '(흑돼지|오겹|두루치기|고기|갈비|돼지)') AS 돼지고기,
  count(*) FILTER (WHERE p.category LIKE 'Dining%') AS 식음료전체
FROM itinerary_steps s JOIN places p ON p.id=s.place_id
WHERE s.itinerary_id IN (:idlist) GROUP BY 1 ORDER BY 1;

\echo '=== 6) 식사 간격 (180분 미만만)'
WITH m AS (SELECT s.itinerary_id AS id, s.day_number AS d, s.start_time::time AS st,
  s.end_time::time AS et, p.name
  FROM itinerary_steps s JOIN places p ON p.id=s.place_id
  WHERE s.itinerary_id IN (:idlist) AND p.category LIKE 'Dining%')
SELECT id, d, left(name,16) AS 앞,
  (EXTRACT(epoch FROM lead(st) OVER (PARTITION BY id,d ORDER BY st) - et)/60)::int AS 간격분,
  left(lead(name) OVER (PARTITION BY id,d ORDER BY st),16) AS 뒤
FROM m ORDER BY 4 NULLS LAST;

\echo '=== 7) 메인 스텝의 집합 POI / 행정구역'
SELECT s.itinerary_id AS id, left(p.name,30) AS 장소
FROM itinerary_steps s JOIN places p ON p.id=s.place_id
WHERE s.itinerary_id IN (:idlist)
  AND (p.name ~ '(특구|관광지구|관광권역|지질공원)' OR length(trim(p.address)) < 12);

\echo '=== 8) 숙소 연속성'
SELECT s.itinerary_id AS id, count(DISTINCT p.name) AS 숙소종류,
       string_agg(DISTINCT left(p.name,14), ', ') AS 숙소
FROM itinerary_steps s JOIN places p ON p.id=s.place_id
WHERE s.itinerary_id IN (:idlist) AND p.category LIKE '%Lodging%' GROUP BY 1 ORDER BY 1;
