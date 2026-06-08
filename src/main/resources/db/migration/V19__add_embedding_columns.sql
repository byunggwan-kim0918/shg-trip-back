-- V19: places 테이블에 벡터 임베딩 및 배치 보강 관련 컬럼 추가
-- S3 브랜치(V18__add_s3_columns)와 통합 — IF NOT EXISTS로 중복 방지

-- 임베딩 벡터 컬럼 (OpenAI text-embedding-3-small: 1536 dimensions)
ALTER TABLE places ADD COLUMN IF NOT EXISTS embedding vector(1536);

-- 배치 보강 컬럼 (tags는 TEXT[]로 통일)
ALTER TABLE places ADD COLUMN IF NOT EXISTS recommended_time_slots text[];
ALTER TABLE places ADD COLUMN IF NOT EXISTS enriched_at timestamp with time zone;

-- 데이터 소스 구분 ('google', 'foursquare', 'llm_generated')
-- S3 브랜치에서 VARCHAR(50)으로 추가했을 수 있으므로 IF NOT EXISTS
ALTER TABLE places ADD COLUMN IF NOT EXISTS source varchar(50) DEFAULT 'google';

-- soft delete
ALTER TABLE places ADD COLUMN IF NOT EXISTS active boolean DEFAULT true;
ALTER TABLE places ADD COLUMN IF NOT EXISTS deactivated_at timestamp with time zone;

-- tags 컬럼: S3 브랜치에서 TEXT로 추가된 경우 text[]로 타입 변경
-- 이미 text[]이면 DO NOTHING
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'places' AND column_name = 'tags'
          AND data_type = 'text' AND udt_name = 'text'
    ) THEN
        ALTER TABLE places ALTER COLUMN tags TYPE text[] USING
            CASE WHEN tags IS NULL THEN NULL
                 ELSE ARRAY[tags]
            END;
    ELSIF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'places' AND column_name = 'tags'
    ) THEN
        ALTER TABLE places ADD COLUMN tags text[];
    END IF;
END$$;

-- embedding HNSW 인덱스
CREATE INDEX IF NOT EXISTS idx_places_embedding_hnsw ON places
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- 복합 필터 인덱스
CREATE INDEX IF NOT EXISTS idx_places_destination_category ON places(country, region, category);

-- 미보강 장소 조회 인덱스
CREATE INDEX IF NOT EXISTS idx_places_enriched_at ON places(enriched_at) WHERE enriched_at IS NULL;
