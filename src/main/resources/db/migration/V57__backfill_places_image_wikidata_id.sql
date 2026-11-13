-- V56과 나눠 둔다. 같은 트랜잭션이면 ALTER의 ACCESS EXCLUSIVE 잠금이 이 UPDATE가 끝날 때까지 남는다.
-- 사진을 붙인 뒤 첫 Wikidata 매칭보다 먼저 적용돼야 지금 항목이 곧 사진의 출처다.
UPDATE places SET image_wikidata_id = wikidata_id
WHERE image_source_url LIKE 'https://commons.wikimedia.org/%';
