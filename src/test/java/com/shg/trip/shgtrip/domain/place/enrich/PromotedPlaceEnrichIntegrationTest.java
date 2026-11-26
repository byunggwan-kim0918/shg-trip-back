package com.shg.trip.shgtrip.domain.place.enrich;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.shg.trip.shgtrip.domain.place.catalog.CatalogPlace;
import com.shg.trip.shgtrip.domain.place.catalog.PlaceCatalogPromoter;
import com.shg.trip.shgtrip.domain.place.embedding.EmbeddingService;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 승격 → 커밋 뒤 보강 → 저장·재임베딩을 실제 PostgreSQL·Valkey에서 확인한다. Haiku·임베딩 API는 모킹하고 Haiku에는 응답 지연을
 * 넣는다. 각 경우의 최초 표시 이름, 보강 완료 시간, 승격 지연, 호출 수를 표준 출력에 남긴다.
 */
@SpringBootTest(properties = {
        "place.promote-enrich.enabled=true",
        "place.promote-enrich.sweep-initial-delay=PT1H",
        "place.promote-enrich.max-per-hour=60",
        "place.promote-enrich.max-per-day=300"})
@ActiveProfiles("test")
@Tag("integration")
class PromotedPlaceEnrichIntegrationTest {

    private static final String SOURCE = "promote-enrich-it";
    private static final Duration HAIKU_LATENCY = Duration.ofMillis(1500);
    private static final String OSU_RESPONSE = """
            {"tags":["상점가","전자상가","먹거리"],"description":"나고야 중구의 아케이드 상점가로 전자상가와 노포 먹거리가 섞여 있다.",
             "place_role":"SHOPPING","recommended_time_slots":["afternoon"],"recommended_duration_minutes":90,
             "traveler_fame":2,"name_ko":"오스 상점가","admission_fee_local":0}
            """;

    @MockBean
    S3Client s3Client;

    @MockBean(answer = Answers.RETURNS_DEEP_STUBS)
    AnthropicClient anthropicClient;

    @MockBean
    EmbeddingService embeddingService;

    @Autowired
    PlaceCatalogPromoter promoter;

    @Autowired
    PromotedPlaceEnricher enricher;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    PlaceEnrichRequests requests;

    @Autowired
    PlaceEnrichBudget budget;

    @Autowired
    com.shg.trip.shgtrip.domain.place.repository.PlaceRepository placeRepository;

    @Autowired
    com.shg.trip.shgtrip.domain.place.batch.PlaceEmbedder embedder;

    @Autowired
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @jakarta.persistence.PersistenceContext
    jakarta.persistence.EntityManager em;

    private final AtomicInteger haikuCalls = new AtomicInteger();
    private volatile boolean haikuFails;
    private volatile String haikuResponse = OSU_RESPONSE;

    @BeforeEach
    void setUp() {
        clearBudget();
        when(embeddingService.embedBatch(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            List<float[]> out = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) out.add(new float[768]);
            return out;
        });
        when(anthropicClient.withOptions(any())).thenReturn(anthropicClient);
        when(anthropicClient.messages().create(any(MessageCreateParams.class), any(RequestOptions.class)))
                .thenAnswer(inv -> {
                    haikuCalls.incrementAndGet();
                    Thread.sleep(HAIKU_LATENCY.toMillis());
                    if (haikuFails) throw new RuntimeException("overloaded_error");
                    return message(haikuResponse);
                });
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM places WHERE source = ?", SOURCE);
        clearBudget();
    }

    private void clearBudget() {
        redis.delete(hourKey());
        redis.delete("place-enrich:calls:d:" + ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd")));
    }

    private static String hourKey() {
        return "place-enrich:calls:h:" + ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMddHH"));
    }

    private static Message message(String text) {
        Message message = mock(Message.class);
        ContentBlock block = mock(ContentBlock.class);
        TextBlock textBlock = mock(TextBlock.class);
        when(block.isText()).thenReturn(true);
        when(block.asText()).thenReturn(textBlock);
        when(textBlock.text()).thenReturn(text);
        when(message.content()).thenReturn(List.of(block));
        return message;
    }

    private static CatalogPlace catalog(String name, String nameKo, String category) {
        return new CatalogPlace(name, nameKo, "愛知県名古屋市中区", 35.1595, 136.9030, category, "Nagoya", "JP",
                SOURCE, null, null, null, null, null, null, List.of(), null);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("""
                SELECT name_ko, description, place_role, enriched_at, embedding IS NOT NULL AS embedded,
                       array_length(tags, 1) AS tag_count
                FROM places WHERE id = ?""", id);
    }

    private Map<String, Object> request(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT attempts, next_attempt_at > now() + interval '30 seconds' AS deferred, claimed_until
                FROM place_enrich_requests WHERE place_id = ?""", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private record Timed<T>(T value, long ms) {}

    private static <T> Timed<T> timed(Callable<T> call) throws Exception {
        long started = System.nanoTime();
        T value = call.call();
        return new Timed<>(value, (System.nanoTime() - started) / 1_000_000);
    }

    private static void report(String label, String firstName, long promoteMs, Long enrichedMs, int calls) {
        System.out.printf("[promote-enrich] %s | 최초 표시 이름=%s | 승격 %dms | 보강 완료 %s | Haiku %d회%n", label,
                firstName, promoteMs, enrichedMs == null ? "없음" : enrichedMs + "ms", calls);
    }

    @Test
    @DisplayName("1. 카탈로그에 검증된 한국어 이름이 있으면 승격 즉시 그 이름이 보이고, 소개는 보강으로 채운다")
    void catalogNameKoIsShownImmediately() throws Exception {
        Timed<Optional<Place>> promoted = timed(() -> promoter.promote(
                catalog("名古屋城", "나고야성", "Landmarks and Outdoors > Castle")));
        long id = promoted.value().orElseThrow().getId();
        String firstName = promoted.value().get().getNameKo();

        long started = System.nanoTime();
        assertThat(enricher.awaitEnriched(List.of(id), Duration.ofSeconds(10))).containsExactly(id);
        long enrichedMs = (System.nanoTime() - started) / 1_000_000 + promoted.ms();

        assertThat(firstName).isEqualTo("나고야성");
        assertThat(row(id).get("name_ko")).isEqualTo("나고야성");
        assertThat(row(id).get("description")).isNotNull();
        report("카탈로그 이름 있음", firstName, promoted.ms(), enrichedMs, haikuCalls.get());
    }

    @Test
    @DisplayName("1-1. 카탈로그 한국어 이름이 검사를 통과하지 못하면 비워 두고 보강 응답의 이름을 쓴다")
    void unverifiedCatalogNameKoIsDropped() {
        Place place = promoter.promote(catalog("大須商店街", "大須 상점가", "Retail > Shopping Mall")).orElseThrow();

        assertThat(place.getNameKo()).isNull();
        assertThat(enricher.awaitEnriched(List.of(place.getId()), Duration.ofSeconds(10))).containsExactly(place.getId());
        assertThat(row(place.getId()).get("name_ko")).isEqualTo("오스 상점가");
    }

    @Test
    @DisplayName("2. 大須商店街: 한국어 이름이 없어도 승격은 기다리지 않고, 커밋 뒤 보강이 이름·소개·태그·역할·임베딩을 채운다")
    void osuShoppingStreetIsEnrichedAfterCommit() throws Exception {
        Timed<Optional<Place>> promoted = timed(() -> promoter.promote(
                catalog("大須商店街", null, "Retail > Shopping Mall")));
        long id = promoted.value().orElseThrow().getId();
        String firstName = Optional.ofNullable(promoted.value().get().getNameKo()).orElse(promoted.value().get().getName());
        assertThat(enricher.pendingAmong(List.of(id))).containsExactly(id);

        long started = System.nanoTime();
        assertThat(enricher.awaitEnriched(List.of(id), Duration.ofSeconds(10))).containsExactly(id);
        long enrichedMs = (System.nanoTime() - started) / 1_000_000 + promoted.ms();

        Map<String, Object> row = row(id);
        assertThat(promoted.ms()).isLessThan(HAIKU_LATENCY.toMillis());
        assertThat(row.get("name_ko")).isEqualTo("오스 상점가");
        assertThat((String) row.get("description")).startsWith("나고야 중구의 아케이드 상점가");
        assertThat(row.get("place_role")).isEqualTo("SHOPPING");
        assertThat(row.get("tag_count")).isEqualTo(3);
        assertThat(row.get("enriched_at")).isNotNull();
        assertThat(row.get("embedded")).isEqualTo(true);
        assertThat(request(id)).isNull();
        assertThat(enricher.pendingAmong(List.of(id))).isEmpty();
        assertThat(haikuCalls.get()).isEqualTo(1);
        report("大須商店街 보강 성공", firstName, promoted.ms(), enrichedMs, haikuCalls.get());
    }

    @Test
    @DisplayName("3. Haiku가 늦거나 실패해도 승격은 바로 끝나고, 요청은 남아 1분 뒤 재시도에서 보강된다")
    void slowOrFailingHaikuDoesNotBlockAndRetries() throws Exception {
        haikuFails = true;
        Timed<Optional<Place>> promoted = timed(() -> promoter.promote(
                catalog("大須商店街", null, "Retail > Shopping Mall")));
        long id = promoted.value().orElseThrow().getId();

        assertThat(enricher.awaitEnriched(List.of(id), Duration.ofSeconds(10))).isEmpty();
        assertThat(promoted.ms()).isLessThan(HAIKU_LATENCY.toMillis());
        Map<String, Object> pending = request(id);
        assertThat(pending.get("attempts")).isEqualTo(1);
        assertThat(pending.get("deferred")).isEqualTo(true);
        assertThat(pending.get("claimed_until")).isNull();
        assertThat(row(id).get("enriched_at")).isNull();
        assertThat(jdbc.queryForObject("SELECT enrich_attempts FROM places WHERE id = ?", Short.class, id)).isZero();

        haikuFails = false;
        jdbc.update("UPDATE place_enrich_requests SET next_attempt_at = now() WHERE place_id = ?", id);
        long started = System.nanoTime();
        enricher.sweep();
        assertThat(enricher.awaitEnriched(List.of(id), Duration.ofSeconds(10))).containsExactly(id);
        long retryMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(row(id).get("name_ko")).isEqualTo("오스 상점가");
        assertThat(haikuCalls.get()).isEqualTo(2);
        report("Haiku 실패 후 재시도", "大須商店街", promoted.ms(), retryMs, haikuCalls.get());
    }

    @Test
    @DisplayName("4. 동시 승격은 한 행·한 요청·한 번의 호출이 되고, 맡은 작업자가 죽은 요청은 시한이 지나야 다시 맡는다")
    void concurrentPromotionAndRestartRecovery() throws Exception {
        CatalogPlace osu = catalog("大須商店街", null, "Retail > Shopping Mall");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Timed<Optional<Place>>>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return timed(() -> promoter.promote(osu));
            }));
        }
        go.countDown();
        long id = futures.get(0).get().value().orElseThrow().getId();
        long slowest = Math.max(futures.get(0).get().ms(), futures.get(1).get().ms());
        assertThat(futures.get(1).get().value().orElseThrow().getId()).isEqualTo(id);
        pool.shutdown();

        assertThat(enricher.awaitEnriched(List.of(id), Duration.ofSeconds(10))).containsExactly(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM places WHERE source = ?", Integer.class, SOURCE)).isEqualTo(1);
        assertThat(haikuCalls.get()).isEqualTo(1);
        report("동시 승격 2건", "大須商店街", slowest, null, haikuCalls.get());

        // 재시작: 맡은 작업자가 죽어 시한이 남은 요청은 건드리지 않고, 시한이 지난 요청만 주기 작업이 한 번 맡는다.
        Place other = promoter.promote(catalog("大須観音", null, "Landmarks and Outdoors > Temple")).orElseThrow();
        enricher.awaitEnriched(List.of(other.getId()), Duration.ofSeconds(10));
        jdbc.update("UPDATE places SET enriched_at = NULL WHERE id = ?", other.getId());
        jdbc.update("""
                INSERT INTO place_enrich_requests (place_id, attempts, claimed_until)
                VALUES (?, 1, now() + interval '1 minute')""", other.getId());
        int before = haikuCalls.get();
        enricher.sweep();
        assertThat(enricher.awaitEnriched(List.of(other.getId()), Duration.ofSeconds(3))).isEmpty();
        assertThat(haikuCalls.get()).isEqualTo(before);

        jdbc.update("UPDATE place_enrich_requests SET claimed_until = now() - interval '1 second' WHERE place_id = ?",
                other.getId());
        enricher.sweep();
        enricher.sweep();
        assertThat(enricher.awaitEnriched(List.of(other.getId()), Duration.ofSeconds(10))).containsExactly(other.getId());
        assertThat(haikuCalls.get()).isEqualTo(before + 1);
        assertThat(request(other.getId())).isNull();
        report("재시작 후 복구", "大須観音", 0, null, haikuCalls.get() - before);
    }

    @Test
    @DisplayName("5. 시간당 상한을 다 쓰면 부르지 않고 10분 뒤로 미루며, 화면에는 준비 중으로 보이지 않는다")
    void budgetExhaustedDefersWithoutCalling() throws Exception {
        redis.opsForValue().set(hourKey(), "60");
        Timed<Optional<Place>> promoted = timed(() -> promoter.promote(
                catalog("大須商店街", null, "Retail > Shopping Mall")));
        long id = promoted.value().orElseThrow().getId();

        assertThat(enricher.awaitEnriched(List.of(id), Duration.ofSeconds(5))).isEmpty();

        assertThat(haikuCalls.get()).isZero();
        Map<String, Object> pending = request(id);
        assertThat(pending.get("attempts")).isEqualTo(0);
        assertThat(pending.get("deferred")).isEqualTo(true);
        assertThat(enricher.pendingAmong(List.of(id))).isEmpty();
        assertThat(row(id).get("enriched_at")).isNull();
        report("호출 상한 초과", "大須商店街", promoted.ms(), null, haikuCalls.get());
    }

    @Test
    @DisplayName("4-1. 두 인스턴스가 같은 요청을 동시에 맡으려 하면 한쪽만 맡아 Haiku를 한 번만 부른다")
    void twoInstancesClaimOnce() throws Exception {
        long id = jdbc.queryForObject("""
                INSERT INTO places (name, address, latitude, longitude, category, region, country, source, active)
                VALUES ('大須商店街', '愛知県名古屋市中区', 35.1595, 136.9030, 'Retail > Shopping Mall', 'Nagoya', 'JP', ?, true)
                RETURNING id""", Long.class, SOURCE);
        jdbc.update("INSERT INTO place_enrich_requests (place_id) VALUES (?)", id);
        PromotedPlaceEnricher other = new PromotedPlaceEnricher(requests, budget, placeRepository, embedder,
                anthropicClient, objectMapper, Runnable::run, true, "claude-haiku-5-5");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<PromotedPlaceEnricher.Outcome> a = pool.submit(() -> { go.await(); return enricher.process(id); });
        Future<PromotedPlaceEnricher.Outcome> b = pool.submit(() -> { go.await(); return other.process(id); });
        go.countDown();
        List<PromotedPlaceEnricher.Outcome> outcomes = List.of(a.get(), b.get());
        pool.shutdown();

        assertThat(outcomes).containsExactlyInAnyOrder(PromotedPlaceEnricher.Outcome.ENRICHED,
                PromotedPlaceEnricher.Outcome.NOT_CLAIMED);
        assertThat(haikuCalls.get()).isEqualTo(1);
        assertThat(row(id).get("name_ko")).isEqualTo("오스 상점가");
    }

    private CatalogPlace bangkok(String name) {
        return new CatalogPlace(name, null, "158 Thanon Wang Doem, Bangkok", 13.7437, 100.4888,
                "Landmarks and Outdoors > Temple", "Bangkok", "TH", SOURCE, null, null, null, null, null, null,
                List.of(), null);
    }

    /** 월간 v1 보강 대상 조건(ENRICH_TARGETS)에 드는 행. 대상 조회와 같은 식을 테스트 행으로만 좁힌다. */
    private List<Long> batchTargets(long... ids) {
        return em.createQuery("SELECT p.id FROM Place p WHERE " + com.shg.trip.shgtrip.domain.place.repository
                        .PlaceRepository.ENRICH_TARGETS + " AND p.id IN :ids", Long.class)
                .setParameter("categoryPrefix", "")
                .setParameter("ids", java.util.Arrays.stream(ids).boxed().toList())
                .getResultList();
    }

    /** 월간 [2/4] Wikidata 매칭이 그 지역 행에 찍는 시각. 운영은 BATCH_WIKIDATA_COUNTRIES 나라의 지역만 찍는다. */
    private void wikidataMatched(long id) {
        jdbc.update("UPDATE places SET wikidata_matched_at = now() WHERE id = ?", id);
    }

    private long secondsUntilNextAttempt(long id) {
        return jdbc.queryForObject("""
                SELECT round(extract(epoch FROM next_attempt_at - now()))::bigint
                FROM place_enrich_requests WHERE place_id = ?""", Long.class, id);
    }

    private void retryNow(long id) {
        jdbc.update("UPDATE place_enrich_requests SET next_attempt_at = now() WHERE place_id = ?", id);
    }

    @Test
    @DisplayName("6. 일시 오류가 이어져도 요청은 1분·5분·30분·6시간 간격으로 남고, 월간 배치는 Wikidata 매칭 시각이 찍힌 행만 다시 집는다")
    void persistentRetryCoversRowsTheMonthlyBatchMisses() {
        haikuFails = true;
        long nagoya = promoter.promote(catalog("大須商店街", null, "Retail > Shopping Mall")).orElseThrow().getId();
        long bangkok = promoter.promote(bangkok("Wat Arun")).orElseThrow().getId();
        enricher.awaitEnriched(List.of(nagoya, bangkok), Duration.ofSeconds(10));
        List<Long> waits = new ArrayList<>(List.of(secondsUntilNextAttempt(bangkok)));
        for (int i = 0; i < 4; i++) {
            retryNow(nagoya);
            retryNow(bangkok);
            enricher.process(nagoya);
            enricher.process(bangkok);
            waits.add(secondsUntilNextAttempt(bangkok));
        }

        assertThat(waits).hasSize(5).allSatisfy(w -> assertThat(w).isPositive());
        assertThat(waits.get(0)).isBetween(55L, 60L);
        assertThat(waits.get(1)).isBetween(295L, 300L);
        assertThat(waits.get(2)).isBetween(1795L, 1800L);
        assertThat(waits.get(3)).isBetween(21595L, 21600L);
        assertThat(waits.get(4)).isBetween(21595L, 21600L);
        for (long id : List.of(nagoya, bangkok)) {
            assertThat(request(id)).as("요청 유지 %d", id).isNotNull();
            assertThat(request(id).get("attempts")).isEqualTo(5);
        }
        assertThat(batchTargets(nagoya, bangkok)).isEmpty();
        wikidataMatched(nagoya);
        assertThat(batchTargets(nagoya, bangkok)).containsExactly(nagoya);

        haikuFails = false;
        haikuResponse = """
                {"name_ko":"왓 아룬","tags":["사원","강변"],"description":"짜오프라야강 서쪽 강변의 불교 사원이다.",
                 "place_role":"SIGHT","admission_fee_local":200,"traveler_fame":2}""";
        retryNow(bangkok);
        enricher.sweep();
        assertThat(enricher.awaitEnriched(List.of(bangkok), Duration.ofSeconds(10))).containsExactly(bangkok);
        assertThat(row(bangkok).get("name_ko")).isEqualTo("왓 아룬");
        assertThat(jdbc.queryForMap("SELECT admission_fee_local, admission_currency FROM places WHERE id = ?", bangkok))
                .containsEntry("admission_fee_local", null).containsEntry("admission_currency", null);
        assertThat(request(bangkok)).isNull();
    }

    @Test
    @DisplayName("7. 장소 탓 실패가 배치 상한(3회)에 닿으면 즉시 보강도 요청을 지우고, 월간 배치도 다시 묻지 않는다")
    void placeFaultsStopBothPaths() {
        when(anthropicClient.messages().create(any(MessageCreateParams.class), any(RequestOptions.class)))
                .thenAnswer(inv -> {
                    haikuCalls.incrementAndGet();
                    return message("{\"tags\": [], \"description\": null}");
                });
        long id = promoter.promote(catalog("大須商店街", null, "Retail > Shopping Mall")).orElseThrow().getId();
        enricher.awaitEnriched(List.of(id), Duration.ofSeconds(10));
        for (int i = 0; i < 3; i++) {
            retryNow(id);
            enricher.process(id);
        }

        assertThat(jdbc.queryForObject("SELECT enrich_attempts FROM places WHERE id = ?", Integer.class, id)).isEqualTo(3);
        assertThat(request(id)).isNull();
        assertThat(haikuCalls.get()).isEqualTo(3);
        wikidataMatched(id);
        assertThat(batchTargets(id)).isEmpty();
    }
}
