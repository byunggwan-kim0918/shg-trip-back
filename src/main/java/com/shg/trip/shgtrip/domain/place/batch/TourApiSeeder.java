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
            32, "Travel and Transportation > Lodging",
            // 28=레포츠(서핑·카약·패러글라이딩 등 체험). 골프장은 toPlace에서 거른다.
            28, "Sports and Recreation > Outdoor Activity"
    );

    /**
     * 쇼핑(38)은 여행자가 찾아가는 분류만 신분류(lclsSystm)로 골라 받는다. 서울만 4,392건인데 대부분이
     * 안경원·약국 같은 개별 매장(SH04)이라, 통째로 받으면 상한(max-rows-per-type) 안에 시장이 들어오지
     * 못한다. 구분류(cat3)는 신규 레코드에서 비어 있어 쓸 수 없다(2026-10 실측).
     */
    record ShoppingTarget(String lclsSystm2, String category) {}

    static final List<ShoppingTarget> SHOPPING_TARGETS = List.of(
            new ShoppingTarget("SH01", "Retail > Department Store"),
            new ShoppingTarget("SH02", "Retail > Shopping Mall"),
            new ShoppingTarget("SH05", "Retail > Shopping Plaza"),   // 전문상가·쇼핑거리
            new ShoppingTarget("SH06", "Retail > Market"));          // 전통·상설시장 (동문시장·광장시장)

    /**
     * 시딩 대상 → DB region(Foursquare 표준 영어 도시명) 매핑. 키는 "areaCode" 또는 "areaCode-sigunguCode" —
     * 도(道) 안의 도시(강릉·경주·여수)는 광역 코드만으로는 섞이므로 시군구 코드까지 붙인다.
     *
     * <p>조회는 법정동 코드(lDongRegnCd·lDongSignguCd)로 한다. 관광공사 지역코드(areaCode)로 조회하면
     * 신규 레코드 대부분이 빠진다 — areacode가 빈 값으로 내려오기 때문이다(실측 2026-10: 제주 관광지
     * areaCode 162건 vs 법정동 557건, 서울 222 vs 767, 제주 쇼핑 4 vs 395. 동문재래시장·만장굴·비자림·
     * 경복궁·해동용궁사 모두 areacode 공란).
     *
     * <p>설정 토큰은 기존 형식("39", "32-1")을 그대로 받는다. 광주는 법정동 코드가 데이터상
     * "12"로 들어와 있어(표준은 29) 다른 도시와 섞일 수 있으므로 지역코드 조회를 유지한다.
     * 여수도 같은 이상값("12")이지만 시군구(130)까지 좁히면 여수만 나온다(실측 130건, 향일암 포함).
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

        /** 조회 파라미터 묶음 — 시군구가 여럿이면 각각 따로 조회한다. */
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

    @Value("${batch.tourapi.service-key:}")
    private String serviceKey;

    /** 시딩 대상 목록(쉼표 구분, "areaCode" 또는 "areaCode-sigunguCode"). 기본은 제주(39). */
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
        log.info("TourAPI 시딩 완료 - 신규 {}건, 중복 스킵 {}건, 이미지 복구 {}건",
                totalInserted, totalSkipped, totalBackfilled);
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
                if (items == null) break; // 호출 실패 — 이 타입은 중단(다음 실행에서 재시도)
                if (items.isEmpty()) break;

                for (JsonNode item : items) {
                    if (fetched >= maxRowsPerType) break; // 페이지 중간에서도 상한 준수
                    fetched++;
                    Place place = toPlace(item, region, category, contentTypeId);
                    if (place == null) continue;
                    // 같은 이름이라도 대분류가 다르면 다른 장소다 — "경복궁"(고깃집)이 있다는 이유로
                    // 궁궐 경복궁을 건너뛰었다(실측). 대분류는 카테고리 경로의 첫 단계로 본다.
                    if (placeRepository.existsByNameAndRegionAndCategoryStartingWith(
                            place.getName(), region, topLevel(place.getCategory()))) {
                        skipped++;
                        backfilled += backfillImage(place.getName(), region, place.getImageUrl());
                        // 좌표 변환 오류로 비활성화된 같은 행(V40)은 정상 좌표로 되살린다 — 이름이 같아
                        // 새로 넣지 못하므로 여기서 고치지 않으면 영영 빠진다(빛의 벙커 실측).
                        repaired += placeRepository.repairBrokenTourApiCoordinates(
                                place.getName(), region, place.getLatitude(), place.getLongitude());
                        // 예전에 총칭 "Restaurant"로 들어간 음식점은 업종이 정해진 카테고리로 바꾼다
                        // (같은 이름이라 새로 넣을 수 없으므로 여기서만 고칠 수 있다).
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
                log.warn("TourAPI 호출 실패 - HTTP {} (area={}, type={}, page={})",
                        response.statusCode(), area, contentTypeId, pageNo);
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
            log.warn("TourAPI 응답 처리 실패 (area={}, type={}, page={}): {}",
                    area, contentTypeId, pageNo, e.getMessage());
            return null;
        }
    }

    private static final java.util.regex.Pattern GOLF_NAME =
            java.util.regex.Pattern.compile("골프|컨트리클럽|country club|\\bCC\\b|파크골프", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** TourAPI 음식점 구분류(cat3) "카페/전통찻집" — 신분류가 빈 옛 레코드용. */
    private static final String TOUR_API_CAFE_CAT3 = "A05020900";
    static final String RESTAURANT_CATEGORY = "Dining and Drinking > Restaurant";
    static final String CAFE_CATEGORY = "Dining and Drinking > Cafe, Coffee, and Tea House";
    static final String BAKERY_CATEGORY = "Dining and Drinking > Bakery";

    /**
     * 음식점 신분류(lclsSystm3) → 내부 카테고리. 식당은 3단계 이상 경로로 둬서 업종이 정해진 것으로
     * 읽히게 하고(보강 배치 DiningEnrichment.venueOf가 카테고리로 업종을 정한다), 총칭
     * {@link #RESTAURANT_CATEGORY}는 분류 정보가 전혀 없을 때만 남긴다.
     *
     * <p>TourAPI 행에는 개요(overview)가 없어 보강 모델이 이름만 보고 업종을 맞춰야 한다 — 올드북촌이
     * 북카페인지 알 길이 없다. 적재 단계 분류가 유일한 근거다(2026-10 실측 코드표).
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
     * 전부 "Restaurant"로 적재하던 탓에 제주 카페 70여 곳이 식당으로 들어가 카페 슬롯·카페 스톱에서 빠졌다.
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
        // TourAPI는 한국 장소만 내려준다. 좌표 변환 오류(0,0·남중국해 고정점 19.694/117.993)는 국내 범위로 거른다 —
        // 거르지 않으면 깨진 좌표가 새로 들어가거나, 아래 repair가 비활성화된 행을 같은 깨진 좌표로 되살린다.
        if (name == null || name.isBlank() || !isInKorea(lat, lng)) return null;

        // 관광지 타입(12/14)에 혼입된 교통시설(여객터미널 등)은 관광 스텝 후보가 되면 안 되므로 스킵.
        // 음식점(39)·숙박(32)에는 적용하지 않는다 — "제주공항 흑돼지" 같은 정상 상호가 잘린다.
        // 판정 기준은 PlaceCategoryConstants 한 곳에 있다(시딩·동선·검증이 같은 규칙을 보도록).
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
                .category(contentTypeId == 39 ? diningCategory(item, name, category) : category)
                .region(region)
                .country("KR")
                .imageUrl(imageUrl != null && !imageUrl.isBlank() ? imageUrl : null)
                .source("tourapi")
                .active(true)
                .savedAt(java.time.OffsetDateTime.now())
                .build();
    }
}
