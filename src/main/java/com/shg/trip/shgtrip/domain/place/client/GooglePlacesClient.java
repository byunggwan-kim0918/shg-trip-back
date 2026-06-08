package com.shg.trip.shgtrip.domain.place.client;

import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

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
            "places.photos,places.googleMapsUri,places.types";

    /**
     * 장소명으로 Text Search 후 첫 번째 결과의 상세 정보 반환.
     * New API는 Text Search 한 번으로 상세 정보까지 포함 가능 (2-step 불필요).
     */
    public Optional<GooglePlaceDetail> searchAndGetDetail(String query) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri(properties.textSearchUri())
                    .header("X-Goog-Api-Key", properties.apiKey())
                    .header("X-Goog-FieldMask", FIELD_MASK)
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
     * Google Places Photo API (New)로 이미지 바이너리를 다운로드한다.
     * @param photoReference 예: "places/ChIJ.../photos/AXCi2Q..."
     * @return 이미지 바이너리, 실패 시 Optional.empty()
     */
    public Optional<byte[]> downloadPhotoBytes(String photoReference) {
        try {
            byte[] bytes = restClient.get()
                    .uri("https://places.googleapis.com/v1/{photoReference}/media?maxHeightPx=800",
                            photoReference)
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
