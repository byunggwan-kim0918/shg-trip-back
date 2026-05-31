-- FK 제거: user_place_wishlists
ALTER TABLE user_place_wishlists
    DROP CONSTRAINT IF EXISTS user_place_wishlists_user_id_fkey,
    DROP CONSTRAINT IF EXISTS user_place_wishlists_place_id_fkey;

-- FK 제거: itineraries
ALTER TABLE itineraries
    DROP CONSTRAINT IF EXISTS itineraries_user_id_fkey;

-- FK 제거: itinerary_steps
ALTER TABLE itinerary_steps
    DROP CONSTRAINT IF EXISTS itinerary_steps_itinerary_id_fkey,
    DROP CONSTRAINT IF EXISTS itinerary_steps_place_id_fkey;

-- FK 제거: alternative_options
ALTER TABLE alternative_options
    DROP CONSTRAINT IF EXISTS alternative_options_step_id_fkey,
    DROP CONSTRAINT IF EXISTS alternative_options_place_id_fkey;
