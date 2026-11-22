package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.StepData;

import java.util.List;

import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.DINNER_END;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.DINNER_START;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.EARLY_FINISH_MINUTES;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.LUNCH_END;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.LUNCH_START;

/**
 * 하루 배치안의 덜 좋은 정도. 지킬 수 없는 위반은 보지 않고, 같은 날의 배치안끼리 견줄 때만 쓴다. 기준 시각과 간격은
 * 저장 판정({@link ItineraryRules}·{@link TripConditionChecks})과 같다.
 */
final class DayScore {

    /** 장소 하나를 잃는 감점이 이동·대기 몇 시간보다 커야 장소를 지키는 안이 이긴다. */
    static final double PER_LOST_PLACE = 40;
    /** 끼니를 거르는 것은 장소 하나를 잃는 것보다 나쁘다. */
    static final double PER_MISSING_MEAL = 60;
    static final double PER_BACK_TO_BACK_MEAL = 20;
    /** 분당 감점. 이동·대기는 머무는 시간이 아니라 버리는 시간이다. */
    static final double PER_TRAVEL_MINUTE = 0.2;
    static final double PER_WAIT_MINUTE = 0.2;
    static final double PER_GAP_MINUTE = 0.2;
    static final double PER_EARLY_MINUTE = 0.1;

    private DayScore() {}

    /** @param planned 이 날 넣으려던 장소(끼니 포함) */
    static double penalty(DayBuilder.Frame f, List<DayBuilder.Stop> planned, DayBuilder.Built built) {
        List<StepData> steps = built.steps();
        // 빈 시간을 채운 예비 장소가 빠진 장소 수를 덮지 않게 넣으려던 장소 하나하나가 남았는지 본다.
        java.util.Set<Long> kept = new java.util.HashSet<>();
        built.stops().forEach(s -> kept.add(s.place().placeId()));
        double out = planned.stream().filter(s -> !kept.contains(s.place().placeId())).count() * PER_LOST_PLACE;

        int prevEnd = -1;
        int prevMealEnd = -1;
        int lastActivityEnd = -1;
        boolean lunch = false;
        boolean dinner = false;
        for (int i = 0; i < steps.size(); i++) {
            StepData s = steps.get(i);
            int start = ScheduleTimes.toMinutesOrZero(s.startTime());
            int end = ScheduleTimes.toMinutesOrZero(s.endTime());
            int leg = s.transportationDuration() == null ? 0 : s.transportationDuration();
            out += leg * PER_TRAVEL_MINUTE;
            if (prevEnd >= 0) {
                int idle = start - prevEnd - leg;
                out += Math.max(0, idle - TripConditionChecks.LONG_GAP) * PER_GAP_MINUTE;
                out += Math.max(0, Math.min(idle, TripConditionChecks.LONG_GAP)) * PER_WAIT_MINUTE;
            }
            prevEnd = end;
            DayBuilder.Stop stop = stopAt(built, i, steps);
            if (stop == null) continue;
            lastActivityEnd = end;
            if (!stop.meal()) continue;
            if (prevMealEnd >= 0 && start - prevMealEnd < TripConditionChecks.MEAL_GAP) out += PER_BACK_TO_BACK_MEAL;
            prevMealEnd = end;
            lunch |= InvariantChecker.inMealWindow(start, LUNCH_START, LUNCH_END);
            dinner |= InvariantChecker.inMealWindow(start, DINNER_START, DINNER_END);
        }
        int dayStart = steps.isEmpty() ? f.startMin() : ScheduleTimes.toMinutesOrZero(steps.get(0).startTime());
        if (!lunch && dayStart <= LUNCH_START && f.endMin() >= LUNCH_END) out += PER_MISSING_MEAL;
        if (!dinner && dayStart <= DINNER_START && f.endMin() >= DINNER_END) out += PER_MISSING_MEAL;
        if (!f.hardEnd() && lastActivityEnd >= 0 && lastActivityEnd < EARLY_FINISH_MINUTES) {
            out += (EARLY_FINISH_MINUTES - lastActivityEnd) * PER_EARLY_MINUTE;
        }
        return out;
    }

    /** 스텝 가운데 장소 칸(허브·숙소가 아닌 것)에 놓인 정류장. */
    private static DayBuilder.Stop stopAt(DayBuilder.Built built, int stepIndex, List<StepData> steps) {
        Long id = steps.get(stepIndex).place() == null ? null : steps.get(stepIndex).place().placeId();
        if (id == null) return null;
        return built.stops().stream().filter(s -> id.equals(s.place().placeId())).findFirst().orElse(null);
    }
}
