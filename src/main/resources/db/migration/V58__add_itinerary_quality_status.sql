-- 저장 관문 결과. NEEDS_ADJUSTMENT는 핵심 품질 기준에 못 미쳐 편집이 필요한 일정이다.
-- plan_context는 편집 뒤 같은 기준으로 다시 판정할 입력이다. 없으면(기존 일정) 다시 판정하지 않는다.
ALTER TABLE itineraries ADD COLUMN quality_status VARCHAR(20) NOT NULL DEFAULT 'OK';
ALTER TABLE itineraries ADD COLUMN plan_context JSONB;

-- 관문에서 막힌 생성은 일정 없이 기록만 남는다. 요청 문장이 있어 탈퇴 시 user_id로도 지운다.
ALTER TABLE generation_traces ALTER COLUMN itinerary_id DROP NOT NULL;
ALTER TABLE generation_traces ADD COLUMN user_id BIGINT;
UPDATE generation_traces t SET user_id = i.user_id FROM itineraries i WHERE i.id = t.itinerary_id;

CREATE INDEX idx_generation_traces_user ON generation_traces (user_id);
CREATE INDEX idx_generation_traces_failed ON generation_traces (created_at) WHERE itinerary_id IS NULL;
