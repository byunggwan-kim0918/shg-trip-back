CREATE TABLE alternative_options (
    id              BIGSERIAL       PRIMARY KEY,
    step_id         BIGINT          NOT NULL REFERENCES itinerary_steps(id) ON DELETE CASCADE,
    place_id        BIGINT          REFERENCES places(id),
    option_order    INTEGER         NOT NULL,
    generated_at    TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_alternative_options_step_id ON alternative_options (step_id);
