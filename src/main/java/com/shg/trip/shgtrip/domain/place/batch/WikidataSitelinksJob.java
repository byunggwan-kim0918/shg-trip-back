package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 장소에 Wikidata 인기도 신호(언어판 수)를 채우는 배치.
 *
 * <p>Google 리뷰 수를 약관상 보관할 수 없게 되면서 들여온 대체 신호다(Maps 약관 3.2.3(a) —
 * 평점·리뷰·좌표·사진의 로컬 캐싱 금지, 예외는 Place ID). Wikidata는 CC0라 저장 제약이 없고,
 * SPARQL이 무인증·무료이며, TourAPI와 달리 해외를 덮는다.
 *
 * <h2>왜 지역당 쿼리 1회인가</h2>
 * 장소마다 API를 부르면 5만 건이 넘고 레이트 리밋에 걸린다(Wikipedia Pageviews로 시도했을 때
 * 429가 즉시 났다). {@code SERVICE wikibase:box}로 지역 경계 상자 안의 항목을 한 번에 받아
 * 메모리에서 좌표 매칭하면 지역당 1회로 끝난다.
 *
 * <h2>좌표와 이름을 모두 본다</h2>
 * 표제가 서로 다르다 — Wikidata "성산일출봉 천연보호구역"(33.4581, 126.9425) ↔
 * 우리 DB "성산일출봉 [유네스코 세계자연유산]"(33.4591, 126.9396), 약 290m 차이. 그래서
 * 좌표가 필요하다.
 *
 * <p>처음에는 좌표만 썼는데 실측 결과가 쓰레기였다 — 제주월드컵경기장 옆 국수집이 경기장
 * 점수(28)를 가져갔고 정방폭포 하나가 주변 식당 7곳에 뿌려졌다. 명소 주변에는 식당이
 * 밀집하기 때문이다. 이름 포함 여부를 게이트로 추가하고, 한 항목은 한 장소만 차지하게 했다.
 *
 * <h2>매칭 실패도 기록한다</h2>
 * {@code wikidata_matched_at}을 반드시 찍는다. 이게 없으면 "Wikidata에 없는 장소"를 배치가
 * 돌 때마다 다시 조회한다 — 2026-10-08 Google 과금 사고(₩36만)가 정확히 그 구조였다.
 *
 * <p>{@code BatchEnrichScheduler}와 같은 {@code @Profile("batch")} + {@code BatchJobRunner}
 * 패턴. HTTP 호출과 응답 매핑을 분리해 후자만 단위 테스트한다.
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
    private static final String USER_AGENT =
            "shg-trip/1.0 (travel itinerary planner; contact: byunggwankim0918@gmail.com)";

    /**
     * 좌표 매칭 반경(m).
     *
     * <p>처음에 300m로 뒀다가 실측에서 명소를 놓쳤다 — 천지연폭포가 우리 DB와 Wikidata 좌표가
     * 약 550m 차이다. 큰 자연물은 "중심점"을 어디로 잡느냐가 출처마다 다르다.
     *
     * <p>이름 게이트가 있으므로 넓혀도 안전하다. 반경의 역할은 "같은 이름의 다른 지역 항목"을
     * 거르는 것뿐인데 그건 보통 수 km 떨어져 있다. 2km면 큰 명소의 좌표 편차는 받아내면서
     * 동명이소는 여전히 걸러낸다.
     */
    private static final double MATCH_RADIUS_M = 2_000.0;

    /** 지역 bbox 산출에 필요한 최소 장소 수. 표본이 적으면 백분위수가 의미 없다. */
    private static final int MIN_PLACES_PER_REGION = 50;

    /**
     * 이름 포함 비교에 요구하는 최소 길이. 한국어 지명은 2~3자가 흔해 짧으면 오매칭이 난다
     * ("산"이 "한라산"에 걸리는 식). 3자면 "우도"는 못 잡지만 오매칭보다 낫다.
     */
    private static final int MIN_NAME_TOKEN_LENGTH = 3;

    private static final double EARTH_RADIUS_M = 6_371_000.0;

    private final PlaceRepository placeRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /** 지역당 매칭 대상 상한. 한 번에 다 돌리지 않고 나눠 실행할 수 있게 한다. */
    @Value("${batch.wikidata.limit-per-region:5000}")
    private int limitPerRegion;

    /** 대상 지역. 비어 있으면 장소 수 기준 전 지역. */
    @Value("${batch.wikidata.regions:}")
    private List<String> targetRegions;

    public WikidataSitelinksJob(PlaceRepository placeRepository, ObjectMapper objectMapper) {
        this.placeRepository = placeRepository;
        this.objectMapper = objectMapper;
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

        int totalMatched = 0;
        int totalChecked = 0;
        for (RegionBox box : boxes) {
            try {
                int[] result = processRegion(box);
                totalChecked += result[0];
                totalMatched += result[1];
            } catch (Exception e) {
                // 한 지역이 실패해도 나머지는 계속한다(TourApiSeeder와 같은 방침).
                log.warn("Wikidata 매칭 실패: region={}, error={}", box.region(), e.getMessage());
            }
        }
        log.info("Wikidata 인기도 매칭 완료: 확인 {}건, 매칭 {}건", totalChecked, totalMatched);
    }

    private int[] processRegion(RegionBox box) throws Exception {
        List<Place> targets = placeRepository.findWikidataUnmatched(
                box.region(), PageRequest.of(0, limitPerRegion));
        if (targets.isEmpty()) {
            log.info("Wikidata 매칭 건너뜀: region={} (대상 없음)", box.region());
            return new int[]{0, 0};
        }

        List<WikidataItem> items = fetchItems(box);
        log.info("Wikidata 조회: region={}, 항목 {}건, 매칭대상 {}곳", box.region(), items.size(), targets.size());

        // 한 항목이 여러 장소에 뿌려지지 않게 한다. 실측에서 정방폭포 하나가 반경 안
        // 식당 7곳에 붙었다 — 같은 명소가 일곱 번 추천되는 것과 같다.
        java.util.Set<String> claimed = new java.util.HashSet<>();
        int matched = 0;
        for (Place place : targets) {
            WikidataItem best = findBestMatch(place, items);
            if (best != null && claimed.add(best.id())) {
                place.markWikidataMatch(best.id(), best.sitelinks());
                matched++;
            } else {
                // 항목이 없다는 사실 자체가 신호다 — sitelinks 0으로 "유명하지 않음"을 기록한다.
                place.markWikidataMatch(null, 0);
            }
        }
        placeRepository.saveAll(targets);
        log.info("Wikidata 매칭: region={}, {}/{}곳", box.region(), matched, targets.size());
        return new int[]{targets.size(), matched};
    }

    /**
     * 장소에 맞는 Wikidata 항목. <b>좌표와 이름을 모두</b> 요구한다.
     *
     * <p>처음에는 "반경 300m 안에서 가장 가까운 것"만 썼다. 실측해 보니 쓰레기가 나왔다 —
     * 제주월드컵경기장(sitelinks 28) 옆 국수집이 경기장 점수를 가져갔고, 정방폭포 하나가
     * 반경 안 식당 7곳에 뿌려졌다. 명소 주변에는 식당이 밀집하므로 좌표만으로는 못 가린다.
     *
     * <p>그래서 이름을 게이트로 둔다. 표제가 서로 달라도 핵심 토큰은 겹친다 —
     * "성산일출봉 [유네스코 세계자연유산]" ↔ "성산일출봉 천연보호구역"은 정규화 후 한쪽이
     * 다른 쪽을 포함한다. 반면 "앙끄레국수 서귀포본점" ↔ "제주월드컵경기장"은 겹치지 않는다.
     *
     * <p>좌표는 여전히 필요하다. 같은 이름의 다른 지역 항목을 걸러내는 역할이다.
     */
    static WikidataItem findBestMatch(Place place, List<WikidataItem> items) {
        if (place.getLatitude() == null || place.getLongitude() == null) return null;
        double lat = place.getLatitude().doubleValue();
        double lon = place.getLongitude().doubleValue();
        String placeName = place.getName();
        if (placeName == null || placeName.isBlank()) return null;

        WikidataItem best = null;
        double bestDistance = Double.MAX_VALUE;
        for (WikidataItem item : items) {
            double d = haversineMeters(lat, lon, item.lat(), item.lon());
            if (d > MATCH_RADIUS_M || d >= bestDistance) continue;
            if (!namesOverlap(placeName, item.label())) continue;
            bestDistance = d;
            best = item;
        }
        return best;
    }

    /**
     * 이름을 비교용 토큰으로 쪼갠다 — 공백·괄호·구두점이 경계다.
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
     * 두 이름이 {@value #MIN_NAME_TOKEN_LENGTH}자 이상 토큰을 <b>공유</b>하는가.
     *
     * <p>토큰 최소 길이를 두는 이유는 "제주"·"산" 같은 짧은 조각이 아무 데나 걸리기 때문이다.
     *
     * <p>처음에는 "한쪽이 다른 쪽을 통째로 포함" 규칙을 함께 뒀다가 뺐다. 행정구역 이름이
     * 상호 안쪽에 박혀 있어 엉뚱하게 걸렸다 — "육도담 제주시청본점"이 "제주시"(sitelinks 51)에,
     * "서귀포시립기당미술관"이 "서귀포시"(41)에 매칭됐다. 토큰 비교만으로도 필요한 경우는
     * 잡힌다("한라산 백록담" ↔ "백록담"은 토큰 "백록담"을 공유한다).
     *
     * <p>이 규칙이 거르는 실측 사례: "앙끄레국수 서귀포본점" ↔ "제주월드컵경기장"(공유 토큰
     * 없음), "서귀포유람선" ↔ "서귀포해양도립공원"(토큰이 각각 하나뿐이고 서로 다름).
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
                    ((Number) row[1]).doubleValue(), ((Number) row[2]).doubleValue(),
                    ((Number) row[3]).doubleValue(), ((Number) row[4]).doubleValue()));
        }
        return boxes;
    }

    private List<WikidataItem> fetchItems(RegionBox box) throws Exception {
        String query = buildSparql(box);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SPARQL_ENDPOINT + "?query="
                        + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                .header("Accept", "application/sparql-results+json")
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(90))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("SPARQL 응답 " + response.statusCode());
        }
        return parseItems(response.body());
    }

    /** bbox 안에서 좌표와 언어판 수를 가진 항목을 모두 가져온다. */
    static String buildSparql(RegionBox box) {
        return """
                SELECT ?item ?itemLabel ?sitelinks ?lat ?lon WHERE {
                  SERVICE wikibase:box {
                    ?item wdt:P625 ?coord .
                    bd:serviceParam wikibase:cornerSouthWest "Point(%f %f)"^^geo:wktLiteral .
                    bd:serviceParam wikibase:cornerNorthEast "Point(%f %f)"^^geo:wktLiteral .
                  }
                  ?item wikibase:sitelinks ?sitelinks .
                  ?item p:P625/psv:P625 ?cv .
                  ?cv wikibase:geoLatitude ?lat ; wikibase:geoLongitude ?lon .
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "ko,ja,en". }
                }
                """.formatted(box.minLon(), box.minLat(), box.maxLon(), box.maxLat());
    }

    /** SPARQL JSON 응답 → 항목 목록. HTTP와 분리해 단위 테스트가 가능하게 둔다. */
    List<WikidataItem> parseItems(String json) throws Exception {
        JsonNode bindings = objectMapper.readTree(json).path("results").path("bindings");
        List<WikidataItem> items = new ArrayList<>();
        for (JsonNode b : bindings) {
            String uri = b.path("item").path("value").asText(null);
            if (uri == null) continue;
            String id = uri.substring(uri.lastIndexOf('/') + 1);   // .../entity/Q12345 → Q12345
            if (!id.startsWith("Q")) continue;
            items.add(new WikidataItem(
                    id,
                    b.path("itemLabel").path("value").asText(""),
                    b.path("sitelinks").path("value").asInt(0),
                    b.path("lat").path("value").asDouble(),
                    b.path("lon").path("value").asDouble()));
        }
        return items;
    }

    /** 지역 경계 상자. 백분위수로 계산돼 오염 좌표가 제거된 범위다. */
    record RegionBox(String region, double minLat, double minLon, double maxLat, double maxLon) {}

    /** Wikidata 항목 한 건. */
    record WikidataItem(String id, String label, int sitelinks, double lat, double lon) {}
}
