-- 같은 입력으로 반복 생성한 일정끼리 장소가 얼마나 겹치는지(쌍별 Jaccard) 본다. 읽기 전용.
-- 겹침이 지나치게 높으면 매번 같은 일정이 나온다는 뜻이라 제약을 늘릴 때 함께 잰다.
--
-- docker exec -i shg-trip-postgres psql -U weShg -d trip -v ids="106,107,108" < scripts/eval/repeat-overlap.sql
WITH visits AS (
    SELECT DISTINCT s.itinerary_id AS id, s.place_id,
           CASE
               WHEN p.category LIKE '%Lodging%' THEN '숙소'
               WHEN p.category LIKE 'Travel and Transportation%' THEN '이동 거점'
               WHEN p.category LIKE 'Dining and Drinking%' THEN '식음료'
               ELSE '관광'
           END AS kind
    FROM itinerary_steps s
    JOIN places p ON p.id = s.place_id
    WHERE s.itinerary_id IN (:ids)
),
pairs AS (
    SELECT a.id AS a, b.id AS b, k.kind
    FROM (SELECT DISTINCT id FROM visits) a
    JOIN (SELECT DISTINCT id FROM visits) b ON a.id < b.id
    CROSS JOIN (SELECT DISTINCT kind FROM visits) k
),
overlap AS (
    SELECT pr.kind, pr.a, pr.b,
           (SELECT count(*) FROM visits x JOIN visits y ON y.place_id = x.place_id AND y.kind = x.kind
             WHERE x.id = pr.a AND y.id = pr.b AND x.kind = pr.kind) AS shared,
           (SELECT count(DISTINCT place_id) FROM visits x
             WHERE x.id IN (pr.a, pr.b) AND x.kind = pr.kind) AS either
    FROM pairs pr
)
SELECT kind AS "종류",
       count(*) AS "쌍",
       round(avg(shared::numeric / NULLIF(either, 0)), 3) AS "평균 겹침",
       round(min(shared::numeric / NULLIF(either, 0)), 3) AS "최소",
       round(max(shared::numeric / NULLIF(either, 0)), 3) AS "최대"
FROM overlap
WHERE kind <> '이동 거점' AND either > 0
GROUP BY kind
ORDER BY kind;
