package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.entity.PlaceAttributes;
import com.shg.trip.shgtrip.domain.place.integrity.PlaceFingerprints;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * Anthropic Batch API로 미보강 장소의 태그·설명·추천 시간대를 채운다.
 * 실패한 장소는 enriched_at이 NULL로 남아 다음 실행에서 다시 잡힌다.
 * <p>
 * 식당·카페·술집은 프롬프트와 결과 해석이 따로 있다({@link DiningEnrichment}).
 */
@Slf4j
@Component
@Profile("batch")
public class BatchEnrichScheduler {

    /** 연속으로 전부 실패한 청크가 이만큼이면 보강을 멈춘다. */
    static final int MAX_CONSECUTIVE_FAILED_CHUNKS = 3;
    private static final double MAX_FAILURE_RATIO = 0.2;

    private static final String CUSTOM_ID_PREFIX = "place_";
    // 맵 항목 순서가 달라도 같은 본문이면 같은 키가 되게 키 순으로 쓴다.
    private static final ObjectWriter REQUEST_KEY_WRITER = new ObjectMapper()
            .writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    /** 실행에서 제외한 실패 id 상한. 대상 조회의 NOT IN 바인드 파라미터 한도(pgjdbc 32767)보다 작아야 한다. */
    static final int MAX_EXCLUDED_IDS = 20_000;

    private final PlaceRepository placeRepository;
    private final ObjectMapper objectMapper;
    private final AnthropicBatchClient batchClient;
    private final EmbeddingBatchJob embeddingBatchJob;

    @Value("${anthropic.api-key:${ANTHROPIC_API_KEY:}}")
    private String anthropicApiKey;

    @Value("${batch.enrich.chunk-size:1000}")
    private int chunkSize;

    /** 태그·설명 생성은 Haiku로 충분하다. */
    @Value("${anthropic.batch.model:claude-haiku-5-5}")
    private String enrichModel;

    /**
     * 지역 스코프(쉼표 구분, 예: "Jeju,Gangneung"). 비어 있으면 전체 대상.
     * 전량(수만 건) LLM 보강은 비용이 커서 관광지 데이터가 빈약한 지역부터 점진 실행한다.
     */
    @Value("${batch.enrich.regions:}")
    private String enrichRegions;

    /**
     * 지역당 보강 상한(0이면 무제한). 대상 조회 순서가 인기도 → 비식당이라 상한 안에는 유명한 곳부터 들어간다.
     * 지역 목록(batch.enrich.regions)과 함께 써야 지역별로 센다. 목록이 비어 있으면 전체 합계 상한이다.
     */
    @Value("${batch.enrich.max-per-region:0}")
    private int maxPerRegion;

    /** 보강 대상 카테고리 접두어(예: "Dining and Drinking"). 비면 전체다. 대상 순서가 명소 먼저라 식당 견본을 따로 뽑을 때 쓴다. */
    @Value("${batch.enrich.category-prefix:}")
    private String categoryPrefix = "";

    /**
     * 보강 국가(쉼표 구분 ISO 코드, 예: "JP,TW"). 비면 전체다. 그 나라 활성 장소가 있는 지역 목록으로 바꿔 지역 범위와 겹친다.
     * 공급처·적재 경로(월간 적재, 카탈로그에서 올린 장소)와 무관하게 DB에 있는 지역을 쓴다.
     */
    @Value("${batch.enrich.countries:}")
    private String enrichCountries = "";

    /**
     * 패스(v1, 이어지는 소개 다시 쓰기)마다 보내는 요청 상한(0이면 무제한). 새 장소는 모두 보강하되, 보강된 장소가 버그로
     * 한꺼번에 다시 대상이 될 때 운영 전체를 다시 사지 않게 한다.
     */
    @Value("${batch.enrich.max-requests:200000}")
    private long maxRequests;

    /** 대상 수와 예상 비용만 남기고 요청은 보내지 않는다. */
    @Value("${batch.enrich.dry-run:false}")
    private boolean dryRun;

    private final AtomicLong submittedRequests = new AtomicLong();

    /** 이번 실행에서 요청 상한 때문에 남긴 대상이 있다. */
    private volatile boolean leftByRequestCap;

    /**
     * 이미 제출·완료된 배치의 결과를 다시 적용할 때 쓰는 batchId.
     * Anthropic이 결과를 29일간 보관하므로, 결과 처리 단계에서만 실패한 경우 재제출(재과금) 없이
     * 복구할 수 있다.
     */
    @Value("${batch.enrich.resume-batch-id:}")
    private String resumeBatchId;

    /**
     * 보강 단계 전체(결과 대기 포함)의 상한. 넘기면 배치 작업 제한(Step Functions 36h)에 걸려 뒤의 임베딩 단계가
     * 돌지 않고, 내려 둔 근사 검색 인덱스도 다시 만들어지지 않는다.
     */
    @Value("${batch.enrich.max-duration:PT10H}")
    private Duration maxDuration = Duration.ofHours(10);

    /** 이번 보강 단계의 끝 시각(epoch ms). 결과 대기도 이 시각에서 끊는다. */
    private volatile long runDeadlineMs = Long.MAX_VALUE;

    /**
     * v1: 미보강 장소의 태그·설명·역할 보강. v2: 속성만 분류({@link AttributeEnrichment}).
     * ingredients: 식당 주재료만({@link IngredientEnrichment}). descriptions: 소개의 평판 문구·섞인 글자만 고쳐 쓴다
     * ({@link DescriptionRewrite}).
     * 운영 배치가 켜져 있어 기본값은 v1이다. v1 밖의 모드는 대상·비용이 달라 명시적으로 켤 때만 돈다.
     */
    @Value("${batch.enrich.mode:v1}")
    private String mode;

    enum Mode {
        V1("v1"), ATTRIBUTES("v2"), INGREDIENTS("ingredients"), DESCRIPTIONS("descriptions");

        /** batch.enrich.mode 설정값 */
        final String value;

        Mode(String value) {
            this.value = value;
        }
    }

    /** v1 보강 뒤 같은 실행에서 소개 다시 쓰기({@link Mode#DESCRIPTIONS})를 돈다. */
    @Value("${batch.enrich.rewrite-descriptions:true}")
    private boolean rewriteDescriptionsAfterV1 = true;

    /** v1 뒤 소개 다시 쓰기를 도는 동안의 모드. 설정값(mode)보다 앞선다. */
    private volatile Mode passMode;

    /** 동시에 제출해 기다리는 묶음 수. 묶음마다 10초 간격으로 상태를 조회해 이 수만큼 Batch API 요청이 늘어난다. */
    @Value("${batch.enrich.parallel-chunks:5}")
    private int parallelChunks;

    @org.springframework.beans.factory.annotation.Autowired
    public BatchEnrichScheduler(PlaceRepository placeRepository, ObjectMapper objectMapper,
                                EmbeddingBatchJob embeddingBatchJob, AnthropicBatchClient batchClient) {
        this.placeRepository = placeRepository;
        this.objectMapper = objectMapper;
        this.embeddingBatchJob = embeddingBatchJob;
        this.batchClient = batchClient;
    }

    /**
     * failedIds는 enriched_at이 여전히 NULL이라 다음 조회에 다시 잡히므로 이번 실행에서 제외할 ID다.
     *
     * @param countsTowardAbort 연속 실패 중단에 세는가. 결과 대기 시간 초과와 전부 만료된 묶음은 API 키·모델
     *                          오류가 아니라 세지 않는다
     */
    record ChunkResult(int success, int failed, List<Long> failedIds, boolean countsTowardAbort) {
        ChunkResult(int success, int failed, List<Long> failedIds) {
            this(success, failed, failedIds, true);
        }
    }

    /**
     * 매번 첫 페이지를 다시 조회하고 이번 실행의 실패 ID는 쿼리에서 뺀다. 고정 offset은 남은 건수보다
     * 앞서 나가 조기 종료된다. 조회 순서가 인기도 → 비식당이라 중간에 끊겨도 중요한 장소부터 끝나 있다.
     * <p>
     * 연속 {@value #MAX_CONSECUTIVE_FAILED_CHUNKS}개 청크가 전부 실패하면 멈춘다. API 키 오류처럼
     * 모든 요청이 실패할 때 수만 건을 헛돌지 않게 한다.
     */
    public void enrich() {
        if (mode() == null) {
            throw new BatchStageFailed("알 수 없는 batch.enrich.mode=" + mode + " (v1, v2, ingredients, descriptions 중 하나)");
        }
        if (anthropicApiKey == null || anthropicApiKey.isBlank()) {
            log.warn("Anthropic API key가 설정되지 않았습니다. 배치 보강을 건너뜁니다.");
            return;
        }

        List<String> resumeIds = parseList(resumeBatchId);
        if (!resumeIds.isEmpty()) {
            if (dryRun) {
                log.info("dry-run - 배치 결과 재반영 {}개를 건너뜀(DB에 쓰지 않음)", resumeIds.size());
                return;
            }
            resume(resumeIds);
            return;
        }

        List<String> regions = scopeRegions();
        if ((mode() == Mode.ATTRIBUTES || mode() == Mode.INGREDIENTS) && regions.isEmpty()) {
            log.warn("속성·주재료 보강(mode={})은 batch.enrich.regions가 필요합니다. 건너뜁니다.", mode().value);
            return;
        }
        long deadline = System.currentTimeMillis() + maxDuration.toMillis();
        runDeadlineMs = deadline;
        leftByRequestCap = false;
        failIfPassFailed(runPass(regions, deadline));
        // v1 프롬프트 설명 가이드를 어긴 소개를 같은 실행에서 고쳐 쓴다. 카테고리 견본 실행은 범위가 달라 건너뛴다.
        if (mode() == Mode.V1 && rewriteDescriptionsAfterV1 && categoryPrefix.isEmpty()) {
            passMode = Mode.DESCRIPTIONS;
            try {
                failIfPassFailed(runPass(regions, deadline));
            } finally {
                passMode = null;
            }
        }
        // 다 돌고 나서 실패로 남겨야 상한이 밀린 양을 종료 코드·batch_runs로 드러낸다. 남은 장소는 다음 실행이 잇는다.
        if (leftByRequestCap) {
            throw new BatchStageFailed("패스당 요청 상한 " + maxRequests + "건에 닿아 보강 대상이 남음(BATCH_ENRICH_MAX_REQUESTS)");
        }
    }

    /**
     * 요청 상한은 패스마다 센다. v1이 상한을 다 써도 v1 결과의 소개 다시 쓰기는 같은 실행에서 돈다.
     *
     * @return [성공, 실패, 연속 실패 중단 횟수]
     */
    private int[] runPass(List<String> regions, long deadline) {
        submittedRequests.set(0);
        log.info("BatchEnrichScheduler 시작 - mode={}, chunkSize={}, regions={}, maxPerRegion={}, category={}",
                mode().value, chunkSize, regions.isEmpty() ? "전체" : regions,
                maxPerRegion > 0 ? maxPerRegion : "무제한",
                mode() != Mode.V1 ? "무시" : categoryPrefix.isEmpty() ? "전체" : categoryPrefix);
        if (mode() == Mode.ATTRIBUTES) {
            log.info("속성 보강 대상 {}건", placeRepository.countAttributeTargetsInRegions(regions));
        } else if (mode() == Mode.INGREDIENTS) {
            log.info("주재료 보강 대상 {}건", placeRepository.countIngredientTargetsInRegions(regions));
        } else if (mode() == Mode.DESCRIPTIONS) {
            // 이름 속 낱말·한자만 걸린 행은 조회 뒤 다시 걸러 실제 대상은 이보다 조금 적다.
            log.info("소개 다시 쓰기 후보 {}건", placeRepository.countDescriptionRewriteTargets(
                    regions.isEmpty(), regionsParam(regions), overseasCountries(), DescriptionRewrite.REPUTATION_REGEX));
            long exhausted = placeRepository.countDescriptionRewriteExhausted();
            if (exhausted > 0) log.warn("소개 고쳐 쓰기에 3번 실패해 제외된 장소 {}건", exhausted);
        } else {
            logEstimate(regions);
            long waiting = placeRepository.countEnrichWaitingForWikidata();
            // batch.wikidata.countries 밖 국가, 한국 50곳 미만 지역, 좌표·지역 없는 행은 계속 기다려 로그로 드러낸다(지역 무관 전체 건수).
            if (waiting > 0) log.warn("Wikidata 매칭 전이라 보강을 미룬 장소 {}건", waiting);
            long exhausted = placeRepository.countEnrichExhausted();
            if (exhausted > 0) log.warn("보강 응답이 3번 실패해 제외된 장소 {}건", exhausted);
        }

        int[] totals = new int[3];
        if (dryRun) {
            log.info("dry-run - 요청을 보내지 않음(mode={})", mode().value);
            return totals;
        }
        if (maxPerRegion > 0 && !regions.isEmpty()) {
            // 지역별로 따로 돌려야 큰 지역(서울)이 상한을 다 먹지 않는다.
            for (String region : regions) {
                if (requestCapReached()) break;
                enrichScope(List.of(region), totals, deadline);
            }
        } else {
            enrichScope(regions, totals, deadline);
        }

        if (requestCapReached() && !fetchTargets(regions, List.of(-1L), PageRequest.of(0, 1)).isEmpty()) {
            // 소개 다시 쓰기 후보 조회는 이름 속 낱말까지 걸려 남은 대상을 부풀린다. 경고만 남긴다.
            if (mode() != Mode.DESCRIPTIONS) leftByRequestCap = true;
            log.warn("패스당 요청 상한 {}건에 닿아 남은 장소를 다음 실행으로 넘김(mode={}){}", maxRequests, mode().value,
                    mode() == Mode.V1 ? " - 남은 대상 견적: " + estimateSummary(regions) : "");
        }
        log.info("BatchEnrichScheduler 완료 - mode={}, 보강 성공: {}건, 실패: {}건, 중단 {}회",
                mode().value, totals[0], totals[1], totals[2]);
        return totals;
    }

    // 장소 단위 실패는 모델 응답 불량으로 늘 조금씩 나와 비율로 본다.
    private void failIfPassFailed(int[] totals) {
        if (totals[2] > 0 || totals[1] > (totals[0] + totals[1]) * MAX_FAILURE_RATIO) {
            throw new BatchStageFailed("배치 보강(" + mode().value + ") 실패 " + totals[1] + "건, 중단 " + totals[2] + "회");
        }
    }

    /** 한 범위(지역 목록, 비면 전체)를 상한까지 보강한다. totals[0]=성공, [1]=실패, [2]=연속 실패 중단 누적. */
    private void enrichScope(List<String> regions, int[] totals, long deadline) {
        int processed = 0;
        int failed = 0;
        int consecutiveFailedChunks = 0;
        Set<Long> excludedIds = new HashSet<>();
        int parallel = Math.max(1, parallelChunks);

        while (true) {
            if (System.currentTimeMillis() >= deadline) {
                log.warn("보강 시간 상한({}) 도달 - 남은 장소는 다음 실행에서 보강", maxDuration);
                break;
            }
            if (excludedIds.size() > MAX_EXCLUDED_IDS) {
                log.error("배치 보강 중단 - 이번 실행에서 뺀 장소(실패·건너뜀) {}건이 상한 {}건을 넘음",
                        excludedIds.size(), MAX_EXCLUDED_IDS);
                totals[2]++;
                break;
            }
            int pageSize = chunkSize * parallel;
            if (maxPerRegion > 0) {
                int remaining = maxPerRegion - processed - failed;
                if (remaining <= 0) break;
                pageSize = Math.min(pageSize, remaining);
            }
            if (requestCapReached()) break;
            if (maxRequests > 0) {
                // 장소 수가 요청 수 이상이라 남은 요청 수만큼만 읽으면 상한을 넘지 않는다.
                pageSize = (int) Math.min(pageSize, maxRequests - submittedRequests.get());
            }
            // NOT IN에 빈 컬렉션을 넘기지 않는다(존재할 수 없는 id로 대신한다).
            Collection<Long> excluded = excludedIds.isEmpty() ? List.of(-1L) : excludedIds;
            List<Place> places = fetchTargets(regions, excluded, PageRequest.of(0, pageSize));

            if (places.isEmpty()) {
                break;
            }
            if (mode() == Mode.DESCRIPTIONS) {
                // 이름 속 평판 낱말과 이름을 따라 쓴 한자는 SQL이 가리지 못해 여기서 뺀다.
                Map<Boolean, List<Place>> split = places.stream()
                        .collect(Collectors.partitioningBy(DescriptionRewrite::needsRewrite));
                split.get(false).forEach(p -> excludedIds.add(p.getId()));
                places = split.get(true);
                if (places.isEmpty()) continue;
            }

            log.info("배치 보강 청크 처리 중 - {} {}건 (누적 성공 {}건, 실패 {}건)",
                    regions.isEmpty() ? "전체" : regions, places.size(), processed, failed);

            for (ChunkResult result : processChunks(splitIntoChunks(places))) {
                processed += result.success();
                failed += result.failed();
                excludedIds.addAll(result.failedIds());
                if (result.success() > 0) {
                    consecutiveFailedChunks = 0;
                } else if (result.countsTowardAbort()) {
                    consecutiveFailedChunks++;
                }
            }
            if (consecutiveFailedChunks >= MAX_CONSECUTIVE_FAILED_CHUNKS) {
                log.error("배치 보강 중단 - 연속 {}개 청크 전부 실패 (API 키·모델명·쿼터 확인 필요)",
                        consecutiveFailedChunks);
                totals[2]++;
                break;
            }
        }
        totals[0] += processed;
        totals[1] += failed;
    }

    private List<Place> fetchTargets(List<String> regions, Collection<Long> excluded, PageRequest page) {
        return switch (mode()) {
            case ATTRIBUTES -> placeRepository.findAttributeTargetsInRegions(regions, excluded, page);
            case INGREDIENTS -> placeRepository.findIngredientTargetsInRegions(regions, excluded, page);
            case DESCRIPTIONS -> placeRepository.findDescriptionRewriteTargets(regions.isEmpty(),
                    regionsParam(regions), overseasCountries(), DescriptionRewrite.REPUTATION_REGEX, excluded, page);
            case V1 -> regions.isEmpty()
                    ? placeRepository.findEnrichTargets(categoryPrefix, excluded, page)
                    : placeRepository.findEnrichTargetsInRegions(regions, categoryPrefix, excluded, page);
        };
    }

    /** 비어 있으면 v1이고, 알 수 없는 값이면 null이다. v1 뒤 소개 다시 쓰기를 도는 동안은 descriptions다. */
    Mode mode() {
        if (passMode != null) return passMode;
        String value = mode == null || mode.isBlank() ? Mode.V1.value : mode.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(Mode.values()).filter(m -> m.value.equals(value)).findFirst().orElse(null);
    }

    // IN ()은 문법 오류라 지역 조건을 끄는 실행에도 자리값 하나를 넘긴다.
    private static List<String> regionsParam(List<String> regions) {
        return regions.isEmpty() ? List.of("") : regions;
    }

    private static List<String> overseasCountries() {
        return List.copyOf(OverseasEnrichment.COUNTRIES.keySet());
    }

    private boolean requestCapReached() {
        return maxRequests > 0 && submittedRequests.get() >= maxRequests;
    }

    /**
     * 지역 범위(비면 전체). 국가를 정하면 그 나라 지역 목록이 되어, 지역이 필요한 속성·주재료 모드도 한 나라 전체를 돈다.
     *
     * @throws BatchStageFailed 국가를 정했는데 겹치는 지역이 없을 때. 국가 코드 오타가 성공으로 기록되지 않게 한다
     */
    private List<String> scopeRegions() {
        List<String> regions = parseList(enrichRegions);
        List<String> countries = parseList(enrichCountries).stream().map(c -> c.toUpperCase(Locale.ROOT)).toList();
        if (countries.isEmpty()) return regions;
        List<String> inCountries = placeRepository.findActiveRegionsInCountries(countries);
        List<String> scoped = regions.isEmpty() ? inCountries : regions.stream().filter(inCountries::contains).toList();
        if (scoped.isEmpty()) {
            throw new BatchStageFailed("보강 국가 " + countries + "에 해당하는 지역이 없음(지역 범위 " + regions + ")");
        }
        log.info("보강 국가 {} → 지역 {}곳", countries, scoped.size());
        return scoped;
    }

    private void logEstimate(List<String> regions) {
        log.info("v1 보강 견적 - {}", estimateSummary(regions));
    }

    // 견적은 안내라 조회가 실패해도 보강을 막지 않는다.
    private String estimateSummary(List<String> regions) {
        try {
            return estimateV1(regions).summary();
        } catch (RuntimeException e) {
            log.warn("v1 보강 견적 조회 실패: {}", e.getMessage());
            return "조회 실패";
        }
    }

    /**
     * 상한은 식당이 아닌 갈래부터 채운다(실제 순서의 인기도는 반영하지 않아 상한이 걸리면 갈래 구성이 조금 다르다).
     * 지역당 상한도 전체 상한으로 바꿔 넣는다.
     */
    EnrichEstimate.Total estimateV1(List<String> regions) {
        List<EnrichEstimate.Line> lines = placeRepository
                .countEnrichTargetsByKind(regions.isEmpty(), regionsParam(regions), categoryPrefix).stream()
                .map(r -> new EnrichEstimate.Line((String) r[0], ((Number) r[1]).intValue() == 1,
                        ((Number) r[2]).longValue()))
                .sorted(Comparator.comparing(EnrichEstimate.Line::dining))
                .toList();
        long budget = maxRequests;
        if (maxPerRegion > 0 && !regions.isEmpty()) {
            long perRegion = (long) maxPerRegion * regions.size();
            budget = budget > 0 ? Math.min(budget, perRegion) : perRegion;
        }
        return EnrichEstimate.of(lines, budget);
    }

    private static List<String> parseList(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * 끝난 배치들의 결과만 다시 반영한다(재제출 없음, 결과 보관 29일). 장소는 결과의 custom_id로 읽는다.
     * 그 사이 보강된 행은 옛 결과로 덮지 않고, 실패 횟수도 올리지 않는다. 같은 요청으로 묶여 결과 행이 없는 장소는
     * 여기서 반영되지 않고 다음 실행에서 다시 묻는다.
     */
    void resume(List<String> batchIds) {
        runDeadlineMs = Long.MAX_VALUE;
        int succeeded = 0;
        int failed = 0;
        int failedBatches = 0;
        for (String batchId : batchIds) {
            try {
                log.info("기존 배치 결과 재반영 - batchId={} (재제출 없음)", batchId);
                String jsonl = fetchResults(batchId);
                if (jsonl == null) {
                    log.error("배치 결과를 가져오지 못함 - batchId={}", batchId);
                    failedBatches++;
                    continue;
                }
                Mode resultMode = modeOf(jsonl);
                if (resultMode != null && resultMode != mode()) {
                    log.error("배치 결과 모드({})가 실행 모드({})와 다름 - batchId={}. BATCH_ENRICH_MODE를 맞춰 다시 반영",
                            resultMode.value, mode().value, batchId);
                    failedBatches++;
                    continue;
                }
                Map<Long, String> inputKeys = inputKeysIn(jsonl);
                List<Place> unanswered = placeRepository.findAllById(List.copyOf(inputKeys.keySet())).stream()
                        .filter(this::stillPending)
                        .toList();
                List<Place> pending = unanswered.stream()
                        .filter(p -> inputKeys.get(p.getId()) == null
                                || inputKeys.get(p.getId()).equals(inputKey(p, mode())))
                        .toList();
                if (pending.size() < unanswered.size()) {
                    log.info("결과를 기다리는 사이 입력이 바뀐 장소 {}건은 반영하지 않음(다음 실행에서 다시 물음) - batchId={}",
                            unanswered.size() - pending.size(), batchId);
                }
                if (pending.isEmpty()) log.warn("재반영할 장소 없음(모두 이미 반영됨) - batchId={}, mode={}", batchId, mode().value);
                ChunkResult result = applyResults(jsonl, pending, false);
                succeeded += result.success();
                failed += result.failed();
            } catch (Exception e) {
                log.error("배치 결과 재반영 중 예외 - batchId={}: {}", batchId, e.getMessage(), e);
                failedBatches++;
            }
        }
        log.info("배치 결과 재반영 완료 - 성공 {}건, 실패 {}건, 처리 못 한 배치 {}개", succeeded, failed, failedBatches);
        if (failedBatches > 0 || failed > (succeeded + failed) * MAX_FAILURE_RATIO) {
            throw new BatchStageFailed("배치 결과 재반영 실패 " + failed + "건, 배치 " + failedBatches + "개");
        }
    }

    /** 결과를 만든 모드. 가를 수 있는 성공 행이 없으면 null이다. */
    Mode modeOf(String jsonl) {
        for (String line : jsonl.split("\n")) {
            if (line.isBlank()) continue;
            try {
                JsonNode result = objectMapper.readTree(line).path("result");
                if (!"succeeded".equals(result.path("type").asText())) continue;
                for (JsonNode block : result.path("message").path("content")) {
                    String type = block.path("type").asText();
                    String tool = block.path("name").asText();
                    if ("tool_use".equals(type) && AttributeEnrichment.TOOL_NAME.equals(tool)) return Mode.ATTRIBUTES;
                    if ("tool_use".equals(type) && IngredientEnrichment.TOOL_NAME.equals(tool)) return Mode.INGREDIENTS;
                    if ("tool_use".equals(type) && DescriptionRewrite.TOOL_NAME.equals(tool)) return Mode.DESCRIPTIONS;
                    if ("text".equals(type)) return Mode.V1;
                }
            } catch (Exception e) {
                log.warn("결과 행 모드 읽기 실패: {}", e.getMessage());
            }
        }
        return null;
    }

    private boolean stillPending(Place p) {
        return switch (mode()) {
            case V1 -> p.getEnrichedAt() == null;
            case ATTRIBUTES -> p.getEnrichVersion() == null || p.getEnrichVersion() < AttributeEnrichment.VERSION;
            case INGREDIENTS -> p.getAttributes() == null || p.getAttributes().ingredients() == null;
            case DESCRIPTIONS -> DescriptionRewrite.needsRewrite(p);
        };
    }

    /** 끝난 배치의 결과 JSONL. 끝나지 않았거나 실패면 null. */
    String fetchResults(String batchId) {
        AnthropicBatchClient.PollResult poll = pollForCompletion(batchId);
        return poll.resultsUrl() == null ? null : downloadResults(poll.resultsUrl());
    }

    /** 결과 행의 장소 id → 요청 입력 지문(지문 없는 옛 custom_id면 null). */
    Map<Long, String> inputKeysIn(String jsonl) {
        Map<Long, String> keys = new LinkedHashMap<>();
        for (String line : jsonl.split("\n")) {
            if (line.isBlank()) continue;
            try {
                String customId = objectMapper.readTree(line).path("custom_id").asText("");
                Long id = placeIdOf(customId);
                if (id != null) keys.put(id, inputKeyOf(customId));
            } catch (Exception e) {
                log.warn("결과 행 custom_id 읽기 실패: {}", e.getMessage());
            }
        }
        return keys;
    }

    static String customId(Place place, Mode mode) {
        String key = inputKey(place, mode);
        return CUSTOM_ID_PREFIX + place.getId() + (key == null ? "" : "_" + key);
    }

    /**
     * 결과를 기다리는 사이 바뀐 입력에 옛 답을 쓰지 않도록 요청에 붙이는 지문. v1은 무효화 작업과 같은 보강 입력 지문이라,
     * 옛 이름으로 받은 결과가 새 이름의 지문을 달고 저장되지 않는다.
     */
    static String inputKey(Place place, Mode mode) {
        return switch (mode) {
            case V1 -> PlaceFingerprints.enrichInput(place.getName(), place.getCategory(), place.getRegion(),
                    place.getCountry());
            case ATTRIBUTES -> AttributeEnrichment.inputKey(place);
            case INGREDIENTS -> IngredientEnrichment.inputKey(place);
            case DESCRIPTIONS -> DescriptionRewrite.inputKey(place);
        };
    }

    /** {@code place_{id}} 또는 {@code place_{id}_{입력 지문}}의 id. 형식이 다르면 null. */
    static Long placeIdOf(String customId) {
        if (customId == null || !customId.startsWith(CUSTOM_ID_PREFIX)) return null;
        String rest = customId.substring(CUSTOM_ID_PREFIX.length());
        int sep = rest.indexOf('_');
        try {
            return Long.parseLong(sep < 0 ? rest : rest.substring(0, sep));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String inputKeyOf(String customId) {
        if (customId == null || !customId.startsWith(CUSTOM_ID_PREFIX)) return null;
        String rest = customId.substring(CUSTOM_ID_PREFIX.length());
        int sep = rest.indexOf('_');
        return sep < 0 ? null : rest.substring(sep + 1);
    }

    /** 결과는 chunks와 같은 순서다. 묶음 처리 중 예외는 그 묶음 전부를 실패로 돌려준다. */
    List<ChunkResult> processChunks(List<List<Place>> chunks) {
        if (chunks.size() == 1) {
            return List.of(processChunk(chunks.get(0)));
        }
        List<ChunkResult> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ChunkResult>> futures = chunks.stream()
                    .map(chunk -> pool.submit(() -> processChunk(chunk)))
                    .toList();
            for (int i = 0; i < chunks.size(); i++) {
                try {
                    results.add(futures.get(i).get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(new ChunkResult(0, chunks.get(i).size(), allIds(chunks.get(i))));
                } catch (ExecutionException e) {
                    log.error("배치 보강 묶음 처리 중 예외: {}", e.getCause().getMessage(), e.getCause());
                    results.add(new ChunkResult(0, chunks.get(i).size(), allIds(chunks.get(i))));
                }
            }
        }
        return results;
    }

    ChunkResult processChunk(List<Place> places) {
        try {
            List<Map<String, Object>> requests = buildBatchRequests(places);
            String batchId = submitBatch(requests);
            if (batchId == null) {
                log.error("배치 제출 실패 - {}건 모두 실패 처리", places.size());
                return new ChunkResult(0, places.size(), allIds(places));
            }
            submittedRequests.addAndGet(requests.size());

            AnthropicBatchClient.PollResult poll = pollForCompletion(batchId);
            if (poll.resultsUrl() == null) {
                // 결과는 나중에 batch.enrich.resume-batch-id로 다시 반영할 수 있다
                log.error("배치 결과 대기 실패 - batchId={}, 시간 초과={}", batchId, poll.timedOut());
                return new ChunkResult(0, places.size(), allIds(places), !poll.timedOut());
            }

            return processResults(batchId, poll.resultsUrl(), places);

        } catch (Exception e) {
            log.error("배치 보강 처리 중 예외 발생: {}", e.getMessage(), e);
            return new ChunkResult(0, places.size(), allIds(places));
        }
    }

    private List<Long> allIds(List<Place> places) {
        return places.stream().map(Place::getId).toList();
    }

    /** 요청 본문이 같은 장소는 한 번만 묻는다. custom_id는 묶음의 첫 장소다. */
    List<Map<String, Object>> buildBatchRequests(List<Place> places) {
        List<Map<String, Object>> requests = new ArrayList<>();
        for (List<Place> group : requestGroups(places)) {
            Place first = group.get(0);
            requests.add(Map.of("custom_id", customId(first, mode()), "params", requestParams(first)));
        }
        return requests;
    }

    private Map<String, Object> requestParams(Place place) {
        return switch (mode()) {
            case ATTRIBUTES -> AttributeEnrichment.requestParams(place, enrichModel);
            case INGREDIENTS -> IngredientEnrichment.requestParams(place, enrichModel);
            case DESCRIPTIONS -> DescriptionRewrite.requestParams(place, enrichModel);
            case V1 -> buildEnrichPrompt(place).requestParams(enrichModel);
        };
    }

    /**
     * 요청 본문이 글자까지 같은 장소 묶음(입력 순서 유지). 체인 지점처럼 이름·카테고리가 같고 주소를 묻지 않는 장소는
     * 모델이 보는 입력이 같아, 따로 물으면 같은 답에 비용만 더 든다.
     */
    List<List<Place>> requestGroups(List<Place> places) {
        Map<String, List<Place>> groups = new LinkedHashMap<>();
        for (Place place : places) {
            groups.computeIfAbsent(requestKey(place), k -> new ArrayList<>()).add(place);
        }
        return List.copyOf(groups.values());
    }

    private String requestKey(Place place) {
        try {
            return REQUEST_KEY_WRITER.writeValueAsString(requestParams(place));
        } catch (JsonProcessingException e) {
            log.debug("요청 본문 키를 만들지 못해 묶지 않음 - placeId={}: {}", place.getId(), e.getMessage());
            return "place:" + place.getId();
        }
    }

    EnrichPrompt buildEnrichPrompt(Place place) {
        boolean overseas = OverseasEnrichment.isOverseas(place.getCountry());
        if (DiningEnrichment.isDiningVenue(place.getCategory())) {
            return DiningEnrichment.buildPrompt(place.getName(), place.getCategory(), place.getDescription(),
                    DiningEnrichment.venueOf(place.getCategory()),
                    DiningEnrichment.isFamous(place.getWikidataSitelinks()),
                    overseas ? OverseasEnrichment.contextLines(place) : null,
                    overseas && place.getNameKo() == null);
        }
        return overseas ? AttractionEnrichment.overseasPrompt(place) : AttractionEnrichment.prompt(place);
    }

    String submitBatch(List<Map<String, Object>> requests) {
        return batchClient.submit(requests);
    }

    AnthropicBatchClient.PollResult pollForCompletion(String batchId) {
        return batchClient.poll(batchId, runDeadlineMs);
    }

    ChunkResult processResults(String batchId, String resultsUrl, List<Place> places) {
        String jsonl = downloadResults(resultsUrl);
        if (jsonl == null) {
            log.error("배치 결과 다운로드 실패 - batchId={} (resume-batch-id로 다시 반영 가능)", batchId);
            return new ChunkResult(0, places.size(), allIds(places));
        }
        return applyResults(jsonl, places);
    }

    /** 결과 JSONL. 실패면 null. 비용은 이미 낸 뒤라 실패해도 resume-batch-id로 다시 받을 수 있다. */
    String downloadResults(String resultsUrl) {
        return batchClient.download(resultsUrl);
    }

    /**
     * 실패 목록은 "청크에서 성공하지 못한 전부"로 계산한다. 행 처리 중 예외가 나거나 결과에서 빠진
     * 장소를 하나씩 챙기지 않으면 enriched_at이 NULL로 남아 다음 청크마다 다시 제출된다.
     */
    ChunkResult applyResults(String jsonl, List<Place> places) {
        return applyResults(jsonl, places, true);
    }

    /**
     * 장소 실패 횟수에는 응답을 해석하지 못한 경우와, 성공이 있는 묶음의 요청 오류(invalid_request_error)만 센다.
     * 만료·취소·일시 오류(overloaded·api_error)·성공 0건 묶음의 오류(모델명 등 묶음 전체 장애)는 장소 탓이 아니다.
     *
     * @param countAttempts 장소 실패 횟수(enrich_attempts)를 올린다. 이미 반영한 결과를 다시 반영할 때는 끈다
     */
    ChunkResult applyResults(String jsonl, List<Place> places, boolean countAttempts) {
        Map<Long, Place> placeMap = new HashMap<>();
        for (Place place : places) {
            placeMap.put(place.getId(), place);
        }
        // 결과에 자기 행이 있는 장소(따로 물은 묶음의 재반영)는 그 행으로만 반영한다.
        Set<Long> answered = inputKeysIn(jsonl).keySet();
        Map<Long, List<Place>> followers = new HashMap<>();
        for (List<Place> group : requestGroups(places)) {
            followers.put(group.get(0).getId(),
                    group.stream().skip(1).filter(p -> !answered.contains(p.getId())).toList());
        }

        Tally tally = new Tally();
        Set<Long> handled = new HashSet<>();
        for (String line : jsonl.split("\n")) {
            if (line.isBlank()) continue;

            JsonNode resultBody;
            List<Place> members = new ArrayList<>();
            try {
                JsonNode resultNode = objectMapper.readTree(line);
                String customId = resultNode.get("custom_id").asText();
                Long placeId = placeIdOf(customId);
                Place place = placeId == null ? null : placeMap.get(placeId);
                if (place == null) {
                    log.warn("결과에 매칭되는 장소 없음 - customId={}", customId);
                    continue;
                }
                resultBody = resultNode.get("result");
                members.add(place);
                members.addAll(followers.getOrDefault(placeId, List.of()));
            } catch (Exception e) {
                log.error("결과 행 처리 중 오류: {}", e.getMessage(), e);
                continue;
            }
            String type = resultBody == null ? "" : resultBody.path("type").asText();
            if ("succeeded".equals(type)) tally.addUsage(resultBody.path("message").path("usage"));
            for (Place member : members) {
                if (!handled.add(member.getId())) continue;
                try {
                    applyOne(member, type, resultBody, tally);
                } catch (Exception e) {
                    // 저장 실패·코드 오류는 장소 탓이 아니라 실패 횟수에 넣지 않는다
                    log.error("결과 행 처리 중 오류 - placeId={}: {}", member.getId(), e.getMessage(), e);
                }
            }
        }

        // 보강분을 비워 두고 마지막 임베딩 단계에서 채우면 보강이 도는 내내 벡터 검색에서 빠진다.
        // 재임베딩에 실패한 것만 비워 임베딩 단계가 다시 잡게 한다. 속성·주재료 보강은 임베딩 텍스트를 바꾸지 않는다.
        List<Long> succeededIds = tally.succeededIds;
        if (!succeededIds.isEmpty() && (mode() == Mode.V1 || mode() == Mode.DESCRIPTIONS)) {
            List<Place> enriched = succeededIds.stream().map(placeMap::get).toList();
            List<Long> notReembedded = embeddingBatchJob.reembed(enriched);
            if (!notReembedded.isEmpty()) {
                placeRepository.resetEmbeddings(notReembedded);
            }
        }

        Set<Long> succeeded = new HashSet<>(succeededIds);
        List<Long> failedIds = places.stream().map(Place::getId).filter(id -> !succeeded.contains(id)).toList();
        Set<Long> placeFaults = new HashSet<>(tally.unreadableIds);
        if (!succeededIds.isEmpty()) placeFaults.addAll(tally.erroredIds);
        if (countAttempts && !placeFaults.isEmpty() && (mode() == Mode.V1 || mode() == Mode.DESCRIPTIONS)) {
            try {
                if (mode() == Mode.V1) placeRepository.incrementEnrichAttempts(placeFaults);
                else placeRepository.incrementDescriptionRewriteAttempts(placeFaults);
            } catch (Exception e) {
                log.warn("보강 실패 횟수 기록 실패: {}", e.getMessage());
            }
        }
        if (!tally.ingredientCounts.isEmpty()) log.info("주재료 분포: {}", tally.ingredientCounts);
        boolean allExpired = succeededIds.isEmpty() && tally.seen > 0 && tally.expiredOrCanceled == tally.seen;
        // 그사이 다른 단계가 바꾼 행은 이번 실행에서만 빼고 실패로 세지 않는다.
        int failed = failedIds.size() - tally.skippedIds.size();
        log.info("배치 결과 처리 완료 - 성공: {}건(분류 없음 {}건), 실패: {}건, 건너뜀 {}건, 토큰 input={} output={} 캐시읽음={} 캐시쓰기={}",
                succeededIds.size(), tally.unclassifiable, failed, tally.skippedIds.size(), tally.inputTokens,
                tally.outputTokens, tally.cacheReadTokens, tally.cacheWriteTokens);
        // 전부 건너뛴 묶음은 다른 단계가 먼저 쓴 것이라 API 키·모델 오류처럼 중단 판정에 세지 않는다.
        return new ChunkResult(succeededIds.size(), failed, failedIds, !allExpired && failed > 0);
    }

    private static final class Tally {
        final List<Long> succeededIds = new ArrayList<>();
        final Set<Long> erroredIds = new HashSet<>();
        final Set<Long> unreadableIds = new HashSet<>();
        final Set<Long> skippedIds = new HashSet<>();
        long inputTokens;
        long outputTokens;
        long cacheReadTokens;
        long cacheWriteTokens;
        final Map<String, Integer> ingredientCounts = new TreeMap<>();
        int expiredOrCanceled;
        int seen;
        int unclassifiable;

        void addUsage(JsonNode node) {
            inputTokens += node.path("input_tokens").asLong(0);
            outputTokens += node.path("output_tokens").asLong(0);
            cacheReadTokens += node.path("cache_read_input_tokens").asLong(0);
            cacheWriteTokens += node.path("cache_creation_input_tokens").asLong(0);
        }
    }

    private void applyOne(Place place, String type, JsonNode resultBody, Tally t) throws Exception {
        long placeId = place.getId();
        t.seen++;
        if (!"succeeded".equals(type)) {
            log.warn("장소 보강 실패 - placeId={}, result={}", placeId, resultBody);
            if ("expired".equals(type) || "canceled".equals(type)) t.expiredOrCanceled++;
            else if (isRequestError(resultBody)) t.erroredIds.add(placeId);
            return;
        }
        JsonNode message = resultBody.get("message");
        switch (mode()) {
            case INGREDIENTS -> {
                List<String> ingredients = IngredientEnrichment.interpret(message);
                if (ingredients == null) {
                    log.warn("주재료 응답 없음 또는 잘림 - placeId={}", placeId);
                    t.unreadableIds.add(placeId);
                    return;
                }
                if (ingredients.isEmpty()) t.unclassifiable++;
                ingredients.forEach(kind -> t.ingredientCounts.merge(kind, 1, Integer::sum));
                placeRepository.writeIngredients(placeId, objectMapper.writeValueAsString(ingredients));
                t.succeededIds.add(placeId);
            }
            case DESCRIPTIONS -> {
                String rewritten = DescriptionRewrite.interpret(message, place);
                if (rewritten == null) {
                    log.warn("소개 응답 없음·잘림 또는 평판 문구·섞인 글자 남음 - placeId={}", placeId);
                    t.unreadableIds.add(placeId);
                    return;
                }
                if (placeRepository.writeDescription(placeId, rewritten, place.getDescription()) == 0) {
                    log.info("결과를 기다리는 사이 소개가 바뀌어 반영하지 않음 - placeId={}", placeId);
                    t.skippedIds.add(placeId);
                    return;
                }
                place.rewriteDescription(rewritten);
                t.succeededIds.add(placeId);
            }
            case ATTRIBUTES -> {
                PlaceAttributes attributes = AttributeEnrichment.interpret(message, place.getCategory());
                if (attributes == null) {
                    log.warn("속성 분류 응답 없음 또는 잘림 - placeId={}", placeId);
                    t.unreadableIds.add(placeId);
                    return;
                }
                if (attributes.hasNoClassification()) t.unclassifiable++;
                if (placeRepository.writeAttributeClassification(placeId, attributes.classificationJson(),
                        (short) AttributeEnrichment.VERSION) == 0) {
                    log.info("결과를 기다리는 사이 속성이 분류돼 반영하지 않음 - placeId={}", placeId);
                    t.skippedIds.add(placeId);
                    return;
                }
                t.succeededIds.add(placeId);
            }
            case V1 -> {
                String content = EnrichOutputs.extractText(message);
                if (content == null) {
                    log.warn("응답 텍스트 추출 실패 - placeId={}", placeId);
                    t.unreadableIds.add(placeId);
                    return;
                }
                if (!applyEnrichment(place, content)) {
                    t.unreadableIds.add(placeId);
                    return;
                }
                if (placeRepository.writeEnrichment(place) == 0) {
                    log.info("결과를 기다리는 사이 보강됐거나 이름·카테고리가 바뀌어 반영하지 않음 - placeId={}", placeId);
                    t.skippedIds.add(placeId);
                    return;
                }
                t.succeededIds.add(placeId);
            }
        }
    }

    /** errored 결과의 오류 종류. 결과 형식이 error.error.type이라 한 단계 안쪽을 먼저 본다. */
    static boolean isRequestError(JsonNode resultBody) {
        JsonNode error = resultBody == null ? null : resultBody.path("error");
        if (error == null || error.isMissingNode()) return false;
        String type = error.path("error").path("type").asText(error.path("type").asText(""));
        return "invalid_request_error".equals(type);
    }

    /**
     * 응답이 JSON이 아니거나(잘림 포함) 규칙에 맞지 않으면 false라 실패 횟수에 넣고 다음 실행에서 다시 받는다.
     * 그 밖의 예외는 던진다. 코드 오류를 장소 실패 횟수로 세지 않는다.
     */
    boolean applyEnrichment(Place place, String content) {
        try {
            JsonNode root = objectMapper.readTree(EnrichOutputs.extractJson(content));
            if (!DiningEnrichment.isDiningVenue(place.getCategory())) return AttractionEnrichment.apply(root, place);
            boolean overseas = OverseasEnrichment.isOverseas(place.getCountry());
            if (!root.isObject()) {
                log.warn("식당 보강 응답이 JSON 객체가 아님 - placeId={}", place.getId());
                return false;
            }
            Short fame = overseas ? OverseasEnrichment.fame(root.get("traveler_fame")) : null;
            // Wikidata에 없는 유명 식당은 모델이 알려진 곳이라고 할 때만 설명을 받는다.
            boolean describe = DiningEnrichment.isFamous(place.getWikidataSitelinks()) || (fame != null && fame >= 1);
            DiningEnrichment.Result dining = DiningEnrichment.interpret(root,
                    DiningEnrichment.venueOf(place.getCategory()), describe,
                    overseas ? OverseasEnrichment.ownRegionNames(place) : Set.of());
            if (EnrichOutputs.rejectsDescription(place.getId(), place.getName(), overseas, dining.description())) {
                return false;
            }
            place.enrichWith(dining.tags(), dining.description(), dining.timeSlots(), dining.durationMinutes(), null);
            // 카테고리 리프까지 보고 음식 종류를 다시 정한다(태그만으로는 "라멘집"의 면요리가 빠질 수 있다).
            String cuisine = !"MEAL".equals(dining.placeRole()) ? null
                    : overseas ? OverseasEnrichment.cuisineOf(dining.tags(), place.getCategory())
                    : DiningEnrichment.cuisineOf(dining.tags(), place.getCategory());
            place.assignRole(dining.placeRole(), cuisine);
            place.rateFame(fame);
            if (overseas) place.fillNameKo(OverseasEnrichment.nameKo(root.get("name_ko")));
            return true;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("보강 데이터 파싱 실패 - placeId={}, error={}", place.getId(), e.getMessage());
            return false;
        }
    }

    /** 묶음마다 요청이 chunkSize건을 넘지 않게 나눈다. 같은 요청으로 묶인 장소는 결과 한 건으로 반영돼 한 묶음에 넣는다. */
    List<List<Place>> splitIntoChunks(List<Place> places) {
        List<List<Place>> groups = requestGroups(places);
        if (groups.size() < places.size()) {
            log.info("요청 본문이 같은 장소 {}건을 요청 {}건으로 묶음", places.size(), groups.size());
        }
        List<List<Place>> chunks = new ArrayList<>();
        List<Place> current = new ArrayList<>();
        int requests = 0;
        for (List<Place> group : groups) {
            if (requests == chunkSize) {
                chunks.add(current);
                current = new ArrayList<>();
                requests = 0;
            }
            current.addAll(group);
            requests++;
        }
        if (!current.isEmpty()) chunks.add(current);
        return chunks;
    }
}
