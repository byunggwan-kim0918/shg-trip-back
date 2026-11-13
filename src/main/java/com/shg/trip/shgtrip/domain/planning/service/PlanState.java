package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.ScheduleArgs;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
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
