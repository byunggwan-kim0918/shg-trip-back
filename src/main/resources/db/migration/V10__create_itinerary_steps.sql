CREATE TABLE itinerary_steps (
    id                          BIGSERIAL       PRIMARY KEY,
    itinerary_id                BIGINT          NOT NULL REFERENCES itineraries(id) ON DELETE CASCADE,
    step_order                  INTEGER         NOT NULL,
    day_number                  INTEGER         NOT NULL,
    start_time                  VARCHAR(5)      NOT NULL,
    end_time                    VARCHAR(5)      NOT NULL,
    place_id                    BIGINT          REFERENCES places(id),
    transportation_mode         VARCHAR(20),
    transportation_duration     INTEGER,
    transportation_distance     DECIMAL(10, 2),
    transportation_cost         DECIMAL(10, 2),
    transportation_route        JSONB,
    notes                       TEXT,
    user_notes                  TEXT,
    estimated_cost              DECIMAL(10, 2),
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_itinerary_steps_itinerary_id ON itinerary_steps (itinerary_id);
CREATE INDEX idx_itinerary_steps_order ON itinerary_steps (itinerary_id, step_order);
