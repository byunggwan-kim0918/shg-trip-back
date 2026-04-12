CREATE TABLE places (
    id              BIGSERIAL       PRIMARY KEY,
    name            VARCHAR(255)    NOT NULL,
    address         TEXT            NOT NULL,
    latitude        DECIMAL(10, 8)  NOT NULL CHECK (latitude >= -90 AND latitude <= 90),
    longitude       DECIMAL(11, 8)  NOT NULL CHECK (longitude >= -180 AND longitude <= 180),
    category        VARCHAR(100)    NOT NULL,
    region          VARCHAR(100),
    country         VARCHAR(100),
    description     TEXT,
    rating          DECIMAL(2, 1)   CHECK (rating >= 0 AND rating <= 5),
    price_level     INTEGER         CHECK (price_level >= 1 AND price_level <= 4),
    opening_hours   TEXT,
    image_url       TEXT,
    source_url      TEXT,
    saved_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT uq_place UNIQUE (name, latitude, longitude)
);

CREATE INDEX idx_places_coordinates ON places (latitude, longitude);
CREATE INDEX idx_places_category ON places (category);
CREATE INDEX idx_places_saved_at ON places (saved_at);
CREATE INDEX idx_places_region ON places (region);
CREATE INDEX idx_places_country ON places (country);
