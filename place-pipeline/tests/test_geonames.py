from pipeline.geonames import parse_line


def line(gid, name, code, alternates="", cls="P", population="0", cc="JP"):
    cols = [gid, name, name, alternates, "43.19", "141.00", cls, code, cc, "", "12", "", "", "", population,
            "", "10", "Asia/Tokyo", "2026-01-01"]
    return "\t".join(cols) + "\n"


def test_small_villages_and_nature_areas_are_candidates():
    village = parse_line(line("1", "Shirakawa", "PPL", population="0"))
    island = parse_line(line("2", "Yakushima", "ISL", cls="T", alternates="屋久島,야쿠시마,Yaku"))
    assert village["kind"] == "town" and village["population"] == "0"
    assert island["kind"] == "island"
    assert island["destination_key"] == "JP:gn:2"
    assert island["name_ko"] == "야쿠시마"
    assert island["aliases"] == "屋久島|야쿠시마|Yaku"


def test_individual_sites_and_city_sections_are_not_destinations():
    assert parse_line(line("3", "Kiyomizu-dera", "TMPL", cls="S")) is None
    assert parse_line(line("4", "Asakusa", "PPLX")) is None
    assert parse_line("too\tshort\n") is None
