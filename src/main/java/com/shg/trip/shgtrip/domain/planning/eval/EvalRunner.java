package com.shg.trip.shgtrip.domain.planning.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.itinerary.entity.GenerationTrace;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.repository.GenerationTraceRepository;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryRepository;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.ScheduleArgs;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.domain.planning.service.CandidatePolicy;
import com.shg.trip.shgtrip.domain.planning.service.CandidateSupplement;
import com.shg.trip.shgtrip.domain.planning.service.GeoCluster;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryScorer;
import com.shg.trip.shgtrip.domain.planning.service.MealKindSwaps;
import com.shg.trip.shgtrip.domain.planning.service.OverCapLegs;
import com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants;
import com.shg.trip.shgtrip.domain.planning.service.PoolLimits;
import com.shg.trip.shgtrip.domain.planning.service.QualityGate;
import com.shg.trip.shgtrip.domain.planning.service.RequiredExperiences;
import com.shg.trip.shgtrip.domain.planning.service.RouteOptimizer;
import com.shg.trip.shgtrip.domain.planning.service.SavedItineraryFacts;
import com.shg.trip.shgtrip.domain.planning.service.ScheduledBy;
import com.shg.trip.shgtrip.domain.planning.service.ScoreReport;
import com.shg.trip.shgtrip.domain.planning.service.SerialPlanner;
import com.shg.trip.shgtrip.domain.planning.service.VectorSearchQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * <pre>
 * java -jar app.jar --spring.profiles.active=local,eval --eval.mode=replay --eval.ids=181,182
 * </pre>
 * score: 저장본만 채점 / replay: 현재 코드로 다시 돌려 비교 / corpus: 기록 있는 일정 전부 replay
 * / pool: 기록의 보강 입력으로 벡터 검색만 다시 돌려 후보 풀을 pool-{id}.tsv로 남긴다(질의 임베딩 호출이 나간다)
 * / files: {@code --eval.files}(쉼표로 여러 개)의 {@link TraceFile} JSON 줄을 DB 없이 replay한다
 * / rejected: 관문에서 막혀 일정 없이 남은 기록을 다시 돌려 rejected-{기록 id}.txt로 남긴다(eval.ids는 기록 id).
 *
 * <p>{@code --eval.pipeline}: 재실행에 쓸 확정 단계. recorded(기본)는 생성 기록이 확정한 단계를 따르고(기록이 없으면 v1),
 * v1은 {@link RouteOptimizer}, v2는 {@link SerialPlanner}다. v2로 확정한 기록은 날짜를 직접 정한 결과라
 * {@code --eval.selection}이 recorded여도 guided로 다시 정한다.
 * {@code --eval.selection}: recorded(기본)는 기록된 선택의 날짜를 그대로 쓴다. guided와 picker는 코드가 날짜를 정하고(v2만),
 * 둘 다 기록된 선택의 요청 의도·거점·숙소·예비 장소를 쓴다. guided는 기록된 선택이 날짜에 놓은 장소를 같은 거리 안에서 먼저 쓴다.
 *
 * <p>{@code --eval.refresh-attributes}: 기록의 후보에 DB의 현재 속성·영업시간을 덮어쓴다.
 * off(기본) 그대로 / score 채점에만 / all 재실행 입력과 채점 모두.
 * 채점 기준이 같아야 휴무·영업시간 위반 수를 비교할 수 있어 속성 연결 효과는 score와 all을 비교한다.
 */
@Slf4j
@Component
@Profile("eval")
@RequiredArgsConstructor
public class EvalRunner implements CommandLineRunner {

    private final ItineraryRepository itineraryRepository;
    private final GenerationTraceRepository traceRepository;
    private final RouteOptimizer routeOptimizer;
    private final PlaceRepository placeRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationContext context;
    private final VectorSearchQueryService vectorSearchQueryService;
    private final CandidateSupplement candidateSupplement;

    private static final Map<String, SerialPlanner.Picks> PICKS_BY_SELECTION = Map.of(
            "guided", SerialPlanner.Picks.PREFER, "picker", SerialPlanner.Picks.IGNORE);

    private final SerialPlanner serialPlanner = new SerialPlanner();
    private final ItineraryScorer scorer = new ItineraryScorer();
    private final ItineraryRules rules = new ItineraryRules();

    @Value("${eval.mode:score}")
    private String mode;

    @Value("${eval.ids:}")
    private String ids;

    @Value("${eval.out:build/eval}")
    private String outDir;

    /** true면 replay한 일정마다 저장본·재실행 스텝을 나란히 steps-{id}.txt로 남긴다. */
    @Value("${eval.dump-steps:false}")
    private boolean dumpSteps;

    @Value("${eval.refresh-attributes:off}")
    private String refreshAttributes;

    @Value("${eval.files:}")
    private String files;

    @Value("${eval.pipeline:recorded}")
    private String pipeline;

    @Value("${eval.selection:recorded}")
    private String selectionSource;

    /**
     * near면 v2 재실행이 생성처럼 날짜를 한 번 정한 뒤 기준 좌표 둘레에서 후보를 보완하고 다시 정한다. 기록된 풀만으로는
     * 보완 검색이 채웠을 날이 비어 보인다. 기록에 요청 문장이 없어 검색 입력은 여행지·나라·지역·이동수단으로 근사한다.
     * 장소 DB와 질의 임베딩이 필요하다.
     */
    @Value("${eval.supplement:off}")
    private String supplement;

    @Override
    @Transactional(readOnly = true)
    public void run(String... args) throws Exception {
        if (!Set.of("off", "score", "all").contains(refreshAttributes)) {
            throw new IllegalArgumentException("eval.refresh-attributes는 off|score|all: " + refreshAttributes);
        }
        if (!Set.of("recorded", "v1", "v2").contains(pipeline)) {
            throw new IllegalArgumentException("eval.pipeline은 recorded|v1|v2: " + pipeline);
        }
        if ("v2".equals(pipeline) && !Set.of("files", "corpus", "replay").contains(mode)) {
            throw new IllegalArgumentException("eval.pipeline=v2는 재실행하는 모드(files|corpus|replay)에서만 쓴다: " + mode);
        }
        if (!PICKS_BY_SELECTION.containsKey(selectionSource) && !"recorded".equals(selectionSource)) {
            throw new IllegalArgumentException("eval.selection은 recorded|guided|picker: " + selectionSource);
        }
        if (!"recorded".equals(selectionSource) && !"v2".equals(pipeline)) {
            throw new IllegalArgumentException("eval.selection=" + selectionSource + "는 eval.pipeline=v2에서만 쓴다");
        }
        if (!Set.of("off", "near").contains(supplement)) {
            throw new IllegalArgumentException("eval.supplement는 off|near: " + supplement);
        }
        if ("near".equals(supplement) && !PICKS_BY_SELECTION.containsKey(selectionSource)) {
            throw new IllegalArgumentException("eval.supplement=near는 날짜를 직접 정하는 eval.selection=guided|picker에서만 쓴다");
        }
        if ("pool".equals(mode)) {
            dumpPools();
            System.exit(SpringApplication.exit(context, () -> 0));
            return;
        }
        if ("rejected".equals(mode)) {
            replayRejected();
            System.exit(SpringApplication.exit(context, () -> 0));
            return;
        }
        List<Row> rows = new ArrayList<>();
        if ("files".equals(mode)) {
            // 기록 파일의 후보는 다른 DB(운영)의 값이라 로컬 DB 값으로 덮으면 섞인다.
            if (!"off".equals(refreshAttributes)) {
                throw new IllegalArgumentException("files 모드는 eval.refresh-attributes=off만 지원한다");
            }
            if (files == null || files.isBlank()) {
                throw new IllegalArgumentException("files 모드는 --eval.files=<TraceFile JSON 줄 파일>이 필요하다");
            }
            Set<Long> seen = new HashSet<>();
            for (String file : files.split(",")) {
                for (String line : Files.readAllLines(Path.of(file.trim()))) {
                    if (line.isBlank()) continue;
                    TraceFile f = TraceFile.parse(objectMapper, line);
                    // 스텝 덤프 파일 이름이 일정 번호라 겹치면 앞 결과를 덮어쓴다.
                    if (!seen.add(f.itineraryId())) {
                        throw new IllegalArgumentException("일정 번호가 여러 번 나온다: " + f.itineraryId());
                    }
                    rows.add(evaluateTraced(f, true));
                }
            }
            writeReport(rows);
            return;
        }
        List<Long> targets = "corpus".equals(mode)
                ? traceRepository.findAll().stream().map(GenerationTrace::getItineraryId).filter(Objects::nonNull).sorted().toList()
                : parseIds(ids);
        boolean replay = !"score".equals(mode);

        for (Long id : targets) {
            Optional<Itinerary> found = itineraryRepository.findByIdWithDetails(id);
            if (found.isEmpty() || found.get().isDeleted()) {
                log.warn("평가 대상 없음 또는 삭제됨: itineraryId={}", id);
                continue;
            }
            rows.add(evaluate(found.get(), traceRepository.findByItineraryId(id).orElse(null), replay));
        }
        writeReport(rows);
    }

    /** 저장본이 없어 채점표 대신 기록의 판정과 지금 코드의 판정·스텝을 나란히 남긴다. 후보는 기록 그대로 쓴다. */
    private void replayRejected() throws Exception {
        Set<Long> only = ids == null || ids.isBlank() ? Set.of() : new HashSet<>(parseIds(ids));
        List<GenerationTrace> traces = traceRepository.findAll().stream()
                .filter(t -> t.getItineraryId() == null && (only.isEmpty() || only.contains(t.getId())))
                .sorted(Comparator.comparing(GenerationTrace::getId))
                .toList();
        Path dir = Path.of(outDir);
        Files.createDirectories(dir);
        for (GenerationTrace t : traces) {
            List<PlaceCandidate> traced = objectMapper.readValue(t.getCandidates(), new TypeReference<>() {});
            SelectionOutput selection = objectMapper.readValue(t.getSelectionFinal(), SelectionOutput.class);
            ScheduleArgs args = objectMapper.readValue(t.getScheduleArgs(), ScheduleArgs.class);
            JsonNode request = objectMapper.readTree(t.getRequest());
            int dayCount = (int) ChronoUnit.DAYS.between(LocalDate.parse(request.path("startDate").asText()),
                    LocalDate.parse(request.path("endDate").asText())) + 1;
            List<StepData> steps = routeOptimizer.schedule(selection, traced, args).steps();
            ItineraryRules.Verdict verdict = rules.evaluate(steps, traced, new ItineraryRules.Context(args.pace(),
                    args.transportPref(), args.startDate(), dayCount, RouteOptimizer.departureHubDeadline(args),
                    RequiredExperiences.places(traced)));
            Files.writeString(dir.resolve("rejected-" + t.getId() + ".txt"),
                    "== 기록 판정\n" + t.getScore() + "\n== 재실행 판정\n" + objectMapper.writeValueAsString(verdict)
                            + "\n== 재실행 스텝\n" + renderSteps(steps));
        }
        log.info("거부 기록 {}건 → {}", traces.size(), dir.toAbsolutePath());
    }

    private void writeReport(List<Row> rows) throws Exception {
        Path dir = Path.of(outDir);
        Files.createDirectories(dir);
        String label = mode + ("off".equals(refreshAttributes) ? "" : ", refresh=" + refreshAttributes)
                + ("recorded".equals(pipeline) ? "" : ", pipeline=" + pipeline)
                + ("recorded".equals(selectionSource) ? "" : ", selection=" + selectionSource)
                + ("off".equals(supplement) ? "" : ", supplement=" + supplement + "(근사 입력)");
        Files.writeString(dir.resolve("report.md"), EvalReport.markdown(label, rows));
        Files.writeString(dir.resolve("report.csv"), EvalReport.csv(rows));
        log.info("평가 완료: mode={}, refresh={}, pipeline={}, selection={}, 일정 {}건 → {}", mode,
                refreshAttributes, pipeline, selectionSource, rows.size(), dir.toAbsolutePath());

        System.exit(SpringApplication.exit(context, () -> 0));
    }

    private Row evaluate(Itinerary itinerary, GenerationTrace trace, boolean replay) throws Exception {
        List<StepData> savedSteps = SavedItineraryFacts.steps(itinerary);
        int dayCount = (int) ChronoUnit.DAYS.between(itinerary.getStartDate(), itinerary.getEndDate()) + 1;
        if (trace == null) {
            List<PlaceCandidate> fromPlaces = SavedItineraryFacts.candidates(itinerary);
            ScoreReport saved = scorer.score(new ItineraryScorer.Input(savedSteps, null, null,
                    itinerary.getStartDate(), fromPlaces, null));
            ItineraryRules.Verdict savedRules = rules.evaluate(savedSteps, fromPlaces,
                    new ItineraryRules.Context(null, null, itinerary.getStartDate(), dayCount, null));
            return new Row(itinerary.getId(), itinerary.getDestination(), countryOf(savedSteps), false,
                    saved, null, null, savedRules, null, Trip.untraced(dayCount), null, null, null, null, null, null, null);
        }

        List<PlaceCandidate> traced = objectMapper.readValue(trace.getCandidates(), new TypeReference<>() {});
        SelectionOutput selection = objectMapper.readValue(trace.getSelectionFinal(), SelectionOutput.class);
        ScheduleArgs args = objectMapper.readValue(trace.getScheduleArgs(), ScheduleArgs.class);
        return evaluateTraced(new TraceFile(itinerary.getId(), itinerary.getDestination(), itinerary.getStartDate(),
                itinerary.getEndDate(), savedSteps, traced, trace.getSearchPoolSize(), selection, args, false,
                trace.getPipeline()), replay);
    }

    private Row evaluateTraced(TraceFile f, boolean replay) throws Exception {
        Long itineraryId = f.itineraryId();
        String destination = f.destination();
        int dayCount = f.dayCount();
        List<StepData> savedSteps = f.savedSteps();
        List<PlaceCandidate> traced = f.candidates();
        SelectionOutput selection = f.selection();
        ScheduleArgs args = f.args();
        List<PlaceCandidate> candidates = "off".equals(refreshAttributes) ? traced : refreshFacts(traced);
        List<PlaceCandidate> scheduleInput = "all".equals(refreshAttributes) ? candidates : traced;

        Integer hubDeadline = RouteOptimizer.departureHubDeadline(args);
        ItineraryRules.Context ruleContext = new ItineraryRules.Context(args.pace(), args.transportPref(),
                args.startDate(), dayCount, hubDeadline, RequiredExperiences.places(traced));
        String country = args.country() != null ? args.country() : countryOf(savedSteps);
        Trip trip = new Trip(dayCount, args.pace(), args.transportPref(), args.cities() != null, f.joined(),
                traced.size(), f.searchPoolSize(), promptZones(traced, f.searchPoolSize(), args));
        boolean hasSaved = !f.joined();
        ScoreReport saved = hasSaved ? scorer.score(new ItineraryScorer.Input(savedSteps, args.pace(),
                args.transportPref(), args.startDate(), candidates, selection, hubDeadline)) : null;
        ItineraryRules.Verdict savedRules = hasSaved ? rules.evaluate(savedSteps, candidates, ruleContext) : null;
        if (!replay) {
            return new Row(itineraryId, destination, country, true, saved, null, null, savedRules, null, trip, null,
                    null, null, null, null, null, null);
        }

        SerialPlanner.Result serial;
        SerialPlanner.Picks picks = picksFor(pipeline, selectionSource, f);
        if (!"v2".equals(pipelineFor(pipeline, f))) {
            serial = null;
        } else if ("near".equals(supplement)) {
            CandidateSupplement.Planned planned = candidateSupplement.planWithReserve(serialPlanner, selection,
                    scheduleInput, args, approximateInput(f, traced), CandidateSupplement.PoolExclusions.none(),
                    dayCount, picks);
            serial = planned.result();
            // 덧붙인 후보도 채점·판정이 이름과 좌표로 찾는다.
            candidates = planned.candidates();
        } else {
            serial = picks == SerialPlanner.Picks.AS_PLACED ? serialPlanner.schedule(selection, scheduleInput, args)
                    : serialPlanner.plan(selection, scheduleInput, args, dayCount, picks);
        }
        SelectionOutput replayedSelection = scoringSelection(selection, serial);
        List<StepData> replayedSteps = serial != null ? serial.steps()
                : routeOptimizer.schedule(selection, scheduleInput, args).steps();
        if (dumpSteps) {
            Path dir = Path.of(outDir);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("steps-" + itineraryId + ".txt"),
                    "== 저장본\n" + renderSteps(savedSteps) + "\n== 재실행\n" + renderSteps(replayedSteps));
        }
        ScoreReport replayed = scorer.score(new ItineraryScorer.Input(replayedSteps, args.pace(),
                args.transportPref(), args.startDate(), candidates, replayedSelection, hubDeadline));
        ItineraryRules.Verdict replayedRules = rules.evaluate(replayedSteps, candidates, ruleContext);
        return new Row(itineraryId, destination, country, true, saved, replayed,
                hasSaved ? sameRoute(savedSteps, replayedSteps) : null, savedRules,
                replayedRules, trip, serial == null ? null : serial.days(),
                OverCapLegs.count(replayedSteps, candidates, args.transportPref(), args.pace(),
                        requestedIndices(selection)), pickUse(selection, candidates, replayedSteps),
                MealKindSwaps.count(replayedSteps, candidates, args.transportPref(), args.pace(), args.startDate()),
                AlternativeStats.of(replayedSteps),
                withDropReasons(ItineraryScorer.missedIntents(replayedSelection, candidates, replayedSteps), serial),
                serial == null ? null : PoolLimits.of(replayedRules, replayedSteps, candidates, ruleContext));
    }

    /** 기록을 따르면 v2로 확정한 기록만 v2로 다시 돌린다. v2가 예외로 끝나거나 저장할 수 없어 v1이 확정한 기록은 v1이다. */
    static String pipelineFor(String pipeline, TraceFile f) {
        if (!"recorded".equals(pipeline)) return pipeline;
        return ScheduledBy.Pipeline.V2.label().equals(f.pipeline()) ? "v2" : "v1";
    }

    // 생성의 v2는 기록된 후보(보완 후보 포함)로 날짜를 직접 정한 결과라 같은 방식으로 다시 정해야 재현된다.
    static SerialPlanner.Picks picksFor(String pipeline, String selectionSource, TraceFile f) {
        if (PICKS_BY_SELECTION.containsKey(selectionSource)) return PICKS_BY_SELECTION.get(selectionSource);
        return "recorded".equals(pipeline) && "v2".equals(pipelineFor(pipeline, f)) ? SerialPlanner.Picks.PREFER
                : SerialPlanner.Picks.AS_PLACED;
    }

    /** 보완 검색 입력. 질의 문구는 기본 문구를 쓰고 지역은 기록된 후보의 지역이다. */
    static VectorEnrichedInput approximateInput(TraceFile f, List<PlaceCandidate> traced) {
        ScheduleArgs args = f.args();
        List<String> regions = traced.stream().map(PlaceCandidate::region).filter(Objects::nonNull).distinct().toList();
        return new VectorEnrichedInput(f.destination(), List.of(), List.of(), args.pace(), args.transportPref(), null,
                f.startDate(), f.endDate(), "", List.of(), f.destination(), args.country(), regions, List.of(), null,
                null, null, null, args.hub(), null, VectorEnrichedInput.CompanionType.UNKNOWN, null, List.of(), null);
    }

    /** 놓친 의도 뒤에 날짜별 확정에서 빠진 날과 이유를 붙인다. 어느 날에도 고르지 않았으면 "안 고름"이다. */
    static List<String> withDropReasons(List<String> missed, SerialPlanner.Result serial) {
        if (serial == null) return missed;
        return missed.stream().map(m -> {
            String name = m.substring(0, m.lastIndexOf(' '));
            String where = serial.days().stream()
                    .flatMap(d -> d.dropped().stream().filter(x -> x.name().equals(name))
                            .map(x -> "d" + d.dayNumber() + " " + x.reason()))
                    .findFirst()
                    .orElseGet(() -> serial.selection().days().stream()
                            .anyMatch(d -> d.placeIndices() != null && !d.placeIndices().isEmpty()) ? "안 고름" : "");
            return where.isEmpty() ? m : m + "(" + where + ")";
        }).toList();
    }

    /** 직접 고른 팔도 기록의 highlight·timing으로 채점해야 의도 점수가 공짜로 오르지 않는다. 날짜별 장소는 재실행이 고른 것이다. */
    static SelectionOutput scoringSelection(SelectionOutput recorded, SerialPlanner.Result serial) {
        return serial == null ? recorded : recorded.withDays(serial.selection().days());
    }

    /**
     * @param placed 기록된 선택이 날짜에 놓은 장소 수
     * @param kept   그 가운데 재실행 일정에 날짜와 상관없이 들어간 수
     */
    record PickUse(int placed, int kept) {}

    static PickUse pickUse(SelectionOutput selection, List<PlaceCandidate> candidates, List<StepData> steps) {
        Set<Long> placedIds = new HashSet<>();
        Set<String> placedNames = new HashSet<>();
        for (StepData step : steps) {
            if (step.place() == null) continue;
            if (step.place().placeId() != null) placedIds.add(step.place().placeId());
            placedNames.add(step.place().name() + "|" + step.place().address());
        }
        Set<Integer> picked = new HashSet<>();
        if (selection.days() != null) {
            selection.days().forEach(d -> picked.addAll(d.placeIndices() == null ? List.of() : d.placeIndices()));
        }
        int kept = 0;
        int counted = 0;
        for (Integer idx : picked) {
            if (idx == null || idx < 1 || idx > candidates.size()) continue;
            PlaceCandidate c = candidates.get(idx - 1);
            if (c == null) continue;
            counted++;
            // 주소 없는 체인은 지점마다 이름이 같아 장소 id가 있으면 id로 본다.
            if (c.placeId() != null ? placedIds.contains(c.placeId())
                    : placedNames.contains(c.name() + "|" + c.address())) {
                kept++;
            }
        }
        return new PickUse(counted, kept);
    }

    /** 요청 의도 장소의 후보 번호. 채점기의 의도 점수가 보는 highlight·timing과 같다. */
    static Set<Integer> requestedIndices(SelectionOutput selection) {
        Set<Integer> out = new HashSet<>(selection.highlightIndices() == null ? List.of() : selection.highlightIndices());
        if (selection.timing() != null) selection.timing().forEach(t -> out.add(t.index()));
        return out;
    }

    private static String countryOf(List<StepData> steps) {
        return steps.stream().map(StepData::place).filter(Objects::nonNull).map(PlaceData::country)
                .filter(Objects::nonNull).findFirst().orElse("?");
    }

    /**
     * eval.files가 있으면 그 파일의 {@code itineraryId}·{@code enrichedInput} 줄을, 없으면 DB 기록(eval.ids가 비면 전부)을 쓴다.
     * 한 줄에 placeId·대분류·역할·유사도·언어판 수·끼니 종류·이름.
     */
    private void dumpPools() throws Exception {
        Map<Long, String> inputs = new LinkedHashMap<>();
        if (files != null && !files.isBlank()) {
            for (String line : Files.readAllLines(Path.of(files))) {
                if (line.isBlank()) continue;
                JsonNode n = objectMapper.readTree(line);
                if (n.hasNonNull("itineraryId") && n.hasNonNull("enrichedInput")) {
                    inputs.put(n.path("itineraryId").asLong(), objectMapper.writeValueAsString(n.get("enrichedInput")));
                }
            }
        } else {
            List<GenerationTrace> traces = ids == null || ids.isBlank() ? traceRepository.findAll()
                    : parseIds(ids).stream().map(traceRepository::findByItineraryId).flatMap(Optional::stream).toList();
            traces.stream().filter(t -> t.getItineraryId() != null && t.getEnrichedInput() != null)
                    .forEach(t -> inputs.put(t.getItineraryId(), t.getEnrichedInput()));
        }
        Path dir = Path.of(outDir);
        Files.createDirectories(dir);
        for (Map.Entry<Long, String> entry : inputs.entrySet()) {
            VectorEnrichedInput input = objectMapper.readValue(entry.getValue(), VectorEnrichedInput.class);
            StringBuilder sb = new StringBuilder();
            for (PlaceCandidate c : vectorSearchQueryService.search(input)) {
                sb.append(String.format(Locale.ROOT, "%s\t%s\t%s\t%.4f\t%s\t%s\t%s%n", c.placeId(),
                        PlaceCategoryConstants.majorCategory(c.category()), CandidatePolicy.roleOf(c),
                        c.similarityScore(), c.wikidataSitelinks(),
                        c.mealEligible() ? CandidatePolicy.cuisineOf(c) : "-", c.name()));
            }
            Files.writeString(dir.resolve("pool-" + entry.getKey() + ".tsv"), sb.toString());
        }
        log.info("후보 풀 {}건 → {}", inputs.size(), dir.toAbsolutePath());
    }

    /** 선택 프롬프트는 도시를 옮기는 여행에 구역을 붙이지 않고, 덧붙이기 전 후보로 구역을 나눈다. */
    private static int promptZones(List<PlaceCandidate> traced, Integer searchPoolSize, ScheduleArgs args) {
        if (args.cities() != null) return 0;
        List<PlaceCandidate> seen = searchPoolSize == null || searchPoolSize > traced.size()
                ? traced : traced.subList(0, searchPoolSize);
        return GeoCluster.of(seen).zones().size();
    }

    // 비용은 저장본에만 인원 배수가 곱해져 있어 비교에서 뺀다
    private static boolean sameRoute(List<StepData> a, List<StepData> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            StepData x = a.get(i), y = b.get(i);
            if (x.dayNumber() != y.dayNumber()
                    || !Objects.equals(x.startTime(), y.startTime())
                    || !Objects.equals(x.place() == null ? null : x.place().name(),
                    y.place() == null ? null : y.place().name())) {
                return false;
            }
        }
        return true;
    }

    private static String renderSteps(List<StepData> steps) {
        StringBuilder sb = new StringBuilder();
        for (StepData s : steps) {
            sb.append(String.format("d%d %s-%s %s | %s | %skm%n", s.dayNumber(), s.startTime(), s.endTime(),
                    s.place() == null ? "-" : s.place().name(),
                    s.place() == null ? "-" : s.place().category(),
                    s.transportationDistance() == null ? "-" : s.transportationDistance().toPlainString()));
        }
        return sb.toString();
    }

    /** DB에 값이 있는 것만 덮어쓴다. 없으면 기록의 값을 그대로 둔다. */
    private List<PlaceCandidate> refreshFacts(List<PlaceCandidate> candidates) {
        Map<Long, Place> places = new HashMap<>();
        placeRepository.findAllById(candidates.stream().map(PlaceCandidate::placeId).filter(Objects::nonNull)
                .distinct().toList()).forEach(p -> places.put(p.getId(), p));
        return candidates.stream().map(c -> {
            Place p = c.placeId() == null ? null : places.get(c.placeId());
            if (p == null) return c;
            return c.withRefreshedFacts(p.getAttributes() != null ? p.getAttributes() : c.attributes(),
                    p.getOpeningHours() != null ? p.getOpeningHours() : c.openingHours());
        }).toList();
    }

    private static List<Long> parseIds(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Long::valueOf).toList();
    }

    /**
     * @param saved      이어 붙인 입력({@link Trip#joined})은 저장본이 없어 null이다
     * @param serialDays v2로 재실행했을 때의 날짜별 결과. 아니면 null
     * @param overCap    재실행 결과의 구간 상한 초과 분류. 재실행하지 않았으면 null
     * @param pickUse    기록된 선택이 재실행 일정에 반영된 정도. 재실행하지 않았으면 null
     * @param mealSwaps  재실행 결과의 끼니 종류 반복 분류. 재실행하지 않았으면 null
     * @param alts       재실행 결과의 대안 상태. 재실행하지 않았으면 null
     * @param missedIntents 재실행 결과가 지키지 못한 요청 의도. 재실행하지 않았으면 null
     * @param poolLimits 재실행 결과의 풀 탓 핵심 위반. 생성과 같이 v2로 재실행했을 때만 있고 아니면 null
     */
    record Row(Long itineraryId, String destination, String country, boolean hasTrace, ScoreReport saved,
               ScoreReport replayed, Boolean sameRoute, ItineraryRules.Verdict savedRules,
               ItineraryRules.Verdict replayedRules, Trip trip, List<SerialPlanner.DayReport> serialDays,
               OverCapLegs.Count overCap, PickUse pickUse, MealKindSwaps.Count mealSwaps,
               AlternativeStats alts, List<String> missedIntents, List<QualityGate.PoolLimit> poolLimits) {

        /** 현재 코드의 결과. 재실행하지 않았으면 저장본이다. */
        ScoreReport current() {
            return replayed != null ? replayed : saved;
        }

        ItineraryRules.Verdict currentRules() {
            return replayedRules != null ? replayedRules : savedRules;
        }
    }

    /**
     * @param joined     기록 두 건을 이어 붙여 만든 입력
     * @param pool       일정 확정에 쓴 후보 수(덧붙인 숙소·예비 후보 포함). 기록이 없으면 0
     * @param searchPool 선택 모델이 본 후보 수. 기록에 없으면 null
     * @param zones      선택 프롬프트에 나간 구역 수. 좁은 여행지와 도시를 옮기는 여행은 0.
     *                   {@code searchPool}을 모르는 기록은 덧붙인 후보까지 넣어 센다
     */
    record Trip(int days, String pace, String transport, boolean multiCity, boolean joined, int pool,
                Integer searchPool, int zones) {

        static Trip untraced(int days) {
            return new Trip(days, null, null, false, false, 0, null, 0);
        }
    }
}
