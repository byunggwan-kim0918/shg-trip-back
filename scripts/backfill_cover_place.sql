-- 기존 일정의 커버 재선정 백필.
--
-- 배경 1 (커버가 전부 공항):
--   Itinerary.assignCoverFromSteps()가 findFirst()로 "첫 스텝"을 커버로 썼는데
--   여행의 첫 스텝은 구조적으로 거의 항상 공항/역이다. 그 결과 제주 일정 10여 건의
--   커버가 전부 "제주국제공항" 사진으로 동일해 대시보드가 같은 사진의 벽이 됐다.
--   엔티티는 카테고리 우선순위로 고르도록 고쳤지만, assignCoverFromSteps()는
--   cover_place_id가 이미 있으면 early-return 하므로 기존 행은 이 백필이 필요하다.
--
-- 배경 2 (DB와 S3 불일치):
--   places.image_url은 S3에 객체가 없어도 채워져 있다 — 로컬 실측 2037건 중 실제 존재는 204건.
--   image_url만 보고 커버를 고르면 카드가 그라데이션 폴백으로 떨어진다(1차 백필에서 4/9가 그랬다).
--   그래서 "S3에 객체가 실제로 있는가"를 최우선 조건으로 둔다.
--
-- 점수 = (S3에 객체 있으면 10) + 카테고리 점수
--   카테고리 점수(엔티티의 coverScore()와 동일):
--     4 Landmarks and Outdoors  (관광지·오름·해변·공원)
--     3 Arts and Entertainment  (박물관·갤러리)
--     2 Dining and Drinking     (미식 여행)
--     1 그 외
--     0 Travel and Transportation (공항·역·버스터미널 — 여행지를 설명하지 못함)
--   동점이면 rating 내림차순, 그래도 동점이면 step_order 오름차순(결정적).
--
-- 실행:
--   cd shg-trip-back
--   docker compose exec -T postgres psql -U weShg -d trip < scripts/backfill_cover_place.sql
--
-- 되돌리기: 아래 BEGIN 전에 백업을 뜬다.
--   CREATE TABLE itineraries_cover_backup AS
--     SELECT id, cover_place_id FROM itineraries WHERE deleted_at IS NULL;
--   -- 복구: UPDATE itineraries i SET cover_place_id = b.cover_place_id
--   --       FROM itineraries_cover_backup b WHERE b.id = i.id;

BEGIN;

-- S3에 실제로 존재하는 place 이미지 id (로컬 localstack 실측).
-- 환경이 바뀌면 다시 뽑을 것:
--   curl -s "http://localhost:4566/shgtrip-data?list-type=2&prefix=images/places/&max-keys=1000" \
--     | grep -o '<Key>images/places/[0-9]*\.jpg</Key>' | sed 's/.*places\///; s/\.jpg.*//'
CREATE TEMP TABLE s3_present(place_id BIGINT PRIMARY KEY) ON COMMIT DROP;
INSERT INTO s3_present(place_id)
SELECT DISTINCT x FROM unnest(ARRAY[
  4,5,7,16,18,26,28,32,37,42,
  4550,4580,4828,5129,5430,5719,5820,5915,6346,6703,
  6723,6885,7090,7379,7537,7720,8304,8421,9286,9443,
  9472,9734,10129,10403,10646,10717,11212,11253,11443,11826,
  12564,12624,12636,12767,13167,13619,13625,13656,13774,14131,
  14201,14369,14566,15022,15917,16054,16504,16653,16654,16813,
  16831,16946,17367,17724,17753,17981,18054,18055,18059,18061,
  18064,18065,18067,18068,18071,18072,18073,18133,31820,48745,
  48809,48874,48938,48996,49042,49122,49151,49194,49230,49254,
  49451,49518,49567,49604,49625,49644,49675,49690,49777,49787,
  49806,50004,50170,50193,50210,50539,50637,50641,50859,50909,
  51007,51034,51036,51108,51123,51174,51430,51476,51568,51682,
  51709,51785,51795,51899,51925,51929,51943,51947,52070,52077,
  52079,52082,52185,52222,52228,52386,52389,52399,52452,52483,
  52538,52581,52587,52624,52926,52952,52990,53474,53604,53659,
  53718,53782,53800,307192,307195,307208,307257,307362,307380,307412,
  307508,307509,307521,307534,307563,307624,307678,307780,307847,308739,
  308752,308767,308789,308801,308806,308826,308830,308847,308853,308860,
  308861,308864,308880,308895,308898,308907,308914,308923,308929,308948,
  308949,308975,308987,309029,309034,309046,309056,309077,309100,309103,
  309105,309106,742643,742644
]::BIGINT[]) AS x;

WITH ranked AS (
  SELECT s.itinerary_id,
         p.id AS place_id,
         ROW_NUMBER() OVER (
           PARTITION BY s.itinerary_id
           -- 등급을 "굵게" 묶는 게 핵심이다. 카테고리 점수를 1점 단위로 세분하면 그 순서가
           -- 항상 동점을 이겨서 분산이 안 걸리고, 같은 목적지 일정끼리 또 같은 사진을 쓴다
           -- (공항 벽을 다른 사진 벽으로 바꾼 셈 — 실제로 제주 3건이 그랬다).
           -- 그래서 굵은 등급(20/11/10/0~4)으로만 나누고, 같은 등급 안에서는 일정 id 해시로 섞는다.
           ORDER BY
             CASE
               WHEN NOT EXISTS (SELECT 1 FROM s3_present sp WHERE sp.place_id = p.id) THEN
                 -- 사진이 실제로 없는 후보. 카테고리 순서만 의미 있다
                 CASE
                   WHEN p.category LIKE 'Travel and Transportation%' THEN 0
                   WHEN p.category LIKE 'Landmarks and Outdoors%'    THEN 4
                   WHEN p.category LIKE 'Arts and Entertainment%'    THEN 3
                   WHEN p.category LIKE 'Dining and Drinking%'       THEN 2
                   ELSE 1
                 END
               -- 아래는 전부 사진이 실제로 있는 후보
               WHEN p.category LIKE 'Travel and Transportation%' THEN 10  -- 공항·역: 최후 수단
               WHEN p.category LIKE 'Landmarks and Outdoors%'
                 OR p.category LIKE 'Arts and Entertainment%'
                 OR p.category LIKE 'Dining and Drinking%'            THEN 20  -- 여행을 설명하는 사진
               ELSE 11
             END DESC,
             -- 같은 등급 안에서 일정별로 다른 후보를 집게 한다. 결정적이면서 분산된다.
             ((p.id * 7 + s.itinerary_id * 13) % 1000) ASC,
             COALESCE(p.rating, 0) DESC,
             s.step_order ASC
         ) AS rn
  FROM itinerary_steps s
  JOIN places p ON p.id = s.place_id
  WHERE p.photo_reference IS NOT NULL
)
UPDATE itineraries i
SET cover_place_id = r.place_id
FROM ranked r
WHERE r.itinerary_id = i.id
  AND r.rn = 1
  AND i.deleted_at IS NULL
  AND i.cover_place_id IS DISTINCT FROM r.place_id;

-- 확인
SELECT
  count(*) FILTER (WHERE p.category LIKE 'Travel and Transportation%') AS "교통허브커버",
  count(*) FILTER (WHERE EXISTS (SELECT 1 FROM s3_present sp WHERE sp.place_id = p.id)) AS "실사진",
  count(*) AS "전체"
FROM itineraries i
JOIN places p ON p.id = i.cover_place_id
WHERE i.deleted_at IS NULL;

COMMIT;
