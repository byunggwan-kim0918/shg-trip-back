package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.destination.KrwRates;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.ScheduleArgs;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.TransportationHub;
import com.shg.trip.shgtrip.domain.planning.dto.TripCities;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 먼 도시를 오가는 여행을 도시 구간마다 따로 확정해 잇는다. 스케줄러는 하루 동선 안에서만 판단해 도시 사이 이동을
 * 모르므로, 구간마다 그 도시 후보만 주고 구간 경계(도착 허브 없음·다음 도시로 이어짐)를 알린다.
 */
@Slf4j
public final class CityBlockPlanner {

    @FunctionalInterface
    interface BlockScheduler {
        RouteOptimizer.ScheduleResult schedule(SelectionOutput selection, List<PlaceCandidate> candidates,
                                               ScheduleArgs args);
    }

    /** 도시 하나에 머무는 연속된 날. */
    public record Block(String city, int firstDay, int lastDay) {
        boolean contains(int day) {
            return day >= firstDay && day <= lastDay;
        }
    }

    /** 도시 간 이동 한 번. 역·공항을 오가는 시간까지 포함한 소요 시간이다. */
    public record IntercityLeg(String mode, int minutes, BigDecimal distanceKm, BigDecimal costWon) {}

    private static final ScheduleTuning.IntercityMode FALLBACK_MODE =
            new ScheduleTuning.IntercityMode("TRAIN", 160, 45, 0, null);
    private static final int LATEST_TRANSFER_ARRIVAL_MINUTES = 23 * 60;

    private CityBlockPlanner() {}

    static RouteOptimizer.ScheduleResult schedule(SelectionOutput selection, List<PlaceCandidate> candidates,
                                                  ScheduleArgs args, BlockScheduler scheduler) {
        TripCities cities = args.cities();
        List<Block> blocks = blocksOf(cities);
        if (blocks.size() < 2 || selection.days() == null) {
            return scheduler.schedule(selection, candidates, args.forCityBlock(args.hub(), args.arrivalTime(),
                    args.departureTime(), false, false));
        }
        Map<Integer, String> cityOfIndex = new HashMap<>();
        for (PlaceCandidate c : candidates) {
            if (c != null && c.latitude() != null && c.longitude() != null
                    && !GeoUtils.isZeroCoord(c.latitude(), c.longitude())) {
                cityOfIndex.put(c.index(), cities.cityOf(c.latitude().doubleValue(), c.longitude().doubleValue()));
            }
        }
        Map<Integer, List<Integer>> placesByDay = placesByBlockDay(selection, blocks, cityOfIndex);

        List<StepData> steps = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        StringBuilder route = new StringBuilder();
        List<BlockPlan> plans = blockPlans(args);
        for (int b = 0; b < plans.size(); b++) {
            BlockPlan plan = plans.get(b);
            Block block = plan.block();
            IntercityLeg leg = plan.leg();
            boolean first = b == 0;
            boolean last = b == plans.size() - 1;

            BlockInput input = blockInput(selection, candidates, block, placesByDay, cityOfIndex, first, last);
            RouteOptimizer.ScheduleResult result = scheduler.schedule(input.selection(), input.candidates(), plan.args());
            unresolved.addAll(result.unresolved());
            steps.addAll(leg == null ? result.steps() : withLegOnFirstStep(result.steps(), leg));
            route.append(first ? "" : " → ").append(block.city()).append(' ')
                    .append(block.firstDay()).append('~').append(block.lastDay()).append("일차");
            if (leg != null) route.append(String.format("(%s %d분)", leg.mode(), leg.minutes()));
        }
        log.info("도시별 일정: {}", route);
        return new RouteOptimizer.ScheduleResult(renumber(steps), unresolved.stream().distinct().toList());
    }

    /**
     * @param args 그 구간만 확정할 인자. 도착 거점·도착 시각은 첫 구간에만, 출발 거점·출발 시각은 마지막 구간에만 있다
     * @param leg  앞 도시에서 넘어오는 이동. 첫 구간은 null
     */
    public record BlockPlan(Block block, ScheduleArgs args, IntercityLeg leg) {}

    /** 도시 구간마다 그 구간의 인자와 넘어오는 이동을 일차 순서대로 준다. 일차별 도시를 모르면 비어 있다. */
    public static List<BlockPlan> blockPlans(ScheduleArgs args) {
        TripCities cities = args.cities();
        List<Block> blocks = blocksOf(cities);
        if (blocks.isEmpty()) return List.of();
        Map<String, TripCities.City> cityByName = cities.cities().stream()
                .collect(Collectors.toMap(TripCities.City::name, Function.identity(), (a, b) -> b));
        List<BlockPlan> plans = new ArrayList<>();
        for (int b = 0; b < blocks.size(); b++) {
            Block block = blocks.get(b);
            boolean first = b == 0;
            boolean last = b == blocks.size() - 1;
            IntercityLeg leg = first ? null
                    : legBetween(cityByName.get(blocks.get(b - 1).city()), cityByName.get(block.city()));
            plans.add(new BlockPlan(block, args.forCityBlock(blockHub(args.hub(), first, last),
                    first ? args.arrivalTime() : ScheduleTimes.formatMinutes(arrivalAfter(leg)),
                    last ? args.departureTime() : null, !first, !last), leg));
        }
        return plans;
    }

    /** 일차별 도시에서 이어진 구간. 일차 순서대로다. */
    public static List<Block> blocksOf(TripCities cities) {
        if (cities == null || cities.dayCity().isEmpty()) return List.of();
        List<Integer> dayNumbers = cities.dayCity().keySet().stream().sorted().toList();
        List<Block> blocks = new ArrayList<>();
        String city = null;
        int start = 0;
        int prev = 0;
        for (int day : dayNumbers) {
            String c = cities.dayCity().get(day);
            if (!c.equals(city)) {
                if (city != null) blocks.add(new Block(city, start, prev));
                city = c;
                start = day;
            }
            prev = day;
        }
        blocks.add(new Block(city, start, prev));
        return blocks;
    }

    /** 도시 중심 사이 이동. 직선거리를 이동 수단 속도로 나누고 역·공항을 오가는 시간을 더한다. */
    public static IntercityLeg legBetween(TripCities.City from, TripCities.City to) {
        double km = GeoUtils.haversine(from.lat(), from.lng(), to.lat(), to.lng());
        ScheduleTuning.IntercityMode mode = ScheduleTuning.INTERCITY_MODES.getOrDefault(to.country(), FALLBACK_MODE);
        int minutes = (int) Math.ceil(km / mode.kmh() * 60) + mode.overheadMinutes();
        Integer won = KrwRates.toWon((int) Math.round(km * mode.farePerKm()), mode.currency());
        return new IntercityLeg(mode.mode(), minutes,
                BigDecimal.valueOf(km * GeoUtils.ROAD_DISTANCE_FACTOR).setScale(2, RoundingMode.HALF_UP),
                won == null ? null : BigDecimal.valueOf(won));
    }

    /** 이동한 날 다음 도시에서 일정을 시작할 수 있는 시각(분). */
    public static int arrivalAfter(IntercityLeg leg) {
        return Math.min(LATEST_TRANSFER_ARRIVAL_MINUTES, ScheduleTuning.INTERCITY_DEPARTURE_MINUTES + leg.minutes());
    }

    /** 도착 허브는 첫 구간에만, 출발 허브는 마지막 구간에만 둔다. */
    private static TransportationHub blockHub(TransportationHub hub, boolean first, boolean last) {
        if (hub == null) return null;
        return new TransportationHub(first ? hub.arrivalHub() : null, last ? hub.departureHub() : null, hub.hubType());
    }

    /**
     * 모델이 고른 장소를 일차 → 장소 목록으로 모은다. 다른 도시 장소는 그 도시 구간에서 장소가 가장 적은 날로 옮긴다.
     * 좌표가 없어 도시를 모르는 장소는 모델이 둔 날에 남는다.
     */
    private static Map<Integer, List<Integer>> placesByBlockDay(SelectionOutput selection, List<Block> blocks,
                                                                Map<Integer, String> cityOfIndex) {
        Map<Integer, List<Integer>> byDay = new LinkedHashMap<>();
        for (Block block : blocks) {
            for (int day = block.firstDay(); day <= block.lastDay(); day++) byDay.put(day, new ArrayList<>());
        }
        List<Integer> strays = new ArrayList<>();
        for (SelectionOutput.DayPlan plan : selection.days()) {
            if (plan == null || plan.placeIndices() == null) continue;
            Block block = blockOfDay(blocks, plan.dayNumber());
            for (Integer idx : plan.placeIndices()) {
                if (idx == null) continue;
                String city = cityOfIndex.get(idx);
                if (city == null || city.equals(block.city())) {
                    byDay.get(Math.max(block.firstDay(), Math.min(block.lastDay(), plan.dayNumber()))).add(idx);
                } else {
                    strays.add(idx);
                }
            }
        }
        for (Integer idx : strays) {
            String city = cityOfIndex.get(idx);
            blocks.stream().filter(b -> b.city().equals(city)).findFirst().ifPresent(block -> {
                int lightest = block.firstDay();
                for (int day = block.firstDay(); day <= block.lastDay(); day++) {
                    if (byDay.get(day).size() < byDay.get(lightest).size()) lightest = day;
                }
                byDay.get(lightest).add(idx);
            });
        }
        return byDay;
    }

    private static Block blockOfDay(List<Block> blocks, int day) {
        for (Block block : blocks) {
            if (block.contains(day)) return block;
        }
        return day < blocks.get(0).firstDay() ? blocks.get(0) : blocks.get(blocks.size() - 1);
    }

    private record BlockInput(SelectionOutput selection, List<PlaceCandidate> candidates) {}

    /** 구간 도시의 후보만 1번부터 다시 번호를 매기고 선택 결과의 인덱스를 그 번호로 바꾼다. */
    private static BlockInput blockInput(SelectionOutput selection, List<PlaceCandidate> candidates, Block block,
                                         Map<Integer, List<Integer>> placesByDay, Map<Integer, String> cityOfIndex,
                                         boolean first, boolean last) {
        Set<Integer> placedHere = new java.util.HashSet<>();
        for (int day = block.firstDay(); day <= block.lastDay(); day++) placedHere.addAll(placesByDay.get(day));

        Map<Integer, Integer> local = new HashMap<>();
        List<PlaceCandidate> blockCandidates = new ArrayList<>();
        for (PlaceCandidate c : candidates) {
            if (c == null) continue;
            String city = cityOfIndex.get(c.index());
            boolean belongs = city != null ? city.equals(block.city()) : placedHere.contains(c.index());
            if (!belongs) continue;
            local.put(c.index(), blockCandidates.size() + 1);
            blockCandidates.add(c.reindexed(blockCandidates.size() + 1));
        }

        Map<Integer, SelectionOutput.DayPlan> planOf = selection.days().stream().filter(Objects::nonNull)
                .collect(Collectors.toMap(SelectionOutput.DayPlan::dayNumber, Function.identity(), (a, b) -> a));
        List<SelectionOutput.DayPlan> days = new ArrayList<>();
        for (int day = block.firstDay(); day <= block.lastDay(); day++) {
            SelectionOutput.DayPlan plan = planOf.get(day);
            Integer arrival = first && day == block.firstDay() && plan != null ? local.get(plan.arrivalHubIndex()) : null;
            Integer departure = last && day == block.lastDay() && plan != null
                    ? local.get(plan.departureHubIndex()) : null;
            Integer lodging = plan != null ? local.get(plan.accommodationIndex()) : null;
            days.add(new SelectionOutput.DayPlan(day, arrival, mapAll(placesByDay.get(day), local), lodging, departure));
        }

        List<List<Integer>> pairs = selection.pairs() == null ? List.of() : selection.pairs().stream()
                .filter(p -> p != null && !p.isEmpty() && p.stream().allMatch(local::containsKey))
                .map(p -> mapAll(p, local))
                .toList();
        List<SelectionOutput.TimingHint> timing = selection.timing() == null ? List.of() : selection.timing().stream()
                .filter(t -> t != null && local.containsKey(t.index()))
                .map(t -> new SelectionOutput.TimingHint(local.get(t.index()), t.when()))
                .toList();
        SelectionOutput blockSelection = new SelectionOutput(selection.concept(), days, pairs,
                mapAll(selection.spareIndices(), local), mapAll(selection.highlightIndices(), local),
                mapAll(selection.restIndices(), local), timing);
        return new BlockInput(blockSelection, blockCandidates);
    }

    private static List<Integer> mapAll(List<Integer> indices, Map<Integer, Integer> local) {
        if (indices == null) return List.of();
        return indices.stream().filter(Objects::nonNull).map(local::get).filter(Objects::nonNull).toList();
    }

    /** 구간 첫 스텝의 이동 정보를 도시 간 이동으로 채운다. 스케줄러는 전 도시 숙소를 몰라 비워 둔다. */
    private static List<StepData> withLegOnFirstStep(List<StepData> blockSteps, IntercityLeg leg) {
        if (blockSteps.isEmpty()) return blockSteps;
        StepData head = blockSteps.stream()
                .min(Comparator.comparingInt(StepData::dayNumber).thenComparingInt(StepData::stepOrder))
                .orElseThrow();
        List<StepData> out = new ArrayList<>(blockSteps.size());
        for (StepData s : blockSteps) {
            out.add(s != head ? s : new StepData(s.stepOrder(), s.dayNumber(), s.startTime(), s.endTime(),
                    s.place(), s.alternatives(), leg.mode(), leg.minutes(), leg.distanceKm(), leg.costWon(),
                    s.notes(), s.estimatedCost()));
        }
        return out;
    }

    private static List<StepData> renumber(List<StepData> steps) {
        List<StepData> sorted = new ArrayList<>(steps);
        sorted.sort(Comparator.comparingInt(StepData::dayNumber).thenComparingInt(StepData::stepOrder));
        List<StepData> out = new ArrayList<>(sorted.size());
        int order = 1;
        for (StepData s : sorted) {
            out.add(new StepData(order++, s.dayNumber(), s.startTime(), s.endTime(), s.place(), s.alternatives(),
                    s.transportationMode(), s.transportationDuration(), s.transportationDistance(),
                    s.transportationCost(), s.notes(), s.estimatedCost()));
        }
        return out;
    }
}
