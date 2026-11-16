package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.AlternativeData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.ScheduleArgs;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.shg.trip.shgtrip.domain.planning.service.PlanEdits.collectUsedIndices;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.paceRange;

/**
 * 스케줄 한 번이 공유하는 입력과 작업 상태. 수리 단계는 days·spare·usedIndices를 제자리에서 고친다.
 * usedIndices는 {@link #beginScheduling()} 전에는 null이다.
 */
final class PlanState {

    final List<DayState> days;
    final List<PlaceCandidate> candidates;
    final Deque<Integer> spare;
    final List<List<Integer>> pairs;
    final Set<Integer> highlights;
    final Set<Integer> rests;
    final String pace;
    final String transportPref;
    final LocalDate startDate;
    final List<String> userCategories;
    final int minPerDay;
    final int maxPerDay;
    final ScheduleConfig cfg;
    Set<Integer> usedIndices;
    private List<Integer> spareBaseline;
    /** 날짜별로 그날 스텝의 대안에 나온 장소 키. 다른 날 대안을 고를 때 반복을 피하는 데 쓴다. */
    private final Map<Integer, Set<String>> alternativeKeysByDay = new HashMap<>();
    private Earlier earlier = Earlier.NONE;

    /**
     * 앞 도시 구간에서 이미 정한 것. 도시 구간마다 상태를 새로 만들어 여행 전체 기준(브랜드·대안·끼니 종류 상한)은 여기서 이어 받는다.
     * 상한은 지금까지 지난 일수로 정해, 첫 도시가 여행 전체 몫을 먼저 다 쓰지 않는다.
     *
     * @param daysBefore 앞 구간들의 일수
     */
    record Earlier(Set<String> brands, Set<String> alternativeKeys, Map<String, Integer> mealKinds, int daysBefore) {

        static final Earlier NONE = new Earlier(Set.of(), Set.of(), Map.of(), 0);

        /** @param blockDays 이번 구간의 일수 */
        Earlier plus(List<StepData> steps, CandidateLookup lookup, int blockDays) {
            Set<String> nextBrands = new HashSet<>(brands);
            Set<String> nextKeys = new HashSet<>(alternativeKeys);
            Map<String, Integer> nextKinds = new HashMap<>(mealKinds);
            for (StepData step : steps) {
                PlaceCandidate c = lookup.of(step);
                if (c != null) {
                    String brand = CandidateTraits.diningBrand(c);
                    if (brand != null) nextBrands.add(brand);
                    String kind = CandidatePolicy.cuisineOf(c);
                    if (c.mealEligible() && CandidateLookup.isSpecificKind(kind)) nextKinds.merge(kind, 1, Integer::sum);
                }
                if (step.alternatives() == null) continue;
                for (AlternativeData a : step.alternatives()) {
                    String key = AlternativeBuilder.altKey(a);
                    if (key != null) nextKeys.add(key);
                }
            }
            return new Earlier(nextBrands, nextKeys, nextKinds, daysBefore + blockDays);
        }
    }

    private PlanState(List<DayState> days, List<PlaceCandidate> candidates, Deque<Integer> spare,
                      List<List<Integer>> pairs, Set<Integer> highlights, Set<Integer> rests, ScheduleArgs args,
                      int minPerDay, int maxPerDay) {
        this.days = days;
        this.candidates = candidates;
        this.spare = spare;
        this.pairs = pairs;
        this.highlights = highlights;
        this.rests = rests;
        this.pace = args.pace();
        this.transportPref = args.transportPref();
        this.startDate = args.startDate();
        this.userCategories = args.categories();
        this.minPerDay = minPerDay;
        this.maxPerDay = maxPerDay;
        this.cfg = ScheduleConfig.forTrip(args.themes(), args.pace(), args.couple());
    }

    static PlanState of(SelectionOutput selection, List<PlaceCandidate> candidates, ScheduleArgs args) {
        int[] range = paceRange(args.pace());
        int minPerDay = args.compact() ? Math.min(range[0], 3) : range[0];

        List<DayState> days = selection.days().stream().map(DayState::new).collect(Collectors.toList());
        LocalDate startDate = args.startDate();
        // 영업시간 판정이 스케줄 깊숙이 퍼져 있어 startDate를 끌고 내려가는 대신 day가 자기 요일을 갖는다.
        // startDate가 없으면 null로 남고 판정을 건너뛴다.
        if (startDate != null) {
            days.forEach(d -> d.dayOfWeek = startDate.plusDays(d.dayNumber - 1L).getDayOfWeek());
        }
        if (!days.isEmpty()) {
            days.get(0).startNotBefore = ScheduleTimes.firstDayStart(args);
            days.get(0).resumesAfterTransfer = args.resumesAfterTransfer();
            days.get(days.size() - 1).departureDeadline = ScheduleTimes.departureHubDeadline(args);
            days.get(days.size() - 1).continuesAfter = args.continuesAfter();
        }
        List<List<Integer>> pairs = selection.pairs() != null ? selection.pairs() : List.of();
        Deque<Integer> spare = new ArrayDeque<>(selection.spareIndices() != null ? selection.spareIndices() : List.of());
        Set<Integer> highlights = selection.highlightIndices() != null
                ? new HashSet<>(selection.highlightIndices()) : Set.of();
        Set<Integer> rests = selection.restIndices() != null
                ? new HashSet<>(selection.restIndices()) : Set.of();
        return new PlanState(days, candidates, spare, pairs, highlights, rests, args, minPerDay, range[1]);
    }

    /** 날짜별 구성이 정해진 뒤, 첫 스케줄 전에 한 번 부른다. */
    void beginScheduling() {
        usedIndices = collectUsedIndices(days);
        // Sonnet이 메인과 spare에 같은 인덱스를 적으면 일정 장소가 다른 스텝의 대안으로 재등장한다.
        spare.removeAll(usedIndices);
        // 최종 불변식 패스가 day를 다시 스케줄할 때 같은 spare 조건으로 돌린다.
        spareBaseline = new ArrayList<>(spare);
    }

    void continueFrom(Earlier earlier) {
        this.earlier = earlier;
    }

    Earlier earlier() {
        return earlier;
    }

    /** 앞 구간과 이번 구간을 합친 일수. 끼니 종류 상한을 여기에 맞춘다. */
    int daysThroughBlock() {
        return earlier.daysBefore() + days.size();
    }

    /** 세부 유형별 수. 끼니 종류는 앞 도시 구간에서 먹은 것까지 더한다. */
    Map<String, Integer> typeCounts() {
        Map<String, Integer> counts = DiversityRepair.countSubTypes(days, candidates);
        earlier.mealKinds().forEach((kind, n) -> counts.merge(kind, n, Integer::sum));
        return counts;
    }

    /** 끼니 종류는 앞 구간까지 지난 일수, 나머지 유형은 이 구간 날 수로 정한 상한. */
    int typeCap(PlaceCandidate c) {
        return DiversityRepair.typeCapFor(c, c != null && c.mealEligible() ? daysThroughBlock() : days.size());
    }

    /** 날짜별 구성에 있는 식음료 브랜드와 앞 도시 구간에서 간 브랜드. 확정에서 빠진 장소는 세지 않는다. */
    Set<String> usedBrands() {
        Set<String> brands = CandidateTraits.diningBrands(collectUsedIndices(days), candidates);
        brands.addAll(earlier.brands());
        return brands;
    }

    /** 일정 전체를 다시 확정하기 전에 부른다. 지난 확정의 키가 남으면 앞날이 곧 다시 만들어질 뒷날 대안까지 피한다. */
    void resetAlternativeKeys() {
        alternativeKeysByDay.clear();
    }

    /**
     * 그날 대안을 고를 때 볼 여행 전체 조건. 그날 키 집합은 새로 만들어 등록하고 대안을 고르며 채운다.
     *
     * @param dayKeys 그날 대안 키를 담을 빈 집합
     */
    AlternativeBuilder.TripView alternativesFor(DayState day, Set<String> dayKeys) {
        alternativeKeysByDay.put(day.dayNumber, dayKeys);
        Set<String> elsewhere = new HashSet<>(earlier.alternativeKeys());
        alternativeKeysByDay.forEach((d, keys) -> {
            if (d != day.dayNumber) elsewhere.addAll(keys);
        });
        Map<String, Integer> kinds = new HashMap<>(earlier.mealKinds());
        for (Integer i : collectUsedIndices(days)) {
            PlaceCandidate c = ScheduleTimes.byIndex(candidates, i);
            if (c == null || !c.mealEligible()) continue;
            String kind = CandidatePolicy.cuisineOf(c);
            if (CandidateLookup.isSpecificKind(kind)) kinds.merge(kind, 1, Integer::sum);
        }
        return new AlternativeBuilder.TripView(elsewhere, kinds, ScheduleTuning.mealKindTripCap(daysThroughBlock()),
                usedBrands());
    }

    /**
     * spare를 원본 스냅샷으로 되돌리되, 그 사이 day에 편입된 인덱스는 뺀다.
     * 되돌리지 않으면 이전 패스에서 트림된 장소가 spare에 중복 누적돼 같은 장소가 두 번 채워진다.
     */
    void resetSpare() {
        Set<Integer> used = collectUsedIndices(days);
        spare.clear();
        for (Integer idx : spareBaseline) {
            if (!used.contains(idx) && !spare.contains(idx)) spare.addLast(idx);
        }
    }

    Checkpoint checkpoint() {
        return new Checkpoint(DaySnapshot.of(days), new HashSet<>(usedIndices));
    }

    /** 사용 표시도 되돌려야 교체로 빠졌다 되살아난 장소가 다른 날에 또 들어가지 않는다. */
    void restore(Checkpoint checkpoint) {
        checkpoint.days().restore(days);
        usedIndices.clear();
        usedIndices.addAll(checkpoint.used());
        resetSpare();
    }

    record Checkpoint(DaySnapshot days, Set<Integer> used) {}
}
