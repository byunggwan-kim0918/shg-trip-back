-- 원천 행(Overture GERS id·FSQ id) → 장소. 대표 원천 행이 바뀌거나 DB마다 장소 id가 달라도 같은 장소를 찾는다.
-- 한 장소에 여러 원천 행이 이어지고(합쳐진 구성원), 한 원천 행은 한 장소에만 이어진다.
CREATE TABLE place_source_refs (
    source           VARCHAR(16)  NOT NULL,
    source_record_id VARCHAR(64)  NOT NULL,
    place_id         BIGINT       NOT NULL REFERENCES places (id),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (source, source_record_id)
);

CREATE INDEX idx_place_source_refs_place ON place_source_refs (place_id);

COMMENT ON COLUMN place_source_refs.source IS '원천 이름(overture·fsq). 카탈로그 source_ref의 콜론 앞부분과 같다.';
