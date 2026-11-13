-- 공용 사진은 장소의 Wikidata 항목에서 고른다. 매칭이 바뀌면 사진도 다시 골라야 해서 출처 항목을 남긴다.
SET LOCAL lock_timeout = '5s';

ALTER TABLE places ADD COLUMN image_wikidata_id VARCHAR(16);

COMMENT ON COLUMN places.image_wikidata_id IS '공용 사진을 고른 Wikidata 항목. 공용 사진이 아니면 NULL';
