-- state는 대화 이벤트·턴·열린 질문을 담는다. 세션은 14일 뒤 만료돼 스키마 변경 시 이어하기 대신 새로 시작한다.
CREATE TABLE trip_chat_sessions (
    id                UUID         PRIMARY KEY,
    user_id           BIGINT       NOT NULL REFERENCES users(id),
    status            VARCHAR(16)  NOT NULL,
    client_request_id VARCHAR(64)  NOT NULL,
    job_id            VARCHAR(64),
    state             JSONB        NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0,
    confirmed_at      TIMESTAMPTZ,
    expires_at        TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 두 탭이 동시에 새 대화를 시작해도 사용자당 진행 중 대화는 하나만 남는다.
CREATE UNIQUE INDEX uk_trip_chat_sessions_active_user
    ON trip_chat_sessions (user_id) WHERE status IN ('ASKING', 'REVIEW', 'CONFIRMED');

CREATE INDEX idx_trip_chat_sessions_expires_at ON trip_chat_sessions (expires_at);
