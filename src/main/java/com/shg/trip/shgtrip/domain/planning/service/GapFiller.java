package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.*;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.coordsOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.detourVia;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.isCafeLike;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.isVisitableFill;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.buildStep;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.computeLeg;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.isClosedOnDay;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.resolveVisitMinutes;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.timePreference;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.unusedIndices;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.wouldExceedDayBudget;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.byIndex;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.*;

/** 끼니 앞 빈 시간을 근처 후보로 채운다. */
@Slf4j
final class GapFiller {

    private GapFiller() {
    }

    /**
     * 갭 충전에 남은 자리 수 = pace 상한 − 그날 장소 수 − 이미 채운 수. 식사도 장소 수에 들어간다.
     *
     * <p>{@code LARGE_GAP_MINUTES} 이상 빈 날만 상한을 넘겨 하루 1곳까지 채운다({@code fillGapBeforeMeal}).
     */
    static int gapFillQuota(DayState day, int maxPerDay, int alreadyFilled) {
        return maxPerDay - day.placeIndices.size() - alreadyFilled;
    }

    /**
     * 식사 슬롯 시작까지 빈 시간이 {@code GAP_FILL_THRESHOLD_MINUTES} 이상이면 현위치→식당 경로에서 크게 벗어나지 않는
     * 주간 활동(관광지/카페)을 사이에 넣는다. 후보가 없으면 갭을 두고 프론트가 자유시간으로 표시한다.
     */
    static GapFill fillGapBeforeMeal(DayState day, List<PlaceCandidate> candidates,
                                     Deque<Integer> spareIndices, Set<Integer> usedIndices,
                                     PlaceCandidate prev, int currentMinutes, int mealStart,
                                     PlaceCandidate mealCand, double[] dayCentroid, String transportPref,
                                     int remainingQuota, Set<String> dayAltKeys, ScheduleConfig cfg,
                                     int[] overQuotaUsed) {
        List<StepData> inserted = new ArrayList<>();
        int guard = 0;
        while (guard++ < 3) {
            TransportLeg toMeal = computeLeg(prev, mealCand, transportPref, day.dayNumber);
            int arrivalAtMeal = currentMinutes + (toMeal != null ? toMeal.durationMin() : 0);
            if (mealStart - arrivalAtMeal < GAP_FILL_THRESHOLD_MINUTES) break;

            int gap = mealStart - arrivalAtMeal;
            // 식사도 장소 수에 들어가 상한 안에서는 오후가 통째로 빌 수 있어, pace 상한이 찼어도 긴 공백은 1곳까지 넣는다.
            // relaxed는 사용자가 여유를 고른 것이라 쉬어 가는 카페만 넣는다.
            boolean overQuota = inserted.size() >= remainingQuota;
            // 호출 단위로 세면 하루 2곳이 넘어가므로 pace 초과 채움은 점심 앞·저녁 앞을 합쳐 하루 1곳까지다.
            if (overQuota && !(gap >= LARGE_GAP_MINUTES && inserted.isEmpty() && overQuotaUsed[0] == 0)) break;
            boolean cafeOnly = overQuota && "relaxed".equals(cfg != null ? cfg.pace : null);
            Integer fillIdx = pickGapFiller(prev, mealCand, candidates, spareIndices, usedIndices,
                    transportPref, 1.0, null, 0, cfg, cafeOnly, day.dayOfWeek);
            if (fillIdx == null && gap >= LARGE_GAP_MINUTES) {
                // 긴 공백은 spare가 말라도 미사용 후보까지 넓히고 반경도 완화한다. 몇 시간 비우느니 조금 더 달리는 게 낫다.
                // 거리 예산은 지킨다. 반경만 풀면 하루 주행 상한을 깨고 다음 패스에 트림되는 churn이 생긴다.
                fillIdx = pickGapFiller(prev, mealCand, candidates,
                        new ArrayDeque<>(unusedIndices(candidates, usedIndices)), usedIndices,
                        transportPref, LARGE_GAP_RADIUS_MULTIPLIER, day,
                        LARGE_GAP_MAX_EXTRA_DETOUR_KM, cfg, cafeOnly, day.dayOfWeek);
                if (fillIdx != null) {
                    log.info("긴 공백({}분) — 미사용 후보까지 확장해 보충: day={}", gap, day.dayNumber);
                }
            }
            if (fillIdx == null) {
                if (gap >= LARGE_GAP_MINUTES) {
                    log.info("긴 공백({}분) 미충전: day={} (반경·시간대 조건을 만족하는 후보 없음)",
                            gap, day.dayNumber);
                }
                break;
            }
            spareIndices.remove(fillIdx);
            usedIndices.add(fillIdx);
            // day 상태에도 기록해야 유형 상한·교체·재스케줄이 이 장소를 보고 같은 종류를 또 넣지 않는다.
            if (!day.placeIndices.contains(fillIdx)) day.placeIndices.add(fillIdx);
            if (overQuota) overQuotaUsed[0]++;

            PlaceCandidate fill = byIndex(candidates, fillIdx);
            TransportLeg leg = computeLeg(prev, fill, transportPref, day.dayNumber);
            if (leg != null) currentMinutes += leg.durationMin();
            int startMin = currentMinutes;
            int endMin = startMin + resolveVisitMinutes(fill);
            inserted.add(buildStep(day, fill, startMin, endMin, leg,
                    AlternativeBuilder.buildAlternatives(fill, dayCentroid, spareIndices, usedIndices, candidates,
                            transportPref, dayAltKeys)));
            log.info("빈 시간대 보충: day={} {} 삽입 (식사 슬롯까지 {}분 공백)",
                    day.dayNumber, fill.name(), mealStart - arrivalAtMeal);
            currentMinutes = endMin;
            prev = fill;
        }
        return new GapFill(inserted, prev, currentMinutes);
    }

    /**
     * 갭 보충 후보: 주간 적합 관광지/카페 중 인기도에서 우회 비용을 뺀 점수가 가장 높은 spare.
     *
     * @param radiusMultiplier 반경 완화 배수. 공백이 길 때만 1보다 큰 값을 준다.
     * @param budgetDay        거리 예산을 검사할 day. {@code null}이면 검사하지 않는다.
     *                         반경을 완화할 때 넘기지 않으면 하루 주행 상한을 깨고 다음 패스에 트림되는 churn이 생긴다.
     * @param maxExtraDetourKm 경로에서 벗어나도 되는 추가 거리(km). 0 이하면 제한 없음.
     *                         반경만 완화하면 공백 대신 긴 운전이 되어 함께 쓴다.
     * @param cafeOnly         카페만 허용(relaxed 페이스에서 pace 상한을 넘겨 채울 때).
     */
    private static Integer pickGapFiller(PlaceCandidate prev, PlaceCandidate mealCand,
                                         List<PlaceCandidate> candidates, Deque<Integer> spareIndices,
                                         Set<Integer> usedIndices, String transportPref,
                                         double radiusMultiplier, DayState budgetDay,
                                         double maxExtraDetourKm, ScheduleConfig cfg, boolean cafeOnly,
                                         java.time.DayOfWeek dow) {
        double[] from = prev != null ? coordsOf(prev) : null;
        double[] to = coordsOf(mealCand);
        double radius = AlternativeBuilder.altRadiusKm(transportPref) * radiusMultiplier;
        // 우회 상한의 기준선은 현위치에서 식당까지의 직선거리다.
        double directKm = (from != null && to != null) ? GeoUtils.haversine(from, to) : 0.0;
        return spareIndices.stream()
                .filter(i -> !usedIndices.contains(i))
                .map(i -> byIndex(candidates, i))
                .filter(Objects::nonNull)
                .filter(c -> {
                    // 카페형에는 식당으로 적재된 베이커리·디저트도 든다(isCafeLike).
                    if (cafeOnly) return isCafeLike(c);
                    return "ATTRACTION".equals(PlaceCategoryConstants.majorCategory(c.category())) || isCafeLike(c);
                })
                // 역할·구역·경유·집합POI·여객시설·긴 등산을 다른 보충 경로와 같은 공용 게이트로 본다.
                .filter(CandidateTraits::isVisitableFill)
                .filter(c -> dow == null || !isClosedOnDay(c.openingHours(), dow))
                .filter(c -> timePreference(c) != TimePreference.EVENING)
                .filter(c -> coordsOf(c) != null)
                .filter(c -> from == null || GeoUtils.haversine(from, coordsOf(c)) <= radius)
                .filter(c -> budgetDay == null
                        || !wouldExceedDayBudget(budgetDay, c, candidates, transportPref, cfg))
                .filter(c -> maxExtraDetourKm <= 0
                        || detourVia(from, coordsOf(c), to) - directKm <= maxExtraDetourKm)
                // 우회거리만 보면 가장 가까운 무명 spare가 이긴다. 반경·예산·우회 상한이 이미 지리를 제한해 그 안에서는 인기도가 정한다.
                .max(Comparator.<PlaceCandidate>comparingDouble(c ->
                                PlaceProminence.of(c) - detourVia(from, coordsOf(c), to) * GAP_FILL_DETOUR_WEIGHT)
                        .thenComparing(c -> c.rating() != null ? c.rating() : BigDecimal.ZERO))
                .map(PlaceCandidate::index)
                .orElse(null);
    }
}
