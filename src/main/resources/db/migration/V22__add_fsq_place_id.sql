-- FSQ POI 전역 고유 ID를 자연키로 추가.
-- 기존 (name, address) 유니크 제약은 주소 없는 체인점(7-Eleven 등)을 잘못 병합하므로 제거하고,
-- fsq_place_id 기준 유니크로 전환한다.
ALTER TABLE places ADD COLUMN IF NOT EXISTS fsq_place_id VARCHAR(255);

-- 기존 name+address 유니크 제약 제거 (존재할 때만)
ALTER TABLE places DROP CONSTRAINT IF EXISTS uq_place_name_address;

-- fsq_place_id 유니크 제약 추가 (NULL은 여러 개 허용 → google/llm_generated 소스는 영향 없음)
ALTER TABLE places ADD CONSTRAINT uq_place_fsq_place_id UNIQUE (fsq_place_id);
