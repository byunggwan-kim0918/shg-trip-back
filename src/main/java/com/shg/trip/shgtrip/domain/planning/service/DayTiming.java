package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.AlternativeData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

import static com.shg.trip.shgtrip.domain.planning.service.CandidateGeo.coordsOf;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.isHikeOfAtLeast;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.isLongHike;
import static com.shg.trip.shgtrip.domain.planning.service.CandidateTraits.subTypeOf;
import static com.shg.trip.shgtrip.domain.planning.service.CostEstimator.estimateCost;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTimes.formatMinutes;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.*;

/** 하루 안의 시각 계산과 스텝 조립(이동 구간, 체류 시간, 시간대 선호, 시간대 예산, 영업시간). */
@Slf4j
final class DayTiming {

    private DayTiming() {
    }

    /**
     * 그 요일이 휴무인지. 고신뢰 신호("휴무"/"closed")에만 true이고 데이터 없음·요일 미매칭·판독 실패는 false(열림 가정).
     *
     * <p>여는/닫는 시각 판정과 같은 파서({@link OpeningHoursText})를 써야 "휴무는 아닌데 그 시각엔 닫혀 있다"를 일관되게 판단한다.
     */
    static boolean isClosedOnDay(String openingHours, java.time.DayOfWeek dow) {
        return OpeningHoursText.isClosedOnDay(openingHours, dow);
    }

    /**
     * {@code [startMin, endMin]} 동안 영업하는지. 요일을 모르거나 판독할 수 없으면 true(열림 가정).
     *
     * <p>{@link #isClosedOnDay}가 요일 단위라면 이쪽은 <b>시각 단위</b>다. 늦게 여는 식당이 점심에 배정되는 것은 요일 판정으로 걸리지 않는다.
     */
    static boolean isOpenDuring(PlaceCandidate c, java.time.DayOfWeek dow, int startMin, int endMin) {
        if (c == null || dow == null) return true;
        return OpeningHoursText.isOpenDuring(c.openingHours(), dow, startMin, endMin);
    }

    /**
     * 하루 확정과 검사가 보는 영업시간. 백화점·쇼핑몰·아울렛은 영업시간이 거의 비어 있어 이 업종의 흔한 시간으로 본다.
     */
    static String openingHoursOf(PlaceCandidate c) {
        return openingHoursOf(c.name(), c.category(), c.openingHours());
    }

    /** 짓기·검사·대안 미리보기·대화가 같은 영업시간을 봐야 한 곳만 영업 밖으로 판정하지 않는다. */
    static String openingHoursOf(String name, String category, String hours) {
        if (isOpenBeach(name, category)) return null;
        if (hours != null && !hours.isBlank()) return hours;
        String lower = category == null ? "" : category.toLowerCase(java.util.Locale.ROOT);
        return RETAIL_COMPLEX.stream().anyMatch(lower::contains) ? ScheduleTuning.RETAIL_COMPLEX_HOURS : hours;
    }

    private static final List<String> RETAIL_COMPLEX = List.of("department store", "shopping mall", "outlet");

    /** 해수욕장의 공식 이용시간은 입수 개장 시간이라 해변에 드나드는 시간이 아니다. */
    private static boolean isOpenBeach(String placeName, String placeCategory) {
        String name = java.util.Objects.toString(placeName, "");
        String category = placeCategory == null ? "" : placeCategory.toLowerCase(java.util.Locale.ROOT);
        return category.contains("beach") || name.endsWith("해수욕장") || name.endsWith("해변");
    }

    static StepData buildStep(DayState day, PlaceCandidate cand, int startMin, int endMin,
                              TransportLeg leg, List<AlternativeData> alternatives) {
        return new StepData(
                0, // stepOrder는 호출부에서 전체 재번호
                day.dayNumber,
                formatMinutes(startMin),
                formatMinutes(endMin),
                toPlaceData(cand),
                alternatives,
                leg != null ? leg.mode() : null,
                leg != null ? leg.durationMin() : null,
                leg != null ? leg.distanceKm() : null,
                leg != null ? leg.cost() : null,
                null, // notes(story)는 비동기 Haiku 단계에서 채움
                estimateCost(cand)
        );
    }

    /**
     * 관광 스텝 체류시간(분). 권장 체류시간이 있으면 30~180(긴 등산은 LONG_HIKE_MAX_MINUTES)으로 클램프하고,
     * 없으면 {@link PlaceCategoryConstants#heuristicVisitMinutes}를 쓴다. 일괄 90분이면 등산형 오름과 해변 산책이 구분되지 않는다.
     */
    static int resolveVisitMinutes(PlaceCandidate cand) {
        if (cand == null) return DEFAULT_VISIT_MINUTES;
        Integer recommended = cand.recommendedDurationMinutes();
        if (recommended != null && recommended > 0) {
            int max = isLongHike(cand) ? LONG_HIKE_MAX_MINUTES : 180;
            return Math.max(30, Math.min(max, recommended));
        }
        return PlaceCategoryConstants.heuristicVisitMinutes(cand.name(), cand.category());
    }

    /**
     * 장소의 적합 시간대. 요청 의도 > recommended_time_slots > 카테고리 휴리스틱(야외 관광지=주간, Bar=저녁) 순으로 판정한다.
     */
    static TimePreference timePreference(PlaceCandidate c) {
        if (c == null) return TimePreference.FLEXIBLE;
        // "야경" 요청의 전망대는 낮 명소가 아니라 요청 의도가 데이터·지형 판정보다 우선한다.
        if ("evening".equals(c.intentTime())) return TimePreference.EVENING;
        if ("morning".equals(c.intentTime())) return TimePreference.MORNING_ONLY;
        if (isHikeOfAtLeast(c, ScheduleTuning.MORNING_ONLY_HIKE_MIN_MINUTES)) return TimePreference.MORNING_ONLY;
        List<String> slots = c.recommendedTimeSlots();
        if (slots != null && !slots.isEmpty()) {
            boolean evening = slots.stream().anyMatch(s ->
                    containsAny(s, "저녁", "밤", "야간", "심야", "evening", "night"));
            boolean daytime = slots.stream().anyMatch(s ->
                    containsAny(s, "아침", "오전", "오후", "낮", "주간", "morning", "afternoon", "daytime"));
            // 데이터가 저녁 전용/주간 전용이라고 명시할 때만 따른다. 그래야 야간 조명 명소가 지형 판정에 눌려 낮으로 가지 않는다.
            if (evening && !daytime) return TimePreference.EVENING;
            if (daytime && !evening) return TimePreference.DAYTIME;
            // 섞여 있으면 지형 판정으로 넘긴다. enrich가 시간대를 느슨하게 나열해 FLEXIBLE로 두면 해변이 저녁 버킷에 들어간다.
        }
        if (PlaceCategoryConstants.isBar(c.category())) return TimePreference.EVENING;
        if (PlaceCategoryConstants.isDaytimeOutdoor(c.category())) return TimePreference.DAYTIME;
        // 총칭 리프 카테고리로는 못 거르는 야외 장소(해변·오름·공원)를 세부 유형으로 판정한다.
        if (PlaceCategoryConstants.isDaytimeSubType(subTypeOf(c))) return TimePreference.DAYTIME;
        // 시간대 데이터가 없는 야외·유적 명소를 FLEXIBLE로 두면 저녁 식사 뒤 사적지 방문이 생겨 낮으로 본다.
        // 실내 문화시설은 저녁에도 가므로 그대로 두고, 야경 명소는 위의 보강 데이터로 판정된다.
        if ((slots == null || slots.isEmpty()) && c.category() != null
                && c.category().toLowerCase().contains("landmarks and outdoors")) {
            return TimePreference.DAYTIME;
        }
        return TimePreference.FLEXIBLE;
    }

    private static boolean containsAny(String s, String... keywords) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        for (String k : keywords) {
            if (lower.contains(k)) return true;
        }
        return false;
    }

    private static PlaceData toPlaceData(PlaceCandidate c) {
        // subRegion/description은 저장에 쓰이지 않고 story 생성(Haiku) 컨텍스트로만 전달된다.
        return new PlaceData(c.name(), c.address(), c.category(), c.region(), c.country(),
                c.subRegion(), c.description(), c.placeId(), c.nameKo());
    }
}
