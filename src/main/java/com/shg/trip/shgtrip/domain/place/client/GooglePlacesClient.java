package com.shg.trip.shgtrip.domain.place.client;

import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Google Places API (New) 클라이언트.
 * - Text Search (POST): 장소명으로 검색 + 상세 정보 한 번에 조회
 * - 인증: X-Goog-Api-Key 헤더
 * - 필드 마스크: X-Goog-FieldMask 헤더
 * RestClientConfig에서 connectTimeout=5s, readTimeout=10s 설정됨.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GooglePlacesClient {

    private final RestClient restClient;
    private final GooglePlacesProperties properties;

    private static final String FIELD_MASK =
            "places.id,places.displayName,places.formattedAddress,places.location," +
            "places.rating,places.priceLevel,places.regularOpeningHours," +
            "places.photos,places.googleMapsUri,places.types,places.editorialSummary";

    /**
     * 여행지 기준 좌표 조회 전용 마스크. Basic SKU에 types만 추가해, 결과가 행정구역(도시/지역)인지
     * 검증할 수 있게 한다 — "제주" 검색 1위가 명동 식당("제주흑돈")이라 서울 좌표가 30일 캐시되고
     * 모든 거리 검증이 오판한 사고의 재발 방지.
     */
    private static final String LOCATION_TYPED_FIELD_MASK =
            "places.id,places.displayName,places.formattedAddress,places.location,places.types";

    /** 행정구역(도시·지역) 결과로 인정할 Google Place type 신호. */
    private static final List<String> ADMINISTRATIVE_TYPES = List.of(
            "locality", "political", "administrative_area_level_1", "administrative_area_level_2",
            "administrative_area_level_3", "sublocality", "country", "natural_feature",
            "geocode", "neighborhood", "postal_town");

    /**
     * 재동기화(refresh) 전용 Text Search 필드 마스크. editorialSummary를 제외해 Atmosphere 티어를
     * 벗어난다 — editorialSummary는 임베딩 생성(1회)에만 쓰이고 프론트 미표시라 refresh에서 불필요.
     * (신규 첫 매칭은 FIELD_MASK 유지 → 신규 장소 임베딩 품질 보존)
     */
    private static final String SEARCH_REFRESH_FIELD_MASK =
            "places.id,places.displayName,places.formattedAddress,places.location," +
            "places.rating,places.priceLevel,places.regularOpeningHours," +
            "places.photos,places.googleMapsUri,places.types";

    /**
     * Place Details(New) ID 직조회용 필드 마스크. Text Search와 달리 'places.' 접두어가 없다
     * (응답이 단일 place 객체이므로). editorialSummary 제외(refresh 전용).
     */
    private static final String DETAILS_REFRESH_FIELD_MASK =
            "id,displayName,formattedAddress,location," +
            "rating,priceLevel,regularOpeningHours," +
            "photos,googleMapsUri,types";

    /**
     * 장소명으로 Text Search 후 첫 번째 결과의 상세 정보 반환.
     * New API는 Text Search 한 번으로 상세 정보까지 포함 가능 (2-step 불필요).
     */
    public Optional<GooglePlaceDetail> searchAndGetDetail(String query) {
        return searchAndGetDetail(query, FIELD_MASK);
    }

    /**
     * 재동기화 전용 Text Search — editorialSummary 제외 마스크로 한 티어 저렴하게 청구.
     * place_id가 없는(첫 매칭 이력 없는) 장소의 refresh나, Details 404 fallback에 쓰인다.
     */
    public Optional<GooglePlaceDetail> searchForRefresh(String query) {
        return searchAndGetDetail(query, SEARCH_REFRESH_FIELD_MASK);
    }

    /**
     * 여행지 기준 좌표 조회 — <b>행정구역 타입 결과만</b> 반환한다.
     * 최대 5건을 받아 types에 political/locality 계열이 있는 첫 결과를 고르고, 없으면
     * empty를 반환한다(상호명 오매칭 좌표를 기준값으로 쓰지 않기 위함).
     */
    public Optional<GooglePlaceDetail> searchAdministrativeArea(String query) {
        List<Map<String, Object>> places = searchTextRaw(query, LOCATION_TYPED_FIELD_MASK, 5);
        for (Map<String, Object> place : places) {
            Object typesObj = place.get("types");
            if (!(typesObj instanceof List<?> types)) continue;
            boolean administrative = types.stream()
                    .map(t -> String.valueOf(t).toLowerCase())
                    .anyMatch(ADMINISTRATIVE_TYPES::contains);
            if (administrative) {
                return Optional.of(GooglePlaceDetail.from(place));
            }
        }
        log.warn("여행지 기준 좌표: 행정구역 타입 결과 없음 — 기준 좌표로 채택하지 않음 (query='{}')", query);
        return Optional.empty();
    }

    private Optional<GooglePlaceDetail> searchAndGetDetail(String query, String fieldMask) {
        List<Map<String, Object>> places = searchTextRaw(query, fieldMask, 1);
        if (places.isEmpty()) return Optional.empty();
        return Optional.of(GooglePlaceDetail.from(places.get(0)));
    }

    /** Text Search 원시 결과 목록. 호출부가 결과를 직접 검증할 수 있도록 map 그대로 반환한다. */
    private List<Map<String, Object>> searchTextRaw(String query, String fieldMask, int maxResultCount) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri(properties.textSearchUri())
                    .header("X-Goog-Api-Key", properties.apiKey())
                    .header("X-Goog-FieldMask", fieldMask)
                    .header("Content-Type", "application/json")
                    .body(Map.of("textQuery", query, "languageCode", "ko", "maxResultCount", maxResultCount))
                    .retrieve()
                    .body(Map.class);

            if (response == null) return List.of();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> places = (List<Map<String, Object>>) response.get("places");
            if (places == null || places.isEmpty()) {
                log.debug("Places API (New): no results for query='{}'", query);
                return List.of();
            }
            return places;

        } catch (ResourceAccessException e) {
            log.warn("Places API (New) timeout: query='{}'", query);
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        } catch (Exception e) {
            log.error("Places API (New) error: query='{}', error={}", query, e.getMessage());
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        }
    }

    /**
     * 장소명 + 위도경도로 Text Search (location restriction 적용).
     * 기존 위치 ~50m 직사각형 영역 내에서만 검색 → 정확한 장소 매칭.
     * Foursquare 시딩 데이터는 좌표가 정확하므로 0.0005도(~50m) 범위로 충분.
     * @param query 검색어 (장소명)
     * @param latitude 기존 위도
     * @param longitude 기존 경도
     * @return 검색 결과 (첫 번째)
     */
    public Optional<GooglePlaceDetail> searchAndGetDetailWithLocation(String query, double latitude, double longitude) {
        return searchAndGetDetailWithLocation(query, latitude, longitude, FIELD_MASK);
    }

    /**
     * 재동기화 전용 좌표기반 Text Search — editorialSummary 제외 마스크로 한 티어 저렴하게 청구.
     * place_id가 없는 stale 장소의 refresh에 쓰인다.
     */
    public Optional<GooglePlaceDetail> searchForRefreshWithLocation(String query, double latitude, double longitude) {
        return searchAndGetDetailWithLocation(query, latitude, longitude, SEARCH_REFRESH_FIELD_MASK);
    }

    private Optional<GooglePlaceDetail> searchAndGetDetailWithLocation(String query, double latitude, double longitude, String fieldMask) {
        try {
            // 0.0005도 ≈ 50m (적도 기준, 약 100m x 88m 범위)
            double delta = 0.0005;

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri(properties.textSearchUri())
                    .header("X-Goog-Api-Key", properties.apiKey())
                    .header("X-Goog-FieldMask", fieldMask)
                    .header("Content-Type", "application/json")
                    .body(Map.of(
                            "textQuery", query,
                            "languageCode", "ko",
                            "maxResultCount", 1,
                            "locationRestriction", Map.of(
                                    "rectangle", Map.of(
                                            "low", Map.of(
                                                    "latitude", latitude - delta,
                                                    "longitude", longitude - delta
                                            ),
                                            "high", Map.of(
                                                    "latitude", latitude + delta,
                                                    "longitude", longitude + delta
                                            )
                                    )
                            )
                    ))
                    .retrieve()
                    .body(Map.class);

            if (response == null) return Optional.empty();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> places = (List<Map<String, Object>>) response.get("places");
            if (places == null || places.isEmpty()) {
                log.debug("Places API (New): no results for query='{}' within 100m of lat={}, lng={}", query, latitude, longitude);
                return Optional.empty();
            }

            return Optional.of(GooglePlaceDetail.from(places.get(0)));

        } catch (ResourceAccessException e) {
            log.warn("Places API (New) timeout: query='{}' at lat={}, lng={}", query, latitude, longitude);
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        } catch (Exception e) {
            log.error("Places API (New) error: query='{}', error={}", query, e.getMessage());
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        }
    }

    /**
     * Place Details(New) — 저장된 place_id로 상세 정보를 직조회한다 (검색이 아닌 ID 직조회이므로
     * Text Search보다 저렴한 SKU + 오매칭 원천 소멸). refresh 전용 마스크(editorialSummary 제외) 사용.
     * @param placeId Google place_id (bare id, 예: "ChIJ..." — 'places/' 접두어 없음)
     * @throws PlaceIdNotFoundException HTTP 404 (place_id 폐기) — 호출부가 잡아 재매칭
     * @throws BusinessException 타임아웃·5xx 등 일시 장애 (place_id는 유지)
     */
    public Optional<GooglePlaceDetail> getPlaceDetails(String placeId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri(URI.create(properties.detailsBaseUri() + "/" + placeId))
                    .header("X-Goog-Api-Key", properties.apiKey())
                    .header("X-Goog-FieldMask", DETAILS_REFRESH_FIELD_MASK)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        if (res.getStatusCode().value() == 404) {
                            throw new PlaceIdNotFoundException(placeId);
                        }
                        throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
                    })
                    .body(Map.class);

            if (response == null) return Optional.empty();
            // Details 응답은 단일 place 객체 (Text Search의 places[] 래핑이 없음)
            return Optional.of(GooglePlaceDetail.from(response));

        } catch (PlaceIdNotFoundException e) {
            throw e;
        } catch (ResourceAccessException e) {
            log.warn("Place Details (New) timeout: placeId='{}'", placeId);
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Place Details (New) error: placeId='{}', error={}", placeId, e.getMessage());
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        }
    }

    /**
     * Google Places Photo API (New)로 이미지 바이너리를 다운로드한다.
     * @param photoReference 예: "places/ChIJ.../photos/AXCi2Q..."
     * @return 이미지 바이너리, 실패 시 Optional.empty()
     */
    public Optional<byte[]> downloadPhotoBytes(String photoReference) {
        try {
            byte[] bytes = restClient.get()
                    .uri(URI.create("https://places.googleapis.com/v1/" + photoReference + "/media?maxHeightPx=800"))
                    .header("X-Goog-Api-Key", properties.apiKey())
                    .retrieve()
                    .body(byte[].class);
            return Optional.ofNullable(bytes);
        } catch (Exception e) {
            log.warn("Places Photo API 다운로드 실패: photoReference='{}', error={}", photoReference, e.getMessage());
            return Optional.empty();
        }
    }
}
