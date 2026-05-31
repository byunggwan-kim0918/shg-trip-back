-- Google Places photo_reference 저장 컬럼 추가
-- API 키를 URL에 포함하지 않고 photo_reference만 저장, 응답 시 서버에서 키 조합
ALTER TABLE places ADD COLUMN IF NOT EXISTS photo_reference TEXT;
