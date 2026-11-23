"""
적재 적합성 정책. 운영 DB에 넣을 만큼 믿을 수 있고 여행 정보로 쓸 수 있는지만 본다.
인기도·전화·웹사이트 유무나 LLM 점수로는 빼지 않는다. 일정 추천 적합성은 생성 단계가 따로 판정한다.
"""

# FSQ 라벨(소문자) 부분 문자열. 일정 스텝이 될 수 없는 업종이다.
NON_TRAVEL_LABEL_KEYWORDS = (
    "business and professional services",
    "health and medicine",
    "residential",
    "government building",
    "education",
    "convenience store",
    "pharmacy",
    "drugstore",
    "transport hub > bus stop",
    "travel and transportation > road",
    "fuel station",
    "travel and transportation > parking",
    "electric vehicle charging station",
    "gym and studio",
    "golf driving range",
    "park > playground",
    "dog park",
    "eyecare store",
    "computers and electronics retail",
    "states and municipalities",
    "polling place",
    "assisted living",
    "cemetery",
    "movie theater",
    "karaoke",
    "pachinko",
    "gaming cafe",
    "internet cafe",
    "bingo",
    "lottery",
    "strip club",
    "adult",
)

# 일정에 쓸 수 없는 명소·교통 하위 시설 이름(승강장·개찰구·출구·주차장). 식음료에는 적용하지 않는다.
JUNK_NAME_PATTERN = (r"(?i)(番線|改札|ホーム|出口|入口|駐車場|乗り場|のりば|站台|出入口|出站口|登机口|搭乗口"
                     r"|[A-Z]\d*口|\d+号口|停车场|停車場|Bãi (đỗ|giữ) xe|\bExit\s?\d|\bGate\s?\d|\bPlatform\s?\d"
                     r"|주차장|출구|승강장)")

# 갱신 기준(년). 명소·자연·종교 시설은 갱신이 뜸할 뿐 사라지지 않고, 식당·상점은 오래 갱신되지 않으면 폐업 가능성이 높다.
FRESH_YEARS_LASTING = 5
FRESH_YEARS_DEFAULT = 2
LASTING_LABEL_PREFIXES = (
    "Landmarks and Outdoors",
    "Arts and Entertainment",
    "Community and Government > Spiritual Center",
    "Community and Government > Cultural Center",
    "Community and Government > Observatory",
)

# 체인이어도 그 자체가 여행 코스인 브랜드(소문자 이름 접두어). 체인 판정보다 우선한다.
TOURIST_CHAINS = (
    "ichiran", "一蘭", "一兰", "ippudo", "一風堂", "一风堂", "sushiro", "スシロー", "寿司郎",
    "marugame", "丸亀製麺", "丸龜製麵", "丸龟制面", "din tai fung", "鼎泰豐", "鼎泰丰",
    "haidilao", "海底捞", "海底撈", "cộng cà phê", "cong caphe", "cong ca phe", "kebuke", "可不可",
    "50嵐", "85度c", "85°c", "點都德", "点都德", "茶颜悦色", "551蓬莱", "551蓬萊",
)

# 제외 사유. 앞의 사유가 우선이고, 행마다 첫 사유 하나만 남는다.
EXCLUSION_ORDER = ("no_name", "bad_coordinate", "closed", "flagged", "unmapped_category",
                   "non_travel_category", "stale", "junk_name", "chain")
