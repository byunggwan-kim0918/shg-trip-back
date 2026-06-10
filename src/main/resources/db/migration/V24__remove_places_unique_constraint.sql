-- 기존 (name, address) UNIQUE 제약 제거
-- Reason: fsq_place_id만으로 Foursquare 데이터 보호됨
--        Google 신규 저장 시 동시성은 stream().findFirst()로 처리 (첫 번째만 사용)
ALTER TABLE places DROP CONSTRAINT IF EXISTS uq_place;
ALTER TABLE places DROP CONSTRAINT IF EXISTS uq_place_name_address;

-- 조회 최적화: (name, address, source) 인덱스 (active=true만)
-- source='google' 우선순위로 조회하여 가장 완전한 데이터 선택
CREATE INDEX idx_places_name_address_source_active
    ON places (name, address, source, active);
