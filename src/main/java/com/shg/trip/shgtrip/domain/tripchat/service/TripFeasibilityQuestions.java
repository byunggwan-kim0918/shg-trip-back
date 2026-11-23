package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.PlaceRestriction;
import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.planning.service.TripFeasibility;
import com.shg.trip.shgtrip.domain.tripchat.model.Draft;
import com.shg.trip.shgtrip.domain.tripchat.model.Draft.Item;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftField;
import com.shg.trip.shgtrip.domain.tripchat.model.OpenQuestion;
import com.shg.trip.shgtrip.domain.tripchat.model.PlaceMatch;
import com.shg.trip.shgtrip.domain.tripchat.model.Slot;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 답한 그 턴에 되물을 지킬 수 없는 요청. 확인 화면이나 생성에서야 막히면 여행자는 대화를 다시 해야 한다. 판정 기준은
 * 생성과 같은 {@link TripFeasibility}를 쓴다. 같은 내용은 한 번만 묻는다. 넣을 수 없는 바람은 바꾸거나 빼거나 그대로
 * 둘 수 있고, 그대로 두면 생성이 동의 없이 뺀 것으로 보지 않는다({@link #acknowledged}).
 */
final class TripFeasibilityQuestions {

    static final String PREFIX = "CRITICAL:feasibility:";
    private static final String KEEP = "그대로 둘게";
    private static final String KEEP_NOTE = "그대로 두면 일정에는 넣지 못하고, 넣지 못했다고 알려 드릴게요.";
    /** 바람 하나에 대해 묻는 되묻기 종류. 어느 것이든 물은 뒤 바람이 남아 있으면 여행자가 그대로 두기로 한 것이다. */
    private static final List<String> WISH_KINDS = List.of("missing", "closed", "evening", "sunrise");
    private static final int MAX_ACKNOWLEDGED = 10;

    private TripFeasibilityQuestions() {}

    /**
     * @param matches     이번 상태로 맞춘 장소. 이번 턴에 다시 맞추지 않았으면 null이고 장소 판정을 하지 않는다
     * @param suggestions 데이터에서 찾지 못한 꼭 갈 곳마다 비슷한 관광지
     * @param country     여행지 나라. 모르면 null
     * @param city        해 뜨고 지는 시각을 볼 여행 도시. 모르면 null이고 시각 판정을 하지 않는다
     */
    static OpenQuestion next(Draft draft, Map<String, PlaceMatch> matches, Map<String, String> suggestions,
                             Set<String> asked, String country, Destination city) {
        OpenQuestion q = foodConflict(draft, asked);
        if (q == null && matches != null) q = missingPlace(draft, matches, suggestions, asked);
        if (q == null && matches != null) q = closedWholeTrip(draft, matches, asked);
        if (q == null && city != null) q = noTimeOfDay(draft, asked, city);
        if (q == null) q = drivingAbroad(draft, asked, country);
        return q;
    }

    /** 넣을 수 없다는 되묻기를 듣고도 남겨 둔 꼭 갈 곳·바람. */
    static List<String> acknowledged(Draft draft, Set<String> asked) {
        Set<String> out = new LinkedHashSet<>();
        for (Item item : wishes(draft)) {
            String k = TextNormalizer.key(item.text());
            if (WISH_KINDS.stream().anyMatch(kind -> asked.contains(PREFIX + kind + ":" + k))) {
                out.add(TextNormalizer.cap(item.text(), TripRequestMapper.MAX_ACCEPTED_TEXT));
            }
            if (out.size() >= MAX_ACKNOWLEDGED) break;
        }
        return List.copyOf(out);
    }

    private static List<Item> wishes(Draft draft) {
        List<Item> out = new ArrayList<>(TripRequestMapper.userItems(draft, DraftField.MUST_VISIT));
        out.addAll(TripRequestMapper.userItems(draft, DraftField.PRIORITIES));
        return out;
    }

    /** 먹고 싶다고 한 음식이 피하겠다고 한 음식에 걸린다. 어느 쪽을 따를지는 여행자만 정할 수 있다. */
    private static OpenQuestion foodConflict(Draft draft, Set<String> asked) {
        List<PlaceRestriction> avoid = avoided(draft);
        if (avoid.isEmpty()) return null;
        List<Item> wants = new ArrayList<>(TripRequestMapper.userItems(draft, DraftField.FOOD_LIKES));
        wants.addAll(TripRequestMapper.userItems(draft, DraftField.PRIORITIES));
        for (Item want : wants) {
            if (TripFeasibility.dishConflict(want.text(), avoid) == null) continue;
            String key = PREFIX + "food:" + TextNormalizer.key(want.text());
            if (asked.contains(key)) continue;
            return QuestionBank.followUp(Slot.EXTRAS, key, "먹고 싶은 것과 피하고 싶은 음식이 겹쳐요.",
                    "'" + want.text() + "'은(는) 어떻게 할까요?", "피하고 싶은 음식에 걸려 그대로는 넣을 수 없어요.",
                    "예: 그건 빼줘", List.of("그건 빼줘", "그 음식은 먹어도 괜찮아"));
        }
        return null;
    }

    private static List<PlaceRestriction> avoided(Draft draft) {
        List<PlaceRestriction> avoid = new ArrayList<>();
        for (Item item : TripRequestMapper.userItems(draft, DraftField.FOOD_AVOID)) {
            avoid.addAll(GeneratorSupport.restrictionsOf(item.text()));
        }
        return avoid;
    }

    /** 꼭 가고 싶은 곳이 장소 데이터에 아직 없다. 비슷한 곳이 있으면 함께 권한다. */
    private static OpenQuestion missingPlace(Draft draft, Map<String, PlaceMatch> matches,
                                             Map<String, String> suggestions, Set<String> asked) {
        for (Item item : TripRequestMapper.userItems(draft, DraftField.MUST_VISIT)) {
            String k = PlaceMatcher.key(DraftField.MUST_VISIT, item.text());
            if (matches.containsKey(k) || !PlaceMatcher.searchable(item.text())) continue;
            String key = PREFIX + "missing:" + TextNormalizer.key(item.text());
            if (asked.contains(key)) continue;
            String other = suggestions == null ? null : suggestions.get(k);
            // 다른 도시에 있는 곳은 데이터에 없는 것이 아니다. 여행지와 맞는지는 대화 해석이 되묻는다.
            if (PlaceMatcher.ELSEWHERE.equals(other)) continue;
            String lead = "'" + item.text() + "'은(는) 아직 업데이트가 되지 않았어요.";
            return other == null
                    ? QuestionBank.followUp(Slot.ANCHORS, key, lead, "다른 곳으로 갈까요?",
                            "가고 싶은 다른 곳을 알려주거나 빼고 갈 수 있어요. " + KEEP_NOTE, "예: 빼고 갈게",
                            List.of("빼고 갈게", KEEP))
                    : QuestionBank.followUp(Slot.ANCHORS, key, lead,
                            "'" + other + "'은(는) 어때요? 혹은 다른 곳으로 갈까요?",
                            "비슷한 곳으로 바꾸거나 빼고 갈 수 있어요. " + KEEP_NOTE, "예: " + other + "로 할게",
                            List.of(other + "로 할게", "빼고 갈게", KEEP));
        }
        return null;
    }

    /** 꼭 가고 싶은 곳이 여행하는 모든 날 쉰다. 날짜를 옮기거나 빼야 한다. */
    private static OpenQuestion closedWholeTrip(Draft draft, Map<String, PlaceMatch> matches, Set<String> asked) {
        TripRequestMapper.DateRange range = TripRequestMapper.dates(draft);
        if (range.start() == null || range.end() == null) return null;
        for (Item item : TripRequestMapper.userItems(draft, DraftField.MUST_VISIT)) {
            PlaceMatch m = matches.get(PlaceMatcher.key(DraftField.MUST_VISIT, item.text()));
            if (m == null || !TripFeasibility.closedEveryDay(m.name(), m.openingHours(), m.closedDays(), range.start(),
                    range.end())) continue;
            String key = PREFIX + "closed:" + TextNormalizer.key(item.text());
            if (asked.contains(key)) continue;
            return QuestionBank.followUp(Slot.ANCHORS, key, "'" + m.name() + "'은(는) 여행하는 날 모두 쉬는 날이에요.",
                    "날짜를 바꿀까요, 빼고 갈까요?", "쉬는 날에는 들어갈 수 없어요. " + KEEP_NOTE, "예: 빼고 갈게",
                    List.of("날짜를 바꿀게", "빼고 갈게", KEEP));
        }
        return null;
    }

    /** 해 진 뒤나 해 뜨기 전에 하는 바람인데 여행 날짜·도착·출발로는 그 시각이 남는 날이 없다. */
    private static OpenQuestion noTimeOfDay(Draft draft, Set<String> asked, Destination city) {
        TripRequestMapper.DateRange range = TripRequestMapper.dates(draft);
        if (range.start() == null || range.end() == null) return null;
        LocalTime arrival = TripRequestMapper.clock(draft, DraftField.ARRIVAL_TIME);
        LocalTime departure = TripRequestMapper.clock(draft, DraftField.DEPARTURE_TIME);
        for (Item item : wishes(draft)) {
            Slot slot = TripRequestMapper.userItems(draft, DraftField.MUST_VISIT).contains(item) ? Slot.ANCHORS
                    : Slot.MOOD;
            String k = TextNormalizer.key(item.text());
            if (sunrise(item.text())) {
                if (!TripFeasibility.noSunriseDay(range.start(), range.end(), city.lat(), city.lng(), city.country(),
                        arrival) || asked.contains(PREFIX + "sunrise:" + k)) continue;
                return QuestionBank.followUp(slot, PREFIX + "sunrise:" + k,
                        "'" + item.text() + "'은(는) 해 뜨기 전에 닿아야 해서 당일 여행으로는 시간이 맞지 않아요.",
                        "해 뜨기 전에 도착하나요, 하루 더 묵을까요?", KEEP_NOTE, "예: 새벽 5시에 도착해",
                        List.of("새벽 5시에 도착해", "하루 더 묵을게", "빼고 갈게", KEEP));
            }
            if ("evening".equals(GeneratorSupport.experienceTiming(item.text()))) {
                if (!TripFeasibility.noEveningDay(range.start(), range.end(), city.lat(), city.lng(), city.country(),
                        arrival, departure) || asked.contains(PREFIX + "evening:" + k)) continue;
                return QuestionBank.followUp(slot, PREFIX + "evening:" + k,
                        "'" + item.text() + "'은(는) 해 진 뒤에 하는 건데 여행하는 날 저녁 시간이 남지 않아요.",
                        "출발을 늦출까요, 그대로 둘까요?", KEEP_NOTE, "예: 출발을 저녁 9시로 늦출게",
                        List.of("출발을 저녁 9시로 늦출게", "빼고 갈게", KEEP));
            }
        }
        return null;
    }

    private static boolean sunrise(String text) {
        return text != null && (text.contains("일출") || text.contains("해돋이"));
    }

    private static OpenQuestion drivingAbroad(Draft draft, Set<String> asked, String country) {
        if (!"car".equals(TripRequestMapper.userValue(draft, DraftField.TRANSPORT))) return null;
        if (!TripFeasibility.drivingNotAllowed(country)) return null;
        String key = PREFIX + "driving";
        if (asked.contains(key)) return null;
        return QuestionBank.followUp(Slot.TRANSPORT, key, "이 나라는 한국 운전면허로 운전할 수 없어요.",
                "택시나 대중교통으로 다닐까요?", "차를 직접 몰 수 없어 다른 이동 방법이 필요해요.",
                "예: 택시로 다닐게", List.of("택시로 다닐게", "대중교통으로 다닐게"));
    }
}
