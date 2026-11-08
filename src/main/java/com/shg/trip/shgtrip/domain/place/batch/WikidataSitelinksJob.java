package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 장소에 Wikidata 인기도 신호(언어판 수)를 채우는 배치.
 *
 * <p>Maps 약관 3.2.3(a)가 평점·리뷰·좌표·사진의 로컬 캐싱을 금지해(예외는 Place ID) 대신 쓰는 신호다.
 * Wikidata는 CC0라 저장 제약이 없고, SPARQL이 무인증·무료이며, TourAPI와 달리 해외를 덮는다.
 *
 * <h2>왜 장소별로 묻지 않는가</h2>
 * 장소마다 API를 부르면 5만 건이 넘고 레이트 리밋에 걸린다. {@code SERVICE wikibase:box}로 지역 경계 상자 안의
 * 항목을 한 번에 받고, 별칭·분류는 항목 id로 묶어 몇 번 더 묻는다. 한 쿼리에 다 넣으면 큰 지역에서
 * WDQS 60초 제한에 걸린다.
 *
 * <h2>좌표와 이름을 모두 본다</h2>
 * 표제가 출처마다 달라("성산일출봉 천연보호구역" ↔ "성산일출봉 [유네스코 세계자연유산]") 좌표가 필요하다.
 * 좌표만 쓰면 명소 주변에 밀집한 식당이 명소 점수를 가져가므로 이름 포함 여부를 게이트로 둔다.
 *
 * <h2>항목이 장소를 고른다 (장소가 항목을 고르는 게 아니라)</h2>
 * 장소가 항목을 고르면 먼저 적재된 장소가 이긴다("경복궁 한식당"이 진짜 경복궁보다 먼저 항목을 가져간다).
 * 항목을 유명한 순으로 돌며, 반경 안에서 <b>이름이 항목 이름의 앞부분과 같은</b> 장소 중 카테고리 등급이
 * 가장 좋은 행에 붙인다(규칙은 {@link #assign}). 지역 전체를 매번 다시 계산해 한 트랜잭션으로 덮어쓰므로
 * 상한도, 순서 의존도 없다.
 * 장소 목록은 id·이름·좌표·카테고리만 읽는다(엔티티째 읽으면 임베딩 컬럼 때문에 서울만 수백 MB다).
 *
 * <h2>매칭 실패도 기록한다</h2>
 * {@code wikidata_matched_at}이 없으면 "Wikidata에 없는 장소"를 배치가 돌 때마다 다시 조회한다.
 *
 * <p>HTTP 호출과 응답 매핑을 분리해 후자만 단위 테스트한다.
 */
@Slf4j
@Component
@Profile("batch")
public class WikidataSitelinksJob {

    private static final String SPARQL_ENDPOINT = "https://query.wikidata.org/sparql";

    /**
     * Wikidata·OSM 모두 User-Agent 없는 요청을 거부한다(Overpass는 406). 연락처를 포함하는 것이
     * Wikimedia 정책 권장 사항이라 앱 이름과 함께 둔다.
     */
    static final String USER_AGENT =
            "shg-trip/1.0 (travel itinerary planner; +https://github.com/byunggwan-kim0918/shg-trip-back)";

    /**
     * 좌표 매칭 반경(m). 큰 자연물은 출처마다 중심점이 수백 m 다르다.
     * 이름 게이트가 있어 넓혀도 되고, 같은 이름의 다른 지역 항목은 보통 수 km 떨어져 있어 2km로도 걸러진다.
     */
    private static final double MATCH_RADIUS_M = 2_000.0;

    /** 지역 bbox 산출에 필요한 최소 장소 수. 표본이 적으면 백분위수가 의미 없다. */
    private static final int MIN_PLACES_PER_REGION = 50;

    /**
     * bbox 여유(도, 약 5km). bbox를 오염 좌표 제거용 2~98 백분위로 잡아 여유가 없으면 지역 끝 명소가 잘린다.
     * 매칭은 장소 반경 2km 안에서만 일어나므로 상자를 넓혀도 오매칭은 늘지 않는다.
     */
    private static final double BOX_MARGIN_DEG = 0.05;

    /** 별칭·분류 조회 한 번에 묶는 항목 수. */
    private static final int DETAIL_BATCH_SIZE = 300;

    /**
     * 식음료 업장 상위 분류: 음식점(Q11707)·카페(Q30022)·바(Q187456)·제과점(Q274393).
     * 이 분류의 하위 분류 전체(한식당·피자집·펍 등)에 속한 항목만 식당 행에 붙는다.
     */
    private static final List<String> FOOD_ROOT_CLASSES = List.of("Q11707", "Q30022", "Q187456", "Q274393");

    /**
     * 앞부분 이름 묶음의 최소 길이. 짧은 앞부분("해동 용궁사"의 "해동")은 아무 데나 걸린다.
     * 이름 전체가 두 글자인 경우("종묘", "우도")는 예외로 허용한다.
     */
    private static final int MIN_NAME_TOKEN_LENGTH = 3;

    private static final double EARTH_RADIUS_M = 6_371_000.0;

    private final PlaceRepository placeRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final HttpClient httpClient;

    /** 대상 지역. 비어 있으면 장소 수 기준 전 지역. */
    @Value("${batch.wikidata.regions:}")
    private List<String> targetRegions;

    public WikidataSitelinksJob(PlaceRepository placeRepository, ObjectMapper objectMapper,
                                PlatformTransactionManager transactionManager) {
        this.placeRepository = placeRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public void run() {
        List<RegionBox> boxes = loadRegionBoxes();
        if (boxes.isEmpty()) {
            log.info("Wikidata 매칭 대상 지역 없음");
            return;
        }

        // 식음료 분류 목록이 없으면 식당이 명소 항목을 가져간다(아래 assign 참고). 그 상태로 덮어쓰느니
        // 이번 실행을 건너뛰고 지난 값을 둔다.
        Set<String> foodClasses;
        try {
            foodClasses = fetchFoodClasses();
        } catch (Exception e) {
            throw new BatchStageFailed("Wikidata 식음료 분류 조회 실패, 매칭 건너뜀: " + e.getMessage());
        }

        int totalMatched = 0;
        int totalChecked = 0;
        List<String> failedRegions = new ArrayList<>();
        for (RegionBox box : boxes) {
            try {
                int[] result = processRegion(box, foodClasses);
                totalChecked += result[0];
                totalMatched += result[1];
            } catch (Exception e) {
                // 한 지역이 실패해도 나머지는 계속한다(TourApiSeeder와 같은 방침).
                failedRegions.add(box.region());
                log.warn("Wikidata 매칭 실패: region={}, error={}", box.region(), e.getMessage());
            }
        }
        log.info("Wikidata 인기도 매칭 완료: 확인 {}건, 매칭 {}건", totalChecked, totalMatched);
        if (!failedRegions.isEmpty()) throw new BatchStageFailed("Wikidata 매칭 실패 지역 " + failedRegions);
    }

    private int[] processRegion(RegionBox box, Set<String> foodClasses) throws Exception {
        List<Candidate> places = placeRepository.findWikidataCandidates(box.region()).stream()
                .map(Candidate::fromRow)
                .toList();
        if (places.isEmpty()) {
            log.info("Wikidata 매칭 건너뜀: region={} (대상 없음)", box.region());
            return new int[]{0, 0};
        }

        // 별칭·분류 조회가 하나라도 실패하면 지역 전체를 건너뛰고 지난 값을 둔다.
        // 별칭·분류 없이 쓰면 식당이 명소 점수를 받거나 별칭으로만 맞는 명소가 0이 된다.
        List<WikidataItem> items = fetchItems(box, foodClasses);
        List<Assignment> assignments = assign(places, items);
        log.info("Wikidata 조회: region={}, 항목 {}건, 장소 {}곳", box.region(), items.size(), places.size());

        // 지역 전체를 0("항목 없음")으로 덮고 매칭된 곳만 다시 쓴다. 한 트랜잭션이라
        // 일정 생성이 중간 상태(인기도가 다 0인 순간)를 보지 않는다.
        transactionTemplate.executeWithoutResult(status -> {
            placeRepository.resetWikidataForRegion(box.region());
            for (Assignment a : assignments) {
                placeRepository.updateWikidataMatch(a.placeId(), a.item().id(), a.item().sitelinks());
            }
        });
        log.info("Wikidata 매칭: region={}, {}/{}곳", box.region(), assignments.size(), places.size());
        return new int[]{places.size(), assignments.size()};
    }

    /**
     * 항목마다 맞는 장소를 고른다. <b>좌표와 이름을 모두</b> 요구한다. 좌표만 쓰면 명소 주변 식당이 명소 점수를 가져간다.
     *
     * <h3>이름 규칙: 장소 이름 = 항목 이름의 앞부분</h3>
     * 장소 이름을 정규화({@link #nameKey})한 값이 항목 이름의 <b>첫 단어부터 이어지는 단어 묶음</b>과
     * 같아야 한다(띄어쓰기는 무시). "성산일출봉"은 "성산일출봉 천연보호구역"에, "북촌한옥마을"은
     * "북촌 한옥마을"에 붙는다. 장소 이름에 항목에 없는 단어가 하나라도 있으면 붙지 않는다.
     * 그래야 "창덕궁 후원"·"연세대학교 정문" 같은 하위 시설이 본체 항목을 가져가지 않는다.
     *
     * <h3>행정구역 행은 받지 않는다</h3>
     * "서울특별시"·"종로구"·"제주특별자치도" 같은 Foursquare "States and Municipalities" 행은
     * 이름이 정확히 같아도 찾아가는 장소가 아니라 인기도 순 대표 명소 슬롯만 차지한다.
     *
     * <h3>이름은 라벨과 한국어 별칭을 모두 본다</h3>
     * 라벨이 옛 이름이거나 묶음 이름인 항목이 있다. N서울타워 항목의 라벨은 "YTN서울타워", 만장굴은
     * "김녕굴·만장굴"이라 라벨만 보면 이 명소들의 실제 행이 0으로 남는다.
     *
     * <h3>식음료 항목은 식당 행에만, 나머지는 식당이 아닌 행에만</h3>
     * 상호가 명소 이름과 같은 식당("창덕궁" 한식당)이 명소 점수를 가져가지 않도록, 항목의 Wikidata 분류
     * ({@link #FOOD_ROOT_CLASSES} 하위)로 식음료 업장인지 가른다.
     *
     * <h3>항목 하나 → 같은 이름의 행 여러 개</h3>
     * 같은 명소가 소스별(google·tourapi·foursquare·wikidata)로 여러 행일 수 있어, 한 행만 붙이면 검색이
     * 0인 행을 골라 대표 명소가 빠진다. 조건을 만족하는 행 중 <b>카테고리 등급이 가장 좋은 행 전부</b>에
     * 붙인다. 등급을 보므로 명소 이름을 그대로 쓴 식당이 명소와 같이 점수를 받지는 않는다.
     *
     * <p>항목은 유명한 순으로 처리하고, 장소 하나엔 항목 하나만 붙는다. "한라산" 장소에는
     * "한라산"(46)과 "한라산 국립공원"(30)이 모두 맞지만 더 유명한 46이 남는다.
     */
    static List<Assignment> assign(List<Candidate> places, List<WikidataItem> items) {
        Map<String, List<Candidate>> byName = new HashMap<>();
        for (Candidate p : places) {
            for (String key : matchKeys(p)) byName.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }

        List<WikidataItem> famousFirst = items.stream()
                .sorted(Comparator.comparingInt(WikidataItem::sitelinks).reversed()
                        .thenComparing(WikidataItem::id))
                .toList();

        Set<Long> taken = new HashSet<>();
        List<Assignment> result = new ArrayList<>();
        for (WikidataItem item : famousFirst) {
            // 라벨은 앞부분 묶음까지, 별칭은 이름 전체만 본다. 별칭의 앞부분은 엉뚱한 곳에 걸린다
            // ("여의도 순복음교회"의 "여의도"가 여의도 섬 행에 붙는다).
            Set<String> keys = new java.util.LinkedHashSet<>(leadingNames(nameKey(item.label())));
            for (String alias : item.aliases()) {
                String whole = String.join("", nameKey(alias));
                if (whole.length() >= 2) keys.add(whole);
            }

            List<Candidate> eligible = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            for (String key : keys) {
                for (Candidate p : byName.getOrDefault(key, List.of())) {
                    if (taken.contains(p.id()) || !seen.add(p.id())) continue;
                    if (isDiningRow(p.category()) != item.food()) continue;
                    if (item.distanceMeters(p.lat(), p.lon()) > MATCH_RADIUS_M) continue;
                    eligible.add(p);
                }
            }
            if (eligible.isEmpty()) continue;

            int bestTier = eligible.stream().mapToInt(p -> categoryTier(p.category())).min().orElseThrow();
            for (Candidate p : eligible) {
                if (categoryTier(p.category()) != bestTier || !taken.add(p.id())) continue;
                result.add(new Assignment(p.id(), item));
            }
        }
        return result;
    }

    /**
     * 항목 이름의 앞부분 단어 묶음(띄어쓰기 제거). ["성산일출봉", "천연보호구역"] →
     * {"성산일출봉", "성산일출봉천연보호구역"}. 이름 전체가 아니면 {@value #MIN_NAME_TOKEN_LENGTH}자
     * 이상만 쓴다. "해동 용궁사"의 "해동"처럼 짧은 앞부분은 다른 장소에 걸린다.
     *
     * <p>뒤쪽 단어만으로는 붙이지 않는다. 뒤쪽은 대개 분류어라("천연보호구역", "국립공원", "해수욕장")
     * 그 이름만 단 엉뚱한 행에 걸린다.
     */
    static List<String> leadingNames(List<String> tokens) {
        List<String> names = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            sb.append(tokens.get(i));
            boolean whole = i == tokens.size() - 1;
            if (whole) {
                if (sb.length() >= 2) names.add(sb.toString());
            } else if (sb.length() >= MIN_NAME_TOKEN_LENGTH && hasHangul(sb)) {
                names.add(sb.toString());
            }
        }
        return names;
    }

    /**
     * 장소 쪽 비교 키. 이름 전체(띄어쓰기 제거)에 더해, "A과 B"·"A와 B" 꼴이면 A도 키로 쓴다.
     * TourAPI는 창덕궁을 "창덕궁과 후원 [유네스코 세계유산]"으로 올려, A를 쓰지 않으면 대표 궁궐의 인기도가 0이 된다.
     * A가 {@value #MIN_NAME_TOKEN_LENGTH}자 이상일 때만 쓴다.
     */
    static List<String> placeKeys(String name) {
        List<String> tokens = nameKey(name);
        List<String> keys = new ArrayList<>();
        String compact = String.join("", tokens);
        if (compact.length() >= 2) keys.add(compact);
        if (tokens.size() >= 2) {
            String first = tokens.get(0);
            char last = first.charAt(first.length() - 1);
            if ((last == '과' || last == '와') && first.length() - 1 >= MIN_NAME_TOKEN_LENGTH) {
                keys.add(first.substring(0, first.length() - 1));
            }
        }
        return keys;
    }

    /**
     * 앞부분 묶음은 한글일 때만 쓴다. 영문 라벨은 첫 단어가 일반명사인 경우가 많아
     * "S Tower"·"S Club"·"T World" 같은 행에 걸린다.
     */
    private static boolean hasHangul(CharSequence s) {
        return s.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL);
    }

    /**
     * 비교용 이름 단어 목록(순서 유지). 다음만 걷어낸다.
     * <ul>
     *   <li>괄호 안: 지정명·영문 병기("[유네스코 세계자연유산]", "(Seoul Olympic Stadium)", "(제주)")</li>
     *   <li>도시 이름 단어: "제주 삼성혈", "롯데호텔 서울"의 "제주"·"서울"</li>
     * </ul>
     * 그 밖의 단어는 한 글자·영문이라도 남긴다. "CGV 명동"·"제주국제공항 Gate 13"·"T World"처럼
     * 하위 시설·다른 업장을 가르는 단어가 바로 그런 것들이다.
     */
    static List<String> nameKey(String name) {
        if (name == null || name.isBlank()) return List.of();
        String s = name.toLowerCase(Locale.ROOT)
                .replaceAll("[\\[(（【][^\\])）】]*[\\])）】]", " ")
                .replaceAll("[\\[(（【].*$", " ");   // 닫히지 않은 괄호는 끝까지 버린다
        List<String> tokens = new ArrayList<>();
        for (String part : s.split("[\\s·,./_:|&'’\"-]+")) {
            if (part.isEmpty() || CITY_WORDS.contains(part)) continue;
            tokens.add(part);
        }
        return tokens;
    }

    /** 이름 앞뒤에 붙는 적재 대상 도시 이름(한/영). 단어 단위로만 뺀다("제주흑돼지"는 그대로). */
    private static final Set<String> CITY_WORDS = Set.of(
            "서울", "부산", "인천", "대구", "대전", "광주", "제주", "서귀포", "수원", "강릉", "경주", "전주", "여수",
            "seoul", "busan", "incheon", "daegu", "daejeon", "gwangju", "jeju", "seogwipo", "suwon",
            "gangneung", "gyeongju", "jeonju", "yeosu");

    /**
     * 장소가 받을 수 있는 키({@link #placeKeys}). 찾아가는 장소가 아닌 행은 빈 목록이다.
     * <ul>
     *   <li>Foursquare "States and Municipalities" 카테고리("서울특별시 (Seoul)", "종로구", "제주특별자치도")</li>
     *   <li>버스 정류장·노선("디지털미디어시티 (ID: 80-019)")</li>
     *   <li>Foursquare 행의 키 중 동·구·군·시·읍·면으로 끝나는 것. 엉뚱한 카테고리로 등록된 행정구역이다
     *       ("청담동"(Boutique), "서교동과 동교동 사이"의 "서교동").
     *       TourAPI의 "명동"·"인사동"·"부암동"은 관광공사가 관광지로 올린 행이라 받는다.</li>
     * </ul>
     */
    static List<String> matchKeys(Candidate p) {
        String category = p.category() == null ? "" : p.category().toLowerCase(Locale.ROOT);
        if (category.contains("states and municipalities") || NON_DESTINATION_CATEGORIES.stream().anyMatch(category::contains)) {
            return List.of();
        }
        List<String> keys = placeKeys(p.name());
        if (!"foursquare".equals(p.source())) return keys;
        return keys.stream().filter(k -> !ADMIN_SUFFIX.matcher(k).find()).toList();
    }

    private static final List<String> NON_DESTINATION_CATEGORIES = List.of("bus station", "bus stop", "bus line");

    private static final java.util.regex.Pattern ADMIN_SUFFIX = java.util.regex.Pattern.compile("[동구군시읍면]$");

    /** 0 = 명소·문화·자연, 1 = 숙소·상점·교통, 2 = 식당·카페. 낮을수록 우선. */
    static int categoryTier(String category) {
        if (category == null) return 1;
        String c = category.toLowerCase();
        if (c.startsWith("dining and drinking")) return 2;
        if (c.startsWith("retail") || c.startsWith("travel and transportation")) return 1;
        return 0;
    }

    static boolean isDiningRow(String category) {
        return category != null && category.toLowerCase(Locale.ROOT).startsWith("dining and drinking");
    }

    /**
     * 이름을 비교용 토큰으로 쪼갠다. 공백·괄호·구두점이 경계다.
     *
     * <p>TourAPI 제목은 "성산일출봉 [유네스코 세계자연유산]"처럼 지정명이 대괄호로 붙고,
     * Wikidata는 "성산일출봉 천연보호구역"처럼 분류어가 붙는다. 두 문자열은 서로를 포함하지
     * 않지만 <b>핵심 토큰 "성산일출봉"을 공유</b>한다. 그래서 통짜 비교가 아니라 토큰으로 본다.
     */
    static java.util.Set<String> nameTokens(String name) {
        if (name == null || name.isBlank()) return java.util.Set.of();
        String[] parts = name.toLowerCase().split("[\\s\\[\\](){}<>·,./_-]+");
        java.util.Set<String> tokens = new java.util.HashSet<>();
        for (String part : parts) {
            if (part.length() >= MIN_NAME_TOKEN_LENGTH) tokens.add(part);
        }
        return tokens;
    }

    /**
     * 두 이름이 {@value #MIN_NAME_TOKEN_LENGTH}자 이상 토큰을 <b>공유</b>하는가. "제주"·"산" 같은 짧은 조각은 아무 데나 걸린다.
     *
     * <p>"한쪽이 다른 쪽을 통째로 포함" 규칙은 쓰지 않는다. 행정구역 이름이 상호 안쪽에 박혀 있어
     * "육도담 제주시청본점"이 "제주시"에 걸린다. 필요한 경우는 토큰 비교로 잡힌다("한라산 백록담" ↔ "백록담").
     */
    static boolean namesOverlap(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) return false;

        java.util.Set<String> tokensA = nameTokens(a);
        for (String token : nameTokens(b)) {
            if (tokensA.contains(token)) return true;
        }
        return false;
    }

    static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private List<RegionBox> loadRegionBoxes() {
        List<String> wanted = targetRegions.stream()
                .map(String::trim).filter(r -> !r.isEmpty()).toList();

        List<RegionBox> boxes = new ArrayList<>();
        for (Object[] row : placeRepository.findRegionBoundingBoxes(MIN_PLACES_PER_REGION)) {
            String region = (String) row[0];
            if (!wanted.isEmpty() && !wanted.contains(region)) continue;
            boxes.add(new RegionBox(region,
                    ((Number) row[1]).doubleValue() - BOX_MARGIN_DEG, ((Number) row[2]).doubleValue() - BOX_MARGIN_DEG,
                    ((Number) row[3]).doubleValue() + BOX_MARGIN_DEG, ((Number) row[4]).doubleValue() + BOX_MARGIN_DEG));
        }
        return boxes;
    }

    private List<WikidataItem> fetchItems(RegionBox box, Set<String> foodClasses) throws Exception {
        List<WikidataItem> items = parseItems(sparql(buildSparql(box)));
        List<WikidataItem> detailed = new ArrayList<>(items.size());
        for (int from = 0; from < items.size(); from += DETAIL_BATCH_SIZE) {
            List<WikidataItem> batch = items.subList(from, Math.min(from + DETAIL_BATCH_SIZE, items.size()));
            Map<String, ItemDetail> details = parseDetails(sparql(buildDetailSparql(batch)));
            for (WikidataItem item : batch) {
                detailed.add(item.withDetail(details.getOrDefault(item.id(), ItemDetail.EMPTY), foodClasses));
            }
        }
        return detailed;
    }

    /** 식음료 상위 분류의 하위 분류 전체(QID). 실행당 1회 조회한다. */
    private Set<String> fetchFoodClasses() throws Exception {
        String roots = FOOD_ROOT_CLASSES.stream().map(q -> "wd:" + q).collect(java.util.stream.Collectors.joining(" "));
        JsonNode bindings = objectMapper.readTree(sparql(
                "SELECT DISTINCT ?c WHERE { VALUES ?root { " + roots + " } ?c wdt:P279* ?root }"))
                .path("results").path("bindings");
        Set<String> classes = new HashSet<>();
        for (JsonNode b : bindings) {
            String id = qid(b.path("c").path("value").asText(null));
            if (id != null) classes.add(id);
        }
        if (classes.size() < FOOD_ROOT_CLASSES.size()) {
            throw new IllegalStateException("식음료 분류가 비정상적으로 적다: " + classes.size());
        }
        return classes;
    }

    /**
     * POST로 보낸다. 항목 id 300개를 VALUES에 넣으면 GET URL이 길어진다.
     *
     * <p>지역 하나에 쿼리가 십수 번 나가므로 일시 오류(연결 끊김·429·5xx)는 재시도한다.
     * 한 번 끊긴 걸로 지역 전체를 건너뛰면 그 지역 인기도가 한 달 묵는다.
     */
    private String sparql(String query) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SPARQL_ENDPOINT))
                .header("Accept", "application/sparql-results+json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofString("query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                .build();

        return sendWithRetry(httpClient, request, objectMapper);
    }

    /**
     * 429·5xx·연결 오류·본문이 잘린 200 응답을 재시도한다. 쿼리 시간 제한에 걸린 SPARQL 서비스는
     * 200 상태로 중간까지만 보내 파싱해 봐야 알 수 있다.
     */
    static String sendWithRetry(HttpClient client, HttpRequest request, ObjectMapper objectMapper) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= SPARQL_MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 200) {
                    objectMapper.readTree(response.body());
                    return response.body();
                }
                last = new IllegalStateException("SPARQL 응답 " + status);
                if (status != 429 && status < 500) throw last;   // 쿼리 오류는 재시도해도 같다
            } catch (java.io.IOException e) {
                last = e;
            }
            if (attempt < SPARQL_MAX_ATTEMPTS) {
                log.warn("SPARQL 재시도 {}/{}: {}", attempt, SPARQL_MAX_ATTEMPTS - 1, last.getMessage());
                Thread.sleep(SPARQL_RETRY_BASE_MS * (1L << (attempt - 1)));
            }
        }
        throw last;
    }

    private static final int SPARQL_MAX_ATTEMPTS = 3;
    private static final long SPARQL_RETRY_BASE_MS = 5_000;

    /**
     * bbox 안에서 좌표와 언어판 수를 가진 항목을 가져온다. 언어판이 0인 항목은 매칭돼도
     * 인기도가 0이라 "항목 없음"과 같으니 받지 않는다.
     */
    static String buildSparql(RegionBox box) {
        return String.format(Locale.ROOT, """
                SELECT ?item ?itemLabel ?sitelinks ?lat ?lon WHERE {
                  SERVICE wikibase:box {
                    ?item wdt:P625 ?coord .
                    bd:serviceParam wikibase:cornerSouthWest "Point(%f %f)"^^geo:wktLiteral .
                    bd:serviceParam wikibase:cornerNorthEast "Point(%f %f)"^^geo:wktLiteral .
                  }
                  ?item wikibase:sitelinks ?sitelinks .
                  FILTER(?sitelinks > 0)
                  ?item p:P625/psv:P625 ?cv .
                  ?cv wikibase:geoLatitude ?lat ; wikibase:geoLongitude ?lon .
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "ko,ja,en". }
                }
                """, box.minLon(), box.minLat(), box.maxLon(), box.maxLat());
    }

    /** 항목들의 한국어 별칭과 직속 분류(P31). 별칭×분류 조합으로 행이 늘어나므로 파싱에서 모은다. */
    static String buildDetailSparql(List<WikidataItem> items) {
        String values = items.stream().map(i -> "wd:" + i.id()).collect(java.util.stream.Collectors.joining(" "));
        return """
                SELECT ?item ?alias ?class WHERE {
                  VALUES ?item { %s }
                  OPTIONAL { ?item skos:altLabel ?alias FILTER(LANG(?alias) = "ko") }
                  OPTIONAL { ?item wdt:P31 ?class }
                }
                """.formatted(values);
    }

    /** SPARQL JSON 응답 → 항목 목록. 좌표가 여러 개인 항목은 행이 여러 번 오므로 id로 합친다. */
    List<WikidataItem> parseItems(String json) throws Exception {
        JsonNode bindings = objectMapper.readTree(json).path("results").path("bindings");
        Map<String, WikidataItem> byId = new java.util.LinkedHashMap<>();
        for (JsonNode b : bindings) {
            String id = qid(b.path("item").path("value").asText(null));
            if (id == null) continue;
            Point point = new Point(b.path("lat").path("value").asDouble(), b.path("lon").path("value").asDouble());
            WikidataItem existing = byId.get(id);
            if (existing == null) {
                byId.put(id, new WikidataItem(id, b.path("itemLabel").path("value").asText(""), List.of(),
                        b.path("sitelinks").path("value").asInt(0), List.of(point), false));
            } else if (!existing.points().contains(point)) {
                List<Point> points = new ArrayList<>(existing.points());
                points.add(point);
                byId.put(id, new WikidataItem(id, existing.label(), existing.aliases(), existing.sitelinks(),
                        List.copyOf(points), existing.food()));
            }
        }
        return new ArrayList<>(byId.values());
    }

    /** 별칭·분류 응답 → 항목 id별 상세. */
    Map<String, ItemDetail> parseDetails(String json) throws Exception {
        JsonNode bindings = objectMapper.readTree(json).path("results").path("bindings");
        Map<String, Set<String>> aliases = new HashMap<>();
        Map<String, Set<String>> classes = new HashMap<>();
        for (JsonNode b : bindings) {
            String id = qid(b.path("item").path("value").asText(null));
            if (id == null) continue;
            String alias = b.path("alias").path("value").asText(null);
            if (alias != null && !alias.isBlank()) {
                aliases.computeIfAbsent(id, k -> new java.util.LinkedHashSet<>()).add(alias);
            }
            String cls = qid(b.path("class").path("value").asText(null));
            if (cls != null) classes.computeIfAbsent(id, k -> new HashSet<>()).add(cls);
        }
        Map<String, ItemDetail> result = new HashMap<>();
        Set<String> ids = new HashSet<>(aliases.keySet());
        ids.addAll(classes.keySet());
        for (String id : ids) {
            result.put(id, new ItemDetail(List.copyOf(aliases.getOrDefault(id, Set.of())),
                    Set.copyOf(classes.getOrDefault(id, Set.of()))));
        }
        return result;
    }

    /** ".../entity/Q12345" → "Q12345". 엔티티가 아니면 null. */
    private static String qid(String uri) {
        if (uri == null) return null;
        String id = uri.substring(uri.lastIndexOf('/') + 1);
        return id.startsWith("Q") ? id : null;
    }

    /** 지역 경계 상자. 백분위수로 계산돼 오염 좌표가 제거된 범위에 여유를 더한 것이다. */
    record RegionBox(String region, double minLat, double minLon, double maxLat, double maxLon) {}

    record Point(double lat, double lon) {}

    /** 항목의 한국어 별칭과 직속 분류. */
    record ItemDetail(List<String> aliases, Set<String> classes) {
        static final ItemDetail EMPTY = new ItemDetail(List.of(), Set.of());
    }

    /**
     * @param points 좌표(여러 개일 수 있다)
     * @param food   식음료 업장 분류인가({@link #FOOD_ROOT_CLASSES} 하위)
     */
    record WikidataItem(String id, String label, List<String> aliases, int sitelinks, List<Point> points,
                        boolean food) {

        /** 좌표 하나, 별칭 없음, 식음료 아님. 테스트·간단한 생성용. */
        WikidataItem(String id, String label, int sitelinks, double lat, double lon) {
            this(id, label, List.of(), sitelinks, List.of(new Point(lat, lon)), false);
        }

        List<String> names() {
            List<String> names = new ArrayList<>();
            names.add(label);
            names.addAll(aliases);
            return names;
        }

        double distanceMeters(double lat, double lon) {
            double min = Double.MAX_VALUE;
            for (Point p : points) min = Math.min(min, haversineMeters(lat, lon, p.lat(), p.lon()));
            return min;
        }

        WikidataItem withDetail(ItemDetail detail, Set<String> foodClasses) {
            boolean isFood = detail.classes().stream().anyMatch(foodClasses::contains);
            return new WikidataItem(id, label, detail.aliases(), sitelinks, points, isFood);
        }
    }

    /** 매칭 후보 장소. 엔티티 대신 필요한 컬럼만 읽는다. */
    record Candidate(long id, String name, double lat, double lon, String category, String source) {

        Candidate(long id, String name, double lat, double lon, String category) {
            this(id, name, lat, lon, category, null);
        }

        /** {@code PlaceRepository.findWikidataCandidates} 행(id, name, latitude, longitude, category, source). */
        static Candidate fromRow(Object[] row) {
            return new Candidate(((Number) row[0]).longValue(), (String) row[1],
                    ((Number) row[2]).doubleValue(), ((Number) row[3]).doubleValue(), (String) row[4],
                    row.length > 5 ? (String) row[5] : null);
        }
    }

    /** 항목이 고른 장소. */
    record Assignment(long placeId, WikidataItem item) {}
}
