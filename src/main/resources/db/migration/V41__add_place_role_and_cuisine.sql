-- 장소의 "일정 속 역할"과 "음식 종류"를 데이터로 갖는다(보강 배치가 채운다).
--
-- 지금까지 역할은 카테고리 문자열·이름 키워드로 추정했다. 그 결과 편의점·대리점이 관광지로,
-- 이름에 "카페"가 든 한식당이 카페로, 카테고리가 "Restaurant" 하나뿐인 제주 식당 97%가 음식 종류
-- 미상으로 다뤄졌다(2026-10 감사). 보강 단계에서 LLM이 정한 값을 저장하고, 없으면 기존 추정으로 떨어진다.
--
-- place_role: MEAL | CAFE | BAR | SIGHT | ACTIVITY | SHOPPING | MARKET | LODGING | TRANSIT | NOT_VISITABLE
-- cuisine   : MEAT | SEAFOOD | NOODLE | SOUP | KOREAN_SET | JAPANESE | CHINESE | WESTERN | SNACK | OTHER (MEAL만)
ALTER TABLE places ADD COLUMN place_role VARCHAR(20);
ALTER TABLE places ADD COLUMN cuisine VARCHAR(20);
