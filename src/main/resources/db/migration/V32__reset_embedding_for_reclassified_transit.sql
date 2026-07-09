-- V31 ③에서 관광지→교통시설(Transport Hub)로 category만 재분류한 레코드는 embedding이
-- 갱신되지 않아, 임베딩 텍스트(name+category+...)가 여전히 구 category("Landmarks...")로
-- 남아 관광 쿼리에 유사도 높게 잡히는 괴리가 있었다. embedding=NULL로 리셋해 다음 임베딩
-- 배치(EmbeddingBatchJob, embedding IS NULL 대상)가 새 category로 재임베딩하게 한다.
-- (pgvector 컬럼은 updatable=false라 앱 flush로 못 바꿈 — 마이그레이션에서 처리, resetEmbeddings와 동일 패턴)
UPDATE places
SET embedding = NULL
WHERE active = true
  AND category = 'Travel and Transportation > Transport Hub > Terminal'
  AND embedding IS NOT NULL;
