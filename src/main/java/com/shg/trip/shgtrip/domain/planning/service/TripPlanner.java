package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.repository.PlaceFactsReader;
import com.shg.trip.shgtrip.domain.planning.dto.DraftEdit;
import com.shg.trip.shgtrip.domain.planning.dto.DraftPlan;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.TripConditions;
import com.shg.trip.shgtrip.domain.planning.service.ai.DraftCallGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 여행 한 건을 짓는다. Sonnet이 여행 전체의 흐름과 사용자 요구를 초안으로 쓰고, 코드가 실제 장소·끼니·동선을 맞추고,
 * 하루의 취지가 깨졌을 때만 Sonnet이 그날을 고친다. 사용자가 요구한 것은 끝까지 지키고 모델이 추천한 것은 바꿀 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TripPlanner {

    private static final ItineraryRules RULES = new ItineraryRules();

    private final DraftCallGenerator draftCallGenerator;
    private final TripSkeletonPlanner skeletonPlanner;
    private final DraftItineraryPlanner planner;
    private final PlaceFactsReader factsReader;

    /**
     * @param context    판정 입력. 저장 관문과 편집 재판정이 같은 입력으로 다시 판정한다
     * @param facts      판정에 쓴 후보 장소의 사실
     * @param violations 최종 일정이 사용자 요구·사실을 어긴 것
     * @param dayNotes   날마다 여행자에게 보일 이유 문장
     * @param tripNotes  여행 전체에 대해 여행자에게 알릴 것(지킬 수 없는 요구와 바꿀 방향)
     */
    public record Built(TripSkeletonPlanner.Skeleton skeleton, DraftItineraryPlanner.Result result,
                        ItineraryRules.Context context, Map<Long, PlaceFactsReader.PlaceFact> facts,
                        List<ItineraryRules.Violation> violations,
                        Map<Integer, List<String>> dayNotes, List<String> tripNotes) {
    }

    /**
     * @param problems 편집을 부른 이유. 비어 있으면 편집하지 않았다
     */
    public record Planned(DraftPlan draft, List<String> problems, List<DraftEdit> edits, Built built,
                          long inputTokens, long outputTokens) {
    }

    /** 초안을 받고, 하루의 취지가 깨졌으면 한 번 고친다. 모델 호출은 초안(형식이 틀리면 한 번 더)과 편집 한 번이다. */
    public Planned plan(DraftItineraryPlanner.Request base, String prompt) {
        return finish(base, prompt, draft(base, prompt));
    }

    /** 모델이 쓴 초안. 짓기 전에 받아 두면 짓는 단계가 실패해도 비용을 낸 초안이 남는다. */
    public DraftCallGenerator.Drafted draft(DraftItineraryPlanner.Request base, String prompt) {
        int days = (int) (base.input().endDate().toEpochDay() - base.input().startDate().toEpochDay()) + 1;
        return draftCallGenerator.draft(prompt, days);
    }

    /**
     * 초안으로 일정을 짓고 필요하면 한 번 고친다. 편집은 모델이 본 그 일정에 반영한다. 다시 지으면 근처 검색 결과가 달라져
     * 모델이 적은 장소 이름이 일정에 없을 수 있다.
     */
    public Planned finish(DraftItineraryPlanner.Request base, String prompt, DraftCallGenerator.Drafted drafted) {
        TripSkeletonPlanner.Skeleton skeleton = skeletonPlanner.build(base.withDraft(drafted.plan()));
        DraftItineraryPlanner.Result first = planner.plan(skeleton.request());
        Built built = assemble(skeleton, first);
        List<String> problems = editProblems(built);
        if (problems.isEmpty()) {
            return new Planned(drafted.plan(), problems, List.of(), built, drafted.inputTokens(),
                    drafted.outputTokens());
        }
        DraftCallGenerator.Edited edited;
        try {
            edited = draftCallGenerator.edits(prompt, DraftItineraryPlanner.timeline(first), problems);
        } catch (RuntimeException e) {
            log.warn("일정 편집 실패, 초안 일정을 쓴다: {}", e.getMessage());
            return new Planned(drafted.plan(), problems, List.of(), built, drafted.inputTokens(),
                    drafted.outputTokens());
        }
        Built finalBuilt = edited.edits().isEmpty() ? built
                : better(built, assemble(skeleton, planner.applyEdits(skeleton.request(), first, edited.edits())));
        return new Planned(drafted.plan(), problems, edited.edits(), finalBuilt,
                drafted.inputTokens() + edited.inputTokens(), drafted.outputTokens() + edited.outputTokens());
    }

    /**
     * 모델을 부르지 않고 기록된 초안과 편집으로 일정을 다시 짓고 검사한다. 운영과 같이 첫 일정에 지킬 수 없는 위반이
     * 있을 때만 편집을 반영하고, 반영한 일정이 나을 때만 쓴다.
     */
    public Built build(DraftItineraryPlanner.Request base, DraftPlan draft, List<DraftEdit> edits) {
        TripSkeletonPlanner.Skeleton skeleton = skeletonPlanner.build(base.withDraft(draft));
        DraftItineraryPlanner.Result first = planner.plan(skeleton.request());
        Built built = assemble(skeleton, first);
        if (edits == null || edits.isEmpty() || editProblems(built).isEmpty()) return built;
        return better(built, assemble(skeleton, planner.applyEdits(skeleton.request(), first, edits)));
    }

    /**
     * 편집 모델은 고칠 곳만 보고 다른 날의 요구를 옮기기도 한다. 지킬 수 없는 위반이 줄고 채운 요구가 줄지 않을 때만
     * 편집한 일정을 쓴다.
     */
    static Built better(Built first, Built edited) {
        Intent before = intentOf(first);
        Intent after = intentOf(edited);
        boolean better = after.hard() < before.hard() && after.wantsMet() >= before.wantsMet();
        log.info("편집안 비교: 지킬 수 없음 {} → {}, 채운 요구 {}/{} → {}/{}, 첫 일정 장소 유지 {}, {}", before.hard(),
                after.hard(), before.wantsMet(), before.wants(), after.wantsMet(), after.wants(),
                String.format("%.2f", keptShare(first, edited)), better ? "편집안" : "첫 일정");
        return better ? edited : first;
    }

    /**
     * 일정이 사용자 의도를 얼마나 지켰는지. 편집 전후를 이 값으로 견준다.
     *
     * @param wants    요구 수
     * @param wantsMet 일정이 채운 요구 수
     * @param hard     지킬 수 없는 위반 수
     */
    public record Intent(int wants, int wantsMet, int hard) {
    }

    public static Intent intentOf(Built built) {
        int wants = built.context() == null || built.context().conditions() == null ? 0
                : built.context().conditions().wants().size();
        long missed = built.violations().stream().filter(v -> v.rule() == ItineraryRules.Rule.WANT_MISSING
                || v.rule() == ItineraryRules.Rule.WANT_FOOD_MISSING || v.rule() == ItineraryRules.Rule.WANT_NOT_IN_DATA)
                .count();
        int hard = (int) built.violations().stream().filter(v -> v.severity() == ItineraryRules.Severity.HARD).count();
        return new Intent(wants, (int) Math.max(0, wants - missed), hard);
    }

    /** 첫 일정의 장소 가운데 편집한 일정에도 남은 비율. 편집이 고칠 곳 밖까지 일정을 갈아엎었는지 본다. */
    private static double keptShare(Built first, Built edited) {
        java.util.Set<Long> after = new java.util.HashSet<>();
        edited.result().steps().forEach(s -> {
            if (s.place() != null && s.place().placeId() != null) after.add(s.place().placeId());
        });
        List<Long> before = first.result().steps().stream().filter(s -> s.place() != null && s.place().placeId() != null)
                .map(s -> s.place().placeId()).distinct().toList();
        return before.isEmpty() ? 1.0 : before.stream().filter(after::contains).count() / (double) before.size();
    }

    private Built assemble(TripSkeletonPlanner.Skeleton skeleton, DraftItineraryPlanner.Result result) {
        ItineraryRules.Context context = ruleContext(skeleton, result);
        Map<Long, PlaceFactsReader.PlaceFact> facts = factsReader.find(placeIds(result.candidates()));
        List<ItineraryRules.Violation> violations = RULES.evaluate(result.steps(), result.candidates(), context,
                facts).violations();
        return new Built(skeleton, result, context, facts, violations, DraftItineraryPlanner.travelerNotes(result),
                tripNotes(skeleton, result, violations));
    }

    /** 출발 허브 기한은 허브로 끝나는 여행에만 둔다. 이른 종료 판정이 그 기한으로 출발 때문에 일찍 끝난 날을 가린다. */
    static ItineraryRules.Context ruleContext(TripSkeletonPlanner.Skeleton skeleton,
                                              DraftItineraryPlanner.Result result) {
        DraftItineraryPlanner.Request request = skeleton.request();
        Integer deadline = null;
        List<StepData> steps = result.steps();
        if (request.departure() != null && !steps.isEmpty()) {
            Long id = steps.get(steps.size() - 1).place() == null ? null
                    : steps.get(steps.size() - 1).place().placeId();
            PlaceCandidate hub = id == null ? null : result.candidates().stream()
                    .filter(c -> id.equals(c.placeId())).findFirst().orElse(null);
            if (hub != null && CandidatePolicy.roleOf(hub) == CandidatePolicy.Role.TRANSIT) {
                deadline = TripHubs.departureDeadline(request.departure(), hub, false, request.country());
            }
        }
        List<ItineraryRules.City> cities = request.cities().stream()
                .map(c -> new ItineraryRules.City(c.nameKo(), c.lat(), c.lng())).toList();
        return new ItineraryRules.Context(request.input().pace(), request.input().transportPref(),
                request.input().startDate(), result.frames().size(), deadline, Map.of(), skeleton.conditions(),
                request.country(), cities);
    }

    private static List<Long> placeIds(List<PlaceCandidate> candidates) {
        return candidates.stream().map(PlaceCandidate::placeId).filter(Objects::nonNull).distinct().toList();
    }

    /** 편집은 한 번뿐이라 지킬 수 없는 위반만 넘긴다. 덜 좋은 것까지 넘기면 모델이 그것을 고치느라 요구를 옮긴다. */
    static List<String> editProblems(Built built) {
        List<String> out = new ArrayList<>();
        for (ItineraryRules.Violation v : built.violations()) {
            if (v.severity() == ItineraryRules.Severity.HARD) {
                out.add(v.message() + (v.fix() == null ? "" : " (고칠 방향: " + v.fix() + ")"));
            }
        }
        if (out.isEmpty()) return out;
        for (String n : built.result().notes()) {
            if (n.contains("찾지 못함") || n.contains("피할 조건")) out.add(n);
        }
        return out;
    }

    /** 사용자가 요구했지만 일정에 없는 것과 그 이유. 요구를 조용히 빼지 않고 무엇을 바꾸면 되는지 알린다. */
    private static List<String> tripNotes(TripSkeletonPlanner.Skeleton skeleton, DraftItineraryPlanner.Result result,
                                          List<ItineraryRules.Violation> violations) {
        List<String> out = new ArrayList<>(skeleton.conflicts());
        for (ItineraryRules.Violation v : violations) {
            if (v.rule() == ItineraryRules.Rule.DAILY_TRANSIT) {
                out.add(v.day() + "일차는 이동 시간이 길어요(" + v.message().replaceAll("^.* (\\d+)분$", "$1")
                        + "분). 들르는 곳들이 서로 멀어 줄이지 못했어요.");
                continue;
            }
            if (v.rule() != ItineraryRules.Rule.WANT_MISSING && v.rule() != ItineraryRules.Rule.WANT_FOOD_MISSING
                    && v.rule() != ItineraryRules.Rule.WANT_NOT_IN_DATA) {
                continue;
            }
            if (v.rule() == ItineraryRules.Rule.WANT_NOT_IN_DATA) {
                out.add("요청하신 '" + v.evidence().get(0) + "'은(는) 아직 업데이트가 되지 않아 일정에 넣지 못했어요.");
                continue;
            }
            TripConditions.Want want = skeleton.conditions().wants().stream()
                    .filter(w -> v.message().contains("'" + w.text() + "'")).findFirst().orElse(null);
            if (want == null) continue;
            String noFood = want.food() == null ? null : "요청 음식 '" + want.food() + "'";
            String why = noFood != null && result.notes().stream().anyMatch(n -> n.contains(noFood))
                    ? " 여행지에서 그 음식을 낸다고 메뉴로 확인되는 식당을 찾지 못했어요." : "";
            out.add("요청하신 '" + want.text() + "'은(는) 일정에 넣지 못했어요." + why);
        }
        return out;
    }
}
