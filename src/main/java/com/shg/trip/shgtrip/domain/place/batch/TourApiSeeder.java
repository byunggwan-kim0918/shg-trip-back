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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 한국관광공사 TourAPI(국문 관광정보) 기반 관광지 시더.
 *
 * <p>Foursquare CSV는 한국 관광지(Landmarks) 커버리지가 구조적으로 빈약하다 — 실측으로 제주
 * 전체 POI 75곳 중 관광지가 7곳(해변 3, 마구간 2, 우물 2)뿐이라, 벡터 검색이 "우물·마구간·야간
 * 골프장"으로 일정을 채우는 저품질 결과가 나왔다. TourAPI는 무료(공공데이터)이고 한글명·좌표·
 * 대표 이미지가 정리돼 있어 관광지 카테고리를 보완하는 데 적합하다.
 *
 * <p>시딩된 행은 embedding IS NULL이므로 같은 배치 실행의 임베딩 단계가 자동으로 잡는다.
 *
 * <p>활성화: BATCH_TOURAPI_ENABLED=true + TOUR_API_SERVICE_KEY(data.go.kr 발급, **URL 인코딩된
 * 키를 그대로** 넣을 것 — 쿼리스트링에 원문 그대로 이어 붙인다).
 */
@Slf4j
@Component
@Profile("batch")
public class TourApiSeeder {

    private static final String BASE_URL = "https://apis.data.go.kr/B551011/KorService2/areaBasedList2";
    private static final int NUM_OF_ROWS = 100;

    /**
     * contentTypeId → 내부 카테고리 경로 매핑. PlaceCategoryConstants.majorCategory가
     * "landmarks"/"arts and entertainment" 키워드로 ATTRACTION을 판정하므로 그 형식을 따른다.
     * 레포츠(28)는 골프장류가 다시 섞여 들어올 수 있어 기본 대상에서 제외한다.
     */
    private static final Map<Integer, String> CONTENT_TYPE_CATEGORY = Map.of(
            12, "Landmarks and Outdoors > Tourist Attraction",
            14, "Arts and Entertainment > Cultural Center"
    );

    /**
     * 관광지 타입(contentTypeId=12)에 혼입되는 교통시설 이름 신호. 여객터미널이 "관광 스텝
     * 90분"으로 배치되는 사고(실측: 성산포항 종합여객터미널) 방지 — 시딩 단계에서 스킵한다.
     * "역"/"항" 단독 글자는 오탐(예: 삼양역사관)이 커서 제외.
     */
    private static final Set<String> TRANSPORT_NAME_KEYWORDS = Set.of(
            "터미널", "여객", "선착장", "부두", "공항", "도선", "카페리"
    );

    /**
     * TourAPI areaCode → DB region(Foursquare 표준 영어 도시명) 매핑.
     * 광역 단위 areaCode가 도시 단위 region과 1:1인 지역만 대상으로 한다 — 강원(32)처럼
     * region이 도시(Gangneung 등)로 쪼개진 지역은 매핑이 어긋나 벡터 검색 지역 필터에서
     * 누락되므로 지원 목록에서 제외한다.
     */
    private static final Map<Integer, String> AREA_CODE_REGION = Map.of(
            1, "Seoul",
            2, "Incheon",
            3, "Daejeon",
            4, "Daegu",
            5, "Gwangju",
            6, "Busan",
            39, "Jeju"
    );

    private final PlaceRepository placeRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${batch.tourapi.service-key:}")
    private String serviceKey;

    /** 시딩 대상 areaCode 목록(쉼표 구분). 기본은 관광지 데이터가 가장 빈약했던 제주(39). */
    @Value("${batch.tourapi.area-codes:39}")
    private String areaCodesRaw;

    /** areaCode×contentType당 최대 수집 행 수 (API 폭주 방지). */
    @Value("${batch.tourapi.max-rows-per-type:500}")
    private int maxRowsPerType;

    public TourApiSeeder(PlaceRepository placeRepository, ObjectMapper objectMapper) {
        this.placeRepository = placeRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public void seed() {
        if (serviceKey == null || serviceKey.isBlank()) {
            log.warn("TOUR_API_SERVICE_KEY가 설정되지 않았습니다. TourAPI 시딩을 건너뜁니다.");
            return;
        }

        int totalInserted = 0;
        int totalSkipped = 0;
        for (String areaRaw : areaCodesRaw.split(",")) {
            String trimmed = areaRaw.trim();
            if (trimmed.isEmpty()) continue;
            int areaCode = Integer.parseInt(trimmed);
            String region = AREA_CODE_REGION.get(areaCode);
            if (region == null) {
                log.warn("지원하지 않는 areaCode={} — region 매핑이 없어 건너뜁니다", areaCode);
                continue;
            }

            for (Map.Entry<Integer, String> type : CONTENT_TYPE_CATEGORY.entrySet()) {
                int[] result = seedAreaType(areaCode, region, type.getKey(), type.getValue());
                totalInserted += result[0];
                totalSkipped += result[1];
            }
        }
        log.info("TourAPI 시딩 완료 - 신규 {}건, 중복 스킵 {}건", totalInserted, totalSkipped);
    }

    /** @return [inserted, skipped] */
    private int[] seedAreaType(int areaCode, String region, int contentTypeId, String category) {
        int inserted = 0;
        int skipped = 0;
        int fetched = 0;

        for (int pageNo = 1; fetched < maxRowsPerType; pageNo++) {
            List<JsonNode> items = fetchPage(areaCode, contentTypeId, pageNo);
            if (items == null) break; // 호출 실패 — 이 타입은 중단(다음 실행에서 재시도)
            if (items.isEmpty()) break;

            for (JsonNode item : items) {
                if (fetched >= maxRowsPerType) break; // 페이지 중간에서도 상한 준수
                fetched++;
                Place place = toPlace(item, region, category);
                if (place == null) continue;
                if (placeRepository.existsByNameAndRegion(place.getName(), region)) {
                    skipped++;
                    continue;
                }
                placeRepository.save(place);
                inserted++;
            }
            if (items.size() < NUM_OF_ROWS) break; // 마지막 페이지
        }

        log.info("TourAPI 시딩 - areaCode={}({}), contentTypeId={}: 신규 {}건, 스킵 {}건",
                areaCode, region, contentTypeId, inserted, skipped);
        return new int[]{inserted, skipped};
    }

    /** @return 아이템 목록. 호출/파싱 실패 시 null (빈 페이지와 구분). */
    private List<JsonNode> fetchPage(int areaCode, int contentTypeId, int pageNo) {
        String url = BASE_URL
                + "?serviceKey=" + serviceKey
                + "&MobileOS=ETC&MobileApp=shgtrip&_type=json"
                + "&numOfRows=" + NUM_OF_ROWS
                + "&pageNo=" + pageNo
                + "&areaCode=" + areaCode
                + "&contentTypeId=" + contentTypeId
                + "&arrange=Q"; // 대표이미지 있는 항목 우선, 수정일순

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("TourAPI 호출 실패 - HTTP {} (areaCode={}, type={}, page={})",
                        response.statusCode(), areaCode, contentTypeId, pageNo);
                return null;
            }
            // 키 오류 등은 XML로 응답한다 — JSON 파싱 실패로 감지
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode itemsNode = root.path("response").path("body").path("items").path("item");
            if (itemsNode.isMissingNode() || itemsNode.isNull()) return List.of();
            if (!itemsNode.isArray()) return List.of(itemsNode); // 단건이면 객체로 옴
            List<JsonNode> items = new java.util.ArrayList<>();
            itemsNode.forEach(items::add);
            return items;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("TourAPI 응답 처리 실패 (areaCode={}, type={}, page={}): {}",
                    areaCode, contentTypeId, pageNo, e.getMessage());
            return null;
        }
    }

    /** TourAPI 아이템 → Place. 좌표/이름이 없으면 null. */
    private Place toPlace(JsonNode item, String region, String category) {
        String name = item.path("title").asText(null);
        double lng = item.path("mapx").asDouble(0); // TourAPI: mapx=경도, mapy=위도
        double lat = item.path("mapy").asDouble(0);
        if (name == null || name.isBlank() || lat == 0 || lng == 0) return null;

        // 관광지 타입에 혼입된 교통시설(여객터미널 등)은 관광 스텝 후보가 되면 안 되므로 스킵
        final String nameForFilter = name;
        if (TRANSPORT_NAME_KEYWORDS.stream().anyMatch(nameForFilter::contains)) {
            log.debug("TourAPI 교통시설 스킵: {}", name);
            return null;
        }

        String address = item.path("addr1").asText("");
        if (address.isBlank()) address = region;
        String imageUrl = item.path("firstimage").asText(null);

        // savedAt은 @Column(nullable=false)인데 @Builder.Default 초기값이 없어 builder에서
        // 명시적으로 채워야 한다(ItineraryDataMapper의 fallback Place 생성 관례와 동일) —
        // 안 채우면 insert 시 NOT NULL 위반. active도 Lombok @Builder.Default가 builder
        // 경유 시 무시될 수 있어 안전하게 true를 직접 지정한다.
        return Place.builder()
                .name(name)
                .address(address)
                .latitude(BigDecimal.valueOf(lat))
                .longitude(BigDecimal.valueOf(lng))
                .category(category)
                .region(region)
                .country("KR")
                .imageUrl(imageUrl != null && !imageUrl.isBlank() ? imageUrl : null)
                .source("tourapi")
                .active(true)
                .savedAt(java.time.OffsetDateTime.now())
                .build();
    }
}
