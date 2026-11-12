package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Wikidata에서 지역의 <b>대표 명소</b>를 가져와 없는 것만 적재한다.
 *
 * <p>기존 소스(Foursquare·TourAPI)에는 대표 명소가 빠진 지역이 있다. Wikidata는 무료·무인증이고 국가를
 * 가리지 않으며, 언어판 수(sitelinks)가 "그 지역에서 실제로 가는 곳"을 잘 가려낸다.
 *
 * <p>대상: 지역 경계 상자 안에서 관광 성격 클래스({@link #CLASS_CATEGORY})의 인스턴스이면서 언어판이
 * {@code minSitelinks} 이상인 항목. 기존 활성 장소와 이름이 겹치고 2km 안이면 같은 장소로 보고 넣지
 * 않는다. 인기도 연결은 이 시더 다음에 도는 {@link WikidataSitelinksJob}이 한다.
 *
 * <p>클래스는 확실한 것만 둔다. 상위 클래스로 넓게 잡으면(예: 건축물) 아파트·관공서가 같이 들어온다.
 *
 * <p>해외는 {@link Destinations}의 여행지마다 반경 안을 조회하고, 추출기와 같은 규칙으로 가장 가까운 여행지에만
 * 넣는다. 이름은 현지 라벨, {@code name_ko}는 한국어 라벨이다. 도시·행정구역 항목, 국가(P17)가 여행지 나라와
 * 다른 항목(샤먼 반경의 진먼 섬), 홍콩·마카오 항목은 뺀다.
 */
@Slf4j
@Component
@Profile("batch")
public class WikidataLandmarkSeeder {

    private static final String SPARQL_ENDPOINT = "https://query.wikidata.org/sparql";

    /** 지역 경계 상자 산출 최소 장소 수. {@link WikidataSitelinksJob}과 같은 기준. */
    private static final int MIN_PLACES_PER_REGION = 50;
    /**
     * bbox 여유(도, 약 5km). bbox가 장소 좌표의 2~98 백분위라 여유가 없으면 지역 끝 명소가 잘린다.
     * {@link WikidataSitelinksJob}과 같은 값.
     */
    private static final double BOX_MARGIN_DEG = 0.05;
    /** 기존 장소와 같은 곳으로 볼 반경(m). 큰 명소는 출처마다 중심점이 수백 m 다르다. */
    private static final double SAME_PLACE_RADIUS_M = 2_000.0;

    /**
     * Wikidata 클래스(QID) → 내부 카테고리 경로. majorCategory가 ATTRACTION으로 판정하는 형식을 쓴다.
     * 순서가 우선순위다. 한 항목이 여러 클래스에 걸리면 앞의 것을 쓴다(구체적인 것 먼저).
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
            "Q848813", "Landmarks and Outdoors > Historic and Protected Site",           // 정자
            "Q276173", "Landmarks and Outdoors > Historic and Protected Site",           // 누각·파빌리온
            "Q23442", "Landmarks and Outdoors > Island",                                 // 섬
            "Q23397", "Landmarks and Outdoors > Lake",                                   // 호수
            "Q1962840", "Dining and Drinking > Night Market",                            // 야시장
            "Q330284", "Retail > Market",                                                // 시장
            // 구역형 명소. 큰길·거리·동네 분류는 국도·철도·주택가까지 들어와 넣지 않는다.
            "Q676050", "Landmarks and Outdoors > Historic and Protected Site",           // 구시가
            "Q15243209", "Landmarks and Outdoors > Historic and Protected Site",         // 역사 지구
            "Q1574228", "Landmarks and Outdoors > Historic and Protected Site",          // 하나마치
            "Q21000333", "Landmarks and Outdoors > Pedestrian Plaza",                    // 쇼핑 거리
            "Q62685721", "Landmarks and Outdoors > Pedestrian Plaza",                    // 보행자 거리
            "Q1133961", "Landmarks and Outdoors > Pedestrian Plaza",                     // 상업지구
            "Q66364404", "Landmarks and Outdoors > Pedestrian Plaza",                    // 번화가
            "Q202509", "Landmarks and Outdoors > Pedestrian Plaza",                      // 차이나타운
            "Q93338609", "Landmarks and Outdoors > Waterfront",                          // 보드워크
            "Q174782", "Landmarks and Outdoors > Plaza",                                 // 광장
            "Q1208212", "Landmarks and Outdoors > Plaza",                                // 보행자 교차로
            "Q570116", "Landmarks and Outdoors > Tourist Attraction");                   // 관광 명소

    /**
     * 분류로 잡히지 않는 대표 명소 → 적재할 분류({@link #CLASS_CATEGORY} 키). 분류가 큰길·거리뿐이거나 비어 있다.
     * 언어판 수 기준도 보지 않는다.
     */
    static final Map<String, String> EXTRA_LANDMARKS = orderedMap(
            "Q1009423", "Q21000333",    // 왕푸징(베이징)
            "Q11334291", "Q62685721",   // 부이비엔 거리(호찌민)
            "Q10878674", "Q62685721",   // 지우펀 옛거리
            "Q107583845", "Q21000333"); // 나카미세(도쿄 아사쿠사)

    /** 홍콩(Q8646)·마카오(Q14773). 국가(P17)가 중국이라 국가 검사로는 걸러지지 않고, 여행지 목록에 없다. */
    static final List<String> EXCLUDED_AREAS = List.of("Q8646", "Q14773");
    private static final String EXCLUDED_AREA_COUNTRY = "CN";

    static final Map<String, String> COUNTRY_QIDS = Map.of("JP", "Q17", "CN", "Q148", "TW", "Q865", "VN", "Q881");

    private final PlaceRepository placeRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Destinations destinations;
    private final Map<String, List<Landmark>> extraByCountry = new java.util.HashMap<>();

    /** 대상 지역. 비어 있으면 장소 수 기준 전 지역({@link WikidataSitelinksJob}과 같은 설정). */
    @Value("${batch.wikidata.regions:}")
    private List<String> targetRegions;

    /** 적재 기준 언어판 수. 낮추면 동네 공원·작은 절까지 들어온다. */
    @Value("${batch.wikidata.landmarks.min-sitelinks:5}")
    private int minSitelinks;

    /**
     * 지역별 기준("Gyeongju:2,Jeonju:2"). 지방 명소는 대도시보다 언어판이 적어 같은 기준이면 대표 명소가 빠진다.
     * 없는 지역은 {@link #minSitelinks}.
     */
    @Value("${batch.wikidata.landmarks.min-sitelinks-by-region:}")
    private String minSitelinksByRegion;

    /** 경관·단일 명소 여행지(하코네·지우펀·바다링)의 기준. 도시보다 대표 명소의 언어판이 적다. */
    @Value("${batch.wikidata.landmarks.min-sitelinks-scenic:2}")
    private int minSitelinksScenic;

    /** 대상 국가. {@link WikidataSitelinksJob}과 같은 설정. */
    @Value("${batch.wikidata.countries:}")
    private List<String> targetCountries;

    public WikidataLandmarkSeeder(PlaceRepository placeRepository, ObjectMapper objectMapper,
                                  Destinations destinations) {
        this.placeRepository = placeRepository;
        this.objectMapper = objectMapper;
        this.destinations = destinations;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    public void seed() {
        int totalInserted = 0;
        List<String> failedRegions = new ArrayList<>();
        List<String> countries = WikidataSitelinksJob.countryScope(targetCountries, destinations);
        if (countries.isEmpty() || countries.contains(WikidataSitelinksJob.DOMESTIC_COUNTRY)) {
            for (Object[] row : placeRepository.findRegionBoundingBoxes(MIN_PLACES_PER_REGION)) {
                String region = (String) row[0];
                if (destinations.isOverseas(region) || !wanted(region)) continue;
                double minLat = ((Number) row[1]).doubleValue() - BOX_MARGIN_DEG;
                double minLon = ((Number) row[2]).doubleValue() - BOX_MARGIN_DEG;
                double maxLat = ((Number) row[3]).doubleValue() + BOX_MARGIN_DEG;
                double maxLon = ((Number) row[4]).doubleValue() + BOX_MARGIN_DEG;
                totalInserted += seedSafely(region, () -> seedRegion(region, minLat, minLon, maxLat, maxLon),
                        failedRegions);
            }
        }
        List<Destination> overseas = destinations.all().stream()
                .filter(d -> (countries.isEmpty() || countries.contains(d.country())) && wanted(d.name()))
                .toList();
        if (!overseas.isEmpty()) {
            Set<String> knownQids = new HashSet<>(placeRepository.findKnownLandmarkQids());
            for (Destination d : overseas) {
                totalInserted += seedSafely(d.name(), () -> seedDestination(d, knownQids), failedRegions);
            }
        }
        log.info("Wikidata 명소 시딩 완료 - 신규 {}건", totalInserted);
        if (!failedRegions.isEmpty()) throw new BatchStageFailed("Wikidata 명소 시딩 실패 지역 " + failedRegions);
    }

    @FunctionalInterface
    private interface RegionWork {
        int run() throws Exception;
    }

    private int seedSafely(String region, RegionWork work, List<String> failedRegions) {
        try {
            int inserted = work.run();
            Thread.sleep(2_000); // SPARQL 서비스는 연속 호출하면 429를 낸다
            return inserted;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BatchStageFailed("Wikidata 명소 시딩 인터럽트");
        } catch (Exception e) {
            failedRegions.add(region);
            log.warn("Wikidata 명소 시딩 실패: region={}, error={}", region, e.getMessage());
            return 0;
        }
    }

    private boolean wanted(String region) {
        List<String> regions = WikidataSitelinksJob.trimmed(targetRegions);
        return regions.isEmpty() || regions.contains(region);
    }

    private int seedDestination(Destination d, Set<String> knownQids) throws Exception {
        int threshold = minSitelinksFor(d.name(), minSitelinksByRegion, d.isCity() ? minSitelinks : minSitelinksScenic);
        List<Landmark> around = new ArrayList<>(parseOverseas(get(buildAroundSparql(d, threshold)), d.country()));
        Set<String> aroundQids = around.stream().map(Landmark::qid).collect(java.util.stream.Collectors.toSet());
        extraLandmarks(d.country()).stream().filter(lm -> !aroundQids.contains(lm.qid())).forEach(around::add);
        List<Landmark> found = withOverseasNames(around, d.country());
        if (EXCLUDED_AREA_COUNTRY.equals(d.country())) {
            Set<String> excluded = inExcludedAreas(found);
            found = found.stream().filter(lm -> !excluded.contains(lm.qid())).toList();
        }
        return store(d, found, knownQids);
    }

    /**
     * 이 여행지가 가장 가까운 명소만 적재한다. 반경이 겹치는 여행지(오사카·나라)에서 같은 명소를 두 번 넣지 않는다.
     * 이미 있는 QID의 명소 행은 이름·여행지를 맞춘다. 이름이 현지 라벨이 아니면 같은 장소의 FSQ 행과 이름이 달라
     * 후보 중복 제거에서 묶이지 않는다. 넣은 명소는 knownQids와 이번 판정 대상에 바로 더한다.
     */
    int store(Destination d, List<Landmark> found, Set<String> knownQids) {
        List<Landmark> landmarks = found.stream()
                .filter(lm -> destinations.nearest(d.country(), lm.lat(), lm.lon())
                        .map(n -> n.name().equals(d.name())).orElse(false))
                .toList();
        List<WikidataSitelinksJob.Candidate> existing = new ArrayList<>(placeRepository.findWikidataCandidates(d.name())
                .stream().map(WikidataSitelinksJob.Candidate::fromRow).toList());

        int inserted = 0;
        int known = 0;
        int refreshed = 0;
        for (Landmark lm : landmarks) {
            if (knownQids.contains(lm.qid())) {
                refreshed += placeRepository.refreshWikidataLandmark(sourceUrl(lm), lm.label(), lm.nameKo(), d.name());
                known++;
                continue;
            }
            if (alreadyKnownOverseas(lm, existing)) {
                known++;
                continue;
            }
            try {
                Place saved = placeRepository.save(toPlace(lm, d.name()));
                knownQids.add(lm.qid());
                existing.add(new WikidataSitelinksJob.Candidate(saved == null || saved.getId() == null ? 0 : saved.getId(),
                        lm.label(), lm.lat(), lm.lon(), lm.category(), "wikidata"));
                inserted++;
            } catch (Exception e) {
                log.warn("Wikidata 명소 저장 실패: {} ({})", lm.label(), e.getMessage());
            }
        }
        log.info("Wikidata 명소 시딩 - region={}: 반경 내 {}건 중 이 여행지 {}건, 기존 {}건(이름 갱신 {}건), 신규 {}건",
                d.name(), found.size(), landmarks.size(), known, refreshed, inserted);
        return inserted;
    }

    /**
     * 해외 명소가 이미 있는가. 2km 안 같은 대분류 장소가 인기도 매칭에서 이 명소에 붙는 이름이면 있다
     * ({@link OverseasNameKeys#matches}). 조건이 매칭보다 느슨하면 FSQ에 본체 행이 없는 명소가 행도 인기도도 없이 빠진다.
     */
    static boolean alreadyKnownOverseas(Landmark lm, List<WikidataSitelinksJob.Candidate> existing) {
        String major = com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants.majorCategory(lm.category());
        Set<String> names = OverseasNameKeys.itemKeys(lm.names());
        for (WikidataSitelinksJob.Candidate c : existing) {
            if (WikidataSitelinksJob.haversineMeters(lm.lat(), lm.lon(), c.lat(), c.lon()) > SAME_PLACE_RADIUS_M) continue;
            if (!major.equals(com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants
                    .majorCategory(c.category()))) continue;
            if (OverseasNameKeys.matches(OverseasNameKeys.placeKeys(c.name()), names, false)) return true;
        }
        return false;
    }

    private Set<String> inExcludedAreas(List<Landmark> landmarks) throws Exception {
        Set<String> result = new HashSet<>();
        for (int from = 0; from < landmarks.size(); from += EXCLUDED_AREA_BATCH_SIZE) {
            List<String> qids = landmarks.subList(from, Math.min(from + EXCLUDED_AREA_BATCH_SIZE, landmarks.size()))
                    .stream().map(Landmark::qid).toList();
            for (JsonNode b : objectMapper.readTree(postSparql(buildExcludedAreaSparql(qids))).path("results").path("bindings")) {
                String uri = b.path("item").path("value").asText("");
                result.add(uri.substring(uri.lastIndexOf('/') + 1));
            }
        }
        return result;
    }

    private static final int EXCLUDED_AREA_BATCH_SIZE = 300;

    /**
     * 주어진 항목 중 {@link #EXCLUDED_AREAS} 안에 있는 것. 반경 조회에 넣으면 반경 안 모든 항목의 상위 행정구역을
     * 따라 올라가 WDQS 60초 제한에 걸려서, 조회된 명소만 따로 묻는다.
     */
    static String buildExcludedAreaSparql(List<String> qids) {
        return """
                SELECT DISTINCT ?item WHERE {
                  VALUES ?item { %s }
                  VALUES ?area { %s }
                  ?item wdt:P131+ ?area .
                }
                """.formatted(qids.stream().map(q -> "wd:" + q).collect(java.util.stream.Collectors.joining(" ")),
                EXCLUDED_AREAS.stream().map(q -> "wd:" + q).collect(java.util.stream.Collectors.joining(" ")));
    }

    /**
     * 언어별 라벨을 붙인다. 받는 언어의 라벨이 하나도 없는 항목은 이름을 정할 수 없어 버린다.
     * 도시·행정구역 항목과 국가가 여행지 나라와 다른 항목도 버린다. 국가가 없는 항목은 둔다.
     */
    private List<Landmark> withOverseasNames(List<Landmark> landmarks, String country) throws Exception {
        List<WikidataSitelinksJob.WikidataItem> items = landmarks.stream()
                .map(lm -> new WikidataSitelinksJob.WikidataItem(lm.qid(), "", lm.sitelinks(), lm.lat(), lm.lon()))
                .toList();
        Map<String, WikidataSitelinksJob.WikidataItem> named = new LinkedHashMap<>();
        for (WikidataSitelinksJob.WikidataItem item : WikidataSitelinksJob.withNames(
                items, Set.of(), country, this::postSparql, objectMapper)) {
            named.put(item.id(), item);
        }
        List<Landmark> result = new ArrayList<>();
        for (Landmark lm : landmarks) {
            WikidataSitelinksJob.WikidataItem item = named.get(lm.qid());
            if (item == null || item.label() == null || item.label().isBlank() || !inCountry(item, country)) continue;
            List<String> names = new ArrayList<>(item.labels().values());
            names.addAll(item.aliases());
            result.add(new Landmark(lm.qid(), item.label(), lm.sitelinks(), lm.lat(), lm.lon(), lm.category(),
                    country, lm.address(), lm.classRank(), item.koLabel(), List.copyOf(names)));
        }
        return result;
    }

    static boolean inCountry(WikidataSitelinksJob.WikidataItem item, String country) {
        String qid = COUNTRY_QIDS.get(country);
        if (qid == null) throw new IllegalStateException("COUNTRY_QIDS에 없는 여행지 국가: " + country);
        if (item.settlement()) return false;
        return item.countries().isEmpty() || item.countries().contains(qid);
    }

    private static String sourceUrl(Landmark lm) {
        return "https://www.wikidata.org/wiki/" + lm.qid();
    }

    private int seedRegion(String region, double minLat, double minLon, double maxLat, double maxLon)
            throws Exception {
        List<Landmark> landmarks = fetch(minLat, minLon, maxLat, maxLon,
                minSitelinksFor(region, minSitelinksByRegion, minSitelinks));
        List<WikidataSitelinksJob.Candidate> existing = placeRepository.findWikidataCandidates(region).stream()
                .map(WikidataSitelinksJob.Candidate::fromRow)
                .toList();

        // 항목 id가 이미 있으면 이름과 무관하게 같은 장소다. 이름 비교만으로는 2자 이름(종묘·우도)이
        // 자기 자신과도 겹치지 않아 실행할 때마다 다시 들어간다.
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
     * "경복궁역"(역)·"경복궁"(고깃집)에 걸려 궁궐이 들어오지 못한다.
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
        // 빠지므로 "지역 이름"을 주소로 둔다. 좌표가 정확해 길 찾기는 좌표로 한다.
        String address = lm.address() != null && !lm.address().isBlank() ? lm.address() : lm.label() + ", " + region;
        return Place.builder()
                .name(lm.label())
                .nameKo(lm.nameKo())
                .address(address)
                .latitude(BigDecimal.valueOf(lm.lat()))
                .longitude(BigDecimal.valueOf(lm.lon()))
                .category(lm.category())
                .region(region)
                .country(lm.countryCode())
                .source("wikidata")
                .sourceUrl(sourceUrl(lm))
                .wikidataId(lm.qid())
                .wikidataSitelinks(lm.sitelinks())
                .wikidataMatchedAt(OffsetDateTime.now())
                .active(true)
                .savedAt(OffsetDateTime.now())
                .build();
    }

    private List<Landmark> fetch(double minLat, double minLon, double maxLat, double maxLon, int threshold)
            throws Exception {
        return parse(get(buildSparql(minLat, minLon, maxLat, maxLon, threshold)));
    }

    private String get(String query) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SPARQL_ENDPOINT + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                .header("Accept", "application/sparql-results+json")
                .header("User-Agent", WikidataSitelinksJob.USER_AGENT)
                .timeout(Duration.ofSeconds(90))
                .GET()
                .build();
        return WikidataSitelinksJob.sendWithRetry(httpClient, request, objectMapper);
    }

    /** 항목 id 수백 개를 VALUES에 넣는 쿼리라 POST로 보낸다. */
    private String postSparql(String query) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SPARQL_ENDPOINT))
                .header("Accept", "application/sparql-results+json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", WikidataSitelinksJob.USER_AGENT)
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofString("query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                .build();
        return WikidataSitelinksJob.sendWithRetry(httpClient, request, objectMapper);
    }

    static int minSitelinksFor(String region, String byRegion, int fallback) {
        if (byRegion == null || byRegion.isBlank()) return fallback;
        for (String entry : byRegion.split(",")) {
            String[] kv = entry.trim().split(":");
            if (kv.length == 2 && kv[0].trim().equalsIgnoreCase(region)) {
                try {
                    return Integer.parseInt(kv[1].trim());
                } catch (NumberFormatException e) {
                    log.warn("min-sitelinks-by-region 값을 읽지 못해 기본값을 쓴다: {}", entry);
                }
            }
        }
        return fallback;
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

    /** 나라마다 한 번 조회한다. 여행지 배정은 {@link #store}가 가장 가까운 여행지로 거른다. */
    private List<Landmark> extraLandmarks(String country) throws Exception {
        List<Landmark> cached = extraByCountry.get(country);
        if (cached == null) {
            cached = parseOverseas(get(buildExtraSparql()), country);
            extraByCountry.put(country, cached);
        }
        return cached;
    }

    /** {@link #EXTRA_LANDMARKS} 항목을 지정한 분류로 받는다. 응답 형식은 {@link #buildAroundSparql}과 같다. */
    static String buildExtraSparql() {
        String pairs = EXTRA_LANDMARKS.entrySet().stream()
                .map(e -> "(wd:" + e.getKey() + " wd:" + e.getValue() + ")")
                .collect(java.util.stream.Collectors.joining(" "));
        return """
                SELECT ?item ?sitelinks ?lat ?lon ?cls ?addr WHERE {
                  VALUES (?item ?cls) { %s }
                  ?item wikibase:sitelinks ?sitelinks .
                  ?item p:P625/psv:P625 ?cv .
                  ?cv wikibase:geoLatitude ?lat ; wikibase:geoLongitude ?lon .
                  OPTIONAL { ?item wdt:P6375 ?addr . }
                }
                """.formatted(pairs);
    }

    /**
     * 여행지 반경 안의 명소. 라벨·도시 여부·국가는 따로 받는다({@link WikidataSitelinksJob#buildNamesSparql}).
     */
    static String buildAroundSparql(Destination d, int minSitelinks) {
        String classes = String.join(" ", CLASS_CATEGORY.keySet().stream().map(q -> "wd:" + q).toList());
        return String.format(Locale.ROOT, """
                SELECT ?item ?sitelinks ?lat ?lon ?cls ?addr WHERE {
                  SERVICE wikibase:around {
                    ?item wdt:P625 ?coord .
                    bd:serviceParam wikibase:center "Point(%f %f)"^^geo:wktLiteral .
                    bd:serviceParam wikibase:radius "%f" .
                  }
                  VALUES ?cls { %s }
                  ?item wdt:P31/wdt:P279? ?cls .
                  ?item wikibase:sitelinks ?sitelinks .
                  FILTER(?sitelinks >= %d)
                  ?item p:P625/psv:P625 ?cv .
                  ?cv wikibase:geoLatitude ?lat ; wikibase:geoLongitude ?lon .
                  OPTIONAL { ?item wdt:P6375 ?addr . }
                }
                """, d.lng(), d.lat(), d.radiusKm(), classes, minSitelinks);
    }

    /**
     * 반경 조회 응답 → 명소(라벨 없음). 한 항목의 여러 줄은 {@link #parse}처럼 가장 구체적인 클래스로 합친다.
     * 좌표가 여러 개면 나라 전체 여행지 중 거리/반경이 가장 작은 좌표를 쓴다. 조회한 여행지 기준으로 고르면 반경이
     * 겹치는 여행지를 돌 때마다 배정이 오간다.
     */
    List<Landmark> parseOverseas(String json, String country) throws Exception {
        List<String> priority = new ArrayList<>(CLASS_CATEGORY.keySet());
        Map<String, Landmark> byId = new LinkedHashMap<>();
        for (JsonNode b : objectMapper.readTree(json).path("results").path("bindings")) {
            String uri = b.path("item").path("value").asText("");
            String qid = uri.substring(uri.lastIndexOf('/') + 1);
            String clsUri = b.path("cls").path("value").asText("");
            String cls = clsUri.substring(clsUri.lastIndexOf('/') + 1);
            if (!qid.startsWith("Q") || !CLASS_CATEGORY.containsKey(cls)) continue;
            double lat = b.path("lat").path("value").asDouble();
            double lon = b.path("lon").path("value").asDouble();
            Landmark current = byId.get(qid);
            int rank = priority.indexOf(cls);
            if (current != null) {
                if (placementRatio(country, current.lat(), current.lon()) <= placementRatio(country, lat, lon)) {
                    lat = current.lat();
                    lon = current.lon();
                }
                rank = Math.min(rank, current.classRank());
            }
            String address = current != null && current.address() != null ? current.address()
                    : b.path("addr").path("value").asText(null);
            byId.put(qid, new Landmark(qid, "", b.path("sitelinks").path("value").asInt(0), lat, lon,
                    CLASS_CATEGORY.get(priority.get(rank)), null, address, rank));
        }
        return new ArrayList<>(byId.values());
    }

    private double placementRatio(String country, double lat, double lon) {
        return destinations.placement(country, lat, lon).map(Destinations.Placement::ratio).orElse(Double.MAX_VALUE);
    }

    /**
     * SPARQL 응답 → 명소. 한 항목이 여러 클래스·주소로 여러 줄이 되므로 QID로 합치고, 클래스는
     * {@link #CLASS_CATEGORY} 순서상 가장 구체적인 것을 쓴다. 라벨이 없거나(QID 그대로) 국가 코드가
     * 없는 항목은 버린다. 국가는 검색 필터에 쓰인다.
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

    /**
     * @param nameKo 한국어 라벨(해외만, 없으면 null)
     * @param names  같은 장소 판정에 쓰는 언어별 라벨·별칭(해외만)
     */
    record Landmark(String qid, String label, int sitelinks, double lat, double lon,
                    String category, String countryCode, String address, int classRank,
                    String nameKo, List<String> names) {

        Landmark(String qid, String label, int sitelinks, double lat, double lon,
                 String category, String countryCode, String address, int classRank) {
            this(qid, label, sitelinks, lat, lon, category, countryCode, address, classRank, null, List.of(label));
        }
    }

    private static Map<String, String> orderedMap(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return java.util.Collections.unmodifiableMap(m);
    }
}
