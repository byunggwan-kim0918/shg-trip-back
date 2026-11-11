package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WikidataRetryAndThresholdTest {

    @Test
    @DisplayName("200인데 본문이 잘린 SPARQL 응답은 다시 받는다")
    @SuppressWarnings("unchecked")
    void retriesTruncatedBody() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> truncated = mock(HttpResponse.class);
        HttpResponse<String> full = mock(HttpResponse.class);
        when(truncated.statusCode()).thenReturn(200);
        when(truncated.body()).thenReturn("{\"results\":{\"bindings\":[{\"item\":");
        when(full.statusCode()).thenReturn(200);
        when(full.body()).thenReturn("{\"results\":{\"bindings\":[]}}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(truncated, full);

        String body = WikidataSitelinksJob.sendWithRetry(client,
                HttpRequest.newBuilder(URI.create("https://query.wikidata.org/sparql")).build(), new ObjectMapper());

        assertThat(body).isEqualTo("{\"results\":{\"bindings\":[]}}");
        verify(client, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @DisplayName("429의 Retry-After(초)를 따르되 2분을 넘기지 않고, 없거나 잘못 적혔으면 0이다")
    @SuppressWarnings("unchecked")
    void retryAfter() {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.headers()).thenReturn(
                java.net.http.HttpHeaders.of(java.util.Map.of("Retry-After", java.util.List.of("30")), (a, b) -> true),
                java.net.http.HttpHeaders.of(java.util.Map.of("Retry-After", java.util.List.of("600")), (a, b) -> true),
                java.net.http.HttpHeaders.of(java.util.Map.of("Retry-After", java.util.List.of("soon")), (a, b) -> true),
                java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true));

        assertThat(WikidataSitelinksJob.retryAfterMs(response)).isEqualTo(30_000);
        assertThat(WikidataSitelinksJob.retryAfterMs(response)).isEqualTo(120_000);
        assertThat(WikidataSitelinksJob.retryAfterMs(response)).isZero();
        assertThat(WikidataSitelinksJob.retryAfterMs(response)).isZero();
    }

    @Test
    @DisplayName("지역별 언어판 기준이 있으면 그 값을, 없거나 잘못 적혔으면 기본값을 쓴다")
    void thresholdByRegion() {
        String byRegion = "Gyeongju:2, Jeonju:2,Yeosu:x";
        assertThat(WikidataLandmarkSeeder.minSitelinksFor("Gyeongju", byRegion, 5)).isEqualTo(2);
        assertThat(WikidataLandmarkSeeder.minSitelinksFor("Jeonju", byRegion, 5)).isEqualTo(2);
        assertThat(WikidataLandmarkSeeder.minSitelinksFor("Yeosu", byRegion, 5)).isEqualTo(5);
        assertThat(WikidataLandmarkSeeder.minSitelinksFor("Seoul", byRegion, 5)).isEqualTo(5);
        assertThat(WikidataLandmarkSeeder.minSitelinksFor("Seoul", "", 5)).isEqualTo(5);
    }

    @Test
    @DisplayName("섬·정자 클래스가 명소 쿼리에 들어간다")
    void queryIncludesIslandAndPavilion() {
        String sparql = WikidataLandmarkSeeder.buildSparql(33.1, 126.1, 33.6, 127.0, 2);
        assertThat(sparql).contains("wd:Q23442", "wd:Q848813", "FILTER(?sitelinks >= 2)");
    }
}
