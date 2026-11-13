package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.entity.AlternativeOption;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;
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
 *
 * <p>Place는 DB에서만 해소하고, 해소하지 못한 장소는 만들지 않고 스텝째 버린다.
 * 외부 검색으로 Place를 만들면 Maps 약관 3.2.3(a)(세부정보·좌표 캐싱 금지)에 걸린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItineraryDataMapper {

    private final PlaceRepository placeRepository;
    private final RouteOptimizer routeOptimizer;
    private final ItineraryAutoFixer autoFixer;

    /** 여행지 기준 오매칭 판정 거리(km). 제주↔서울(약 450km) 같은 도시 단위 오매칭을 걸러낸다. */
    private static final double DESTINATION_DISTANCE_THRESHOLD_KM = 150;

    /** 자체 median을 기준 좌표로 채택하기 위한 최소 표본 수. 이하면 이상치 하나에 끌려간다. */
    private static final int MEDIAN_MIN_SAMPLE = 4;

    /** 트랜잭션 경계는 호출자 {@code ItinerarySaveHelper.save()}가 쥔다. 조립과 저장을 분리해 둔다. */
    public Itinerary toEntity(ItineraryData data, EnrichedInput input, Long userId) {
        return toEntity(data, input, userId, false, null);
    }

    /**
     * @param alreadyOptimized RouteOptimizer가 day/순서/시간/교통을 이미 확정했으면 true.
     *                          autoFixer/optimize()를 다시 돌리면 식사 슬롯 등 확정된 구조가 깨진다.
     */
    public Itinerary toEntity(ItineraryData data, EnrichedInput input, Long userId, boolean alreadyOptimized) {
        return toEntity(data, input, userId, alreadyOptimized, null);
    }

    /**
     * @param referenceCenters 여행지별 후보 좌표 median 중심점. 장소는 가장 가까운 중심점에서 잰다(도쿄+오사카 일정).
     *                         null이나 빈 목록이면 일정에 든 장소들의 median을 자체 기준으로 쓴다.
     */
    public Itinerary toEntity(ItineraryData data, EnrichedInput input, Long userId,
                              boolean alreadyOptimized, List<double[]> referenceCenters) {
        Map<String, PlaceData> uniquePlaces = collectUniquePlaces(data);

        Map<String, Place> placeCache = batchResolvePlaces(uniquePlaces, referenceCenters);

        List<StepData> optimizedSteps;
        if (alreadyOptimized) {
            optimizedSteps = data.steps();
        } else {
            ItineraryData deduped = autoFixer.fix(data);
            optimizedSteps = routeOptimizer.optimize(deduped.steps(), placeCache);
        }

        Itinerary itinerary = Itinerary.builder()
                .userId(userId)
                .title(data.title())
                .destination(data.destination())
                .startDate(input.startDate())
                .endDate(input.endDate())
                .totalBudget(input.budget())
                .estimatedCost(data.estimatedCost())
                .tags(data.tags())
                .qualityNotices(data.qualityNotices())
                .build();

        if (optimizedSteps != null) {
            int dropped = 0;
            boolean precededByDrop = false;
            StepData pendingTransfer = null;
            for (StepData stepData : optimizedSteps) {
                if (lookupPlace(stepData.place(), placeCache) == null) {
                    dropped++;
                    precededByDrop = true;
                    // 도시 간 구간은 다음 도시로 가는 이동 자체라 첫 장소가 빠져도 그날 다음 장소가 이어받는다.
                    if (InvariantChecker.isIntercityLeg(stepData)) pendingTransfer = stepData;
                    continue;
                }
                if (pendingTransfer != null && pendingTransfer.dayNumber() == stepData.dayNumber()) {
                    stepData = withTransportOf(stepData, pendingTransfer);
                    precededByDrop = false;
                }
                pendingTransfer = null;
                itinerary.addStep(toStepEntity(stepData, placeCache, precededByDrop));
                precededByDrop = false;
            }
            if (dropped > 0) {
                log.warn("장소 미해소로 제외한 스텝 {}건", dropped);
            }
        }

        return itinerary;
    }

    // ── Private helpers ──

    private static StepData withTransportOf(StepData step, StepData legSource) {
        return new StepData(step.stepOrder(), step.dayNumber(), step.startTime(), step.endTime(), step.place(),
                step.alternatives(), legSource.transportationMode(), legSource.transportationDuration(),
                legSource.transportationDistance(), legSource.transportationCost(), step.notes(), step.estimatedCost());
    }

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
        map.putIfAbsent(altKey(alt), new PlaceData(alt.name(), alt.address(), alt.category(), alt.region(),
                alt.country(), null, null, alt.placeId(), alt.nameKo()));
    }

    /** 주소 없는 체인 지점은 이름+주소가 같아 id가 있으면 id로 구분한다. */
    private String placeKey(PlaceData pd) {
        return key(pd.placeId(), pd.name(), pd.address());
    }

    private String altKey(AlternativeData alt) {
        return key(alt.placeId(), alt.name(), alt.address());
    }

    private static String key(Long placeId, String name, String address) {
        if (placeId != null) return "id:" + placeId;
        return name + "|" + (address != null ? address : "");
    }

    /** 못 찾았거나 너무 먼 장소는 캐시에 넣지 않는다. 호출자가 그 스텝을 버린다. 신선도는 적재 배치의 책임이다. */
    private Map<String, Place> batchResolvePlaces(Map<String, PlaceData> uniquePlaces,
                                                  List<double[]> referenceCenters) {
        List<Long> ids = uniquePlaces.values().stream().map(PlaceData::placeId).filter(Objects::nonNull)
                .distinct().toList();
        // 후보로 고른 장소는 그사이 배치가 비활성으로 돌렸어도 그 장소다. 이름+주소로 다시 찾으면 다른 지점이 붙는다.
        Map<Long, Place> byId = new HashMap<>();
        if (!ids.isEmpty()) {
            placeRepository.findAllById(ids).forEach(p -> byId.put(p.getId(), p));
        }

        // 거리 판정은 기준 좌표가 정해진 뒤에 해야 하므로 조회만 먼저 한다.
        Map<String, Place> found = new LinkedHashMap<>();
        List<String> unresolved = new ArrayList<>();
        for (Map.Entry<String, PlaceData> entry : uniquePlaces.entrySet()) {
            PlaceData pd = entry.getValue();
            Place hit = pd.placeId() != null ? byId.get(pd.placeId()) : null;
            if (hit == null) hit = placeRepository.findByNameAndAddress(pd.name(), pd.address()).orElse(null);
            if (hit == null) {
                unresolved.add(pd.name());
            } else {
                found.put(entry.getKey(), hit);
            }
        }

        // 호출자가 준 기준은 후보 수십 건의 median이고, 자체 median은 일정 장소 몇 개짜리라 약하다.
        boolean selfDerivedReference = referenceCenters == null || referenceCenters.isEmpty();
        List<double[]> centers = selfDerivedReference
                ? Stream.ofNullable(medianOf(found.values())).toList() : referenceCenters;

        // 과반이 걸리면 기준 좌표 쪽을 의심해야 하므로 아직 버리지 않고 추리기만 한다.
        List<String> farKeys = new ArrayList<>();
        for (Map.Entry<String, Place> entry : found.entrySet()) {
            Place place = entry.getValue();
            if (isFarFromDestination(place.getLatitude(), place.getLongitude(), centers)) {
                farKeys.add(entry.getKey());
            }
        }

        /* 위도·경도 median을 따로 구하므로 두 지역이 반반 섞이면 중심점이 그 사이 허공에 찍혀 전부 삭제된다.
         * 자체 median에서 과반이 멀면 기준이 틀린 것으로 보고 필터를 포기한다. 호출자 기준이면 일정이 실제로 엉뚱한 것이다. */
        if (selfDerivedReference && !farKeys.isEmpty() && farKeys.size() * 2 > found.size()) {
            log.warn("여행지 기준 좌표에서 먼 장소가 과반({}/{}) — 기준 좌표를 신뢰할 수 없어 거리 필터를 건너뛴다",
                    farKeys.size(), found.size());
            farKeys.clear();
        }

        Map<String, Place> cache = new HashMap<>(found);
        for (String key : farKeys) {
            Place removed = cache.remove(key);
            log.warn("DB 장소가 여행지에서 너무 멀어 제외: name={}", removed.getName());
            unresolved.add(removed.getName());
        }

        if (!unresolved.isEmpty()) {
            log.warn("DB에서 해소하지 못한 장소 {}건 — 해당 스텝은 제외한다: {}",
                    unresolved.size(), unresolved);
        }

        return cache;
    }

    /**
     * Fallback 경로는 기준 좌표를 넘기지 않아서, 이게 없으면 오매칭 가드가 통째로 꺼진다.
     * 표본이 {@value #MEDIAN_MIN_SAMPLE} 미만이면 null을 준다.
     */
    private double[] medianOf(java.util.Collection<Place> places) {
        List<Double> lats = new ArrayList<>();
        List<Double> lngs = new ArrayList<>();
        for (Place p : places) {
            if (p.getLatitude() == null || p.getLongitude() == null) continue;
            if (p.getLatitude().signum() == 0 && p.getLongitude().signum() == 0) continue;
            lats.add(p.getLatitude().doubleValue());
            lngs.add(p.getLongitude().doubleValue());
        }
        if (lats.size() < MEDIAN_MIN_SAMPLE) return null;
        return new double[]{GeoUtils.median(lats), GeoUtils.median(lngs)};
    }

    /** 모든 중심점에서 멀어야 true. 중심점·좌표가 없거나 (0,0) fallback 마커면 false. */
    private boolean isFarFromDestination(BigDecimal lat, BigDecimal lng, List<double[]> centers) {
        if (centers.isEmpty() || lat == null || lng == null) return false;
        if (BigDecimal.ZERO.compareTo(lat) == 0 && BigDecimal.ZERO.compareTo(lng) == 0) return false;
        return centers.stream().allMatch(c ->
                GeoUtils.haversine(c[0], c[1], lat.doubleValue(), lng.doubleValue()) > DESTINATION_DISTANCE_THRESHOLD_KM);
    }

    /**
     * @param precededByDrop 바로 앞 스텝이 버려졌으면 true. 교통 정보가 사라진 출발지에서 온
     *                       구간이라 비워서 UI가 구간을 그리지 않게 한다.
     */
    private ItineraryStep toStepEntity(StepData stepData, Map<String, Place> placeCache,
                                       boolean precededByDrop) {
        Place place = lookupPlace(stepData.place(), placeCache);

        ItineraryStep step = ItineraryStep.builder()
                .stepOrder(stepData.stepOrder())
                .dayNumber(stepData.dayNumber())
                .startTime(stepData.startTime())
                .endTime(stepData.endTime())
                .place(place)
                .transportationMode(precededByDrop ? null : stepData.transportationMode())
                .transportationDuration(precededByDrop ? null : stepData.transportationDuration())
                .transportationDistance(precededByDrop ? null : stepData.transportationDistance())
                .transportationCost(precededByDrop ? null : stepData.transportationCost())
                .notes(stepData.notes())
                .estimatedCost(stepData.estimatedCost())
                .build();

        if (stepData.alternatives() != null) {
            int order = 1;
            for (AlternativeData altData : stepData.alternatives()) {
                Place altPlace = placeCache.get(altKey(altData));
                // place가 null인 AlternativeOption을 저장하면 조회 시 PlaceResponse.from(null)에서 NPE가 난다.
                if (altPlace == null) continue;

                AlternativeOption alt = AlternativeOption.builder()
                        .place(altPlace)
                        .notes(altData.notes())
                        .estimatedCost(altData.estimatedCost())
                        .optionOrder(order++)
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
