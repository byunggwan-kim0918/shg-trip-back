-- 기존 (name, latitude, longitude) UNIQUE → (name, address) UNIQUE로 변경
-- fallback 저장 시 좌표 (0,0) 중복 방지
ALTER TABLE places DROP CONSTRAINT IF EXISTS uq_place;
ALTER TABLE places ADD CONSTRAINT uq_place_name_address UNIQUE (name, address);
