package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wikidata에서 지역의 <b>대표 명소</b>를 가져와 없는 것만 적재한다.
 *
 * <p>왜 필요한가: 기존 소스(Foursquare·TourAPI)에 대표 명소가 통째로 빠져 있었다(2026-10 감사).
 * 경복궁은 러시아어 행만 있었고, 교토의 청수사·금각사·후시미이나리, 도쿄타워·센소지, 제주의 만장굴·비자림이
 * 없었다. 평가 세트 대표 명소 포함률이 0~40%였던 직접 원인이다. Wikidata는 무료·무인증이고 국가를
 * 가리지 않으며, 언어판 수(sitelinks)가 "그 지역에서 실제로 가는 곳"을 잘 가려낸다.
 *
 * <p>대상: 지역 경계 상자 안에서 관광 성격 클래스({@link #CLASS_CATEGORY})의 인스턴스이면서 언어판이
 * {@code minSitelinks} 이상인 항목. 기존 활성 장소와 이름이 겹치고 2km 안이면 같은 장소로 보고 넣지
 * 않는다 — 인기도 연결은 {@link WikidataSitelinksJob}이 따로 한다(이 시더 다음에 돈다).
 *
 * <p>클래스는 확실한 것만 둔다. 상위 클래스로 넓게 잡으면(예: 건축물) 아파트·관공서가 같이 들어온다.
 */
@Slf4j
@Component
@Profile("batch")
public class WikidataLandmarkSeeder {

    private static final String SPARQL_ENDPOINT = "https://query.wikidata.org/sparql";

    /** 지역 경계 상자 산출 최소 장소 수 — {@link WikidataSitelinksJob}과 같은 기준. */
    private static final int MIN_PLACES_PER_REGION = 50;
    /**
     * bbox 여유(도, 약 5km). bbox가 장소 좌표의 2~98 백분위라 지역 끝 명소가 잘린다 — 제주 동쪽 경계가
     * 경도 126.934라 성산일출봉·우도가 빠졌다. {@link WikidataSitelinksJob}과 같은 값.
     */
    private static final double BOX_MARGIN_DEG = 0.05;
    /** 기존 장소와 같은 곳으로 볼 반경(m). 큰 명소는 출처마다 중심점이 수백 m 다르다. */
    private static final double SAME_PLACE_RADIUS_M = 2_000.0;

    /**
     * Wikidata 클래스(QID) → 내부 카테고리 경로. majorCategory가 ATTRACTION으로 판정하는 형식을 쓴다.
     * 순서가 우선순위다 — 한 항목이 여러 클래스에 걸리면 앞의 것을 쓴다(구체적인 것 먼저).
     */
    static final Map<String, String> CLASS_CATEGORY = orderedMap(
            "Q845945", "Community and Government > Spiritual Center > Shrine",          // 신사
            "Q5393308", "Community and Government > Spiritual Center > Buddhist Temple", // 불교 사찰
            "Q44539", "Community and Government > Spiritual Center > Temple",            // 사원
            "Q16970", "Community and Government > Spiritual Center > Church",            // 교회 건물
            "Q16560", "Landmarks and Outdoors > Historic and Protected Site > Palace",   // 궁
            "Q23413", "Landmarks and Outdoors > Historic and Protected Site > Castle",    // 성
            "Q839954", "Landmarks and Outdoors > Historic and Protected Site",           // 유적
            "Q207694", "Arts and Entertainment > Museum > Art Museum",                   // 미술관
            "Q33506", "Arts and Entertainment > Museum",                                 // 박물관
            "Q43501", "Arts and Entertainment > Zoo",                                    // 동물원
            "Q2281788", "Arts and Entertainment > Aquarium",                             // 수족관
            "Q2416723", "Arts and Entertainment > Theme Park",                           // 테마파크
            "Q194195", "Arts and Entertainment > Theme Park",                            // 놀이공원
            "Q46169", "Landmarks and Outdoors > National Park",                          // 국립공원
            "Q1107656", "Landmarks and Outdoors > Garden",                               // 정원
            "Q22698", "Landmarks and Outdoors > Park",                                   // 공원
            "Q40080", "Landmarks and Outdoors > Beach",                                  // 해변
            "Q34038", "Landmarks and Outdoors > Waterfall",                              // 폭포
            "Q35509", "Landmarks and Outdoors > Cave",                                   // 동굴
            "Q8072", "Landmarks and Outdoors > Mountain",                                // 화산
            "Q8502", "Landmarks and Outdoors > Mountain",                                // 산
            "Q1440300", "Landmarks and Outdoors > Scenic Lookout",                       // 전망탑
            "Q12518", "Landmarks and Outdoors > Monument",                               // 탑
            "Q39715", "Landmarks and Outdoors > Lighthouse",                             // 등대
            "Q330284", "Retail > Market",                                                // 시장
            "Q570116", "Landmarks and Outdoors > Tourist Attraction");                   // 관광 명소

    private final PlaceRepository placeRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /** 대상 지역. 비어 있으면 장소 수 기준 전 지역({@link WikidataSitelinksJob}과 같은 설정). */
    @Value("${batch.wikidata.regions:}")
    private List<String> targetRegions;

    /** 적재 기준 언어판 수. 낮추면 동네 공원·작은 절까지 들어온다. */
    @Value("${batch.wikidata.landmarks.min-sitelinks:5}")
    private int minSitelinks;

    public WikidataLandmarkSeeder(PlaceRepository placeRepository, ObjectMapper objectMapper) {
        this.placeRepository = placeRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    public void seed() {
        int totalInserted = 0;
        for (Object[] row : placeRepository.findRegionBoundingBoxes(MIN_PLACES_PER_REGION)) {
            String region = (String) row[0];
            if (!wanted(region)) continue;
            double minLat = ((Number) row[1]).doubleValue() - BOX_MARGIN_DEG;
            double minLon = ((Number) row[2]).doubleValue() - BOX_MARGIN_DEG;
            double maxLat = ((Number) row[3]).doubleValue() + BOX_MARGIN_DEG;
            double maxLon = ((Number) row[4]).doubleValue() + BOX_MARGIN_DEG;
            try {
                totalInserted += seedRegion(region, minLat, minLon, maxLat, maxLon);
                Thread.sleep(2_000); // SPARQL 서비스 예의 — 연속 호출 시 429가 난다
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Wikidata 명소 시딩 실패: region={}, error={}", region, e.getMessage());
            }
        }
        log.info("Wikidata 명소 시딩 완료 - 신규 {}건", totalInserted);
    }

    private boolean wanted(String region) {
        List<String> regions = targetRegions == null ? List.of()
                : targetRegions.stream().map(String::trim).filter(r -> !r.isEmpty()).toList();
        return regions.isEmpty() || regions.contains(region);
    }

    private int seedRegion(String region, double minLat, double minLon, double maxLat, double maxLon)
            throws Exception {
        List<Landmark> landmarks = fetch(minLat, minLon, maxLat, maxLon);
        List<WikidataSitelinksJob.Candidate> existing = placeRepository.findWikidataCandidates(region).stream()
                .map(WikidataSitelinksJob.Candidate::fromRow)
                .toList();

        // 항목 id가 이미 있으면 이름과 무관하게 같은 장소다. 이름 비교만으로는 2자 이름(종묘·우도)이
        // 자기 자신과도 겹치지 않아 실행할 때마다 다시 들어갔다.
        Set<String> knownQids = new HashSet<>(placeRepository.findAllWikidataIds());
        int inserted = 0;
        int known = 0;
        for (Landmark lm : landmarks) {
            if (knownQids.contains(lm.qid()) || alreadyKnown(lm, existing)) {
                known++;
                continue;
            }
            try {
                placeRepository.save(toPlace(lm, region));
                knownQids.add(lm.qid());
                inserted++;
            } catch (Exception e) {
                // 한 건 실패로 지역 전체를 멈추지 않는다
                log.warn("Wikidata 명소 저장 실패: {} ({})", lm.label(), e.getMessage());
            }
        }
        log.info("Wikidata 명소 시딩 - region={}: 조회 {}건, 기존 {}건, 신규 {}건",
                region, landmarks.size(), known, inserted);
        return inserted;
    }

    /**
     * 이름이 겹치고 2km 안에 <b>같은 대분류</b>의 활성 장소가 있으면 같은 장소다. 대분류를 안 보면
     * "경복궁역"(역)·"경복궁"(고깃집)에 걸려 궁궐이 들어오지 못했다(실측).
     */
    static boolean alreadyKnown(Landmark lm, List<WikidataSitelinksJob.Candidate> existing) {
        String major = com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants.majorCategory(lm.category());
        for (WikidataSitelinksJob.Candidate c : existing) {
            if (!major.equals(com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants
                    .majorCategory(c.category()))) continue;
            if (!WikidataSitelinksJob.namesOverlap(lm.label(), c.name())) continue;
            if (WikidataSitelinksJob.haversineMeters(lm.lat(), lm.lon(), c.lat(), c.lon()) <= SAME_PLACE_RADIUS_M) {
                return true;
            }
        }
        return false;
    }

    private Place toPlace(Landmark lm, String region) {
        // Wikidata에는 도로명 주소가 거의 없다. 주소가 비면 방문 가능 판정(isVisitableEntity)에서
        // 빠지므로 "지역 이름"을 주소로 둔다 — 좌표가 정확해 길 찾기는 좌표로 한다.
        String address = lm.address() != null && !lm.address().isBlank() ? lm.address() : lm.label() + ", " + region;
        return Place.builder()
                .name(lm.label())
                .address(address)
                .latitude(BigDecimal.valueOf(lm.lat()))
                .longitude(BigDecimal.valueOf(lm.lon()))
                .category(lm.category())
                .region(region)
                .country(lm.countryCode())
                .source("wikidata")
                .sourceUrl("https://www.wikidata.org/wiki/" + lm.qid())
                .wikidataId(lm.qid())
                .wikidataSitelinks(lm.sitelinks())
                .wikidataMatchedAt(OffsetDateTime.now())
                .active(true)
                .savedAt(OffsetDateTime.now())
                .build();
    }

    private List<Landmark> fetch(double minLat, double minLon, double maxLat, double maxLon) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SPARQL_ENDPOINT + "?query="
                        + URLEncoder.encode(buildSparql(minLat, minLon, maxLat, maxLon, minSitelinks),
                        StandardCharsets.UTF_8)))
                .header("Accept", "application/sparql-results+json")
                .header("User-Agent", WikidataSitelinksJob.USER_AGENT)
                .timeout(Duration.ofSeconds(90))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("SPARQL 응답 " + response.statusCode());
        }
        return parse(response.body());
    }

    static String buildSparql(double minLat, double minLon, double maxLat, double maxLon, int minSitelinks) {
        String classes = String.join(" ", CLASS_CATEGORY.keySet().stream().map(q -> "wd:" + q).toList());
        return """
                SELECT ?item ?itemLabel ?sitelinks ?lat ?lon ?cls ?cc ?addr WHERE {
                  SERVICE wikibase:box {
                    ?item wdt:P625 ?coord .
                    bd:serviceParam wikibase:cornerSouthWest "Point(%f %f)"^^geo:wktLiteral .
                    bd:serviceParam wikibase:cornerNorthEast "Point(%f %f)"^^geo:wktLiteral .
                  }
                  VALUES ?cls { %s }
                  ?item wdt:P31/wdt:P279? ?cls .
                  ?item wikibase:sitelinks ?sitelinks .
                  FILTER(?sitelinks >= %d)
                  ?item p:P625/psv:P625 ?cv .
                  ?cv wikibase:geoLatitude ?lat ; wikibase:geoLongitude ?lon .
                  OPTIONAL { ?item wdt:P17/wdt:P297 ?cc . }
                  OPTIONAL { ?item wdt:P6375 ?addr . }
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "ko,ja,en". }
                }
                """.formatted(minLon, minLat, maxLon, maxLat, classes, minSitelinks);
    }

    /**
     * SPARQL 응답 → 명소. 한 항목이 여러 클래스·주소로 여러 줄이 되므로 QID로 합치고, 클래스는
     * {@link #CLASS_CATEGORY} 순서상 가장 구체적인 것을 쓴다. 라벨이 없거나(QID 그대로) 국가 코드가
     * 없는 항목은 버린다 — 국가는 검색 필터에 쓰인다.
     */
    List<Landmark> parse(String json) throws Exception {
        List<String> priority = new ArrayList<>(CLASS_CATEGORY.keySet());
        Map<String, Landmark> byId = new LinkedHashMap<>();
        for (JsonNode b : objectMapper.readTree(json).path("results").path("bindings")) {
            String uri = b.path("item").path("value").asText("");
            String qid = uri.substring(uri.lastIndexOf('/') + 1);
            String label = b.path("itemLabel").path("value").asText("");
            String cc = b.path("cc").path("value").asText("");
            String clsUri = b.path("cls").path("value").asText("");
            String cls = clsUri.substring(clsUri.lastIndexOf('/') + 1);
            if (!qid.startsWith("Q") || label.isBlank() || label.equals(qid) || cc.isBlank()
                    || !CLASS_CATEGORY.containsKey(cls)) {
                continue;
            }
            Landmark candidate = new Landmark(qid, label, b.path("sitelinks").path("value").asInt(0),
                    b.path("lat").path("value").asDouble(), b.path("lon").path("value").asDouble(),
                    CLASS_CATEGORY.get(cls), cc, b.path("addr").path("value").asText(null), priority.indexOf(cls));
            Landmark current = byId.get(qid);
            if (current == null || candidate.classRank() < current.classRank()) {
                byId.put(qid, candidate);
            }
        }
        return new ArrayList<>(byId.values());
    }

    record Landmark(String qid, String label, int sitelinks, double lat, double lon,
                    String category, String countryCode, String address, int classRank) {}

    private static Map<String, String> orderedMap(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return java.util.Collections.unmodifiableMap(m);
    }
}
