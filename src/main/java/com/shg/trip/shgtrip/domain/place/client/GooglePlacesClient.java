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
     * Basic Data SKU만 사용하는 필드 마스크 (좌표 확인 등 상세 정보가 불필요한 조회 전용).
     * rating/priceLevel/regularOpeningHours/photos/editorialSummary(Atmosphere·Enterprise SKU)를
     * 빼면 Google이 가장 저렴한 단가로 청구한다.
     */
    private static final String BASIC_FIELD_MASK =
            "places.id,places.displayName,places.formattedAddress,places.location";

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
     * 좌표 확인 등 위치 정보만 필요한 조회 전용 — Basic Data SKU로 청구되어 가장 저렴하다.
     */
    public Optional<GooglePlaceDetail> searchLocationOnly(String query) {
        return searchAndGetDetail(query, BASIC_FIELD_MASK);
    }

    /**
     * 재동기화 전용 Text Search — editorialSummary 제외 마스크로 한 티어 저렴하게 청구.
     * place_id가 없는(첫 매칭 이력 없는) 장소의 refresh나, Details 404 fallback에 쓰인다.
     */
    public Optional<GooglePlaceDetail> searchForRefresh(String query) {
        return searchAndGetDetail(query, SEARCH_REFRESH_FIELD_MASK);
    }

    private Optional<GooglePlaceDetail> searchAndGetDetail(String query, String fieldMask) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri(properties.textSearchUri())
                    .header("X-Goog-Api-Key", properties.apiKey())
                    .header("X-Goog-FieldMask", fieldMask)
                    .header("Content-Type", "application/json")
                    .body(Map.of("textQuery", query, "languageCode", "ko", "maxResultCount", 1))
                    .retrieve()
                    .body(Map.class);

            if (response == null) return Optional.empty();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> places = (List<Map<String, Object>>) response.get("places");
            if (places == null || places.isEmpty()) {
                log.debug("Places API (New): no results for query='{}'", query);
                return Optional.empty();
            }

            return Optional.of(GooglePlaceDetail.from(places.get(0)));

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
