"""장소 이름 정확 키. 규칙은 src/test/resources/place-names/exact-keys.tsv 계약과 Java PlaceNames.exactKey를 따른다."""
import re
import unicodedata

# 글자(L)·숫자(N)만 남긴다. 결합 부호(M)·서식 문자(Cf)도 지워 DB [:alnum:]과 같게 한다.
_KEPT_CATEGORIES = frozenset("LN")


def exact_key(name):
    """같은 이름을 공백·문장부호·전각 차이 없이 비교하는 키. 빈 이름이면 빈 문자열."""
    if not name:
        return ""
    folded = unicodedata.normalize("NFKC", name).lower()
    return "".join(ch for ch in folded if unicodedata.category(ch)[0] in _KEPT_CATEGORIES)


# Java PlaceNames.BRACKETS와 같은 괄호. 괄호 안은 다른 표기·위치 설명이라 본래 이름 키에서 뺀다.
_BRACKETS = re.compile(r"[(\[（【]([^)\]）】]*)[)\]）】]")


def bare_key(name):
    """괄호 설명을 뗀 정확 키(Java PlaceNames.bareKey). 떼고 나서 비면 이름 전체의 키."""
    key = exact_key(_BRACKETS.sub("", name or ""))
    return key or exact_key(name)
