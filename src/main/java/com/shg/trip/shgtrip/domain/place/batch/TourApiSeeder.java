package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants;
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
    /** 지역 목록에 안 잡히는 레코드(areacode 공란)를 이름으로 역조회할 때 쓴다. */
    private static final String KEYWORD_URL = "https://apis.data.go.kr/B551011/KorService2/searchKeyword2";
    private static final int NUM_OF_ROWS = 100;

    /**
     * contentTypeId → 내부 카테고리 경로 매핑. PlaceCategoryConstants.majorCategory가
     * "landmarks"/"arts and entertainment" 키워드로 ATTRACTION을 판정하므로 그 형식을 따른다.
     * 레포츠(28)는 골프장류가 다시 섞여 들어올 수 있어 기본 대상에서 제외한다.
     */
    private static final Map<Integer, String> CONTENT_TYPE_CATEGORY = Map.of(
            12, "Landmarks and Outdoors > Tourist Attraction",
            14, "Arts and Entertainment > Cultural Center",
            // 39=음식점, 32=숙박. Foursquare 시딩만으로는 제주 식당/숙소가 각각 10곳대라
            // 식사 슬롯·숙소 동선 최적화가 선택지 없이 돌았다(하루 40km 식사 원정, 숙소 복귀 35km).
            // majorCategory가 DINING/LODGING으로 판정하는 경로 형식을 그대로 쓴다.
            39, "Dining and Drinking > Restaurant",
            32, "Travel and Transportation > Lodging"
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

    /**
     * 이미지 없는 관광지를 이름으로 역조회해 채울지 여부. 기본 false — 켜면 대상 1건당
     * 호출 1회가 나가므로 의도적으로 돌릴 때만 켠다.
     */
    @Value("${batch.tourapi.image-backfill.enabled:false}")
    private boolean imageBackfillEnabled;

    /** 1회 실행당 이름 역조회 상한. */
    @Value("${batch.tourapi.image-backfill.limit:300}")
    private int imageBackfillLimit;

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
        int totalBackfilled = 0;
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
                totalBackfilled += result[2];
            }

            // 지역 목록에 안 잡히는 행(areacode 공란)은 여기서 이름으로 따로 채운다.
            if (imageBackfillEnabled) {
                totalBackfilled += backfillImagesByKeyword(region, imageBackfillLimit);
            }
        }
        log.info("TourAPI 시딩 완료 - 신규 {}건, 중복 스킵 {}건, 이미지 복구 {}건",
                totalInserted, totalSkipped, totalBackfilled);
    }

    /** @return [inserted, skipped, backfilled] */
    private int[] seedAreaType(int areaCode, String region, int contentTypeId, String category) {
        int inserted = 0;
        int skipped = 0;
        int backfilled = 0;
        int fetched = 0;

        for (int pageNo = 1; fetched < maxRowsPerType; pageNo++) {
            List<JsonNode> items = fetchPage(areaCode, contentTypeId, pageNo);
            if (items == null) break; // 호출 실패 — 이 타입은 중단(다음 실행에서 재시도)
            if (items.isEmpty()) break;

            for (JsonNode item : items) {
                if (fetched >= maxRowsPerType) break; // 페이지 중간에서도 상한 준수
                fetched++;
                Place place = toPlace(item, region, category, contentTypeId);
                if (place == null) continue;
                if (placeRepository.existsByNameAndRegion(place.getName(), region)) {
                    skipped++;
                    backfilled += backfillImage(place.getName(), region, place.getImageUrl());
                    continue;
                }
                placeRepository.save(place);
                inserted++;
            }
            if (items.size() < NUM_OF_ROWS) break; // 마지막 페이지
        }

        log.info("TourAPI 시딩 - areaCode={}({}), contentTypeId={}: 신규 {}건, 스킵 {}건, 이미지복구 {}건",
                areaCode, region, contentTypeId, inserted, skipped, backfilled);
        return new int[]{inserted, skipped, backfilled};
    }

    /**
     * 이미 있는 행의 비어 있는 {@code image_url}만 TourAPI 대표 이미지로 채운다.
     *
     * <p>시더는 원래 중복을 스킵만 하고 기존 행을 건드리지 않았다. 그 사이 Google 동기화가
     * 이 행들의 이미지를 덮어썼고, 약관에 따라 Google 데이터를 삭제하면서 함께 비었다
     * (실측: TourAPI 제주 관광지 175건 전부 DB에 있는데 이미지 0건). 커버 이미지가 전멸한
     * 직접 원인이라, 시딩 경로에서 같이 복구한다.
     *
     * <p><b>이미지만 건드린다.</b> 이름·좌표·카테고리는 덮어쓰지 않는다 — 기존 행이 Google
     * 동기화로 한글 정식명을 얻은 경우가 있어, TourAPI 표제로 되돌리면 품질이 내려간다.
     *
     * @return 실제로 채운 행 수
     */
    int backfillImage(String name, String region, String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) return 0;
        List<Place> targets = placeRepository.findImagelessByNameAndRegion(name, region);
        if (targets.isEmpty()) return 0;
        for (Place target : targets) {
            target.updateImageUrl(imageUrl);
        }
        placeRepository.saveAll(targets);
        log.debug("TourAPI 이미지 복구: name={}, region={}, {}행", name, region, targets.size());
        return targets.size();
    }

    /**
     * 지역 목록 조회로는 닿지 않는 행의 이미지를 <b>이름 역조회</b>로 채운다.
     *
     * <p>왜 따로 필요한가: TourAPI가 성산일출봉의 {@code areacode}를 빈 값으로 내려준다.
     * 그래서 {@code areaBasedList2&areaCode=39}에는 영영 안 들어온다 — 제주 관광지 totalCount가
     * arrange를 뭘 주든 175로 고정이고, 그 175에 성산일출봉·천지연폭포·산방산이 없다.
     * 같은 레코드를 {@code searchKeyword2}로 치면 이미지까지 정상으로 나온다.
     *
     * <p><b>제목이 정확히 같을 때만</b> 채운다. "성산일출봉"으로 검색하면
     * "성산흑돼지두루치기 성산일출봉점"도 같이 나오는데, 느슨하게 받으면 명소 자리에 식당 사진이
     * 박힌다. DB 이름이 이미 TourAPI 표제(`성산일출봉 [유네스코 세계자연유산]`)와 같은 형식이라
     * 정확일치로 충분하다.
     *
     * <p>Google 백필과 달리 실패 이력을 남기지 않는다. 이 API는 <b>무료 공공데이터</b>이고
     * 이 단계는 플래그로 꺼져 있으며 {@code limit}으로 1회 호출 수가 묶인다 — 재조회가 반복돼도
     * 과금으로 이어지지 않는다(2026-10-08 사고는 호출마다 과금되는 Google에서 났다).
     *
     * @return 채운 행 수
     */
    int backfillImagesByKeyword(String region, int limit) {
        List<Place> targets = placeRepository.findImagelessAttractions(
                region, org.springframework.data.domain.PageRequest.of(0, limit));
        if (targets.isEmpty()) {
            log.info("TourAPI 키워드 백필 - region={}: 대상 없음", region);
            return 0;
        }

        int filled = 0;
        int notFound = 0;
        for (Place place : targets) {
            String imageUrl = fetchImageByExactTitle(place.getName());
            if (imageUrl == null) {
                notFound++;
                continue;
            }
            place.updateImageUrl(imageUrl);
            placeRepository.save(place);
            filled++;
        }
        log.info("TourAPI 키워드 백필 - region={}: 대상 {}건, 복구 {}건, 미발견 {}건",
                region, targets.size(), filled, notFound);
        return filled;
    }

    /** 키워드 검색 결과 중 <b>제목이 정확히 일치하고</b> 대표이미지가 있는 첫 건의 이미지 URL. */
    private String fetchImageByExactTitle(String name) {
        String url = KEYWORD_URL
                + "?serviceKey=" + serviceKey
                + "&MobileOS=ETC&MobileApp=shgtrip&_type=json"
                + "&numOfRows=20&pageNo=1"
                + "&keyword=" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;

            JsonNode itemsNode = objectMapper.readTree(response.body())
                    .path("response").path("body").path("items").path("item");
            if (itemsNode.isMissingNode() || itemsNode.isNull()) return null;
            List<JsonNode> items = itemsNode.isArray()
                    ? java.util.stream.StreamSupport.stream(itemsNode.spliterator(), false).toList()
                    : List.of(itemsNode);

            for (JsonNode item : items) {
                if (!name.equals(item.path("title").asText(null))) continue;
                String image = item.path("firstimage").asText(null);
                if (image != null && !image.isBlank()) return image;
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("TourAPI 키워드 조회 실패: name={}, error={}", name, e.getMessage());
            return null;
        }
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
    private Place toPlace(JsonNode item, String region, String category, int contentTypeId) {
        String name = item.path("title").asText(null);
        double lng = item.path("mapx").asDouble(0); // TourAPI: mapx=경도, mapy=위도
        double lat = item.path("mapy").asDouble(0);
        if (name == null || name.isBlank() || lat == 0 || lng == 0) return null;

        // 관광지 타입(12/14)에 혼입된 교통시설(여객터미널 등)은 관광 스텝 후보가 되면 안 되므로 스킵.
        // 음식점(39)·숙박(32)에는 적용하지 않는다 — "제주공항 흑돼지" 같은 정상 상호가 잘린다.
        // 판정 기준은 PlaceCategoryConstants 한 곳에 있다(시딩·동선·검증이 같은 규칙을 보도록).
        if ((contentTypeId == 12 || contentTypeId == 14)
                && (PlaceCategoryConstants.isPassengerFacility(name) || name.contains("공항"))) {
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
