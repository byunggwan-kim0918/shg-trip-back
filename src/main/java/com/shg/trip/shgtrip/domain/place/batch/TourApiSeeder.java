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
 * <p>Foursquare CSV는 한국 관광지(Landmarks) 커버리지가 구조적으로 빈약해 벡터 검색이 저가치 POI로
 * 일정을 채운다. TourAPI는 무료(공공데이터)이고 한글명·좌표·대표 이미지가 정리돼 있다.
 *
 * <p>시딩된 행은 embedding IS NULL이므로 같은 배치 실행의 임베딩 단계가 잡는다.
 *
 * <p>TOUR_API_SERVICE_KEY(data.go.kr 발급)는 URL 인코딩된 키 그대로 둔다. 쿼리스트링에 원문 그대로 이어 붙인다.
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
            // 39=음식점, 32=숙박. Foursquare만으로는 식당·숙소 선택지가 모자란다.
            // majorCategory가 DINING/LODGING으로 판정하는 경로 형식을 그대로 쓴다.
            39, "Dining and Drinking > Restaurant",
            32, "Travel and Transportation > Lodging",
            // 28=레포츠(서핑·카약·패러글라이딩 등 체험). 골프장은 toPlace에서 거른다.
            28, "Sports and Recreation > Outdoor Activity"
    );

    /**
     * 쇼핑(38)은 여행자가 찾아가는 분류만 신분류(lclsSystm)로 골라 받는다. 대부분이 개별 매장(SH04)이라
     * 통째로 받으면 상한(max-rows-per-type) 안에 시장이 못 들어온다. 구분류(cat3)는 신규 레코드에서 비어 있다.
     */
    record ShoppingTarget(String lclsSystm2, String category) {}

    static final List<ShoppingTarget> SHOPPING_TARGETS = List.of(
            new ShoppingTarget("SH01", "Retail > Department Store"),
            new ShoppingTarget("SH02", "Retail > Shopping Mall"),
            new ShoppingTarget("SH05", "Retail > Shopping Plaza"),   // 전문상가·쇼핑거리
            new ShoppingTarget("SH06", "Retail > Market"));          // 전통·상설시장 (동문시장·광장시장)

    /**
     * 시딩 대상 → DB region(Foursquare 표준 영어 도시명) 매핑. 키는 "areaCode" 또는 "areaCode-sigunguCode"이다.
     * 도(道) 안의 도시(강릉·경주·여수)는 광역 코드만으로는 섞이므로 시군구 코드까지 붙인다.
     *
     * <p>조회는 법정동 코드(lDongRegnCd·lDongSignguCd)로 한다. 신규 레코드 대부분은 areacode가 빈 값으로
     * 내려와 관광공사 지역코드(areaCode)로 조회하면 빠진다.
     *
     * <p>설정 토큰은 기존 형식("39", "32-1")을 그대로 받는다. 광주는 법정동 코드가 데이터상
     * "12"로 들어와 있어(표준은 29) 다른 도시와 섞일 수 있으므로 지역코드 조회를 유지한다.
     * 여수도 같은 이상값("12")이지만 시군구(130)까지 좁히면 여수만 나온다.
     */
    private static final Map<String, Area> AREAS = Map.ofEntries(
            Map.entry("1", Area.ldong("11", "Seoul")),
            Map.entry("2", Area.ldong("28", "Incheon")),
            Map.entry("3", Area.ldong("30", "Daejeon")),
            Map.entry("4", Area.ldong("27", "Daegu")),
            Map.entry("5", Area.legacy(5, "Gwangju")),
            Map.entry("6", Area.ldong("26", "Busan")),
            Map.entry("39", Area.ldong("50", "Jeju")),
            Map.entry("31-13", Area.ldong("41", "Suwon", "111", "113", "115", "117")), // 수원 4개 구
            Map.entry("32-1", Area.ldong("51", "Gangneung", "150")),
            Map.entry("35-2", Area.ldong("47", "Gyeongju", "130")),
            Map.entry("37-12", Area.ldong("52", "Jeonju", "111", "113")),              // 전주 완산·덕진
            Map.entry("38-13", Area.ldong("12", "Yeosu", "130"))
    );

    /**
     * 시딩 단위. {@code lDongRegnCd}가 있으면 법정동 조회(시군구 목록이 비면 시도 전체),
     * 없으면 {@code legacyAreaCode}로 지역코드 조회.
     */
    record Area(String lDongRegnCd, List<String> lDongSignguCds, Integer legacyAreaCode, String region) {
        static Area ldong(String regn, String region, String... signgu) {
            return new Area(regn, List.of(signgu), null, region);
        }

        static Area legacy(int areaCode, String region) {
            return new Area(null, List.of(), areaCode, region);
        }

        /** 조회 파라미터 묶음. 시군구가 여럿이면 각각 따로 조회한다. */
        List<String> queries() {
            if (lDongRegnCd == null) return List.of("&areaCode=" + legacyAreaCode);
            if (lDongSignguCds.isEmpty()) return List.of("&lDongRegnCd=" + lDongRegnCd);
            return lDongSignguCds.stream()
                    .map(sg -> "&lDongRegnCd=" + lDongRegnCd + "&lDongSignguCd=" + sg)
                    .toList();
        }
    }

    /** "39" 또는 "32-1" 형태의 설정값 → Area. 매핑에 없으면 null. */
    static Area parseArea(String token) {
        if (token == null) return null;
        return AREAS.get(token.trim());
    }

    private final PlaceRepository placeRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private int failedListCalls;

    @Value("${batch.tourapi.service-key:}")
    private String serviceKey;

    /** 시딩 대상 목록(쉼표 구분, "areaCode" 또는 "areaCode-sigunguCode"). 기본은 제주(39). */
    @Value("${batch.tourapi.area-codes:39}")
    private String areaCodesRaw;

    /** areaCode×contentType당 최대 수집 행 수 (API 폭주 방지). */
    @Value("${batch.tourapi.max-rows-per-type:500}")
    private int maxRowsPerType;

    /**
     * 이미지 없는 관광지를 이름으로 역조회해 채울지 여부. 켜면 대상 1건당 호출 1회가 나간다.
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
        failedListCalls = 0;

        int totalInserted = 0;
        int totalSkipped = 0;
        int totalBackfilled = 0;
        for (String areaRaw : areaCodesRaw.split(",")) {
            if (areaRaw.isBlank()) continue;
            Area area = parseArea(areaRaw);
            if (area == null) {
                log.warn("지원하지 않는 시딩 대상={} — region 매핑이 없어 건너뜁니다", areaRaw.trim());
                continue;
            }
            String region = area.region();

            for (Map.Entry<Integer, String> type : CONTENT_TYPE_CATEGORY.entrySet()) {
                int[] result = seedAreaType(area, type.getKey(), type.getValue(), "");
                totalInserted += result[0];
                totalSkipped += result[1];
                totalBackfilled += result[2];
            }
            for (ShoppingTarget target : SHOPPING_TARGETS) {
                int[] result = seedAreaType(area, 38, target.category(),
                        "&lclsSystm1=SH&lclsSystm2=" + target.lclsSystm2());
                totalInserted += result[0];
                totalSkipped += result[1];
                totalBackfilled += result[2];
            }

            // 지역 목록에 안 잡히는 행(areacode 공란)은 여기서 이름으로 따로 채운다.
            if (imageBackfillEnabled) {
                totalBackfilled += backfillImagesByKeyword(region, imageBackfillLimit);
            }
        }
        log.info("TourAPI 시딩 완료 - 신규 {}건, 중복 스킵 {}건, 이미지 복구 {}건, 목록 조회 실패 {}건",
                totalInserted, totalSkipped, totalBackfilled, failedListCalls);
        // 이미지 보충 조회는 실패해도 다음 실행이 다시 채워 목록 조회만 센다.
        if (failedListCalls > 0) throw new BatchStageFailed("TourAPI 목록 조회 " + failedListCalls + "건 실패");
    }

    /**
     * @param extraQuery 분류 필터 등 추가 파라미터(없으면 빈 문자열)
     * @return [inserted, skipped, backfilled]
     */
    private int[] seedAreaType(Area area, int contentTypeId, String category, String extraQuery) {
        String region = area.region();
        int inserted = 0;
        int skipped = 0;
        int backfilled = 0;
        int repaired = 0;
        int refined = 0;

        for (String areaQuery : area.queries()) {
            int fetched = 0;
            for (int pageNo = 1; fetched < maxRowsPerType; pageNo++) {
                List<JsonNode> items = fetchPage(areaQuery + extraQuery, contentTypeId, pageNo);
                if (items == null) break; // 호출 실패. 이 타입은 중단하고 다음 실행에서 재시도한다
                if (items.isEmpty()) break;

                for (JsonNode item : items) {
                    if (fetched >= maxRowsPerType) break; // 페이지 중간에서도 상한 준수
                    fetched++;
                    Place place = toPlace(item, region, category, contentTypeId);
                    if (place == null) continue;
                    // 같은 이름이라도 대분류가 다르면 다른 장소다("경복궁" 고깃집과 궁궐).
                    // 대분류는 카테고리 경로의 첫 단계로 본다.
                    if (placeRepository.existsByNameAndRegionAndCategoryStartingWith(
                            place.getName(), region, topLevel(place.getCategory()))) {
                        skipped++;
                        backfilled += backfillImage(place.getName(), region, place.getImageUrl());
                        // 좌표 변환 오류로 비활성화된 같은 행(V40)은 이름이 같아 새로 넣지 못하므로
                        // 여기서 정상 좌표로 되살리지 않으면 영영 빠진다.
                        repaired += placeRepository.repairBrokenTourApiCoordinates(
                                place.getName(), region, place.getLatitude(), place.getLongitude());
                        // 총칭 "Restaurant"로 들어간 음식점은 같은 이름이라 새로 넣을 수 없어 여기서만
                        // 업종 카테고리로 고칠 수 있다.
                        if (contentTypeId == 39 && !RESTAURANT_CATEGORY.equals(place.getCategory())) {
                            refined += placeRepository.refineGenericTourApiDiningCategory(
                                    place.getName(), region, place.getCategory());
                        }
                        continue;
                    }
                    placeRepository.save(place);
                    inserted++;
                }
                if (items.size() < NUM_OF_ROWS) break; // 마지막 페이지
            }
        }

        log.info("TourAPI 시딩 - {}{}({}), contentTypeId={}: 신규 {}건, 스킵 {}건, 이미지복구 {}건, 좌표복구 {}건, 업종갱신 {}건",
                area.queries(), extraQuery, region, contentTypeId, inserted, skipped, backfilled, repaired, refined);
        return new int[]{inserted, skipped, backfilled};
    }

    /**
     * 이미 있는 행의 비어 있는 {@code image_url}만 TourAPI 대표 이미지로 채운다.
     *
     * <p><b>이미지만 건드린다.</b> 기존 행이 한글 정식명을 가진 경우가 있어 TourAPI 표제로
     * 되돌리면 품질이 내려간다.
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
     * <p>TourAPI는 일부 레코드(성산일출봉 등)의 {@code areacode}를 빈 값으로 내려줘 {@code areaBasedList2}에는
     * 들어오지 않지만, {@code searchKeyword2}로는 이미지까지 나온다.
     *
     * <p><b>제목이 정확히 같을 때만</b> 채운다. 검색 결과에 "성산흑돼지두루치기 성산일출봉점"도 섞여
     * 느슨하게 받으면 명소 자리에 식당 사진이 박힌다.
     *
     * <p>실패 이력을 남기지 않는다. 무료 공공데이터이고 플래그와 {@code limit}으로 호출 수가 묶여
     * 재조회가 과금으로 이어지지 않는다.
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
                String image = secureImageUrl(item.path("firstimage").asText(null));
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
    private List<JsonNode> fetchPage(String areaQuery, int contentTypeId, int pageNo) {
        String area = areaQuery;
        String url = BASE_URL
                + "?serviceKey=" + serviceKey
                + "&MobileOS=ETC&MobileApp=shgtrip&_type=json"
                + "&numOfRows=" + NUM_OF_ROWS
                + "&pageNo=" + pageNo
                + areaQuery
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
                failedListCalls++;
                log.warn("TourAPI 호출 실패 - HTTP {} (area={}, type={}, page={})",
                        response.statusCode(), area, contentTypeId, pageNo);
                return null;
            }
            // 키 오류 등은 XML로 응답해 JSON 파싱 실패로 감지한다
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode itemsNode = root.path("response").path("body").path("items").path("item");
            if (itemsNode.isMissingNode() || itemsNode.isNull()) return List.of();
            if (!itemsNode.isArray()) return List.of(itemsNode); // 단건이면 객체로 옴
            List<JsonNode> items = new java.util.ArrayList<>();
            itemsNode.forEach(items::add);
            return items;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failedListCalls++;
            return null;
        } catch (Exception e) {
            failedListCalls++;
            log.warn("TourAPI 응답 처리 실패 (area={}, type={}, page={}): {}",
                    area, contentTypeId, pageNo, e.getMessage());
            return null;
        }
    }

    private static final java.util.regex.Pattern GOLF_NAME =
            java.util.regex.Pattern.compile("골프|컨트리클럽|country club|\\bCC\\b|파크골프", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** TourAPI 음식점 구분류(cat3) "카페/전통찻집". 신분류가 빈 옛 레코드용. */
    private static final String TOUR_API_CAFE_CAT3 = "A05020900";
    static final String RESTAURANT_CATEGORY = "Dining and Drinking > Restaurant";
    static final String CAFE_CATEGORY = "Dining and Drinking > Cafe, Coffee, and Tea House";
    static final String BAKERY_CATEGORY = "Dining and Drinking > Bakery";

    /**
     * 음식점 신분류(lclsSystm3) → 내부 카테고리. 식당은 3단계 이상 경로로 둬서 업종이 정해진 것으로
     * 읽히게 하고(보강 배치 DiningEnrichment.venueOf가 카테고리로 업종을 정한다), 총칭
     * {@link #RESTAURANT_CATEGORY}는 분류 정보가 전혀 없을 때만 남긴다.
     *
     * <p>TourAPI 행에는 개요(overview)가 없어 보강 모델이 이름만으로는 업종을 맞출 수 없다.
     * 적재 단계 분류가 유일한 근거다.
     */
    static final java.util.Map<String, String> DINING_LCLS3 = java.util.Map.ofEntries(
            java.util.Map.entry("FD020100", RESTAURANT_CATEGORY + " > Asian Restaurant > Chinese Restaurant"),
            java.util.Map.entry("FD020200", RESTAURANT_CATEGORY + " > Asian Restaurant > Japanese Restaurant"),
            java.util.Map.entry("FD020300", RESTAURANT_CATEGORY + " > Western Restaurant"),
            java.util.Map.entry("FD020400", RESTAURANT_CATEGORY + " > International Restaurant"),
            java.util.Map.entry("FD020500", RESTAURANT_CATEGORY + " > Fusion Restaurant"),
            java.util.Map.entry("FD030100", BAKERY_CATEGORY),
            java.util.Map.entry("FD030200", RESTAURANT_CATEGORY + " > Fast Food Restaurant"),
            java.util.Map.entry("FD030300", RESTAURANT_CATEGORY + " > Fried Chicken Joint"),
            java.util.Map.entry("FD030400", RESTAURANT_CATEGORY + " > Asian Restaurant > Korean Restaurant > Bunsik Restaurant"),
            java.util.Map.entry("FD030500", "Dining and Drinking > Food Truck"),
            java.util.Map.entry("FD030600", "Dining and Drinking > Snack Place"),
            java.util.Map.entry("FD040100", "Dining and Drinking > Bar > Pub"),
            java.util.Map.entry("FD040200", "Dining and Drinking > Bar > Beer Bar"),
            java.util.Map.entry("FD040300", "Dining and Drinking > Bar > Nightclub"),
            java.util.Map.entry("FD040400", "Dining and Drinking > Bar > Traditional Pub"),
            java.util.Map.entry("FD040500", "Dining and Drinking > Bar"),
            java.util.Map.entry("FD050100", CAFE_CATEGORY),
            java.util.Map.entry("FD050200", CAFE_CATEGORY + " > Tea Room"),
            java.util.Map.entry("FD050300", CAFE_CATEGORY));

    /**
     * 음식점(39)의 업종 카테고리. 신분류(lclsSystm) → 구분류(cat3 카페) → 이름(베이커리) 순으로 본다.
     */
    static String diningCategory(JsonNode item, String name, String defaultCategory) {
        String lcls3 = item.path("lclsSystm3").asText("");
        if (DINING_LCLS3.containsKey(lcls3)) return DINING_LCLS3.get(lcls3);
        // 한식(FD01)은 소분류가 업종이 아니라 인증 구분(관광식당·모범음식점)이라 한식당으로 둔다.
        if (item.path("lclsSystm2").asText("").equals("FD01")) {
            return RESTAURANT_CATEGORY + " > Asian Restaurant > Korean Restaurant";
        }
        if (TOUR_API_CAFE_CAT3.equals(item.path("cat3").asText(""))) return CAFE_CATEGORY;
        String lower = name == null ? "" : name.toLowerCase();
        boolean bakeryName = lower.contains("베이커리") || lower.contains("제과") || lower.contains("빵집")
                || lower.contains("bakery");
        if (bakeryName) return BAKERY_CATEGORY;
        return defaultCategory;
    }

    /** "Landmarks and Outdoors > Tourist Attraction" → "Landmarks and Outdoors" */
    static String topLevel(String category) {
        if (category == null) return "";
        int i = category.indexOf(" >");
        return i >= 0 ? category.substring(0, i) : category;
    }

    static boolean isInKorea(double lat, double lng) {
        return lat >= 33.0 && lat <= 38.7 && lng >= 124.5 && lng <= 131.9;
    }

    /** TourAPI 아이템 → Place. 좌표/이름이 없으면 null. */
    private Place toPlace(JsonNode item, String region, String category, int contentTypeId) {
        String name = item.path("title").asText(null);
        double lng = item.path("mapx").asDouble(0); // TourAPI: mapx=경도, mapy=위도
        double lat = item.path("mapy").asDouble(0);
        // TourAPI는 한국 장소만 내려주므로 좌표 변환 오류(0,0·남중국해 고정점 19.694/117.993)는 국내 범위로 거른다.
        // 거르지 않으면 깨진 좌표가 새로 들어가거나, 아래 repair가 비활성화된 행을 같은 깨진 좌표로 되살린다.
        if (name == null || name.isBlank() || !isInKorea(lat, lng)) return null;

        // 관광지 타입에 섞인 교통시설(여객터미널 등)은 관광 스텝 후보가 되면 안 된다.
        // 음식점(39)·숙박(32)에 적용하면 "제주공항 흑돼지" 같은 정상 상호가 잘린다.
        if ((contentTypeId == 12 || contentTypeId == 14 || contentTypeId == 28)
                && (PlaceCategoryConstants.isPassengerFacility(name) || name.contains("공항"))) {
            log.debug("TourAPI 교통시설 스킵: {}", name);
            return null;
        }
        // 레포츠의 골프장, 쇼핑의 도매시장은 여행 일정의 방문지가 아니다.
        if (contentTypeId == 28 && GOLF_NAME.matcher(name).find()) return null;
        if (contentTypeId == 38 && name.contains("도매")) return null;

        String address = item.path("addr1").asText("");
        if (address.isBlank()) address = region;
        String imageUrl = secureImageUrl(item.path("firstimage").asText(null));

        // savedAt은 nullable=false인데 @Builder.Default 초기값이 없어 안 채우면 insert가 NOT NULL로 실패한다.
        // active도 builder 경유 시 @Builder.Default가 무시될 수 있어 직접 지정한다.
        return Place.builder()
                .name(name)
                .address(address)
                .latitude(BigDecimal.valueOf(lat))
                .longitude(BigDecimal.valueOf(lng))
                .category(contentTypeId == 39 ? diningCategory(item, name, category) : category)
                .region(region)
                .country("KR")
                .imageUrl(imageUrl != null && !imageUrl.isBlank() ? imageUrl : null)
                .source("tourapi")
                .tourapiContentId(item.path("contentid").asText(null))
                .tourapiContentType((short) contentTypeId)
                .active(true)
                .savedAt(java.time.OffsetDateTime.now())
                .build();
    }

    /** TourAPI는 같은 이미지 서버를 http로도 주는데, https 페이지에 그대로 넣으면 혼합 콘텐츠로 막힐 수 있다. */
    static String secureImageUrl(String url) {
        if (url == null || url.isBlank()) return url;
        return url.startsWith("http://") ? "https://" + url.substring("http://".length()) : url;
    }
}
