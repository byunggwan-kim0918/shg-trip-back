"""일본 장소 이름 한국어 표기 규칙."""
from pipeline.korean_names import from_kana, from_romaji


def test_kana_follows_korean_orthography():
    assert from_kana("なかみせ") == "나카미세"
    assert from_kana("とうきょう") == "도쿄"
    assert from_kana("おおさか") == "오사카"
    assert from_kana("ラーメン") == "라멘"
    assert from_kana("すみよしじんじゃ") == "스미요시 신사"
    assert from_kana("せんそうじ") == "센소지"


def test_kana_with_kanji_is_not_guessed():
    assert from_kana("アメ横") is None


def test_romaji_with_macrons_and_suffixes():
    assert from_romaji("Sensō-ji Temple") == "센소지"
    assert from_romaji("Ōdōri Park") == "오도리 공원"
    assert from_romaji("Meiji Jingu") == "메이지 신궁"
    assert from_romaji("Mt. Moiwa") == "모이와산"
    assert from_romaji("Shin-Osaka") == "신오사카"


def test_unknown_english_word_gives_no_name():
    assert from_romaji("Tokyo Skytree") is None
    assert from_romaji("Former Hokkaido Government Office") is None
    assert from_romaji(None) is None


def test_natural_feature_and_settlement_words_are_translated():
    assert from_romaji("Asano River") == "아사노 강"
    assert from_romaji("Kabira Bay") == "가비라 만"
    assert from_romaji("Ishigaki Yaima Village") == "이시가키 야이마 마을"


def test_english_word_order_qualifiers_and_long_vowels():
    assert from_romaji("Lake Kawaguchi") == "가와구치 호수"
    assert from_romaji("Mitsuzō-in (Kawaguchi, Saitama)") == "미쓰조인"
    assert from_romaji("Heian-Jingū Shrine") == "헤이안 신궁"
    assert from_romaji("Hōon-ji Temple") == "호온지"
    assert from_romaji("Lake Biwa Canal") == "비와 호수 운하"
    assert from_romaji("Temple Kiyomizu-dera") == "기요미즈데라"
    assert from_romaji("Port Tower") is None
