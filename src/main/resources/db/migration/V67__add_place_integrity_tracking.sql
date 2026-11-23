-- 장소 원문이 바뀌었을 때 그 입력으로 만든 보강·임베딩·장소 사실만 다시 만들도록 입력 지문을 남긴다.
-- 지문이 비어 있는 행은 첫 배치가 현재 값으로 채운다(PlaceInvalidationJob.adopt).
-- ADD COLUMN(상수 기본값 포함)은 메타데이터만 바꾸지만 ACCESS EXCLUSIVE 잠금을 잡아 잠금 대기를 짧게 끊는다.
SET LOCAL lock_timeout = '5s';

ALTER TABLE places
    ADD COLUMN enrich_input_fp   VARCHAR(16),
    ADD COLUMN embedding_text_fp VARCHAR(16),
    ADD COLUMN missing_since     TIMESTAMPTZ,
    ADD COLUMN missing_runs      SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN missing_source    VARCHAR(500);

ALTER TABLE place_facts
    ADD COLUMN input_fp VARCHAR(16);

COMMENT ON COLUMN places.enrich_input_fp IS '보강 요청 입력(이름·카테고리·지역·국가)의 지문. 다르면 보강 결과를 비우고 다시 묻는다.';
COMMENT ON COLUMN places.embedding_text_fp IS '임베딩 텍스트의 지문. 다르면 임베딩을 다시 만든다.';
COMMENT ON COLUMN places.missing_runs IS '공급처 추출에서 연속으로 빠진 횟수. 기준 횟수 이상이어야 비활성 후보가 된다.';
COMMENT ON COLUMN places.missing_source IS '마지막으로 누락을 센 추출(CSV 내용 해시). 같은 추출로 다시 돌린 배치가 횟수를 또 올리지 않게 한다.';
COMMENT ON COLUMN place_facts.input_fp IS '사실을 만든 입력(이름·카테고리·좌표)의 지문. 다르면 사실을 지우고 다시 만든다.';

-- 배치 단계 실행 이력. 실패한 단계를 성공으로 남기지 않도록 시작할 때 RUNNING을 먼저 쓴다.
CREATE TABLE batch_runs (
    id            BIGSERIAL PRIMARY KEY,
    run_id        UUID        NOT NULL,
    stage         VARCHAR(80) NOT NULL,
    status        VARCHAR(12) NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED')),
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    error_message TEXT
);

CREATE INDEX idx_batch_runs_run_id ON batch_runs (run_id);
CREATE INDEX idx_batch_runs_started_at ON batch_runs (started_at DESC);
