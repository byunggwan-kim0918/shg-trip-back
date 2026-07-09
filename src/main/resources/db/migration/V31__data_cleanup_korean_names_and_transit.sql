-- 데이터 정비 3종 (실측 근거는 2026-07-09 검토):
--
-- ① 좌표(4자리 반올림, ±11m)·region이 동일한 한/영 중복 쌍(실측 220쌍)에서 영문 레코드를
--    비활성화한다. 벡터 검색이 영문 레코드만 후보로 올리면 후보 단계 dedupe가 개입할 기회가
--    없어 "Cafecola"가 사용자에게 노출됐다(한글 "카페콜라" 레코드가 있는데도).
--    itinerary_steps는 place_id FK 참조라 기존 일정은 안전하고, 벡터 검색은 active=true
--    필터라 즉시 후보에서 제외된다. Foursquare 재시딩 upsert는 active를 건드리지 않는다.
UPDATE places e
SET active = false, deactivated_at = now()
WHERE e.active = true
  AND e.name !~ '[가-힣]'
  AND EXISTS (
    SELECT 1 FROM places k
    WHERE k.active = true
      AND k.id <> e.id
      AND k.name ~ '[가-힣]'
      AND k.region IS NOT DISTINCT FROM e.region
      AND round(k.latitude, 4) = round(e.latitude, 4)
      AND round(k.longitude, 4) = round(e.longitude, 4)
  );

-- ② 좌표 (0,0) 불량 레코드 비활성화 (실측 11건 — 지도 표시 불가/거리 계산 왜곡의 원인)
UPDATE places
SET active = false, deactivated_at = now()
WHERE active = true AND latitude = 0 AND longitude = 0;

-- ③ TourAPI 관광지 타입(12)에 혼입된 교통시설을 TRANSIT_HUB 카테고리로 재분류
--    (실측: "성산포항 종합여객터미널"이 관광 스텝으로 90분 배치됨. 비활성화가 아니라
--    재분류인 이유 — 여객터미널은 도착/출발 허브 후보로는 유효하다)
UPDATE places
SET category = 'Travel and Transportation > Transport Hub > Terminal'
WHERE active = true
  AND source = 'tourapi'
  AND category LIKE 'Landmarks%'
  AND (name LIKE '%터미널%' OR name LIKE '%여객%' OR name LIKE '%선착장%'
       OR name LIKE '%부두%' OR name LIKE '%공항%' OR name LIKE '%도선%');
