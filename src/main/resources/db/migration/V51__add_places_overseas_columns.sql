-- 해외 장소용 컬럼: 한국어 표시 이름, 비활성화 사유, 현지 통화 입장료, 보강 시도 횟수.
-- ADD COLUMN(상수 기본값 포함)은 메타데이터만 바꾸지만 ACCESS EXCLUSIVE 잠금을 잡는다. 앞선 긴 쿼리를 기다리며
-- places 읽기를 줄 세우지 않도록 잠금 대기를 짧게 끊는다(실패하면 배포를 다시 한다).
SET LOCAL lock_timeout = '5s';

ALTER TABLE places
    ADD COLUMN name_ko             VARCHAR(255),
    ADD COLUMN deactivated_reason  VARCHAR(20),
    ADD COLUMN admission_fee_local INTEGER,
    ADD COLUMN admission_currency  VARCHAR(3),
    ADD COLUMN enrich_attempts     SMALLINT NOT NULL DEFAULT 0;

-- 기존 행은 전부 NULL이라 검사할 것이 없다. NOT VALID로 테이블 스캔 없이 새 값만 막는다.
ALTER TABLE places
    ADD CONSTRAINT chk_places_deactivated_reason
    CHECK (deactivated_reason IN ('stale', 'policy')) NOT VALID;

COMMENT ON COLUMN places.name_ko IS
  '한국어 표시 이름. name은 저장·조회 키라 바꾸지 않고, 화면·프롬프트만 이 값을 쓴다.';
COMMENT ON COLUMN places.deactivated_reason IS
  'stale: 월간 추출에서 빠짐(다시 나오면 적재기가 되살린다) | policy: 정책·데이터 정리로 끔(되살리지 않는다)';
COMMENT ON COLUMN places.admission_fee_local IS '현지 통화 입장료. 원화 환산은 읽을 때 한다.';
