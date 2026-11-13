package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.centroidOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.coordsOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.isSubstituteFor;
import static com.shg.trip.shgtrip.domain.planning.service.DayTiming.isClosedOnDay;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.byIndex;

/** 휴무일 장소와 일정 안 중복 장소를 같은 대분류 spare로 바꾼다. */
@Slf4j
final class SubstitutionRepair {

    private SubstitutionRepair() {
    }

    /**
     * 정기휴무 장소를 그날 열려 있는 같은 카테고리 spare로 교체한다.
     * openingHours가 없거나 그 요일을 파싱하지 못하면 열림으로 보고, 고신뢰 휴무 신호에만 작동한다.
     * swap이라 장소 수가 변하지 않으며 대체가 없으면 로그만 남긴다.
     */
    static void repairClosedDayPlaces(List<DayState> days, List<PlaceCandidate> candidates,
                                      Deque<Integer> spare, java.time.LocalDate startDate,
                                      List<List<Integer>> pairs) {
        if (startDate == null) return;
        Set<Integer> pairedIndices = pairs.stream()
                .filter(p -> p != null && p.size() == 2)
                .flatMap(List::stream)
                .collect(Collectors.toSet());

        for (DayState day : days) {
            java.time.DayOfWeek dow = startDate.plusDays((long) day.dayNumber - 1).getDayOfWeek();
            double[] centroid = centroidOf(day.placeIndices, candidates);

            for (int i = 0; i < day.placeIndices.size(); i++) {
                int idx = day.placeIndices.get(i);
                PlaceCandidate cand = byIndex(candidates, idx);
                if (cand == null || !isClosedOnDay(cand.openingHours(), dow)) continue;
                // 포함 여부는 사용자의 판단이라 휴무여도 빼지 않는다.
                if (cand.userSelected()) {
                    log.warn("정기휴무 장소이나 사용자 필수라 교체 보류: day={}({}) {}",
                            day.dayNumber, dow, cand.name());
                    continue;
                }
                // pair 멤버도 교체한다. 짝 배치가 풀리는 것보다 휴무인 곳에 보내는 게 나쁘다.
                // 자리 치환이라 짝의 다른 쪽은 그날에 그대로 남는다.
                if (pairedIndices.contains(idx)) {
                    log.info("정기휴무 pair 멤버 교체 진행(인접 배치 포기): day={}({}) {}",
                            day.dayNumber, dow, cand.name());
                }

                final double[] anchor = centroid != null ? centroid : coordsOf(cand);
                Integer replacement = spare.stream()
                        .map(si -> byIndex(candidates, si))
                        .filter(Objects::nonNull)
                        .filter(sc -> isSubstituteFor(cand, sc))
                        .filter(sc -> !isClosedOnDay(sc.openingHours(), dow))
                        .min(Comparator.comparingDouble(sc -> anchor != null && coordsOf(sc) != null
                                ? GeoUtils.haversine(anchor, coordsOf(sc)) : Double.MAX_VALUE))
                        .map(PlaceCandidate::index)
                        .orElse(null);

                if (replacement != null) {
                    spare.remove(replacement);
                    day.placeIndices.set(i, replacement);
                    spare.addLast(idx);
                    log.info("정기휴무 회피: day={}({}) {} → 대체 index={}", day.dayNumber, dow, cand.name(), replacement);
                } else {
                    log.warn("정기휴무 장소이나 대체 spare 없음: day={}({}) {}", day.dayNumber, dow, cand.name());
                }
            }
        }
    }

    /**
     * 같은 장소(placeId, 없으면 candidate index)가 메인 스텝에 두 번 나오지 않게 한다.
     * 중복은 같은 대분류 미사용 spare로 바꾸고 없으면 그냥 뺀다. 숙소·허브는 대상이 아니다.
     */
    static void dedupeMainStepsAcrossItinerary(List<DayState> days, List<PlaceCandidate> candidates,
                                               Deque<Integer> spare) {
        Set<Integer> seenIndices = new HashSet<>();
        Set<Long> seenPlaceIds = new HashSet<>();
        for (DayState day : days) {
            List<Integer> deduped = new ArrayList<>();
            for (Integer idx : day.placeIndices) {
                PlaceCandidate c = byIndex(candidates, idx);
                Long pid = c != null ? c.placeId() : null;
                boolean dup = seenIndices.contains(idx) || (pid != null && seenPlaceIds.contains(pid));
                if (dup) {
                    Integer fill = pickDistinctSpare(day, candidates, spare, seenIndices, seenPlaceIds, c);
                    if (fill != null) {
                        deduped.add(fill);
                        seenIndices.add(fill);
                        PlaceCandidate fc = byIndex(candidates, fill);
                        if (fc != null && fc.placeId() != null) seenPlaceIds.add(fc.placeId());
                    } else {
                        log.info("메인 스텝 중복 제거(보충 실패): day={} {}", day.dayNumber,
                                c != null ? c.name() : idx);
                    }
                } else {
                    deduped.add(idx);
                    seenIndices.add(idx);
                    if (pid != null) seenPlaceIds.add(pid);
                }
            }
            day.placeIndices.clear();
            day.placeIndices.addAll(deduped);
        }
    }

    /** 중복 스텝 보충용: 같은 대분류·미사용(index/placeId) spare 중 day 중심에서 가까운 하나. */
    private static Integer pickDistinctSpare(DayState day, List<PlaceCandidate> candidates, Deque<Integer> spare,
                                             Set<Integer> seenIndices, Set<Long> seenPlaceIds, PlaceCandidate like) {
        double[] centroid = centroidOf(day.placeIndices, candidates);
        Integer chosen = spare.stream()
                .filter(si -> !seenIndices.contains(si))
                .map(si -> byIndex(candidates, si))
                .filter(Objects::nonNull)
                .filter(sc -> sc.placeId() == null || !seenPlaceIds.contains(sc.placeId()))
                .filter(sc -> like == null || isSubstituteFor(like, sc))
                .min(Comparator.comparingDouble(sc -> centroid != null && coordsOf(sc) != null
                        ? GeoUtils.haversine(centroid, coordsOf(sc)) : Double.MAX_VALUE))
                .map(PlaceCandidate::index)
                .orElse(null);
        if (chosen != null) spare.remove(chosen);
        return chosen;
    }
}
