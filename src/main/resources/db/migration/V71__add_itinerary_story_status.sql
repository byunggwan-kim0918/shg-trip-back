-- 이야기(Haiku) 작성 상태. 화면은 PENDING일 때만 다시 조회한다.
-- NULL은 이 컬럼 전에 만든 일정 중 완료 여부를 데이터로 판별하지 못한 것이고, 다시 조회하지 않는다.
ALTER TABLE itineraries ADD COLUMN story_status VARCHAR(10);
ALTER TABLE itineraries ADD CONSTRAINT chk_itineraries_story_status
    CHECK (story_status IN ('PENDING', 'DONE', 'FAILED'));
-- 끝나지 못한 이야기 정리(StaleStoryJob)가 모든 앱 태스크에서 주기적으로 PENDING만 찾는다.
CREATE INDEX idx_itineraries_story_pending ON itineraries (created_at) WHERE story_status = 'PENDING';

-- 기존 일정은 이야기 작업이 이미 끝났거나 끊긴 뒤라 PENDING이 될 수 없다.
-- 넣은 이유는 하루 2개까지만 스텝 메모가 되므로 그보다 많은 메모는 이야기가 쓴 것이다.
-- 메모가 모두 초안(selection_raw)의 넣은 이유와 같으면 이야기가 덮어쓰지 못한 것이다.
WITH step_notes AS (
    SELECT i.id,
           (i.end_date - i.start_date + 1) AS days,
           count(*) FILTER (WHERE btrim(coalesce(s.notes, '')) <> '') AS noted,
           count(*) FILTER (
               WHERE btrim(coalesce(s.notes, '')) <> ''
                 AND NOT EXISTS (
                     SELECT 1 FROM generation_traces t
                     WHERE t.itinerary_id = i.id
                       AND jsonb_path_exists(t.selection_raw, 'strict $.**.reason ? (@ == $n)',
                                             jsonb_build_object('n', s.notes)))
           ) AS not_reason
    FROM itineraries i
    LEFT JOIN itinerary_steps s ON s.itinerary_id = i.id
    GROUP BY i.id
)
UPDATE itineraries i
SET story_status = CASE
        WHEN n.noted = 0 THEN 'FAILED'
        WHEN n.noted > 2 * n.days THEN 'DONE'
        WHEN n.not_reason = 0 THEN 'FAILED'
    END
FROM step_notes n
WHERE n.id = i.id;
