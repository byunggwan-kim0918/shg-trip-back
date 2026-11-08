package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.PlaceAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 장소 카테고리 분류 키워드 상수. 검색·조립·검증이 같은 판정을 보도록 한 곳에 둔다.
 */
public final class PlaceCategoryConstants {

    private PlaceCategoryConstants() {}

    /**
     * 숙소 판정 키워드. {@code "lodging"}은 {@code "lodge"}를 부분 문자열로 포함하지 않아 따로 둔다.
     * 빠지면 리프가 Lodging인 숙소가 관광 체류로 계산되고 {@link #majorCategory}와 어긋난다.
     */
    public static final Set<String> ACCOMMODATION_KEYWORDS = Set.of(
            "숙소", "호텔", "리조트", "펜션", "게스트하우스", "모텔", "에어비앤비",
            "캠핑장", "캠프",
            "hotel", "resort", "hostel", "motel", "lodge", "lodging", "accommodation", "camp"
    );

    /**
     * DB <b>카테고리 경로</b>에 쓰는 교통 판정 키워드. 카테고리는 "Travel and Transportation &gt;
     * Transport Hub &gt; Airport"처럼 정형이라 부분 문자열 매칭이 안전하다.
     * 이름 판정에 쓰면 "Sports Complex"가 "port"로 걸린다.
     */
    public static final Set<String> TRANSIT_HUB_KEYWORDS = Set.of(
            "역", "공항", "터미널", "항구", "airport", "station", "terminal", "port"
    );

    /**
     * 교통시설 이름 판정. 부분 문자열로 보면 "Sports Complex"("port")·"역사박물관"("역")이 허브가 되므로
     * 한 글자 키워드는 경계 조건을, 영문은 단어 경계를 붙인다. "항"은 관광 어항이 많아 넣지 않는다.
     */
    private static final java.util.regex.Pattern TRANSIT_FACILITY_NAME = java.util.regex.Pattern.compile(
            "공항|터미널|여객|선착장|부두|도선|카페리|페리"
            + "|[가-힣]역(?![사원전])"                       // 서울역 O / 역사박물관·역원 X
            + "|\\bairport\\b|\\bterminal\\b|\\bferry\\b|\\bpier\\b"
            + "|\\b(?:train|bus|subway|metro|railway)?\\s*station\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 여객시설임이 이름에 드러나는 신호. 관광 스텝이 될 수 없어 방문 후보에서 뺀다. 시딩·검증·동선이 같은 목록을 본다. */
    private static final java.util.regex.Pattern PASSENGER_FACILITY_NAME = java.util.regex.Pattern.compile(
            "여객|터미널|대합실|선착장|부두|도선|카페리", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * 허브 본체가 아닌 부속 시설 POI 신호. Foursquare에 "Immigration Check"·"주차장" 같은 하위 POI가
     * 공항과 같은 카테고리로 섞여 있어, 거르지 않으면 일정 첫 스텝이 출입국심사대가 된다.
     */
    private static final Set<String> HUB_SUB_FACILITY_KEYWORDS = Set.of(
            "immigration", "check-in", "checkin", "security", "gate", "counter",
            "baggage", "lounge", "parking", "주차", "수하물", "출입국", "심사", "탑승구"
    );

    /** 야간 방문이 부적합한 야외/주간 성격 카테고리 신호 (일몰 전 버킷에만 배치). */
    private static final Set<String> DAYTIME_OUTDOOR_KEYWORDS = Set.of(
            "beach", "golf", "trail", "hiking", "garden", "farm", "stable",
            "well", "campground", "scenic", "mountain", "island", "waterfall"
    );

    public static boolean isAccommodation(String category) {
        if (category == null) return false;
        String lower = category.toLowerCase();
        return ACCOMMODATION_KEYWORDS.stream().anyMatch(lower::contains);
    }

    public static boolean isTransitHub(String name, String category) {
        String combined = ((name != null ? name : "") + " " + (category != null ? category : "")).toLowerCase();
        return TRANSIT_HUB_KEYWORDS.stream().anyMatch(combined::contains);
    }

    /** 카테고리 오적재(마을이 Bus Station 등)가 있어 도착/출발 허브는 이름으로 한 번 더 거른다. */
    public static boolean hasTransitNameSignal(String name) {
        if (name == null) return false;
        return TRANSIT_FACILITY_NAME.matcher(name).find();
    }

    /** 시딩({@code TourApiSeeder})과 동선 보충이 같은 기준을 보도록 여기 한 곳에 둔다. */
    public static boolean isPassengerFacility(String name) {
        if (name == null) return false;
        return PASSENGER_FACILITY_NAME.matcher(name).find();
    }

    public static boolean isHubSubFacility(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return HUB_SUB_FACILITY_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /** 대분류는 DINING이지만 식사 슬롯에 부적합해 scheduleDay가 저녁 활동으로 다룬다. */
    public static boolean isBar(String category) {
        if (category == null) return false;
        String lower = category.toLowerCase();
        // 술 없는 "~bar"(주스·샐러드·스낵 바)는 endsWith("bar")에 걸리지만 술집이 아니다. 양조장류는 "bar"가 없어도 술집이다.
        if (NON_ALCOHOL_BARS.stream().anyMatch(lower::contains)) return false;
        if (ALCOHOL_VENUES.stream().anyMatch(lower::contains)) return true;
        return lower.contains("> bar") || lower.contains("nightlife")
                || lower.endsWith("bar") || lower.contains("pub");
    }

    /** 총칭 "Restaurant"인데 보강 태그가 술집인 곳(호프·포차)을 잡는다. */
    public static boolean isBar(String category, List<String> tags) {
        if (isBar(category)) return true;
        return tags != null && tags.stream().anyMatch(t -> t != null && BAR_TAGS.stream().anyMatch(t::contains));
    }

    private static final List<String> NON_ALCOHOL_BARS = List.of(
            "juice bar", "salad bar", "snack bar", "oxygen bar", "smoothie");
    private static final List<String> ALCOHOL_VENUES = List.of(
            "brewery", "winery", "distillery", "meadery", "cidery", "izakaya", "sake bar");
    private static final List<String> BAR_TAGS = List.of("술집", "호프", "주점", "포차", "이자카야");

    /**
     * 체류시간 휴리스틱(분). {@code recommended_duration_minutes}가 없을 때의 폴백이고 값은 제주 유형별 중앙값이다.
     * TourAPI 적재분은 카테고리가 "Tourist Attraction" 하나라 이름도 본다.
     */
    public static int heuristicVisitMinutes(String name, String category) {
        String lower = category == null ? "" : category.toLowerCase();
        if (lower.contains("hiking") || lower.contains("trail") || lower.contains("mountain")) return 150;
        if (lower.contains("market")) return 60;

        if (name != null && !name.isBlank()) {
            Integer byName = NAME_VISIT_MINUTES.get(subType(name, category, List.of(), null));
            if (byName != null) return byName;
        }
        return 90;
    }

    /**
     * 세부 유형별 체류시간(분). 제주 유형별 중앙값이고 기본값 90분과 같은 유형은 넣지 않는다.
     * "hill"은 오름(120)과 한라산(180)을 함께 담아 보수적으로 120을 쓴다.
     */
    private static final Map<String, Integer> NAME_VISIT_MINUTES = Map.of(
            "hill", 120,
            "waterfall", 120,
            "viewpoint", 120);

    /** 보강 속성의 숙소 유형이 있으면 게스트하우스·호스텔만 다인실로 본다. 없으면 이름·카테고리·태그 판정. */
    public static boolean isDormLodging(PlaceAttributes attributes, String name, String category, List<String> tags) {
        if (attributes != null && attributes.lodgingType() != null) {
            return "HOSTEL".equals(attributes.lodgingType()) || "GUESTHOUSE".equals(attributes.lodgingType());
        }
        return isDormLodging(name, category, tags);
    }

    /**
     * 다인실 중심 숙소(게스트하우스·호스텔)인지. 커플·가족 여행에서 숙소 후보로 쓰지 않는다.
     * enrich가 호텔에도 "게스트하우스" 태그를 붙이므로 태그는 카테고리가 총칭("Lodging")일 때만 본다.
     */
    public static boolean isDormLodging(String name, String category, List<String> tags) {
        String leaf = leafCategory(category);
        if (leaf.contains("hostel")) return true;
        if (name != null && containsAny(name.toLowerCase(), DORM_KEYWORDS)) return true;
        if (!"lodging".equals(leaf)) return false;
        return tags != null && tags.stream()
                .anyMatch(t -> t != null && containsAny(t.toLowerCase(), DORM_KEYWORDS));
    }

    private static boolean containsAny(String lower, List<String> keywords) {
        return keywords.stream().anyMatch(lower::contains);
    }

    private static final List<String> DORM_KEYWORDS = List.of(
            "게스트하우스", "호스텔", "백패커", "도미토리", "hostel", "guesthouse", "guest house",
            "backpacker", "dormitory");

    /**
     * 보강 속성의 등산 강도가 있으면 LONG만 등산으로 본다. 키워드는 태그의 "등산"만으로 30분이면 오르는
     * 성산일출봉까지 잡고, 이름에 키워드가 없는 관악산·북악산은 놓친다.
     */
    public static boolean isHikingPlace(PlaceAttributes attributes, String name, List<String> tags) {
        if (attributes != null && attributes.hikeLevel() != null) return "LONG".equals(attributes.hikeLevel());
        return isHikingPlace(name, tags);
    }

    /** 30분이면 오르는 곳도 걸리므로 단독으로 쓰지 않고 체류시간과 함께 긴 등산 판정에 쓴다. */
    public static boolean isHikingPlace(String name, List<String> tags) {
        if (name != null && containsHikingKeyword(name)) return true;
        return tags != null && tags.stream().anyMatch(PlaceCategoryConstants::containsHikingKeyword);
    }

    private static boolean containsHikingKeyword(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        return HIKING_KEYWORDS.stream().anyMatch(lower::contains);
    }

    private static final List<String> HIKING_KEYWORDS = List.of(
            "등산", "등반", "트레킹", "하이킹", "hiking", "trekking");

    public static boolean isDaytimeOutdoor(String category) {
        if (category == null) return false;
        String lower = category.toLowerCase();
        if (!"ATTRACTION".equals(majorCategory(category))) return false;
        return DAYTIME_OUTDOOR_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /**
     * DB 카테고리(Foursquare 계층 경로, 예: "Dining and Drinking > Restaurant > ...")를
     * 5개 대분류로 매핑한다.
     */
    public static String majorCategory(String category) {
        if (category == null) return "OTHER";
        String lower = category.toLowerCase();
        if (lower.contains("lodging")) return "LODGING";
        // "Cafeteria"(구내식당)는 "cafe"를 포함하지만 카페가 아니다.
        if (lower.contains("dining and drinking") && !lower.contains("cafeteria")
                && (lower.contains("cafe") || lower.contains("coffee"))) return "CAFE";
        if (lower.contains("dining and drinking") || lower.contains("restaurant")) return "DINING";
        if (lower.contains("landmarks") || lower.contains("arts and entertainment")
                || lower.contains("sports and recreation") || lower.contains("outdoors")) return "ATTRACTION";
        // 신사·사찰·성당은 "Community and Government" 아래라 이 분기가 없으면 OTHER로 떨어진다.
        if (lower.contains("spiritual center")) return "ATTRACTION";
        // Retail 전체를 관광지로 보면 편의점·약국 같은 상점이 관광 후보를 채운다.
        if (lower.contains("retail")) return isTravelRetail(lower) ? "ATTRACTION" : "OTHER";
        if (lower.contains("market") || lower.contains("shopping")) return "ATTRACTION";
        if (isTransitHubCategory(lower)) return "TRANSIT_HUB";
        return "OTHER";
    }

    /** 여행자가 일부러 찾아가는 소매(시장·몰·백화점·기념품·면세·아울렛). 슈퍼·편의점·일반 매장은 아니다. */
    static boolean isTravelRetail(String lowerCategory) {
        if (NON_TRAVEL_RETAIL_PATTERNS.stream().anyMatch(lowerCategory::contains)) return false;
        return TRAVEL_RETAIL_PATTERNS.stream().anyMatch(lowerCategory::contains);
    }

    private static final List<String> TRAVEL_RETAIL_PATTERNS = List.of(
            "market", "shopping mall", "shopping plaza", "department store", "souvenir", "duty-free",
            "duty free", "outlet");

    /** "market"이 들어가지만 여행 시장이 아닌 것(슈퍼마켓 등). */
    private static final List<String> NON_TRAVEL_RETAIL_PATTERNS = List.of(
            "supermarket", "minimarket", "convenience store", "grocery");

    /**
     * 공항·철도역·버스터미널·여객선만 허브로 본다. {@link #TRANSIT_HUB_KEYWORDS}를 부분 일치시키면
     * "transportation" 안의 "port"가 걸려 도로·정류장까지 허브가 된다.
     */
    private static boolean isTransitHubCategory(String lower) {
        if (lower.contains("bus stop") || lower.contains("taxi stand") || lower.contains("rental car")) return false;
        return lower.contains("airport") || lower.contains("rail station") || lower.contains("train station")
                || lower.contains("metro station") || lower.contains("bus station") || lower.contains("terminal")
                || lower.contains("boat or ferry") || lower.contains("ferry") || lower.contains("pier");
    }

    // ── 사용자 입력(마법사) ↔ 검색 슬롯 매핑 ─────────────────────────────────────
    // 여행지별 특수 규칙은 두지 않는다. 판단은 이 표와 DB 데이터로만 한다.

    /** 검색 슬롯 역할. RouteOptimizer가 식사·숙소·허브를 채우는 데 필요한 고정 구조. */
    public enum SearchRole { TRANSPORTATION, RESTAURANT, CAFE, ACCOMMODATION, ATTRACTION }

    /**
     * 마법사 카테고리 id → 검색 문구. 여러 카테고리를 한 문장에 뭉치면 임베딩이 희석되므로
     * 관광 하위유형은 ATTRACTION 슬롯에서 유형별로 쿼리를 나눈다.
     */
    private static final Map<String, String> USER_CATEGORY_QUERY = Map.ofEntries(
            Map.entry("attraction", "대표 관광지 명소"),
            Map.entry("restaurant", "맛집 현지 음식 식당"),
            Map.entry("cafe", "카페 디저트 베이커리"),
            Map.entry("viewpoint", "전망대 뷰포인트 파노라마 전망"),
            Map.entry("beach", "해변 해수욕장 바닷가"),
            Map.entry("market", "전통시장 재래시장 로컬푸드"),
            Map.entry("trail", "산책로 둘레길 트레킹 코스"),
            Map.entry("accommodation", "호텔 리조트 게스트하우스 숙소"),
            Map.entry("experience", "체험 액티비티 레저"),
            Map.entry("shopping", "쇼핑 백화점 쇼핑거리 편집숍"),
            Map.entry("nightlife", "나이트라이프 바 펍 라운지"),
            Map.entry("nature", "자연 공원 숲 폭포 정원"),
            Map.entry("museum", "박물관 미술관 전시관"),
            Map.entry("theme_park", "테마파크 놀이공원"),
            Map.entry("spa", "스파 온천 웰니스 찜질"),
            Map.entry("temple", "사찰 절 성당 종교 유적"),
            Map.entry("street_food", "길거리 음식 노점 먹거리 골목")
    );

    /** 관광(ATTRACTION) 슬롯에서 전용 쿼리로 분리할 마법사 카테고리 id. */
    private static final Set<String> ATTRACTION_SUBTYPES = Set.of(
            "attraction", "viewpoint", "beach", "market", "trail", "experience", "shopping",
            "nightlife", "nature", "museum", "theme_park", "spa", "temple", "street_food"
    );

    /**
     * 마법사 테마 id → 관광 슬롯 검색 문구. 가족·알뜰·반려동물처럼 여행 방식인 테마는 무관한 장소를 끌어와 넣지 않는다.
     * food는 관광 슬롯이 Dining을 배제해 항상 0건이라 넣지 않는다.
     */
    private static final Map<String, String> THEME_QUERY = Map.ofEntries(
            Map.entry("healing", "힐링 조용한 쉼터"),
            Map.entry("activity", "액티비티 체험 레저"),
            Map.entry("culture", "문화 유적 역사"),
            Map.entry("nature", "자연 풍경 경치"),
            Map.entry("shopping", "쇼핑 거리"),
            Map.entry("adventure", "모험 탐험 오지"),
            Map.entry("romance", "로맨틱 분위기 좋은 데이트"),
            Map.entry("luxury", "고급 프리미엄"),
            Map.entry("photo", "사진 명소 인생샷 포토스팟"),
            Map.entry("walking", "도보 산책 골목"),
            Map.entry("ocean", "바다 해안 오션뷰"),
            Map.entry("mountain", "산 등산 트레킹"),
            Map.entry("nightview", "야경 야간 명소 일몰"),
            Map.entry("local", "로컬 현지인 동네"),
            Map.entry("art", "예술 전시 갤러리"),
            Map.entry("festival", "축제 이벤트 행사")
    );

    /**
     * 한 끼 식사가 아닌 Dining and Drinking 하위 유형. 검색 슬롯(SQL 배제)과 동선 층(식사 슬롯 배정·보충)이
     * 같은 목록을 봐야 한다. 한쪽만 거르면 최종 패스가 디저트를 저녁으로 넣는다.
     */
    private static final List<String> NON_MEAL_DINING_PATTERNS = List.of(
            "> bar", "bakery", "dessert", "ice cream", "donut", "bagel", "smoothie", "juice",
            "brewery", "winery", "snack place", "food court", "food stand", "food truck",
            "cafe", "coffee", "patisserie", "cupcake", "tea room");

    private static List<String> restaurantSlotExclusions() {
        List<String> patterns = new java.util.ArrayList<>(
                List.of("lodging", "travel and transportation", "landmarks"));
        patterns.addAll(NON_MEAL_DINING_PATTERNS);
        return List.copyOf(patterns);
    }

    public static boolean isMealPlace(String name, String category) {
        return isMealPlace(name, category, null);
    }

    /**
     * @param tags enrich 배치가 붙인 한국어 태그. 이름·카테고리 어디에도 신호가 없는 곳을
     *             걸러내는 가장 정확한 단서다. null이면 보지 않는다.
     */
    public static boolean isMealPlace(String name, String category, List<String> tags) {
        if (!"DINING".equals(majorCategory(category))) return false;
        if (isAggregatePoi(name, category)) return false;
        String lower = category == null ? "" : category.toLowerCase();
        if (NON_MEAL_DINING_PATTERNS.stream().anyMatch(lower::contains)) return false;
        if (tags != null && tags.stream().anyMatch(PlaceCategoryConstants::isNonMealByName)) return false;
        if (isBar(category, tags)) return false;
        // TourAPI는 음식점을 전부 "Dining and Drinking > Restaurant"로 적재해 카테고리만 보면 베이커리가 식사가 된다.
        return !isNonMealByName(name);
    }

    /**
     * 카페·디저트처럼 짧게 쉬어 가는 곳인지. 식당으로 적재됐지만 한 끼가 아닌 곳(베이커리·디저트)도 포함한다.
     * 태그 규칙은 LLM이 이름만 보고 붙인 태그라 틀릴 수 있어, 적재 단계 분류가 없을 때만 의미가 있다.
     */
    public static boolean isCafeLike(String name, String category, List<String> tags) {
        if (name != null && CONVENIENCE_NAMES.stream().anyMatch(name::contains)) return false;
        String major = majorCategory(category);
        if ("CAFE".equals(major)) return true;
        if (!"DINING".equals(major) || isMealPlace(name, category, tags)) return false;
        if (isBar(category, tags) || isAggregatePoi(name, category)) return false;
        return !looksLikeRestaurantByTags(tags);
    }

    /** 카페 자리로 쓰면 안 되는 편의점·휴게소 이름 신호(FSQ Snack Place 혼입). */
    private static final List<String> CONVENIENCE_NAMES = List.of(
            "휴게소", "편의점", "7~11", "세븐일레븐", "GS25", "CU ", "이마트24", "미니스톱", "7-Eleven", "FamilyMart",
            "ファミリーマート", "ローソン", "Lawson");

    private static boolean looksLikeRestaurantByTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return false;
        boolean restaurant = tags.stream().anyMatch(t -> t != null && containsAny(t, RESTAURANT_TAG_KEYWORDS));
        boolean drink = tags.stream().anyMatch(t -> t != null && containsAny(t, CAFE_TAG_KEYWORDS));
        return restaurant && !drink;
    }

    private static final List<String> RESTAURANT_TAG_KEYWORDS = List.of(
            "한식", "레스토랑", "다이닝", "음식점", "식당", "해산물");
    private static final List<String> CAFE_TAG_KEYWORDS = List.of(
            "커피", "디저트", "베이커리", "제과", "빵");

    /** enrich 배치가 식당·술집 태그를 거를 때 {@link #isMealPlace}·{@link #isCafeLike}와 같은 목록을 보게 연다. */
    public static boolean isNonMealTag(String tag) {
        if (tag == null) return false;
        return isNonMealByName(tag) || containsAny(tag, CAFE_TAG_KEYWORDS);
    }

    /** 이 태그가 붙으면 {@link #isCafeLike}가 식당으로 읽는다. enrich 배치가 카페류 태그에서 걸러낼 때 쓴다. */
    public static boolean isRestaurantTag(String tag) {
        return tag != null && containsAny(tag, RESTAURANT_TAG_KEYWORDS);
    }

    private static boolean isNonMealByName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return NON_MEAL_NAME_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /**
     * 비식사 이름 신호. 식당 상호에 섞이지 않는 어휘만 담는다. "밥"·"국수"는 카페 이름에도 쓰여 넣지 않는다.
     */
    private static final List<String> NON_MEAL_NAME_KEYWORDS = List.of(
            "카페", "베이커리", "디저트", "제과", "빵집", "아이스크림", "빙수", "커피",
            "cafe", "café", "bakery", "dessert", "coffee", "patisserie");

    /**
     * 슬롯별로 명백히 틀린 대분류만 SQL에서 배제한다. DB 오적재가 흔해 의미 판별은 벡터 쿼리에 맡긴다.
     * 값은 SQL ILIKE 패턴 조각(소문자 부분 문자열).
     */
    private static final Map<SearchRole, List<String>> SLOT_EXCLUDED_PATTERNS = Map.of(
            SearchRole.CAFE, List.of("lodging", "travel and transportation", "landmarks"),
            // 바·간식류는 한 끼로 배치되지 않으면서 식사 슬롯 자리를 차지해 실제 식당을 밀어낸다.
            SearchRole.RESTAURANT, restaurantSlotExclusions(),
            SearchRole.ACCOMMODATION, List.of("dining and drinking", "landmarks", "arts and entertainment"),
            SearchRole.ATTRACTION, List.of("lodging", "transport hub", "dining and drinking"),
            SearchRole.TRANSPORTATION, List.of("dining and drinking", "lodging")
    );

    /**
     * 관광 슬롯이지만 대상이 Dining and Drinking 아래에 있는 사용자 카테고리(나이트라이프=Bar 등).
     * 관광 슬롯의 기본 배제에 걸려 후보가 나올 수 없으므로 이 카테고리에서만 배제를 푼다.
     */
    private static final Set<String> DINING_BASED_ATTRACTION_SUBTYPES = Set.of("nightlife", "street_food");

    /**
     * 슬롯별 최소 cosine 유사도. 없으면 데이터가 부족할 때 limit를 채우려고 무관한 장소를 끌어온다.
     * 후보가 줄면 FallbackDecider가 COMPACT/FALLBACK으로 이어받는다.
     * 768차원 halfvec 기준이다. 같은 후보의 유사도가 1536차원보다 대분류별 중앙값 0.013~0.027 높다.
     */
    private static final Map<SearchRole, Double> SLOT_MIN_SIMILARITY = Map.of(
            SearchRole.TRANSPORTATION, 0.32,
            SearchRole.RESTAURANT, 0.35,
            SearchRole.CAFE, 0.39,
            SearchRole.ACCOMMODATION, 0.36,
            SearchRole.ATTRACTION, 0.34
    );

    private static final Map<SearchRole, String> SLOT_MAJOR_CATEGORY = Map.of(
            SearchRole.TRANSPORTATION, "TRANSIT_HUB",
            SearchRole.RESTAURANT, "DINING",
            SearchRole.CAFE, "CAFE",
            SearchRole.ACCOMMODATION, "LODGING",
            SearchRole.ATTRACTION, "ATTRACTION"
    );

    /**
     * 경유형(드라이브 코스·해안도로·둘레길 등) 신호. 지나가는 구간이라 방문 스텝으로 반복되면 일정이
     * 무의미해지므로, [경유]로 마킹해 프롬프트가 전체 최대 1개로 제한하게 한다.
     */
    private static final Set<String> VIA_ROUTE_KEYWORDS = Set.of(
            "해안도로", "드라이브", "드라이브코스", "둘레길", "올레길", "산책로", "가로수길",
            "road", "drive", "scenic route", "scenic drive", "trail route", "boulevard", "highway"
    );

    /** 미정의 id면 id 자체를 그대로 쓴다. */
    public static String userCategoryQuery(String categoryId) {
        if (categoryId == null || categoryId.isBlank()) return "";
        return USER_CATEGORY_QUERY.getOrDefault(categoryId.trim().toLowerCase(), categoryId.trim());
    }

    public static boolean isAttractionSubtype(String categoryId) {
        return categoryId != null && ATTRACTION_SUBTYPES.contains(categoryId.trim().toLowerCase());
    }

    /** 검색 의미가 없는 테마면 null이고, 호출자는 쿼리를 만들지 않는다. */
    public static String themeQuery(String themeId) {
        if (themeId == null || themeId.isBlank()) return null;
        return THEME_QUERY.get(themeId.trim().toLowerCase());
    }

    public static List<String> excludedCategoryPatterns(SearchRole role) {
        return SLOT_EXCLUDED_PATTERNS.getOrDefault(role, List.of());
    }

    public static List<String> excludedCategoryPatterns(SearchRole role, String categoryId) {
        List<String> base = excludedCategoryPatterns(role);
        if (role != SearchRole.ATTRACTION || categoryId == null) return base;
        if (!DINING_BASED_ATTRACTION_SUBTYPES.contains(categoryId.trim().toLowerCase())) return base;
        return base.stream().filter(p -> !"dining and drinking".equals(p)).toList();
    }

    public static double minSimilarity(SearchRole role) {
        return SLOT_MIN_SIMILARITY.getOrDefault(role, 0.30);
    }

    public static String slotMajorCategory(SearchRole role) {
        return SLOT_MAJOR_CATEGORY.get(role);
    }

    /**
     * 관광지는 보강 속성의 경유형을 따른다. 키워드는 태그 하나로 일반 명소까지 경유형으로 잡는다.
     * 공항·역·숙소는 모델이 PASS_THROUGH를 붙여도 경유형 상한으로 잘리면 안 되므로 키워드 판정을 유지한다.
     */
    public static boolean isViaRoute(PlaceAttributes attributes, String name, String category, List<String> tags) {
        if (attributes != null && attributes.visitStyle() != null && "ATTRACTION".equals(majorCategory(category))) {
            return "PASS_THROUGH".equals(attributes.visitStyle());
        }
        return isViaRoute(name, category, tags);
    }

    public static boolean isViaRoute(String name, String category, List<String> tags) {
        StringBuilder sb = new StringBuilder();
        if (name != null) sb.append(name).append(' ');
        if (category != null) sb.append(category).append(' ');
        if (tags != null) tags.forEach(t -> sb.append(t).append(' '));
        String combined = sb.toString().toLowerCase();
        return VIA_ROUTE_KEYWORDS.stream().anyMatch(combined::contains);
    }

    /**
     * 주소에서 세부지역(시/군/구 단위) 토큰을 추출한다. region 컬럼이 상위 도시명뿐이라 Sonnet이 day를
     * 지리적으로 묶을 근거가 필요하다. 판단이 서지 않으면 null이고 호출부가 region을 유지한다.
     * <ul>
     *   <li>한국식: 공백 구분 토큰 중 시/군/구/읍/면으로 끝나는 첫 토큰(광역시·도 접미사는 제외)</li>
     *   <li>쉼표 구분(영문/일본식): 앞에서 두 번째 토큰이 있으면 그것(도시 단위가 오는 자리)</li>
     * </ul>
     */
    public static String extractSubRegion(String address) {
        if (address == null || address.isBlank()) return null;
        String trimmed = address.trim();

        for (String token : trimmed.split("\\s+")) {
            String t = token.trim();
            if (t.length() < 3) continue;                      // "시" 한 글자짜리 오탐 방지
            if (t.endsWith("특별자치도") || t.endsWith("광역시") || t.endsWith("특별시")) continue;
            if (t.endsWith("시") || t.endsWith("군") || t.endsWith("구")
                    || t.endsWith("읍") || t.endsWith("면")) {
                return t;
            }
        }

        if (trimmed.contains(",")) {
            String[] parts = trimmed.split(",");
            if (parts.length >= 2) {
                String second = parts[1].trim();
                if (!second.isEmpty() && second.length() <= 30) return second;
            }
        }
        return null;
    }

    /**
     * 마법사 카테고리 id → 일정에 그 유형이 등장했는지 판정할 키워드. DB category는 영어이고 name/tags는
     * 한국어인 경우가 많아 한/영을 함께 둔다. 대분류로 판정하는 유형은 여기 넣지 않는다.
     */
    private static final Map<String, Set<String>> USER_CATEGORY_MATCH_KEYWORDS = Map.ofEntries(
            Map.entry("viewpoint", Set.of("전망", "뷰포인트", "展望", "viewpoint", "observation", "observatory", "lookout", "scenic")),
            Map.entry("beach", Set.of("해변", "해수욕장", "해안", "beach", "coast")),
            Map.entry("market", Set.of("시장", "장터", "market", "bazaar")),
            Map.entry("trail", Set.of("둘레길", "산책로", "올레", "트레일", "trail", "hiking", "walkway", "promenade")),
            Map.entry("experience", Set.of("체험", "액티비티", "레저", "experience", "activity", "adventure", "recreation")),
            Map.entry("shopping", Set.of("쇼핑", "백화점", "몰", "면세", "shopping", "mall", "department store", "boutique", "store")),
            Map.entry("nightlife", Set.of("나이트", "바", "펍", "포차", "클럽", "라운지", "nightlife", "bar", "pub", "club", "lounge")),
            Map.entry("nature", Set.of("공원", "숲", "폭포", "정원", "수목원", "park", "forest", "waterfall", "garden", "nature", "mountain", "lake")),
            Map.entry("museum", Set.of("박물관", "미술관", "전시", "기념관", "museum", "gallery", "exhibit")),
            Map.entry("theme_park", Set.of("테마파크", "놀이공원", "워터파크", "theme park", "amusement", "water park")),
            Map.entry("spa", Set.of("스파", "온천", "찜질", "사우나", "웰니스", "spa", "onsen", "hot spring", "sauna", "wellness")),
            Map.entry("temple", Set.of("사찰", "사(寺)", "절", "성당", "교회", "향교", "서원", "temple", "shrine", "church", "cathedral", "monastery")),
            Map.entry("street_food", Set.of("길거리", "노점", "포장마차", "먹거리", "분식", "street food", "food stall", "snack"))
    );

    public static boolean matchesUserCategory(String categoryId, String name, String category, List<String> tags) {
        if (categoryId == null) return false;
        String id = categoryId.trim().toLowerCase();
        String major = majorCategory(category);

        switch (id) {
            case "restaurant" -> { return "DINING".equals(major); }
            case "cafe" -> { return "CAFE".equals(major); }
            case "accommodation" -> { return "LODGING".equals(major); }
            case "attraction" -> { return "ATTRACTION".equals(major); }
            default -> { /* 아래 키워드 매칭 */ }
        }

        Set<String> keywords = USER_CATEGORY_MATCH_KEYWORDS.get(id);
        if (keywords == null) return false;

        StringBuilder sb = new StringBuilder();
        if (name != null) sb.append(name).append(' ');
        if (category != null) sb.append(category).append(' ');
        if (tags != null) tags.forEach(t -> sb.append(t).append(' '));
        String combined = sb.toString().toLowerCase();
        return keywords.stream().anyMatch(combined::contains);
    }

    // ── 총칭 카테고리 세부 유형 추론 ────────────────────────────────────────────
    // 리프가 총칭이면 유형 상한·시간대 판정·대안 매칭이 전부 무력해져 이름·태그·설명으로 추론한다.
    // 여행지 고유명사는 넣지 않고 지형·시설의 일반 명사만 쓴다.

    /** 유형 정보가 없는 총칭 리프. 이 경우에만 이름·태그 기반 추론으로 넘어간다. */
    public static final Set<String> GENERIC_LEAF_TYPES = Set.of(
            "tourist attraction", "attraction", "landmark", "landmarks", "landmarks and outdoors",
            "point of interest", "outdoors", "arts and entertainment", "structure", "plaza",
            // 식당 대부분이 "Restaurant" 리프라 총칭으로 두지 않으면 유형 상한이 모든 끼니를 한 종류로 센다.
            "restaurant", "dining and drinking",
            // 한국의 "Korean Restaurant"처럼 나라 단위 리프는 종류가 아니라서, 한 종류로 세면 해물탕집과 국숫집이 같이 상한에 걸린다.
            "korean restaurant", "japanese restaurant", "asian restaurant",
            "기타", "other", "general"
    );

    /**
     * 세부 유형 → 판별 키워드(한/영/일). 앞에 오는 항목이 우선한다(더 구체적인 것 먼저).
     * 값은 소문자 부분 문자열 매칭이며, 오탐이 잦은 한 글자 접미사(산·봉·사)는 별도 규칙으로 다룬다.
     */
    private static final Map<String, List<String>> SUB_TYPE_KEYWORDS = new LinkedHashMap<>();
    static {
        // ── 음식 유형(식당 세부 분류) ──
        // 유형 상한·대안 매칭이 이 값을 공유하므로 지형 키워드보다 먼저 판정한다.
        // 여행자에게 흑돼지 구이와 한우 구이는 같은 고기 끼니라 돼지·소를 한 종류로 센다.
        SUB_TYPE_KEYWORDS.put("meat", List.of("흑돼지", "돼지", "삼겹", "오겹", "돈가", "뒷고기",
                "족발", "보쌈", "pork", "한우", "소고기", "갈비", "불고기", "스테이크",
                "야키니쿠", "beef", "steak", "焼肉"));
        SUB_TYPE_KEYWORDS.put("chicken", List.of("치킨", "닭", "chicken"));
        SUB_TYPE_KEYWORDS.put("seafood", List.of("해물", "해산물", "회", "물회", "전복", "갈치",
                "고등어", "조개", "장어", "seafood", "sushi", "초밥"));
        SUB_TYPE_KEYWORDS.put("noodle", List.of("국수", "면", "라멘", "우동", "소바", "짜장",
                "칼국수", "noodle", "ramen", "udon"));
        SUB_TYPE_KEYWORDS.put("soup", List.of("탕", "국밥", "찌개", "전골", "곰탕", "감자탕"));
        SUB_TYPE_KEYWORDS.put("bakery", List.of("베이커리", "제과", "빵", "케이크", "디저트",
                "bakery", "patisserie", "dessert"));
        SUB_TYPE_KEYWORDS.put("cafe_drink", List.of("카페", "커피", "로스터", "티하우스",
                "cafe", "coffee", "roaster"));
        // ── 지형·시설 유형 ──
        SUB_TYPE_KEYWORDS.put("waterfall", List.of("폭포", "waterfall", "falls", "滝"));
        SUB_TYPE_KEYWORDS.put("beach", List.of("해수욕장", "해변", "바닷가", "beach", "浜", "ビーチ"));
        SUB_TYPE_KEYWORDS.put("cave", List.of("동굴", "굴사", "cave", "grotto", "洞窟"));
        SUB_TYPE_KEYWORDS.put("hill", List.of("오름", "등산", "hiking", "mountain", "hill", "peak", "oreum", "岳", "山"));
        SUB_TYPE_KEYWORDS.put("trail", List.of("둘레길", "올레", "산책로", "트레일", "trail", "promenade", "walkway", "遊歩道"));
        SUB_TYPE_KEYWORDS.put("park", List.of("공원", "수목원", "정원", "park", "garden", "arboretum", "公園", "庭園"));
        SUB_TYPE_KEYWORDS.put("lake", List.of("호수", "저수지", "lake", "pond", "湖"));
        SUB_TYPE_KEYWORDS.put("island", List.of("island", "islet", "島"));
        // 항구는 총칭 리프로 들어오는 경우가 많아 유형만 부여한다. 관광 어항은 방문지로 유지된다.
        SUB_TYPE_KEYWORDS.put("harbor", List.of("항구", "포구", "harbor", "harbour", "marina", "wharf", "港"));
        SUB_TYPE_KEYWORDS.put("viewpoint", List.of("전망", "뷰포인트", "observatory", "observation", "lookout", "展望"));
        SUB_TYPE_KEYWORDS.put("tower", List.of("타워", "tower", "塔"));
        SUB_TYPE_KEYWORDS.put("temple", List.of("사찰", "암자", "temple", "shrine", "성당", "교회", "cathedral",
                "church", "monastery", "寺", "神社"));
        SUB_TYPE_KEYWORDS.put("palace", List.of("궁궐", "고궁", "palace", "fortress", "성곽", "宮", "城"));
        SUB_TYPE_KEYWORDS.put("museum", List.of("박물관", "미술관", "전시관", "기념관", "museum", "gallery",
                "exhibition", "博物館", "美術館"));
        SUB_TYPE_KEYWORDS.put("market", List.of("시장", "장터", "market", "bazaar", "市場", "商店街"));
        SUB_TYPE_KEYWORDS.put("theme_park", List.of("테마파크", "놀이공원", "워터파크", "theme park", "amusement",
                "water park", "遊園地"));
        SUB_TYPE_KEYWORDS.put("spa", List.of("온천", "스파", "찜질", "사우나", "spa", "onsen", "hot spring", "温泉"));
        SUB_TYPE_KEYWORDS.put("shopping", List.of("백화점", "면세점", "쇼핑몰", "아울렛", "mall", "department store",
                "outlet", "百貨店"));
        SUB_TYPE_KEYWORDS.put("street", List.of("골목", "먹자", "거리", "street", "alley", "district", "通り", "横丁"));
    }

    /** 이름 접미사로만 판정하는 유형. 부분 문자열로 보면 오탐이 잦은 한 글자들이다. */
    private static final Map<String, List<String>> SUB_TYPE_NAME_SUFFIX = Map.of(
            "hill", List.of("봉", "산", "악"),
            "temple", List.of("사", "암"),
            "island", List.of("섬", "도"),
            // 항공·관광은 끝 글자가 "항"이 아니라 걸리지 않는다.
            "harbor", List.of("항")
    );

    /**
     * 행정 지정 구역과 주소가 비었거나 광역만 있는 레코드를 막는다. 충전·보충·숙소 선택 경로가 공유한다.
     * 검색 단계나 Sonnet 메인 선택에 걸면 주소가 짧은 서울·오사카 후보 절반이 날아간다.
     * 평점 보유율이 낮아 평점 하한은 넣지 않는다.
     */
    public static boolean isVisitableEntity(String name, String address) {
        if (isAdministrativeZone(name)) return false;
        String addr = address == null ? "" : address.trim();
        if (addr.length() < MIN_USABLE_ADDRESS_LENGTH) return false;
        return !BROAD_ONLY_ADDRESSES.contains(addr);
    }

    /**
     * 개별 방문지가 아니라 지정 구역임이 이름에서 드러나는지. 이름만 보므로 주소 길이 규칙과 달리
     * 후보 풀을 줄이지 않아 Sonnet이 고른 메인 스텝에도 걸 수 있다.
     */
    public static boolean isAdministrativeZone(String name) {
        if (name == null) return false;
        if (ADMINISTRATIVE_ZONE_PATTERNS.stream().anyMatch(name::contains)) return true;
        return endsWithZoneDesignation(name);
    }

    /**
     * 괄호 주석을 떼고 남은 이름이 지정 명칭으로 끝나면 지정 구역이다. 단순 포함으로 막으면
     * {@code 서석대 (무등산권 국가지질공원)} 같은 개별 명소가 날아간다. 국립공원은 실제 방문지라 넣지 않는다.
     */
    private static boolean endsWithZoneDesignation(String name) {
        String bare = name.replaceAll("\\(.*?\\)", " ").trim();
        return ZONE_DESIGNATION_SUFFIXES.stream().anyMatch(bare::endsWith);
    }

    private static final Set<String> ZONE_DESIGNATION_SUFFIXES = Set.of("지질공원");

    private static final Set<String> ADMINISTRATIVE_ZONE_PATTERNS = Set.of(
            "관광특구", "관광지구", "관광권역", "특구");

    /**
     * 사실상 비어 있는 주소로 보는 길이. 더 넓히면 "의사당대로 38" 같은 짧은 유효 주소까지 막으므로
     * 판별은 광역 전용 목록이 맡는다.
     */
    private static final int MIN_USABLE_ADDRESS_LENGTH = 4;

    /** 광역 단위만 담긴 주소. 좌표는 맞아도 찾아갈 정보가 없다. */
    private static final Set<String> BROAD_ONLY_ADDRESSES = Set.of(
            "대한민국 서울특별시", "대한민국 부산광역시", "대한민국 제주특별자치도",
            "대한민국 인천광역시", "대한민국 대구광역시", "대한민국 대전광역시",
            "대한민국 광주광역시", "대한민국 울산광역시", "대한민국 세종특별자치시",
            "서울특별시", "부산광역시", "제주특별자치도", "인천광역시", "대구광역시",
            "대전광역시", "광주광역시", "울산광역시", "세종특별자치시", "대한민국");

    /** 야외·주간 성격이 명확한 세부 유형. 야간 버킷에 배치하지 않는다. */
    private static final Set<String> DAYTIME_SUB_TYPES = Set.of(
            "beach", "hill", "park", "waterfall", "trail", "island", "lake", "cave");

    /**
     * DB 리프가 구체적이면 그 리프(소문자)를, 총칭이면 이름·태그·설명으로 추론한 유형을, 추론에 실패하면
     * 총칭 리프를 반환한다. 유형 상한·시간대 버킷·대안 판정이 같은 기준을 보도록 모두 이 값을 쓴다.
     */
    public static String subType(String name, String category, List<String> tags, String description) {
        String leaf = leafCategory(category);
        String alias = LEAF_ALIAS.get(leaf);
        if (alias != null) return alias;
        if (!isGenericLeaf(leaf)) return leaf;

        String haystack = haystack(name, tags, description);
        if (!haystack.isBlank()) {
            for (Map.Entry<String, List<String>> entry : SUB_TYPE_KEYWORDS.entrySet()) {
                if (entry.getValue().stream().anyMatch(haystack::contains)) return entry.getKey();
            }
            String inferred = inferByNameSuffix(name);
            if (inferred != null) return inferred;
        }
        return leaf;
    }

    /**
     * 음식 종류를 직접 말하는 Foursquare 리프 → 이름 키워드와 같은 키. 별칭이 없으면 "Korean BBQ Restaurant"·
     * "BBQ Joint"·이름의 "흑돼지"가 서로 다른 종류로 세여 유형 상한이 안 걸린다.
     */
    private static final Map<String, String> LEAF_ALIAS = Map.ofEntries(
            Map.entry("korean bbq restaurant", "meat"), Map.entry("bbq joint", "meat"),
            Map.entry("barbecue restaurant", "meat"), Map.entry("steakhouse", "meat"),
            Map.entry("yakiniku restaurant", "meat"), Map.entry("korean beef restaurant", "meat"),
            Map.entry("seafood restaurant", "seafood"), Map.entry("sushi restaurant", "seafood"),
            Map.entry("fish restaurant", "seafood"),
            Map.entry("noodle restaurant", "noodle"), Map.entry("ramen restaurant", "noodle"),
            Map.entry("udon restaurant", "noodle"), Map.entry("soba restaurant", "noodle"),
            Map.entry("korean noodle house", "noodle"),
            Map.entry("fried chicken joint", "chicken"), Map.entry("korean fried chicken joint", "chicken"),
            Map.entry("soup place", "soup"));

    /**
     * 보강이 정한 음식 종류(DiningEnrichment) → 종류 키. 고기·해산물·면·국물만 구체 종류로 보고 나라 단위·분식·기타는
     * null이다. 해물탕집과 국숫집을 한식 하나로 묶어 상한을 걸면 안 된다.
     */
    public static String cuisineKey(String cuisine) {
        if (cuisine == null) return null;
        return switch (cuisine.trim().toUpperCase(Locale.ROOT)) {
            case "MEAT" -> "meat";
            case "SEAFOOD" -> "seafood";
            case "NOODLE" -> "noodle";
            case "SOUP" -> "soup";
            default -> null;
        };
    }

    /**
     * 식사 장소의 종류 키. 다양화(검색)와 유형 상한(스케줄)이 같은 값을 쓰도록 한 곳에 둔다.
     */
    public static String mealKind(String name, String category, List<String> tags, String description, String cuisine) {
        String key = cuisineKey(cuisine);
        return key != null ? key : subType(name, category, tags, description);
    }

    /**
     * 보강 속성의 세부 유형 → 유형 키. 같은 오름이 "mountain"·"hill"로 갈려 상한을 따로 세지 않게 기존 키로 옮긴다.
     * 판정에 쓸 수 없는 값(landmark·other)은 null이고 호출부가 추론으로 돌아간다.
     */
    public static String attributeSubTypeKey(PlaceAttributes attributes) {
        if (attributes == null || attributes.subType() == null) return null;
        return switch (attributes.subType()) {
            case "mountain" -> "hill";
            case "garden" -> "park";
            case "gallery" -> "museum";
            case "landmark", "other" -> null;
            default -> attributes.subType();
        };
    }

    public static boolean isDaytimeSubType(String subType) {
        return subType != null && DAYTIME_SUB_TYPES.contains(subType);
    }

    /** 대분류나 다름없는 총칭 리프인지. 유형 상한을 걸어도 의미가 없다. */
    public static boolean isGenericLeaf(String leaf) {
        return leaf == null || leaf.isBlank() || GENERIC_LEAF_TYPES.contains(leaf.toLowerCase());
    }

    /** Foursquare 계층 경로의 리프(소문자, trim). */
    public static String leafCategory(String category) {
        if (category == null || category.isBlank()) return "";
        int i = category.lastIndexOf('>');
        return (i >= 0 ? category.substring(i + 1) : category).trim().toLowerCase();
    }

    /**
     * "사라봉"·"한라산"처럼 한 글자 접미사로만 유형이 드러나는 이름용. 부분 문자열로 보면
     * "봉래동"·"산책"까지 걸리므로 마지막 글자에서만 본다.
     */
    private static String inferByNameSuffix(String name) {
        if (name == null || name.isBlank()) return null;
        // "성산 일출봉 (제주)" → "일출봉"
        String cleaned = name.replaceAll("\\(.*?\\)", " ").trim();
        String[] tokens = cleaned.split("\\s+");
        if (tokens.length == 0) return null;
        String last = tokens[tokens.length - 1];
        if (last.length() < 2) return null; // 한 글자 토큰은 판단 불가
        String tail = last.substring(last.length() - 1);
        for (Map.Entry<String, List<String>> entry : SUB_TYPE_NAME_SUFFIX.entrySet()) {
            if (entry.getValue().contains(tail)) return entry.getKey();
        }
        return null;
    }

    private static String haystack(String name, List<String> tags, String description) {
        StringBuilder sb = new StringBuilder();
        if (name != null) sb.append(name).append(' ');
        if (tags != null) tags.forEach(t -> sb.append(t).append(' '));
        if (description != null) sb.append(description).append(' ');
        return sb.toString().toLowerCase();
    }

    /**
     * 집합 POI(먹자골목·음식거리처럼 구역을 가리키는 장소)인지. 식사 슬롯에 들어가면 먹을 곳이 없는 스텝이 되고
     * 좌표가 구역 중심이라 동선이 왜곡된다. 소유격 "'s"는 "Joe's Pizza"처럼 흔해 판정에 쓰지 않는다.
     */
    public static boolean isAggregatePoi(String name, String category) {
        if (name == null) return false;
        String major = majorCategory(category);
        if (!"DINING".equals(major) && !"CAFE".equals(major)) return false;

        String lower = name.toLowerCase();
        if (lower.contains("streets") || lower.contains("food street") || lower.contains("district")
                || lower.contains("alleys") || lower.contains(" area") || lower.contains("商店街")
                || lower.contains("横丁") || lower.contains("골목") || lower.contains("먹자")
                || lower.contains("음식거리") || lower.contains("맛집거리")) {
            return true;
        }
        for (String token : lower.split("\\s+")) {
            if (token.endsWith("거리") && token.length() > 2
                    && !token.endsWith("먹거리") && !token.endsWith("볼거리") && !token.endsWith("즐길거리")) {
                return true;
            }
        }
        return false;
    }

}
