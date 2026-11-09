-- TourAPI 이미지 서버는 https도 같은 경로로 응답한다. http 주소는 https 페이지에서 혼합 콘텐츠로 막힐 수 있다.
UPDATE places
SET image_url = 'https://' || substring(image_url FROM 8)
WHERE image_url LIKE 'http://tong.visitkorea.or.kr/%';
