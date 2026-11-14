package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.TransportationHub;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.centroidOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.coordsOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.nameOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.namesOverlap;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.isOpenDuring;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.resolveVisitMinutes;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.timePreference;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.collectUsedIndices;
import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.isSameIndex;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.byIndex;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.*;

/** 도착·출발 허브와 숙소 배정(근접 보정, 연박 통일, 숙소 수 상한, 숙소 근처 마무리). */
@Slf4j
final class LodgingPlanner {

    private LodgingPlanner() {
    }

    static void assignHubsAndLodging(List<DayState> days, List<PlaceCandidate> candidates, TransportationHub hub) {
        // 오분류 데이터로 허브가 된 일반 장소를 먼저 강등해야 repairHubs가 진짜 허브로 채운다.
        sanitizeSuspiciousHubs(days, candidates);
        repairHubs(days, candidates);
        // rating만으로는 공항 본체와 부속시설 POI가 동률일 수 있어 enrich가 정한 허브 이름으로 교정한다.
        repairNamedHubs(days, candidates, hub);
        // Sonnet 숙소는 동선과 무관해 매일 장거리 귀가가 생기므로 방문지 중심에 가까운 숙소로 바꾼다.
        // continuity 앞에 둬야 짧은 여행에서 각 day 최적화 뒤 continuity가 같은 지역으로 통일한다.
        repairAccommodationByProximity(days, candidates);
        repairAccommodationContinuity(days, candidates);
        enforceLodgingCountCap(days, candidates);
    }

    /**
     * 숙박일에 숙소가 없으면 방문지 중심에 가장 가까운 LODGING을 넣는다. IndexResultMapper.fillMissingAccommodation의 안전망이다.
     * 숙소는 하루의 종착점이라 먼 숙소가 없는 것보다 나아서 거리·예산 가드를 걸지 않는다.
     */
    static boolean ensureAccommodation(DayState day, List<PlaceCandidate> candidates, boolean lastDay) {
        if (lastDay || day.accommodationIndex != null) return false;

        double[] centroid = centroidOf(day.placeIndices, candidates);
        Set<Integer> visited = new HashSet<>(day.placeIndices);
        PlaceCandidate best = candidates.stream()
                .filter(c -> "LODGING".equals(PlaceCategoryConstants.majorCategory(c.category())))
                .filter(c -> PlaceCategoryConstants.isVisitableEntity(c.name(), c.address(), c.country()))
                .filter(c -> !visited.contains(c.index()))   // 방문지로 쓰인 숙소는 이중 등장 방지
                .min(Comparator.comparingDouble(c -> centroid == null || coordsOf(c) == null
                        ? Double.MAX_VALUE : GeoUtils.haversine(centroid, coordsOf(c))))
                .orElse(null);

        if (best == null) {
            log.warn("숙소 보충 실패(LODGING 후보 없음): day={}", day.dayNumber);
            return false;
        }
        day.accommodationIndex = best.index();
        log.info("숙소 보충: day={} {}", day.dayNumber, best.name());
        return true;
    }

    /** TRANSIT_HUB 후보를 첫날 도착 / 마지막날 출발에 누락 시 보충. */
    private static void repairHubs(List<DayState> days, List<PlaceCandidate> candidates) {
        if (days.isEmpty()) return;
        Set<Integer> used = collectUsedIndices(days);
        DayState first = days.get(0);
        DayState last = days.get(days.size() - 1);

        if (first.arrivalHubIndex == null && !first.resumesAfterTransfer) {
            Integer hub = findUnusedHub(candidates, used);
            if (hub != null) {
                first.arrivalHubIndex = hub;
                used.add(hub);
            }
        }
        if (last.departureHubIndex == null && !last.continuesAfter) {
            Integer hub = findUnusedHub(candidates, used);
            if (hub != null) {
                last.departureHubIndex = hub;
            }
        }
    }

    /**
     * enrich가 지정한 허브 이름으로 도착/출발 허브를 교정한다. rating만으로는 공항 본체와 부속시설 POI가 동률일 수 있다.
     * 이름 일치 > 부속시설 아님 > rating 순으로 고른다.
     */
    private static void repairNamedHubs(List<DayState> days, List<PlaceCandidate> candidates, TransportationHub hub) {
        if (hub == null || days.isEmpty()) return;
        DayState first = days.get(0);
        DayState last = days.get(days.size() - 1);

        Integer arrival = first.resumesAfterTransfer ? null
                : pickNamedHub(candidates, hub.arrivalHub(), first.arrivalHubIndex);
        if (arrival != null) first.arrivalHubIndex = arrival;
        Integer departure = last.continuesAfter ? null
                : pickNamedHub(candidates, hub.departureHub(), last.departureHubIndex);
        if (departure != null) last.departureHubIndex = departure;
    }

    /** 현재 선택보다 hubScore가 확실히 높은 허브 후보 인덱스를 반환. 개선 없으면 null. */
    private static Integer pickNamedHub(List<PlaceCandidate> candidates, String hubName, Integer current) {
        PlaceCandidate best = candidates.stream()
                .filter(c -> "TRANSIT_HUB".equals(PlaceCategoryConstants.majorCategory(c.category())))
                .filter(LodgingPlanner::hasTransitName)
                .max(Comparator.comparingDouble(c -> hubScore(c, hubName)))
                .orElse(null);
        if (best == null) return null;
        if (current != null) {
            if (best.index() == current) return null;
            PlaceCandidate cur = byIndex(candidates, current);
            if (cur != null && hubScore(cur, hubName) >= hubScore(best, hubName)) return null;
            log.info("허브 이름 매칭 교정: {} → {}", cur != null ? cur.name() : current, best.name());
        }
        return best.index();
    }

    /** 해외 허브는 원문 이름("成田国際空港")에 한국어 허브 신호가 없어 한국어 이름도 본다. */
    private static boolean hasTransitName(PlaceCandidate c) {
        return PlaceCategoryConstants.hasTransitNameSignal(c.name())
                || PlaceCategoryConstants.hasTransitNameSignal(c.nameKo());
    }

    private static boolean isHubSubFacility(PlaceCandidate c) {
        return PlaceCategoryConstants.isHubSubFacility(c.name()) || PlaceCategoryConstants.isHubSubFacility(c.nameKo());
    }

    /** 허브 적합도: enrich 허브 이름 일치(+1000, 원문·한국어 이름) > 부속시설 아님(+500) > rating. */
    private static double hubScore(PlaceCandidate c, String hubName) {
        double score = 0;
        if (hubName != null && !hubName.isBlank()
                && (namesOverlap(c.name(), hubName) || namesOverlap(c.nameKo(), hubName))) {
            score += 1000;
        }
        if (!isHubSubFacility(c)) score += 500;
        if (c.rating() != null) score += c.rating().doubleValue();
        return score;
    }

    /**
     * 각 day 숙소를 그날 방문지 centroid에 가장 가까운 LODGING으로 교체한다. Sonnet 숙소는 동선과 무관해 장거리 귀가를 만든다.
     * userSelected 숙소는 교체하지 않고, 유효 좌표가 없으면 원본을 둔다.
     */
    private static void repairAccommodationByProximity(List<DayState> days, List<PlaceCandidate> candidates) {
        // 방문지로 쓰인 LODGING을 빼야 같은 장소가 방문+숙소로 이중 등장하지 않는다.
        // dedupeMainStepsAcrossItinerary는 숙소 인덱스를 보지 않는다.
        Set<Integer> visitedIndices = new HashSet<>();
        for (DayState d : days) visitedIndices.addAll(d.placeIndices);

        List<PlaceCandidate> lodgings = candidates.stream()
                .filter(c -> "LODGING".equals(PlaceCategoryConstants.majorCategory(c.category())))
                .filter(c -> coordsOf(c) != null)
                .filter(c -> !visitedIndices.contains(c.index()))
                .collect(Collectors.toList());
        if (lodgings.isEmpty()) return;

        for (int i = 0; i < days.size(); i++) {
            DayState day = days.get(i);
            if (day.accommodationIndex == null) continue; // 귀가일
            PlaceCandidate current = byIndex(candidates, day.accommodationIndex);
            if (current != null && current.userSelected()) continue;

            double[] centroid = centroidOf(day.placeIndices, candidates);
            if (centroid == null) continue;

            // 그 숙소에서 아침에 출발하는 다음 날. 교체 손익을 그 날까지 함께 재야 한다.
            List<Integer> nextDayPlaces = i + 1 < days.size()
                    ? days.get(i + 1).placeIndices : List.of();

            PlaceCandidate nearest = null;
            double best = Double.MAX_VALUE;
            for (PlaceCandidate lodging : lodgings) {
                double d = GeoUtils.haversine(centroid, coordsOf(lodging));
                if (d < best) {
                    best = d;
                    nearest = lodging;
                }
            }
            if (nearest != null && nearest.index() != day.accommodationIndex) {
                double curDist = current != null && coordsOf(current) != null
                        ? GeoUtils.haversine(centroid, coordsOf(current)) : Double.MAX_VALUE;
                double sameDayGain = curDist - best;

                // 하루씩만 보면 숙소를 당긴 대가를 다음 날 거리 예산에서 꺼내 써 다음 날 관광지가 트림된다.
                // 그래서 다음 날 첫 구간 증가분을 빼고, 그 대용값은 그날 장소 중 숙소에서 가장 가까운 곳까지의 거리로 둔다.
                double nextDayPenalty = nearestPlaceDistance(coordsOf(nearest), nextDayPlaces, candidates)
                        - nearestPlaceDistance(
                                current != null ? coordsOf(current) : null, nextDayPlaces, candidates);
                double netGain = sameDayGain - nextDayPenalty;

                if (netGain >= ACCOMMODATION_SWAP_MIN_GAIN_KM) {
                    log.info("숙소 동선 최적화: day={} {} → {} (당일 {}km 단축, 다음날 {}km 증가, 순이득 {}km)",
                            day.dayNumber,
                            current != null ? current.name() : day.accommodationIndex, nearest.name(),
                            String.format("%.1f", sameDayGain),
                            String.format("%.1f", nextDayPenalty),
                            String.format("%.1f", netGain));
                    day.accommodationIndex = nearest.index();
                } else if (sameDayGain >= ACCOMMODATION_SWAP_MIN_GAIN_KM) {
                    log.info("숙소 교체 보류: day={} 당일은 {}km 짧아지지만 다음날 첫 구간이 {}km 늘어 순이득 {}km",
                            day.dayNumber, String.format("%.1f", sameDayGain),
                            String.format("%.1f", nextDayPenalty), String.format("%.1f", netGain));
                }
            }
        }
    }

    /**
     * 좌표 {@code from}에서 주어진 방문지들 중 <b>가장 가까운 곳</b>까지의 거리(km).
     * 방문지가 없거나 좌표를 모르면 0이라 없는 날에 벌점을 주지 않는다.
     */
    private static double nearestPlaceDistance(double[] from, List<Integer> placeIndices,
                                               List<PlaceCandidate> candidates) {
        if (from == null || placeIndices == null || placeIndices.isEmpty()) return 0.0;
        double best = Double.MAX_VALUE;
        for (Integer idx : placeIndices) {
            double[] cc = coordsOf(byIndex(candidates, idx));
            if (cc == null) continue;
            best = Math.min(best, GeoUtils.haversine(from, cc));
        }
        return best == Double.MAX_VALUE ? 0.0 : best;
    }

    /**
     * 인접 day가 같은 지역이면 숙소를 통일한다.
     *
     * <p>짐을 싸는 비용은 거리로 환산되지 않아 거리 기준 보정은 보지 못한다. 그래서 날짜 수와 무관하게 연박을 기본으로 두고
     * 거리 손해가 {@link ScheduleTuning#CONTINUITY_MAX_EXTRA_KM}을 넘을 때만 바꾼다.
     */
    private static void repairAccommodationContinuity(List<DayState> days, List<PlaceCandidate> candidates) {
        for (int i = 1; i < days.size(); i++) {
            DayState prev = days.get(i - 1);
            DayState curr = days.get(i);
            if (prev.accommodationIndex == null || curr.accommodationIndex == null) continue;
            if (prev.accommodationIndex.equals(curr.accommodationIndex)) continue;

            String prevRegion = dominantRegion(prev.placeIndices, candidates);
            String currRegion = dominantRegion(curr.placeIndices, candidates);
            if (prevRegion == null || !prevRegion.equals(currRegion)) continue;

            // region은 섬·광역 단위라 거칠어 같은 region이어도 동서로 갈린 날을 통일하면 매 구간이 길어진다.
            // 통일했을 때 숙소가 그날 중심에서 지나치게 멀어지면 그대로 둔다.
            double[] currCentroid = centroidOf(curr.placeIndices, candidates);
            double[] prevHotel = coordsOf(byIndex(candidates, prev.accommodationIndex));
            double[] currHotel = coordsOf(byIndex(candidates, curr.accommodationIndex));
            if (currCentroid != null && prevHotel != null && currHotel != null) {
                double unified = GeoUtils.haversine(currCentroid, prevHotel);
                double own = GeoUtils.haversine(currCentroid, currHotel);
                if (unified - own > CONTINUITY_MAX_EXTRA_KM) {
                    log.info("숙소 연박 통일 보류: day={} 통일 시 중심에서 {}km(현재 {}km) — 동선 우선",
                            curr.dayNumber, String.format("%.0f", unified), String.format("%.0f", own));
                    continue;
                }
            }
            curr.accommodationIndex = prev.accommodationIndex;
        }
    }

    /**
     * 숙소 종류 수에 상한을 둔다. {@link #repairAccommodationContinuity}는 인접 두 day만 봐서 합계를 통제하지 못한다.
     *
     * <p>숙박 {@value ScheduleTuning#NIGHTS_PER_LODGING}박마다 1곳(3박 → 2곳, 5박 → 3곳, 1~2박 → 1곳)이다.
     * 초과하면 통일 손해가 가장 작은 날부터 직전 날 숙소로 합쳐 가장 싼 조합이 남는다.
     */
    private static void enforceLodgingCountCap(List<DayState> days, List<PlaceCandidate> candidates) {
        int nights = days.isEmpty() ? 0 : days.size() - (days.get(days.size() - 1).continuesAfter ? 0 : 1);
        if (nights <= 0) return;
        int cap = Math.max(1, (int) Math.ceil((double) nights / NIGHTS_PER_LODGING));

        while (distinctLodgings(days).size() > cap) {
            int bestDay = -1;
            double bestPenalty = Double.MAX_VALUE;
            for (int i = 1; i < days.size(); i++) {
                DayState prev = days.get(i - 1);
                DayState curr = days.get(i);
                if (prev.accommodationIndex == null || curr.accommodationIndex == null) continue;
                if (prev.accommodationIndex.equals(curr.accommodationIndex)) continue;
                PlaceCandidate chosen = byIndex(candidates, curr.accommodationIndex);
                if (chosen != null && chosen.userSelected()) continue; // 사용자가 고른 숙소는 바꾸지 않는다
                double penalty = unifyPenaltyKm(curr, prev, candidates);
                if (penalty < bestPenalty) {
                    bestPenalty = penalty;
                    bestDay = i;
                }
            }
            if (bestDay < 0) break; // 합칠 수 있는 인접 쌍이 없다

            DayState curr = days.get(bestDay);
            PlaceCandidate from = byIndex(candidates, curr.accommodationIndex);
            curr.accommodationIndex = days.get(bestDay - 1).accommodationIndex;
            log.info("숙소 수 상한({}박 → {}곳) 강제: day={} {} → {} (통일 손해 {}km)",
                    nights, cap, curr.dayNumber, from != null ? from.name() : "?",
                    nameOf(candidates, curr.accommodationIndex),
                    bestPenalty == Double.MAX_VALUE ? "?" : String.format("%.0f", bestPenalty));
        }
    }

    /**
     * 출발 허브 체류(분). 차량 여행이면 탑승 수속에 <b>렌터카 반납</b>을 더한다.
     *
     * <p>공항이 마지막 스텝이라 이 값이 사용자가 보는 일정 종료 시각을 정한다. 짧게 잡으면 종료가 실제보다 이르게 보인다.
     */
    static int departureHubMinutes(String transportPref) {
        return "car".equalsIgnoreCase(transportPref)
                ? DEPARTURE_HUB_MINUTES + RENTAL_RETURN_MINUTES
                : DEPARTURE_HUB_MINUTES;
    }

    private static Set<Integer> distinctLodgings(List<DayState> days) {
        Set<Integer> distinct = new HashSet<>();
        for (DayState d : days) {
            if (d.accommodationIndex != null) distinct.add(d.accommodationIndex);
        }
        return distinct;
    }

    /**
     * {@code curr}를 {@code prev}의 숙소로 합쳤을 때 늘어나는 "방문지 중심 ↔ 숙소" 거리(km).
     * 좌표를 모르면 최댓값(가장 나중에 합침).
     */
    private static double unifyPenaltyKm(DayState curr, DayState prev, List<PlaceCandidate> candidates) {
        double[] centroid = centroidOf(curr.placeIndices, candidates);
        double[] prevHotel = coordsOf(byIndex(candidates, prev.accommodationIndex));
        double[] currHotel = coordsOf(byIndex(candidates, curr.accommodationIndex));
        if (centroid == null || prevHotel == null || currHotel == null) return Double.MAX_VALUE;
        return GeoUtils.haversine(centroid, prevHotel) - GeoUtils.haversine(centroid, currHotel);
    }

    /**
     * Sonnet이 지정한 허브 이름에 허브 신호(공항/역/터미널/항구)가 없으면 일반 방문지로 강등한다.
     * 오적재 데이터가 도착 지점으로 쓰이는 것을 막는다. 강등된 장소는 placeIndices로 보존하고 repairHubs가 진짜 허브로 다시 채운다.
     */
    private static void sanitizeSuspiciousHubs(List<DayState> days, List<PlaceCandidate> candidates) {
        for (DayState day : days) {
            day.arrivalHubIndex = demoteIfNotHub(day.arrivalHubIndex, day, candidates);
            day.departureHubIndex = demoteIfNotHub(day.departureHubIndex, day, candidates);
        }
    }

    private static Integer demoteIfNotHub(Integer hubIndex, DayState day, List<PlaceCandidate> candidates) {
        if (hubIndex == null) return null;
        PlaceCandidate c = byIndex(candidates, hubIndex);
        if (c == null) return hubIndex;
        if (hasTransitName(c)) return hubIndex;
        if (!day.placeIndices.contains(hubIndex)) day.placeIndices.add(hubIndex);
        log.info("허브 오분류 강등: day={} {} → 일반 방문지", day.dayNumber, c.name());
        return null;
    }

    /**
     * TRANSIT_HUB 중 허브 본체를 고른다. Foursquare에는 심사대·게이트 같은 부속 POI가 섞여 있고
     * 대개 rating이 없거나 낮아 본체 우선 후 rating 순으로 고르면 입력 순서보다 안정적이다.
     */
    private static Integer findUnusedHub(List<PlaceCandidate> candidates, Set<Integer> used) {
        return candidates.stream()
                .filter(c -> "TRANSIT_HUB".equals(PlaceCategoryConstants.majorCategory(c.category())))
                // 오적재된 비허브가 도착 허브로 쓰이지 않게 이름에 허브 신호가 있는 것만 받는다.
                .filter(LodgingPlanner::hasTransitName)
                .filter(c -> !used.contains(c.index()))
                .max(Comparator
                        .comparingInt((PlaceCandidate c) -> isHubSubFacility(c) ? 0 : 1)
                        .thenComparing(c -> c.rating() != null ? c.rating() : BigDecimal.ZERO))
                .map(PlaceCandidate::index)
                .orElse(null);
    }

    /**
     * 저녁 이후 활동이 숙소에서 "숙소-저녁식당 거리 + 허용치"보다 멀면 spare로 뺀다. 저녁 시간대는 숙소로 수렴하는 방향만 허용한다.
     */
    static void filterEveningAwayFromAccommodation(DayState day, List<PlaceCandidate> candidates,
                                                   List<Integer> evening, Integer dinnerIdx,
                                                   Deque<Integer> spareIndices) {
        if (evening.isEmpty() || day.accommodationIndex == null) return;
        double[] hotel = coordsOf(byIndex(candidates, day.accommodationIndex));
        if (hotel == null) return;

        double baseline = EVENING_AWAY_TOLERANCE_KM;
        if (dinnerIdx != null) {
            double[] d = coordsOf(byIndex(candidates, dinnerIdx));
            if (d != null) baseline = Math.max(baseline, GeoUtils.haversine(d, hotel));
        }
        double allowed = baseline + EVENING_AWAY_TOLERANCE_KM;

        Iterator<Integer> it = evening.iterator();
        while (it.hasNext()) {
            Integer idx = it.next();
            PlaceCandidate c = byIndex(candidates, idx);
            if (c != null && c.keepsPlace()) continue; // 필수·의도 장소는 거리로 빼지 않음
            double[] coord = coordsOf(c);
            if (coord != null && GeoUtils.haversine(coord, hotel) > allowed) {
                log.info("저녁 활동이 숙소에서 과도히 멂({}km > {}km) — spare 전환: day={} {}",
                        String.format("%.1f", GeoUtils.haversine(coord, hotel)), String.format("%.1f", allowed),
                        day.dayNumber, c != null ? c.name() : idx);
                it.remove();
                day.drop(idx, DropReason.FAR_FROM_LODGING);
                spareIndices.addLast(idx);
            }
        }
    }

    /**
     * 저녁 활동이 없어 저녁 식당이 숙소 직전이 될 때, 숙소에 더 가까운 오후 활동 하나를 저녁으로
     * 옮겨 그날의 마지막 스텝이 숙소 근처가 되게 한다(저녁→숙소 이동거리 단축). 조건 미충족 시 무동작.
     */
    static void endDayNearAccommodation(DayState day, List<PlaceCandidate> candidates,
                                        List<Integer> afternoon, List<Integer> evening, Integer dinnerIdx) {
        if (!evening.isEmpty() || dinnerIdx == null || day.accommodationIndex == null || afternoon.isEmpty()) return;
        double[] hotel = coordsOf(byIndex(candidates, day.accommodationIndex));
        double[] dinnerCoord = coordsOf(byIndex(candidates, dinnerIdx));
        if (hotel == null || dinnerCoord == null) return;

        Integer nearest = null;
        double best = GeoUtils.haversine(dinnerCoord, hotel); // 저녁 식당보다 숙소에 더 가까운 오후 활동만 대상
        for (Integer idx : afternoon) {
            // 도보 보정으로 활동 버킷에 섞인 그날 숙소를 저녁으로 옮기면 밤 체크인과 겹쳐 같은 호텔이 두 스텝이 된다.
            if (isSameIndex(day.accommodationIndex, idx)) continue;
            // 야외/주간 장소를 저녁으로 옮기면 버킷 배분의 시간대 가드를 우회한다.
            TimePreference pref = timePreference(byIndex(candidates, idx));
            if (pref == TimePreference.DAYTIME || pref == TimePreference.MORNING_ONLY) continue;
            // 이 이동은 버킷 배분 뒤라 여기서 보지 않으면 영업시간 판정을 우회해 저녁에 닫힌 곳이 스텝이 된다.
            PlaceCandidate moving = byIndex(candidates, idx);
            int eveningStart = DINNER_START + DINING_VISIT_MINUTES;
            if (!isOpenDuring(moving, day.dayOfWeek, eveningStart,
                    eveningStart + resolveVisitMinutes(moving))) continue;
            double[] c = coordsOf(byIndex(candidates, idx));
            if (c == null) continue;
            double d = GeoUtils.haversine(c, hotel);
            if (d < best) { best = d; nearest = idx; }
        }
        if (nearest != null) {
            afternoon.remove(nearest);
            evening.add(nearest);
        }
    }

    private static String dominantRegion(List<Integer> indices, List<PlaceCandidate> candidates) {
        return indices.stream()
                .map(i -> byIndex(candidates, i))
                .filter(c -> c != null && c.region() != null)
                .collect(Collectors.groupingBy(PlaceCandidate::region, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }
}
