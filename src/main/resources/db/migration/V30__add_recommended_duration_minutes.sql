-- 장소별 권장 체류시간(분). enrich 배치(LLM)가 채우며, RouteOptimizer.scheduleDay가
-- 관광 스텝 체류시간으로 사용한다(없으면 카테고리 휴리스틱 폴백).
-- 배경: 일괄 90분 체류가 등산형 오름(실소요 3시간+)과 해변 산책(1시간)을 구분하지 못해
-- 여행자가 일정 시각을 신뢰할 수 없는 문제(실측: 사라오름 90분 배치).
ALTER TABLE places ADD COLUMN recommended_duration_minutes integer;
