package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.itinerary.dto.WizardOptionIds;
import com.shg.trip.shgtrip.domain.tripchat.model.Draft;
import com.shg.trip.shgtrip.domain.tripchat.model.Draft.Fact;
import com.shg.trip.shgtrip.domain.tripchat.model.Draft.Item;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftField;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldSource;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldStatus;
import com.shg.trip.shgtrip.domain.tripchat.model.PlaceMatch;
import com.shg.trip.shgtrip.domain.tripchat.model.Slot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 대화로 모은 조건을 기존 생성 요청으로 옮긴다. 옮길 수 없거나 생성기가 강제하지 않는 조건은 확인 화면 안내로 남긴다.
 * 생성기 계약은 이 클래스에만 있어 프론트는 요청 형식을 모른다.
 */
public final class TripRequestMapper {

    static final int MAX_TRIP_DAYS = 10;            // ItineraryGenerateRequest @ValidDateRange(maxDays = 10)
    static final int MAX_DESCRIPTION = 500;         // ItineraryGenerateRequest description @Size
    static final int MAX_DESTINATION = 50;
    static final long MAX_BUDGET = 100_000_000L;
    private static final int MAX_SELECTED_PLACES = 10;
    private static final String SEPARATOR = " / ";

    private TripRequestMapper() {}

    public record Blocker(String key, Slot slot, String title, String description, String placeholder,
                          List<String> examples) {}

    public record DateRange(LocalDate start, LocalDate end) {}

    /** "20일부터 2박 3일"처럼 시작일과 박 수만 말해도 종료일을 계산한다. 반대도 같다. */
    public static DateRange dates(Draft draft) {
        LocalDate start = parseDate(draft.value(DraftField.START_DATE));
        LocalDate end = parseDate(draft.value(DraftField.END_DATE));
        Integer nights = parseInt(draft.value(DraftField.NIGHTS), 1, 30);
        if (nights != null && start != null && end == null) end = start.plusDays(nights);
        if (nights != null && end != null && start == null) start = end.minusDays(nights);
        return new DateRange(start, end);
    }

    public record Result(ItineraryGenerateRequest request, List<Blocker> blockers, Map<Slot, List<String>> notes,
                         String description, Integer days) {

        public boolean ready() {
            return blockers.isEmpty() && request != null;
        }
    }

    public static Result map(Draft draft, Map<String, PlaceMatch> matches, LocalDate today) {
        Map<Slot, List<String>> notes = new EnumMap<>(Slot.class);
        List<Blocker> blockers = new ArrayList<>();
        List<String> fragments = new ArrayList<>();

        String destination = TextNormalizer.cap(draft.value(DraftField.DESTINATION), MAX_DESTINATION);
        if (destination == null) blockers.add(destinationBlocker());

        DateRange range = dates(draft);
        LocalDate start = range.start();
        LocalDate end = range.end();
        Integer days = null;
        Blocker dateBlocker = dateBlocker(draft, start, end, today);
        if (dateBlocker != null) blockers.add(dateBlocker);
        else days = (int) ChronoUnit.DAYS.between(start, end) + 1;

        String companion = inSet(draft.value(DraftField.COMPANION), GeneratorSupport.COMPANION_LABELS.keySet());
        Integer partySize = parseInt(draft.value(DraftField.PARTY_SIZE), 1, 20);
        companionFragment(draft, companion).ifPresent(fragments::add);

        List<String> themes = new ArrayList<>(whitelisted(draft.items(DraftField.THEMES), WizardOptionIds.THEMES));
        List<String> categories = new ArrayList<>(whitelisted(draft.items(DraftField.CATEGORIES), WizardOptionIds.CATEGORIES));
        String pace = inSet(draft.value(DraftField.PACE), WizardOptionIds.PACES);
        tasteNotes(draft, themes, pace, notes);
        if (themes.isEmpty()) themes.addAll(GeneratorSupport.DEFAULT_THEMES);
        if (categories.isEmpty()) categories.addAll(deriveCategories(themes));
        String priorities = draft.value(DraftField.PRIORITIES);
        if (priorities != null) fragments.add(0, "원하는 여행: " + priorities);

        Fact transport = draft.fact(DraftField.TRANSPORT).orElse(null);
        boolean tentative = transport != null && transport.source() == FieldSource.TENTATIVE;
        String mode = transport == null ? null : transport.value();
        String transportPref = GeneratorSupport.transportPref(mode, tentative);
        addNote(notes, Slot.TRANSPORT, GeneratorSupport.transportNote(mode, tentative));
        transportFragment(mode, tentative).ifPresent(fragments::add);
        if (draft.value(DraftField.WALKING) != null) fragments.add("걷기: " + draft.value(DraftField.WALKING));

        Set<Long> placeIds = new LinkedHashSet<>();
        anchors(draft, matches, placeIds, notes, fragments);

        String arrival = time(draft.value(DraftField.ARRIVAL_TIME));
        String departure = time(draft.value(DraftField.DEPARTURE_TIME));
        if (draft.value(DraftField.ARRIVAL_NOTE) != null) {
            fragments.add("도착: " + draft.value(DraftField.ARRIVAL_NOTE));
            if (arrival == null) addNote(notes, Slot.EXTRAS, GeneratorSupport.APPROX_TIME);
        }
        if (draft.value(DraftField.DEPARTURE_NOTE) != null) {
            fragments.add("출발: " + draft.value(DraftField.DEPARTURE_NOTE));
            if (departure == null) addNote(notes, Slot.EXTRAS, GeneratorSupport.APPROX_TIME);
        }

        BigDecimal budget = budget(draft, companion, partySize, days, notes, fragments, blockers);

        listFragment("좋아하는 음식", draft.items(DraftField.FOOD_LIKES), fragments, notes);
        listFragment("못 먹거나 피할 음식", draft.items(DraftField.FOOD_AVOID), fragments, notes);
        listFragment("피하고 싶은 활동", draft.items(DraftField.AVOID_ACTIVITIES), fragments, notes);
        listFragment("기타", draft.items(DraftField.NOTES), fragments, notes);
        if (!draft.items(DraftField.AVOID_PLACES).isEmpty()) addNote(notes, Slot.EXTRAS, GeneratorSupport.AVOID_PLACES);

        String description = describe(fragments, draft.items(DraftField.AVOID_PLACES));
        if (description != null && description.length() > MAX_DESCRIPTION) {
            blockers.add(new Blocker("description_too_long", Slot.EXTRAS, "더 알려준 내용을 조금 줄여줄래요?",
                    "추가 조건은 " + MAX_DESCRIPTION + "자까지 전달할 수 있어요. 꼭 필요한 것만 다시 알려주세요.",
                    "예: 해산물은 못 먹어", List.of()));
        }

        if (!blockers.isEmpty()) return new Result(null, blockers, notes, description, days);

        List<Long> selected = List.copyOf(placeIds);
        // 선택 장소는 모드와 관계없이 일정에 들어가지만, MANUAL은 선택 장소가 있어야 요청 검증을 통과한다.
        ItineraryGenerateRequest.PlanningMode planningMode = selected.isEmpty()
                ? ItineraryGenerateRequest.PlanningMode.AUTO : ItineraryGenerateRequest.PlanningMode.MANUAL;
        ItineraryGenerateRequest request = new ItineraryGenerateRequest(planningMode, destination,
                themes.stream().limit(10).toList(), categories.stream().limit(10).toList(), pace, transportPref,
                budget, start, end, description, selected, List.of(), companion, partySize, arrival, departure);
        return new Result(request, blockers, notes, description, days);
    }

    /** 확인 화면 보완 질문에 답하면 그 보완 대상 필드만 지우고 새 답을 받는다. */
    public static List<DraftField> fieldsToReplace(String blockerKey) {
        if (blockerKey == null) return List.of();
        if (blockerKey.startsWith("dates_")) return List.of(DraftField.START_DATE, DraftField.END_DATE,
                DraftField.DATE_APPROX, DraftField.NIGHTS);
        if (blockerKey.equals("destination_missing")) return List.of(DraftField.DESTINATION);
        if (blockerKey.equals("budget_floor")) return List.of(DraftField.BUDGET, DraftField.BUDGET_SCOPE);
        if (blockerKey.equals("description_too_long")) return List.of(DraftField.FOOD_LIKES, DraftField.FOOD_AVOID,
                DraftField.AVOID_ACTIVITIES, DraftField.NOTES, DraftField.ARRIVAL_NOTE, DraftField.DEPARTURE_NOTE);
        return List.of();
    }

    public static Blocker dateBlocker(Draft draft, LocalDate start, LocalDate end, LocalDate today) {
        Integer nights = parseInt(draft.value(DraftField.NIGHTS), 1, 30);
        if (start == null || end == null) {
            DateCandidates.Approx approx = DateCandidates.parseApprox(draft.value(DraftField.DATE_APPROX));
            List<DateCandidates.Range> ranges = approx != null
                    ? DateCandidates.forApprox(approx, nights, today) : DateCandidates.upcoming(nights, today);
            if (ranges.isEmpty()) ranges = DateCandidates.upcoming(nights, today);
            String said = approx != null ? DateCandidates.approxLabel(approx, today) + "쯤이라고 했어요. " : "";
            return new Blocker("dates_missing", Slot.DATES, "언제 떠날지 알려줄래요?",
                    said + "날짜를 알아야 일정을 만들 수 있어요.", "예: " + QuestionBank.sampleRange(today),
                    phrases(ranges, today));
        }
        if (start.isBefore(today)) {
            return new Blocker("dates_past", Slot.DATES, "이번 여행은 언제 떠나나요?",
                    "이미 지난 날짜로는 일정을 만들 수 없어요.", "예: " + QuestionBank.sampleRange(today),
                    phrases(DateCandidates.upcoming(nights, today), today));
        }
        if (!end.isAfter(start)) {
            DateCandidates.Range oneNight = new DateCandidates.Range(start, start.plusDays(1));
            return new Blocker("dates_day_trip", Slot.DATES, "하룻밤 묵어 가는 일정으로 바꿔볼까요?",
                    "아직은 1박 이상 일정만 만들 수 있어요.", "예: " + DateCandidates.phrase(oneNight, today),
                    List.of(DateCandidates.phrase(oneNight, today), "날짜를 다시 정할게"));
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_TRIP_DAYS) {
            DateCandidates.Range max = new DateCandidates.Range(start, start.plusDays(MAX_TRIP_DAYS - 1));
            return new Blocker("dates_too_long", Slot.DATES, "10일 안으로 줄여볼까요?",
                    days + "일 일정은 한 번에 만들 수 없어요. 최대 9박 10일까지 만들 수 있어요.",
                    "예: " + DateCandidates.phrase(max, today),
                    List.of(DateCandidates.phrase(max, today), "날짜를 다시 정할게"));
        }
        return null;
    }

    private static Blocker destinationBlocker() {
        return new Blocker("destination_missing", Slot.CITY, "어디로 떠날지 알려줄래요?",
                "여행지를 알아야 일정을 만들 수 있어요.", "예: 부산", List.of("부산", "제주", "강릉"));
    }

    private static void tasteNotes(Draft draft, List<String> themes, String pace, Map<Slot, List<String>> notes) {
        boolean inferredThemes = !themes.isEmpty() && draft.items(DraftField.THEMES).stream()
                .noneMatch(i -> i.source().fromUser());
        boolean inferredPace = pace != null && draft.fact(DraftField.PACE)
                .map(f -> !f.source().fromUser()).orElse(false);
        if (themes.isEmpty()) {
            addNote(notes, Slot.MOOD, draft.slotStatus(Slot.MOOD) == FieldStatus.KNOWN
                    ? GeneratorSupport.UNCLASSIFIED_TASTE : GeneratorSupport.DEFAULT_TASTE);
            return;
        }
        if (!inferredThemes && !inferredPace) return;
        List<String> parts = new ArrayList<>();
        if (pace != null) parts.add(GeneratorSupport.PACE_LABELS.get(pace));
        parts.add(String.join("·", themes.stream().limit(3)
                .map(t -> GeneratorSupport.THEME_LABELS.getOrDefault(t, t)).toList()) + " 위주로 찾아요");
        addNote(notes, Slot.MOOD, String.join(" · ", parts) + GeneratorSupport.SUGGESTED_SUFFIX);
    }

    /** 모델이 추론한 숙소·장소는 고정하지 않는다. 사용자가 말한 것만 일정에 강제로 넣는다. */
    private static void anchors(Draft draft, Map<String, PlaceMatch> matches, Set<Long> placeIds,
                                Map<Slot, List<String>> notes, List<String> fragments) {
        String lodgingState = userValue(draft, DraftField.LODGING_STATE);
        String lodging = userValue(draft, DraftField.LODGING);
        if (lodging != null && "booked".equals(lodgingState)) {
            PlaceMatch m = matches.get(PlaceMatcher.key(DraftField.LODGING, lodging));
            if (m != null && "LODGING".equals(m.major()) && placeIds.size() < MAX_SELECTED_PLACES) {
                placeIds.add(m.placeId());
                addNote(notes, Slot.ANCHORS, m.name() + " · " + GeneratorSupport.LODGING_FIXED);
            } else {
                fragments.add("숙소 예약: " + lodging);
                addNote(notes, Slot.ANCHORS, lodging + " · "
                        + (m == null ? GeneratorSupport.LODGING_NOT_FOUND : GeneratorSupport.LODGING_NOT_LODGING));
            }
        } else if (lodging != null && "wish".equals(lodgingState)) {
            fragments.add("숙소: " + lodging + " 쪽 희망");
            addNote(notes, Slot.ANCHORS, GeneratorSupport.LODGING_REFERENCE);
        }

        List<String> unmatched = new ArrayList<>();
        for (Item item : userItems(draft, DraftField.MUST_VISIT)) {
            PlaceMatch m = matches.get(PlaceMatcher.key(DraftField.MUST_VISIT, item.text()));
            if (m != null && !"LODGING".equals(m.major()) && placeIds.size() < MAX_SELECTED_PLACES) {
                placeIds.add(m.placeId());
                addNote(notes, Slot.ANCHORS, m.name() + " · " + GeneratorSupport.PLACE_INCLUDED);
            } else {
                unmatched.add(item.text());
                addNote(notes, Slot.ANCHORS, item.text() + " · " + (m == null ? GeneratorSupport.PLACE_REFERENCE
                        : "LODGING".equals(m.major()) ? GeneratorSupport.PLACE_IS_LODGING : GeneratorSupport.PLACES_FULL));
            }
        }
        if (!unmatched.isEmpty()) fragments.add("가고 싶은 곳: " + String.join(", ", unmatched));

        for (Item item : userItems(draft, DraftField.BOOKED)) {
            PlaceMatch m = matches.get(PlaceMatcher.key(DraftField.BOOKED, item.text()));
            String when = String.join(" ", nonBlank(item.date(), item.time()));
            fragments.add("예약: " + item.text() + (when.isBlank() ? "" : " " + when));
            if (m != null && !"LODGING".equals(m.major()) && placeIds.size() < MAX_SELECTED_PLACES) {
                placeIds.add(m.placeId());
                addNote(notes, Slot.ANCHORS, m.name() + " · " + GeneratorSupport.BOOKED_INCLUDED);
            } else {
                addNote(notes, Slot.ANCHORS, item.text() + " · "
                        + (m == null ? GeneratorSupport.PLACE_REFERENCE : GeneratorSupport.PLACES_FULL));
            }
            if (item.date() == null || item.time() == null) {
                addNote(notes, Slot.ANCHORS, item.text() + " · " + GeneratorSupport.BOOKED_TIME_MISSING);
            }
        }
    }

    private static BigDecimal budget(Draft draft, String companion, Integer partySize, Integer days,
                                     Map<Slot, List<String>> notes, List<String> fragments, List<Blocker> blockers) {
        Long amount = parseLong(draft.value(DraftField.BUDGET));
        if (amount == null || amount <= 0) return null;
        String scope = draft.value(DraftField.BUDGET_SCOPE);
        long total;
        if ("excluding_lodging".equals(scope)) {
            fragments.add("예산: 숙박 제외 " + won(amount));
            addNote(notes, Slot.EXTRAS, GeneratorSupport.BUDGET_EXCLUDING_LODGING);
            return null;
        }
        if ("per_person".equals(scope)) {
            Integer people = partySize != null ? partySize
                    : "solo".equals(companion) ? Integer.valueOf(1) : "couple".equals(companion) ? Integer.valueOf(2) : null;
            if (people == null) {
                fragments.add("예산: 1인 " + won(amount));
                addNote(notes, Slot.EXTRAS, GeneratorSupport.BUDGET_NEEDS_PARTY);
                return null;
            }
            total = amount * people;
        } else {
            total = amount;
        }
        if (total > MAX_BUDGET) return null;
        addNote(notes, Slot.EXTRAS, GeneratorSupport.BUDGET_REFERENCE);
        if (days != null && total / days < GeneratorSupport.MIN_BUDGET_PER_DAY) {
            blockers.add(new Blocker("budget_floor", Slot.EXTRAS, "예산을 다시 알려줄래요?",
                    "하루 5만원보다 적은 예산으로는 일정을 만들기 어려워요.", "예: 예산은 일행 전체 50만원",
                    List.of("예산은 상관없어", "예산은 일행 전체 " + won(days * 100_000L))));
        }
        return BigDecimal.valueOf(total);
    }

    private static java.util.Optional<String> companionFragment(Draft draft, String companion) {
        // 아이 나이·부모님 동반처럼 동행 값 하나로 담기지 않는 사정은 원문으로 넘긴다.
        if (!"family".equals(companion) && !"parents".equals(companion)) return java.util.Optional.empty();
        return draft.fact(DraftField.COMPANION).map(Fact::raw).filter(r -> r != null && !r.isBlank())
                .map(r -> "동행: " + r);
    }

    private static java.util.Optional<String> transportFragment(String mode, boolean tentative) {
        if (tentative) return java.util.Optional.of("이동: 걷는 쪽을 생각 중");
        if (mode == null) return java.util.Optional.empty();
        return java.util.Optional.ofNullable(switch (mode) {
            case "transit" -> "이동: 대중교통(차 없음)";
            case "walk_transit" -> "이동: 주로 걸어서, 먼 곳은 택시나 버스";
            case "taxi" -> "이동: 택시";
            case "mixed" -> "이동: 여러 교통편";
            default -> null;
        });
    }

    private static void listFragment(String label, List<Item> items, List<String> fragments,
                                     Map<Slot, List<String>> notes) {
        if (items.isEmpty()) return;
        fragments.add(label + ": " + String.join(", ", items.stream().map(Item::text).toList()));
        addNote(notes, Slot.EXTRAS, GeneratorSupport.CONDITIONS_REFERENCE);
    }

    /** 생성기는 요청 문장에 이름이 나온 명소를 후보에 더하므로, 피하고 싶다는 장소 이름이 든 조각은 보내지 않는다. */
    static String describe(List<String> fragments, List<Item> avoidPlaces) {
        // 한 글자 이름("산")으로 거르면 "부산"·"예산"이 든 조각까지 빠진다.
        List<Item> names = avoidPlaces.stream().filter(a -> TextNormalizer.key(a.text()).length() >= 2).toList();
        List<String> kept = fragments.stream()
                .filter(f -> names.stream().noneMatch(a -> TextNormalizer.containsKey(f, a.text())))
                .toList();
        return kept.isEmpty() ? null : String.join(SEPARATOR, kept);
    }

    private static List<String> deriveCategories(List<String> themes) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : themes) out.addAll(GeneratorSupport.THEME_DEFAULT_CATEGORIES.getOrDefault(t, List.of()));
        List<String> derived = out.stream().limit(4).toList();
        return derived.isEmpty() ? GeneratorSupport.DEFAULT_CATEGORIES : derived;
    }

    static String userValue(Draft draft, DraftField field) {
        return draft.fact(field).filter(f -> f.source().fromUser()).map(Draft.Fact::value).orElse(null);
    }

    static List<Item> userItems(Draft draft, DraftField field) {
        return draft.items(field).stream().filter(i -> i.source().fromUser()).toList();
    }

    private static List<String> whitelisted(List<Item> items, Set<String> allowed) {
        return items.stream().map(Item::text).filter(allowed::contains).distinct().toList();
    }

    private static List<String> phrases(List<DateCandidates.Range> ranges, LocalDate today) {
        return ranges.stream().map(r -> DateCandidates.phrase(r, today)).toList();
    }

    private static void addNote(Map<Slot, List<String>> notes, Slot slot, String note) {
        if (note == null) return;
        List<String> list = notes.computeIfAbsent(slot, s -> new ArrayList<>());
        if (!list.contains(note)) list.add(note);
    }

    private static List<String> nonBlank(String... values) {
        List<String> out = new ArrayList<>();
        for (String v : values) if (v != null && !v.isBlank()) out.add(v);
        return out;
    }

    static String won(long amount) {
        if (amount % 10_000 == 0) return (amount / 10_000) + "만원";
        return String.format("%,d원", amount);
    }

    static LocalDate parseDate(String value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static String inSet(String value, Set<String> allowed) {
        return value != null && allowed.contains(value) ? value : null;
    }

    private static String time(String value) {
        return value != null && value.matches("([01]\\d|2[0-3]):[0-5]\\d") ? value : null;
    }

    static Integer parseInt(String value, int min, int max) {
        Long v = parseLong(value);
        return v == null || v < min || v > max ? null : v.intValue();
    }

    static Long parseLong(String value) {
        if (value == null) return null;
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
