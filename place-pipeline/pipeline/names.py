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


# 아래는 Java OverseasEnrichment.nameKo와 같은 한국어 표시 이름 검사다(계약 src/test/resources/place-names/korean-labels.tsv).
MAX_KOREAN_LABEL_LENGTH = 60
_NON_NAMES = frozenset({"한국어이름", "한국어표시이름", "없음", "미상", "알수없음", "모름", "해당없음", "이름없음", "null", "na"})
_LABEL_BRACKET = re.compile(r"\s*[(（\[【]([^)）\]】]*)[)）\]】]", re.ASCII)
_REMARK_MARK = re.compile(r"[—–―:：]")
_ORIGINAL_MARK = re.compile(r"[—–―:：\-−〜~]")
_ABBREVIATION = re.compile(r"[A-Z]{1,4}")
# Java UnicodeScript.HANGUL 범위(괄호·원문자 한글 포함).
_HANGUL_RANGES = ((0x1100, 0x11FF), (0x302E, 0x302F), (0x3131, 0x318E), (0x3200, 0x321E), (0x3260, 0x327E),
                  (0xA960, 0xA97C), (0xAC00, 0xD7A3), (0xD7B0, 0xD7FB), (0xFFA0, 0xFFDC))


def _hangul(ch):
    return any(lo <= ord(ch) <= hi for lo, hi in _HANGUL_RANGES)


# Java UnicodeScript HAN·HIRAGANA·KATAKANA에 드는 글자 이름. 가운뎃점(・)은 COMMON이라 받고, 장음(ー)은 Java처럼 따로 막는다.
_CJK_NAME_PREFIXES = ("CJK UNIFIED IDEOGRAPH", "CJK COMPATIBILITY IDEOGRAPH", "CJK RADICAL", "KANGXI RADICAL",
                      "IDEOGRAPHIC ITERATION MARK", "IDEOGRAPHIC CLOSING MARK", "IDEOGRAPHIC NUMBER ZERO",
                      "VERTICAL IDEOGRAPHIC ITERATION MARK", "HIRAGANA LETTER", "HIRAGANA DIGRAPH",
                      "HIRAGANA ITERATION MARK", "HIRAGANA VOICED ITERATION MARK", "KATAKANA LETTER", "KATAKANA DIGRAPH",
                      "KATAKANA ITERATION MARK", "KATAKANA VOICED ITERATION MARK", "HALFWIDTH KATAKANA LETTER")


def _cjk(ch):
    return ch == "ー" or unicodedata.name(ch, "").startswith(_CJK_NAME_PREFIXES)


def korean_label(value, original_name):
    """
    한국어 표시 이름으로 쓸 수 있는 값. 한글이 없거나 한자·가나가 남거나, 원문에 없는 외국 글자·설명 기호가 있으면 None이다.
    원문에 없는 괄호("나카노 역 (도쿄 도)")는 모델이 붙인 구분이라 뗀다. 원문 괄호를 옮긴 한글 괄호는 부속 시설 이름일 수
    있어 괄호만 뗀다.
    """
    if value is None:
        return None
    v = value.strip()
    if len(v) >= 2 and v.startswith('"') and v.endswith('"'):
        v = v[1:-1].strip()
    original = unicodedata.normalize("NFKC", original_name or "").lower()
    bracketed = _LABEL_BRACKET.search(original) is not None

    def unbracket(m):
        inner = m.group(1).strip()
        return " " + inner if bracketed and any(_hangul(c) for c in inner) else ""

    v = re.sub(r"\s+", " ", _LABEL_BRACKET.sub(unbracket, v), flags=re.ASCII).strip()
    if not v or len(v.encode("utf-16-le")) // 2 > MAX_KOREAN_LABEL_LENGTH or exact_key(v) in _NON_NAMES:
        return None
    if not any(_hangul(c) for c in v) or any(_cjk(c) for c in v):
        return None
    letters = "".join(c if unicodedata.category(c).startswith("L") and not _hangul(c) else " " for c in v)
    for part in letters.split():
        if part.lower() not in original and not _ABBREVIATION.fullmatch(part):
            return None
    if _REMARK_MARK.search(v) and not _ORIGINAL_MARK.search(original):
        return None
    return v


# 위키백과 제목식 한국어 라벨 끝의 괄호("가마타 역 (도쿄 도)"). 원문 괄호를 옮긴 것이 아니라 같은 이름 문서를 가르는 말이다.
_DISAMBIGUATION = re.compile(r"\s*\([^()]*\)\s*$", re.ASCII)


def wikidata_korean_label(label, original_name):
    """Wikidata 한국어 라벨(Java WikidataSitelinksJob.koreanLabel). 끝의 구분 괄호를 떼고 korean_label 검사를 거친다."""
    return None if label is None else korean_label(_DISAMBIGUATION.sub("", label, count=1), original_name)
