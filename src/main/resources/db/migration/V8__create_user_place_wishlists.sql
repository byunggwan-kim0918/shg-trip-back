CREATE TABLE user_place_wishlists
(
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    place_id   BIGINT      NOT NULL REFERENCES places (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_place UNIQUE (user_id, place_id)
);

CREATE INDEX idx_wishlist_user_id ON user_place_wishlists (user_id);
CREATE INDEX idx_wishlist_place_id ON user_place_wishlists (place_id);
