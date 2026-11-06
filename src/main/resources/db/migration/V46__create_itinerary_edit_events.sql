-- 사용자가 생성된 일정을 어떻게 고쳤는지 — 품질의 실사용 신호.
-- 일정을 지워도 남기려고 FK를 두지 않는다. 자유 텍스트 없이 id·유형·순서만 담는다
-- (요청 문장이 든 generation_traces는 일정 삭제 시 지운다).
CREATE TABLE itinerary_edit_events (
    id           BIGSERIAL PRIMARY KEY,
    itinerary_id BIGINT      NOT NULL,
    user_id      BIGINT      NOT NULL,
    type         VARCHAR(30) NOT NULL,
    day_number   INT,
    step_id      BIGINT,
    payload      JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_itinerary_edit_events_itinerary ON itinerary_edit_events (itinerary_id, created_at);

-- 생성 직후 스텝. 재실행은 코드가 바뀌면 원본이 아니게 되므로 편집 전 상태를 따로 남긴다.
ALTER TABLE generation_traces ADD COLUMN generated_steps JSONB;
