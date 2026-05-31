-- user_auth_providers 테이블에 updated_at 컬럼 추가
ALTER TABLE user_auth_providers
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
