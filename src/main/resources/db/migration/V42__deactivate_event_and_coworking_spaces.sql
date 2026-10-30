-- V40의 스파 예외('%spa%')가 "space"에도 걸려 이벤트홀·코워킹 공간이 비활성화를 피했다.
-- V40과 같은 기준(business and professional services)으로 다시 비활성화한다. 실제 스파 리프('> spa')는 남긴다.

UPDATE places
SET active = false, deactivated_at = now(), updated_at = now()
WHERE active
  AND lower(category) LIKE '%business and professional services%'
  AND lower(category) LIKE '%space%'
  AND lower(category) NOT LIKE '%> spa%';
