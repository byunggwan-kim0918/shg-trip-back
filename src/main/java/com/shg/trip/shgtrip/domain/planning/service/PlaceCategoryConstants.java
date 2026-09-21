package com.shg.trip.shgtrip.domain.planning.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 장소 카테고리 분류 키워드 상수.
 * HardValidator, ItineraryAutoFixer 등에서 공통으로 사용한다.
 */
public final class PlaceCategoryConstants {

    private PlaceCategoryConstants() {}

    public static final Set<String> ACCOMMODATION_KEYWORDS = Set.of(
            "숙소", "호텔", "리조트", "펜션", "게스트하우스", "모텔", "에어비앤비",
            "캠핑장", "캠프",
            "hotel", "resort", "hostel", "motel", "lodge", "accommodation", "camp"
    );

    /**
     * DB <b>카테고리 경로</b>에 쓰는 교통 판정 키워드. 카테고리는 "Travel and Transportation &gt;
     * Transport Hub &gt; Airport"처럼 정형이라 부분 문자열 매칭이 안전하다.
     * <b>이름 판정에는 쓰지 말 것</b> — 이름에 쓰면 "Sports Complex"가 "port"로 걸린다.
     */
    public static final Set<String> TRANSIT_HUB_KEYWORDS = Set.of(
            "역", "공항", "터미널", "항구", "airport", "station", "terminal", "port"
    );

    /**
     * 교통시설 <b>이름</b> 판정 — 단일 소스.
     *
     * <p>예전엔 {@link #TRANSIT_HUB_KEYWORDS}를 이름에도 부분 문자열로 썼는데, 그러면
     * "Sports Complex"·"Export Center"("port")와 "역사박물관"("역")이 전부 교통허브로 오판된다
     * (실측 확인). 한 글자 키워드는 경계 조건을 붙이고, 영문은 단어 경계로 끊는다.
     *
     * <p>"항"은 <b>포함하지 않는다</b>: 미포항·학리항처럼 관광지로 유효한 어항이 많아, 이름만으로
     * 교통시설로 단정하면 실제 명소가 일정에서 사라진다. 항구는 {@code subType("harbor")}로
     * 유형만 부여해 반복을 막는다.
     */
    private static final java.util.regex.Pattern TRANSIT_FACILITY_NAME = java.util.regex.Pattern.compile(
            "공항|터미널|여객|선착장|부두|도선|카페리|페리"
            + "|[가-힣]역(?![사원전])"                       // 서울역 O / 역사박물관·역원 X
            + "|\\bairport\\b|\\bterminal\\b|\\bferry\\b|\\bpier\\b"
            + "|\\b(?:train|bus|subway|metro|railway)?\\s*station\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * 개별 가게가 아니라 <b>여객시설</b>임이 이름에 드러나는 신호. 이런 곳은 "90분 관광"이 될 수 없어
     * 방문 스텝 후보에서 제외한다(실측: "성산포항 종합여객터미널"이 관광 스텝 90분).
     * 시딩·검증·동선이 같은 목록을 본다.
     */
    private static final java.util.regex.Pattern PASSENGER_FACILITY_NAME = java.util.regex.Pattern.compile(
            "여객|터미널|대합실|선착장|부두|도선|카페리", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * 허브 본체가 아닌 부속 시설 POI 신호. Foursquare에 "Immigration Check"/"Check-in
     * Counters"/"주차장" 같은 하위 POI가 공항과 같은 카테고리로 섞여 있어, 도착/출발 허브로
     * 쓰이면 일정 첫 스텝이 "출입국심사대"가 되는 사고가 난다.
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

    /**
     * 이름에 교통 허브 신호(역/공항/터미널/항구 등)가 있는지 판정한다.
     * 카테고리만으로 판단하지 않는다 — Foursquare/Google 데이터에 "흰여울문화마을"이
     * "Transport Hub > Bus Station"으로 오적재되는 사례가 있어, 실제 허브(공항·역·터미널)만
     * 도착/출발 지점으로 쓰이도록 이름 기반으로 한 번 더 거른다.
     */
    public static boolean hasTransitNameSignal(String name) {
        if (name == null) return false;
        return TRANSIT_FACILITY_NAME.matcher(name).find();
    }

    /**
     * 여객시설(터미널·대합실·선착장 등) 이름인지 — 방문 스텝 후보에서 제외할 때 쓴다.
     * 시딩({@code TourApiSeeder})과 동선 보충이 같은 기준을 보도록 여기 한 곳에 둔다.
     */
    public static boolean isPassengerFacility(String name) {
        if (name == null) return false;
        return PASSENGER_FACILITY_NAME.matcher(name).find();
    }

    /** 허브 카테고리이지만 본체가 아닌 부속 시설(심사대/체크인/주차 등)인지 판정. */
    public static boolean isHubSubFacility(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return HUB_SUB_FACILITY_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /**
     * Bar 계열(맥주바/펍/와인바 등) 판정. 대분류는 DINING이지만 식사 슬롯(특히 점심)에
     * 배치되면 부적합하므로 scheduleDay에서 식사가 아닌 저녁 활동으로 다룬다.
     */
    public static boolean isBar(String category) {
        if (category == null) return false;
        String lower = category.toLowerCase();
        return lower.contains("> bar") || lower.contains("nightlife")
                || lower.endsWith("bar") || lower.contains("pub");
    }

    /**
     * 카테고리 기반 체류시간 휴리스틱(분) — enrich 권장 체류시간이 없을 때의 폴백.
     * 등산·트레킹류는 길게, 해변·시장은 짧게, 그 외 관광지는 기존 90분 유지.
     */
    public static int heuristicVisitMinutes(String category) {
        if (category == null) return 90;
        String lower = category.toLowerCase();
        if (lower.contains("hiking") || lower.contains("trail") || lower.contains("mountain")) return 150;
        if (lower.contains("beach") || lower.contains("market")) return 60;
        return 90;
    }

    /** 야간 방문이 부적합한 야외/주간 성격 장소인지 판정(해변/골프/등산로 등). */
    public static boolean isDaytimeOutdoor(String category) {
        if (category == null) return false;
        String lower = category.toLowerCase();
        if (!"ATTRACTION".equals(majorCategory(category))) return false;
        return DAYTIME_OUTDOOR_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /**
     * DB 카테고리(Foursquare 계층 경로, 예: "Dining and Drinking > Restaurant > ...")를
     * 5개 대분류로 매핑한다. 대안 카테고리 매칭, 동선 재정렬 등에서 공통으로 사용한다.
     */
    public static String majorCategory(String category) {
        if (category == null) return "OTHER";
        String lower = category.toLowerCase();
        if (lower.contains("lodging")) return "LODGING";
        if (lower.contains("dining and drinking") && (lower.contains("cafe") || lower.contains("coffee"))) return "CAFE";
        if (lower.contains("dining and drinking") || lower.contains("restaurant")) return "DINING";
        if (lower.contains("landmarks") || lower.contains("arts and entertainment")
                || lower.contains("sports and recreation") || lower.contains("outdoors")) return "ATTRACTION";
        // 시장·쇼핑몰(Foursquare "Retail > ...")도 사용자가 고르는 방문지다. 이 매핑이 없으면
        // majorCategory가 OTHER가 돼 방문 스텝 보충(isVisitableFill)에서 통째로 배제됐다 —
        // 마법사에 market/shopping 카테고리가 있는데 일정에 절대 나올 수 없던 구멍.
        // (카페/식당 계열은 위에서 이미 판정되므로 "Coffee Shop" 같은 이름과 충돌하지 않는다)
        if (lower.contains("retail") || lower.contains("market") || lower.contains("shopping")) return "ATTRACTION";
        if (TRANSIT_HUB_KEYWORDS.stream().anyMatch(lower::contains)) return "TRANSIT_HUB";
        return "OTHER";
    }

    // ── 사용자 입력(마법사) ↔ 검색 슬롯 매핑 ─────────────────────────────────────
    // 여행지별 특수 규칙은 두지 않는다. 아래 표는 "마법사 옵션 → 검색 의미"만 정의하며
    // 실제 판단은 이 표 + DB 데이터로만 이뤄진다(여행지명 하드코딩 금지).

    /** 검색 슬롯 역할. RouteOptimizer가 식사·숙소·허브를 채우는 데 필요한 고정 구조. */
    public enum SearchRole { TRANSPORTATION, RESTAURANT, CAFE, ACCOMMODATION, ATTRACTION }

    /**
     * 마법사 카테고리 id(17종) → 검색 문구. 관광 하위유형은 ATTRACTION 슬롯에서 유형별로
     * 쿼리를 분리해 임베딩 평균화(여러 카테고리가 한 문장에 뭉쳐 의미가 희석되는 문제)를 막는다.
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
     * 마법사 테마 id(20종) → 관광 슬롯 검색 문구. 넣지 않는 테마:
     * <ul>
     *   <li>가족여행·알뜰여행·반려동물 등 — 장소 성격이 아니라 여행 방식이라 무관한 장소가 끌려온다</li>
     *   <li>맛집(food) — 대상이 Dining and Drinking이라 관광 슬롯(해당 대분류 배제)에서 항상 0건이고,
     *       식당 슬롯이 이미 담당한다</li>
     * </ul>
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
     * "한 끼 식사"가 아닌 Dining and Drinking 하위 유형. 대분류는 DINING이지만 점심·저녁 슬롯에
     * 넣으면 안 되는 것들 — 검색 슬롯(SQL 배제)과 동선 층(식사 슬롯 배정·보충)이 <b>같은 목록</b>을
     * 봐야 한다. 한쪽만 걸러내면 검색은 식당을 가져오는데 최종 패스가 디저트를 저녁으로 넣는다
     * (실측: 오사카 저녁 슬롯에 パティスリー·スイーツパラダイス 삽입).
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

    /**
     * 점심·저녁 슬롯에 넣어도 되는 "한 끼 식사" 장소인지. 대분류가 DINING이어야 하고,
     * 디저트·베이커리·바·간식류가 아니어야 하며, 집합 POI(음식거리)도 아니어야 한다.
     */
    public static boolean isMealPlace(String name, String category) {
        return isMealPlace(name, category, null);
    }

    /**
     * @param tags enrich 배치가 붙인 한국어 태그. 이름·카테고리 어디에도 신호가 없는 곳을
     *             걸러내는 가장 정확한 단서다(실측: "올드북촌"은 상호만 보면 식당 같지만
     *             태그가 카페·북카페·디저트다).
     */
    public static boolean isMealPlace(String name, String category, List<String> tags) {
        if (!"DINING".equals(majorCategory(category))) return false;
        if (isAggregatePoi(name, category)) return false;
        String lower = category == null ? "" : category.toLowerCase();
        if (NON_MEAL_DINING_PATTERNS.stream().anyMatch(lower::contains)) return false;
        if (tags != null && tags.stream().anyMatch(PlaceCategoryConstants::isNonMealByName)) return false;
        // 이름도 본다 — TourAPI는 음식점(contentTypeId=39)을 전부 "Dining and Drinking > Restaurant"
        // 하나로 적재해서, 카테고리만 보면 베이커리·북카페가 저녁 식사로 들어간다
        // (실측: "젤코바 베이커리 카페" 17:30 저녁 슬롯, "올드북촌"(북카페) 식사 후보).
        return !isNonMealByName(name);
    }

    /** 이름에 드러나는 비식사 신호(카페·베이커리·디저트류). 카테고리가 뭉뚱그려진 데이터 방어용. */
    private static boolean isNonMealByName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return NON_MEAL_NAME_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /**
     * 비식사 이름 신호. "식당"류 상호에 섞이지 않는 어휘만 담는다 — 예컨대 "밥"·"국수"는
     * 카페 이름에도 쓰이므로 넣지 않는다.
     */
    private static final List<String> NON_MEAL_NAME_KEYWORDS = List.of(
            "카페", "베이커리", "디저트", "제과", "빵집", "아이스크림", "빙수", "커피",
            "cafe", "café", "bakery", "dessert", "coffee", "patisserie");

    /**
     * 슬롯별로 "명백히 틀린 대분류"만 SQL에서 배제한다(하드 카테고리 필터가 아니라 오적재
     * 방어). 마법사 카테고리 id와 DB category(Foursquare 계층 경로)는 형식이 다르고 DB
     * 오적재가 흔하므로(흰여울문화마을=Bus Station 등) 의미 판별은 벡터 쿼리에 맡긴다.
     * 값은 SQL ILIKE 패턴 조각(소문자 부분 문자열).
     */
    private static final Map<SearchRole, List<String>> SLOT_EXCLUDED_PATTERNS = Map.of(
            SearchRole.CAFE, List.of("lodging", "travel and transportation", "landmarks"),
            // 식사 슬롯에서 바·베이커리·디저트·간식(도넛/베이글/스무디/푸드코트/푸드트럭)을 뺀다.
            // 대분류가 DINING이라 슬롯 자리를 차지하지만 점심·저녁 한 끼로 배치되지 않아(Bar는
            // RouteOptimizer가 식사에서 제외) 실제 식사 후보를 밀어낸다 — 실측: 서울 식사 슬롯
            // 12곳 중 8곳이 간식·푸드코트라 정작 식당이 4곳뿐이었다. 카페는 전용 슬롯이 따로 있다.
            SearchRole.RESTAURANT, restaurantSlotExclusions(),
            SearchRole.ACCOMMODATION, List.of("dining and drinking", "landmarks", "arts and entertainment"),
            SearchRole.ATTRACTION, List.of("lodging", "transport hub", "dining and drinking"),
            SearchRole.TRANSPORTATION, List.of("dining and drinking", "lodging")
    );

    /**
     * 관광 슬롯이지만 대상이 Dining and Drinking 아래에 있는 사용자 카테고리.
     * (나이트라이프=Bar, 길거리음식=Snack Place 등) 관광 슬롯의 기본 배제에 "dining and drinking"이
     * 있어 그대로 두면 이 카테고리는 후보가 절대 나올 수 없다 — 이 슬롯에서만 배제를 푼다.
     */
    private static final Set<String> DINING_BASED_ATTRACTION_SUBTYPES = Set.of("nightlife", "street_food");

    /**
     * 슬롯별 최소 cosine 유사도. 미만 결과는 버린다 — 데이터가 부족할 때 limit를 채우려고
     * 무관한 장소를 끌어오던 문제(카페 쿼리에 해변이 나온 직접 원인)의 근본 차단. 후보가
     * 줄면 FallbackDecider가 COMPACT/FALLBACK으로 정직하게 이어받는다.
     */
    private static final Map<SearchRole, Double> SLOT_MIN_SIMILARITY = Map.of(
            SearchRole.TRANSPORTATION, 0.30,
            SearchRole.RESTAURANT, 0.34,
            SearchRole.CAFE, 0.36,
            SearchRole.ACCOMMODATION, 0.34,
            SearchRole.ATTRACTION, 0.32
    );

    /** 슬롯 역할에 대응하는 majorCategory (하이브리드 랭킹의 대분류 일치 가점용). */
    private static final Map<SearchRole, String> SLOT_MAJOR_CATEGORY = Map.of(
            SearchRole.TRANSPORTATION, "TRANSIT_HUB",
            SearchRole.RESTAURANT, "DINING",
            SearchRole.CAFE, "CAFE",
            SearchRole.ACCOMMODATION, "LODGING",
            SearchRole.ATTRACTION, "ATTRACTION"
    );

    /**
     * 경유형(드라이브 코스·해안도로·둘레길 등) 신호. 이런 장소는 "90분 머무는 방문지"가 아니라
     * 이동 중 지나가는 구간이라, 방문 스텝으로 반복 배치되면 일정이 무의미해진다(실측: 해안도로가
     * 한 일정에 7곳). 코드가 [경유]로 마킹해 프롬프트가 전체 최대 1개로 제한하도록 한다.
     */
    private static final Set<String> VIA_ROUTE_KEYWORDS = Set.of(
            "해안도로", "드라이브", "드라이브코스", "둘레길", "올레길", "산책로", "가로수길",
            "road", "drive", "scenic route", "scenic drive", "trail route", "boulevard", "highway"
    );

    /** 마법사 카테고리 id의 검색 문구. 미정의 id면 id 자체를 그대로 쓴다(신규 옵션 대비). */
    public static String userCategoryQuery(String categoryId) {
        if (categoryId == null || categoryId.isBlank()) return "";
        return USER_CATEGORY_QUERY.getOrDefault(categoryId.trim().toLowerCase(), categoryId.trim());
    }

    /** 관광 슬롯에서 전용 쿼리로 분리할 카테고리인지. */
    public static boolean isAttractionSubtype(String categoryId) {
        return categoryId != null && ATTRACTION_SUBTYPES.contains(categoryId.trim().toLowerCase());
    }

    /** 검색 의미가 있는 테마의 문구. 없으면 null(쿼리를 만들지 않음). */
    public static String themeQuery(String themeId) {
        if (themeId == null || themeId.isBlank()) return null;
        return THEME_QUERY.get(themeId.trim().toLowerCase());
    }

    public static List<String> excludedCategoryPatterns(SearchRole role) {
        return SLOT_EXCLUDED_PATTERNS.getOrDefault(role, List.of());
    }

    /**
     * 슬롯별 배제 패턴. 관광 슬롯 중 Dining and Drinking 계열을 대상으로 하는 카테고리
     * (나이트라이프·길거리음식)는 "dining and drinking" 배제를 풀어야 후보가 나온다.
     */
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

    /** 이름·카테고리·태그에 경유형 신호가 있으면 true(방문지가 아니라 지나가는 구간). */
    public static boolean isViaRoute(String name, String category, List<String> tags) {
        StringBuilder sb = new StringBuilder();
        if (name != null) sb.append(name).append(' ');
        if (category != null) sb.append(category).append(' ');
        if (tags != null) tags.forEach(t -> sb.append(t).append(' '));
        String combined = sb.toString().toLowerCase();
        return VIA_ROUTE_KEYWORDS.stream().anyMatch(combined::contains);
    }

    /**
     * 주소에서 세부지역(시/군/구 단위) 토큰을 추출한다. 후보의 region 컬럼이 전부 상위 도시명
     * (Jeju 등)이라 Sonnet이 day를 지리적으로 묶을 근거가 없던 문제를 메운다.
     *
     * <p>국가 무관하게 동작해야 하므로 주소 형식별 휴리스틱만 쓰고, 판단이 서지 않으면 null을
     * 반환해 호출부가 region을 유지하게 한다(여행지명 하드코딩 금지 원칙).
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
     * 마법사 카테고리 id → 일정에 그 유형이 실제로 등장했는지 판정할 매칭 키워드(한/영 혼용).
     * DB category는 영어 계층 경로이고 name/tags는 한국어인 경우가 많아 양쪽을 함께 본다.
     * restaurant/cafe/accommodation/attraction은 키워드가 아니라 대분류로 판정한다.
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

    /**
     * 이 장소가 사용자가 고른 카테고리(마법사 id)에 해당하는지 판정한다(C2 커버리지 검사용).
     * 대분류로 판정되는 유형(식당/카페/숙소/관광 전반)은 majorCategory로, 나머지 하위유형은
     * 이름·카테고리·태그의 한/영 키워드 매칭으로 본다.
     */
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
    // DB 리프가 "Tourist Attraction"처럼 총칭이면 유형 정보가 사라져 유형 상한·시간대 판정·
    // 대안 매칭이 전부 무력해진다(실측: 제주 일정의 관광지 10곳이 전부 같은 리프 → 오름 3연속).
    // 이름·태그·설명의 범용 키워드로 세부 유형을 추론해 세 곳에서 같은 기준으로 쓴다.
    // 여행지 고유명사는 넣지 않는다 — 지형·시설의 일반 명사만(한/영/일 병기).

    /** 유형 정보가 없는 총칭 리프. 이 경우에만 이름·태그 기반 추론으로 넘어간다. */
    public static final Set<String> GENERIC_LEAF_TYPES = Set.of(
            "tourist attraction", "attraction", "landmark", "landmarks", "landmarks and outdoors",
            "point of interest", "outdoors", "arts and entertainment", "structure", "plaza",
            "기타", "other", "general"
    );

    /**
     * 세부 유형 → 판별 키워드(한/영/일). 앞에 오는 항목이 우선한다(더 구체적인 것 먼저).
     * 값은 소문자 부분 문자열 매칭이며, 오탐이 잦은 한 글자 접미사(산·봉·사)는 별도 규칙으로 다룬다.
     */
    private static final Map<String, List<String>> SUB_TYPE_KEYWORDS = new LinkedHashMap<>();
    static {
        SUB_TYPE_KEYWORDS.put("waterfall", List.of("폭포", "waterfall", "falls", "滝"));
        SUB_TYPE_KEYWORDS.put("beach", List.of("해수욕장", "해변", "바닷가", "beach", "浜", "ビーチ"));
        SUB_TYPE_KEYWORDS.put("cave", List.of("동굴", "굴사", "cave", "grotto", "洞窟"));
        SUB_TYPE_KEYWORDS.put("hill", List.of("오름", "등산", "hiking", "mountain", "hill", "peak", "oreum", "岳", "山"));
        SUB_TYPE_KEYWORDS.put("trail", List.of("둘레길", "올레", "산책로", "트레일", "trail", "promenade", "walkway", "遊歩道"));
        SUB_TYPE_KEYWORDS.put("park", List.of("공원", "수목원", "정원", "park", "garden", "arboretum", "公園", "庭園"));
        SUB_TYPE_KEYWORDS.put("lake", List.of("호수", "저수지", "lake", "pond", "湖"));
        SUB_TYPE_KEYWORDS.put("island", List.of("island", "islet", "島"));
        // 항구는 총칭 리프("Tourist Attraction")로 들어오는 경우가 많아 유형 정보가 사라진다.
        // 유형을 부여해야 상한·대안 매칭이 작동한다(재분류가 아니라 분류 — 미포항 같은 관광 어항은 유지).
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

    /** 이름 접미사로만 판정하는 유형 — 부분 문자열로 보면 오탐이 잦은 한 글자들. */
    private static final Map<String, List<String>> SUB_TYPE_NAME_SUFFIX = Map.of(
            "hill", List.of("봉", "산", "악"),
            "temple", List.of("사", "암"),
            "island", List.of("섬", "도"),
            // "제주 성산항"처럼 이름 끝 한 글자로만 항구임이 드러나는 경우 (항공·관광은 접미사가 아니라 안 걸림)
            "harbor", List.of("항")
    );

    /** 야외·주간 성격이 명확한 세부 유형 — 야간 버킷에 배치하지 않는다. */
    private static final Set<String> DAYTIME_SUB_TYPES = Set.of(
            "beach", "hill", "park", "waterfall", "trail", "island", "lake", "cave");

    /**
     * 장소의 세부 유형을 반환한다. DB 리프가 구체적이면 그 리프(소문자)를, 총칭이면 이름·태그·설명의
     * 범용 키워드로 추론한 유형을 돌려준다. 추론에 실패하면 총칭 리프를 그대로 반환한다.
     *
     * <p>유형 상한(cap 키)·시간대 버킷·대안 "같은 종류" 판정이 모두 이 값을 쓴다 — 세 곳이 같은
     * 기준을 봐야 "오름 3연속인데 상한에 안 걸리고, 해변이 저녁에 배치되고, 대안은 엉뚱한 종류"가
     * 동시에 생기지 않는다.
     */
    public static String subType(String name, String category, List<String> tags, String description) {
        String leaf = leafCategory(category);
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

    /** 이 세부 유형이 야외·주간 성격인지(야간 버킷 배치 금지). */
    public static boolean isDaytimeSubType(String subType) {
        return subType != null && DAYTIME_SUB_TYPES.contains(subType);
    }

    /** 대분류나 다름없는 총칭 리프인지 — 유형 상한을 걸어도 의미가 없는 범주. */
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
     * 이름 접미사 판정. "사라봉"·"한라산"처럼 한 글자 접미사로만 유형이 드러나는 이름을 위한 것으로,
     * 부분 문자열로 보면 "봉래동"·"산책"까지 걸리므로 이름의 마지막 글자에서만 본다.
     */
    private static String inferByNameSuffix(String name) {
        if (name == null || name.isBlank()) return null;
        // 괄호 주석·지점명을 떼고 마지막 토큰의 끝 글자를 본다 ("성산 일출봉 (제주)" → "일출봉")
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
     * 집합 POI(먹자골목·음식거리·상점가처럼 개별 가게가 아니라 구역을 가리키는 장소)인지 판정한다.
     * 식사 슬롯에 들어가면 "그 거리에서 뭘 먹는지"가 없는 스텝이 되고 좌표도 구역 중심이라
     * 동선이 왜곡된다(실측: "Seogwipo's food streets"가 저녁 스텝).
     *
     * <p>소유격 "'s"만으로는 판정하지 않는다 — "Joe's Pizza"처럼 개별 가게 이름에 흔해 오탐이 크다.
     * 복수·구역을 뜻하는 토큰이 있을 때만 집합으로 본다.
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
        // "…거리"로 끝나는 이름(먹거리·볼거리 같은 일반 명사는 제외)
        for (String token : lower.split("\\s+")) {
            if (token.endsWith("거리") && token.length() > 2
                    && !token.endsWith("먹거리") && !token.endsWith("볼거리") && !token.endsWith("즐길거리")) {
                return true;
            }
        }
        return false;
    }
}
