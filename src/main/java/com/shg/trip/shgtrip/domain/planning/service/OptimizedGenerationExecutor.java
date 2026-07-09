package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.place.client.GooglePlaceDetail;
import com.shg.trip.shgtrip.domain.place.client.GooglePlacesClient;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.place.service.PlaceRefreshService;
import com.shg.trip.shgtrip.domain.planning.dto.*;
import com.shg.trip.shgtrip.domain.planning.service.ai.SelectionCallGenerator;
import com.shg.trip.shgtrip.domain.planning.service.ai.OptimizedClaudeAIService;
import com.shg.trip.shgtrip.domain.planning.service.validation.PlaceRegionValidator;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final PlaceRefreshService placeRefreshService;
    private final PlaceRepository placeRepository;
    private final PlaceRegionValidator placeRegionValidator;
    private final GooglePlacesClient googlePlacesClient;
    private final PlacePersistenceHelper placePersistenceHelper;

    /** blocking I/O(Google API) 전용 풀 — commonPool 점유 방지 (AsyncConfig.googleSyncExecutor). */
    @org.springframework.beans.factory.annotation.Qualifier("googleSyncExecutor")
    private final java.util.concurrent.Executor googleSyncExecutor;

    /** 보완 숙소가 기존 후보 median 중심점에서 이 거리(km)를 초과하면 지역 오태깅으로 보고 제외. */
    private static final double MAX_ACCOMMODATION_RADIUS_KM = 100.0;
    /** 보완 숙소 좌표 검증용 region별 최소 후보 표본 수(미만이면 중심점 신뢰도 낮아 필터 skip). */
    private static final int ACCOMMODATION_GEO_MIN_SAMPLE = 4;

    @Async("planningExecutor")
    public void execute(String jobId, ItineraryGenerateRequest request, Long userId, SseEmitter emitter) {
        try {
            // [10%] Haiku enrichInput
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "ENRICHING", 10, "입력을 분석하고 있습니다...");

            EnrichmentResult enrichResult = executeWithHeartbeat(emitter,
                    () -> optimizedClaudeAIService.enrichInput(request));

            if (!enrichResult.valid()) {
                sendSseError(emitter, enrichResult.errorCode(), enrichResult.errorMessage());
                return;
            }

            VectorEnrichedInput enrichedInput = enrichResult.enrichedInput();
            long days = ChronoUnit.DAYS.between(enrichedInput.startDate(), enrichedInput.endDate()) + 1;

            // [20%] 카테고리별 벡터 검색
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "SEARCHING", 20, "장소를 검색하고 있습니다...");

            List<PlaceCandidate> candidatesRaw = executeWithHeartbeat(emitter,
                    () -> vectorSearchQueryService.search(enrichedInput));

            // Manual 모드: 사용자가 선택한 장소(ID) + 자유입력 장소명(Google 실장소화)을 resolve
            // → 지역 검증 → 후보에 userSelected(필수 방문)로 병합.
            List<Place> selectedPlaces = new ArrayList<>(resolveSelectedPlaces(request));
            // 자유입력 실장소화는 Google 호출(최대 5건 × read 10s)이 순차라 최악 지연이 heartbeat
            // 주기(30초)를 넘길 수 있어 반드시 heartbeat로 감싼다(프록시 idle 타임아웃 방지).
            selectedPlaces.addAll(executeWithHeartbeat(emitter,
                    () -> resolveCustomPlaces(request, enrichedInput, candidatesRaw, emitter)));
            placeRegionValidator.validate(selectedPlaces, enrichedInput); // 불일치 시 BusinessException → SSE error
            List<PlaceCandidate> candidates = mergeSelectedPlaces(candidatesRaw, selectedPlaces);

            // [25%] Fallback/컴팩트 분기 판단
            if (cancellationRegistry.isCancelled(jobId)) return;
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

            // [35%] Google Places 동기화
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "SYNCING", 35, "장소 정보를 동기화하고 있습니다...");

            List<Long> placeIds = candidates.stream()
                    .map(PlaceCandidate::placeId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());

            // 후보 등장 시각 기록 (월배치 사전채움 인기도 기준) — 벌크 UPDATE 1회, 비용 미미
            if (!placeIds.isEmpty()) {
                placeRepository.markCandidateAppearance(placeIds, OffsetDateTime.now());
            }

            List<Place> toSync = placeRepository.findByIdAndNeedsSync(
                    placeIds,
                    OffsetDateTime.now().minusDays(Place.STALENESS_DAYS)
            );

            if (!toSync.isEmpty()) {
                log.info("Google Places 동기화: {}건", toSync.size());
                syncAllPlaces(toSync);
            }

            // DB 최신 데이터 반영 + 숙소 보완 + 물리적 중복 제거(선택 이전 1회)
            final List<PlaceCandidate> enrichedCandidates = dedupePhysicalCandidates(
                    ensureAccommodationCandidates(
                            enrichCandidatesFromDb(candidates), enrichedInput.regions()));

            // [50%] Call 1: Sonnet selectPlaces
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "SELECTING", 50, "날짜별 장소를 선택하고 있습니다...");

            SelectionOutput rawSelectionOutput = executeWithHeartbeat(emitter,
                    () -> selectionCallGenerator.selectPlaces(enrichedInput, enrichedCandidates));

            // 중간 날 숙소 누락 보정 + 사용자 필수 장소 누락 주입 (추가 LLM 호출 없는 결정론적 보정)
            final SelectionOutput selectionOutput = indexResultMapper.injectRequiredPlaces(
                    indexResultMapper.fillMissingAccommodation(rawSelectionOutput, enrichedCandidates),
                    enrichedCandidates);

            // [65%] Backend Repair·Optimizer: day/순서/시간/교통/대안 전부 결정론적으로 확정
            // (LLM 재호출 없음 — pace quota·pair·거리이탈·연속숙소·허브를 fixpoint로 수리 후 NN+2-opt)
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "OPTIMIZING", 65, "동선과 시간을 확정하고 있습니다...");

            final boolean compact = compactMode;
            List<StepData> fixedSteps = executeWithHeartbeat(emitter,
                    () -> routeOptimizer.repairAndSchedule(
                            selectionOutput, enrichedCandidates, enrichedInput.pace(),
                            enrichedInput.transportPref(), enrichedInput.startDate(),
                            enrichedInput.themes(), enrichedInput.transportationHub(), compact));

            // [80%] 구조 검증(안전망 — 결정론적 코드이므로 실패 시 재시도가 아니라 버그로 취급)
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "VALIDATING", 80, "일정을 검증하고 있습니다...");

            String destination = enrichedInput.normalizedDestination() != null
                    ? enrichedInput.normalizedDestination() : enrichedInput.destination();
            ItineraryData draftData = indexResultMapper.toDraftItineraryData(
                    fixedSteps, destination, selectionOutput.concept());
            HardValidationResult validationResult = hardValidator.validate(draftData);
            if (!validationResult.valid()) {
                log.error("Optimized 경로 구조 검증 실패 (결정론적 로직 버그 가능성): {}", validationResult.failureReason());
            }
            verifyUserSelectedIncluded(enrichedCandidates, fixedSteps);

            // [90%] 구조 일정 저장 (story는 비어있음) — 즉시 complete, story는 비동기로 채움
            if (cancellationRegistry.isCancelled(jobId)) return;
            sendSseEvent(emitter, "SAVING", 90, "일정을 저장하고 있습니다...");

            EnrichedInput legacyInput = toLegacyEnrichedInput(enrichedInput);
            Itinerary saved = saveHelper.save(draftData, legacyInput, userId, true);

            // [100%] 구조 완료 — emitter는 닫지 않고 story-ready까지 유지
            resultStore.save(jobId, saved.getId());
            sendSseEvent(emitter, "COMPLETE", 100, "일정 생성이 완료되었습니다.");
            sendCompleteKeepOpen(emitter, saved.getId());

            log.info("OptimizedGeneration 구조 완료: jobId={}, itineraryId={}, days={}",
                    jobId, saved.getId(), days);

            // 비동기 스토리텔링 — critical path 밖에서 진행, 완료 시 story-ready emit 후 emitter 종료
            storyGenerationService.generateAndAttach(
                    jobId, emitter, saved.getId(), fixedSteps, selectionOutput.concept(), enrichedInput);

        } catch (BusinessException e) {
            log.error("OptimizedGeneration 비즈니스 오류 (jobId={}): {}", jobId, e.getMessage());
            sendSseError(emitter, "GENERATION_FAILED", e.getMessage());
        } catch (Exception e) {
            log.error("OptimizedGeneration 예기치 않은 오류 (jobId={})", jobId, e);
            sendSseError(emitter, "UNEXPECTED_ERROR", "일정 생성 중 오류가 발생했습니다.");
        }
    }

    // ── Private helpers ──

    private void syncAllPlaces(List<Place> places) {
        log.info("Google Places 동기화 시작: {}건", places.size());

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Place place : places) {
            if (place.getId() == null) continue;
            futures.add(CompletableFuture.runAsync(() ->
                    placeRefreshService.refreshSync(place.getId(), place.getName()),
                    googleSyncExecutor   // blocking I/O 전용 풀 (commonPool 점유 방지)
            ));
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(20, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("Google Places 동기화 타임아웃 (20초), 동기화된 장소만 사용");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Google Places 동기화 인터럽트");
        } catch (ExecutionException e) {
            log.warn("Google Places 동기화 중 오류: {}", e.getMessage());
        }
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
                    place.getAdmissionFee()
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

    /** 이름 동일성 키: region + 공백제거·소문자 이름. 이름이 없으면 병합하지 않는다(고유 키). */
    private String nameKey(PlaceCandidate c) {
        String name = c.name() == null ? "" : c.name().toLowerCase().replaceAll("\\s+", "");
        if (name.isEmpty()) return "idx:" + c.index();
        return (c.region() == null ? "" : c.region()) + "|" + name;
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
                c.recommendedTimeSlots(), c.recommendedDurationMinutes(), c.userSelected(), c.admissionFee());
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
     * Manual 모드 자유입력 장소명을 Google Places(languageCode=ko)로 실장소화한다.
     * 기존엔 프론트가 자유입력을 조용히 버렸다 — 사용자는 넣었다고 생각하지만 서버 미도달.
     * <ul>
     *   <li>이름+여행지로 텍스트 검색 → 후보 median 중심점 100km 초과면 오매칭(동명 타지역) 제외</li>
     *   <li>name+address로 기존 Place 재사용, 없으면 신규 저장(source='google')</li>
     *   <li>실패분은 SSE로 정직하게 안내 후 계속. MANUAL인데 ID도 없고 전부 실패면 예외</li>
     * </ul>
     * 최대 5건(@Size) 순차 호출 — executeWithHeartbeat 바깥이지만 건당 ~0.5s라 SSE 유지에 문제없다.
     */
    private List<Place> resolveCustomPlaces(ItineraryGenerateRequest request, VectorEnrichedInput enrichedInput,
                                            List<PlaceCandidate> candidatesRaw, SseEmitter emitter) {
        List<String> names = request.customPlaceNames();
        if (names == null || names.isEmpty()) return List.of();

        double[] center = candidateMedianCenter(candidatesRaw);
        String destination = enrichedInput.normalizedDestination() != null
                ? enrichedInput.normalizedDestination() : enrichedInput.destination();

        List<Place> resolved = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (String raw : names) {
            if (raw == null || raw.isBlank()) continue;
            String name = raw.trim();
            try {
                Optional<GooglePlaceDetail> detailOpt = googlePlacesClient.searchAndGetDetail(name + " " + destination);
                if (detailOpt.isEmpty() || detailOpt.get().name() == null
                        || (detailOpt.get().lat() == 0.0 && detailOpt.get().lng() == 0.0)) {
                    failed.add(name);
                    continue;
                }
                GooglePlaceDetail detail = detailOpt.get();
                if (center != null && GeoUtils.haversine(center,
                        new double[]{detail.lat(), detail.lng()}) > MAX_ACCOMMODATION_RADIUS_KM) {
                    log.info("자유입력 '{}' Google 매칭({})이 여행지 중심에서 100km 초과 — 오매칭으로 제외",
                            name, detail.name());
                    failed.add(name);
                    continue;
                }
                resolved.add(findOrCreateCustomPlace(detail, enrichedInput, candidatesRaw));
            } catch (Exception e) {
                log.warn("자유입력 '{}' 실장소화 실패: {}", name, e.getMessage());
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

    /**
     * name+address 기존 레코드 재사용, 없으면 신규 저장. @Async 생성 파이프라인은 논트랜잭션이라
     * (ItinerarySaveHelper 관례대로) 저장을 REQUIRES_NEW 독립 트랜잭션 헬퍼로 분리하고, 경합
     * 시 유니크 위반은 재조회로 멱등 복구한다. Place는 일정과 무관한 재사용 캐시라 독립 커밋이 정상.
     */
    private Place findOrCreateCustomPlace(GooglePlaceDetail detail, VectorEnrichedInput enrichedInput,
                                          List<PlaceCandidate> candidatesRaw) {
        String address = detail.address() != null ? detail.address() : "주소 미확인";
        return placePersistenceHelper.findOrCreate(detail.name(), address, () -> Place.builder()
                .googlePlaceId(detail.placeId())
                .name(detail.name())
                .address(address)
                .latitude(BigDecimal.valueOf(detail.lat()))
                .longitude(BigDecimal.valueOf(detail.lng()))
                .category(mapGoogleTypesToCategory(detail.types()))
                // region은 여행지 첫 region 강제 대신 좌표상 가장 가까운 후보의 region을 상속
                // (검증·dedupe가 실제 지역 기준으로 동작하도록). 상속 불가면 null(하류 null-safe).
                .region(inheritRegion(detail, candidatesRaw))
                .country(enrichedInput.country())
                .rating(detail.rating() != null ? BigDecimal.valueOf(detail.rating()) : null)
                .priceLevel(detail.priceLevel())
                .openingHours(detail.openingHours())
                .photoReference(detail.photoReference())
                .sourceUrl(detail.sourceUrl())
                .description(detail.editorialSummary())
                .source("google")
                .active(true)
                .savedAt(OffsetDateTime.now())
                .build());
    }

    /** 좌표상 가장 가까운 기존 후보의 region 상속(30km 이내). 없으면 null. */
    private String inheritRegion(GooglePlaceDetail detail, List<PlaceCandidate> candidatesRaw) {
        double[] target = {detail.lat(), detail.lng()};
        String best = null;
        double bestDist = 30.0; // 30km 초과면 지역 상속 부적절
        for (PlaceCandidate c : candidatesRaw) {
            if (!hasValidCoords(c) || c.region() == null) continue;
            double d = GeoUtils.haversine(target,
                    new double[]{c.latitude().doubleValue(), c.longitude().doubleValue()});
            if (d < bestDist) {
                bestDist = d;
                best = c.region();
            }
        }
        return best;
    }

    /**
     * Google types → 내부 카테고리(Foursquare 계층 형식) 매핑. majorCategory 판정과 호환되는
     * 형식이어야 식사 슬롯/대안 매칭이 정상 동작한다. 불명이면 관광지로 취급(사용자가 "가고
     * 싶은 곳"으로 넣은 의도에 가장 가까움).
     */
    private String mapGoogleTypesToCategory(List<String> types) {
        if (types != null) {
            for (String t : types) {
                String lower = t.toLowerCase();
                if (lower.contains("lodging") || lower.contains("hotel")) {
                    return "Travel and Transportation > Lodging";
                }
                if (lower.contains("cafe") || lower.contains("coffee") || lower.contains("bakery")) {
                    return "Dining and Drinking > Cafe, Coffee, and Tea House";
                }
                if (lower.contains("bar") || lower.contains("night_club")) {
                    return "Dining and Drinking > Bar";
                }
                if (lower.contains("restaurant") || lower.contains("food")) {
                    return "Dining and Drinking > Restaurant";
                }
            }
        }
        return "Landmarks and Outdoors > Tourist Attraction";
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
                    p.getRecommendedTimeSlots(), p.getRecommendedDurationMinutes(), true, p.getAdmissionFee()));
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
            List<PlaceCandidate> candidates, List<String> regions) {

        boolean hasAccommodation = candidates.stream()
                .anyMatch(c -> c.category() != null
                        && c.category().toLowerCase().contains("lodging"));

        if (hasAccommodation) return candidates;

        if (regions == null || regions.isEmpty()) return candidates;

        List<Place> hotels = placeRepository.findTopAccommodationsByRegions(
                regions, PageRequest.of(0, 2));

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
                    hotel.getRating(), 0.0, null, hotel.getOpeningHours()
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

    private void sendSseEvent(SseEmitter emitter, String status, int progress, String message) {
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

    private void sendSseError(SseEmitter emitter, String errorCode, String message) {
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
    private void sendCompleteKeepOpen(SseEmitter emitter, Long itineraryId) {
        try {
            emitter.send(SseEmitter.event()
                    .name("complete")
                    .data(Map.of("status", "DONE", "itineraryId", itineraryId, "pipeline", "optimized")));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 완료 이벤트 전송 실패: {}", e.getMessage());
        }
    }

    private <T> T executeWithHeartbeat(SseEmitter emitter, Callable<T> task) {
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
