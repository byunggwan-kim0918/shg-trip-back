CREATE TABLE itineraries (
    id                  BIGSERIAL       PRIMARY KEY,
    user_id             BIGINT          NOT NULL REFERENCES users(id),
    title               VARCHAR(255)    NOT NULL,
    destination         VARCHAR(255)    NOT NULL,
    start_date          DATE            NOT NULL,
    end_date            DATE            NOT NULL,
    total_budget        DECIMAL(15, 2),
    estimated_cost      DECIMAL(15, 2),
    cover_image         TEXT,
    tags                TEXT[],
    status              VARCHAR(20)     NOT NULL DEFAULT 'DRAFT'
                                        CHECK (status IN ('DRAFT', 'FINALIZED', 'ARCHIVED')),
    share_token         VARCHAR(64)     UNIQUE,
    share_expires_at    TIMESTAMPTZ,
    version             INTEGER         NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_itineraries_user_id ON itineraries (user_id);
CREATE INDEX idx_itineraries_status ON itineraries (status);
CREATE INDEX idx_itineraries_share_token ON itineraries (share_token) WHERE share_token IS NOT NULL;
CREATE INDEX idx_itineraries_deleted_at ON itineraries (deleted_at) WHERE deleted_at IS NULL;
