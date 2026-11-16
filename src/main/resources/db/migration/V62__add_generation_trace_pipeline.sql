-- 열 추가는 ACCESS EXCLUSIVE를 잡아 앞선 긴 쿼리를 기다리며 기록 저장을 줄 세우지 않도록 잠금 대기를 짧게 끊는다.
SET LOCAL lock_timeout = '5s';

-- 일정을 확정한 방식: v1(RouteOptimizer), v2(SerialPlanner), v2-fallback(v2 예외로 v1이 확정). 열 이전 기록은 비어 있고 v1이다.
ALTER TABLE generation_traces ADD COLUMN pipeline VARCHAR(16);
-- v2가 날짜별로 고른 장소(SelectionOutput). selection_final은 선택 모델의 날짜 배분이라 v2 일정과 다르다.
ALTER TABLE generation_traces ADD COLUMN day_picks JSONB;
