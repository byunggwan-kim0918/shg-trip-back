-- ==============================================
-- users
-- ==============================================
CREATE TABLE IF NOT EXISTS users (
    id              BIGSERIAL       PRIMARY KEY,
    email           VARCHAR(255)    NOT NULL UNIQUE,
    nickname        VARCHAR(50)     NOT NULL,
    profile_image   VARCHAR(500),
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    role            VARCHAR(20)     NOT NULL DEFAULT 'USER',
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,

    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'DORMANT', 'SUSPENDED', 'WITHDRAWN')),
    CONSTRAINT chk_users_role CHECK (role IN ('USER', 'ADMIN'))
);

CREATE INDEX idx_users_active ON users(id) WHERE deleted_at IS NULL;

-- ==============================================
-- user_auth_providers
-- ==============================================
CREATE TABLE IF NOT EXISTS user_auth_providers (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL,
    provider        VARCHAR(20)     NOT NULL,
    provider_id     VARCHAR(255)    NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT uq_user_provider UNIQUE (user_id, provider),
    CONSTRAINT uq_provider_provider_id UNIQUE (provider, provider_id),
    CONSTRAINT chk_provider CHECK (provider IN ('KAKAO', 'GOOGLE', 'NAVER', 'APPLE'))
);

CREATE INDEX idx_auth_providers_user_id ON user_auth_providers(user_id);
