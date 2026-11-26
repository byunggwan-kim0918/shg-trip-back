package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.tripchat.model.Draft;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftField;
import com.shg.trip.shgtrip.domain.tripchat.model.OpenQuestion;
import com.shg.trip.shgtrip.domain.tripchat.model.Slot;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 비행기로 가는 해외 여행에서 항공편 시각을 한 번 묻는다. 시각을 모르면 건너뛰고, 생성은 모르는 시각을 항공편 시각으로
 * 정하지 않고 첫날 평소 시작·마지막 날 이른 오후 마침으로 짠다.
 */
final class FlightTimesQuestion {

    static final String KEY = "FOLLOW_UP:flight-times";
    /** 한국에서 배로 가는 해외(부산–후쿠오카·오사카)는 항공편이 없다. */
    private static final Pattern BY_SEA = Pattern.compile("(^|\\s)배(로|를|\\s*타)|페리|선박|크루즈|여객선|ferry",
            Pattern.CASE_INSENSITIVE);

    private FlightTimesQuestion() {}

    /**
     * @param country 여행 나라. 국내거나 모르면 묻지 않는다
     * @return 물을 질문. 이미 물었거나 시각을 다 알면 null
     */
    static OpenQuestion next(Draft draft, Set<String> asked, String country) {
        if (country == null || "KR".equalsIgnoreCase(country) || asked.contains(KEY)) return null;
        boolean arrival = draft.value(DraftField.ARRIVAL_TIME) != null;
        boolean departure = draft.value(DraftField.DEPARTURE_TIME) != null;
        if (arrival && departure) return null;
        String notes = String.join(" ", Objects.toString(draft.value(DraftField.ARRIVAL_NOTE), ""),
                Objects.toString(draft.value(DraftField.DEPARTURE_NOTE), ""),
                String.join(" ", TripRequestMapper.userItems(draft, DraftField.NOTES).stream().map(Draft.Item::text).toList()));
        if (BY_SEA.matcher(notes).find()) return null;
        String title = arrival ? "돌아오는 비행기 출발 시각을 알면 알려줄래요?"
                : departure ? "도착하는 비행기 시각을 알면 알려줄래요?"
                : "항공편 도착·출발 시각을 알면 알려줄래요?";
        return QuestionBank.followUp(Slot.EXTRAS, KEY, "비행기로 가는 여행이네요.", title,
                "시각에 맞춰 첫날과 마지막 날을 짜요. 모르면 건너뛰어도 돼요. 그때는 첫날 오전부터 마지막 날 이른 오후까지로 짜요.",
                "예: 오전 10시 도착, 오후 6시 출발", List.of("오전 10시 도착, 오후 6시 출발", "아직 몰라"));
    }
}
