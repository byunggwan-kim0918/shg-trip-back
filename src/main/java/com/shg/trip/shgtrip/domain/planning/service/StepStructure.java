package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 저장 직전 일정의 형태 검사. 짓기·편집 단계에 버그가 있어도 시각이 겹치거나 같은 곳을 두 번 가는 일정이 저장되지 않게 막는다.
 * 숙소와 교통 거점은 같은 날 나갔다 돌아오므로 중복으로 보지 않는다.
 */
final class StepStructure {

    private StepStructure() {
    }

    /**
     * @param candidates 숙소·거점 판정에 쓸 후보. 검사기와 같은 역할 기준을 보려고 장소 역할을 먼저 본다
     * @return 첫 문제 설명, 문제가 없으면 null
     */
    static String problemOf(List<StepData> steps, List<PlaceCandidate> candidates) {
        Map<Long, PlaceCandidate> byId = new HashMap<>();
        for (PlaceCandidate c : candidates) if (c.placeId() != null) byId.putIfAbsent(c.placeId(), c);
        int day = -1;
        Integer previousEnd = null;
        Set<Long> seenToday = new HashSet<>();
        for (StepData s : steps) {
            if (s.place() == null || s.place().name() == null || s.place().name().isBlank()) {
                return s.dayNumber() + "일차 장소 없음";
            }
            Integer start = ScheduleTimes.toMinutesOrNull(s.startTime());
            Integer end = ScheduleTimes.toMinutesOrNull(s.endTime());
            if (start == null || end == null) return s.dayNumber() + "일차 시각 형식 " + s.startTime() + "~" + s.endTime();
            if (end < start) return s.dayNumber() + "일차 " + s.place().name() + " 끝이 시작보다 이름";
            if (s.dayNumber() < day) return s.dayNumber() + "일차가 " + day + "일차 뒤에 옴";
            if (s.dayNumber() != day) {
                day = s.dayNumber();
                previousEnd = null;
                seenToday.clear();
            }
            if (previousEnd != null && start < previousEnd) {
                return day + "일차 " + s.place().name() + " 시각 겹침(" + s.startTime() + ")";
            }
            previousEnd = end;
            Long id = s.place().placeId();
            if (id != null && !returnsSameDay(byId.get(id), s) && !seenToday.add(id)) {
                return day + "일차 " + s.place().name() + " 같은 날 두 번";
            }
        }
        return null;
    }

    private static boolean returnsSameDay(PlaceCandidate c, StepData s) {
        if (c != null) {
            CandidatePolicy.Role role = CandidatePolicy.roleOf(c);
            return role == CandidatePolicy.Role.LODGING || role == CandidatePolicy.Role.TRANSIT;
        }
        String category = s.place().category();
        String major = PlaceCategoryConstants.majorCategory(category);
        return "LODGING".equals(major) || "TRANSIT_HUB".equals(major);
    }
}
