package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.place.entity.Place;

import java.util.List;
import java.util.Map;

/**
 * 해외 장소 보강 규칙. 한국 장소의 프롬프트·해석은 바꾸지 않고, 해외 행에만 국가·주소 문맥, 한국어 표시 이름,
 * 현지 통화 입장료를 더한다.
 */
final class OverseasEnrichment {

    private OverseasEnrichment() {
    }


    /**
     * @param minPaid 유료일 때 최소 금액. 이보다 작으면 단위를 줄여 쓴 값이다(베트남 동을 천 단위로 적은 40 등)
     * @param max     명소 입장료로 볼 최대 금액. 넘으면 원화 등 다른 통화로 적은 값이다
     */
    record Currency(String code, String label, int minPaid, int max) {
        boolean plausible(int amount) {
            return amount == 0 || (amount >= minPaid && amount <= max);
        }
    }

    record Country(String nameKo, String nameEn, Currency currency) {}

    /** 국가 코드 → 국가명·현지 통화. 여기 없는 국가는 한국 규칙을 탄다. */
    static final Map<String, Country> COUNTRIES = Map.of(
            "JP", new Country("일본", "japan", new Currency("JPY", "엔", 10, 30_000)),
            "CN", new Country("중국", "china", new Currency("CNY", "위안", 1, 2_000)),
            "TW", new Country("대만", "taiwan", new Currency("TWD", "대만달러", 10, 5_000)),
            "VN", new Country("베트남", "vietnam", new Currency("VND", "동", 1_000, 3_000_000)));

    static final int MAX_NAME_KO_LENGTH = 60;

    private static final class DestinationsHolder {
        static final Destinations INSTANCE = Destinations.fromClasspath();
    }

    static boolean isOverseas(String country) {
        return country != null && COUNTRIES.containsKey(country);
    }

    static Currency currencyOf(String country) {
        return COUNTRIES.get(country).currency();
    }

    /**
     * 식당 태그에서 지울 지역명. 그 장소의 나라·여행지 이름만이다. 다른 나라 이름은 음식 태그다
     * (오사카 쌀국수집의 "베트남", 상하이 술집의 "칭다오").
     */
    static java.util.Set<String> ownRegionNames(Place place) {
        java.util.Set<String> names = new java.util.HashSet<>();
        Country country = COUNTRIES.get(place.getCountry());
        if (country != null) {
            names.add(country.nameKo());
            names.add(country.nameEn());
        }
        DestinationsHolder.INSTANCE.find(place.getRegion()).ifPresent(d -> {
            names.add(d.nameKo().replaceAll("\\s+", ""));
            names.add(d.name().replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT));
        });
        return names;
    }

    /** 프롬프트의 장소 정보 줄(국가·여행지·주소). */
    static String contextLines(Place place) {
        StringBuilder sb = new StringBuilder();
        sb.append("- 국가: ").append(COUNTRIES.get(place.getCountry()).nameKo()).append("\n");
        if (place.getRegion() != null) {
            String label = DestinationsHolder.INSTANCE.find(place.getRegion())
                    .map(Destination::nameKo).map(ko -> ko + " (" + place.getRegion() + ")")
                    .orElse(place.getRegion());
            sb.append("- 여행지: ").append(label).append("\n");
        }
        if (place.getAddress() != null && !place.getAddress().isBlank()) {
            sb.append("- 주소: ").append(place.getAddress().strip()).append("\n");
        }
        return sb.toString();
    }

    /** name_ko를 물을 때 붙이는 규칙. 이미 이름이 있으면 묻지 않는다. */
    static String nameKoRule() {
        return """
                name_ko는 한국인 여행자가 알아볼 한국어 이름이다.
                - 한국에서 널리 쓰는 이름이 있으면 그 이름을 쓴다(八达岭长城 → 바다링 만리장성, 颐和园 → 이화원, \
                外滩 → 와이탄, 鼎泰豐 → 딘타이펑).
                - 없으면 고유명사는 국립국어원 외래어 표기법에 따라 현지 발음대로 적고, 시장·해변·사원·신사·공원·박물관·역 같은 \
                일반 명사는 한국어로 옮긴다(士林夜市 → 스린 야시장, Bãi biển Mỹ Khê → 미케 해변, 浅草寺 → 센소지).
                - 지명·섬·산 이름은 뜻으로 옮기지 않고 발음대로 적는다(嵐山 → 아라시야마).
                - 중국어 고유명사는 한 이름 안에서 중국어 발음과 한국 한자음을 섞지 않는다(青春足球场 → 칭춘 축구장).
                - 원문에 영어 상호와 한자 이름이 함께 있으면 영어 상호를 발음대로 적고, 한자를 한국 한자음으로 읽지 않는다.
                - 지점명은 원문에 적힌 지점만 "○○점"으로 붙인다(○○ 本店 → ○○ 본점, ○○ 梅田店 → ○○ 우메다점).
                - 원문 이름만 옮긴다. 원문에 없는 상호·지명을 덧붙이거나, 다른 가게·시설(같은 단지 안 다른 시설 포함) 이름으로 \
                바꾸지 않는다.
                - 이름이 이미 한국어면 그대로 쓴다. 확실하지 않아도 발음 표기는 적는다.
                - 한국어 이름만 적는다. 원문·괄호 병기·설명을 붙이지 않는다.""";
    }

    /** traveler_fame 규칙. 관광지·식당 프롬프트가 같은 기준을 쓴다. */
    static String fameRule() {
        return """
                traveler_fame은 여행자 사이에서 알려진 정도다.
                - 2: 이 도시 여행 가이드북의 주요 명소·맛집, 미쉐린 스타 식당, 이 도시에 가는 여행자 대부분이 들르는 곳. \
                한 도시에 수십 곳 정도다(자금성, 센소지, 스린 야시장, 니시키 시장, 하노이 문묘, 딘타이펑 신이 본점).
                - 1: 현지에서 이름난 곳(오래된 노포, 지역 대표 맛집·명소, 여행 후기에 자주 나오는 곳).
                - 0: 그 밖의 평범한 곳. 체인점은 본점·대표 지점만 1 이상이다.
                - 모르는 장소면 0이다.""";
    }

    /** 0~2 정수만 받는다. 그 밖은 모름(null)이다. */
    static Short fame(JsonNode node) {
        if (node == null || !node.isIntegralNumber()) return null;
        int v = node.asInt();
        return v >= 0 && v <= 2 ? (short) v : null;
    }

    /** 모델이 이름 대신 돌려주는 말. 한 번 저장되면 다시 묻지 않아 그대로 남는다. */
    private static final java.util.Set<String> NON_NAMES = java.util.Set.of(
            "한국어이름", "한국어표시이름", "없음", "미상", "알수없음", "모름", "해당없음", "이름없음", "null", "n/a");

    private static final java.util.regex.Pattern BRACKETED = java.util.regex.Pattern.compile("\\s*[(（\\[【][^)）\\]】]*[)）\\]】]");

    /**
     * 한국어 표시 이름만 받는다. 괄호 병기는 떼고, 한자·가나가 남거나 한글이 없거나 이름이 아닌 말이면 버린다.
     * 저장한 이름은 다시 묻지 않으므로 애매하면 버리고 보정표·재보강에 맡긴다.
     */
    static String nameKo(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String v = node.asText("").strip();
        if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\""))) v = v.substring(1, v.length() - 1).strip();
        v = BRACKETED.matcher(v).replaceAll("").strip();
        if (v.isEmpty() || v.length() > MAX_NAME_KO_LENGTH) return null;
        if (NON_NAMES.contains(v.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT))) return null;
        boolean hasHangul = v.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL);
        return hasHangul && !OverseasNameKeys.hasCjk(v) ? v : null;
    }

    /** 현지 통화 입장료. 숫자가 아니거나 그 통화로 있을 수 없는 금액은 버린다(0은 무료). */
    static Integer admissionLocal(JsonNode node, Currency currency) {
        if (node == null || !node.canConvertToInt()) return null;
        int v = node.asInt();
        return v >= 0 && currency.plausible(v) ? v : null;
    }

    /** 요리 이름(한국어 태그) → 음식 종류. 동선의 끼니 반복 판정은 MEAT·SEAFOOD·NOODLE·SOUP만 본다. */
    private static final Map<String, List<String>> DISH_CUISINES = Map.of(
            "NOODLE", List.of("라멘", "라면", "우동", "소바", "쌀국수", "분짜", "분보", "우육면", "탄탄면", "짬뽕", "국수", "미펀",
                    "까오러우"),
            "SEAFOOD", List.of("스시", "초밥", "사시미", "회", "해산물", "게", "새우", "굴", "장어", "카이센동"),
            "MEAT", List.of("야키니쿠", "야키토리", "규동", "돈카츠", "꼬치", "양꼬치", "베이징덕", "오리구이", "바비큐", "스테이크",
                    "꼼땀"),
            "SOUP", List.of("훠궈", "샤브샤브", "스키야키", "나베", "전골"));

    private static final List<String> DISH_ORDER = List.of("MEAT", "SEAFOOD", "NOODLE", "SOUP");

    /**
     * 요리 이름이 큰 갈래보다 먼저다(샤브샤브는 소고기여도 국물). 요리 이름이 없으면 한국 규칙
     * ({@link DiningEnrichment#cuisineOf})으로 간다. "회"·"게"처럼 한 글자는 태그 전체가 같을 때만 본다.
     */
    static String cuisineOf(List<String> tags, String category) {
        if (tags != null) {
            for (String cuisine : DISH_ORDER) {
                for (String dish : DISH_CUISINES.get(cuisine)) {
                    for (String tag : tags) {
                        if (dish.length() == 1 ? tag.equals(dish) : tag.contains(dish)) return cuisine;
                    }
                }
            }
        }
        return DiningEnrichment.cuisineOf(tags, category);
    }
}
