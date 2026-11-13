-- 위키미디어 공용 사진은 저작자·라이선스 표시가 사용 조건이라 사진 주소와 함께 둔다. TourAPI 사진 행은 NULL이다.
SET LOCAL lock_timeout = '5s';

ALTER TABLE places
    ADD COLUMN image_credit     VARCHAR(255),
    ADD COLUMN image_license    VARCHAR(100),
    ADD COLUMN image_source_url TEXT;

COMMENT ON COLUMN places.image_credit IS '사진 작성자(태그 제거한 평문). 표시 의무가 없는 출처는 NULL';
COMMENT ON COLUMN places.image_license IS '사진 라이선스 약칭(CC BY-SA 4.0 등)';
COMMENT ON COLUMN places.image_source_url IS '사진 원본 파일 페이지';
