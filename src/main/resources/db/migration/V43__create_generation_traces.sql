-- RouteOptimizer는 입력이 같으면 결과가 같아서, 이 값으로 LLM 없이 다시 돌려 볼 수 있다.
-- candidates는 applyIntentHints 전 그대로 저장한다. selection 인덱스가 이 배열의 1-based 위치다.
CREATE TABLE generation_traces (
    id              BIGSERIAL PRIMARY KEY,
    itinerary_id    BIGINT       NOT NULL REFERENCES itineraries(id) ON DELETE CASCADE,
    job_id          VARCHAR(64)  NOT NULL,
    trace_version   INT          NOT NULL,
    request         JSONB        NOT NULL,
    enriched_input  JSONB        NOT NULL,
    selection_raw   JSONB        NOT NULL,
    selection_final JSONB        NOT NULL,
    candidates      JSONB        NOT NULL,
    schedule_args   JSONB        NOT NULL,
    models          JSONB,
    total_ms        BIGINT,
    score           JSONB,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_generation_traces_itinerary ON generation_traces (itinerary_id);
