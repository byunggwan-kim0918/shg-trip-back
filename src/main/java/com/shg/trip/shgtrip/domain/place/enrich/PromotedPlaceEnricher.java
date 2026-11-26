package com.shg.trip.shgtrip.domain.place.enrich;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.UnprocessableEntityException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.Usage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.batch.EnrichPrompt;
import com.shg.trip.shgtrip.domain.place.batch.PlaceEmbedder;
import com.shg.trip.shgtrip.domain.place.batch.PlaceEnrichment;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 생성 중 카탈로그에서 승격한 장소를 월간 배치와 같은 v1 규칙({@link PlaceEnrichment})으로 바로 보강한다.
 * 승격 트랜잭션이 커밋되면 곧바로 시작하고, 재시작·실패로 남은 요청은 주기 작업이 다시 맡는다. 요청은 보강되거나, 장소 탓
 * 실패가 배치와 같은 상한(enrich_attempts)에 닿거나, 장소가 꺼질 때만 지운다.
 */
@Slf4j
@Component
public class PromotedPlaceEnricher {

    /** 재시도 없는 호출 한 번(30초)과 재임베딩을 넉넉히 덮는다. 이 시각이 지나면 다른 작업자가 같은 요청을 다시 맡는다. */
    static final Duration LEASE = Duration.ofMinutes(2);
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);
    /**
     * n번째 차례 뒤 기다리는 시간. 횟수가 목록보다 많으면 마지막 값을 쓴다. 일시 오류로는 요청을 지우지 않는다. 월간 배치는
     * Wikidata 매칭 시각이 찍힌 행만 보강해, 그 밖의 행은 이 요청이 유일한 경로다.
     */
    static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(6));
    /** 호출 상한에 걸린 요청을 다시 볼 때까지. 시간 카운터가 바뀌기 전에 같은 요청을 매분 다시 집지 않는다. */
    static final Duration BUDGET_RETRY = Duration.ofMinutes(10);
    private static final int SWEEP_LIMIT = 20;

    public enum Outcome { ENRICHED, NOT_CLAIMED, NOT_NEEDED, BUDGET_EXHAUSTED, CALL_FAILED, REJECTED, UNREADABLE, FAILED }

    private final PlaceEnrichRequests requests;
    private final PlaceEnrichBudget budget;
    private final PlaceRepository placeRepository;
    private final PlaceEmbedder embedder;
    private final AnthropicClient client;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final boolean enabled;
    private final String model;
    /** 이 인스턴스에서 시작했거나 기다리는 보강. 이야기 단계가 끝나기를 기다린다. */
    private final Map<Long, CompletableFuture<Outcome>> inFlight = new ConcurrentHashMap<>();

    public PromotedPlaceEnricher(PlaceEnrichRequests requests, PlaceEnrichBudget budget, PlaceRepository placeRepository,
                                 PlaceEmbedder embedder, AnthropicClient client, ObjectMapper objectMapper,
                                 @Qualifier("placeEnrichExecutor") Executor executor,
                                 @Value("${place.promote-enrich.enabled:false}") boolean enabled,
                                 @Value("${anthropic.batch.model:claude-haiku-5-5}") String model) {
        this.requests = requests;
        this.budget = budget;
        this.placeRepository = placeRepository;
        this.embedder = embedder;
        this.client = client;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.enabled = enabled;
        this.model = model;
    }

    /**
     * 승격한 장소의 보강을 요청한다. 요청은 호출부 트랜잭션과 함께 남고, 보강은 커밋 뒤에 다른 스레드에서 돈다.
     * 호출부는 기다리지 않는다. 꺼져 있으면 남기지 않는다. 쌓인 요청이 켜는 순간 한꺼번에 호출되지 않게 한다.
     */
    public void request(long placeId) {
        if (!enabled || !requests.add(placeId)) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    start(placeId);
                }
            });
        } else {
            start(placeId);
        }
    }

    /** 재시작·실패·상한으로 남은 요청을 다시 맡는다. 즉시 보강이 놓친 요청도 늦어도 이 주기 안에 시작한다. */
    @Scheduled(fixedDelayString = "${place.promote-enrich.sweep-interval:PT1M}",
            initialDelayString = "${place.promote-enrich.sweep-initial-delay:PT30S}")
    public void sweep() {
        if (!enabled) return;
        try {
            requests.due(SWEEP_LIMIT).forEach(this::start);
        } catch (RuntimeException e) {
            log.warn("승격 장소 보강 대기열 조회 실패: {}", e.getMessage());
        }
    }

    /**
     * 이 인스턴스에서 진행 중인 ids의 보강이 끝나기를 최대 maxWait 기다린다.
     *
     * @return 기다리는 동안 보강을 마친 장소 id
     */
    public Set<Long> awaitEnriched(Collection<Long> ids, Duration maxWait) {
        Map<Long, CompletableFuture<Outcome>> waiting = new java.util.HashMap<>();
        for (Long id : ids) {
            // 끝난 작업은 그 사이 inFlight에서 빠져 null일 수 있다.
            CompletableFuture<Outcome> f = id == null ? null : inFlight.get(id);
            if (f != null) waiting.put(id, f);
        }
        if (waiting.isEmpty()) return Set.of();
        try {
            CompletableFuture.allOf(waiting.values().toArray(CompletableFuture[]::new))
                    .get(maxWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.info("승격 장소 보강 대기 {}ms 초과 - 끝난 것만 반영", maxWait.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.warn("승격 장소 보강 대기 중 오류: {}", e.getMessage());
        }
        return waiting.entrySet().stream()
                .filter(e -> e.getValue().isDone() && !e.getValue().isCompletedExceptionally()
                        && e.getValue().join() == Outcome.ENRICHED)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 곧 보강될 장소. 꺼져 있으면 맡을 작업자가 없어 비어 있다. */
    public List<Long> pendingAmong(Collection<Long> placeIds) {
        if (!enabled || placeIds.isEmpty()) return List.of();
        try {
            return requests.pendingAmong(placeIds, LEASE);
        } catch (RuntimeException e) {
            // 준비 중 표시는 부가 정보라 조회 실패가 일정 조회를 막지 않는다.
            log.warn("승격 장소 보강 대기 조회 실패: {}", e.getMessage());
            return List.of();
        }
    }

    void start(long placeId) {
        CompletableFuture<Outcome> created = new CompletableFuture<>();
        if (inFlight.putIfAbsent(placeId, created) != null) return;
        try {
            executor.execute(() -> {
                try {
                    created.complete(process(placeId));
                } catch (RuntimeException e) {
                    log.error("승격 장소 보강 오류 - placeId={}", placeId, e);
                    created.completeExceptionally(e);
                } finally {
                    inFlight.remove(placeId, created);
                }
            });
        } catch (RejectedExecutionException e) {
            // 요청은 대기열에 남아 주기 작업이 다시 맡는다.
            inFlight.remove(placeId, created);
            created.complete(Outcome.NOT_CLAIMED);
            log.warn("승격 장소 보강 실행기 포화 - placeId={}는 주기 작업으로 넘김", placeId);
        }
    }

    Outcome process(long placeId) {
        Optional<Integer> claimed = requests.claim(placeId, LEASE, BACKOFF);
        if (claimed.isEmpty()) return Outcome.NOT_CLAIMED;
        int attempt = claimed.get();
        try {
            return enrich(placeId, attempt);
        } catch (RuntimeException e) {
            log.error("승격 장소 보강 오류 - placeId={}, 시도 {}", placeId, attempt, e);
            requests.release(placeId, e.getClass().getSimpleName() + ": " + e.getMessage());
            return Outcome.FAILED;
        }
    }

    private Outcome enrich(long placeId, int attempt) {
        Place place = placeRepository.findById(placeId).orElse(null);
        if (!needsEnrichment(place)) {
            requests.remove(placeId);
            return Outcome.NOT_NEEDED;
        }
        if (!budget.tryAcquire()) {
            requests.postpone(placeId, BUDGET_RETRY);
            return Outcome.BUDGET_EXHAUSTED;
        }

        long started = System.nanoTime();
        Answer answer;
        try {
            answer = ask(PlaceEnrichment.prompt(place));
        } catch (BadRequestException | UnprocessableEntityException e) {
            // 배치가 invalid_request_error를 장소 탓으로 세듯 같은 요청은 다시 보내도 거절돼, 상한에 닿으면 요청을 지운다.
            log.warn("승격 장소 보강 요청 거절 - placeId={}, 시도 {}: {}", placeId, attempt, e.getMessage());
            placeRepository.incrementEnrichAttempts(List.of(placeId));
            requests.release(placeId, e.getClass().getSimpleName() + ": " + e.getMessage());
            return Outcome.REJECTED;
        } catch (RuntimeException e) {
            log.warn("승격 장소 보강 호출 실패 - placeId={}, 시도 {}: {}", placeId, attempt, e.toString());
            if (attempt == BACKOFF.size()) {
                log.warn("승격 장소 보강 일시 오류 {}회 - placeId={}는 {}마다 다시 맡음", attempt, placeId,
                        BACKOFF.get(BACKOFF.size() - 1));
            }
            requests.release(placeId, e.getClass().getSimpleName() + ": " + e.getMessage());
            return Outcome.CALL_FAILED;
        }
        try {
            return save(place, placeId, answer, started);
        } catch (RuntimeException e) {
            // 응답을 받은 뒤의 실패를 일시 오류로 두면 같은 장소를 6시간마다 다시 사서 호출 상한을 잠근다.
            log.error("승격 장소 보강 반영 실패 - placeId={}, 시도 {}", placeId, attempt, e);
            placeRepository.incrementEnrichAttempts(List.of(placeId));
            requests.release(placeId, e.getClass().getSimpleName() + ": " + e.getMessage());
            return Outcome.FAILED;
        }
    }

    private Outcome save(Place place, long placeId, Answer answer, long started) {
        if (answer.text() == null || !PlaceEnrichment.apply(objectMapper, place, answer.text())) {
            // 배치와 같이 장소 탓인 실패만 enrich_attempts에 센다. 상한에 닿으면 다음 차례에 요청을 지운다.
            placeRepository.incrementEnrichAttempts(List.of(placeId));
            requests.release(placeId, "응답을 읽지 못함");
            return Outcome.UNREADABLE;
        }
        if (placeRepository.writeEnrichment(place) == 0) {
            log.info("승격 장소 보강 중 다른 경로가 먼저 보강했거나 원문이 바뀜 - placeId={}", placeId);
            requests.remove(placeId);
            return Outcome.NOT_NEEDED;
        }
        // 카탈로그 임베딩은 보강 전 텍스트로 만든 것이라 한국어 이름·소개가 검색에 반영되지 않는다.
        List<Long> notEmbedded = embedder.embed(List.of(place)).failedIds();
        if (!notEmbedded.isEmpty()) placeRepository.resetEmbeddings(notEmbedded);
        requests.remove(placeId);
        log.info("승격 장소 보강 완료 - placeId={}, name={}, nameKo={}, {}ms, 토큰 input={} output={} 캐시읽음={} 캐시쓰기={}",
                placeId, place.getName(), place.getNameKo(), (System.nanoTime() - started) / 1_000_000,
                answer.inputTokens(), answer.outputTokens(), answer.cacheReadTokens(), answer.cacheWriteTokens());
        return Outcome.ENRICHED;
    }

    private static boolean needsEnrichment(Place place) {
        return place != null && Boolean.TRUE.equals(place.getActive()) && place.getEnrichedAt() == null
                && (place.getEnrichAttempts() == null || place.getEnrichAttempts() < PlaceRepository.MAX_ENRICH_ATTEMPTS)
                && !"NOT_VISITABLE".equals(place.getPlaceRole());
    }

    /** @param text 응답 글. 글 블록이 없으면 null이다 */
    record Answer(String text, String model, long inputTokens, long outputTokens, long cacheReadTokens,
                  long cacheWriteTokens) {
    }

    /** 배치 요청({@link EnrichPrompt#requestParams})과 같은 모델·system 캐시·thinking 설정으로 한 번 묻는다. */
    Answer ask(EnrichPrompt prompt) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(EnrichPrompt.MAX_TOKENS)
                .thinking(ThinkingConfigDisabled.builder().build())
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(prompt.system())
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .addUserMessage(prompt.user())
                .build();
        // SDK 기본 재시도(2회)가 돌면 한 차례가 맡은 시한을 넘겨 다른 작업자가 같은 장소를 또 부르고, 상한도 실제 호출보다 적게 센다.
        Message message = client.withOptions(o -> o.maxRetries(0)).messages()
                .create(params, RequestOptions.builder().timeout(CALL_TIMEOUT).build());
        String text = message.content().stream()
                .filter(ContentBlock::isText)
                .map(b -> b.asText().text())
                .collect(Collectors.joining());
        Usage usage = message.usage();
        String answered = message.model() == null ? null : message.model().asString();
        return usage == null ? new Answer(text.isEmpty() ? null : text, answered, 0, 0, 0, 0)
                : new Answer(text.isEmpty() ? null : text, answered, usage.inputTokens(), usage.outputTokens(),
                usage.cacheReadInputTokens().orElse(0L), usage.cacheCreationInputTokens().orElse(0L));
    }
}
