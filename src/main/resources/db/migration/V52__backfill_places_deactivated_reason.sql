-- V51과 나눠 둔다. 같은 트랜잭션이면 ALTER의 ACCESS EXCLUSIVE 잠금이 이 UPDATE가 끝날 때까지 남는다.
-- 지금까지 꺼진 행은 모두 마이그레이션 정리분(V31·V40·V42)이다.
UPDATE places SET deactivated_reason = 'policy' WHERE active = false AND deactivated_reason IS NULL;
