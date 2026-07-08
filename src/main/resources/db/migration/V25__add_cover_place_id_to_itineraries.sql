-- 커버 이미지를 만료되는 presigned URL 문자열(cover_image)로 저장하던 방식은 7일마다 만료돼 깨졌다.
-- 대신 안정적 참조인 place id(cover_place_id)를 저장하고, imageUrl은 조회 시점에 해소한다.
ALTER TABLE itineraries ADD COLUMN cover_place_id BIGINT;

-- 기존 일정 백필: 첫 스텝(day, order 순) 중 사진 확보 가능한(photo_reference 있는) place를 커버로 지정
UPDATE itineraries i SET cover_place_id = (
    SELECT s.place_id
    FROM itinerary_steps s
    JOIN places p ON p.id = s.place_id
    WHERE s.itinerary_id = i.id
      AND s.place_id IS NOT NULL
      AND p.photo_reference IS NOT NULL
    ORDER BY s.day_number, s.step_order
    LIMIT 1
)
WHERE i.cover_place_id IS NULL;
