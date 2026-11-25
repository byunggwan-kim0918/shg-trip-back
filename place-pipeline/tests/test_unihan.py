"""Unihan 한자 읽기 표."""
from pipeline import unihan

READINGS = [
    "# comment\n",
    "U+6DFA\tkHangul\t천:0E\n",
    "U+6DFA\tkJapaneseOn\tSEN\n",
    "U+5DDD\tkHangul\t천:0E\n",
    "U+5DDD\tkJapaneseKun\tKAWA\n",
    "U+5DDD\tkJapaneseOn\tSEN\n",
]
VARIANTS = ["U+6D45\tkTraditionalVariant\tU+6DFA\n"]


def test_shinjitai_without_readings_uses_traditional_variant():
    ko = unihan.with_variants(unihan.readings(READINGS, {"kHangul"}), unihan.variants(VARIANTS))
    ja = unihan.with_variants(unihan.readings(READINGS, {"kJapaneseOn", "kJapaneseKun"}), unihan.variants(VARIANTS))
    assert ko["浅"] == ["천"] and ko["川"] == ["천"]
    assert ja["浅"] == ["sen"] and ja["川"] == ["kawa", "sen"]
