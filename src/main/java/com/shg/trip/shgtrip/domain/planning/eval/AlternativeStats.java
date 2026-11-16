package com.shg.trip.shgtrip.domain.planning.eval;

import com.shg.trip.shgtrip.domain.planning.dto.AlternativeData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 재실행 일정의 대안 상태. 연박 숙소는 밤마다 같은 대안을 내고 교통 거점은 대안을 두지 않아 방문 스텝만 센다.
 *
 * @param steps    방문 스텝 수
 * @param total    대안 수
 * @param empty    대안이 없는 방문 스텝 수
 * @param crossDay 둘 이상의 날에 대안으로 나온 장소 수
 */
record AlternativeStats(int steps, int total, int empty, int crossDay) {

    static AlternativeStats of(List<StepData> stepsOfTrip) {
        int steps = 0, total = 0, empty = 0;
        Map<String, Set<Integer>> daysByPlace = new HashMap<>();
        for (StepData s : stepsOfTrip) {
            if (s.place() == null) continue;
            String major = PlaceCategoryConstants.majorCategory(s.place().category());
            if ("LODGING".equals(major) || "TRANSIT_HUB".equals(major)) continue;
            steps++;
            List<AlternativeData> alts = s.alternatives() == null ? List.of() : s.alternatives();
            if (alts.isEmpty()) empty++;
            for (AlternativeData a : alts) {
                total++;
                daysByPlace.computeIfAbsent(key(a), k -> new HashSet<>()).add(s.dayNumber());
            }
        }
        int crossDay = (int) daysByPlace.values().stream().filter(d -> d.size() > 1).count();
        return new AlternativeStats(steps, total, empty, crossDay);
    }

    private static String key(AlternativeData a) {
        return a.placeId() != null ? "id:" + a.placeId() : "name:" + a.name() + "|" + a.address();
    }
}
