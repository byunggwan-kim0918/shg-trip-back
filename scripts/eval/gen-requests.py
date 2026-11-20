#!/usr/bin/env python3
"""일정 생성 평가용 요청 세트를 만든다. --scope overseas면 해외(일본 위주) 세트다.

대화 입력(TripRequestMapper)이 만드는 요청과 같은 모양으로 만든다. 설명은 " / "로 이은 조각이다.
같은 시드면 같은 세트가 나오고, 홀드아웃은 개발 중에 열어 보지 않는다.

  python3 scripts/eval/gen-requests.py --count 2000 --seed 20261117 --out out/requests.jsonl

각 줄: {"id", "split": holdout|dev|bulk, "request": 생성 요청 바디, "expect": 판정용 기대값}
"""
import argparse
import datetime as dt
import hashlib
import json
import random

SEPARATOR = " / "

# 도시별 실제 명소·음식·경험. 요청 문장 재료이자 "요청 반영" 판정의 기대값이다.
CITIES = {
    "서울": dict(
        sights=["경복궁", "창덕궁", "북촌한옥마을", "광장시장", "남산서울타워", "익선동", "성수동", "롯데월드", "망원시장", "국립중앙박물관"],
        foods=["삼겹살", "칼국수", "닭한마리", "빈대떡", "냉면"],
        night=["남산서울타워 야경", "반포한강공원 야경"], dawn=[],
        themes=["culture", "shopping", "food", "nightview"], walkable=True),
    "부산": dict(
        sights=["해운대해수욕장", "광안리해수욕장", "감천문화마을", "자갈치시장", "해동용궁사", "흰여울문화마을", "태종대", "국제시장", "송도해상케이블카"],
        foods=["돼지국밥", "밀면", "회", "씨앗호떡", "어묵"],
        night=["광안대교 야경"], dawn=["해운대 일출"],
        themes=["ocean", "food", "nightview"], walkable=True),
    "제주": dict(
        sights=["성산일출봉", "우도", "협재해수욕장", "섭지코지", "만장굴", "오설록 티뮤지엄", "동문시장", "새별오름", "천지연폭포", "카멜리아힐"],
        foods=["흑돼지", "고기국수", "갈치조림", "해물라면", "고등어회"],
        night=[], dawn=["성산일출봉 일출"],
        themes=["nature", "ocean", "healing"], walkable=False),
    "경주": dict(
        sights=["불국사", "석굴암", "대릉원", "첨성대", "동궁과 월지", "황리단길", "월정교", "국립경주박물관", "보문호"],
        foods=["쌈밥", "한정식", "황남빵", "칼국수"],
        night=["동궁과 월지 야경"], dawn=[],
        themes=["culture", "photo", "healing"], walkable=True),
    "강릉": dict(
        sights=["경포해변", "안목해변", "정동진", "오죽헌", "주문진수산시장", "강문해변", "테라로사 커피공장", "경포호"],
        foods=["초당순두부", "장칼국수", "물회", "감자옹심이"],
        night=["경포호 야경"], dawn=["정동진 일출"],
        themes=["ocean", "healing", "local"], walkable=False),
    "전주": dict(
        sights=["전주한옥마을", "경기전", "전동성당", "오목대", "자만벽화마을", "남부시장", "객리단길"],
        foods=["비빔밥", "콩나물국밥", "피순대", "막걸리"],
        night=["남부시장 야시장"], dawn=[],
        themes=["culture", "food", "local"], walkable=True),
    "여수": dict(
        sights=["오동도", "여수해상케이블카", "이순신광장", "향일암", "돌산공원", "아쿠아플라넷 여수", "고소동 벽화마을"],
        foods=["게장", "갓김치", "서대회", "갈치조림"],
        night=["여수 밤바다", "낭만포차"], dawn=["향일암 일출"],
        themes=["ocean", "nightview", "food"], walkable=False),
    "대구": dict(
        sights=["근대골목", "서문시장", "김광석다시그리기길", "앞산전망대", "동화사", "수성못", "83타워"],
        foods=["막창", "납작만두", "찜갈비", "뭉티기", "따로국밥"],
        night=["서문시장 야시장", "앞산전망대 야경"], dawn=[],
        themes=["food", "culture", "local"], walkable=True),
    "대전": dict(
        sights=["성심당", "국립중앙과학관", "엑스포과학공원", "한밭수목원", "대청호", "계족산 황톳길", "유성온천"],
        foods=["칼국수", "두부두루치기", "빵"],
        night=["엑스포다리 야경"], dawn=[],
        themes=["food", "family", "nature"], walkable=True),
    "광주": dict(
        sights=["국립아시아문화전당", "양림동", "무등산", "1913송정역시장", "대인예술시장", "광주호 호수생태원"],
        foods=["떡갈비", "오리탕", "육전", "상추튀김"],
        night=["대인예술시장 야시장"], dawn=[],
        themes=["art", "food", "culture"], walkable=True),
    "수원": dict(
        sights=["수원화성", "화성행궁", "방화수류정", "행리단길", "수원 통닭거리", "광교호수공원"],
        foods=["통닭", "갈비"],
        night=["수원화성 야경"], dawn=[],
        themes=["culture", "food", "walking"], walkable=True),
    "인천": dict(
        sights=["차이나타운", "월미도", "송월동 동화마을", "을왕리해수욕장", "송도 센트럴파크", "강화도", "소래포구"],
        foods=["짜장면", "조개구이", "닭강정"],
        night=["월미도 야경"], dawn=[],
        themes=["food", "ocean", "culture"], walkable=True),
}

# 해외 여행지. 이름은 destinations.json의 한국어 이름과 같아야 한다.
OVERSEAS_CITIES = {
    "도쿄": dict(
        sights=["센소지", "메이지 신궁", "시부야 스크램블 교차로", "도쿄 타워", "우에노 공원", "츠키지 장외시장", "도쿄 스카이트리", "신주쿠 교엔"],
        foods=["스시", "라멘", "몬자야키", "텐동"],
        night=["도쿄 타워 야경"], dawn=[],
        themes=["shopping", "food", "culture", "nightview"], walkable=True),
    "오사카": dict(
        sights=["오사카성", "도톤보리", "유니버설 스튜디오 재팬", "구로몬 시장", "신세카이", "우메다 스카이 빌딩", "기요미즈데라", "후시미 이나리 신사", "나라 공원"],
        foods=["타코야키", "오코노미야키", "쿠시카츠", "라멘"],
        night=["우메다 스카이 빌딩 야경"], dawn=[],
        themes=["food", "shopping", "culture"], walkable=True),
    "후쿠오카": dict(
        sights=["다자이후 텐만구", "오호리 공원", "캐널시티 하카타", "후쿠오카 타워", "모모치 해변", "나카스"],
        foods=["돈코츠 라멘", "모츠나베", "멘타이코"],
        night=["나카스 야타이"], dawn=[],
        themes=["food", "shopping", "healing"], walkable=True),
    "삿포로": dict(
        sights=["오도리 공원", "삿포로 시계탑", "모이와산", "니조 시장", "삿포로 맥주 박물관", "오타루 운하"],
        foods=["징기스칸", "수프카레", "미소 라멘", "해산물 덮밥"],
        night=["모이와산 야경"], dawn=[],
        themes=["nature", "food", "photo"], walkable=True),
    "오키나와": dict(
        sights=["슈리성", "국제거리", "츄라우미 수족관", "만좌모", "아메리칸 빌리지", "고우리 대교"],
        foods=["오키나와 소바", "타코라이스", "아구 돼지"],
        night=[], dawn=[],
        themes=["ocean", "nature", "healing"], walkable=False),
    "타이베이": dict(
        sights=["타이베이 101", "국립고궁박물원", "스린 야시장", "중정기념당", "용산사", "지우펀"],
        foods=["우육면", "샤오롱바오", "망고빙수"],
        night=["스린 야시장"], dawn=[],
        themes=["food", "culture", "nightview"], walkable=True),
    "다낭": dict(
        sights=["미케 해변", "바나 힐", "용다리", "한 시장", "오행산", "호이안 구시가지"],
        foods=["쌀국수", "반미", "분짜"],
        night=["용다리 야경"], dawn=[],
        themes=["ocean", "food", "healing"], walkable=False),
}

# 해외는 일본 여행이 대부분이라 일본 여행지를 더 자주 고른다.
OVERSEAS_WEIGHTS = [("도쿄", .25), ("오사카", .25), ("후쿠오카", .15), ("삿포로", .1), ("오키나와", .1),
                    ("타이베이", .08), ("다낭", .07)]
OVERSEAS_MULTI = [("도쿄", "오사카"), ("후쿠오카", "오사카")]

# 거리상 하루 안에 오가기 어려워 나눠 묵는 짝. 150km 미만 짝(부산·경주, 전주·여수)도 일부러 넣는다.
MULTI = [("서울", "부산"), ("부산", "경주"), ("전주", "여수"), ("서울", "제주"), ("부산", "제주"), ("강릉", "경주"), ("서울", "전주")]

COMPANIONS = [("couple", .28), ("friends", .22), ("family", .2), ("parents", .15), ("solo", .15)]
NIGHTS = [(1, .3), (2, .35), (3, .2), (4, .1), (5, .05)]
FOOD_AVOID = [("회", ["RAW"]), ("해산물", ["SEAFOOD"]), ("돼지고기", ["PORK"]), ("소고기", ["BEEF"]), ("닭고기", ["CHICKEN"]), ("고기", ["MEAT"])]
ACTIVITY_AVOID = [("등산", ["HIKING"]), ("박물관", ["MUSEUM"]), ("쇼핑", ["SHOPPING"]), ("놀이공원", ["THEME_PARK"])]
ARRIVALS = ["09:00", "11:00", "13:00", "15:00", "18:00", "21:00"]
DEPARTURES = ["10:00", "13:00", "15:00", "17:00", "19:00"]
THEME_CATEGORIES = {"ocean": "beach", "nature": "nature", "food": "restaurant", "culture": "attraction",
                    "nightview": "viewpoint", "shopping": "shopping", "art": "museum", "family": "theme_park"}


def pick(rng, weighted):
    r, acc = rng.random(), 0.0
    for value, w in weighted:
        acc += w
        if r < acc:
            return value
    return weighted[-1][0]


def josa(word, with_final, without_final):
    code = ord(word[-1]) - 0xAC00
    return with_final if 0 <= code <= 11171 and code % 28 else without_final


def interest_sentence(rng, city, table):
    c = table[city]
    picks = rng.sample(c["sights"], 2) if rng.random() < .5 else [rng.choice(c["sights"]), rng.choice(c["foods"])]
    a, b = picks
    style = rng.choice(["{a}{j} {b} 위주로 보고 싶어요.", "{a}{j} {b}{t} 꼭 넣어 주세요.", "{a} 가 보고 {b}도 먹고 싶어요."])
    if style.startswith("{a} 가 보고") and b in c["sights"]:
        style = "{a}{j} {b} 둘 다 가 보고 싶어요."
    return style.format(a=a, b=b, j=josa(a, "과", "와"), t=josa(b, "은", "는")), picks


def companion_detail(rng, companion):
    if companion == "family":
        n = rng.choice([1, 2])
        ages = sorted(rng.sample(range(3, 13), n))
        return 2 + n, "동행: 아이 " + ", ".join(f"{a}살" for a in ages), {"kidAges": ages}
    if companion == "parents":
        return rng.choice([2, 3, 4]), "동행: 부모님 60대", {"elderly": True}
    if companion == "friends":
        return rng.choice([2, 3, 4]), None, {}
    return (1 if companion == "solo" else 2), None, {}


def build(rng, idx, today, overseas=False):
    table = OVERSEAS_CITIES if overseas else CITIES
    multi = rng.random() < .06
    nights = pick(rng, NIGHTS)
    if multi:
        nights = max(nights, 3)
        cities = list(rng.choice(OVERSEAS_MULTI if overseas else MULTI))
    else:
        cities = [pick(rng, OVERSEAS_WEIGHTS) if overseas else rng.choice(list(CITIES))]
    city = cities[0]
    companion = pick(rng, COMPANIONS)
    party, companion_note, companion_expect = companion_detail(rng, companion)

    pace_w = [("normal", .5), ("relaxed", .3), ("tight", .2)]
    if companion in ("parents", "family"):
        pace_w = [("normal", .5), ("relaxed", .4), ("tight", .1)]
    pace = pick(rng, pace_w)

    walk_ok = all(table[c]["walkable"] for c in cities) and not multi
    car_share = (.4 if not walk_ok else .05) if overseas else .4
    mode = pick(rng, [("car", car_share), ("transit", .35), ("walk", .25 if walk_ok else 0.0)])
    if mode == "walk" and not walk_ok:
        mode = "transit"
    transport = {"car": "car", "walk": "walk"}.get(mode, "any")

    start = today + dt.timedelta(days=rng.randint(14, 360))
    end = start + dt.timedelta(days=nights)

    fragments, expect = [], {"cities": cities, "companion": companion, **companion_expect}
    sentence, wanted = interest_sentence(rng, city, table)
    fragments.append("원하는 여행: " + sentence)
    expect["wanted"] = wanted
    if multi:
        split = rng.randint(1, nights)
        fragments.append(f"일정: {cities[0]} {split}일, {cities[1]} {nights + 1 - split}일")
        expect["cityDays"] = {cities[0]: split, cities[1]: nights + 1 - split}
    if companion_note:
        fragments.append(companion_note)
    if mode == "transit":
        fragments.append("이동: 대중교통(차 없음)")
    if companion == "parents" and rng.random() < .5:
        fragments.append("걷기: 오래 걷기 힘들어요")
        expect["lowWalking"] = True

    restrictions = []
    if rng.random() < .15:
        text, codes = rng.choice(FOOD_AVOID)
        fragments.append("못 먹거나 피할 음식: " + text)
        restrictions += codes
        expect["avoidFood"] = text
    if rng.random() < .08:
        text, codes = rng.choice(ACTIVITY_AVOID)
        fragments.append("피하고 싶은 활동: " + text)
        restrictions += codes
        expect["avoidActivity"] = text

    required = []
    c = table[city]
    if rng.random() < .15 and (c["night"] or c["dawn"]):
        options = [(t, "evening") for t in c["night"]] + [(t, "morning") for t in c["dawn"]]
        text, timing = rng.choice(options)
        required.append({"text": text, "areaKeyword": text.split()[0], "timing": timing})
        expect["required"] = {"text": text, "timing": timing}

    request = {
        "mode": "AUTO",
        "destination": ", ".join(cities),
        "themes": sorted(set(rng.sample(c["themes"], 2))),
        "categories": [],
        "pace": pace,
        "transportPref": transport,
        "budget": party * nights * rng.choice([150000, 250000, 400000]),
        "startDate": start.isoformat(),
        "endDate": end.isoformat(),
        "companion": companion,
        "partySize": party,
    }
    request["categories"] = sorted({"attraction", "restaurant", "cafe"}
                                   | {THEME_CATEGORIES[t] for t in request["themes"] if t in THEME_CATEGORIES})
    # 해외는 비행기 시각을 거의 늘 안다.
    if rng.random() < (.8 if overseas else .35):
        request["arrivalTime"] = rng.choice(ARRIVALS)
    if rng.random() < (.8 if overseas else .35):
        request["departureTime"] = rng.choice(DEPARTURES)
        if nights == 1 and request.get("arrivalTime", "00:00") >= "18:00" and request["departureTime"] <= "13:00":
            request["departureTime"] = "17:00"
    if required:
        request["requiredExperiences"] = required
    if restrictions:
        request["restrictions"] = restrictions
    request["description"] = SEPARATOR.join(fragments)[:500]
    expect["mode"] = mode
    return request, expect


def split_of(case_id, seed):
    h = int(hashlib.sha256(f"{seed}:{case_id}".encode()).hexdigest(), 16) % 1000
    return "holdout" if h < 150 else "dev" if h < 250 else "bulk"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--count", type=int, default=2000)
    ap.add_argument("--seed", type=int, default=20261117)
    ap.add_argument("--today", default="2026-11-17")
    ap.add_argument("--out", required=True)
    ap.add_argument("--scope", choices=["domestic", "overseas"], default="domestic")
    args = ap.parse_args()
    overseas = args.scope == "overseas"
    rng = random.Random(args.seed)
    today = dt.date.fromisoformat(args.today)
    with open(args.out, "w", encoding="utf-8") as f:
        for i in range(1, args.count + 1):
            case_id = f"{'o' if overseas else 'g'}{i:04d}"
            request, expect = build(rng, i, today, overseas)
            f.write(json.dumps({"id": case_id, "split": split_of(case_id, args.seed),
                                "request": request, "expect": expect}, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
