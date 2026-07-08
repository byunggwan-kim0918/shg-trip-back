-- 월배치 사전채움(GooglePlaceSyncScheduler.findPrefetchTargets)의 정렬·필터 키.
-- last_candidate_at DESC로 상위 N개를 뽑으므로, 인덱스가 없으면 수만~수십만 row 풀스캔 + 디스크 정렬.
-- NULL(후보로 등장한 적 없는 대다수 시딩 장소)은 대상이 아니므로 부분 인덱스로 크기 최소화.
CREATE INDEX IF NOT EXISTS idx_places_last_candidate_at
    ON places(last_candidate_at DESC) WHERE last_candidate_at IS NOT NULL;
