package com.shg.trip.shgtrip.domain.planning.curate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryRepository;
import com.shg.trip.shgtrip.domain.planning.service.GenerationResultStore;
import com.shg.trip.shgtrip.domain.planning.service.GenerationStream;
import com.shg.trip.shgtrip.domain.planning.service.OptimizedGenerationExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 공개 샘플 일정을 운영 파이프라인으로 생성한다. 요청은 컨트롤러를 거치지 않아 생성 쿼터를 쓰지 않는다.
 * <pre>
 * java -jar app.jar --spring.profiles.active=prod,curate --curate.user-id=7 --curate.requests-url=https://...
 * </pre>
 * 요청 목록 {@code [{"key":"jeju/2nights-3days-food","request":{ItineraryGenerateRequest}}]}은 requests-url(S3 presigned)이나
 * requests-b64로 받는다. ECS 환경변수 덮어쓰기는 합계 8KB라 수십 건이면 URL을 쓴다.
 * 결과는 로그의 {@code CURATE key=... itineraryId=...} 줄로 남기고, 실패가 하나라도 있으면 종료 코드 1이다.
 */
@Slf4j
@Component
@Profile("curate")
@RequiredArgsConstructor
public class CurationRunner implements CommandLineRunner {

    record Item(String key, ItineraryGenerateRequest request) {
    }

    private final OptimizedGenerationExecutor executor;
    private final GenerationResultStore resultStore;
    private final ItineraryRepository itineraryRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationContext context;

    @Value("${curate.user-id}")
    private Long userId;

    @Value("${curate.requests-url:}")
    private String requestsUrl;

    @Value("${curate.requests-b64:}")
    private String requestsB64;

    @Value("${curate.timeout-minutes:10}")
    private long timeoutMinutes;

    @Override
    public void run(String... args) throws Exception {
        List<Item> items = objectMapper.readValue(loadRequests(), new TypeReference<>() {
        });
        log.info("CURATE 시작 - {}건, userId={}", items.size(), userId);

        int failed = 0;
        int needsAdjustment = 0;
        for (Item item : items) {
            Long itineraryId = generate(item);
            if (itineraryId == null) failed++;
            Itinerary.QualityStatus quality = itineraryId == null ? null : itineraryRepository.findById(itineraryId)
                    .map(Itinerary::getQualityStatus).orElse(null);
            if (quality == Itinerary.QualityStatus.NEEDS_ADJUSTMENT) needsAdjustment++;
            log.info("CURATE key={} itineraryId={} status={} quality={}", item.key(), itineraryId,
                    itineraryId == null ? "FAIL" : "OK", quality);
        }

        log.info("CURATE 완료 - 성공 {}건(조정 필요 {}건), 실패 {}건", items.size() - failed, needsAdjustment, failed);
        int exitCode = failed == 0 ? 0 : 1;
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    private String loadRequests() throws Exception {
        if (requestsUrl.isBlank()) {
            return new String(Base64.getDecoder().decode(requestsB64), StandardCharsets.UTF_8);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> res = client.send(HttpRequest.newBuilder(URI.create(requestsUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) throw new IllegalStateException("요청 목록 다운로드 실패: HTTP " + res.statusCode());
            return res.body();
        }
    }

    /** 생성기는 @Async라 스트림이 닫힐 때까지 기다린다. 한 건씩 돌려 Sonnet·DB 부하를 운영 요청 수준으로 둔다. */
    private Long generate(Item item) throws InterruptedException {
        String jobId = UUID.randomUUID().toString();
        CountDownLatch done = new CountDownLatch(1);
        executor.execute(jobId, item.request(), userId, new GenerationStream(jobId, done::countDown));
        if (!done.await(timeoutMinutes, TimeUnit.MINUTES)) {
            log.warn("CURATE key={} 시간 초과({}분)", item.key(), timeoutMinutes);
            return null;
        }
        return resultStore.findForOwner(jobId, userId);
    }
}
