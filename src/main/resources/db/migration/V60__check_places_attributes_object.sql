-- 키 단위 쓰기(attributes - 'ingredients', || jsonb_build_object)는 객체만 가정한다. 스칼라면 오류, 배열이면 엔티티로 읽지 못한다.
-- 앞선 긴 쿼리를 기다리며 places 읽기를 줄 세우지 않도록 잠금 대기를 짧게 끊는다(실패하면 배포를 다시 한다).
SET LOCAL lock_timeout = '5s';

-- 객체가 아닌 기존 행이 없어 NOT VALID로 테이블 스캔 없이 새 값만 막는다.
ALTER TABLE places ADD CONSTRAINT chk_places_attributes_object
    CHECK (attributes IS NULL OR jsonb_typeof(attributes) = 'object') NOT VALID;
