package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
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
import java.util.stream.Collectors;

/**
 * 2-Call 일정 생성 파이프라인 실행기.
 *
 * enrich(Haiku) → vectorSearch(카테고리별) →
 *   if (충분) selectPlaces(Sonnet) → assembleItinerary(Haiku) → validate → save
 *   else fallback → ItineraryGenerationExecutor
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

    /** 보완 숙소가 기존 후보 median 중심점에서 이 거리(km)를 초과하면 지역 오태깅으로 보고 제외. */
    private static final double MAX_ACCOMMODATION_RADIUS_KM = 100.0;
    /** 보완 숙소 좌표 검증용 region별 최소 후보 표본 수(미만이면 중심점 신뢰도 낮아 필터 skip). */
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
                // R1: 입력 검증 실패(비현실 입력)만 카운트 → 5회 도달 시 24h 차단.
                // 모든 요청이 이 경로를 먼저 지나므로 여기가 유일한 실패 카운트 지점이다
                // (fallback 재-enrich는 이미 valid한 입력을 다루므로 카운트하지 않음).
                // errorCode를 넘겨 사용자 입력 문제일 때만 집계(AI 오탐/포맷오류는 정책 서비스가 제외).
                generationPolicyService.recordValidationFailure(userId, enrichResult.errorCode());
                sendSseError(emitter, enrichResult.errorCode(), enrichResult.errorMessage());
                return;
            }

            VectorEnrichedInput enrichedInput = enrichResult.enrichedInput();
            long days = ChronoUnit.DAYS.between(enrichedInput.startDate(), enrichedInput.endDate()) + 1;
            stageMark = logStage("enrich", stageMark);

            // [20%] 카테고리별 벡터 검색
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "SEARCHING", 20, "장소를 검색하고 있습니다...");

            List<PlaceCandidate> candidatesRaw = executeWithHeartbeat(emitter,
                    () -> vectorSearchQueryService.search(enrichedInput));

            // Manual 모드: 사용자가 선택한 장소(ID) + 자유입력 장소명(DB 해소)을 resolve
            // → 지역 검증 → 후보에 userSelected(필수 방문)로 병합.
            List<Place> selectedPlaces = new ArrayList<>(resolveSelectedPlaces(request));
            // DB 조회만 하므로 예전 Google 호출(최대 5건 × read 10s)처럼 느리지 않지만,
            // heartbeat 래핑은 유지한다 — 비용이 없고 느려질 때 프록시가 끊는 것을 막아준다.
            selectedPlaces.addAll(executeWithHeartbeat(emitter,
                    () -> resolveCustomPlaces(request, enrichedInput, candidatesRaw, emitter)));
            placeRegionValidator.validate(selectedPlaces, enrichedInput); // 불일치 시 BusinessException → SSE error
            List<PlaceCandidate> candidates = mergeSelectedPlaces(candidatesRaw, selectedPlaces);
            // 여행지 기준 좌표 — 후보 수십 건의 median이 단일 Google 검색 결과보다 신뢰도가 높다.
            // Google/Redis 캐시 좌표가 이 중심점에서 벗어나면 저장 단계가 캐시를 폐기하고 이 값을 쓴다(A7).
            final double[] destinationReference = candidateMedianCenter(candidates);
            stageMark = logStage("search", stageMark);

            // [25%] Fallback/컴팩트 분기 판단
            if (cancellationRegistry.isCancelled(jobId)) return;
            logCandidatePool(candidates, days);
            if (candidates.isEmpty()) {
                // 후보가 하나도 없으면 fallback(Sonnet)이 이름만으로 일정을 지어도 DB에서 해소되는 장소가 없다
                log.info("OptimizedGeneration 중단 (jobId={}, reason=no_candidates)", jobId);
                ErrorCode code = ErrorCode.INSUFFICIENT_PLACE_DATA;
                sendSseError(emitter, code.getCode(), code.getMessage());
                return;
            }
            FallbackDecider.PoolQuality poolQuality = fallbackDecider.assess(candidates, days);

            if (poolQuality == FallbackDecider.PoolQuality.FALLBACK) {
                sendSseEvent(emitter, "FALLBACK", 30, "Fallback 경로로 일정을 생성합니다...");
                log.info("OptimizedGeneration → Fallback (jobId={}, reason=insufficient_candidates)", jobId);
                fallbackExecutor.execute(jobId, request, userId, emitter);
                return;
            }
            // 유효 관광지 빈약 → 억지로 채우지 않고 컴팩트 일정으로(quota 하한 완화) + 사용자 안내
            boolean compactMode = poolQuality == FallbackDecider.PoolQuality.COMPACT;
            if (compactMode) {
                sendSseEvent(emitter, "SEARCHING", 30,
                        "이 지역은 장소 데이터를 보강 중이에요. 알찬 곳 위주로 컴팩트하게 구성합니다...");
                log.info("OptimizedGeneration 컴팩트 모드 (jobId={}, reason=sparse_attractions)", jobId);
            }

            // [35%] 후보 정리
            //
            // 예전엔 여기서 stale 후보를 Google Places로 동기화했다(SKU 과금 지점이자 2026-10-08
            // 과금 사고의 호출 경로). 제거했다 — Maps 약관 3.2.3(a)상 받아온 세부정보를 DB에
            // 보관할 수 없어, 캐싱 전제의 "동기화"가 성립하지 않는다.
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "SYNCING", 35, "장소 정보를 정리하고 있습니다...");

            List<Long> placeIds = candidates.stream()
                    .map(PlaceCandidate::placeId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());

            // 후보 등장 시각 기록 — 배치가 보강 우선순위를 정하는 데 쓴다. 벌크 UPDATE 1회.
            if (!placeIds.isEmpty()) {
                placeRepository.markCandidateAppearance(placeIds, OffsetDateTime.now());
            }

            // DB 최신 데이터 반영 + 숙소 보완 + 물리적 중복 제거(선택 이전 1회)
            // 배로만 가는 부속섬 장소는 사용자가 원할 때만 남긴다(FerryAreaFilter). 토큰 검증은 풀 전체로 한 번.
            final List<String> ferryTokens = FerryAreaFilter.activeTokens(enrichedInput.ferryOnlyAreas(),
                    enrichedInput.description(), request.customPlaceNames(), candidates);
            // 커플·가족이면 다인실 숙소를 뺀 뒤에 숙소 보완 여부를 판단한다(남은 숙소가 0이면 보완).
            // DB 보완 숙소도 같은 필터를 거친다(두 번째 exclude) — 숙소가 들어오는 모든 경로에 같은 규칙.
            final List<PlaceCandidate> enrichedCandidates = dedupePhysicalCandidates(
                    FerryAreaFilter.exclude(ensureAccommodationCandidates(
                            excludeDormLodging(FerryAreaFilter.exclude(enrichCandidatesFromDb(candidates),
                                    ferryTokens), enrichedInput),
                            enrichedInput.regions(), enrichedInput), ferryTokens));
            stageMark = logStage("sync", stageMark);

            // [50%] Call 1: Sonnet selectPlaces
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "SELECTING", 50, "날짜별 장소를 선택하고 있습니다...");

            SelectionOutput rawSelectionOutput = executeWithHeartbeat(emitter,
                    () -> selectionCallGenerator.selectPlaces(enrichedInput, enrichedCandidates));

            // 숙소·허브(코드 소유 필드) 제거 → 집합 POI 제외 (day 구성 확정)
            SelectionOutput dayComposition = indexResultMapper.stripZoneLevelPicks(
                    indexResultMapper.stripCodeOwnedFields(rawSelectionOutput), enrichedCandidates);

            // day 구성이 확정됐으니, 방문지 중심에서 너무 먼 숙소밖에 없는 day가 있으면 그 day의
            // centroid 기준으로 숙소를 한 번 더 검색해 후보에 덧붙인다(3차 4번). 기존 인덱스는
            // 건드리지 않고 뒤에 이어 붙인다 — selectionOutput이 인덱스를 참조하고 있다.
            // **숙소 배정보다 먼저** 돌려야 새로 찾은 숙소가 배정 대상에 들어간다(예전엔 배정 뒤라 무용지물).
            // 같은 방식으로 날짜별 중심 근처의 관광·카페·식당을 예비 후보로 붙인다 — 빈 시간 채우기와
            // 교체가 쓸 장소가 동선 근처에 남아 있게 한다(실측 116~118: 3~5시간 공백 미충전).
            // 덧붙인 후보는 벡터 검색 결과라 체류시간·시간대·입장료가 비어 있다. DB 값으로 채운다.
            final List<PlaceCandidate> candidatesWithLocalStays = enrichCandidatesFromDb(appendNearbyReserve(
                    appendNearbyAccommodations(enrichedCandidates, dayComposition, enrichedInput, ferryTokens),
                    dayComposition, enrichedInput, ferryTokens));

            // 숙소 결정론적 배정 → 사용자 필수 장소 주입 (추가 LLM 호출 없는 결정론적 보정)
            final SelectionOutput selectionOutput = indexResultMapper.injectRequiredPlaces(
                    indexResultMapper.fillMissingAccommodation(dayComposition, candidatesWithLocalStays),
                    candidatesWithLocalStays);
            stageMark = logStage("select", stageMark);

            // [65%] Backend Repair·Optimizer: day/순서/시간/교통/대안 전부 결정론적으로 확정
            // (LLM 재호출 없음 — pace quota·pair·거리이탈·연속숙소·허브를 fixpoint로 수리 후 NN+2-opt)
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "OPTIMIZING", 65, "동선과 시간을 확정하고 있습니다...");

            final ScheduleArgs scheduleArgs = ScheduleArgs.of(enrichedInput, compactMode);
            RouteOptimizer.ScheduleResult scheduleResult = executeWithHeartbeat(emitter,
                    () -> routeOptimizer.schedule(selectionOutput, candidatesWithLocalStays, scheduleArgs));
            List<StepData> fixedSteps = scaleCostsForParty(scheduleResult.steps(), enrichedInput.partySize());
            List<String> qualityNotices = scheduleResult.notices();

            // [F4] 확정된 뼈대(장소·시간·Day구성)를 day별로 스트리밍 → 프론트 스켈레톤→실카드 교체.
            // 65%에 이미 실데이터가 완성돼 있으므로 연출(가짜 delay) 아님. 인위적 sleep 금지(SSE 스레드 blocking).
            sendStepStream(emitter, fixedSteps);
            // 해소하지 못한 품질 문제를 사용자에게도 알린다 — 로그만 남기면 숙소 없는 일정이
            // 아무 표시 없이 나간다. 상세 화면 표시는 itineraries.quality_notices가 담당하고,
            // 이 이벤트는 생성 중 즉시 안내용이다.
            sendQualityNotices(emitter, qualityNotices);
            stageMark = logStage("optimize", stageMark);

            // [80%] 구조 검증(안전망 — 결정론적 코드이므로 실패 시 재시도가 아니라 버그로 취급)
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "VALIDATING", 80, "일정을 검증하고 있습니다...");

            String destination = enrichedInput.normalizedDestination() != null
                    ? enrichedInput.normalizedDestination() : enrichedInput.destination();
            // 태그 시드: enrich의 searchTags(한국어 표시용 태그) → 없으면 themes 폴백.
            // StorySaveHelper가 title/tags를 더 이상 덮지 않으므로 여기서 확정하지 않으면 tags가 빈값으로 남는다.
            List<String> tagSeed = enrichedInput.searchTags() != null && !enrichedInput.searchTags().isEmpty()
                    ? enrichedInput.searchTags() : enrichedInput.themes();
            // 임시 제목은 Sonnet의 concept 문장이 아니라 짧은 결정론적 문구를 쓴다.
            // concept은 "~를 오가며 즐기는 ~ 여행 – ~과 ~을 함께 즐긴다" 같은 한 줄 설명이라,
            // Haiku 스토리가 비동기로 끝나기 전까지 사용자가 60자짜리 설명문을 제목으로 본다
            // (실측 91번 화면: 2줄로 줄바꿈된 제목). 교체 전 상태도 그대로 보여줄 수 있어야 한다.
            String draftTitle = buildDraftTitle(destination, enrichedInput);
            ItineraryData draftData = indexResultMapper.toDraftItineraryData(
                    fixedSteps, destination, draftTitle, tagSeed, qualityNotices);
            HardValidationResult validationResult = hardValidator.validate(draftData);
            if (!validationResult.valid()) {
                log.error("Optimized 경로 구조 검증 실패 (결정론적 로직 버그 가능성): {}", validationResult.failureReason());
            }
            verifyUserSelectedIncluded(candidatesWithLocalStays, fixedSteps);

            // [90%] 구조 일정 저장 (story는 비어있음) — 즉시 complete, story는 비동기로 채움
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "SAVING", 90, "일정을 저장하고 있습니다...");

            EnrichedInput legacyInput = toLegacyEnrichedInput(enrichedInput);
            Itinerary saved = saveHelper.save(draftData, legacyInput, userId, true, destinationReference);
            stageMark = logStage("save", stageMark);

            // [100%] 구조 완료 — emitter는 닫지 않고 story-ready까지 유지
            resultStore.save(jobId, saved.getId(), userId);
            // R1/R2: 성공 시 실패 카운트 리셋 + 30일 쿼터 소모(성공 시에만)
            generationPolicyService.recordSuccess(userId);
            sendSseEvent(emitter, "COMPLETE", 100, "일정 생성이 완료되었습니다.");
            sendCompleteKeepOpen(emitter, saved.getId());
            traceRecorder.record(saved.getId(), jobId, request, enrichedInput, rawSelectionOutput,
                    selectionOutput, candidatesWithLocalStays, scheduleArgs,
                    (System.nanoTime() - pipelineStart) / 1_000_000);

            log.info("OptimizedGeneration 구조 완료: jobId={}, itineraryId={}, days={}",
                    jobId, saved.getId(), days);
            log.info("stage=total elapsedMs={}", (System.nanoTime() - pipelineStart) / 1_000_000);

            // 비동기 스토리텔링 — critical path 밖에서 진행, 완료 시 story-ready emit 후 emitter 종료
            // 교체 비교 기준(expectedTitle)은 실제로 저장한 임시 제목이어야 한다 — concept을
            // 넘기면 0행 업데이트가 되어 제목이 영원히 임시값으로 남는다.
            storyGenerationService.generateAndAttach(
                    jobId, emitter, saved.getId(), fixedSteps, draftTitle, enrichedInput,
                    selectionOutput.concept());

        } catch (BusinessException e) {
            log.error("OptimizedGeneration 비즈니스 오류 (jobId={}): {}", jobId, e.getMessage());
            sendSseError(emitter, e.getErrorCode().getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("OptimizedGeneration 예기치 않은 오류 (jobId={})", jobId, e);
            sendSseError(emitter, "UNEXPECTED_ERROR", "일정 생성 중 오류가 발생했습니다.");
        } finally {
            MDC.remove("jobId");
        }
    }

    // ── Private helpers ──

    /**
     * 1인 기준으로 산정된 식사·카페·입장료를 인원수만큼 곱한다. 숙소(1실)·교통(차 1대)은 그대로 둔다.
     * 대안의 비용도 같이 곱한다 — 대안을 고르면 그 비용이 스텝 비용을 대체하므로 기준이 같아야 한다.
     *
     * <p>RouteOptimizer 밖에서 하는 이유: 거기는 입력만으로 결과가 정해지는 순수 계산기라 인원 같은
     * 표시용 배수를 섞지 않는다. 실측 115: 커플 100만원 예산에 예상 33만원(1인 기준)이 표시됐다.
     */
    static List<StepData> scaleCostsForParty(List<StepData> steps, Integer partySize) {
        if (partySize == null || partySize <= 1) return steps;
        BigDecimal factor = BigDecimal.valueOf(partySize);
        List<StepData> scaled = new ArrayList<>(steps.size());
        for (StepData s : steps) {
            List<AlternativeData> alts = s.alternatives() == null ? null : s.alternatives().stream()
                    .map(a -> isPerPersonCost(a.category()) && a.estimatedCost() != null
                            ? new AlternativeData(a.name(), a.address(), a.category(), a.region(), a.country(),
                                    a.notes(), a.estimatedCost().multiply(factor))
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
     * <p>숙소 후보가 들어오는 세 경로(검색 풀·DB 보완·day별 근처 검색)가 모두 이 메서드를 거친다 —
     * 한 경로만 막으면 나머지 경로로 같은 숙소가 다시 들어온다.
     */
    private List<PlaceCandidate> excludeDormLodging(List<PlaceCandidate> candidates, VectorEnrichedInput input) {
        if (input == null || !input.companion().avoidsDormLodging()) return candidates;
        List<PlaceCandidate> kept = candidates.stream()
                .filter(c -> c.userSelected()
                        || !"LODGING".equals(PlaceCategoryConstants.majorCategory(c.category()))
                        || !PlaceCategoryConstants.isDormLodging(c.name(), c.category(), c.tags()))
                .toList();
        if (kept.size() < candidates.size()) {
            log.info("다인실 숙소 제외({}): {}개 → {}개", input.companion(), candidates.size(), kept.size());
        }
        return kept;
    }

    /** 예비 후보 검색 반경(km) — 이동수단별. 도보는 걸어서 들를 만한 거리만. */
    private static double reserveRadiusKm(String transportPref) {
        if ("walk".equals(transportPref)) return 3.0;
        if ("car".equals(transportPref)) return 15.0;
        return 10.0;
    }

    /** 날짜별로 덧붙일 예비 후보 수: 관광 4 · 카페 2 · 식당 3. */
    private static final List<Map.Entry<PlaceCategoryConstants.SearchRole, Integer>> RESERVE_PER_DAY = List.of(
            Map.entry(PlaceCategoryConstants.SearchRole.ATTRACTION, 4),
            Map.entry(PlaceCategoryConstants.SearchRole.CAFE, 2),
            Map.entry(PlaceCategoryConstants.SearchRole.RESTAURANT, 3));

    /**
     * 날짜별 방문지 중심 근처의 관광·카페·식당을 예비 후보로 덧붙인다. 선택(SelectionOutput)에는 넣지 않으므로
     * Sonnet의 구성은 그대로이고, RouteOptimizer의 "미사용 후보" 경로(빈 시간 채우기·끼니 보충·교체)만 쓴다.
     * 기존 인덱스는 건드리지 않고 뒤에 이어 붙인다. 숙소 보완과 같은 필터(다인실·도선 섬)를 거친다.
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
    /** 추가 검색 반경(km) — 트리거 거리보다 좁게 잡아 "그 지역 숙소"만 들어오게 한다. */
    private static final double LOCAL_STAY_RADIUS_KM = 20.0;
    /** day당 추가로 가져올 숙소 수. */
    private static final int LOCAL_STAY_LIMIT = 3;

    /**
     * 각 day의 방문지 centroid에서 가장 가까운 숙소 후보가 {@link #LOCAL_STAY_TRIGGER_KM}를 넘으면,
     * 그 centroid 기준으로 숙소를 1회 추가 검색해 후보 목록 뒤에 이어 붙인다.
     * 기존 후보의 인덱스는 그대로 두므로 selectionOutput의 인덱스 참조가 깨지지 않는다.
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
            // 숙소 후보가 아예 없으면 거리와 무관하게 발동한다 — 거리 게이트만 두면 "숙소 0개"인
            // 최악의 경우에 보완이 돌지 않는다(nearest가 MAX_VALUE라 통과하긴 하지만 의도를 명시).
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

    /** DayPlan의 방문지 좌표 centroid. 유효 좌표가 없으면 null. */
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
     * 후보 풀 구성을 로깅한다 — FallbackDecider가 왜 그 판정을 냈는지 사후에 추적할 수 있어야 한다
     * (판정 실패 시 "후보가 부족했다"만 남으면 검색 층 어디가 비었는지 알 수 없다).
     */
    /**
     * 구조 저장 시점의 <b>임시 제목</b> — "제주 3박 4일" 같은 짧은 결정론적 문구.
     *
     * <p>예전에는 Sonnet의 {@code concept}을 그대로 썼는데, concept은 "제주 동서 해안과 숲을
     * 오가며 즐기는 로맨틱 힐링 커플 여행 – 일출·오름·숲길의 자연경관과 다채로운 제주 미식을
     * 함께 즐긴다" 같은 한 줄 설명이다. Haiku 스토리가 비동기로 끝나기 전까지 사용자는 그
     * 60자짜리 문장을 제목으로 본다(실측 91번 화면: 제목이 두 줄로 줄바꿈됐다).
     *
     * <p>스토리가 끝나면 Haiku의 제목으로 교체되지만, <b>교체 전 상태도 보여줄 수 있어야 한다.</b>
     * 스토리 생성이 실패하거나 사용자가 그 사이에 목록을 보면 이 값이 최종 제목이 된다.
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
        // FallbackDecider가 쓰는 키워드 기준 카운트(대분류 매핑과 다를 수 있어 함께 남긴다)
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
     * 식사 후보의 세부 유형 분포. 유형 상한(RouteOptimizer)이 "대체 후보 없음"으로 실패할 때
     * 조립 로직이 아니라 <b>후보 풀 자체</b>가 한 유형으로 쏠린 것인지 구분하기 위해 남긴다.
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
            // name/address는 DB 값을 채택한다 — Google 동기화가 한글명/정식 주소로 갱신한 뒤에도
            // 프롬프트·스텝·저장(name+address 매핑)이 같은 값을 보도록 일관성을 보장.
            // (기존엔 후보의 옛 이름/주소를 유지해 저장 시 (name,address) 조회가 어긋날 수 있었다)
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
                    place.getAdmissionFee(),
                    // 주소가 Google 동기화로 갱신됐을 수 있으므로 세부지역을 다시 뽑고, 실패하면 기존 값 유지
                    subRegionOrKeep(place.getAddress(), c.subRegion()),
                    place.getUserRatingCount() != null ? place.getUserRatingCount() : c.userRatingCount(),
                    place.getWikidataSitelinks() != null
                            ? place.getWikidataSitelinks() : c.wikidataSitelinks(),
                    place.getPlaceRole() != null ? place.getPlaceRole() : c.placeRole(),
                    place.getCuisine() != null ? place.getCuisine() : c.cuisine()
            );
        }).collect(Collectors.toList());
    }

    /**
     * 물리적으로 동일한 장소(같은 좌표, 좌표 없으면 같은 정규화 이름)가 중복 레코드로 들어온
     * 경우 1개만 남긴다(A-0-1). Sonnet 선택 이전에 1회만 수행해 인덱스를 깨끗이 하고, 같은
     * 장소가 여러 스텝/대안에 반복되는 사고(흰여울문화마을 3연속, 고전떡볶이/본점 등)를 원천
     * 차단한다. 대표는 "카테고리 신뢰도(오분류 허브 배제) → rating → 좌표 유효" 순. 남은 후보는
     * 1-based 인덱스를 다시 연속 부여한다(RouteOptimizer.byIndex가 위치 기반이므로 필수).
     */
    private List<PlaceCandidate> dedupePhysicalCandidates(List<PlaceCandidate> candidates) {
        Map<String, List<PlaceCandidate>> groups = new LinkedHashMap<>();
        for (PlaceCandidate c : candidates) {
            groups.computeIfAbsent(physicalKey(c), k -> new ArrayList<>()).add(c);
        }

        List<PlaceCandidate> representatives = new ArrayList<>(groups.size());
        for (List<PlaceCandidate> group : groups.values()) {
            representatives.add(pickRepresentative(group));
        }

        // 2차: 같은 region의 완전 동일 이름(공백 제거)은 좌표가 좀 달라도 같은 장소로 병합.
        // 실측: "어멍"이 Foursquare/Google 이중 레코드(약 180m 차이)로 좌표 키(±11m)를 통과해
        // 둘 다 후보로 살아남았고, 같은 식당이 1일차 저녁과 4일차 점심에 모두 배치됐다.
        Map<String, List<PlaceCandidate>> nameGroups = new LinkedHashMap<>();
        for (PlaceCandidate c : representatives) {
            nameGroups.computeIfAbsent(nameKey(c), k -> new ArrayList<>()).add(c);
        }
        representatives = new ArrayList<>(nameGroups.size());
        for (List<PlaceCandidate> group : nameGroups.values()) {
            representatives.add(pickRepresentative(group));
        }

        // 원래 등장 순서(최소 index) 유지 후 1-based 재인덱싱
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

    /** 대표 선정: 카테고리 신뢰도 → 한글명(한국 사용자 노출용) → rating → 좌표 유효 순. */
    private PlaceCandidate pickRepresentative(List<PlaceCandidate> group) {
        PlaceCandidate rep = group.stream()
                .max(Comparator
                        .comparingInt((PlaceCandidate c) -> isTrustedCategory(c) ? 1 : 0)
                        // 한/영 중복(예: "성읍랜드" vs "Sungeup Land")은 물리적으로 같은
                        // 장소이므로 한국 사용자에게 보여줄 한글명 레코드를 대표로 선호한다.
                        .thenComparingInt(c -> hasKoreanName(c) ? 1 : 0)
                        .thenComparing(c -> c.rating() != null ? c.rating() : BigDecimal.ZERO)
                        .thenComparingInt(c -> hasValidCoords(c) ? 1 : 0))
                .orElse(group.get(0));
        // 그룹 내 하나라도 사용자 선택이면 대표에 필수성 전파 — 사용자가 고른 영문 레코드가
        // 한글 대표에 흡수돼도 "반드시 포함" 보장이 유지돼야 한다.
        if (group.stream().anyMatch(PlaceCandidate::userSelected)) {
            rep = rep.asUserSelected();
        }
        return rep;
    }

    /**
     * 이름 동일성 키: region + 공백제거·소문자 이름. 이름이 없으면 병합하지 않는다(고유 키).
     *
     * <p>Wikidata 항목에 붙은 명소는 괄호·대괄호 주석까지 떼고 언어판 수를 키에 넣는다 — "제주러브랜드"와
     * "제주러브랜드 (Jeju Love Land)"가 출처별 좌표 차이(애월·안덕)로 따로 살아남아 1일차·4일차에 두 번
     * 배치됐다(실측 171). 괄호 떼기를 명소에만 하는 이유: 식당 "OO (제주점)"·"OO (서귀포점)"은 다른 가게다.
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
     * 뒤에 이어 붙이는 후보(예비·숙소)용 중복 키 — id·이름·좌표. 앞 단계 dedupe는 이미 끝났으므로 id만 보면
     * 출처만 다른 같은 장소(placeId가 다름)가 다시 들어와 일정에 두 번 배치될 수 있다.
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

    /** 이름에 한글이 포함되어 있는지 (한/영 중복 레코드 중 대표 선정용). */
    private boolean hasKoreanName(PlaceCandidate c) {
        return c.name() != null && c.name().codePoints()
                .anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HANGUL);
    }

    /** TRANSIT_HUB로 분류됐으나 이름에 허브 신호가 없으면 오분류(흰여울=Bus Station)로 간주. */
    private boolean isTrustedCategory(PlaceCandidate c) {
        boolean isHub = "TRANSIT_HUB".equals(PlaceCategoryConstants.majorCategory(c.category()));
        return !(isHub && !PlaceCategoryConstants.hasTransitNameSignal(c.name()));
    }

    private PlaceCandidate reindexCandidate(PlaceCandidate c, int index) {
        return new PlaceCandidate(index, c.placeId(), c.name(), c.address(), c.category(),
                c.tags(), c.region(), c.country(), c.latitude(), c.longitude(),
                c.description(), c.rating(), c.similarityScore(), c.priceLevel(), c.openingHours(),
                c.recommendedTimeSlots(), c.recommendedDurationMinutes(), c.userSelected(), c.admissionFee(),
                c.subRegion(), c.userRatingCount(), c.wikidataSitelinks(), c.placeRole(), c.cuisine());
    }

    /** 주소에서 세부지역을 재추출하되, 추출 실패 시 기존 값을 유지한다. */
    private String subRegionOrKeep(String address, String current) {
        String extracted = PlaceCategoryConstants.extractSubRegion(address);
        return extracted != null ? extracted : current;
    }

    /**
     * 사용자 필수 방문 장소가 최종 스텝에 전부 포함됐는지 최종 검증한다. 주입·보호가 전부
     * 결정론적 코드이므로 누락은 로직 버그다 — hardValidator와 동일하게 로그만 남기고 진행.
     */
    private void verifyUserSelectedIncluded(List<PlaceCandidate> candidates, List<StepData> fixedSteps) {
        Set<String> stepNames = fixedSteps.stream()
                .map(StepData::place)
                .filter(Objects::nonNull)
                .map(p -> normalize(p.name()))
                .collect(Collectors.toSet());
        candidates.stream()
                .filter(PlaceCandidate::userSelected)
                .filter(c -> !stepNames.contains(normalize(c.name())))
                .forEach(c -> log.error("사용자 필수 장소가 최종 일정에서 누락 (결정론적 로직 버그): {}", c.name()));
    }

    private String normalize(String name) {
        return name == null ? "" : name.toLowerCase().replaceAll("\\s+", "");
    }

    /**
     * Manual 모드 자유입력 장소명을 **DB에서** 실장소로 해소한다.
     *
     * <p>예전에는 Google Places 텍스트 검색으로 찾고, 없으면 새 Place를 저장했다. 그 경로는
     * 제거했다 — 받아온 좌표·주소·평점을 보관할 수 없고(Maps 약관 3.2.3(a)), 자유입력 5건이
     * 생성 1건당 Text Search 5회로 그대로 과금됐다.
     *
     * <p>지금은 이름 부분일치로 DB를 뒤지고, 여행지 중심에서 {@value #MAX_ACCOMMODATION_RADIUS_KM}km
     * 안에 있는 후보 중 **중심에 가장 가까운 하나**를 고른다. 동명 타지역 가게가 섞여 들어오는
     * 것을 막는 기준은 Google 시절과 같다. DB에 없는 장소는 만들어내지 않고 실패로 안내한다 —
     * 좌표 없는 Place를 지어내 봐야 지도에도 동선에도 못 쓴다.
     */
    private List<Place> resolveCustomPlaces(ItineraryGenerateRequest request, VectorEnrichedInput enrichedInput,
                                            List<PlaceCandidate> candidatesRaw, GenerationStream emitter) {
        List<String> names = request.customPlaceNames();
        if (names == null || names.isEmpty()) return List.of();

        double[] center = candidateMedianCenter(candidatesRaw);

        List<Place> resolved = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (String raw : names) {
            if (raw == null || raw.isBlank()) continue;
            String name = raw.trim();
            try {
                Optional<Place> match = findCustomPlaceInDb(name, center);
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

    /** 이름 부분일치 중 여행지 중심에 가장 가까운 하나(상한 {@value #CUSTOM_PLACE_SEARCH_LIMIT}건). */
    private Optional<Place> findCustomPlaceInDb(String name, double[] center) {
        // 중심점을 모르면 거리 판정이 불가능하다. 이름이 가장 짧은(= 가장 타이트한) 결과를 쓴다.
        if (center == null) {
            List<Place> byName = placeRepository.findActiveByNameLike(
                    name, PageRequest.of(0, CUSTOM_PLACE_SEARCH_LIMIT));
            return byName.isEmpty() ? Optional.empty() : Optional.of(byName.get(0));
        }

        // 거리순으로 잘라야 한다. 이름 길이순으로 자르면 "스타벅스"처럼 전국에 수백 건인 이름에서
        // 정작 여행지 안의 지점이 상한 밖으로 밀려 나간다.
        List<Place> matches = placeRepository.findActiveByNameLikeNearest(
                name, center[0], center[1], PageRequest.of(0, CUSTOM_PLACE_SEARCH_LIMIT));
        if (matches.isEmpty()) return Optional.empty();

        Place best = null;
        double bestDist = MAX_ACCOMMODATION_RADIUS_KM;
        for (Place p : matches) {
            double d = GeoUtils.haversine(center,
                    new double[]{p.getLatitude().doubleValue(), p.getLongitude().doubleValue()});
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        if (best == null) {
            log.info("자유입력 '{}' DB 매칭 {}건이 모두 여행지 중심에서 {}km 초과 — 동명 타지역으로 보고 제외",
                    name, matches.size(), (long) MAX_ACCOMMODATION_RADIUS_KM);
        }
        return Optional.ofNullable(best);
    }

    /** 후보들의 region 무관 전체 median 중심점 (자유입력 오매칭 검증용). 표본 부족 시 null. */
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
     * Manual 모드 선택 장소 ID를 실제 Place로 조회한다.
     * 프론트 자유입력(음수 임시 ID)은 selectedPlaceIds 변환 시 제외되지만 방어적으로 양수만 조회한다.
     */
    private List<Place> resolveSelectedPlaces(ItineraryGenerateRequest request) {
        List<Long> ids = request.selectedPlaceIds();
        if (ids == null || ids.isEmpty()) return List.of();
        List<Long> validIds = ids.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .collect(Collectors.toList());
        if (validIds.isEmpty()) return List.of();
        return placeRepository.findAllById(validIds);
    }

    /**
     * 사용자 선택 장소를 userSelected=true(필수 방문 — 트림/교체 면제)로 후보 목록 앞쪽에
     * 병합한다. 벡터 검색으로 이미 들어온 후보와 placeId가 겹치면 **그 후보를 userSelected로
     * 마킹**한다 — 기존엔 skip이라 무표식으로 남아 트림 대상이 될 수 있었다.
     * 이후 dedupePhysicalCandidates가 좌표 기준 물리적 중복도 정리한다(플래그 OR 전파).
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
            // similarityScore=1.0: 사용자가 명시적으로 고른 장소이므로 최상위 우선순위 부여
            merged.add(new PlaceCandidate(
                    index++, p.getId(), p.getName(), p.getAddress(), p.getCategory(),
                    p.getTags(), p.getRegion(), p.getCountry(), p.getLatitude(), p.getLongitude(),
                    p.getDescription(), p.getRating(), 1.0, p.getPriceLevel(), p.getOpeningHours(),
                    p.getRecommendedTimeSlots(), p.getRecommendedDurationMinutes(), true, p.getAdmissionFee(),
                    PlaceCategoryConstants.extractSubRegion(p.getAddress()), p.getUserRatingCount(),
                    p.getWikidataSitelinks(), p.getPlaceRole(), p.getCuisine()));
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
                        h.getName(), h.getCategory(), h.getTags()))
                .limit(2)
                .toList();

        if (hotels.isEmpty()) {
            log.warn("숙소 후보 보완 실패: {}", regions);
            return candidates;
        }

        // 보완 숙소도 좌표 sanity 적용 — region 오태깅으로 좌표가 지역 밖인 숙소가 벡터 필터를 우회해
        // 유입되는 것을 막는다(예: region='Jeju'인데 좌표는 타지역). 기존 후보의 region별 median 중심점 기준.
        List<Place> saneHotels = filterAccommodationsByGeography(hotels, candidates);
        if (saneHotels.isEmpty()) {
            // catch-22 회피: 모든 보완 숙소가 좌표상 부적합이어도 숙소 0개보다는 나으므로 최선책 1개는 유지.
            log.warn("보완 숙소가 모두 좌표상 지역 밖 — 최선책 1개 유지: regions={}, name={}",
                    regions, hotels.get(0).getName());
            saneHotels = List.of(hotels.get(0));
        }

        List<PlaceCandidate> result = new ArrayList<>(candidates);
        int nextIndex = candidates.size() + 1;
        for (Place hotel : saneHotels) {
            result.add(new PlaceCandidate(
                    nextIndex++, hotel.getId(), hotel.getName(), hotel.getAddress(),
                    hotel.getCategory(), hotel.getTags(), hotel.getRegion(), hotel.getCountry(),
                    hotel.getLatitude(), hotel.getLongitude(), hotel.getDescription(),
                    hotel.getRating(), 0.0, null, hotel.getOpeningHours(),
                    hotel.getRecommendedTimeSlots(), hotel.getRecommendedDurationMinutes(),
                    false, hotel.getAdmissionFee(),
                    PlaceCategoryConstants.extractSubRegion(hotel.getAddress()),
                    hotel.getUserRatingCount(), hotel.getWikidataSitelinks(),
                    hotel.getPlaceRole(), hotel.getCuisine()
            ));
        }
        return result;
    }

    /**
     * 보완 숙소를 기존 후보의 좌표 median 중심점 기준으로 검증한다.
     * 숙소의 region과 같은 기존 후보들의 중심점에서 {@link #MAX_ACCOMMODATION_RADIUS_KM}를 초과하면 제외.
     * (VectorSearchQueryService.filterGeographicOutliers와 동일한 per-region median 로직)
     * 같은 region 후보가 4개 미만이면 중심점 신뢰도가 낮아 필터하지 않고 통과시킨다.
     */
    List<Place> filterAccommodationsByGeography(List<Place> hotels, List<PlaceCandidate> candidates) {
        List<Place> kept = new ArrayList<>();
        for (Place hotel : hotels) {
            if (hotel.getLatitude() == null || hotel.getLongitude() == null) {
                kept.add(hotel); // 좌표 없으면 판단 보류
                continue;
            }
            List<PlaceCandidate> sameRegion = candidates.stream()
                    .filter(c -> Objects.equals(c.region(), hotel.getRegion()))
                    .filter(c -> c.latitude() != null && c.longitude() != null)
                    .toList();
            if (sameRegion.size() < ACCOMMODATION_GEO_MIN_SAMPLE) {
                kept.add(hotel); // 기준 표본 부족 → 통과
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

    /**
     * [F4] 확정된 뼈대를 day별 step-stream 이벤트로 전송한다.
     * payload: {@code { dayNumber, steps: [{name, startTime, category}] }} — 프론트가 Day 스켈레톤을 실카드로 교체.
     * 장소명이 없는 스텝은 건너뛴다. 전송 실패는 조용히 무시(critical path 아님).
     */
    /** 남은 품질 문제를 SSE로 전달한다. 전송 실패는 무시(critical path 아님). */
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
     * 구조 일정 완료를 알리지만 emitter는 닫지 않는다 — story-ready까지 같은 emitter로 추가
     * 이벤트를 보내야 하므로, 표준 SSE "complete=종료" 관례를 이 흐름에서만 예외로 둔다.
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
