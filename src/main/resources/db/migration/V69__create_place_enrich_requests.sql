-- 생성 중 카탈로그에서 승격한 장소의 즉시 보강 대기열. 서버가 재시작해도 남은 요청을 주기 작업이 다시 집는다.
-- 보강되거나, 장소 탓 실패가 보강 상한(enrich_attempts)에 닿거나, 장소가 꺼지면 행을 지운다. 일시 오류로는 지우지 않는다.
CREATE TABLE place_enrich_requests (
    place_id        BIGINT       PRIMARY KEY REFERENCES places (id) ON DELETE CASCADE,
    requested_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    attempts        SMALLINT     NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    claimed_until   TIMESTAMPTZ,
    last_error      VARCHAR(200)
);

CREATE INDEX idx_place_enrich_requests_due ON place_enrich_requests (next_attempt_at);

COMMENT ON COLUMN place_enrich_requests.attempts IS '보강을 시작한 횟수. 호출 상한으로 미룬 차례는 세지 않는다.';
COMMENT ON COLUMN place_enrich_requests.claimed_until IS '이 시각까지 한 작업자가 맡는다. 작업자가 죽어도 이 시각이 지나면 다른 작업자가 다시 맡는다.';
