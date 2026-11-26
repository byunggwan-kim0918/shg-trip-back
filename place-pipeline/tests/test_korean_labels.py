"""한국어 표시 이름 검사. Java OverseasEnrichment와 같은 계약 파일을 쓴다."""
import os

import pytest

from pipeline.names import korean_label, wikidata_korean_label

CONTRACT = os.path.join(os.path.dirname(__file__), "..", "..", "src", "test", "resources", "place-names",
                        "korean-labels.tsv")
CHECKS = {"name": korean_label, "wikidata": wikidata_korean_label}


def contract_rows():
    with open(CONTRACT, encoding="utf-8") as f:
        return [tuple(line.rstrip("\n").split("\t")) for line in f if line.strip() and not line.startswith("#")]


@pytest.mark.parametrize("kind,value,original,expected", contract_rows())
def test_korean_label_follows_shared_contract(kind, value, original, expected):
    assert CHECKS[kind](value, original) == (expected or None)


def test_missing_label_is_none():
    assert korean_label(None, "x") is None and wikidata_korean_label(None, "x") is None
