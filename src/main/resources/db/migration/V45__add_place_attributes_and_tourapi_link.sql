-- 장소 속성(업종·세부유형·경유형·등산 강도·숙소 유형·조식)과 TourAPI 공식 정보 연결.
-- attributes가 NULL이면 일정 로직은 지금처럼 카테고리·이름으로 추정한다.
ALTER TABLE places
    ADD COLUMN attributes           JSONB,
    ADD COLUMN enrich_version       SMALLINT,
    ADD COLUMN tourapi_content_id   VARCHAR(20),
    ADD COLUMN tourapi_content_type SMALLINT,
    ADD COLUMN tourapi_checked_at   TIMESTAMPTZ,
    ADD COLUMN tourapi_detail_at    TIMESTAMPTZ,
    ADD COLUMN opening_hours_source VARCHAR(10);

CREATE INDEX idx_places_tourapi_content_id
    ON places (tourapi_content_id)
    WHERE tourapi_content_id IS NOT NULL;
