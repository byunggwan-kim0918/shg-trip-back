package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.entity.AlternativeOption;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;
import com.shg.trip.shgtrip.domain.place.client.GooglePlaceDetail;
import com.shg.trip.shgtrip.domain.place.client.GooglePlacesClient;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.dto.AlternativeData;
import com.shg.trip.shgtrip.domain.planning.dto.EnrichedInput;
import com.shg.trip.shgtrip.domain.planning.dto.ItineraryData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Stream;

/**
 * AI 응답(ItineraryData) → Itinerary 엔티티 변환.
 * PlaceData → Place 엔티티 조회/생성 (Google Places API 연동).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItineraryDataMapper {

    private final PlaceRepository placeRepository;
    private final GooglePlacesClient googlePlacesClient;
    private final PlacePersistenceHelper placePersistenceHelper;
    private final RouteOptimizer routeOptimizer;
    private final ItineraryAutoFixer autoFixer;

    /**
     * 여행지 기준 "엉뚱한 장소" 판정 거리(km). 기존 500km는 한국 내 도시 단위 오매칭
     * (예: 제주↔서울 약 450km)을 걸러내지 못할 만큼 느슨했음 — 단일 지역 여행 기준으로
     * 충분히 타이트하게(150km) 좁혔다. 전국 일주처럼 광역 여행이면 destinationCoord 자체가
     * 모호해져 이 검증의 의미가 줄어들지만, 그 경우도 500km보다 150km가 더 안전한 기본값.
     */
    private static final double DESTINATION_DISTANCE_THRESHOLD_KM = 150;

    /**
     * ItineraryData → Itinerary 엔티티 변환.
     * 1) 모든 PlaceData를 수집 → 중복 제거
     * 2) DB 배치 조회 → 미존재분만 Google API 호출 (트랜잭션 밖)
     * 3) 캐시된 Place로 엔티티 조립 (트랜잭션은 호출자 ItinerarySaveHelper가 관리)
     *
     * @Transactional 제거: Google Places API 외부 HTTP 호출이 포함되어 있어
     * 트랜잭션 내 DB 커넥션을 수십 초간 점유하는 문제 방지.
     * 저장 트랜잭션은 ItinerarySaveHelper.save()가 담당.
     */
    public Itinerary toEntity(ItineraryData data, EnrichedInput input, Long userId) {
        return toEntity(data, input, userId, false);
    }

    /**
     * @param alreadyOptimized RouteOptimizer.repairAndSchedule()로 day/순서/시간/교통이
     *                          이미 결정론적으로 확정된 경우 true. 이 경우 autoFixer/optimize()를
     *                          다시 적용하면 pair 인접성·식사 슬롯 등 이미 확정된 구조가
     *                          깨질 수 있으므로 건너뛴다(Optimized 파이프라인 경로).
     */
    public Itinerary toEntity(ItineraryData data, EnrichedInput input, Long userId, boolean alreadyOptimized) {
        // 1. 모든 PlaceData 수집 + 중복 제거
        Map<String, PlaceData> uniquePlaces = collectUniquePlaces(data);

        // 2. 배치로 Place 엔티티 resolve (여행지 기준 좌표로 엉뚱한 장소 필터링)
        Map<String, Place> placeCache = batchResolvePlaces(uniquePlaces, input.destination());

        List<StepData> optimizedSteps;
        if (alreadyOptimized) {
            optimizedSteps = data.steps();
        } else {
            // 2.5. 같은 날 숙소 중복 제거 (강제 삽입 없음 — LLM 흐름 보존)
            ItineraryData deduped = autoFixer.fix(data);
            // 2.6. 좌표 기반 동선 최적화 (같은 날 장소를 가까운 순서로 재정렬)
            optimizedSteps = routeOptimizer.optimize(deduped.steps(), placeCache);
        }

        // 3. 엔티티 조립
        Itinerary itinerary = Itinerary.builder()
                .userId(userId)
                .title(data.title())
                .destination(data.destination())
                .startDate(input.startDate())
                .endDate(input.endDate())
                .totalBudget(input.budget())
                .estimatedCost(data.estimatedCost())
                .tags(data.tags())
                .build();

        if (optimizedSteps != null) {
            for (StepData stepData : optimizedSteps) {
                ItineraryStep step = toStepEntity(stepData, placeCache);
                itinerary.addStep(step);
            }
        }

        return itinerary;
    }

    // ── Private helpers ──

    /**
     * 모든 PlaceData를 "name|address" 키로 중복 제거하여 수집.
     */
    private Map<String, PlaceData> collectUniquePlaces(ItineraryData data) {
        Map<String, PlaceData> map = new LinkedHashMap<>();
        if (data.steps() == null) return map;

        for (StepData step : data.steps()) {
            addToMap(map, step.place());
            if (step.alternatives() != null) {
                step.alternatives().forEach(alt -> addToMap(map, alt));
            }
        }
        return map;
    }

    private void addToMap(Map<String, PlaceData> map, PlaceData pd) {
        if (pd == null || pd.name() == null) return;
        map.putIfAbsent(placeKey(pd), pd);
    }

    private void addToMap(Map<String, PlaceData> map, AlternativeData alt) {
        if (alt == null || alt.name() == null) return;
        map.putIfAbsent(altKey(alt), new PlaceData(alt.name(), alt.address(), alt.category(), alt.region(), alt.country()));
    }

    private String placeKey(PlaceData pd) {
        return pd.name() + "|" + (pd.address() != null ? pd.address() : "");
    }

    private String altKey(AlternativeData alt) {
        return alt.name() + "|" + (alt.address() != null ? alt.address() : "");
    }

    /**
     * 배치 Place resolve:
     * 1) DB에서 name+address로 일괄 조회
     *    - 유효(7일 미만): 캐시에 직접 등록
     *    - 만료(7일 이상): stale 목록으로 분류 → Google API로 갱신
     * 2) 미존재분 + 만료분: Google API 호출 (신규 저장 or 기존 업데이트)
     * 3) Google 실패 시 fallback 처리
     */
    private Map<String, Place> batchResolvePlaces(Map<String, PlaceData> uniquePlaces, String destination) {
        Map<String, Place> cache = new HashMap<>();
        Map<String, Place> stalePlaces = new HashMap<>();
        int fallbackCount = 0;

        // 여행지 기준 좌표 조회 (Google API로 1회 검색) — 엉뚱한 장소 필터링에 사용
        double[] destinationCoord = resolveDestinationCoord(destination);

        // 1. DB 배치 조회 — 유효/만료 분류. 여행지에서 너무 먼 기존 DB row(오매칭/오래된 잘못된
        // 데이터)는 캐시에 그대로 쓰지 않고 "못 찾음"으로 취급해 2단계에서 재해결하게 한다.
        for (Map.Entry<String, PlaceData> entry : uniquePlaces.entrySet()) {
            PlaceData pd = entry.getValue();
            placeRepository.findByNameAndAddress(pd.name(), pd.address())
                    .ifPresent(place -> {
                        if (isFarFromDestination(place.getLatitude(), place.getLongitude(), destinationCoord)) {
                            log.warn("DB에 캐시된 장소가 여행지에서 너무 멀어 재해결 대상으로 처리: name={}", place.getName());
                            return;
                        }
                        if (place.isStale()) {
                            stalePlaces.put(entry.getKey(), place);
                        } else {
                            cache.put(entry.getKey(), place);
                        }
                    });
        }

        // 2. 신규 장소: Google API 조회 + fallback
        for (Map.Entry<String, PlaceData> entry : uniquePlaces.entrySet()) {
            String key = entry.getKey();
            if (cache.containsKey(key)) continue;

            PlaceData pd = entry.getValue();
            if (stalePlaces.containsKey(key)) {
                // 만료된 장소: Google API로 정보 갱신
                Place refreshed = refreshFromGoogle(stalePlaces.get(key), pd, destinationCoord);
                cache.put(key, refreshed);
                if (isFallbackPlace(refreshed)) {
                    fallbackCount++;
                }
            } else {
                // 신규 장소: Google API 조회 후 저장
                Place resolved = resolveFromGoogleOrFallback(pd, destinationCoord);
                cache.put(key, resolved);
                if (isFallbackPlace(resolved)) {
                    fallbackCount++;
                }
            }
        }

        if (fallbackCount > 0) {
            log.warn("Google Places 미확인 장소 {}건 (좌표·사진 없음, 지도 표시 불가)", fallbackCount);
        }

        return cache;
    }

    /**
     * fallback으로 생성된 장소인지 판별 (좌표가 0,0이면 fallback).
     */
    private boolean isFallbackPlace(Place place) {
        return place.getLatitude() != null && place.getLongitude() != null
                && BigDecimal.ZERO.compareTo(place.getLatitude()) == 0
                && BigDecimal.ZERO.compareTo(place.getLongitude()) == 0;
    }

    /**
     * 여행지 이름으로 기준 좌표를 조회.
     * 실패 시 null 반환 — 거리 검증 skip (서비스 중단 방지).
     */
    private double[] resolveDestinationCoord(String destination) {
        try {
            Optional<GooglePlaceDetail> detail = googlePlacesClient.searchAndGetDetail(destination);
            if (detail.isPresent() && detail.get().lat() != 0.0) {
                return new double[]{detail.get().lat(), detail.get().lng()};
            }
        } catch (Exception e) {
            log.warn("여행지 기준 좌표 조회 실패: destination={}, error={}", destination, e.getMessage());
        }
        return null;
    }

    /**
     * 만료된 Place를 Google Places API로 갱신.
     * 갱신 실패 시 오래진 데이터를 그대로 반환 (서비스 중단 방지).
     * Google이 여행지에서 너무 먼 좌표를 반환하면(오매칭) 갱신을 적용하지 않고 기존 데이터를 유지한다
     * — 잘못된 좌표로 덮어써서 지도에 엉뚱한 위치가 찍히는 것을 방지.
     * photoReference는 DB에 저장되며, 이미지는 화면 조회 시 PlaceImageAsyncRecovery에서 비동기로 다운로드.
     */
    private Place refreshFromGoogle(Place stalePlace, PlaceData placeData, double[] destinationCoord) {
        try {
            Optional<GooglePlaceDetail> detail =
                    googlePlacesClient.searchAndGetDetail(placeData.name() + " " + placeData.address());
            if (detail.isPresent()) {
                GooglePlaceDetail d = detail.get();
                if (isFarFromDestination(BigDecimal.valueOf(d.lat()), BigDecimal.valueOf(d.lng()), destinationCoord)) {
                    log.warn("Google 갱신 결과가 여행지에서 너무 멀어 적용하지 않고 기존 데이터 유지: name={}", stalePlace.getName());
                    return stalePlace;
                }
                stalePlace.update(d.address(), d.lat(), d.lng(), d.rating(),
                        d.priceLevel(), d.openingHours(), d.photoReference(), d.sourceUrl(),
                        d.editorialSummary());
                return placePersistenceHelper.updateAndSave(stalePlace);
            }
        } catch (com.shg.trip.shgtrip.global.exception.BusinessException e) {
            log.warn("Google Places API 장애로 stale 데이터 유지: name={}, error={}", stalePlace.getName(), e.getMessage());
        } catch (Exception e) {
            log.warn("Google Places 갱신 실패: name={}, error={}", stalePlace.getName(), e.getMessage());
        }
        log.warn("만료된 장소 데이터 그대로 사용: name={}", stalePlace.getName());
        return stalePlace;
    }

    /** 여행지 기준 좌표에서 너무 먼지 판정. destinationCoord/좌표가 없거나 (0,0) fallback 마커면 false. */
    private boolean isFarFromDestination(BigDecimal lat, BigDecimal lng, double[] destinationCoord) {
        if (destinationCoord == null || lat == null || lng == null) return false;
        if (BigDecimal.ZERO.compareTo(lat) == 0 && BigDecimal.ZERO.compareTo(lng) == 0) return false;
        double distKm = GeoUtils.haversine(destinationCoord[0], destinationCoord[1], lat.doubleValue(), lng.doubleValue());
        return distKm > DESTINATION_DISTANCE_THRESHOLD_KM;
    }

    /**
     * Google Places API 조회 → 실패 시 fallback 저장.
     * - BusinessException(외부 API 장애)은 fallback으로 처리 (일정 생성 전체 중단 방지)
     * - 일반 예외(파싱 오류 등)도 fallback 처리
     * - photoReference는 DB에 저장되며, 이미지는 화면 조회 시 PlaceImageAsyncRecovery에서 비동기로 다운로드
     * - destinationCoord가 있으면 여행지 기준 {@link #DESTINATION_DISTANCE_THRESHOLD_KM} 초과 장소는
     *   fallback 처리 (엉뚱한 장소 방지)
     */
    private Place resolveFromGoogleOrFallback(PlaceData placeData, double[] destinationCoord) {
        // Google API 시도
        try {
            Optional<GooglePlaceDetail> detail =
                    googlePlacesClient.searchAndGetDetail(placeData.name() + " " + placeData.address());

            if (detail.isPresent()) {
                GooglePlaceDetail d = detail.get();

                // 여행지 기준 거리 검증: 초과 시 엉뚱한 장소로 판단 → fallback
                if (d.lat() != 0.0 && d.lng() != 0.0
                        && isFarFromDestination(BigDecimal.valueOf(d.lat()), BigDecimal.valueOf(d.lng()), destinationCoord)) {
                    log.warn("Google Places 결과가 여행지에서 너무 멀어 fallback 처리: name={}", placeData.name());
                    return buildFallbackPlace(placeData);
                }

                // Google이 반환한 name+address로 DB에 이미 있는지 확인
                Optional<Place> existing = placeRepository.findByNameAndAddress(d.name(), d.address());
                if (existing.isPresent()) {
                    Place ex = existing.get();
                    if (ex.isStale()) {
                        ex.update(d.address(), d.lat(), d.lng(), d.rating(),
                                d.priceLevel(), d.openingHours(), d.photoReference(), d.sourceUrl(),
                                d.editorialSummary());
                        return placePersistenceHelper.updateAndSave(ex);
                    }
                    return ex;
                }

                Place place = Place.builder()
                        .name(d.name())
                        .address(d.address())
                        .latitude(BigDecimal.valueOf(d.lat()))
                        .longitude(BigDecimal.valueOf(d.lng()))
                        .category(placeData.category() != null ? placeData.category() : "기타")
                        .region(placeData.region())
                        .country(placeData.country())
                        .rating(d.rating() != null ? BigDecimal.valueOf(d.rating()) : null)
                        .priceLevel(d.priceLevel())
                        .openingHours(d.openingHours())
                        .photoReference(d.photoReference())
                        .sourceUrl(d.sourceUrl())
                        .source("google")
                        .savedAt(OffsetDateTime.now())
                        .build();
                return placeRepository.save(place);
            }
        } catch (com.shg.trip.shgtrip.global.exception.BusinessException e) {
            log.warn("Google Places API 장애로 fallback 처리: name={}, error={}", placeData.name(), e.getMessage());
        } catch (Exception e) {
            log.warn("Google Places API 조회 실패: name={}, error={}", placeData.name(), e.getMessage());
        }

        // Fallback: AI가 생성한 name+address로 DB 조회 (동시성 대비)
        Optional<Place> existing = placeRepository.findByNameAndAddress(
                placeData.name(), placeData.address());
        if (existing.isPresent()) return existing.get();

        return buildFallbackPlace(placeData);
    }

    private Place buildFallbackPlace(PlaceData placeData) {
        Place fallback = Place.builder()
                .name(placeData.name())
                .address(placeData.address() != null ? placeData.address() : "주소 미확인")
                .latitude(BigDecimal.ZERO)
                .longitude(BigDecimal.ZERO)
                .category(placeData.category() != null ? placeData.category() : "기타")
                .region(placeData.region())
                .country(placeData.country())
                .source("google")
                .savedAt(OffsetDateTime.now())
                .build();
        return placeRepository.save(fallback);
    }

    private ItineraryStep toStepEntity(StepData stepData, Map<String, Place> placeCache) {
        Place place = lookupPlace(stepData.place(), placeCache);

        ItineraryStep step = ItineraryStep.builder()
                .stepOrder(stepData.stepOrder())
                .dayNumber(stepData.dayNumber())
                .startTime(stepData.startTime())
                .endTime(stepData.endTime())
                .place(place)
                .transportationMode(stepData.transportationMode())
                .transportationDuration(stepData.transportationDuration())
                .transportationDistance(stepData.transportationDistance())
                .transportationCost(stepData.transportationCost())
                .notes(stepData.notes())
                .estimatedCost(stepData.estimatedCost())
                .build();

        if (stepData.alternatives() != null) {
            for (int i = 0; i < stepData.alternatives().size(); i++) {
                AlternativeData altData = stepData.alternatives().get(i);
                Place altPlace = placeCache.get(altKey(altData));

                AlternativeOption alt = AlternativeOption.builder()
                        .place(altPlace)
                        .notes(altData.notes())
                        .estimatedCost(altData.estimatedCost())
                        .optionOrder(i + 1)
                        .generatedAt(OffsetDateTime.now())
                        .build();
                step.addAlternative(alt);
            }
        }

        return step;
    }

    private Place lookupPlace(PlaceData pd, Map<String, Place> cache) {
        if (pd == null || pd.name() == null) return null;
        return cache.get(placeKey(pd));
    }
}
