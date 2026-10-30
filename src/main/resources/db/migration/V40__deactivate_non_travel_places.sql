-- 여행 일정의 방문지가 될 수 없는 장소와 좌표가 깨진 장소를 비활성화한다(삭제하지 않는다 —
-- 기존 일정 스텝이 place_id로 참조한다).
--
-- 실측(2026-10-28 감사): 서울 관광 후보 풀 4,037곳 중 실제 명소는 15%, 오사카 5%, 삿포로 4%였다.
-- 나머지는 편의점·은행·약국·병원·자동차 대리점·버스정류장·도로였고, 빈 시간 채우기와 관광 보충이
-- 이들을 관광지로 넣었다("KCC오토모빌 제주전시장"이 3일차 관광 스텝으로 들어간 실측).
--
-- 1) 비여행 카테고리 — FoursquareSeeder.BLOCKED_CATEGORY_KEYWORDS(신규 적재 차단 목록)와 같은 기준에
--    생활 소매(슈퍼·약국·휴대폰·대리점 등)를 더했다. 스파·온천·사우나는 마법사에 spa 카테고리가 있어 남긴다.
--    교육은 초중고·학과 건물·어학원만 뺀다 — 대학 본교(관광객이 찾는 캠퍼스)와 향교·서원은 남긴다.
-- 2) 좌표 오류 — (0,0), TourAPI 좌표 변환 오류로 생긴 남중국해 고정점(19.694, 117.993), 그리고
--    지역 중앙값에서 100km 넘게 떨어진 행(다른 도시 가게가 region만 잘못 붙은 경우).

UPDATE places
SET active = false, deactivated_at = now(), updated_at = now()
WHERE active
  AND (lower(category) LIKE ANY (ARRAY[
          '%business and professional services%',
          '%health and medicine%',
          '%residential building%',
          '%government building%',
          '%education > primary and secondary school%',
          '%education > college and university > college%',
          '%education > language school%',
          '%education > adult education%',
          '%education > driving school%',
          '%convenience store%',
          '%pharmacy%',
          '%drugstore%',
          '%transport hub > bus stop%',
          '%travel and transportation > road%',
          '%fuel station%',
          '%travel and transportation > parking%',
          '%gym and studio%',
          '%golf driving range%',
          '%park > playground%',
          '%eyecare store%',
          '%computers and electronics retail%',
          '%housing development%',
          '%polling place%',
          '%assisted living%',
          '%supermarket%',
          '%grocery store%',
          '%car dealership%',
          '%automotive%',
          '%mobile phone store%',
          '%furniture and home store%',
          '%liquor store%',
          '%post office%',
          '%police station%',
          '%fire station%'])
      )
  AND lower(category) NOT LIKE ALL (ARRAY['%spa%', '%onsen%', '%sauna%', '%bathhouse%'])
  -- 향교·서원은 교육 카테고리에 있지만 문화재다.
  AND name NOT LIKE ALL (ARRAY['%향교%', '%서원%']);

UPDATE places
SET active = false, deactivated_at = now(), updated_at = now()
WHERE active
  AND ((latitude = 0 AND longitude = 0)
       OR (abs(latitude - 19.694) < 0.01 AND abs(longitude - 117.993) < 0.01));

WITH center AS (
    SELECT region,
           percentile_cont(0.5) WITHIN GROUP (ORDER BY latitude)  AS lat,
           percentile_cont(0.5) WITHIN GROUP (ORDER BY longitude) AS lng
    FROM places
    WHERE active AND region IS NOT NULL
    GROUP BY region
    HAVING count(*) >= 20
)
UPDATE places p
SET active = false, deactivated_at = now(), updated_at = now()
FROM center c
WHERE p.active
  AND p.region = c.region
  -- 위도 1도 ≈ 111km, 경도 1도 ≈ 111km × cos(위도). 100km 판정은 이 근사로 충분하다.
  AND sqrt(power((p.latitude - c.lat) * 111.0, 2)
           + power((p.longitude - c.lng) * 111.0 * cos(radians(c.lat)), 2)) > 100;

-- 제주 TourAPI 식당 일부가 시간대를 식사 어휘(lunch/dinner)로 갖고 있다. 스케줄러는
-- morning/afternoon/evening/night만 읽으므로 같은 의미로 맞춘다.
UPDATE places
SET recommended_time_slots = array_replace(array_replace(recommended_time_slots, 'lunch', 'afternoon'),
                                           'dinner', 'evening'),
    updated_at = now()
WHERE recommended_time_slots && ARRAY['lunch', 'dinner'];
