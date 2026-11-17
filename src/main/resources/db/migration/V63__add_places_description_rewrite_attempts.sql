-- 소개 고쳐 쓰기(보강 descriptions 모드)가 받아들일 수 없는 답을 낸 횟수. 3회 이상이면 대상에서 빠진다.
-- ADD COLUMN은 ACCESS EXCLUSIVE 잠금을 잡아, 앞선 긴 쿼리 뒤에 places 읽기가 줄 서지 않도록 잠금 대기를 짧게 끊는다.
SET LOCAL lock_timeout = '5s';

ALTER TABLE places
    ADD COLUMN description_rewrite_attempts SMALLINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN places.description_rewrite_attempts IS
  '소개 고쳐 쓰기 실패 횟수. v1 보강이 소개를 새로 쓰면 0으로 돌아간다.';
