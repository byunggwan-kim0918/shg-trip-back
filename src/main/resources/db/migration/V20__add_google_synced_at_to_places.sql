-- Google Places API 마지막 동기화 시각 컬럼 추가
-- null: 한 번도 동기화 안 됨 → stale 판정
-- 값: 해당 시각 이후 7일간 fresh 판정
ALTER TABLE places ADD COLUMN IF NOT EXISTS google_synced_at TIMESTAMPTZ;
