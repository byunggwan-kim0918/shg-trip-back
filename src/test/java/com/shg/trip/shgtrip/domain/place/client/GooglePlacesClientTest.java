package com.shg.trip.shgtrip.domain.place.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Place Details(New) ID 직조회의 URL/필드마스크 형식과 404 구분 검증.
 * Text Search와 달리 URL은 /v1/places/{bareId}, 마스크는 'places.' 접두어가 없어야 한다.
 */
class GooglePlacesClientTest {

    private static final String DETAILS_BASE = "https://places.googleapis.com/v1/places";

    private MockRestServiceServer server;
    private GooglePlacesClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        GooglePlacesProperties props = new GooglePlacesProperties(
                "test-key",
                "https://places.googleapis.com/v1/places:searchText",
                DETAILS_BASE);
        client = new GooglePlacesClient(restClient, props);
    }

    @Test
    @DisplayName("getPlaceDetails는 /v1/places/{bareId}로 GET하고 접두어 없는 필드마스크를 보낸다")
    void getPlaceDetails_usesCorrectUrlAndMask() {
        server.expect(requestTo(DETAILS_BASE + "/ChIJ_abc"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("X-Goog-Api-Key", "test-key"))
                // Details 마스크는 'places.' 접두어가 없어야 한다
                .andExpect(header("X-Goog-FieldMask", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("places."))))
                .andExpect(header("X-Goog-FieldMask", org.hamcrest.Matchers.containsString("displayName")))
                .andRespond(withSuccess("""
                        {"id":"ChIJ_abc","displayName":{"text":"확정장소"},
                         "formattedAddress":"제주 어딘가","location":{"latitude":33.5,"longitude":126.5},
                         "rating":4.3,"priceLevel":"PRICE_LEVEL_MODERATE"}
                        """, MediaType.APPLICATION_JSON));

        Optional<GooglePlaceDetail> result = client.getPlaceDetails("ChIJ_abc");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("확정장소");
        assertThat(result.get().lat()).isEqualTo(33.5);
        server.verify();
    }

    @Test
    @DisplayName("Details가 404면 PlaceIdNotFoundException을 던진다 (일시 장애와 구분)")
    void getPlaceDetails_throwsNotFoundOn404() {
        server.expect(requestTo(DETAILS_BASE + "/ChIJ_dead"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getPlaceDetails("ChIJ_dead"))
                .isInstanceOf(PlaceIdNotFoundException.class);
    }

    @Test
    @DisplayName("Details가 5xx면 BusinessException(일시 장애)으로 처리한다 (id 리셋 안 함)")
    void getPlaceDetails_throwsBusinessOn5xx() {
        server.expect(requestTo(DETAILS_BASE + "/ChIJ_x"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.getPlaceDetails("ChIJ_x"))
                .isInstanceOf(com.shg.trip.shgtrip.global.exception.BusinessException.class);
    }
}
