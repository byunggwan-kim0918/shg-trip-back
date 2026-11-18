-- 관광 장소의 여행자용 사실(방문 가치, 상위 장소, 정기 휴무, 일반인 접근). LLM 배치가 채우고 일정 생성이 읽는다.
CREATE TABLE IF NOT EXISTS place_facts (
    place_id   BIGINT PRIMARY KEY REFERENCES places (id) ON DELETE CASCADE,
    facts      JSONB       NOT NULL,
    model      VARCHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
