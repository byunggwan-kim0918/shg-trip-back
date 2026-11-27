package com.shg.trip.shgtrip.domain.routing;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 경로 공급자 HTTP 호출. 주소는 설정의 base URL에서만 만들고 리다이렉트를 따라가지 않는다.
 * 키가 쿼리에 실리는 공급자가 있어 예외 메시지에 URL을 넣지 않는다.
 */
@Component
public class RoutingHttp {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    // 평문 http에 h2c 업그레이드를 붙이면 OTP(Grizzly)가 응답하지 않는다.
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public record Response(int status, String body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** 통신 자체가 실패했다. 메시지에 URL·키를 담지 않는다. */
    public static class CallFailed extends RuntimeException {
        public CallFailed(String reason) {
            super(reason);
        }
    }

    public Response get(URI uri, Map<String, String> headers, Duration timeout) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout).GET();
        headers.forEach(b::header);
        return send(b.build());
    }

    public Response postJson(URI uri, String body, Map<String, String> headers, Duration timeout) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(b::header);
        return send(b.build());
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> r = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(r.statusCode(), r.body());
        } catch (HttpTimeoutException e) {
            throw new CallFailed("timeout");
        } catch (IOException e) {
            throw new CallFailed("io:" + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CallFailed("interrupted");
        }
    }

    /** base URL 뒤에 경로와 쿼리를 붙인다. 경로 조각은 호출부 상수만 넣는다. */
    public static URI uri(String baseUrl, String path, Map<String, String> query) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String q = new LinkedHashMap<>(query).entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        return URI.create(base + path + (q.isEmpty() ? "" : "?" + q));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** 원격 공급자는 https만 받는다. 로컬 OTP처럼 같은 호스트에서 도는 서버만 http를 허용한다. */
    public static void requireSafeBaseUrl(String id, String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalStateException(id + ": base-url이 비었다");
        URI u = URI.create(baseUrl);
        String host = u.getHost();
        boolean local = "localhost".equals(host) || "127.0.0.1".equals(host);
        boolean https = "https".equals(u.getScheme());
        if (host == null || !(https || (local && "http".equals(u.getScheme())))) {
            throw new IllegalStateException(id + ": https 또는 로컬 http 주소만 쓸 수 있다");
        }
        if (u.getUserInfo() != null || u.getQuery() != null) {
            throw new IllegalStateException(id + ": base-url에 사용자 정보·쿼리를 넣지 않는다");
        }
    }
}
