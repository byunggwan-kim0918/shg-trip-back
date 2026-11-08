package com.shg.trip.shgtrip.domain.auth.service.oauth;

import com.shg.trip.shgtrip.global.config.OAuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OAuthRevokeTest {

    private MockRestServiceServer server;
    private RestClient restClient;
    private final OAuthProperties props = new OAuthProperties(
            provider("https://kauth.kakao.com/oauth/token"),
            provider("https://oauth2.googleapis.com/token"),
            provider("https://nid.naver.com/oauth2.0/token"));

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
    }

    @Test
    void 카카오는_사용자_토큰으로_연결_끊기를_호출한다() {
        server.expect(requestTo("https://kapi.kakao.com/v1/user/unlink"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess("{\"id\":1}", MediaType.APPLICATION_JSON));

        new KakaoOAuthStrategy(restClient, props).revoke("tok");

        server.verify();
    }

    @Test
    void 네이버는_토큰_엔드포인트에_delete_grant를_보낸다() {
        server.expect(requestTo("https://nid.naver.com/oauth2.0/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(java.util.Map.of(
                        "grant_type", "delete", "client_id", "id", "client_secret", "secret",
                        "access_token", "tok", "service_provider", "NAVER")))
                .andRespond(withSuccess("{\"result\":\"success\"}", MediaType.APPLICATION_JSON));

        new NaverOAuthStrategy(restClient, props).revoke("tok");

        server.verify();
    }

    @Test
    void 구글은_revoke_엔드포인트에_토큰을_보낸다() {
        server.expect(requestTo("https://oauth2.googleapis.com/revoke"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(java.util.Map.of("token", "tok")))
                .andRespond(withSuccess());

        new GoogleOAuthStrategy(restClient, props).revoke("tok");

        server.verify();
    }

    @Test
    void 제공자가_실패해도_예외를_던지지_않는다() {
        server.expect(requestTo("https://oauth2.googleapis.com/revoke"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatCode(() -> new GoogleOAuthStrategy(restClient, props).revoke("tok"))
                .doesNotThrowAnyException();
    }

    private static OAuthProperties.ProviderProperties provider(String tokenUri) {
        return new OAuthProperties.ProviderProperties("id", "secret", tokenUri, "https://userinfo", "https://cb");
    }
}
