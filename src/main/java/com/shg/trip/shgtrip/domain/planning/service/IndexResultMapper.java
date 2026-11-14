package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.*;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 인덱스만 참조하는 LLM 선택 결과를 원본 후보 장소 데이터와 결합한다. */
@Slf4j
@Component
public class IndexResultMapper {

    /**
     * 확정된 step 목록에 Haiku story를 stepOrder 기준으로 병합한다. 구조(day·순서·시간·교통·대안)는 바꾸지 않는다.
     *
     * @param destination 여행지명. Haiku 응답에는 없으므로 enrichedInput 값을 쓴다.
     */
    public ItineraryData mergeStory(List<StepData> fixedSteps, AssemblyItineraryOutput assemblyOutput,
                                     String destination) {
        if (fixedSteps == null) {
            throw new IllegalArgumentException("fixedSteps must not be null");
        }
        if (assemblyOutput == null) {
            throw new IllegalArgumentException("AssemblyItineraryOutput must not be null");
        }

        Map<Integer, String> storyByOrder = new HashMap<>();
        if (assemblyOutput.steps() != null) {
            for (AssemblyItineraryOutput.StoryStep s : assemblyOutput.steps()) {
                storyByOrder.put(s.stepOrder(), s.story());
            }
        }

        List<StepData> merged = new ArrayList<>(fixedSteps.size());
        for (StepData step : fixedSteps) {
            String story = storyByOrder.get(step.stepOrder());
            if (story == null) {
                log.warn("stepOrder={} 에 대한 story가 응답에 없음", step.stepOrder());
            }
            merged.add(new StepData(
                    step.stepOrder(), step.dayNumber(), step.startTime(), step.endTime(),
                    step.place(), step.alternatives(), step.transportationMode(),
                    step.transportationDuration(), step.transportationDistance(), step.transportationCost(),
                    story, step.estimatedCost()
            ));
        }

        BigDecimal totalCost = fixedSteps.stream()
                .map(StepData::estimatedCost)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new ItineraryData(assemblyOutput.title(), destination, totalCost, assemblyOutput.tags(), merged);
    }

    /** Haiku story 전에 구조만 저장할 때 쓴다. story는 비어 있고 title은 concept 임시값이다. */
    public ItineraryData toDraftItineraryData(
            List<StepData> fixedSteps, String destination, String concept, List<String> tagSeed) {
        return toDraftItineraryData(fixedSteps, destination, concept, tagSeed, List.of());
    }

    /**
     * @param qualityNotices 저장 전 미리보기 안내. 저장 관문이 저장할 일정 기준으로 다시 만든다.
     */
    public ItineraryData toDraftItineraryData(
            List<StepData> fixedSteps, String destination, String concept, List<String> tagSeed,
            List<String> qualityNotices) {
        BigDecimal totalCost = fixedSteps.stream()
                .map(StepData::estimatedCost)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ItineraryData(concept, destination, totalCost, normalizeTags(tagSeed), fixedSteps,
                qualityNotices != null ? qualityNotices : List.of());
    }

    /**
     * 최대 8개는 프론트 MAX_TAGS와 맞춘다. StorySaveHelper가 tags를 덮지 않으므로 여기서 정한 값이 최종이다.
     */
    private List<String> normalizeTags(List<String> raw) {
        if (raw == null) return List.of();
        return raw.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .distinct()
                .limit(8)
                .toList();
    }

    /**
     * 백엔드가 소유하는 필드(숙소·도착/출발 허브)를 비운다. 모델이나 fallback 경로가 값을 채워 보내도
     * 뒤따르는 결정론적 보정이 최종 값을 정하므로, 중간 판단에 모델 값이 섞이지 않게 한다.
     */
    public SelectionOutput stripCodeOwnedFields(SelectionOutput selection) {
        if (selection == null || selection.days() == null || selection.days().isEmpty()) {
            return selection;
        }
        List<SelectionOutput.DayPlan> stripped = new ArrayList<>(selection.days().size());
        for (SelectionOutput.DayPlan d : selection.days()) {
            stripped.add(new SelectionOutput.DayPlan(d.dayNumber(), null,
                    d.placeIndices() != null ? d.placeIndices() : List.of(), null, null));
        }
        return selection.withDays(stripped);
    }

    /**
     * Sonnet이 고른 집합 POI(음식거리 등)와 행정·지정 구역 이름을 방문 목록에서 빼고 spare로 돌린다.
     * 좌표가 구역 중심이라 동선까지 왜곡된다. 주소 길이 규칙으로 걸면 서울·오사카 후보 절반이 날아가므로
     * {@link PlaceCategoryConstants#isAdministrativeZone}만 쓴다.
     */
    public SelectionOutput stripZoneLevelPicks(SelectionOutput selection,
                                                        List<PlaceCandidate> allCandidates) {
        if (selection == null || selection.days() == null || selection.days().isEmpty()) {
            return selection;
        }
        List<SelectionOutput.DayPlan> days = new ArrayList<>(selection.days().size());
        List<Integer> spare = new ArrayList<>(
                selection.spareIndices() != null ? selection.spareIndices() : List.of());
        boolean changed = false;

        for (SelectionOutput.DayPlan d : selection.days()) {
            List<Integer> kept = new ArrayList<>();
            for (Integer idx : d.placeIndices() != null ? d.placeIndices() : List.<Integer>of()) {
                PlaceCandidate c = idx != null && idx >= 1 && idx <= allCandidates.size()
                        ? allCandidates.get(idx - 1) : null;
                if (c != null && PlaceCategoryConstants.isAggregatePoi(c.name(), c.category())) {
                    if (!spare.contains(idx)) spare.add(idx);
                    changed = true;
                    log.info("집합 POI 선택 제외: day={} {} (개별 가게가 아니라 구역)", d.dayNumber(), c.name());
                    continue;
                }
                if (c != null && PlaceCategoryConstants.isAdministrativeZone(c.name())) {
                    if (!spare.contains(idx)) spare.add(idx);
                    changed = true;
                    log.info("지정 구역 선택 제외: day={} {} (개별 방문지가 아니라 구역명)",
                            d.dayNumber(), c.name());
                    continue;
                }
                kept.add(idx);
            }
            days.add(new SelectionOutput.DayPlan(d.dayNumber(), d.arrivalHubIndex(), kept,
                    d.accommodationIndex(), d.departureHubIndex()));
        }
        if (!changed) return selection;
        return selection.withDaysAndSpare(days, spare);
    }

    /**
     * 마지막 날을 제외한 모든 날에 accommodationIndex를 채운다. Tool Use 스키마가 강제하지 않아 Sonnet이 누락한다.
     * 프롬프트가 남는 숙소를 spare에 넣으라고 하므로 spare 숙소도 배정 대상이어야 숙소가 0개가 되지 않는다.
     */
    public SelectionOutput fillMissingAccommodation(SelectionOutput selection, List<PlaceCandidate> allCandidates) {
        if (selection == null || selection.days() == null || selection.days().isEmpty()) {
            return selection;
        }

        int lastDay = selection.days().stream()
                .mapToInt(SelectionOutput.DayPlan::dayNumber)
                .max().orElse(0);

        Set<Integer> usedIndices = collectMainStepIndices(selection);

        List<SelectionOutput.DayPlan> fixedDays = new ArrayList<>();
        Integer lastKnownAccommodation = null;

        for (SelectionOutput.DayPlan day : selection.days()) {
            Integer accomIdx = day.accommodationIndex();

            if (accomIdx == null && day.dayNumber() != lastDay) {
                if (lastKnownAccommodation != null) {
                    accomIdx = lastKnownAccommodation;
                    log.warn("day={} accommodationIndex 누락 → 직전 숙소(index={}) 재사용으로 보정",
                            day.dayNumber(), accomIdx);
                } else {
                    accomIdx = findUnusedAccommodation(allCandidates, usedIndices);
                    if (accomIdx != null) {
                        usedIndices.add(accomIdx);
                        log.info("day={} 숙소 자동 배정: index={}", day.dayNumber(), accomIdx);
                    } else {
                        // 방문 스텝과 겹치더라도 숙소 없는 숙박일보다는 낫다.
                        accomIdx = findAnyAccommodation(allCandidates);
                        if (accomIdx != null) {
                            log.warn("day={} 미사용 LODGING 없음 → 방문 스텝과 중복이지만 최선책 배정: index={}",
                                    day.dayNumber(), accomIdx);
                        } else {
                            log.error("day={} 숙소 배정 불가 — 후보 풀에 LODGING이 0개", day.dayNumber());
                        }
                    }
                }
                fixedDays.add(new SelectionOutput.DayPlan(
                        day.dayNumber(), day.arrivalHubIndex(), day.placeIndices(), accomIdx, day.departureHubIndex()));
            } else {
                fixedDays.add(day);
            }

            if (accomIdx != null) {
                lastKnownAccommodation = accomIdx;
            }
        }

        // 필드를 나열해 다시 만들면 highlight/rest/timing이 유실된다.
        return selection.withDays(fixedDays);
    }

    /**
     * Sonnet 출력에서 누락된 사용자 필수 장소와 필수 경험 장소({@link PlaceCandidate#keepsPlace()})를 주입한다.
     * 프롬프트의 ★ 지시는 보장이 아니라서 코드가 보증한다.
     * 일반 장소는 centroid가 가장 가까운 day에, LODGING은 귀가일을 뺀 전 일정 숙소로 넣는다.
     */
    public SelectionOutput injectRequiredPlaces(SelectionOutput selection, List<PlaceCandidate> allCandidates) {
        if (selection == null || selection.days() == null || selection.days().isEmpty()) {
            return selection;
        }
        List<PlaceCandidate> required = allCandidates.stream()
                .filter(PlaceCandidate::keepsPlace)
                .filter(c -> !"TRANSIT_HUB".equals(PlaceCategoryConstants.majorCategory(c.category())))
                .toList();
        if (required.isEmpty()) return selection;

        List<SelectionOutput.DayPlan> days = new ArrayList<>();
        for (SelectionOutput.DayPlan d : selection.days()) {
            days.add(new SelectionOutput.DayPlan(d.dayNumber(), d.arrivalHubIndex(),
                    new ArrayList<>(d.placeIndices() != null ? d.placeIndices() : List.of()),
                    d.accommodationIndex(), d.departureHubIndex()));
        }
        List<Integer> spare = new ArrayList<>(
                selection.spareIndices() != null ? selection.spareIndices() : List.of());

        for (PlaceCandidate cand : required) {
            int idx = cand.index();
            boolean isLodging = "LODGING".equals(PlaceCategoryConstants.majorCategory(cand.category()));

            if (isLodging) {
                // 숙소 교체는 사용자가 예약한 숙소만 한다. 필수 경험 매칭은 숙소를 받지 않는다.
                if (!cand.userSelected()) continue;
                boolean usedAsAccommodation = days.stream()
                        .anyMatch(d -> Integer.valueOf(idx).equals(d.accommodationIndex()));
                if (!usedAsAccommodation) {
                    // repairAccommodationContinuity는 3일 이하만 전파하므로 첫날만 바꾸면 4일 이상 여행에서 숙소가 섞인다.
                    // 밀려난 숙소는 spare로 회수해 대안·트림에서 계속 쓰이게 한다.
                    Set<Integer> displaced = new HashSet<>();
                    for (int i = 0; i < days.size(); i++) {
                        SelectionOutput.DayPlan d = days.get(i);
                        if (d.accommodationIndex() == null) continue; // 귀가일
                        if (Integer.valueOf(idx).equals(d.accommodationIndex())) continue;
                        displaced.add(d.accommodationIndex());
                        days.set(i, new SelectionOutput.DayPlan(d.dayNumber(), d.arrivalHubIndex(),
                                d.placeIndices(), idx, d.departureHubIndex()));
                    }
                    spare.remove(Integer.valueOf(idx));
                    Set<Integer> stillUsed = collectAllUsedIndices(
                            new SelectionOutput(selection.concept(), days, selection.pairs(), spare));
                    for (Integer old : displaced) {
                        if (!stillUsed.contains(old) && !spare.contains(old)) spare.add(old);
                    }
                    log.warn("사용자 필수 숙소 누락 → 전 일정 숙소로 교체 주입: index={} ({})", idx, cand.name());
                }
                continue;
            }

            boolean included = days.stream().anyMatch(d -> d.placeIndices().contains(idx));
            if (included) {
                spare.remove(Integer.valueOf(idx)); // 메인·spare 중복 기재 정리
                continue;
            }

            SelectionOutput.DayPlan target = pickNearestDay(days, allCandidates, cand);
            target.placeIndices().add(idx);
            spare.remove(Integer.valueOf(idx));
            log.warn("{} 누락 → day={}에 주입: index={} ({})", cand.userSelected() ? "사용자 필수 장소" : "필수 경험 장소",
                    target.dayNumber(), idx, cand.name());
        }

        return selection.withDaysAndSpare(days, spare);
    }

    /** 필수 장소와 day centroid가 가장 가까운 day. 좌표 불명이면 장소 수가 가장 적은 day. */
    private SelectionOutput.DayPlan pickNearestDay(List<SelectionOutput.DayPlan> days,
                                                   List<PlaceCandidate> allCandidates,
                                                   PlaceCandidate cand) {
        double[] target = coordsOf(cand);
        if (target != null) {
            SelectionOutput.DayPlan best = null;
            double bestDist = Double.MAX_VALUE;
            for (SelectionOutput.DayPlan d : days) {
                double[] centroid = centroidOf(d.placeIndices(), allCandidates);
                if (centroid == null) continue;
                double dist = GeoUtils.haversine(centroid, target);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = d;
                }
            }
            if (best != null) return best;
        }
        return days.stream()
                .min(Comparator.comparingInt(d -> d.placeIndices().size()))
                .orElse(days.get(0));
    }

    private double[] coordsOf(PlaceCandidate c) {
        if (c == null || c.latitude() == null || c.longitude() == null) return null;
        if (c.latitude().signum() == 0 && c.longitude().signum() == 0) return null;
        return new double[]{c.latitude().doubleValue(), c.longitude().doubleValue()};
    }

    private double[] centroidOf(List<Integer> indices, List<PlaceCandidate> allCandidates) {
        double lat = 0, lng = 0;
        int n = 0;
        for (Integer idx : indices) {
            if (idx == null || idx < 1 || idx > allCandidates.size()) continue;
            double[] coord = coordsOf(allCandidates.get(idx - 1)); // index는 1-based 위치(byIndex 규약)
            if (coord == null) continue;
            lat += coord[0];
            lng += coord[1];
            n++;
        }
        return n == 0 ? null : new double[]{lat / n, lng / n};
    }

    private Set<Integer> collectAllUsedIndices(SelectionOutput selection) {
        Set<Integer> used = new HashSet<>();
        for (SelectionOutput.DayPlan day : selection.days()) {
            if (day.arrivalHubIndex() != null) used.add(day.arrivalHubIndex());
            if (day.placeIndices() != null) used.addAll(day.placeIndices());
            if (day.accommodationIndex() != null) used.add(day.accommodationIndex());
            if (day.departureHubIndex() != null) used.add(day.departureHubIndex());
        }
        if (selection.spareIndices() != null) used.addAll(selection.spareIndices());
        return used;
    }

    /** spare는 제외한다. 숙소 배정에 {@link #collectAllUsedIndices}를 쓰면 spare 숙소가 전부 탈락한다. */
    private Set<Integer> collectMainStepIndices(SelectionOutput selection) {
        Set<Integer> used = new HashSet<>();
        for (SelectionOutput.DayPlan day : selection.days()) {
            if (day.arrivalHubIndex() != null) used.add(day.arrivalHubIndex());
            if (day.placeIndices() != null) used.addAll(day.placeIndices());
            if (day.departureHubIndex() != null) used.add(day.departureHubIndex());
        }
        return used;
    }

    private Integer findUnusedAccommodation(List<PlaceCandidate> allCandidates, Set<Integer> usedIndices) {
        return bestAccommodation(allCandidates.stream()
                .filter(c -> !usedIndices.contains(c.index())));
    }

    private Integer findAnyAccommodation(List<PlaceCandidate> allCandidates) {
        return bestAccommodation(allCandidates.stream());
    }

    private Integer bestAccommodation(java.util.stream.Stream<PlaceCandidate> candidates) {
        return candidates
                .filter(c -> "LODGING".equals(PlaceCategoryConstants.majorCategory(c.category())))
                // 리뷰 없는 평점 5.0 스텁이 실제 호텔을 이기지 못하게 한다.
                .filter(c -> PlaceCategoryConstants.isVisitableEntity(c.name(), c.address(), c.country()))
                // 주소 게이트를 통과한 스텁도 있어 평점이 아니라 인기도(평점 × 리뷰 수)로 고른다.
                .max(Comparator.<PlaceCandidate>comparingDouble(PlaceProminence::of)
                        .thenComparing(c -> c.rating() != null ? c.rating() : BigDecimal.ZERO))
                .map(PlaceCandidate::index)
                .orElse(null);
    }

    /** 날마다 arrivalHub → placeIndices → accommodation → departureHub 순으로 펼친다. */
    public FlattenedSelection flattenSelection(SelectionOutput selection,
                                              List<PlaceCandidate> allCandidates) {
        List<PlaceCandidate> mainSteps = new ArrayList<>();

        for (SelectionOutput.DayPlan dayPlan : selection.days()) {
            if (dayPlan.arrivalHubIndex() != null) {
                mainSteps.add(getCandidateByIndex(allCandidates, dayPlan.arrivalHubIndex()));
            }

            if (dayPlan.placeIndices() != null) {
                for (Integer placeIndex : dayPlan.placeIndices()) {
                    mainSteps.add(getCandidateByIndex(allCandidates, placeIndex));
                }
            }

            if (dayPlan.accommodationIndex() != null) {
                mainSteps.add(getCandidateByIndex(allCandidates, dayPlan.accommodationIndex()));
            }

            if (dayPlan.departureHubIndex() != null) {
                mainSteps.add(getCandidateByIndex(allCandidates, dayPlan.departureHubIndex()));
            }
        }

        List<PlaceCandidate> spareSteps = (selection.spareIndices() != null)
                ? selection.spareIndices().stream()
                    .map(idx -> getCandidateByIndex(allCandidates, idx))
                    .collect(Collectors.toList())
                : List.of();

        return new FlattenedSelection(mainSteps, spareSteps);
    }

    /** index는 1-based다. */
    private PlaceCandidate getCandidateByIndex(List<PlaceCandidate> candidates, Integer index) {
        if (index == null || index < 1 || index > candidates.size()) {
            throw new IllegalArgumentException(
                    String.format("Invalid index: %d (valid range: 1-%d)", index, candidates.size()));
        }
        return candidates.get(index - 1);
    }

    public static class FlattenedSelection {
        public final List<PlaceCandidate> mainSteps;
        public final List<PlaceCandidate> spareSteps;

        public FlattenedSelection(List<PlaceCandidate> mainSteps, List<PlaceCandidate> spareSteps) {
            this.mainSteps = mainSteps;
            this.spareSteps = spareSteps;
        }
    }
}
