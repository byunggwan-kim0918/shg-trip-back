-- 장소 입장료(원). enrich 배치(LLM)가 채우며, RouteOptimizer.estimateCost가 관광지 비용으로
-- 사용한다. 관광지는 Google/TourAPI가 priceLevel을 주지 않아 전부 0원으로 표시되던 문제
-- (실측: 성산일출봉·산방산 등 유료 명소도 0원). 무료 명소는 0, 유료는 실제 입장료로 구분한다.
ALTER TABLE places ADD COLUMN admission_fee integer;
