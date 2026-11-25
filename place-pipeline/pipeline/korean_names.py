"""
일본 장소 이름 → 한국어 표기 규칙 변환. LLM·사전 없이 가나와 로마자(헵번식)만 옮긴다. 한자 읽기는 모르므로
한자 이름은 같은 장소의 로마자 이름(Wikidata 영어 라벨·출처 영어 이름·괄호 병기)이 있을 때만 옮긴다.
  가나 → 한글   : 국립국어원 일본어 표기법(어두 거센소리 없음, 장음 생략, っ→ㅅ 받침, ん→ㄴ 받침)
  로마자 → 가나 : 헵번식(장음 부호·ou·oo 장음, 겹자음 촉음, n' 발음)을 가나로 바꾼 뒤 위 규칙
  일반어       : 신사·신궁·공원·시장처럼 한국 여행자가 번역해 부르는 낱말만 번역하고, 모르는 영어 낱말이 있으면 옮기지 않는다
틀린 이름은 다른 장소로 잘못 잇게 하므로, 확실하지 않으면 None을 돌려준다.
"""
import re
import unicodedata

# 어두형·어중형이 다른 행(か·た 행과 그 요음). 값은 (어두, 어중)이다.
_INITIAL_SOFT = {
    "か": ("가", "카"), "き": ("기", "키"), "く": ("구", "쿠"), "け": ("게", "케"), "こ": ("고", "코"),
    "た": ("다", "타"), "ち": ("지", "치"), "て": ("데", "테"), "と": ("도", "토"),
    "きゃ": ("갸", "캬"), "きゅ": ("규", "큐"), "きょ": ("교", "쿄"),
    "ちゃ": ("자", "차"), "ちゅ": ("주", "추"), "ちょ": ("조", "초"),
}
_KANA = {
    "あ": "아", "い": "이", "う": "우", "え": "에", "お": "오",
    "さ": "사", "し": "시", "す": "스", "せ": "세", "そ": "소", "つ": "쓰",
    "な": "나", "に": "니", "ぬ": "누", "ね": "네", "の": "노",
    "は": "하", "ひ": "히", "ふ": "후", "へ": "헤", "ほ": "호",
    "ま": "마", "み": "미", "む": "무", "め": "메", "も": "모",
    "や": "야", "ゆ": "유", "よ": "요",
    "ら": "라", "り": "리", "る": "루", "れ": "레", "ろ": "로", "わ": "와", "を": "오",
    "が": "가", "ぎ": "기", "ぐ": "구", "げ": "게", "ご": "고",
    "ざ": "자", "じ": "지", "ず": "즈", "ぜ": "제", "ぞ": "조",
    "だ": "다", "ぢ": "지", "づ": "즈", "で": "데", "ど": "도",
    "ば": "바", "び": "비", "ぶ": "부", "べ": "베", "ぼ": "보",
    "ぱ": "파", "ぴ": "피", "ぷ": "푸", "ぺ": "페", "ぽ": "포",
    "しゃ": "샤", "しゅ": "슈", "しょ": "쇼", "にゃ": "냐", "にゅ": "뉴", "にょ": "뇨",
    "ひゃ": "햐", "ひゅ": "휴", "ひょ": "효", "みゃ": "먀", "みゅ": "뮤", "みょ": "묘",
    "りゃ": "랴", "りゅ": "류", "りょ": "료", "ぎゃ": "갸", "ぎゅ": "규", "ぎょ": "교",
    "じゃ": "자", "じゅ": "주", "じょ": "조", "びゃ": "뱌", "びゅ": "뷰", "びょ": "뵤",
    "ぴゃ": "퍄", "ぴゅ": "퓨", "ぴょ": "표",
    # 외래어 가타카나
    "てぃ": "티", "でぃ": "디", "ふぁ": "파", "ふぃ": "피", "ふぇ": "페", "ふぉ": "포", "うぃ": "위", "うぇ": "웨",
    "うぉ": "워", "しぇ": "셰", "じぇ": "제", "ちぇ": "체", "ゔ": "부", "でゅ": "듀",
}
_O_ROW = set("おこそとのほもよろごぞどぼぽょ")
_U_ROW = set("うくすつぬふむゆるぐずづぶぷゅ")

# 한국 여행자가 번역해 부르는 일본어 일반어(로마자). 값이 None이면 앞 낱말에 붙은 것으로 보고 옮긴다.
ROMAJI_GENERIC = {"jinja": "신사", "jingu": "신궁", "koen": "공원", "ichiba": "시장", "shotengai": "상점가",
                  "eki": "역", "onsen": "온천"}
# 영어 일반어. 앞 이름 뒤에 번역해 붙인다. temple은 앞 낱말이 -ji·-dera면 이미 절 이름이라 뺀다.
ENGLISH_GENERIC = {"shrine": "신사", "park": "공원", "market": "시장", "castle": "성", "museum": "박물관",
                   "aquarium": "수족관", "zoo": "동물원", "garden": "정원", "gardens": "정원", "tower": "타워",
                   "station": "역", "street": "거리", "canal": "운하", "falls": "폭포", "waterfall": "폭포",
                   "lake": "호수", "beach": "해변", "island": "섬", "bridge": "다리", "gate": "문", "port": "항",
                   "pond": "연못", "temple": "사", "river": "강", "bay": "만", "village": "마을", "valley": "계곡",
                   "gorge": "협곡", "hill": "언덕", "church": "교회"}
ENGLISH_PHRASES = {"art museum": "미술관", "shopping street": "상점가", "hot spring": "온천", "hot springs": "온천",
                   "botanical garden": "식물원", "botanical gardens": "식물원", "observation deck": "전망대"}
# 가나 이름 끝의 일반어. 로마자 일반어와 같은 번역이다.
KANA_GENERIC = {"じんじゃ": "신사", "じんぐう": "신궁", "こうえん": "공원", "いちば": "시장", "しょうてんがい": "상점가",
                "えき": "역", "おんせん": "온천", "びじゅつかん": "미술관", "はくぶつかん": "박물관", "すいぞくかん": "수족관",
                "どうぶつえん": "동물원", "しょくぶつえん": "식물원", "ていえん": "정원"}
_MOUNT = {"mt", "mount"}
_SMALL_TSU = "っ"
_N = "ん"


def from_kana(text):
    """가나만으로 된 이름 → 한글. 가나·장음 부호·가운뎃점·공백 밖의 글자가 있으면 None."""
    if not text:
        return None
    hira = _to_hiragana(unicodedata.normalize("NFKC", text))
    if not re.fullmatch(r"[ぁ-ゖー・\s]+", hira):
        return None
    out = []
    for word in (w for w in re.split(r"[・\s]+", hira) if w):
        suffix = next((k for k in sorted(KANA_GENERIC, key=len, reverse=True)
                       if word.endswith(k) and len(word) > len(k)), None)
        head = _kana_word(word[:-len(suffix)] if suffix else word)
        if head is None:
            return None
        out.append(head + (" " + KANA_GENERIC[suffix] if suffix else ""))
    return " ".join(out) if out else None


def from_romaji(text):
    """로마자 이름(헵번식 + 영어 일반어) → 한글. 옮길 수 없는 낱말이 하나라도 있으면 None."""
    if not text:
        return None
    # 괄호 안은 같은 이름의 다른 장소와 구별하는 말(Mitsuzō-in (Kawaguchi, Saitama))이라 이름이 아니다.
    folded = re.sub(r"\s*\([^)]*\)", "", unicodedata.normalize("NFKC", text)).strip()
    lowered = folded.lower()
    for phrase, ko in ENGLISH_PHRASES.items():
        lowered = re.sub(rf"\b{phrase}\b", ko, lowered)
    tokens = [t for t in re.split(r"[\s/]+", lowered.replace("’", "'")) if t]
    out, mount, lead = [], False, None
    for token in tokens:
        if re.fullmatch(r"[가-힣]+", token):
            out.append(token)
            continue
        bare = token.strip(".,()")
        if bare in _MOUNT:
            mount = True
            continue
        if bare == "the" or not bare:
            continue
        if bare in ENGLISH_GENERIC:
            if bare == "temple" and out and re.search(r"(지|데라)$", out[-1]):
                continue
            if bare == "shrine" and out and re.search(r"(신궁|신사)$", out[-1]):
                continue
            if lead:
                return None
            # 영어는 일반어가 앞에 온다(Lake Kawaguchi). 한국어 표기는 첫 이름 뒤에 붙인다.
            if not out and len(tokens) > 1:
                lead = ENGLISH_GENERIC[bare]
                continue
            out.append(ENGLISH_GENERIC[bare])
            continue
        parts = bare.split("-")
        word = ""
        for i, part in enumerate(parts):
            plain = _strip_macrons(part)
            if i > 0 and plain in ROMAJI_GENERIC:
                word += " " + ROMAJI_GENERIC[plain]
                continue
            if plain in ROMAJI_GENERIC and i == 0 and out:
                out.append(ROMAJI_GENERIC[plain])
                word = None
                break
            kana = _romaji_to_kana(part)
            if kana is None:
                return None
            ko = _kana_word(kana, initial=(i == 0))
            if ko is None:
                return None
            word += ko
        if word is None:
            continue
        out.append(word + ("산" if mount else ""))
        mount = False
        if lead:
            if not (lead == "사" and re.search(r"(지|데라)$", out[-1])):
                out.append(lead)
            lead = None
    if lead:
        return None
    return " ".join(out) if out else None


def _kana_word(word, initial=True):
    out = []
    i = 0
    prev = ""
    while i < len(word):
        ch = word[i]
        if ch == "ー":
            i += 1
            continue
        if ch == _SMALL_TSU:
            if out:
                out[-1] = _add_final(out[-1], "ㅅ")
            i += 1
            continue
        if ch == _N:
            if not out:
                return None
            out[-1] = _add_final(out[-1], "ㄴ")
            prev = ch
            i += 1
            continue
        # 장음: お段 뒤 う·お, う段 뒤 う는 적지 않는다.
        if ch in "うお" and prev and (prev[-1] in _O_ROW and ch in "うお" or prev[-1] in _U_ROW and ch == "う"):
            if not (i + 1 < len(word) and word[i + 1] in "ぁぃぅぇぉゃゅょ"):
                prev = ch
                i += 1
                continue
        two = word[i:i + 2]
        unit = two if two in _KANA or two in _INITIAL_SOFT else ch
        first = not out
        if unit in _INITIAL_SOFT:
            syllable = _INITIAL_SOFT[unit][0 if first and initial else 1]
        elif unit in _KANA:
            syllable = _KANA[unit]
        else:
            return None
        out.append(syllable)
        prev = unit
        i += len(unit)
    return "".join(out) if out else None


_ROMAJI = sorted({
    "kya": "きゃ", "kyu": "きゅ", "kyo": "きょ", "sha": "しゃ", "shu": "しゅ", "sho": "しょ", "chi": "ち",
    "cha": "ちゃ", "chu": "ちゅ", "cho": "ちょ", "tsu": "つ", "shi": "し", "nya": "にゃ", "nyu": "にゅ", "nyo": "にょ",
    "hya": "ひゃ", "hyu": "ひゅ", "hyo": "ひょ", "mya": "みゃ", "myu": "みゅ", "myo": "みょ", "rya": "りゃ",
    "ryu": "りゅ", "ryo": "りょ", "gya": "ぎゃ", "gyu": "ぎゅ", "gyo": "ぎょ", "bya": "びゃ", "byu": "びゅ",
    "byo": "びょ", "pya": "ぴゃ", "pyu": "ぴゅ", "pyo": "ぴょ", "ja": "じゃ", "ju": "じゅ", "jo": "じょ", "ji": "じ",
    "fu": "ふ", "ka": "か", "ki": "き", "ku": "く", "ke": "け", "ko": "こ", "sa": "さ", "su": "す", "se": "せ",
    "so": "そ", "ta": "た", "te": "て", "to": "と", "na": "な", "ni": "に", "nu": "ぬ", "ne": "ね", "no": "の",
    "ha": "は", "hi": "ひ", "he": "へ", "ho": "ほ", "ma": "ま", "mi": "み", "mu": "む", "me": "め", "mo": "も",
    "ya": "や", "yu": "ゆ", "yo": "よ", "ra": "ら", "ri": "り", "ru": "る", "re": "れ", "ro": "ろ", "wa": "わ",
    "wo": "を", "ga": "が", "gi": "ぎ", "gu": "ぐ", "ge": "げ", "go": "ご", "za": "ざ", "zu": "ず", "ze": "ぜ",
    "zo": "ぞ", "da": "だ", "de": "で", "do": "ど", "ba": "ば", "bi": "び", "bu": "ぶ", "be": "べ", "bo": "ぼ",
    "pa": "ぱ", "pi": "ぴ", "pu": "ぷ", "pe": "ぺ", "po": "ぽ", "a": "あ", "i": "い", "u": "う", "e": "え", "o": "お",
}.items(), key=lambda kv: -len(kv[0]))
_LONG = {"ā": "a", "ī": "i", "ū": "u", "ē": "e", "ō": "o", "â": "a", "î": "i", "û": "u", "ê": "e", "ô": "o"}


def _strip_macrons(text):
    return "".join(_LONG.get(c, c) for c in text)


# 장음 부호를 겹모음으로 푼다. 지우기만 하면 Hōon(ほうおん)이 hoon(ほおん)으로 읽혀 뒤 모음과 합쳐진다.
_LONG_SPELLED = {"ā": "aa", "ī": "ii", "ū": "uu", "ē": "ee", "ō": "ou", "â": "aa", "î": "ii", "û": "uu", "ê": "ee", "ô": "ou"}


def _romaji_to_kana(word):
    """헵번식 로마자 낱말 → 히라가나. 장음 부호는 장음이라 적지 않는다. 헵번식으로 읽히지 않으면 None."""
    w = "".join(_LONG_SPELLED.get(c, c) for c in word.lower()).replace("'", "’")
    if not re.fullmatch(r"[a-z’]+", w):
        return None
    out, i = [], 0
    while i < len(w):
        c = w[i]
        if c == "’":
            i += 1
            continue
        if c == "n" and (i + 1 == len(w) or w[i + 1] not in "aiueoy"):
            out.append(_N)
            i += 1
            continue
        if c == "m" and i + 1 < len(w) and w[i + 1] in "bmp":
            out.append(_N)
            i += 1
            continue
        if i + 1 < len(w) and c == w[i + 1] and c not in "aiueon":
            out.append(_SMALL_TSU)
            i += 1
            continue
        if c == "t" and w[i:i + 3] == "tch":
            out.append(_SMALL_TSU)
            i += 1
            continue
        for roman, kana in _ROMAJI:
            if w.startswith(roman, i):
                out.append(kana)
                i += len(roman)
                break
        else:
            return None
    return "".join(out)


def _to_hiragana(text):
    return "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in text)


def _add_final(syllable, final):
    """받침 없는 한글 음절에 받침(ㄴ·ㅅ)을 더한다. 이미 받침이 있으면 그대로 둔다."""
    code = ord(syllable[-1]) - 0xAC00
    if code < 0 or code > 11171 or code % 28:
        return syllable
    index = {"ㄴ": 4, "ㅅ": 19}[final]
    return syllable[:-1] + chr(0xAC00 + code + index)
