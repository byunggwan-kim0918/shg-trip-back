-- V19: places 테이블에 벡터 임베딩 및 배치 보강 관련 컬럼 추가
-- Requirements: 12.2, 12.3, 12.4, 12.5, 1.7

-- 임베딩 벡터 컬럼 (OpenAI text-embedding-3-small: 1536 dimensions)
ALTER TABLE places ADD COLUMN embedding vector(1536);

-- 배치 보강 컬럼
ALTER TABLE places ADD COLUMN tags text[];
ALTER TABLE places ADD COLUMN recommended_time_slots text[];
ALTER TABLE places ADD COLUMN enriched_at timestamp with time zone;

-- 데이터 소스 구분 ('google', 'foursquare', 'llm_generated')
ALTER TABLE places ADD COLUMN source varchar(50) DEFAULT 'google';

-- soft delete 컬럼 (폐업 장소 처리)
ALTER TABLE places ADD COLUMN active boolean DEFAULT true;
ALTER TABLE places ADD COLUMN deactivated_at timestamp with time zone;

-- embedding HNSW 인덱스 (빠른 cosine similarity 검색)
CREATE INDEX idx_places_embedding_hnsw ON places
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- 복합 필터 인덱스 (벡터 검색 시 destination 필터링)
CREATE INDEX idx_places_destination_category ON places(country, region, category);

-- 미보강 장소 조회 인덱스 (배치 처리 대상 빠르게 조회)
CREATE INDEX idx_places_enriched_at ON places(enriched_at) WHERE enriched_at IS NULL;
