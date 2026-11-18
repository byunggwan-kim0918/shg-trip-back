package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.DraftEdit;
import com.shg.trip.shgtrip.domain.planning.dto.DraftPlan;
import com.shg.trip.shgtrip.domain.planning.dto.TripConditions;
import com.shg.trip.shgtrip.domain.planning.service.ai.DraftCallGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 여행 한 건을 짓는다. Sonnet이 여행 전체의 흐름과 사용자 요구를 초안으로 쓰고, 코드가 실제 장소·끼니·동선을 맞추고,
 * 하루의 취지가 깨졌을 때만 Sonnet이 그날을 고친다. 사용자가 요구한 것은 끝까지 지키고 모델이 추천한 것은 바꿀 수 있다.
 */
@Slf4j
@Component
@Profile("eval")
@RequiredArgsConstructor
public class TripPlanner {

    /** 고칠 곳을 모델에게 보일 검사 항목. 사실 위반과 페이스·빈 시간처럼 하루의 취지가 깨진 것이다. */
    private static final Set<String> INTENT_BROKEN = Set.of("PACE_SHORT", "LONG_GAP");

    private final DraftCallGenerator draftCallGenerator;
    private final TripSkeletonPlanner skeletonPlanner;
    private final DraftItineraryPlanner planner;
    private final ItineraryVerifier verifier;

    /**
     * @param violations 최종 일정이 사용자 요구·사실을 어긴 것
     * @param dayNotes   날마다 여행자에게 보일 이유 문장
     * @param tripNotes  여행 전체에 대해 여행자에게 알릴 것(지킬 수 없는 요구와 바꿀 방향)
     */
    public record Built(TripSkeletonPlanner.Skeleton skeleton, DraftItineraryPlanner.Result result,
                        List<ItineraryVerifier.Violation> violations, Map<Integer, List<String>> dayNotes,
                        List<String> tripNotes) {
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
        TripSkeletonPlanner.Skeleton skeleton = skeletonPlanner.build(base.with(drafted.plan(), Map.of(), Set.of()));
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
        // 고칠 곳이 없다는 답이면 다시 짓지 않는다. 기록을 다시 짓는 build()도 편집이 없으면 첫 일정을 쓴다.
        Built finalBuilt = edited.edits().isEmpty() ? built
                : assemble(skeleton, planner.applyEdits(skeleton.request(), first, edited.edits()));
        return new Planned(drafted.plan(), problems, edited.edits(), finalBuilt,
                drafted.inputTokens() + edited.inputTokens(), drafted.outputTokens() + edited.outputTokens());
    }

    /** 모델을 부르지 않고 기록된 초안과 편집으로 일정을 다시 짓고 검사한다. */
    public Built build(DraftItineraryPlanner.Request base, DraftPlan draft, List<DraftEdit> edits) {
        TripSkeletonPlanner.Skeleton skeleton = skeletonPlanner.build(base.with(draft, Map.of(), Set.of()));
        DraftItineraryPlanner.Result result = planner.plan(skeleton.request());
        if (edits != null && !edits.isEmpty()) result = planner.applyEdits(skeleton.request(), result, edits);
        return assemble(skeleton, result);
    }

    private Built assemble(TripSkeletonPlanner.Skeleton skeleton, DraftItineraryPlanner.Result result) {
        DraftItineraryPlanner.Request request = skeleton.request();
        List<ItineraryVerifier.Violation> violations = verifier.verify(skeleton.conditions(),
                request.input().startDate(), result.frames().size(), result.steps(), result.candidates(),
                request.cityNames());
        return new Built(skeleton, result, violations, DraftItineraryPlanner.travelerNotes(result),
                tripNotes(skeleton, result, violations));
    }

    /** 편집을 부를 이유. 지킬 수 없는 요구(조건 충돌)는 모델이 고칠 수 없으니 넣지 않는다. */
    static List<String> editProblems(Built built) {
        List<String> out = new ArrayList<>();
        for (ItineraryVerifier.Violation v : built.violations()) {
            if (v.severity() == ItineraryVerifier.Severity.FATAL || INTENT_BROKEN.contains(v.code())) {
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
                                          List<ItineraryVerifier.Violation> violations) {
        List<String> out = new ArrayList<>(skeleton.conflicts());
        for (ItineraryVerifier.Violation v : violations) {
            if ("DAILY_TRANSIT".equals(v.code())) {
                out.add(v.day() + "일차는 이동 시간이 깁니다(" + v.message().replaceAll("^.* (\\d+)분$", "$1") + "분). 들르는 곳들이 서로 멀어 줄이지 못했습니다.");
                continue;
            }
            if (!"WANT_MISSING".equals(v.code())) continue;
            TripConditions.Want want = skeleton.conditions().wants().stream()
                    .filter(w -> v.message().contains("'" + w.text() + "'")).findFirst().orElse(null);
            if (want == null) continue;
            String noFood = want.food() == null ? null : "요청 음식 '" + want.food() + "'";
            String why = noFood != null && result.notes().stream().anyMatch(n -> n.contains(noFood))
                    ? " 여행지에서 그 음식을 낸다고 메뉴로 확인되는 식당을 찾지 못했습니다." : "";
            out.add("요청하신 '" + want.text() + "'은(는) 일정에 넣지 못했습니다." + why);
        }
        return out;
    }
}
