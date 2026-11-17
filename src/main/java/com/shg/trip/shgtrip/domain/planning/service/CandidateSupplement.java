package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.destination.KrwRates;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.ScheduleArgs;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 선택 단계 뒤에 후보를 덧붙인다(날짜별 예비 후보, 근처 숙소, v2 기준 장소 둘레 보완). 덧붙인 후보는 검색 결과라 DB 값으로
 * 채우고, 생성 요청의 제외 조건을 검색 풀과 똑같이 거친다. 생성 실행기와 재실행 평가가 같은 경로를 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandidateSupplement {

    private final VectorSearchQueryService vectorSearchQueryService;
    private final PlaceRepository placeRepository;

    /** v2 확정 결과와 확정에 쓴 후보(기준 장소 둘레에서 찾은 후보를 뒤에 덧붙인 것). */
    public record Planned(SerialPlanner.Result result, List<PlaceCandidate> candidates) {}

    /**
     * 날짜를 한 번 정하고, 날마다의 기준 좌표 둘레에서 후보를 보완한 뒤 다시 정한다. 선택 단계의 보완 후보는 선택한 날짜
     * 둘레에서만 찾아, v2가 다시 짠 날의 기준 장소 근처에는 식당이 모자란다.
     * 보완할 후보가 없어도 다시 확정해 요약 로그를 마지막 확정에서 한 번만 남긴다.
     *
     * @param selector 마지막 확정에서만 쓴다. 1차 확정은 기준 좌표만 쓰여 날마다 고르는 호출을 하지 않는다
     */
    public Planned planWithReserve(SerialPlanner planner, SelectionOutput selection, List<PlaceCandidate> candidates,
                                   ScheduleArgs args, VectorEnrichedInput input, PoolExclusions exclusions,
                                   int dayCount, SerialPlanner.Picks picks, DaySelector selector) {
        SerialPlanner.Result first = planner.planWithoutSummary(selection, candidates, args, dayCount, picks);
        List<PlaceCandidate> withReserve = appendReserveAroundAnchors(candidates, first.anchors(), input, exclusions);
        return new Planned(planner.plan(selection, withReserve, args, dayCount, picks, selector), withReserve);
    }

    /** 커플·가족 여행이면 다인실 숙소(게스트하우스·호스텔)를 후보에서 뺀다. 사용자가 직접 고른 숙소는 남긴다. */
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

    public record PoolExclusions(List<String> ferryTokens, AvoidedPlaces avoided, PlaceRestrictions restrictions,
                                 Set<Long> displacedIds) {

        /** 요청에 제외 조건이 없을 때. 재실행 평가는 기록에 요청 조건이 없어 이것을 쓴다. */
        public static PoolExclusions none() {
            return new PoolExclusions(List.of(), AvoidedPlaces.NONE, PlaceRestrictions.NONE, Set.of());
        }

        PoolExclusions withoutRestrictions() {
            return new PoolExclusions(ferryTokens, avoided, PlaceRestrictions.NONE, displacedIds);
        }

        List<PlaceCandidate> excludeDisplaced(List<PlaceCandidate> candidates) {
            if (displacedIds.isEmpty()) return candidates;
            return candidates.stream().filter(c -> c.placeId() == null || !displacedIds.contains(c.placeId())).toList();
        }
    }

    /** 날짜별 예비 후보는 검색 결과라 DB 체류 시간을 채운 뒤에야 등산·트레킹을 판정할 수 있다. */
    static List<PlaceCandidate> excludeRestrictedAppended(List<PlaceCandidate> candidates, int baseSize,
                                                          PlaceRestrictions restrictions) {
        if (restrictions.isEmpty() || candidates.size() <= baseSize) return candidates;
        List<PlaceCandidate> result = new ArrayList<>(candidates.subList(0, baseSize));
        for (PlaceCandidate c : restrictions.exclude(candidates.subList(baseSize, candidates.size()))) {
            result.add(c.reindexed(result.size() + 1));
        }
        return result;
    }

    /** 관문을 뺀 모든 후보 추가 경로(검색 풀·숙소 보완·날짜별 예비·근처 숙소)가 거친다. 한 경로만 막으면 다른 경로로 다시 들어온다. */
    List<PlaceCandidate> excludeUnwanted(List<PlaceCandidate> candidates, PoolExclusions exclusions,
                                                 VectorEnrichedInput input) {
        return excludeDormLodging(exclusions.restrictions().exclude(exclusions.avoided().exclude(
                FerryAreaFilter.exclude(exclusions.excludeDisplaced(candidates), exclusions.ferryTokens()))), input);
    }

    /** 이동수단별 예비 후보 검색 반경(km). 도보는 걸어서 들를 만한 거리만. */
    private static double reserveRadiusKm(String transportPref) {
        if ("walk".equals(transportPref)) return 3.0;
        if ("car".equals(transportPref)) return 15.0;
        return 10.0;
    }

    /**
     * 날마다 새로 덧붙일 예비 후보 수. 대안은 같은 역할끼리만 내서, 끼니·관광 스텝마다 다른 날과 겹치지 않는 대안 3개를 주려면
     * 역할마다 하루 스텝 수의 몇 배가 남아야 한다.
     */
    static final List<Map.Entry<PlaceCategoryConstants.SearchRole, Integer>> RESERVE_PER_DAY = List.of(
            Map.entry(PlaceCategoryConstants.SearchRole.ATTRACTION, 8),
            Map.entry(PlaceCategoryConstants.SearchRole.CAFE, 3),
            Map.entry(PlaceCategoryConstants.SearchRole.RESTAURANT, 8));
    /** 근처 검색 상위는 대부분 이미 후보에 있는 곳이라 이만큼 더 받아 새 곳만 쓴다. */
    static final int RESERVE_OVERFETCH = 3;

    /**
     * 날짜별 방문지 중심 근처의 예비 후보를 덧붙인다. SelectionOutput에는 넣지 않아 RouteOptimizer의 미사용 후보
     * 경로(빈 시간 채우기·끼니 보충·교체)만 쓴다.
     */
    List<PlaceCandidate> appendNearbyReserve(List<PlaceCandidate> candidates, SelectionOutput selection,
                                                     VectorEnrichedInput input, PoolExclusions exclusions) {
        if (selection == null || selection.days() == null || selection.days().isEmpty()) return candidates;
        List<double[]> centers = selection.days().stream().map(d -> activityCentroid(d, candidates)).toList();
        return appendReserveNear(candidates, centers, reserveRadiusKm(input.transportPref()), input, exclusions);
    }

    private List<PlaceCandidate> appendReserveNear(List<PlaceCandidate> candidates, List<double[]> centers,
                                                   double radius, VectorEnrichedInput input,
                                                   PoolExclusions exclusions) {
        List<PlaceCandidate> result = new ArrayList<>(candidates);
        Set<String> known = CandidateKeys.knownKeys(candidates);
        for (Map.Entry<PlaceCategoryConstants.SearchRole, Integer> role : RESERVE_PER_DAY) {
            for (List<PlaceCandidate> found : vectorSearchQueryService.searchNearEach(
                    input, role.getKey(), centers, radius, role.getValue() * RESERVE_OVERFETCH)) {
                int added = 0;
                for (PlaceCandidate c : excludeUnwanted(found, exclusions, input)) {
                    if (added == role.getValue()) break;
                    if (!CandidateKeys.addIfNew(known, result, c)) continue;
                    result.add(c.reindexed(result.size() + 1));
                    added++;
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
    List<PlaceCandidate> appendNearbyAccommodations(List<PlaceCandidate> candidates,
                                                            SelectionOutput selection,
                                                            VectorEnrichedInput input,
                                                            PoolExclusions exclusions) {
        if (selection == null || selection.days() == null || selection.days().isEmpty()) return candidates;
        List<DayCenter> centers = selection.days().stream()
                .map(day -> new DayCenter(day.dayNumber(), dayCentroid(day, candidates))).toList();
        return appendAccommodationsNear(candidates, centers, input, exclusions);
    }

    /** @param center 그날 중심. null이면 그날은 보완하지 않는다 */
    private record DayCenter(int dayNumber, double[] center) {
    }

    private List<PlaceCandidate> appendAccommodationsNear(List<PlaceCandidate> candidates, List<DayCenter> centers,
                                                          VectorEnrichedInput input, PoolExclusions exclusions) {
        List<PlaceCandidate> lodgings = candidates.stream()
                .filter(c -> "LODGING".equals(PlaceCategoryConstants.majorCategory(c.category())))
                .filter(CandidateKeys::hasValidCoords)
                .toList();

        List<PlaceCandidate> result = new ArrayList<>(candidates);
        Set<String> knownPlaceIds = CandidateKeys.knownKeys(candidates);

        for (DayCenter day : centers) {
            double[] centroid = day.center();
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
            for (PlaceCandidate c : excludeUnwanted(extra, exclusions, input)) {
                if (!CandidateKeys.addIfNew(knownPlaceIds, result, c)) continue; // 이미 있는 숙소
                result.add(c.reindexed(result.size() + 1));
            }
        }
        if (result.size() > candidates.size()) {
            log.info("숙소 후보 보완: {}개 → {}개", candidates.size(), result.size());
        }
        return result;
    }

    private static final Set<String> NON_ACTIVITY_MAJORS = Set.of("DINING", "CAFE", "LODGING", "TRANSIT_HUB");

    /**
     * 그날 방문지(식당·카페·숙소·교통 허브 제외)의 중심. 모델이 먼 도시 식당을 같은 날에 넣으면 전체 중심이 명소에서 벗어나
     * 바나힐 같은 근교 명소 근처 식당이 예비 후보 반경에 들지 않는다. 방문지가 없으면 전체 중심이다.
     */
    static double[] activityCentroid(SelectionOutput.DayPlan day, List<PlaceCandidate> candidates) {
        List<Integer> activities = (day.placeIndices() != null ? day.placeIndices() : List.<Integer>of()).stream()
                .filter(idx -> idx != null && idx >= 1 && idx <= candidates.size())
                .filter(idx -> !NON_ACTIVITY_MAJORS.contains(
                        PlaceCategoryConstants.majorCategory(candidates.get(idx - 1).category())))
                .toList();
        if (activities.isEmpty()) return dayCentroid(day, candidates);
        return dayCentroid(new SelectionOutput.DayPlan(day.dayNumber(), null, activities, null, null), candidates);
    }

    /** 유효 좌표가 없으면 null. */
    private static double[] dayCentroid(SelectionOutput.DayPlan day, List<PlaceCandidate> candidates) {
        List<Double> lats = new ArrayList<>();
        List<Double> lngs = new ArrayList<>();
        for (Integer idx : day.placeIndices() != null ? day.placeIndices() : List.<Integer>of()) {
            if (idx == null || idx < 1 || idx > candidates.size()) continue;
            PlaceCandidate c = candidates.get(idx - 1);
            if (!CandidateKeys.hasValidCoords(c)) continue;
            lats.add(c.latitude().doubleValue());
            lngs.add(c.longitude().doubleValue());
        }
        if (lats.isEmpty()) return null;
        return new double[]{
                lats.stream().mapToDouble(Double::doubleValue).average().orElse(0),
                lngs.stream().mapToDouble(Double::doubleValue).average().orElse(0)};
    }

    /** 덧붙인 후보는 검색 결과라 DB 값으로 채운다. 앞쪽 후보는 필수 경험 표시가 붙어 있어 다시 채우면 표시가 지워진다. */
    List<PlaceCandidate> appendReserveAroundAnchors(List<PlaceCandidate> candidates,
                                                            Map<Integer, double[]> anchors, VectorEnrichedInput input,
                                                            PoolExclusions exclusions) {
        if (anchors.isEmpty()) return candidates;
        int baseSize = candidates.size();
        List<DayCenter> centers = anchors.entrySet().stream().map(e -> new DayCenter(e.getKey(), e.getValue())).toList();
        List<PlaceCandidate> appended = appendReserveNear(
                appendAccommodationsNear(candidates, centers, input, exclusions),
                List.copyOf(anchors.values()), DayPicker.nearRingKm(input.transportPref(), input.pace()), input,
                exclusions.withoutRestrictions());
        if (appended.size() == baseSize) return candidates;
        List<PlaceCandidate> enriched = new ArrayList<>(appended.subList(0, baseSize));
        enriched.addAll(enrichFromDb(appended.subList(baseSize, appended.size())));
        return dropAppendedSameWikidataItem(excludeRestrictedAppended(enriched, baseSize, exclusions.restrictions()),
                baseSize);
    }

    List<PlaceCandidate> enrichFromDb(List<PlaceCandidate> candidates) {
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
                    subRegionOrKeep(place.getAddress(), c.region(), c.subRegion()),
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

    private static String subRegionOrKeep(String address, String region, String current) {
        if (KoreanAddressRegion.contradicts(address, region)) return null;
        String extracted = PlaceCategoryConstants.subRegionOf(address, region);
        return extracted != null ? extracted : current;
    }

    /**
     * 덧붙인 후보 중 앞선 후보와 같은 위키데이터 항목이거나, 다른 후보 건물 안 매장이거나, 앞선 후보와 명소 중복 행인 것을 뺀다.
     * 후보에는 Wikidata 항목 id가 없어, 이름이 다른 같은 항목 행은 DB에서 항목 id를 읽어야 걸러진다.
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
            if (CandidateKeys.isStoreInsideVenue(c, candidates)) continue;
            if (result.stream().anyMatch(r -> PlaceIdentity.isWikidataTwin(c, r) || PlaceIdentity.isWikidataTwin(r, c)
                    || PlaceIdentity.isNestedWikidataItem(c, r) || PlaceIdentity.isNestedWikidataItem(r, c))) continue;
            result.add(c.reindexed(result.size() + 1));
        }
        if (result.size() < candidates.size()) {
            log.info("덧붙인 후보 중 같은 장소 {}개 제외", candidates.size() - result.size());
        }
        return result;
    }
}
