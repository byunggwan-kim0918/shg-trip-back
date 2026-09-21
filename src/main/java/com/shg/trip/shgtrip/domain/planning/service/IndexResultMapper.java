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

/**
 * 2-Call LLM 파이프라인 결과를 원본 후보 장소 데이터와 결합하여
 * ItineraryData를 생성한다.
 *
 * Call 1 (Sonnet selectPlaces) → SelectionOutput
 * Call 2 (Haiku assembleItinerary) → AssemblyItineraryOutput
 * 결합 → ItineraryData
 */
@Slf4j
@Component
public class IndexResultMapper {

    /**
     * RouteOptimizer.repairAndSchedule()이 확정한 step 목록(구조 전부 확정, notes=null)에
     * Haiku의 story를 stepOrder 기준으로 병합한다. 구조(day·순서·시간·교통·대안)는 건드리지 않음.
     *
     * @param fixedSteps     백엔드가 확정한 최종 step 목록
     * @param assemblyOutput Call 2 (Haiku) 응답 — title/tags/steps[{stepOrder, story}]
     * @param destination    여행지명 (enrichedInput에서 가져옴, Haiku가 더 이상 생성하지 않음)
     * @return story가 채워진 ItineraryData
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

    /**
     * 구조만 먼저 저장할 때 사용 — story는 비어있고 title/tags는 임시값.
     * Haiku 비동기 단계가 끝나기 전, SSE complete 시점에 구조 일정을 저장하기 위함.
     */
    public ItineraryData toDraftItineraryData(
            List<StepData> fixedSteps, String destination, String concept, List<String> tagSeed) {
        return toDraftItineraryData(fixedSteps, destination, concept, tagSeed, List.of());
    }

    /**
     * @param qualityNotices RouteOptimizer가 끝내 해소하지 못한 품질 문제의 사용자 안내 문구.
     *                       일정과 함께 저장돼 상세 화면에서 노출된다.
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
     * 구조 저장 시점 태그 시드 정규화 — 공백/중복 제거 후 최대 8개(프론트 MAX_TAGS와 동일).
     * story(notes)는 비동기로 채우되 title/tags는 여기서 확정하므로(StorySaveHelper가 덮지 않음),
     * enrich의 searchTags를 태그 시드로 넘겨 AI 생성 일정의 태그가 빈값으로 저장되는 것을 막는다.
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
     * 백엔드가 소유하는 필드(숙소·도착/출발 허브)를 비운다(B3).
     *
     * <p>세 필드는 Tool Use 스키마에서 제거됐지만, 모델이 규약 밖 필드를 보내거나 fallback 경로가
     * 값을 채워 넘길 수 있다. 어느 경우든 뒤따르는 결정론적 보정(fillMissingAccommodation /
     * repairHubs / repairNamedHubs / repairAccommodationByProximity)이 최종 값을 정하므로,
     * 중간 상태에 모델 값이 섞여 판단이 흔들리지 않도록 진입 시점에 한 번 비운다.
     * placeIndices에 잘못 들어온 숙소·허브는 RouteOptimizer가 별도로 정리한다.
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
        return new SelectionOutput(selection.concept(), stripped, selection.pairs(),
                selection.spareIndices(), selection.highlightIndices(), selection.restIndices());
    }

    /**
     * Sonnet이 식당으로 오인해 고른 <b>집합 POI</b>(음식거리·먹자골목처럼 개별 가게가 아니라
     * 구역을 가리키는 장소)를 방문 목록에서 빼고 spare로 돌린다.
     *
     * <p>검색 슬롯은 SQL로, 최종 보정 패스는 {@code mealEligible}로 이미 걸러내지만 선택 결과에는
     * 검사가 없어서, Sonnet이 고르면 그대로 통과해 저녁 시간대를 차지했다(실측 58 day2
     * "Seogwipo's food streets" 18:23). 세 경로가 같은 기준을 보도록 여기서도 같은 판정을 쓴다.
     * 좌표가 구역 중심이라 동선까지 왜곡되므로 활동으로도 남기지 않는다.
     */
    public SelectionOutput stripMealIneligibleAggregates(SelectionOutput selection,
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
                kept.add(idx);
            }
            days.add(new SelectionOutput.DayPlan(d.dayNumber(), d.arrivalHubIndex(), kept,
                    d.accommodationIndex(), d.departureHubIndex()));
        }
        if (!changed) return selection;
        return new SelectionOutput(selection.concept(), days, selection.pairs(), spare,
                selection.highlightIndices(), selection.restIndices());
    }

    /**
     * 마지막 날을 제외한 모든 날에 accommodationIndex가 채워지도록 보정한다.
     * Sonnet이 중간 날 숙소를 누락하는 경우(Tool Use 스키마가 강제하지 않음)를
     * 추가 LLM 호출 없이 코드로 메운다.
     *
     * 1) 직전 날에 배정된 accommodationIndex가 있으면 재사용(연박 가정)
     * 2) 없으면 방문 스텝으로 쓰이지 않은 LODGING 카테고리 후보를 새로 배정
     *
     * <p><b>spare에 있는 숙소도 배정 대상이다.</b> 예전엔 {@link #collectAllUsedIndices}(spare 포함)로
     * 걸러서, 프롬프트가 "남는 숙소는 전부 spareIndices에 넣어라"고 지시한 대로 동작한 Sonnet 출력에서
     * LODGING이 전량 탈락해 숙소 스텝이 0개가 됐다(실측: itinerary 60·66·67). spare는 "메인 스텝으로
     * 쓰지 않음"을 뜻할 뿐이고, 숙소는 애초에 메인 스텝이 아니라 별도 슬롯이라 무관하다.
     *
     * @return 보정된 SelectionOutput (변경 없으면 원본과 동일한 내용의 새 인스턴스)
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
                        // 마지막 안전망 — 방문 스텝으로 쓰인 숙소밖에 없더라도 "숙소 없는 숙박일"보다는 낫다.
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

        // 4-인자 생성자는 highlight/rest를 빈 리스트로 만들어 Sonnet의 서사 가중치를 유실한다 — 보존.
        return new SelectionOutput(selection.concept(), fixedDays, selection.pairs(),
                selection.spareIndices(), selection.highlightIndices(), selection.restIndices());
    }

    /**
     * 사용자 필수 방문 장소(userSelected)가 Sonnet 출력에서 누락된 경우 결정론적으로 주입한다
     * (LLM 재호출 없음). 프롬프트의 ★ 지시는 강한 힌트일 뿐 보장이 아니므로 코드가 최종 보증한다.
     * <ul>
     *   <li>일반 장소: 어느 day의 placeIndices에도 없으면 spare에서 제거하고, day 장소들의
     *       centroid가 가장 가까운 day에 append (좌표 불명이면 장소 수가 가장 적은 day)</li>
     *   <li>LODGING: 어느 day의 accommodationIndex도 아니면 첫 day의 숙소로 교체
     *       (≤3일 여행은 repairAccommodationContinuity가 전 일정으로 전파)</li>
     *   <li>TRANSIT_HUB: 방문 개념이 아니므로 제외</li>
     * </ul>
     */
    public SelectionOutput injectRequiredPlaces(SelectionOutput selection, List<PlaceCandidate> allCandidates) {
        if (selection == null || selection.days() == null || selection.days().isEmpty()) {
            return selection;
        }
        List<PlaceCandidate> required = allCandidates.stream()
                .filter(PlaceCandidate::userSelected)
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
                boolean usedAsAccommodation = days.stream()
                        .anyMatch(d -> Integer.valueOf(idx).equals(d.accommodationIndex()));
                if (!usedAsAccommodation) {
                    // 마지막날(귀가일, accommodationIndex=null)을 제외한 모든 날의 숙소를 사용자
                    // 필수 숙소로 교체한다. 첫날만 바꾸면 4일+ 여행에서 나머지 날이 다른 숙소로
                    // 남는다(repairAccommodationContinuity는 ≤3일만 전파). 밀려난 원래 숙소
                    // 인덱스는 spare로 회수해 대안·트림에서 계속 활용되게 한다(후보 누수 방지).
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
                    // 다른 어디에도 안 쓰인 원래 숙소만 spare로 회수
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
            log.warn("사용자 필수 장소 누락 → day={}에 주입: index={} ({})",
                    target.dayNumber(), idx, cand.name());
        }

        return new SelectionOutput(selection.concept(), days, selection.pairs(), spare,
                selection.highlightIndices(), selection.restIndices());
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

    /**
     * 메인 방문 스텝으로 점유된 인덱스만 모은다 — <b>spare는 제외</b>.
     *
     * <p>{@link #collectAllUsedIndices}는 "한 장소는 전체 일정에서 1회"라는 방문 스텝 유일성을 위한
     * 것이라 spare까지 점유로 센다. 숙소 배정은 다른 질문("이 호텔을 호텔로 쓸 수 있나")이라 그 집합을
     * 쓰면 안 된다 — spare에 있다고 해서 숙소로 못 쓸 이유가 없다.
     */
    private Set<Integer> collectMainStepIndices(SelectionOutput selection) {
        Set<Integer> used = new HashSet<>();
        for (SelectionOutput.DayPlan day : selection.days()) {
            if (day.arrivalHubIndex() != null) used.add(day.arrivalHubIndex());
            if (day.placeIndices() != null) used.addAll(day.placeIndices());
            if (day.departureHubIndex() != null) used.add(day.departureHubIndex());
        }
        return used;
    }

    /** 방문 스텝으로 쓰이지 않은 LODGING 중 평점이 가장 높은 후보. */
    private Integer findUnusedAccommodation(List<PlaceCandidate> allCandidates, Set<Integer> usedIndices) {
        return bestAccommodation(allCandidates.stream()
                .filter(c -> !usedIndices.contains(c.index())));
    }

    /** 점유 여부를 무시한 최선책 LODGING — 숙소 없는 숙박일을 만들지 않기 위한 마지막 수단. */
    private Integer findAnyAccommodation(List<PlaceCandidate> allCandidates) {
        return bestAccommodation(allCandidates.stream());
    }

    private Integer bestAccommodation(java.util.stream.Stream<PlaceCandidate> candidates) {
        return candidates
                .filter(c -> "LODGING".equals(PlaceCategoryConstants.majorCategory(c.category())))
                .max(Comparator.comparing(c -> c.rating() != null ? c.rating() : BigDecimal.ZERO))
                .map(PlaceCandidate::index)
                .orElse(null);
    }

    /**
     * SelectionOutput을 PlaceCandidate flat 목록으로 변환.
     * arrivalHub → placeIndices → accommodationIndex → departureHub 순서대로 단일 리스트로.
     * 또한 spareIndices를 별도로 반환.
     */
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

    /**
     * 후보 인덱스(1-based)로 PlaceCandidate를 조회한다.
     */
    private PlaceCandidate getCandidateByIndex(List<PlaceCandidate> candidates, Integer index) {
        if (index == null || index < 1 || index > candidates.size()) {
            throw new IllegalArgumentException(
                    String.format("Invalid index: %d (valid range: 1-%d)", index, candidates.size()));
        }
        return candidates.get(index - 1);
    }

    /**
     * 플래트닝 결과.
     */
    public static class FlattenedSelection {
        public final List<PlaceCandidate> mainSteps;
        public final List<PlaceCandidate> spareSteps;

        public FlattenedSelection(List<PlaceCandidate> mainSteps, List<PlaceCandidate> spareSteps) {
            this.mainSteps = mainSteps;
            this.spareSteps = spareSteps;
        }
    }
}
