package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules.Violation;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 판정 결과를 일정 화면 안내 문구로 바꾼다. HARD는 경고로 보이고, SOFT는 사용자가 대안으로 고칠 수 있는 것만 남긴다.
 * 화면은 "N일차 본문. 원인." 형식을 날짜별로 나눠 보여 주므로 문장 앞에 일차를 둔다.
 */
public final class QualityNotices {

    private QualityNotices() {}

    public static List<String> of(ItineraryRules.Verdict verdict, ItineraryRules.Context ctx) {
        if (verdict == null) return List.of();
        Set<String> out = new LinkedHashSet<>();
        verdict.violations().stream()
                // 여행 전체 위반(day 0)은 날짜별 안내 뒤에 둔다
                .sorted(Comparator.comparingInt((Violation v) -> v.day() == 0 ? Integer.MAX_VALUE : v.day())
                        .thenComparing(Violation::rule))
                .map(v -> text(v, ctx))
                .filter(java.util.Objects::nonNull)
                .forEach(out::add);
        return List.copyOf(out);
    }

    // default 없이 모든 규칙을 나열해 새 규칙이 문구 없이 추가되면 컴파일이 깨진다.
    private static String text(Violation v, ItineraryRules.Context ctx) {
        int d = v.day();
        return switch (v.rule()) {
            case DAY_MISSING -> d + "일차 일정이 비어 있어요.";
            case CLOSED_DAY -> d + "일차 " + quoted(v, 0) + "은(는) 휴무일이에요. 다른 곳으로 바꿔 주세요.";
            case DEPARTURE_MISSED -> d + "일차 출발 시각에 맞추기 어려워요. 출발 시간을 다시 확인해 주세요.";
            case LUNCH_MISSING -> d + "일차에 점심 식사가 없어요.";
            case DINNER_MISSING -> d + "일차에 저녁 식사가 없어요.";
            // 숙소·출발 허브는 편집으로 자리와 시각이 바뀌지 않아 할 일을 붙이지 않는다.
            case NOT_ENDING_AT_LODGING -> d + "일차 일정이 숙소에서 끝나지 않아요.";
            case DEPARTURE_LATE -> d + "일차 일정이 늦게 끝나 돌아가는 길이 빠듯해요.";
            case OUTSIDE_HOURS -> d + "일차 " + quoted(v, 0) + " 방문 시간이 영업시간을 벗어나요. 순서를 바꾸거나 대안으로 바꿔 주세요.";
            case SAME_DAY_MEAL_KIND -> d + "일차 점심과 저녁이 같은 종류예요(" + String.join("·", v.evidence())
                    + "). 한 곳을 대안으로 바꿔 보세요.";
            case MEAL_KIND_REPEATED -> "같은 종류 식당이 " + v.evidence().size() + "번 나와요(" + quoted(v, 0)
                    + " 등). 대안으로 바꿔 보세요.";
            case CAFE_PER_DAY -> d + "일차에 카페·디저트가 " + v.evidence().size() + "곳이에요. 한 곳을 대안으로 바꿔 보세요.";
            case NO_NON_FOOD_ACTIVITY -> d + "일차에 식사·카페 말고 둘러볼 곳이 없어요.";
            case ADDRESS_REGION_MISMATCH -> d + "일차 " + quoted(v, 0) + "의 주소가 여행지와 달라요. 대안으로 바꾸거나 빼 주세요.";
            case WANT_MISSING -> "꼭 가고 싶다고 한 " + quoted(v, 0) + "을(를) 일정에 넣지 못했어요.";
            case WANT_TIME -> d + "일차 " + quoted(v, 0) + "을(를) 원한 시간대에 가지 않아요. 순서를 바꿔 주세요.";
            case CITY_DAYS -> quoted(v, 0) + "에서 " + (v.evidence().size() > 1 ? v.evidence().get(1) : "")
                    + "일을 보내기로 했는데 " + (v.evidence().size() > 2 ? v.evidence().get(2) : "") + "일이에요.";
            case AVOID -> d + "일차 " + quoted(v, 0) + "은(는) 피하고 싶다고 한 조건에 걸려요. 대안으로 바꿔 주세요.";
            case UNREACHABLE -> d + "일차 " + quoted(v, 0) + "은(는) 일반 관광객이 들어갈 수 없는 곳이에요. 대안으로 바꿔 주세요.";
            case TRAVEL_MISSING -> v.evidence().size() > 2 && !v.evidence().get(2).isEmpty()
                    ? d + "일차 " + quoted(v, 0) + "에서 " + quoted(v, 1) + "까지 약 " + v.evidence().get(2)
                    + "km를 이동 시간 없이 건너뛰어요. 멀리 떨어진 장소를 빼거나 다시 만들어 주세요."
                    : d + "일차 " + quoted(v, 0) + "에서 " + quoted(v, 1) + "까지 이동 시간을 알 수 없어요. "
                    + "한 곳을 대안으로 바꿔 주세요.";
            case EARLY_FINISH -> isLastDayWithoutDeparture(d, ctx)
                    ? d + "일차 일정이 " + first(v) + "에 끝나요. 다음에 만들 때 출발 시간을 알려 주면 마지막 날을 더 채울 수 있어요."
                    : null;
            // 거리·시각 배치는 사용자가 대안 하나를 바꿔서는 고칠 수 없다.
            case LEG_OVER_CAP, DAY_DISTANCE_OVER_CAP, MEALS_BACK_TO_BACK, PACE_SHORT, LONG_GAP, LONG_LEG -> null;
            // 근거가 이름·분류 추정이라 경고로 띄우면 잘못 본 것까지 사용자에게 보인다.
            case CONSECUTIVE_MEAL_KIND, SAME_DAY_MEAL_GROUP, MEAL_GROUP_REPEATED, STRENUOUS, DUPLICATE -> null;
            // 생성 때 이유를 붙인 고정 안내(TripPlanner.tripNotes)로 남는다.
            case DAILY_TRANSIT, WANT_FOOD_MISSING, WANT_NOT_IN_DATA -> null;
        };
    }

    /**
     * @param cause  저장하지 않은 이유
     * @param action 사용자가 할 일. 같은 입력으로 다시 만들어도 막히는 사유는 입력을 고치라고 한다
     */
    public record Rejection(String cause, String action) {}

    /**
     * 저장하지 않은 일정의 사유. 같은 입력으로 다시 만들어도 막히는 사유(출발 시각)를 먼저 고른다.
     *
     * @param ctx 판정 입력. 모르면 null
     * @return HARD 위반이 없으면 null
     */
    public static Rejection rejection(ItineraryRules.Verdict verdict, ItineraryRules.Context ctx) {
        if (verdict == null) return null;
        return verdict.violations().stream()
                .filter(v -> v.severity() == ItineraryRules.Severity.HARD)
                .sorted(Comparator.comparingInt((Violation v) -> causedByDeparture(v, ctx) ? 0 : 1)
                        .thenComparingInt(Violation::day))
                .map(v -> rejectionText(v, ctx))
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    // 여행 조건이 없는 옛 판정 입력은 출발이 일러 빈 마지막 날도 날짜 누락으로 판정한다. 조건이 있으면 그날은 판정에서 빠진다.
    private static boolean causedByDeparture(Violation v, ItineraryRules.Context ctx) {
        return v.rule() == ItineraryRules.Rule.DEPARTURE_MISSED
                || (v.rule() == ItineraryRules.Rule.DAY_MISSING && ctx != null && ctx.conditions() == null
                && ctx.lastDayHubDeadline() != null && ctx.dayCount() > 0 && v.day() == ctx.dayCount());
    }

    // default 없이 모든 규칙을 나열해 HARD로 올린 규칙에 거부 사유를 빠뜨리지 않게 한다.
    private static Rejection rejectionText(Violation v, ItineraryRules.Context ctx) {
        String retry = "다시 만들어 주세요.";
        String changeRequest = "요청을 조금 바꿔 다시 만들어 주세요.";
        String checkDeparture = "출발 시간을 확인한 뒤 다시 만들어 주세요.";
        return switch (v.rule()) {
            case DEPARTURE_MISSED -> new Rejection("마지막 날 출발 시각까지 공항·역에 닿는 일정을 만들 수 없어 저장하지 않았어요.",
                    checkDeparture);
            case DAY_MISSING -> causedByDeparture(v, ctx)
                    ? new Rejection("마지막 날 출발 시각이 일러 그날 일정을 넣지 못해 저장하지 않았어요.", checkDeparture)
                    : new Rejection(v.day() + "일차 일정을 채우지 못해 저장하지 않았어요.", retry);
            case CLOSED_DAY -> new Rejection(v.day() + "일차에 휴무일인 " + quoted(v, 0) + "이(가) 들어가 저장하지 않았어요.", retry);
            case OUTSIDE_HOURS -> new Rejection(v.day() + "일차 " + quoted(v, 0) + "을(를) 영업시간 안에 넣지 못해 저장하지 않았어요.",
                    retry);
            case WANT_MISSING -> new Rejection("꼭 가고 싶다고 한 " + quoted(v, 0) + "을(를) 일정에 넣지 못해 저장하지 않았어요.",
                    changeRequest);
            case WANT_TIME -> new Rejection(quoted(v, 0) + "을(를) 원한 시간대에 넣지 못해 저장하지 않았어요.", changeRequest);
            case CITY_DAYS -> new Rejection(quoted(v, 0) + "에서 보내기로 한 날 수를 맞추지 못해 저장하지 않았어요.",
                    changeRequest);
            case AVOID -> new Rejection(v.day() + "일차에 피하고 싶다고 한 " + quoted(v, 0) + "이(가) 들어가 저장하지 않았어요.", retry);
            case UNREACHABLE -> new Rejection(v.day() + "일차에 들어갈 수 없는 " + quoted(v, 0) + "이(가) 들어가 저장하지 않았어요.",
                    retry);
            case TRAVEL_MISSING -> new Rejection(v.day() + "일차에 이동 시간을 계산할 수 없는 구간이 있어 저장하지 않았어요.", retry);
            case NOT_ENDING_AT_LODGING -> new Rejection(v.day() + "일차 밤에 묵을 숙소를 찾지 못해 저장하지 않았어요.", retry);
            case LUNCH_MISSING, DINNER_MISSING, MEALS_BACK_TO_BACK, DEPARTURE_LATE, SAME_DAY_MEAL_KIND,
                 MEAL_KIND_REPEATED, CAFE_PER_DAY, NO_NON_FOOD_ACTIVITY, ADDRESS_REGION_MISMATCH, WANT_FOOD_MISSING,
                 WANT_NOT_IN_DATA,
                 PACE_SHORT, LONG_GAP, LONG_LEG, DAILY_TRANSIT, STRENUOUS, DUPLICATE, EARLY_FINISH, LEG_OVER_CAP,
                 DAY_DISTANCE_OVER_CAP, CONSECUTIVE_MEAL_KIND, SAME_DAY_MEAL_GROUP, MEAL_GROUP_REPEATED -> null;
        };
    }

    private static boolean isLastDayWithoutDeparture(int day, ItineraryRules.Context ctx) {
        return ctx != null && ctx.dayCount() > 0 && day == ctx.dayCount() && !ctx.departureKnown();
    }

    private static String quoted(Violation v, int i) {
        return "'" + (v.evidence().size() > i ? v.evidence().get(i) : "") + "'";
    }

    private static String first(Violation v) {
        return v.evidence().isEmpty() ? "" : v.evidence().get(0);
    }
}
