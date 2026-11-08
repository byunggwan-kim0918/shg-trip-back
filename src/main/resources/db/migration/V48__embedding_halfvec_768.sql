-- text-embedding-3는 앞 768차원을 잘라 정규화한 값이 dimensions=768 응답과 같아 다시 임베딩하지 않는다.
-- halfvec·subvector·l2_normalize는 pgvector 0.7 이상이 필요하다.
DROP INDEX IF EXISTS idx_places_embedding_hnsw;

ALTER TABLE places
    ALTER COLUMN embedding TYPE halfvec(768)
    USING l2_normalize(subvector(embedding, 1, 768))::halfvec(768);

-- 값을 다 바꾼 뒤에 만들어야 행마다 인덱스를 갱신하지 않는다.
CREATE INDEX idx_places_embedding_hnsw ON places
    USING hnsw (embedding halfvec_cosine_ops)
    WITH (m = 16, ef_construction = 64);
