-- 공용 사진 단계는 사진 없는 행만 다시 보므로 이미 연결된 주소의 utm 쿼리는 여기서 뗀다.
UPDATE places
SET image_url = split_part(image_url, '?', 1)
WHERE image_source_url LIKE 'https://commons.wikimedia.org/%'
  AND image_url LIKE '%?%';
