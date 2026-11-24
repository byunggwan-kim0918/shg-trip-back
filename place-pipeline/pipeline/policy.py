"""
적재 적합성 정책. 운영 DB에 넣을 만큼 믿을 수 있고 여행 정보로 쓸 수 있는지만 본다.
인기도·전화·웹사이트 유무나 LLM 점수로는 빼지 않는다. 일정 추천 적합성은 생성 단계가 따로 판정한다.
"""

# FSQ 라벨(소문자) 부분 문자열. 일정 스텝이 될 수 없는 업종이다.
NON_TRAVEL_LABEL_KEYWORDS = (
    "business and professional services",
    "health and medicine",
    "residential",
    "housing development",
    "government building",
    "education",
    "convenience store",
    "pharmacy",
    "drugstore",
    "transport hub > bus stop",
    "road > intersection",
    "fuel station",
    "travel and transportation > parking",
    "travel and transportation > train",
    "travel and transportation > platform",
    "travel and transportation > moving target",
    "travel and transportation > transportation service",
    "travel and transportation > travel agency",
    "travel and transportation > toll",
    "travel and transportation > baggage locker",
    "travel and transportation > elevator",
    "travel and transportation > border crossing",
    "airport > plane",
    "airport > airport gate",
    "airport > airport ticket counter",
    "airport > baggage claim",
    "airport > airport service",
    "transport hub > taxi stand",
    "transport hub > rental car location",
    "grocery store",
    "supermarket",
    "automotive retail",
    "furniture and home store",
    "hardware store",
    "construction supplies store",
    "office supply store",
    "warehouse or wholesale store",
    "flower store",
    "community center",
    "town hall",
    "community and government > organization",
    "print store",
    "pet supplies store",
    "pawn shop",
    "swim school",
    "soccer field",
    "baseball field",
    "electric vehicle charging station",
    "gym and studio",
    "golf driving range",
    "park > playground",
    "dog park",
    "eyecare store",
    "computers and electronics retail",
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

# 매핑표(overture_categories.csv)에서 검토 대상 분류를 뜻하는 라벨.
REVIEW_LABEL = "?"
# 여행 장소와 비여행 시설이 섞인 FSQ 라벨. 지우지 않고 검토 대상으로 남긴다(관광 도로·상점가, 공방·사무실이 같은 라벨).
REVIEW_LABEL_KEYWORDS = ("travel and transportation > road", "states and municipalities")
REVIEW_LABELS = ("Business and Professional Services", "Community and Government", "Travel and Transportation")

# FSQ unresolved_flags. 없음·사유지·삭제는 확실 제외, closed 신고는 확인 전이라 검토, inappropriate·duplicate는 제외하지 않는다.
FSQ_CERTAIN_FLAGS = ("doesnt_exist", "privatevenue", "delete")
FSQ_REVIEW_FLAGS = ("closed",)

# FSQ가 업무 서비스 아래에 두지만 여행 일정이 되는 라벨(목욕탕·스파). 업무 서비스 제외보다 우선한다.
TRAVEL_LABEL_EXCEPTIONS = (
    "Business and Professional Services > Health and Beauty Service > Spa",
    "Business and Professional Services > Health and Beauty Service > Bath House",
)

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

# 실제 확인 날짜를 주는 Overture 출처. Meta·Overture 항목의 update_time은 릴리스 스냅샷·계산 시각이라 확인일이 아니다.
DATED_DATASETS = ("Foursquare", "Microsoft")

# 제외 사유. 앞의 사유가 우선이고, 행마다 첫 사유 하나만 남는다. 확실 제외가 검토보다 앞선다.
EXCLUSION_ORDER = ("no_name", "bad_coordinate", "closed", "flagged", "junk_name", "unmapped_category",
                   "review_category", "non_travel_category", "stale", "review_name", "review_flag")
# 검토 사유 가운데 FSQ 적재 적합 행과 자동 연결되면 풀에 올리는 사유. 분류를 모르면 FSQ 분류를, stale이면 FSQ 확인일을 쓴다.
SUPPLEMENTABLE_EXCLUSIONS = ("unmapped_category", "review_category", "stale")
# 지우지 않고 다른 공급처·사람 판정으로 다시 볼 사유. 나머지는 확실 제외다.
REVIEW_EXCLUSIONS = ("unmapped_category", "review_category", "stale", "review_name", "review_flag")
