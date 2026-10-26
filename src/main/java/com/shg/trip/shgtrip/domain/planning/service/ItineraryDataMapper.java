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
 * <p>PlaceData → Place는 **DB에서만** 해소한다. 예전에는 DB에 없으면 Google Places로 검색해
 * 새 Place를 만들고, 그마저 실패하면 좌표 (0,0)짜리 fallback 레코드를 저장했다. 두 경로 모두
 * 제거했다 — 전자는 Maps 약관 3.2.3(a)(세부정보·좌표 캐싱 금지) 위반이고, 후자는 지도에 찍히지도
 * 검색에 걸리지도 않는 쓰레기 행을 남긴다(벡터 검색이 {@code NOT(lat=0 AND lng=0)}으로 걸러낸다).
 * 해소하지 못한 장소는 **만들지 않고 스텝째 버린다**.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItineraryDataMapper {

    private final PlaceRepository placeRepository;
    private final RouteOptimizer routeOptimizer;
    private final ItineraryAutoFixer autoFixer;

    /**
     * 여행지 기준 "엉뚱한 장소" 판정 거리(km). 기존 500km는 한국 내 도시 단위 오매칭
     * (예: 제주↔서울 약 450km)을 걸러내지 못할 만큼 느슨했음 — 단일 지역 여행 기준으로
     * 충분히 타이트하게(150km) 좁혔다. 전국 일주처럼 광역 여행이면 destinationCoord 자체가
     * 모호해져 이 검증의 의미가 줄어들지만, 그 경우도 500km보다 150km가 더 안전한 기본값.
     */
    private static final double DESTINATION_DISTANCE_THRESHOLD_KM = 150;

    /** 자체 median을 기준 좌표로 채택하기 위한 최소 표본 수. 이하면 이상치 하나에 끌려간다. */
    private static final int MEDIAN_MIN_SAMPLE = 4;

    /**
     * ItineraryData → Itinerary 엔티티 변환.
     * 1) 모든 PlaceData를 수집 → 중복 제거
     * 2) DB 배치 조회 — 못 찾은 장소는 그 스텝째 버린다
     * 3) 해소된 Place로 엔티티 조립 (트랜잭션은 호출자 ItinerarySaveHelper가 관리)
     *
     * <p>이 클래스에 {@code @Transactional}이 없는 이유는 원래 Google HTTP 호출이 섞여 있어
     * 커넥션을 수십 초 점유했기 때문이다. 호출은 사라졌지만 저장 트랜잭션 경계는 그대로
     * {@code ItinerarySaveHelper.save()}가 쥔다 — 조립과 저장을 분리해 두는 편이 낫다.
     */
    public Itinerary toEntity(ItineraryData data, EnrichedInput input, Long userId) {
        return toEntity(data, input, userId, false, null);
    }

    /**
     * @param alreadyOptimized RouteOptimizer.repairAndSchedule()로 day/순서/시간/교통이
     *                          이미 결정론적으로 확정된 경우 true. 이 경우 autoFixer/optimize()를
     *                          다시 적용하면 pair 인접성·식사 슬롯 등 이미 확정된 구조가
     *                          깨질 수 있으므로 건너뛴다(Optimized 파이프라인 경로).
     */
    public Itinerary toEntity(ItineraryData data, EnrichedInput input, Long userId, boolean alreadyOptimized) {
        return toEntity(data, input, userId, alreadyOptimized, null);
    }

    /**
     * @param referenceCoord 후보 장소 좌표의 median 중심점(있으면). 여행지에서 멀리 떨어진
     *                       오매칭 장소를 걸러내는 유일한 기준점이다. null이면 거리 검증을
     *                       건너뛴다 — 기준이 없는데 거르면 멀쩡한 장소까지 버린다.
     */
    public Itinerary toEntity(ItineraryData data, EnrichedInput input, Long userId,
                              boolean alreadyOptimized, double[] referenceCoord) {
        // 1. 모든 PlaceData 수집 + 중복 제거
        Map<String, PlaceData> uniquePlaces = collectUniquePlaces(data);

        // 2. 배치로 Place 엔티티 resolve (여행지 기준 좌표로 엉뚱한 장소 필터링)
        Map<String, Place> placeCache = batchResolvePlaces(uniquePlaces, referenceCoord);

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
                .qualityNotices(data.qualityNotices())
                .build();

        if (optimizedSteps != null) {
            int dropped = 0;
            boolean precededByDrop = false;
            for (StepData stepData : optimizedSteps) {
                // 장소를 DB에서 못 찾은 스텝은 버린다. 예전엔 좌표 (0,0) fallback Place를 만들어
                // 붙였는데, 그건 지도에 안 찍히고 검색에도 안 걸리는 빈 카드로 남을 뿐이었다.
                if (lookupPlace(stepData.place(), placeCache) == null) {
                    dropped++;
                    precededByDrop = true;
                    continue;
                }
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
     * 배치 Place resolve — DB에서만 해소한다.
     *
     * <p>name+address로 일괄 조회하고, 여행지에서 {@link #DESTINATION_DISTANCE_THRESHOLD_KM}
     * 넘게 떨어진 행은 오매칭으로 보고 버린다. 못 찾은 장소는 캐시에 넣지 않는다 —
     * 호출자({@link #toEntity})가 그 스텝을 버린다.
     *
     * <p>stale(7일 경과) 분기가 사라졌다. 갱신할 외부 소스가 없으므로 "오래됨"이 더 이상
     * 행동을 가르지 않는다. 신선도는 적재 배치의 책임이다.
     */
    private Map<String, Place> batchResolvePlaces(Map<String, PlaceData> uniquePlaces,
                                                  double[] referenceCoord) {
        // 1차: DB 조회만 한다. 거리 판정은 기준 좌표가 정해진 뒤에 해야 하므로 여기서 미룬다.
        Map<String, Place> found = new LinkedHashMap<>();
        List<String> unresolved = new ArrayList<>();
        for (Map.Entry<String, PlaceData> entry : uniquePlaces.entrySet()) {
            PlaceData pd = entry.getValue();
            Optional<Place> hit = placeRepository.findByNameAndAddress(pd.name(), pd.address());
            if (hit.isEmpty()) {
                unresolved.add(pd.name());
            } else {
                found.put(entry.getKey(), hit.get());
            }
        }

        // 기준 좌표의 출처를 구분해 둔다. 호출자가 준 값은 후보 수십 건의 median이라 신뢰할 수
        // 있고, 우리가 직접 뽑은 median은 일정에 들어온 장소 몇 개짜리라 약하다.
        boolean selfDerivedReference = referenceCoord == null;
        double[] destinationCoord = selfDerivedReference ? medianOf(found.values()) : referenceCoord;

        // 2차: 여행지 기준에서 너무 먼 행을 추린다. 아직 버리지는 않는다 — 과반이 걸리면
        // 기준 좌표 쪽을 의심해야 하기 때문이다(아래).
        List<String> farKeys = new ArrayList<>();
        for (Map.Entry<String, Place> entry : found.entrySet()) {
            Place place = entry.getValue();
            if (isFarFromDestination(place.getLatitude(), place.getLongitude(), destinationCoord)) {
                farKeys.add(entry.getKey());
            }
        }

        /* 자체 median을 썼는데 과반이 "너무 멀다"면 기준 좌표 쪽이 틀린 것이다 — 필터를 포기한다.
         *
         * 이 필터는 **소수의 오매칭**을 걷어내라고 있는 것이지 일정을 비우라고 있는 게 아니다.
         * 실제로 비는 경로가 있다: medianOf는 위도·경도 median을 독립적으로 계산하므로 장소가
         * 두 지역에 반반 섞이면 중심점이 그 사이 허공에 찍힌다(제주 2곳 + 서울 2곳 → (35.5,
         * 126.75), 양쪽 모두 224km → 임계 150km 초과로 **전부 삭제**). 스텝 0개짜리 일정을
         * 저장하느니 오매칭 몇 건을 남기는 편이 낫다.
         *
         * 호출자가 기준 좌표를 준 경우에는 적용하지 않는다. 그건 후보 수십 건의 median이라
         * "과반이 멀다"가 기준이 틀렸다는 뜻이 아니라 **일정이 실제로 엉뚱하다**는 뜻이다. */
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
     * 해소된 장소들의 median 좌표. 호출자가 기준 좌표를 안 넘겼을 때 쓰는 **자체 기준**이다.
     *
     * <p>없으면 안 되는 이유: 기준 좌표가 null이면 {@link #isFarFromDestination}가 항상 false를
     * 반환해 "제주 일정에 서울 좌표가 찍히는" 오매칭 가드가 통째로 꺼진다. 예전에는 Redis 캐시나
     * Google 행정구역 검색이 기준을 만들어 줬는데 둘 다 사라졌고, Fallback 경로
     * ({@code ItineraryGenerationExecutor})는 median을 넘기지 않는다.
     *
     * <p>표본이 {@value #MEDIAN_MIN_SAMPLE} 미만이면 null을 준다 — 장소 두세 곳의 median은
     * 이상치 하나에 그대로 끌려가서, 기준으로 삼으면 멀쩡한 장소를 버린다.
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

    /** 여행지 기준 좌표에서 너무 먼지 판정. destinationCoord/좌표가 없거나 (0,0) fallback 마커면 false. */
    private boolean isFarFromDestination(BigDecimal lat, BigDecimal lng, double[] destinationCoord) {
        if (destinationCoord == null || lat == null || lng == null) return false;
        if (BigDecimal.ZERO.compareTo(lat) == 0 && BigDecimal.ZERO.compareTo(lng) == 0) return false;
        double distKm = GeoUtils.haversine(destinationCoord[0], destinationCoord[1], lat.doubleValue(), lng.doubleValue());
        return distKm > DESTINATION_DISTANCE_THRESHOLD_KM;
    }

    /**
     * @param precededByDrop 바로 앞 스텝이 장소 미해소로 버려졌으면 true. 이 스텝의 교통 정보는
     *                       <b>사라진 출발지</b>에서 온 구간이라 그대로 두면 거짓말이 된다
     *                       ("차로 32분·20.7km"인데 그 출발지가 일정에 없음). 비워서 UI가
     *                       구간을 그리지 않게 한다.
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
                // 해소 못 한 대안은 **버린다**. place가 null인 AlternativeOption을 저장하면
                // 조회 시 PlaceResponse.from(null)에서 NPE가 난다(null 체크가 없다).
                // 예전엔 좌표 (0,0) fallback Place라도 항상 붙어서 null이 될 수 없었다.
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
