-- FSQ 장소 시딩 배치 실행 이력 테이블
CREATE TABLE place_seeding_history (
    id              BIGSERIAL PRIMARY KEY,
    started_at      TIMESTAMPTZ NOT NULL,
    finished_at     TIMESTAMPTZ,
    status          VARCHAR(20)  NOT NULL DEFAULT 'RUNNING', -- RUNNING | SUCCESS | PARTIAL | FAILED
    source_file     VARCHAR(500),                            -- S3 key 등 소스 경로
    total_processed INT          NOT NULL DEFAULT 0,         -- 전체 처리 건수
    inserted        INT          NOT NULL DEFAULT 0,         -- 신규 등록
    updated         INT          NOT NULL DEFAULT 0,         -- 수정
    deleted         INT          NOT NULL DEFAULT 0,         -- 삭제 (soft delete)
    failed          INT          NOT NULL DEFAULT 0,         -- 실패 건수
    failed_chunks   INT          NOT NULL DEFAULT 0,         -- 실패 청크 수
    error_message   TEXT,                                    -- 전체 실패 시 에러 메시지
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_place_seeding_history_started_at ON place_seeding_history (started_at DESC);
