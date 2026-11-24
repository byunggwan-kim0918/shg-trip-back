from pipeline import policy
from pipeline.categories import load_overture_map, overture_label


def test_most_specific_key_wins():
    mapping = load_overture_map()
    label, key = overture_label(["food_and_drink", "restaurant", "japanese_restaurant", "sushi_restaurant"],
                                "sushi_restaurant", mapping)
    assert key == "sushi_restaurant"
    assert label.endswith("Sushi Restaurant")


def test_unknown_leaf_falls_back_to_parent():
    label, key = overture_label(["food_and_drink", "restaurant", "obscure_cuisine"], "obscure_cuisine",
                                load_overture_map())
    assert (label, key) == ("Dining and Drinking > Restaurant", "restaurant")


def test_non_travel_branch_has_empty_label():
    label, key = overture_label(["health_care", "dentist"], "dentist", load_overture_map())
    assert (label, key) == ("", "health_care")


def test_unmapped_returns_none():
    assert overture_label(["mystery"], "mystery", load_overture_map()) == (None, None)


def test_primary_without_hierarchy_is_used():
    label, _ = overture_label(None, "national_park", load_overture_map())
    assert label == "Landmarks and Outdoors > National Park"


def test_every_label_uses_fsq_top_level():
    tops = {"Arts and Entertainment", "Business and Professional Services", "Community and Government", "Dining and Drinking", "Landmarks and Outdoors",
            "Retail", "Sports and Recreation", "Travel and Transportation"}
    for key, label in load_overture_map().items():
        assert label in ("", policy.REVIEW_LABEL) or label.split(" > ")[0] in tops, key


def test_well_known_venues_are_loaded_and_left_to_recommendation():
    mapping = load_overture_map()
    assert overture_label(["arts_and_entertainment", "casino"], "casino", mapping)[0] == "Arts and Entertainment > Casino"
    assert overture_label(["education", "library"], "library", mapping)[0] == "Community and Government > Library"
    assert overture_label(["cultural_and_historic", "religious_organization"], "religious_organization",
                          mapping)[0] == "Community and Government > Spiritual Center"
