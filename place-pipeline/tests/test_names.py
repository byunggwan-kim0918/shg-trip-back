import os

import pytest

from pipeline.names import exact_key

CONTRACT = os.path.join(os.path.dirname(__file__), "..", "..", "src", "test", "resources", "place-names", "exact-keys.tsv")


def contract_rows():
    with open(CONTRACT, encoding="utf-8") as f:
        return [tuple(line.rstrip("\n").split("\t")) for line in f if line.strip() and not line.startswith("#")]


@pytest.mark.parametrize("raw,expected", contract_rows())
def test_exact_key_follows_shared_contract(raw, expected):
    assert exact_key(raw) == expected


def test_empty_name_has_empty_key():
    assert exact_key(None) == ""
    assert exact_key("  · ") == ""


def test_bare_key_drops_bracketed_notes_like_java():
    from pipeline.names import bare_key
    assert bare_key("大涌谷 (Ōwakudani)") == "大涌谷"
    assert bare_key("鹿苑寺（金閣寺）") == "鹿苑寺"
    assert bare_key("(株)") == "株"
