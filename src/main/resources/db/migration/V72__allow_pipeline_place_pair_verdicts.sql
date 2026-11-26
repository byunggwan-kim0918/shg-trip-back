-- 제약 교체는 ACCESS EXCLUSIVE를 잡아 앞선 긴 쿼리를 기다리며 읽기를 줄 세우지 않도록 잠금 대기를 짧게 끊는다(실패하면 배포를 다시 한다).
SET LOCAL lock_timeout = '5s';

-- PIPELINE은 장소 파이프라인의 같은 장소 묶음이다. 장소 연결 배치는 이 판정을 다시 묻거나 지문이 달라졌다고 버리지 않는다.
ALTER TABLE place_pair_verdicts DROP CONSTRAINT chk_place_pair_basis;
-- 기존 행은 RULE·LLM뿐이라 검사할 것이 없다. NOT VALID로 테이블 스캔 없이 새 값만 막는다.
ALTER TABLE place_pair_verdicts ADD CONSTRAINT chk_place_pair_basis
    CHECK (basis IN ('RULE', 'LLM', 'PIPELINE')) NOT VALID;
