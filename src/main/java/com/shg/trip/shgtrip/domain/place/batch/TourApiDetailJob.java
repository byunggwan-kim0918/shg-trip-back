package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 후보 장소를 TourAPI 콘텐츠와 연결하고(searchKeyword2), 공식 이용시간·쉬는날·요금(detailIntro2)을 반영한다.
 *
 * <p>하루 한도는 기능(오퍼레이션)마다 따로 세므로 검색·상세 상한도 따로 둔다. 매칭은 시도 시각, 상세는 조회 시각을
 * 남겨 다음 실행이 이어서 처리한다. 응답을 읽지 못하면 그 자리에서 멈추고 아무것도 기록하지 않는다.
 */
@Slf4j
@Component
@Profile("batch")
public class TourApiDetailJob {

    private static final String BASE_URL = "https://apis.data.go.kr/B551011/KorService2/";
    private static final int PAGE = 100;

    /** contentTypeId → [이용시간, 쉬는날, 요금] 필드. 관광지(12)·쇼핑·음식점은 요금 필드가 없다. */
    static final Map<Integer, String[]> FIELDS = Map.of(
            12, new String[]{"usetime", "restdate", null},
            14, new String[]{"usetimeculture", "restdateculture", "usefee"},
            28, new String[]{"usetimeleports", "restdateleports", "usefeeleports"},
            38, new String[]{"opentime", "restdateshopping", null},
            39, new String[]{"opentimefood", "restdatefood", null});

    /** 원문으로 남기는 추가 필드(메뉴는 가격대 근거 후보). */
    private static final Map<Integer, List<String>> EXTRA_FIELDS = Map.of(
            14, List.of("spendtime"),
            39, List.of("treatmenu", "firstmenu"));

    private final PlaceRepository placeRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${batch.tourapi.service-key:}")
    private String serviceKey;

    @Value("${batch.tourapi.detail.regions:}")
    private String regionsRaw;

    @Value("${batch.tourapi.detail.max-search-calls:950}")
    private int maxSearchCalls;

    @Value("${batch.tourapi.detail.max-detail-calls:950}")
    private int maxDetailCalls;

    private int searchCalls;
    private int detailCalls;

    public TourApiDetailJob(PlaceRepository placeRepository, ObjectMapper objectMapper) {
        this.placeRepository = placeRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    /** 응답을 읽지 못해 실행을 멈춰야 할 때. */
    static final class ApiUnavailable extends Exception {
        ApiUnavailable(String message) {
            super(message);
        }
    }

    public void run() {
        List<String> regions = regionsRaw == null ? List.of()
                : Arrays.stream(regionsRaw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (serviceKey == null || serviceKey.isBlank() || regions.isEmpty()) {
            log.warn("TourAPI 상세: 서비스키 또는 batch.tourapi.detail.regions가 없어 건너뜁니다");
            return;
        }
        searchCalls = 0;
        detailCalls = 0;
        int[] detail = new int[4]; // 조회, 영업시간 반영, 요금 반영, 원문은 있는데 영업시간 판독 실패
        int[] match = new int[2];  // 연결, 못 찾음
        try {
            fetchDetails(regions, detail);
            matchPlaces(regions, match);
            fetchDetails(regions, detail);
        } catch (ApiUnavailable e) {
            log.error("TourAPI 상세 중단: {}", e.getMessage());
        }
        log.info("TourAPI 상세 완료 - 호출 검색 {}건·상세 {}건, 연결 {}건, 못 찾음 {}건, 상세 {}건(영업시간 {}건, 판독 실패 {}건, 요금 {}건)",
                searchCalls, detailCalls, match[0], match[1], detail[0], detail[1], detail[3], detail[2]);
    }

    private boolean searchBudgetLeft() {
        return searchCalls < maxSearchCalls;
    }

    private boolean detailBudgetLeft() {
        return detailCalls < maxDetailCalls;
    }

    private void matchPlaces(List<String> regions, int[] counts) throws ApiUnavailable {
        while (searchBudgetLeft()) {
            List<Object[]> targets = placeRepository.findTourApiMatchTargets(regions, PAGE);
            if (targets.isEmpty()) return;
            for (Object[] row : targets) {
                if (!searchBudgetLeft()) return;
                long id = ((Number) row[0]).longValue();
                String name = (String) row[1];
                String category = (String) row[2];
                double lat = ((Number) row[3]).doubleValue();
                double lng = ((Number) row[4]).doubleValue();

                String keyword = TourApiMatcher.keyword(name);
                var match = keyword.length() < 2 ? java.util.Optional.<TourApiMatcher.Match>empty()
                        : TourApiMatcher.pick(name, category, lat, lng, search(keyword));
                if (match.isPresent()) {
                    TourApiMatcher.Hit hit = match.get().hit();
                    placeRepository.writeTourApiMatch(id, hit.contentId(), (short) hit.contentType());
                    counts[0]++;
                    log.debug("TourAPI 연결: {} → {}({}m)", name, hit.title(),
                            Math.round(match.get().distanceMeters()));
                } else {
                    placeRepository.writeTourApiMatch(id, null, null);
                    counts[1]++;
                }
            }
        }
    }

    private void fetchDetails(List<String> regions, int[] counts) throws ApiUnavailable {
        while (detailBudgetLeft()) {
            List<Object[]> targets = placeRepository.findTourApiDetailTargets(regions, PAGE);
            if (targets.isEmpty()) return;
            for (Object[] row : targets) {
                if (!detailBudgetLeft()) return;
                long id = ((Number) row[0]).longValue();
                String contentId = (String) row[1];
                int type = ((Number) row[2]).intValue();

                JsonNode item = detail(contentId, type);
                Map<String, String> official = officialText(item, type);
                String[] f = FIELDS.get(type);
                String hours = OfficialHoursParser.toOpeningHours(item.path(f[0]).asText(null),
                        item.path(f[1]).asText(null));
                Integer fee = f[2] == null ? null : OfficialHoursParser.admissionFee(item.path(f[2]).asText(null));
                writeDetail(id, official, hours, fee);
                counts[0]++;
                if (hours != null) counts[1]++;
                else if (official.containsKey(f[0])) counts[3]++;
                if (fee != null) counts[2]++;
            }
        }
    }

    static Map<String, String> officialText(JsonNode item, int type) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        for (String f : FIELDS.get(type)) if (f != null) names.add(f);
        names.addAll(EXTRA_FIELDS.getOrDefault(type, List.of()));
        for (String field : names) {
            String text = OfficialHoursParser.clean(item.path(field).asText(null));
            if (text != null) out.put(field, text);
        }
        return out;
    }

    private void writeDetail(long id, Map<String, String> official, String hours, Integer fee) {
        try {
            placeRepository.writeTourApiDetail(id, objectMapper.writeValueAsString(official), hours, fee);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<TourApiMatcher.Hit> search(String keyword) throws ApiUnavailable {
        JsonNode body = call("searchKeyword2", "numOfRows=20&pageNo=1&keyword="
                + URLEncoder.encode(keyword, StandardCharsets.UTF_8));
        List<TourApiMatcher.Hit> hits = new ArrayList<>();
        for (JsonNode i : items(body)) {
            double lat = i.path("mapy").asDouble(0);
            double lng = i.path("mapx").asDouble(0);
            if (lat == 0 || lng == 0) continue;
            hits.add(new TourApiMatcher.Hit(i.path("title").asText(""), i.path("contentid").asText(),
                    i.path("contenttypeid").asInt(0), lat, lng));
        }
        return hits;
    }

    private JsonNode detail(String contentId, int type) throws ApiUnavailable {
        List<JsonNode> list = items(call("detailIntro2", "contentId=" + contentId + "&contentTypeId=" + type));
        return list.isEmpty() ? objectMapper.createObjectNode() : list.get(0);
    }

    private JsonNode call(String operation, String query) throws ApiUnavailable {
        if ("detailIntro2".equals(operation)) detailCalls++;
        else searchCalls++;
        String url = BASE_URL + operation + "?serviceKey=" + serviceKey
                + "&MobileOS=ETC&MobileApp=shgtrip&_type=json&" + query;
        try {
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new ApiUnavailable(operation + " HTTP " + response.statusCode());
            JsonNode root;
            try {
                root = objectMapper.readTree(response.body());
            } catch (Exception e) {
                // 한도 초과·키 오류는 JSON이 아니라 XML로 온다
                String body = response.body();
                throw new ApiUnavailable(operation + " 응답 판독 실패: "
                        + body.substring(0, Math.min(200, body.length())));
            }
            String code = root.path("response").path("header").path("resultCode").asText("");
            if (!"0000".equals(code)) throw new ApiUnavailable(operation + " resultCode=" + code);
            return root;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiUnavailable(operation + " 인터럽트");
        } catch (java.io.IOException e) {
            throw new ApiUnavailable(operation + " " + e.getMessage());
        }
    }

    private static List<JsonNode> items(JsonNode root) {
        JsonNode node = root.path("response").path("body").path("items");
        if (!node.isObject()) return List.of(); // 결과 없음은 빈 문자열로 온다
        JsonNode item = node.path("item");
        if (item.isArray()) {
            List<JsonNode> out = new ArrayList<>();
            item.forEach(out::add);
            return out;
        }
        return item.isObject() ? List.of(item) : List.of();
    }
}
