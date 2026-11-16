package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Predicate;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.nameOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.placeKey;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.byIndex;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.toMinutesOrZero;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.*;

/** 요청 의도(timing·highlight)를 후보에 싣고, 출발일 저녁 의도를 옮기고, 최종 배치를 기록한다. */
@Slf4j
final class IntentHints {

    private IntentHints() {
    }

    private static final Set<String> INTENT_TIMES = Set.of("morning", "evening");

    /**
     * Sonnet이 표시한 요청 의도(timing·highlight)를 후보에 싣는다. 스케줄러는 요청 문장을 모르므로 이게 없으면 야경 명소가 낮에 놓인다.
     * <ul>
     *   <li>timing → {@link PlaceCandidate#intentTime()}. {@link DayTiming#timePreference}가 데이터보다 먼저 읽는다</li>
     *   <li>timing·highlight → {@link PlaceCandidate#intentProtected()}. 거리·교체·유형 트림에서 빠지지만
     *       userSelected와 달리 영업시간·휴무·시각·귀가 가드는 그대로 받는다</li>
     * </ul>
     * 본선택 장소만, 식당 제외, 날짜별로 timing → highlight 순서로 {@code INTENT_PROTECT_PER_DAY}곳까지 보호한다.
     * 필수 경험으로 이미 실린 의도({@link RequiredExperiences})는 상한에 세지 않고, 사용자가 말한 시간대가 Sonnet보다 앞선다.
     */
    static List<PlaceCandidate> apply(SelectionOutput selection, List<PlaceCandidate> candidates) {
        if (selection.days() == null) return candidates;
        Map<Integer, String> timing = new HashMap<>();
        if (selection.timing() != null) {
            for (SelectionOutput.TimingHint h : selection.timing()) {
                if (h == null || h.when() == null) continue;
                String when = h.when().trim().toLowerCase(Locale.ROOT);
                if (INTENT_TIMES.contains(when)) timing.putIfAbsent(h.index(), when);
            }
        }
        Set<Integer> highlights = selection.highlightIndices() != null
                ? new HashSet<>(selection.highlightIndices()) : Set.of();
        if (timing.isEmpty() && highlights.isEmpty()) return candidates;

        Map<Integer, String> timeOf = new HashMap<>();
        Set<Integer> protect = new HashSet<>();
        for (SelectionOutput.DayPlan d : selection.days()) {
            if (d.placeIndices() == null) continue;
            List<Integer> ranked = new ArrayList<>();
            d.placeIndices().stream().filter(timing::containsKey).forEach(ranked::add);
            d.placeIndices().stream().filter(i -> highlights.contains(i) && !timing.containsKey(i)).forEach(ranked::add);
            int kept = 0;
            for (Integer idx : ranked) {
                PlaceCandidate c = byIndex(candidates, idx);
                if (c == null || c.mealEligible()) continue;
                if (timing.containsKey(idx)) timeOf.put(idx, timing.get(idx));
                if (c.keepsPlace()) continue; // 이미 보호돼 있어 상한을 쓰지 않는다
                if (kept < INTENT_PROTECT_PER_DAY) {
                    protect.add(idx);
                    kept++;
                }
            }
        }
        if (timeOf.isEmpty() && protect.isEmpty()) return candidates;

        List<PlaceCandidate> out = new ArrayList<>(candidates.size());
        for (PlaceCandidate c : candidates) {
            boolean p = c != null && (protect.contains(c.index()) || c.intentProtected());
            String t = c == null ? null : c.intentTime() != null ? c.intentTime() : timeOf.get(c.index());
            out.add(p || t != null ? c.withIntent(p, t) : c);
        }
        log.info("요청 의도 반영: 시간대 {} / 보호 {}",
                timeOf.entrySet().stream().map(e -> nameOf(candidates, e.getKey()) + "→" + e.getValue()).toList(),
                protect.stream().map(i -> nameOf(candidates, i)).toList());
        return out;
    }

    /** 시간 의도 장소의 최종 배치를 남긴다. 어긋나면 WARN이며 의도 반영률 집계에 쓴다. */
    static void logOutcome(List<PlaceCandidate> candidates, List<StepData> steps) {
        for (PlaceCandidate c : candidates) {
            if (c == null || c.intentTime() == null) continue;
            String key = placeKey(c.name(), c.address());
            StepData placed = steps.stream()
                    .filter(s -> s.place() != null && key.equals(placeKey(s.place().name(), s.place().address())))
                    .findFirst().orElse(null);
            if (placed == null) {
                log.warn("의도 시간대 미반영: {} 요청={} → 일정에서 빠짐", c.name(), c.intentTime());
                continue;
            }
            int start = toMinutesOrZero(placed.startTime());
            boolean ok = "evening".equals(c.intentTime()) ? start >= EVENING_ACTIVITY_EARLIEST_START : start < LUNCH_START;
            if (ok) {
                log.info("의도 시간대 반영: {} 요청={} → day{} {}", c.name(), c.intentTime(),
                        placed.dayNumber(), placed.startTime());
            } else {
                log.warn("의도 시간대 미반영: {} 요청={} → day{} {}", c.name(), c.intentTime(),
                        placed.dayNumber(), placed.startTime());
            }
        }
    }

    /**
     * 출발일(귀가 허브가 있는 날)에는 저녁 식사도 저녁 시간도 없어 그날의 저녁 의도 장소를 전날로 옮긴다.
     * 두면 출발 시각 트림에 잘리거나, 남더라도 야경을 보지 못하고 떠난다.
     */
    static void moveEveningOffDepartureDay(List<DayState> days, List<PlaceCandidate> candidates) {
        for (int i = 1; i < days.size(); i++) {
            DayState day = days.get(i);
            if (day.departureHubIndex == null) continue;
            DayState prev = days.get(i - 1);
            for (Integer idx : new ArrayList<>(day.placeIndices)) {
                PlaceCandidate c = byIndex(candidates, idx);
                if (c == null || c.userSelected() || !"evening".equals(c.intentTime())) continue;
                day.placeIndices.remove(idx);
                prev.placeIndices.add(idx);
                log.info("저녁 의도 장소를 출발일에서 전날로 이동: day={} → day={} {}",
                        day.dayNumber, prev.dayNumber, c.name());
            }
        }
    }

    /**
     * 사용자가 출발 시각을 준 마지막 날에서 사용자가 고른 방문지(끼니·숙소·허브 제외) 하나를 전날로 옮긴다.
     * 출발 거점에서 가장 먼 곳부터 옮기고, 전날이 그곳의 휴무일이거나 관광할 시간이 없는 날이면 옮기지 않는다.
     *
     * <p>전날의 일반 활동 하나와 맞바꾼다. 그대로 얹으면 전날 시간이 넘쳐 저녁이 밀린다.
     *
     * @return 옮긴 장소가 있으면 true
     */
    static boolean moveUserPickOffDeadlineDay(List<DayState> days, List<PlaceCandidate> candidates,
                                              LocalDate startDate) {
        if (days.size() < 2) return false;
        DayState last = days.get(days.size() - 1);
        DayState prev = days.get(days.size() - 2);
        if (last.departureDeadline == null || last.departureHubIndex == null || prev.tooShortForSightseeing()) {
            return false;
        }
        double[] hub = CandidateGeo.coordsOf(byIndex(candidates, last.departureHubIndex));
        Predicate<PlaceCandidate> openBefore = PlanEdits.openOnDayFilter(startDate, prev.dayNumber);
        Integer idx = last.placeIndices.stream()
                .filter(i -> {
                    PlaceCandidate c = byIndex(candidates, i);
                    return isMovableVisit(c) && c.userSelected() && openBefore.test(c);
                })
                .max(Comparator.comparingDouble(i -> kmFrom(hub, byIndex(candidates, i), -1)))
                .orElse(null);
        if (idx == null) return false;
        Integer swap = prev.placeIndices.stream()
                .filter(i -> {
                    PlaceCandidate p = byIndex(candidates, i);
                    return isMovableVisit(p) && !p.keepsPlace() && !PlanEdits.isSameIndex(prev.cafeStopIndex, i)
                            && !prev.coverageCriticalIndices.contains(i);
                })
                .min(Comparator.comparingDouble(i -> kmFrom(hub, byIndex(candidates, i), Double.MAX_VALUE)))
                .orElse(null);
        last.placeIndices.remove(idx);
        prev.placeIndices.add(idx);
        if (swap != null) {
            prev.placeIndices.remove(swap);
            last.placeIndices.add(swap);
        }
        log.info("사용자가 고른 장소를 출발 시각이 있는 마지막 날에서 전날로 이동: day={} → day={} {} (맞바꾼 장소 {})",
                last.dayNumber, prev.dayNumber, nameOf(candidates, idx), swap != null ? nameOf(candidates, swap) : "없음");
        return true;
    }

    private static double kmFrom(double[] from, PlaceCandidate c, double unknown) {
        double[] to = CandidateGeo.coordsOf(c);
        return from == null || to == null ? unknown : GeoUtils.haversine(from, to);
    }

    private static boolean isMovableVisit(PlaceCandidate c) {
        if (c == null || c.mealEligible()) return false;
        String major = PlaceCategoryConstants.majorCategory(c.category());
        return !"LODGING".equals(major) && !"TRANSIT_HUB".equals(major);
    }
}
