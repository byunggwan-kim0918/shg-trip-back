-- 소셜 로그인별 별도 계정 생성 정책으로 변경
-- 동일 이메일로 여러 계정 생성 가능하도록 UNIQUE 제약 제거
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_email_key;
