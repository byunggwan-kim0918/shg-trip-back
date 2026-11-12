package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.place.destination.KrwRates;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.dto.*;
import com.shg.trip.shgtrip.domain.planning.service.ai.SelectionCallGenerator;
import com.shg.trip.shgtrip.domain.planning.service.ai.OptimizedClaudeAIService;
import com.shg.trip.shgtrip.domain.planning.service.validation.PlaceRegionValidator;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.data.domain.PageRequest;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 최적화 경로 일정 생성 실행기. 후보가 부족하면 ItineraryGenerationExecutor(fallback)로 넘긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OptimizedGenerationExecutor {

    private final OptimizedClaudeAIService optimizedClaudeAIService;
    private final VectorSearchQueryService vectorSearchQueryService;
    private final FallbackDecider fallbackDecider;
    private final SelectionCallGenerator selectionCallGenerator;
    private final HardValidator hardValidator;
    private final IndexResultMapper indexResultMapper;
    private final RouteOptimizer routeOptimizer;
    private final ItineraryGenerationExecutor fallbackExecutor;
    private final ItinerarySaveHelper saveHelper;
    private final StoryGenerationService storyGenerationService;
    private final GenerationResultStore resultStore;
    private final CancellationRegistry cancellationRegistry;
    private final GenerationPolicyService generationPolicyService;
    private final PlaceRepository placeRepository;
    private final PlaceRegionValidator placeRegionValidator;
    private final GenerationTraceRecorder traceRecorder;
    private final Destinations destinations;
    private final PlanningCountries planningCountries;

    /** 한국 운전면허(국제운전면허 포함)로 운전할 수 없는 나라. 자동차 요청은 택시·차량 호출로 본다. */
    private static final Set<String> NO_KOREAN_LICENSE_COUNTRIES = Set.of("CN", "VN");

    /** 공항 부지 안 POI는 공항 대표 좌표에서 2~3km까지 떨어진다. 서로 다른 두 공항이 이 거리 안에 있지는 않다. */
    private static final double SAME_AIRPORT_KM = 3.0;

    /** 보완 숙소가 기존 후보 median 중심점에서 이 거리(km)를 넘으면 지역 오태깅으로 보고 제외한다. */
    private static final double MAX_ACCOMMODATION_RADIUS_KM = 100.0;
    /** 보완 숙소 좌표 검증용 region별 최소 표본 수. 미만이면 중심점을 믿을 수 없어 필터를 건너뛴다. */
    private static final int ACCOMMODATION_GEO_MIN_SAMPLE = 4;
    /** 자유입력 장소명 DB 검색 시 거리 비교 대상으로 가져올 동명 후보 수. */
    private static final int CUSTOM_PLACE_SEARCH_LIMIT = 10;

    @Async("planningExecutor")
    public void execute(String jobId, ItineraryGenerateRequest request, Long userId, GenerationStream emitter) {
        MDC.put("jobId", jobId);
        final long pipelineStart = System.nanoTime();
        long stageMark = pipelineStart;
        try {
            // [10%] Haiku enrichInput
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "ENRICHING", 10, "입력을 분석하고 있습니다...");

            EnrichmentResult enrichResult = executeWithHeartbeat(emitter,
                    () -> optimizedClaudeAIService.enrichInput(request));

            if (!enrichResult.valid()) {
                // 모든 요청이 이 경로를 먼저 지나므로 여기가 유일한 실패 카운트 지점이다. fallback 재-enrich는 세지 않는다.
                // AI 오탐·포맷 오류는 errorCode로 정책 서비스가 집계에서 뺀다.
                generationPolicyService.recordValidationFailure(userId, enrichResult.errorCode());
                sendSseError(emitter, enrichResult.errorCode(), enrichResult.errorMessage());
                return;
            }

            VectorEnrichedInput.CompanionType chosen = VectorEnrichedInput.companionOf(request.companion());
            VectorEnrichedInput withCompanion = enrichResult.enrichedInput().withCompanion(chosen,
                    request.partySize() != null ? request.partySize() : VectorEnrichedInput.defaultPartySize(chosen));
            boolean carWithoutLicense = isCarWithoutKoreanLicense(withCompanion);
            VectorEnrichedInput enrichedInput = carWithoutLicense ? withCompanion.withTransportPref("any") : withCompanion;
            long days = ChronoUnit.DAYS.between(enrichedInput.startDate(), enrichedInput.endDate()) + 1;
            stageMark = logStage("enrich", stageMark);

            if (!planningCountries.isEnabled(enrichedInput.country())) {
                log.info("OptimizedGeneration 중단 (jobId={}, reason=country_not_enabled, country={})",
                        jobId, enrichedInput.country());
                sendSseError(emitter, ErrorCode.INSUFFICIENT_PLACE_DATA.getCode(),
                        planningCountries.notEnabledMessage(enrichedInput.country()));
                return;
            }

            // [20%] 카테고리별 벡터 검색
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "SEARCHING", 20, "장소를 검색하고 있습니다...");

            List<PlaceCandidate> candidatesRaw = executeWithHeartbeat(emitter,
                    () -> vectorSearchQueryService.search(enrichedInput));

            List<String> pickNotices = new ArrayList<>();
            if (carWithoutLicense) {
                pickNotices.add(Destinations.countryNameKo(enrichedInput.country().trim().toUpperCase(Locale.ROOT))
                        + "은 한국 운전면허로 운전할 수 없어 택시·차량 호출로 다니는 기준으로 짰어요.");
            }
            List<String> unresolvedNames = new ArrayList<>();
            List<Place> selectedPlaces = new ArrayList<>(resolveSelectedPlaces(request, pickNotices));
            // DB 조회뿐이지만 느려질 때 프록시가 끊지 않게 heartbeat로 감싼다.
            selectedPlaces.addAll(executeWithHeartbeat(emitter,
                    () -> resolveCustomPlaces(request, enrichedInput, candidatesRaw, emitter, unresolvedNames)));
            placeRegionValidator.validate(selectedPlaces, enrichedInput); // 불일치 시 BusinessException → SSE error
            List<PlaceCandidate> candidates = mergeSelectedPlaces(candidatesRaw, selectedPlaces);
            // 후보 수십 건의 median이라 저장 단계의 오매칭 거리 판정 기준으로 쓴다.
            final List<double[]> destinationReference = regionCenters(candidates);
            stageMark = logStage("search", stageMark);

            // [25%] Fallback/컴팩트 분기 판단
            if (cancellationRegistry.isCancelled(jobId)) return;
            logCandidatePool(candidates, days);
            if (candidates.isEmpty()) {
                // 후보가 하나도 없으면 fallback(Sonnet)이 이름만으로 일정을 지어도 DB에서 해소되는 장소가 없다.
                log.info("OptimizedGeneration 중단 (jobId={}, reason=no_candidates)", jobId);
                ErrorCode code = ErrorCode.INSUFFICIENT_PLACE_DATA;
                sendSseError(emitter, code.getCode(), code.getMessage());
                return;
            }
            FallbackDecider.PoolQuality poolQuality = fallbackDecider.assess(candidates, days);

            if (poolQuality == FallbackDecider.PoolQuality.FALLBACK && isOverseas(enrichedInput.country())) {
                // fallback은 모델이 지은 장소 이름을 이름·주소로 DB에서 찾는데, 해외 장소는 표기가 달라 거의 찾지 못한다.
                log.info("OptimizedGeneration 중단 (jobId={}, reason=overseas_sparse, regions={})",
                        jobId, enrichedInput.regions());
                sendSseError(emitter, ErrorCode.INSUFFICIENT_PLACE_DATA.getCode(), sparseOverseasMessage(enrichedInput));
                return;
            }
            if (poolQuality == FallbackDecider.PoolQuality.FALLBACK) {
                sendSseEvent(emitter, "FALLBACK", 30, "Fallback 경로로 일정을 생성합니다...");
                log.info("OptimizedGeneration → Fallback (jobId={}, reason=insufficient_candidates)", jobId);
                // fallback은 새 입력 필드를 쓰지 않고 문장만 읽으므로 사용자가 고른 값을 문장에 붙여 넘긴다.
                fallbackExecutor.execute(jobId, request.withTripFactsInDescription(), userId, emitter);
                return;
            }
            // 유효 관광지가 빈약하면 억지로 채우지 않고 quota 하한을 완화한 컴팩트 일정으로 간다.
            boolean compactMode = poolQuality == FallbackDecider.PoolQuality.COMPACT;
            if (compactMode) {
                sendSseEvent(emitter, "SEARCHING", 30,
                        "이 지역은 장소 데이터를 보강 중이에요. 알찬 곳 위주로 컴팩트하게 구성합니다...");
                log.info("OptimizedGeneration 컴팩트 모드 (jobId={}, reason=sparse_attractions)", jobId);
            }

            // [35%] 후보 정리
            // Google Places 동기화는 하지 않는다. Maps 약관 3.2.3(a)상 받아온 세부정보를 DB에 보관할 수 없다.
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "SYNCING", 35, "장소 정보를 정리하고 있습니다...");

            List<Long> placeIds = candidates.stream()
                    .map(PlaceCandidate::placeId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());

            // 배치가 보강 우선순위를 정하는 데 쓴다.
            if (!placeIds.isEmpty()) {
                placeRepository.markCandidateAppearance(placeIds, OffsetDateTime.now());
            }

            // 배로만 가는 부속섬 장소는 사용자가 원할 때만 남긴다. 토큰 검증은 풀 전체로 한 번 한다.
            final List<String> ferryTokens = FerryAreaFilter.activeTokens(enrichedInput.ferryOnlyAreas(),
                    enrichedInput.description(), request.customPlaceNames(), candidates);
            // 다인실 숙소를 뺀 뒤에 보완 여부를 판단하고, 보완 숙소도 같은 필터를 거친다(두 번째 exclude).
            final List<PlaceCandidate> enrichedCandidates = appendGateways(dedupePhysicalCandidates(
                    FerryAreaFilter.exclude(ensureAccommodationCandidates(
                            excludeDormLodging(FerryAreaFilter.exclude(enrichCandidatesFromDb(candidates),
                                    ferryTokens), enrichedInput),
                            enrichedInput.regions(), enrichedInput), ferryTokens)), enrichedInput.regions());
            stageMark = logStage("sync", stageMark);

            // [50%] Call 1: Sonnet selectPlaces
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "SELECTING", 50, "날짜별 장소를 선택하고 있습니다...");

            SelectionOutput rawSelectionOutput = executeWithHeartbeat(emitter,
                    () -> selectionCallGenerator.selectPlaces(enrichedInput, enrichedCandidates));

            SelectionOutput dayComposition = indexResultMapper.stripZoneLevelPicks(
                    indexResultMapper.stripCodeOwnedFields(rawSelectionOutput), enrichedCandidates);

            // 숙소 배정보다 먼저 돌려야 새로 찾은 숙소가 배정 대상에 들어간다. selectionOutput이 인덱스를 참조하므로 뒤에 이어 붙인다.
            // 덧붙인 후보는 벡터 검색 결과라 체류시간·시간대·입장료가 비어 있어 DB 값으로 채운다.
            final List<PlaceCandidate> candidatesWithLocalStays = dropAppendedSameWikidataItem(
                    enrichCandidatesFromDb(appendNearbyReserve(
                            appendNearbyAccommodations(enrichedCandidates, dayComposition, enrichedInput, ferryTokens),
                            dayComposition, enrichedInput, ferryTokens)),
                    enrichedCandidates.size());

            final SelectionOutput selectionOutput = indexResultMapper.injectRequiredPlaces(
                    indexResultMapper.fillMissingAccommodation(dayComposition, candidatesWithLocalStays),
                    candidatesWithLocalStays);
            stageMark = logStage("select", stageMark);

            // [65%] day/순서/시간/교통/대안을 결정론적으로 확정
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "OPTIMIZING", 65, "동선과 시간을 확정하고 있습니다...");

            final ScheduleArgs scheduleArgs = ScheduleArgs.of(enrichedInput, compactMode,
                    request.arrivalTime(), request.departureTime());
            RouteOptimizer.ScheduleResult scheduleResult = executeWithHeartbeat(emitter,
                    () -> routeOptimizer.schedule(selectionOutput, candidatesWithLocalStays, scheduleArgs));
            List<StepData> fixedSteps = scaleCostsForParty(scheduleResult.steps(), enrichedInput.partySize());
            List<String> qualityNotices = new ArrayList<>(scheduleResult.notices());
            qualityNotices.addAll(pickNotices);
            qualityNotices.addAll(mustVisitNotices(unresolvedNames,
                    missingUserSelected(candidatesWithLocalStays, fixedSteps)));

            // 인위적 sleep을 넣으면 SSE 스레드가 blocking된다.
            sendStepStream(emitter, fixedSteps);
            // 로그만 남기면 숙소 없는 일정이 표시 없이 나간다. 상세 화면 표시는 itineraries.quality_notices가 맡는다.
            sendQualityNotices(emitter, qualityNotices);
            stageMark = logStage("optimize", stageMark);

            // [80%] 구조 검증. 결정론적 코드라 실패는 재시도가 아니라 버그로 취급한다.
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "VALIDATING", 80, "일정을 검증하고 있습니다...");

            String destination = enrichedInput.normalizedDestination() != null
                    ? enrichedInput.normalizedDestination() : enrichedInput.destination();
            // StorySaveHelper가 tags를 덮지 않으므로 여기서 확정하지 않으면 tags가 빈값으로 남는다.
            List<String> tagSeed = enrichedInput.searchTags() != null && !enrichedInput.searchTags().isEmpty()
                    ? enrichedInput.searchTags() : enrichedInput.themes();
            String draftTitle = buildDraftTitle(destination, enrichedInput);
            ItineraryData draftData = indexResultMapper.toDraftItineraryData(
                    fixedSteps, destination, draftTitle, tagSeed, qualityNotices);
            HardValidationResult validationResult = hardValidator.validate(draftData);
            if (!validationResult.valid()) {
                log.error("Optimized 경로 구조 검증 실패 (결정론적 로직 버그 가능성): {}", validationResult.failureReason());
            }

            // [90%] 구조 일정 저장. story는 비동기로 채운다.
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "SAVING", 90, "일정을 저장하고 있습니다...");

            EnrichedInput legacyInput = toLegacyEnrichedInput(enrichedInput);
            Itinerary saved = saveHelper.save(draftData, legacyInput, userId, true, destinationReference);
            stageMark = logStage("save", stageMark);
            // 완료 전송보다 먼저 남긴다. 뒤에 남기면 그 사이 지운 일정의 기록(요청 문장 포함)이 삭제를 피해 남는다.
            traceRecorder.record(saved, jobId, request, enrichedInput, rawSelectionOutput,
                    selectionOutput, candidatesWithLocalStays, scheduleArgs,
                    (System.nanoTime() - pipelineStart) / 1_000_000);

            // [100%] 구조 완료. emitter는 story-ready까지 닫지 않는다.
            resultStore.save(jobId, saved.getId(), userId);
            // 30일 쿼터는 성공 시에만 소모한다.
            generationPolicyService.recordSuccess(userId);
            sendSseEvent(emitter, "COMPLETE", 100, "일정 생성이 완료되었습니다.");
            sendCompleteKeepOpen(emitter, saved.getId());

            log.info("OptimizedGeneration 구조 완료: jobId={}, itineraryId={}, days={}",
                    jobId, saved.getId(), days);
            log.info("stage=total elapsedMs={}", (System.nanoTime() - pipelineStart) / 1_000_000);

            // expectedTitle은 실제로 저장한 임시 제목이어야 한다. concept을 넘기면 0행 업데이트가 되어 제목이 임시값으로 남는다.
            storyGenerationService.generateAndAttach(
                    jobId, emitter, saved.getId(), fixedSteps, draftTitle, enrichedInput,
                    selectionOutput.concept());

        } catch (BusinessException e) {
            log.error("OptimizedGeneration 비즈니스 오류 (jobId={}): {}", jobId, e.getMessage());
            sendSseError(emitter, e.getErrorCode().getCode(), e.userMessage());
        } catch (Exception e) {
            log.error("OptimizedGeneration 예기치 않은 오류 (jobId={})", jobId, e);
            sendSseError(emitter, "UNEXPECTED_ERROR", "일정 생성 중 오류가 발생했습니다.");
        } finally {
            MDC.remove("jobId");
        }
    }

    // ── Private helpers ──

    /**
     * 1인 기준 식사·카페·입장료를 인원수만큼 곱한다. 숙소(1실)·교통(차 1대)은 그대로 둔다.
     * 대안을 고르면 그 비용이 스텝 비용을 대체하므로 대안도 같이 곱한다.
     * RouteOptimizer는 순수 계산기라 인원 같은 표시용 배수를 섞지 않으려고 여기서 한다.
     */
    static List<StepData> scaleCostsForParty(List<StepData> steps, Integer partySize) {
        if (partySize == null || partySize <= 1) return steps;
        BigDecimal factor = BigDecimal.valueOf(partySize);
        List<StepData> scaled = new ArrayList<>(steps.size());
        for (StepData s : steps) {
            List<AlternativeData> alts = s.alternatives() == null ? null : s.alternatives().stream()
                    .map(a -> isPerPersonCost(a.category()) && a.estimatedCost() != null
                            ? a.withEstimatedCost(a.estimatedCost().multiply(factor))
                            : a)
                    .toList();
            BigDecimal cost = s.place() != null && isPerPersonCost(s.place().category()) && s.estimatedCost() != null
                    ? s.estimatedCost().multiply(factor) : s.estimatedCost();
            scaled.add(new StepData(s.stepOrder(), s.dayNumber(), s.startTime(), s.endTime(), s.place(), alts,
                    s.transportationMode(), s.transportationDuration(), s.transportationDistance(),
                    s.transportationCost(), s.notes(), cost));
        }
        return scaled;
    }

    private static boolean isPerPersonCost(String category) {
        String major = PlaceCategoryConstants.majorCategory(category);
        return "DINING".equals(major) || "CAFE".equals(major) || "ATTRACTION".equals(major);
    }

    /**
     * 커플·가족 여행이면 다인실 숙소(게스트하우스·호스텔)를 후보에서 뺀다. 사용자가 직접 고른 숙소는 남긴다.
     *
     * <p>숙소가 들어오는 세 경로(검색 풀·DB 보완·day별 근처 검색)가 모두 거쳐야 한다. 한 경로만 막으면
     * 나머지 경로로 같은 숙소가 다시 들어온다.
     */
    private List<PlaceCandidate> excludeDormLodging(List<PlaceCandidate> candidates, VectorEnrichedInput input) {
        if (input == null || !input.companion().avoidsDormLodging()) return candidates;
        List<PlaceCandidate> kept = candidates.stream()
                .filter(c -> c.userSelected()
                        || !"LODGING".equals(PlaceCategoryConstants.majorCategory(c.category()))
                        || !PlaceCategoryConstants.isDormLodging(c.attributes(), c.name(), c.category(), c.tags()))
                .toList();
        if (kept.size() < candidates.size()) {
            log.info("다인실 숙소 제외({}): {}개 → {}개", input.companion(), candidates.size(), kept.size());
        }
        return kept;
    }

    /** 이동수단별 예비 후보 검색 반경(km). 도보는 걸어서 들를 만한 거리만. */
    private static double reserveRadiusKm(String transportPref) {
        if ("walk".equals(transportPref)) return 3.0;
        if ("car".equals(transportPref)) return 15.0;
        return 10.0;
    }

    private static final List<Map.Entry<PlaceCategoryConstants.SearchRole, Integer>> RESERVE_PER_DAY = List.of(
            Map.entry(PlaceCategoryConstants.SearchRole.ATTRACTION, 4),
            Map.entry(PlaceCategoryConstants.SearchRole.CAFE, 2),
            Map.entry(PlaceCategoryConstants.SearchRole.RESTAURANT, 3));

    /**
     * 날짜별 방문지 중심 근처의 예비 후보를 덧붙인다. SelectionOutput에는 넣지 않아 RouteOptimizer의 미사용 후보
     * 경로(빈 시간 채우기·끼니 보충·교체)만 쓴다. 숙소 보완과 같은 필터(다인실·도선 섬)를 거친다.
     */
    private List<PlaceCandidate> appendNearbyReserve(List<PlaceCandidate> candidates, SelectionOutput selection,
                                                     VectorEnrichedInput input, List<String> ferryTokens) {
        if (selection == null || selection.days() == null || selection.days().isEmpty()) return candidates;
        List<double[]> centers = selection.days().stream().map(d -> dayCentroid(d, candidates)).toList();
        double radius = reserveRadiusKm(input.transportPref());

        List<PlaceCandidate> result = new ArrayList<>(candidates);
        Set<String> known = knownKeys(candidates);
        for (Map.Entry<PlaceCategoryConstants.SearchRole, Integer> role : RESERVE_PER_DAY) {
            for (List<PlaceCandidate> found : vectorSearchQueryService.searchNearEach(
                    input, role.getKey(), centers, radius, role.getValue())) {
                for (PlaceCandidate c : excludeDormLodging(FerryAreaFilter.exclude(found, ferryTokens), input)) {
                    if (!addIfNew(known, c)) continue;
                    result.add(reindexCandidate(c, result.size() + 1));
                }
            }
        }
        if (result.size() > candidates.size()) {
            log.info("날짜별 예비 후보 보완: {}개 → {}개 (반경 {}km)", candidates.size(), result.size(), radius);
        }
        return result;
    }

    /** day 방문지 centroid에서 이 거리(km)를 넘는 숙소밖에 없으면 그 day 기준으로 추가 검색한다. */
    private static final double LOCAL_STAY_TRIGGER_KM = 30.0;
    /** 추가 검색 반경(km). 트리거 거리보다 좁게 잡아 그 지역 숙소만 들어오게 한다. */
    private static final double LOCAL_STAY_RADIUS_KM = 20.0;
    private static final int LOCAL_STAY_LIMIT = 3;

    /**
     * 각 day centroid에서 가장 가까운 숙소가 {@link #LOCAL_STAY_TRIGGER_KM}를 넘으면 그 기준으로 1회 추가 검색한다.
     * 기존 인덱스를 그대로 두고 뒤에 이어 붙여 selectionOutput의 인덱스 참조가 깨지지 않게 한다.
     */
    private List<PlaceCandidate> appendNearbyAccommodations(List<PlaceCandidate> candidates,
                                                            SelectionOutput selection,
                                                            VectorEnrichedInput input,
                                                            List<String> ferryTokens) {
        if (selection == null || selection.days() == null || selection.days().isEmpty()) return candidates;

        List<PlaceCandidate> lodgings = candidates.stream()
                .filter(c -> "LODGING".equals(PlaceCategoryConstants.majorCategory(c.category())))
                .filter(this::hasValidCoords)
                .toList();

        List<PlaceCandidate> result = new ArrayList<>(candidates);
        Set<String> knownPlaceIds = knownKeys(candidates);

        for (SelectionOutput.DayPlan day : selection.days()) {
            double[] centroid = dayCentroid(day, candidates);
            if (centroid == null) continue;

            double nearest = lodgings.stream()
                    .mapToDouble(c -> GeoUtils.haversine(centroid,
                            new double[]{c.latitude().doubleValue(), c.longitude().doubleValue()}))
                    .min().orElse(Double.MAX_VALUE);
            // 숙소 후보가 아예 없으면 거리와 무관하게 발동한다.
            boolean noLodgingAtAll = lodgings.isEmpty();
            if (!noLodgingAtAll && nearest <= LOCAL_STAY_TRIGGER_KM) continue;

            log.info("day={} 숙소 보완 검색: {} — centroid 기준 추가 검색", day.dayNumber(),
                    noLodgingAtAll ? "후보 풀에 숙소 0개"
                            : "가장 가까운 숙소가 " + String.format("%.0f", nearest) + "km");
            List<PlaceCandidate> extra = vectorSearchQueryService.searchAccommodationsNear(
                    input, centroid, LOCAL_STAY_RADIUS_KM, LOCAL_STAY_LIMIT, result.size() + 1);
            for (PlaceCandidate c : excludeDormLodging(FerryAreaFilter.exclude(extra, ferryTokens), input)) {
                if (!addIfNew(knownPlaceIds, c)) continue; // 이미 있는 숙소
                result.add(reindexCandidate(c, result.size() + 1));
            }
        }
        if (result.size() > candidates.size()) {
            log.info("숙소 후보 보완: {}개 → {}개", candidates.size(), result.size());
        }
        return result;
    }

    /** 유효 좌표가 없으면 null. */
    private double[] dayCentroid(SelectionOutput.DayPlan day, List<PlaceCandidate> candidates) {
        List<Double> lats = new ArrayList<>();
        List<Double> lngs = new ArrayList<>();
        for (Integer idx : day.placeIndices() != null ? day.placeIndices() : List.<Integer>of()) {
            if (idx == null || idx < 1 || idx > candidates.size()) continue;
            PlaceCandidate c = candidates.get(idx - 1);
            if (!hasValidCoords(c)) continue;
            lats.add(c.latitude().doubleValue());
            lngs.add(c.longitude().doubleValue());
        }
        if (lats.isEmpty()) return null;
        return new double[]{
                lats.stream().mapToDouble(Double::doubleValue).average().orElse(0),
                lngs.stream().mapToDouble(Double::doubleValue).average().orElse(0)};
    }

    /**
     * 구조 저장 시점의 임시 제목("제주 3박 4일"). concept은 한 줄 설명문이라 제목으로 쓰면 두 줄로 넘친다.
     * 스토리 생성이 실패하면 이 값이 최종 제목이 된다.
     */
    String buildDraftTitle(String destination, VectorEnrichedInput input) {
        String place = (destination != null && !destination.isBlank()) ? destination.trim() : "여행";
        if (input == null || input.startDate() == null || input.endDate() == null) {
            return place + " 여행";
        }
        long days = ChronoUnit.DAYS.between(input.startDate(), input.endDate()) + 1;
        if (days <= 1) return place + " 당일치기";
        return place + " " + (days - 1) + "박 " + days + "일";
    }

    private void logCandidatePool(List<PlaceCandidate> candidates, long days) {
        Map<String, Long> byMajor = candidates.stream().collect(Collectors.groupingBy(
                c -> PlaceCategoryConstants.majorCategory(c.category()), Collectors.counting()));
        // FallbackDecider가 쓰는 키워드 기준 카운트. 대분류 매핑과 다를 수 있어 함께 남긴다.
        long lodging = candidates.stream().filter(c -> containsIgnoreCase(c.category(), "lodging")).count();
        long restaurant = candidates.stream().filter(c -> containsIgnoreCase(c.category(), "restaurant")).count();
        long attraction = candidates.stream().filter(c ->
                containsIgnoreCase(c.category(), "landmarks")
                        || containsIgnoreCase(c.category(), "arts and entertainment")
                        || containsIgnoreCase(c.category(), "sports and recreation")
                        || containsIgnoreCase(c.category(), "outdoors")).count();
        log.info("후보 풀: total={}, 대분류={}, 판정기준(lodging={}/1, restaurant={}/{}, attraction={}/{})",
                candidates.size(), byMajor, lodging, restaurant, days * 2, attraction, days);
        logDiningSubTypes(candidates);
    }

    /**
     * 식사 후보의 세부 유형 분포. 유형 상한이 "대체 후보 없음"으로 실패할 때 후보 풀 자체가
     * 한 유형으로 쏠린 것인지 구분하려고 남긴다.
     */
    private void logDiningSubTypes(List<PlaceCandidate> candidates) {
        Map<String, Long> bySubType = candidates.stream()
                .filter(c -> PlaceCategoryConstants.isMealPlace(c.name(), c.category()))
                .collect(Collectors.groupingBy(
                        c -> PlaceCategoryConstants.subType(c.name(), c.category(), List.of(), c.description()),
                        Collectors.counting()));
        if (bySubType.isEmpty()) return;
        log.info("식사 후보 세부유형 분포: {}", bySubType.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> e.getKey() + "(" + e.getValue() + ")")
                .collect(Collectors.joining(", ")));
    }

    private boolean containsIgnoreCase(String value, String needle) {
        return value != null && value.toLowerCase().contains(needle);
    }

    /** 파이프라인 단계 소요시간(ms)을 로깅하고, 다음 구간 측정을 위한 기준 시각(nanoTime)을 반환한다. */
    private long logStage(String stage, long sinceNanos) {
        long now = System.nanoTime();
        log.info("stage={} elapsedMs={}", stage, (now - sinceNanos) / 1_000_000);
        return now;
    }

    private List<PlaceCandidate> enrichCandidatesFromDb(List<PlaceCandidate> candidates) {
        List<Long> placeIds = candidates.stream()
                .map(PlaceCandidate::placeId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        if (placeIds.isEmpty()) return candidates;

        Map<Long, Place> placeMap = placeRepository.findAllById(placeIds).stream()
                .collect(Collectors.toMap(Place::getId, Function.identity()));

        return candidates.stream().map(c -> {
            Place place = c.placeId() != null ? placeMap.get(c.placeId()) : null;
            if (place == null) return c;
            // 저장 단계가 id로 못 찾으면 (name,address)로 조회하므로 프롬프트·스텝·저장이 같은 DB 값을 봐야 한다.
            return new PlaceCandidate(
                    c.index(), c.placeId(),
                    place.getName() != null ? place.getName() : c.name(),
                    place.getAddress() != null ? place.getAddress() : c.address(),
                    c.category(),
                    c.tags(), c.region(), c.country(), c.latitude(), c.longitude(),
                    place.getDescription() != null ? place.getDescription() : c.description(),
                    place.getRating() != null ? place.getRating() : c.rating(),
                    c.similarityScore(),
                    place.getPriceLevel(),
                    place.getOpeningHours(),
                    place.getRecommendedTimeSlots(),
                    place.getRecommendedDurationMinutes(),
                    c.userSelected(),
                    KrwRates.admissionWon(place),
                    // DB 주소로 세부지역을 다시 뽑고, 실패하면 기존 값을 유지한다.
                    subRegionOrKeep(place.getAddress(), c.subRegion()),
                    place.getUserRatingCount() != null ? place.getUserRatingCount() : c.userRatingCount(),
                    place.getWikidataSitelinks() != null
                            ? place.getWikidataSitelinks() : c.wikidataSitelinks(),
                    place.getPlaceRole() != null ? place.getPlaceRole() : c.placeRole(),
                    place.getCuisine() != null ? place.getCuisine() : c.cuisine(),
                    false, null,
                    place.getAttributes() != null ? place.getAttributes() : c.attributes(),
                    place.getNameKo() != null ? place.getNameKo() : c.nameKo(),
                    place.getTravelerFame() != null ? place.getTravelerFame() : c.travelerFame()
            );
        }).collect(Collectors.toList());
    }

    /**
     * 물리적으로 같은 장소의 중복 레코드를 1개만 남긴다. 같은 장소가 여러 스텝·대안에 반복되지 않게
     * Sonnet 선택 이전에 한다. RouteOptimizer.byIndex가 위치 기반이라 1-based 인덱스를 다시 연속 부여한다.
     */
    List<PlaceCandidate> dedupePhysicalCandidates(List<PlaceCandidate> candidates) {
        Map<String, List<PlaceCandidate>> groups = new LinkedHashMap<>();
        for (PlaceCandidate c : candidates) {
            groups.computeIfAbsent(physicalKey(c), k -> new ArrayList<>()).add(c);
        }

        List<PlaceCandidate> representatives = new ArrayList<>(groups.size());
        for (List<PlaceCandidate> group : groups.values()) {
            representatives.add(pickRepresentative(group));
        }

        // 출처별 이중 레코드는 좌표가 수백 m 달라 좌표 키(±11m)를 통과하므로, 같은 region의 동일 이름도 병합한다.
        Map<String, List<PlaceCandidate>> nameGroups = new LinkedHashMap<>();
        for (PlaceCandidate c : representatives) {
            nameGroups.computeIfAbsent(nameKey(c), k -> new ArrayList<>()).add(c);
        }
        representatives = new ArrayList<>(nameGroups.size());
        for (List<PlaceCandidate> group : nameGroups.values()) {
            representatives.add(pickRepresentative(group));
        }
        representatives = dropStoresInsideVenue(representatives);
        representatives = dropBilingualTwins(representatives);

        representatives.sort(Comparator.comparingInt(PlaceCandidate::index));
        List<PlaceCandidate> result = new ArrayList<>(representatives.size());
        for (int i = 0; i < representatives.size(); i++) {
            result.add(reindexCandidate(representatives.get(i), i + 1));
        }

        if (result.size() < candidates.size()) {
            log.info("후보 물리적 dedupe: {}개 → {}개", candidates.size(), result.size());
        }
        return result;
    }

    private static final double STORE_INSIDE_VENUE_KM = 0.3;
    private static final int VENUE_NAME_MIN_LENGTH = 3;

    /** 상업시설 이름으로 시작하는 근처 매장은 그 건물 안이라 둘 다 두면 같은 건물을 두 번 방문한다. */
    static List<PlaceCandidate> dropStoresInsideVenue(List<PlaceCandidate> candidates) {
        List<PlaceCandidate> result = new ArrayList<>(candidates.size());
        for (PlaceCandidate c : candidates) {
            if (isStoreInsideVenue(c, candidates)) {
                log.info("건물 안 매장 제외: {}", c.name());
            } else {
                result.add(c);
            }
        }
        return result;
    }

    static boolean isStoreInsideVenue(PlaceCandidate c, List<PlaceCandidate> pool) {
        if (c.userSelected() || !isRetail(c) || !hasCoords(c)) return false;
        String name = compactName(c);
        return pool.stream().anyMatch(v -> v != c && isRetail(v) && hasCoords(v)
                && compactName(v).length() >= VENUE_NAME_MIN_LENGTH
                && name.length() > compactName(v).length()
                && name.startsWith(compactName(v))
                && GeoUtils.haversine(v.latitude().doubleValue(), v.longitude().doubleValue(),
                        c.latitude().doubleValue(), c.longitude().doubleValue()) <= STORE_INSIDE_VENUE_KM);
    }

    private static final double BILINGUAL_TWIN_KM = 1.0;
    /** 영어 이름 꼬리. 괄호로 감싼 병기("東京ドームシティ (Tokyo Dome City)")도 받는다. */
    private static final Pattern LATIN_TAIL = Pattern.compile("[\\s(（]+[A-Za-z0-9][A-Za-z0-9 .,'’&()（）/-]*");
    /** 구역 명소 이름으로 시작하는 FSQ 행은 그 구역 안 가게("アメリカ村 Club DROP")다. */
    private static final Set<String> AREA_LEAVES = Set.of("pedestrian plaza", "plaza");

    /**
     * Wikidata 명소 이름 뒤에 영어 이름만 붙은 FSQ 행("饒河街觀光夜市 Raohe St. Night Market")을 뺀다. 인기도 매칭은
     * 영어 부분이 영어 라벨과 같아야 항목을 붙여 이 행은 항목 없이 남고, 이름 키도 달라 위 병합을 지나간다.
     */
    static List<PlaceCandidate> dropBilingualTwins(List<PlaceCandidate> candidates) {
        List<PlaceCandidate> result = new ArrayList<>(candidates.size());
        for (PlaceCandidate c : candidates) {
            if (candidates.stream().anyMatch(item -> isBilingualTwin(c, item))) {
                log.info("이중 표기 중복 제외: {}", c.name());
            } else {
                result.add(c);
            }
        }
        return result;
    }

    static boolean isBilingualTwin(PlaceCandidate twin, PlaceCandidate item) {
        if (twin == item || twin.userSelected() || !isWikidataItem(item) || isWikidataItem(twin)) return false;
        if (twin.name() == null || item.name() == null || !Objects.equals(twin.region(), item.region())) return false;
        String base = item.name().strip();
        String name = twin.name().strip();
        if (base.chars().allMatch(ch -> ch < 0x80) || !name.startsWith(base)
                || !LATIN_TAIL.matcher(name.substring(base.length())).matches()) {
            return false;
        }
        if (isAreaLeaf(item.category()) || !PlaceCategoryConstants.majorCategory(item.category())
                .equals(PlaceCategoryConstants.majorCategory(twin.category()))) {
            return false;
        }
        return hasCoords(twin) && hasCoords(item)
                && GeoUtils.haversine(item.latitude().doubleValue(), item.longitude().doubleValue(),
                        twin.latitude().doubleValue(), twin.longitude().doubleValue()) <= BILINGUAL_TWIN_KM;
    }

    private static boolean isWikidataItem(PlaceCandidate c) {
        return c.wikidataSitelinks() != null && c.wikidataSitelinks() > 0;
    }

    private static boolean isAreaLeaf(String category) {
        if (category == null) return false;
        return AREA_LEAVES.contains(category.substring(category.lastIndexOf('>') + 1).strip().toLowerCase(Locale.ROOT));
    }

    private static boolean isRetail(PlaceCandidate c) {
        return c.category() != null && c.category().toLowerCase().startsWith("retail");
    }

    private static boolean hasCoords(PlaceCandidate c) {
        return c.latitude() != null && c.longitude() != null
                && !GeoUtils.isZeroCoord(c.latitude(), c.longitude());
    }

    private static String compactName(PlaceCandidate c) {
        return c.name() == null ? "" : c.name().toLowerCase().replaceAll("\\s+", "");
    }

    /** 대표 선정: 카테고리 신뢰도 → 한글명(한국 사용자 노출용) → rating → 좌표 유효 순. */
    private PlaceCandidate pickRepresentative(List<PlaceCandidate> group) {
        PlaceCandidate rep = group.stream()
                .max(Comparator
                        .comparingInt((PlaceCandidate c) -> isTrustedCategory(c) ? 1 : 0)
                        // 한/영 중복("성읍랜드" vs "Sungeup Land")은 한국 사용자에게 보일 한글명(해외는 한국어 이름) 레코드를 대표로 둔다.
                        .thenComparingInt(c -> hasKoreanName(c) ? 1 : 0)
                        .thenComparing(c -> c.rating() != null ? c.rating() : BigDecimal.ZERO)
                        .thenComparingInt(c -> hasValidCoords(c) ? 1 : 0))
                .orElse(group.get(0));
        // 사용자가 고른 레코드가 다른 대표에 흡수돼도 필수 포함이 유지되게 userSelected를 전파한다.
        if (group.stream().anyMatch(PlaceCandidate::userSelected)) {
            rep = rep.asUserSelected();
        }
        return rep;
    }

    /**
     * 이름 동일성 키: region + 공백제거·소문자 이름. 이름이 없으면 병합하지 않는다(고유 키).
     * Wikidata 명소는 영문 병기 행이 따로 살아남지 않게 괄호 주석을 떼고 언어판 수를 키에 넣는다.
     * 식당 "OO (제주점)"·"OO (서귀포점)"은 다른 가게라 명소에만 한다.
     */
    private String nameKey(PlaceCandidate c) {
        String raw = c.name() == null ? "" : c.name().toLowerCase();
        boolean wikidataItem = c.wikidataSitelinks() != null && c.wikidataSitelinks() > 0;
        if (wikidataItem) raw = raw.replaceAll("\\(.*?\\)|\\[.*?]", "");
        String name = raw.replaceAll("\\s+", "");
        if (name.isEmpty()) return "idx:" + c.index();
        String region = c.region() == null ? "" : c.region();
        return wikidataItem ? region + "|wd" + c.wikidataSitelinks() + "|" + name : region + "|" + name;
    }

    /**
     * 뒤에 이어 붙이는 후보(예비·숙소)용 중복 키(id·이름·좌표). id만 보면 출처만 다른 같은 장소가
     * 다시 들어와 일정에 두 번 배치된다.
     */
    private Set<String> knownKeys(List<PlaceCandidate> candidates) {
        Set<String> keys = new HashSet<>();
        candidates.forEach(c -> addIfNew(keys, c));
        return keys;
    }

    private boolean addIfNew(Set<String> keys, PlaceCandidate c) {
        List<String> mine = new ArrayList<>(3);
        if (c.placeId() != null) mine.add("id:" + c.placeId());
        mine.add(nameKey(c));
        mine.add(physicalKey(c));
        if (mine.stream().anyMatch(keys::contains)) return false;
        keys.addAll(mine);
        return true;
    }

    /** 물리적 동일성 키: 유효좌표면 4자리 반올림 좌표, 없으면 공백제거·소문자 이름. */
    private String physicalKey(PlaceCandidate c) {
        if (hasValidCoords(c)) {
            long lat = Math.round(c.latitude().doubleValue() * 10000);
            long lng = Math.round(c.longitude().doubleValue() * 10000);
            return "geo:" + lat + "|" + lng;
        }
        String name = c.name() == null ? "" : c.name().toLowerCase().replaceAll("\\s+", "");
        return "name:" + name;
    }

    private boolean hasValidCoords(PlaceCandidate c) {
        return c.latitude() != null && c.longitude() != null
                && !(c.latitude().signum() == 0 && c.longitude().signum() == 0);
    }

    private boolean hasKoreanName(PlaceCandidate c) {
        if (c.nameKo() != null && !c.nameKo().isBlank()) return true;
        return c.name() != null && c.name().codePoints()
                .anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HANGUL);
    }

    /** TRANSIT_HUB로 분류됐으나 이름(원문·한국어)에 허브 신호가 없으면 오분류로 본다. */
    private boolean isTrustedCategory(PlaceCandidate c) {
        boolean isHub = "TRANSIT_HUB".equals(PlaceCategoryConstants.majorCategory(c.category()));
        return !(isHub && !PlaceCategoryConstants.hasTransitNameSignal(c.name())
                && !PlaceCategoryConstants.hasTransitNameSignal(c.nameKo()));
    }

    /**
     * 덧붙인 후보 중 앞선 후보와 같은 위키데이터 항목이거나, 다른 후보 건물 안 매장이거나, 앞선 후보와 이중 표기 중복인 것을 뺀다.
     * 출처별 행·매장은 이름·좌표가 달라 addIfNew를 통과한다.
     * 덧붙인 후보는 선택에 없어 번호를 다시 매겨도 된다.
     */
    List<PlaceCandidate> dropAppendedSameWikidataItem(List<PlaceCandidate> candidates, int baseSize) {
        if (candidates.size() <= baseSize) return candidates;
        List<Long> ids = candidates.stream().map(PlaceCandidate::placeId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> qidById = new HashMap<>();
        for (Place p : placeRepository.findAllById(ids)) {
            if (p.getWikidataId() != null) qidById.put(p.getId(), p.getWikidataId());
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < baseSize; i++) {
            String qid = qidById.get(candidates.get(i).placeId());
            if (qid != null) seen.add(qid);
        }
        List<PlaceCandidate> result = new ArrayList<>(candidates.subList(0, baseSize));
        for (PlaceCandidate c : candidates.subList(baseSize, candidates.size())) {
            String qid = qidById.get(c.placeId());
            if (qid != null && !seen.add(qid)) continue;
            if (isStoreInsideVenue(c, candidates)) continue;
            if (result.stream().anyMatch(r -> isBilingualTwin(c, r) || isBilingualTwin(r, c))) continue;
            result.add(reindexCandidate(c, result.size() + 1));
        }
        if (result.size() < candidates.size()) {
            log.info("덧붙인 후보 중 같은 장소 {}개 제외", candidates.size() - result.size());
        }
        return result;
    }

    private PlaceCandidate reindexCandidate(PlaceCandidate c, int index) {
        return c.reindexed(index);
    }

    private String subRegionOrKeep(String address, String current) {
        String extracted = PlaceCategoryConstants.extractSubRegion(address);
        return extracted != null ? extracted : current;
    }

    /**
     * 최종 스텝에 없는 사용자 필수 장소 이름. 주입·보호가 결정론적이라 누락은 로직 버그로 로그를 남기지만,
     * 사용자는 고른 곳이 빠진 이유를 알 수 없어 안내 문구로도 돌려준다.
     */
    private List<String> missingUserSelected(List<PlaceCandidate> candidates, List<StepData> fixedSteps) {
        Set<String> stepNames = fixedSteps.stream()
                .map(StepData::place)
                .filter(Objects::nonNull)
                .map(p -> normalize(p.name()))
                .collect(Collectors.toSet());
        List<String> missing = candidates.stream()
                .filter(PlaceCandidate::userSelected)
                .filter(c -> !stepNames.contains(normalize(c.name())))
                .map(PlaceCandidate::displayName)
                .distinct()
                .toList();
        missing.forEach(name -> log.error("사용자 필수 장소가 최종 일정에서 누락 (결정론적 로직 버그): {}", name));
        return missing;
    }

    static List<String> mustVisitNotices(List<String> unresolved, List<String> dropped) {
        List<String> notices = new ArrayList<>();
        if (!unresolved.isEmpty()) {
            notices.add(quoted(unresolved) + "은(는) 장소를 찾지 못해 일정에 넣지 못했어요.");
        }
        if (!dropped.isEmpty()) {
            notices.add("꼭 가고 싶다고 고른 " + quoted(dropped) + "을(를) 일정에 넣지 못했어요.");
        }
        return notices;
    }

    private static String quoted(List<String> names) {
        return names.stream().map(n -> "'" + n + "'").collect(Collectors.joining(", "));
    }

    private String normalize(String name) {
        return name == null ? "" : name.toLowerCase().replaceAll("\\s+", "");
    }

    /**
     * Manual 모드 자유입력 장소명을 DB에서만 해소한다. 외부 검색 결과는 Maps 약관 3.2.3(a)상 보관할 수 없다.
     * 동명 타지역 가게를 막으려고 중심 {@value #MAX_ACCOMMODATION_RADIUS_KM}km 안에서 중심에 가장 가까운 하나를 고르고,
     * DB에 없으면 만들지 않고 실패로 안내한다.
     */
    private List<Place> resolveCustomPlaces(ItineraryGenerateRequest request, VectorEnrichedInput enrichedInput,
                                            List<PlaceCandidate> candidatesRaw, GenerationStream emitter,
                                            List<String> unresolved) {
        List<String> names = request.customPlaceNames();
        if (names == null || names.isEmpty()) return List.of();

        List<double[]> centers = regionCenters(candidatesRaw);

        List<Place> resolved = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (String raw : names) {
            if (raw == null || raw.isBlank()) continue;
            String name = raw.trim();
            try {
                Optional<Place> match = findCustomPlaceInDb(name, centers);
                if (match.isEmpty()) {
                    failed.add(name);
                    continue;
                }
                resolved.add(match.get());
            } catch (Exception e) {
                log.warn("자유입력 '{}' 해소 실패: {}", name, e.getMessage());
                failed.add(name);
            }
        }

        unresolved.addAll(failed);
        if (!failed.isEmpty()) {
            sendSseEvent(emitter, "SEARCHING", 22,
                    "'" + String.join(", ", failed) + "' 장소는 찾지 못해 제외했습니다.");
        }
        boolean manual = request.mode() == ItineraryGenerateRequest.PlanningMode.MANUAL;
        boolean noIds = request.selectedPlaceIds() == null || request.selectedPlaceIds().isEmpty();
        if (manual && noIds && resolved.isEmpty()) {
            throw new BusinessException(com.shg.trip.shgtrip.global.exception.ErrorCode.INVALID_INPUT,
                    "직접 입력한 장소를 하나도 찾지 못했습니다. 장소명을 확인하거나 검색으로 선택해주세요.");
        }
        return resolved;
    }

    /**
     * 이름 부분일치 중 여행지 중심에 가장 가까운 하나(중심마다 상한 {@value #CUSTOM_PLACE_SEARCH_LIMIT}건).
     * 여러 여행지 일정은 여행지마다 중심이 있어 가장 가까운 중심에서 잰다.
     */
    Optional<Place> findCustomPlaceInDb(String name, List<double[]> centers) {
        // 중심점을 모르면 이름이 가장 짧은(가장 타이트한) 결과를 쓴다.
        if (centers.isEmpty()) {
            List<Place> byName = placeRepository.findActiveByNameLike(
                    name, PageRequest.of(0, CUSTOM_PLACE_SEARCH_LIMIT));
            return byName.isEmpty() ? Optional.empty() : Optional.of(byName.get(0));
        }

        Place best = null;
        double bestDist = MAX_ACCOMMODATION_RADIUS_KM;
        int matched = 0;
        for (double[] center : centers) {
            // 이름 길이순으로 자르면 전국에 수백 건인 이름에서 여행지 안의 지점이 상한 밖으로 밀려난다.
            List<Place> matches = placeRepository.findActiveByNameLikeNearest(
                    name, center[0], center[1], PageRequest.of(0, CUSTOM_PLACE_SEARCH_LIMIT));
            matched += matches.size();
            for (Place p : matches) {
                double d = GeoUtils.haversine(center,
                        new double[]{p.getLatitude().doubleValue(), p.getLongitude().doubleValue()});
                if (d < bestDist) {
                    bestDist = d;
                    best = p;
                }
            }
        }
        if (best == null && matched > 0) {
            log.info("자유입력 '{}' DB 매칭 {}건이 모두 여행지 중심에서 {}km 초과 — 동명 타지역으로 보고 제외",
                    name, matched, (long) MAX_ACCOMMODATION_RADIUS_KM);
        }
        return Optional.ofNullable(best);
    }

    /**
     * 여행지(region)별 후보 median 중심점. 전체 median 하나로 재면 도쿄+오사카 일정에서 한쪽 도시 장소가 전부 멀다.
     * 표본이 부족한 여행지는 빼고, 모두 부족하면 전체 median 하나다.
     */
    List<double[]> regionCenters(List<PlaceCandidate> candidates) {
        Map<String, List<PlaceCandidate>> byRegion = candidates.stream()
                .collect(Collectors.groupingBy(c -> c.region() == null ? "" : c.region(),
                        LinkedHashMap::new, Collectors.toList()));
        List<double[]> centers = new ArrayList<>();
        for (List<PlaceCandidate> group : byRegion.values()) {
            double[] center = candidateMedianCenter(group);
            if (center != null) centers.add(center);
        }
        if (centers.isEmpty()) {
            double[] all = candidateMedianCenter(candidates);
            if (all != null) centers.add(all);
        }
        return centers;
    }

    /** 후보들의 region 무관 전체 median 중심점. 표본 부족 시 null. */
    private double[] candidateMedianCenter(List<PlaceCandidate> candidates) {
        List<Double> lats = new ArrayList<>();
        List<Double> lngs = new ArrayList<>();
        for (PlaceCandidate c : candidates) {
            if (!hasValidCoords(c)) continue;
            lats.add(c.latitude().doubleValue());
            lngs.add(c.longitude().doubleValue());
        }
        if (lats.size() < ACCOMMODATION_GEO_MIN_SAMPLE) return null;
        return new double[]{GeoUtils.median(lats), GeoUtils.median(lngs)};
    }

    /**
     * 프론트 자유입력(음수 임시 ID)은 selectedPlaceIds 변환 시 제외되지만 방어적으로 양수만 조회한다.
     */
    private List<Place> resolveSelectedPlaces(ItineraryGenerateRequest request, List<String> notices) {
        List<Long> ids = request.selectedPlaceIds();
        if (ids == null || ids.isEmpty()) return List.of();
        List<Long> validIds = ids.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .collect(Collectors.toList());
        if (validIds.isEmpty()) return List.of();
        List<Place> found = placeRepository.findAllById(validIds);
        // 검색 뒤 삭제된 장소는 이름을 알 수 없어 개수로 알린다.
        if (found.size() < validIds.size()) {
            notices.add("고른 장소 중 " + (validIds.size() - found.size()) + "곳은 찾을 수 없어 일정에서 뺐어요.");
        }
        return found;
    }

    /**
     * 사용자 선택 장소를 userSelected=true(트림·교체 면제)로 후보 목록 앞쪽에 병합한다. 벡터 검색으로
     * 이미 들어온 후보와 겹치면 그 후보를 마킹해야 트림 대상이 되지 않는다.
     */
    private List<PlaceCandidate> mergeSelectedPlaces(List<PlaceCandidate> candidates, List<Place> selectedPlaces) {
        if (selectedPlaces.isEmpty()) return candidates;

        Set<Long> selectedIds = selectedPlaces.stream()
                .map(Place::getId)
                .collect(Collectors.toSet());
        Set<Long> existingIds = candidates.stream()
                .map(PlaceCandidate::placeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<PlaceCandidate> merged = new ArrayList<>();
        int index = 1;
        for (Place p : selectedPlaces) {
            if (existingIds.contains(p.getId())) continue; // 아래 기존 후보 루프에서 마킹
            // 사용자가 명시적으로 고른 장소라 similarityScore=1.0으로 최상위에 둔다.
            merged.add(new PlaceCandidate(
                    index++, p.getId(), p.getName(), p.getAddress(), p.getCategory(),
                    p.getTags(), p.getRegion(), p.getCountry(), p.getLatitude(), p.getLongitude(),
                    p.getDescription(), p.getRating(), 1.0, p.getPriceLevel(), p.getOpeningHours(),
                    p.getRecommendedTimeSlots(), p.getRecommendedDurationMinutes(), true, KrwRates.admissionWon(p),
                    PlaceCategoryConstants.extractSubRegion(p.getAddress()), p.getUserRatingCount(),
                    p.getWikidataSitelinks(), p.getPlaceRole(), p.getCuisine(), false, null, p.getAttributes(),
                    p.getNameKo(), p.getTravelerFame()));
        }
        for (PlaceCandidate c : candidates) {
            PlaceCandidate reindexed = reindexCandidate(c, index++);
            if (c.placeId() != null && selectedIds.contains(c.placeId())) {
                reindexed = reindexed.asUserSelected();
            }
            merged.add(reindexed);
        }
        return merged;
    }

    private List<PlaceCandidate> ensureAccommodationCandidates(
            List<PlaceCandidate> candidates, List<String> regions, VectorEnrichedInput input) {

        boolean hasAccommodation = candidates.stream()
                .anyMatch(c -> c.category() != null
                        && c.category().toLowerCase().contains("lodging"));

        if (hasAccommodation) return candidates;

        if (regions == null || regions.isEmpty()) return candidates;

        // 커플·가족이면 상위권이 게스트하우스로 채워질 수 있어 넉넉히 받아 거른 뒤 2곳만 쓴다.
        boolean avoidDorm = input != null && input.companion().avoidsDormLodging();
        List<Place> hotels = placeRepository.findTopAccommodationsByRegions(
                regions, PageRequest.of(0, avoidDorm ? 8 : 2)).stream()
                .filter(h -> !avoidDorm || !PlaceCategoryConstants.isDormLodging(
                        h.getAttributes(), h.getName(), h.getCategory(), h.getTags()))
                .limit(2)
                .toList();

        if (hotels.isEmpty()) {
            log.warn("숙소 후보 보완 실패: {}", regions);
            return candidates;
        }

        // region 오태깅으로 좌표가 지역 밖인 숙소가 벡터 필터를 우회해 들어오지 않게 보완 숙소도 좌표를 검사한다.
        List<Place> saneHotels = filterAccommodationsByGeography(hotels, candidates);
        if (saneHotels.isEmpty()) {
            // 모든 보완 숙소가 부적합해도 숙소 0개보다는 나아 최선책 1개는 유지한다.
            log.warn("보완 숙소가 모두 좌표상 지역 밖 — 최선책 1개 유지: regions={}, name={}",
                    regions, hotels.get(0).getName());
            saneHotels = List.of(hotels.get(0));
        }

        List<PlaceCandidate> result = new ArrayList<>(candidates);
        int nextIndex = candidates.size() + 1;
        for (Place hotel : saneHotels) {
            result.add(toCandidate(hotel, nextIndex++));
        }
        return result;
    }

    private static PlaceCandidate toCandidate(Place place, int index) {
        return new PlaceCandidate(
                index, place.getId(), place.getName(), place.getAddress(),
                place.getCategory(), place.getTags(), place.getRegion(), place.getCountry(),
                place.getLatitude(), place.getLongitude(), place.getDescription(),
                place.getRating(), 0.0, null, place.getOpeningHours(),
                place.getRecommendedTimeSlots(), place.getRecommendedDurationMinutes(),
                false, KrwRates.admissionWon(place),
                PlaceCategoryConstants.extractSubRegion(place.getAddress()),
                place.getUserRatingCount(), place.getWikidataSitelinks(),
                place.getPlaceRole(), place.getCuisine(), false, null, place.getAttributes(), place.getNameKo(),
                place.getTravelerFame());
    }

    /**
     * 해외 여행지의 관문(공항·역, source='gateway')을 후보에 넣는다. 관문 공항은 대개 여행지 반경 밖이라 지역 검색에
     * 잡히지 않고, 없으면 도착·출발 허브가 시내 역으로 대신 잡힌다.
     */
    List<PlaceCandidate> appendGateways(List<PlaceCandidate> candidates, List<String> regions) {
        if (regions == null || regions.isEmpty()) return candidates;
        List<String> gatewayRegions = regions.stream().map(destinations::gatewayRegion).distinct().toList();
        // 목록에서 뺀 관문(국내선 전용 공항·역)도 DB 행은 남아 있어 목록에 있는 이름만 붙인다.
        List<Place> gateways = placeRepository.findGatewaysByRegions(gatewayRegions).stream()
                .filter(this::isListedGateway)
                .toList();
        if (gateways.isEmpty()) return candidates;
        Set<String> known = knownKeys(candidates);
        List<PlaceCandidate> result = new ArrayList<>(candidates);
        for (Place gateway : gateways) {
            PlaceCandidate c = toCandidate(gateway, result.size() + 1);
            if (hasNearbyAirport(candidates, c) || !addIfNew(known, c)) continue;
            result.add(c);
        }
        if (result.size() > candidates.size()) {
            log.info("관문 후보 추가: {}", result.subList(candidates.size(), result.size()).stream()
                    .map(PlaceCandidate::name).toList());
        }
        return result;
    }

    private boolean isListedGateway(Place gateway) {
        return destinations.find(gateway.getRegion())
                .map(d -> d.gateways().stream().anyMatch(g -> g.name().equals(gateway.getName())))
                .orElse(false);
    }

    /** 같은 공항의 FSQ 행은 관문과 좌표가 수백 m 달라 이름·좌표 키로 걸리지 않는다. */
    private static boolean hasNearbyAirport(List<PlaceCandidate> candidates, PlaceCandidate gateway) {
        if (!isAirport(gateway) || gateway.latitude() == null || gateway.longitude() == null) return false;
        return candidates.stream().anyMatch(c -> isAirport(c) && c.latitude() != null && c.longitude() != null
                && GeoUtils.haversine(c.latitude().doubleValue(), c.longitude().doubleValue(),
                        gateway.latitude().doubleValue(), gateway.longitude().doubleValue()) <= SAME_AIRPORT_KM);
    }

    private static boolean isAirport(PlaceCandidate c) {
        return c.category() != null && c.category().toLowerCase(Locale.ROOT).contains("airport")
                && "TRANSIT_HUB".equals(PlaceCategoryConstants.majorCategory(c.category()));
    }

    static boolean isCarWithoutKoreanLicense(VectorEnrichedInput input) {
        return "car".equals(input.transportPref()) && input.country() != null
                && NO_KOREAN_LICENSE_COUNTRIES.contains(input.country().trim().toUpperCase(Locale.ROOT));
    }

    private static boolean isOverseas(String country) {
        return country != null && !country.isBlank() && !"KR".equalsIgnoreCase(country);
    }

    /** 근교 여행지만으로 장소가 부족하면 상위 도시와 묶어 다시 요청하도록 안내한다. */
    String sparseOverseasMessage(VectorEnrichedInput input) {
        if (input.regions() != null) {
            for (String region : input.regions()) {
                var parent = destinations.find(region).map(d -> d.parent()).flatMap(destinations::find);
                if (parent.isPresent() && !input.regions().contains(parent.get().name())) {
                    String child = destinations.find(region).map(d -> d.nameKo()).orElse(region);
                    return "'" + child + "'만으로는 장소가 적어 일정을 만들기 어려워요. '" + parent.get().nameKo()
                            + "'까지 함께 넣어 다시 요청해 주세요.";
                }
            }
        }
        return ErrorCode.INSUFFICIENT_PLACE_DATA.getMessage();
    }

    /**
     * 같은 region 기존 후보의 median 중심점에서 {@link #MAX_ACCOMMODATION_RADIUS_KM}를 넘는 보완 숙소를 뺀다.
     * VectorSearchQueryService.filterGeographicOutliers와 같은 로직이다.
     */
    List<Place> filterAccommodationsByGeography(List<Place> hotels, List<PlaceCandidate> candidates) {
        List<Place> kept = new ArrayList<>();
        for (Place hotel : hotels) {
            if (hotel.getLatitude() == null || hotel.getLongitude() == null) {
                kept.add(hotel);
                continue;
            }
            List<PlaceCandidate> sameRegion = candidates.stream()
                    .filter(c -> Objects.equals(c.region(), hotel.getRegion()))
                    .filter(c -> c.latitude() != null && c.longitude() != null)
                    .toList();
            if (sameRegion.size() < ACCOMMODATION_GEO_MIN_SAMPLE) {
                kept.add(hotel);
                continue;
            }
            double centerLat = GeoUtils.median(sameRegion.stream().map(c -> c.latitude().doubleValue()).toList());
            double centerLng = GeoUtils.median(sameRegion.stream().map(c -> c.longitude().doubleValue()).toList());
            double dist = GeoUtils.haversine(
                    centerLat, centerLng, hotel.getLatitude().doubleValue(), hotel.getLongitude().doubleValue());
            if (dist <= MAX_ACCOMMODATION_RADIUS_KM) {
                kept.add(hotel);
            } else {
                log.info("보완 숙소 좌표 아웃라이어 제외: name='{}', region='{}', 중심점에서 {}km",
                        hotel.getName(), hotel.getRegion(), String.format("%.1f", dist));
            }
        }
        return kept;
    }

    private EnrichedInput toLegacyEnrichedInput(VectorEnrichedInput input) {
        return new EnrichedInput(
                input.normalizedDestination() != null ? input.normalizedDestination() : input.destination(),
                input.themes(),
                input.categories(),
                input.pace(),
                input.transportPref(),
                input.budget(),
                input.startDate(),
                input.endDate(),
                input.description(),
                input.enrichedContext(),
                input.selectedPlaceIds()
        );
    }

    private void sendSseEvent(GenerationStream emitter, String status, int progress, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(Map.of(
                            "stage", status,
                            "percentage", progress,
                            "message", message
                    )));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 이벤트 전송 실패 (status={}): {}", status, e.getMessage());
        }
    }

    /** 전송 실패는 무시한다(critical path 아님). */
    private void sendQualityNotices(GenerationStream emitter, List<String> notices) {
        if (notices == null || notices.isEmpty()) return;
        try {
            emitter.send(SseEmitter.event().name("quality-notice").data(Map.of("notices", notices)));
            log.info("품질 안내 {}건 전송: {}", notices.size(), notices);
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE quality-notice 전송 실패: {}", e.getMessage());
        }
    }

    private void sendStepStream(GenerationStream emitter, List<StepData> steps) {
        if (steps == null || steps.isEmpty()) return;

        Map<Integer, List<Map<String, String>>> byDay = new LinkedHashMap<>();
        steps.stream()
                .sorted(Comparator.comparingInt(StepData::dayNumber).thenComparingInt(StepData::stepOrder))
                .forEach(s -> {
                    String name = s.place() != null ? s.place().name() : null;
                    if (name == null) return;
                    Map<String, String> info = new HashMap<>();
                    info.put("name", name);
                    info.put("nameKo", s.place().nameKo());
                    info.put("startTime", s.startTime());
                    info.put("category", s.place() != null ? s.place().category() : null);
                    byDay.computeIfAbsent(s.dayNumber(), k -> new ArrayList<>()).add(info);
                });

        for (Map.Entry<Integer, List<Map<String, String>>> entry : byDay.entrySet()) {
            try {
                emitter.send(SseEmitter.event()
                        .name("step-stream")
                        .data(Map.of("dayNumber", entry.getKey(), "steps", entry.getValue())));
            } catch (IOException | IllegalStateException e) {
                log.debug("step-stream 전송 실패 (day={}): {}", entry.getKey(), e.getMessage());
            }
        }
    }

    private void sendSseError(GenerationStream emitter, String errorCode, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(Map.of(
                            "status", "error",
                            "errorCode", errorCode,
                            "message", message
                    )));
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 에러 이벤트 전송 실패: {}", e.getMessage());
        }
    }

    /**
     * 구조 일정 완료를 알리지만 emitter는 닫지 않는다. story-ready까지 같은 emitter로 이벤트를 더 보내야 해서
     * "complete=종료" 관례를 이 흐름에서만 예외로 둔다.
     */
    private void sendCompleteKeepOpen(GenerationStream emitter, Long itineraryId) {
        try {
            emitter.send(SseEmitter.event()
                    .name("complete")
                    .data(Map.of("status", "DONE", "itineraryId", itineraryId, "pipeline", "optimized")));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 완료 이벤트 전송 실패: {}", e.getMessage());
        }
    }

    private <T> T executeWithHeartbeat(GenerationStream emitter, Callable<T> task) {
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> heartbeatFuture = heartbeat.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException e) {
                // emitter 닫힘
            }
        }, 30, 30, TimeUnit.SECONDS);

        try {
            return task.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            heartbeatFuture.cancel(false);
            heartbeat.shutdownNow();
            try {
                if (!heartbeat.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Heartbeat executor did not terminate gracefully");
                }
            } catch (InterruptedException e) {
                log.warn("Interrupted waiting for heartbeat executor termination");
                Thread.currentThread().interrupt();
            }
        }
    }
}
