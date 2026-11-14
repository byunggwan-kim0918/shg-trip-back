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
import com.shg.trip.shgtrip.domain.planning.service.ItineraryRules;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryScorer;
import com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants;
import com.shg.trip.shgtrip.domain.planning.service.RequiredExperiences;
import com.shg.trip.shgtrip.domain.planning.service.RouteOptimizer;
import com.shg.trip.shgtrip.domain.planning.service.SavedItineraryFacts;
import com.shg.trip.shgtrip.domain.planning.service.ScoreReport;
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
 * / files: {@code --eval.files}의 {@link TraceFile} JSON 줄을 DB 없이 replay한다
 * / rejected: 관문에서 막혀 일정 없이 남은 기록을 다시 돌려 rejected-{기록 id}.txt로 남긴다(eval.ids는 기록 id).
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

    @Override
    @Transactional(readOnly = true)
    public void run(String... args) throws Exception {
        if (!Set.of("off", "score", "all").contains(refreshAttributes)) {
            throw new IllegalArgumentException("eval.refresh-attributes는 off|score|all: " + refreshAttributes);
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
            for (String line : Files.readAllLines(Path.of(files))) {
                if (line.isBlank()) continue;
                TraceFile f = TraceFile.parse(objectMapper, line);
                rows.add(evaluateTraced(f.itineraryId(), f.destination(), f.dayCount(), f.savedSteps(),
                        f.candidates(), f.selection(), f.args(), true));
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
        String label = "off".equals(refreshAttributes) ? mode : mode + ", refresh=" + refreshAttributes;
        Files.writeString(dir.resolve("report.md"), EvalReport.markdown(label, rows));
        Files.writeString(dir.resolve("report.csv"), EvalReport.csv(rows));
        log.info("평가 완료: mode={}, refresh={}, 일정 {}건 → {}", mode, refreshAttributes, rows.size(),
                dir.toAbsolutePath());

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
                    saved, null, null, savedRules, null);
        }

        List<PlaceCandidate> traced = objectMapper.readValue(trace.getCandidates(), new TypeReference<>() {});
        SelectionOutput selection = objectMapper.readValue(trace.getSelectionFinal(), SelectionOutput.class);
        ScheduleArgs args = objectMapper.readValue(trace.getScheduleArgs(), ScheduleArgs.class);
        return evaluateTraced(itinerary.getId(), itinerary.getDestination(), dayCount, savedSteps, traced, selection,
                args, replay);
    }

    private Row evaluateTraced(Long itineraryId, String destination, int dayCount, List<StepData> savedSteps,
                               List<PlaceCandidate> traced, SelectionOutput selection, ScheduleArgs args,
                               boolean replay) throws Exception {
        List<PlaceCandidate> candidates = "off".equals(refreshAttributes) ? traced : refreshFacts(traced);
        List<PlaceCandidate> scheduleInput = "all".equals(refreshAttributes) ? candidates : traced;

        Integer hubDeadline = RouteOptimizer.departureHubDeadline(args);
        ItineraryRules.Context ruleContext = new ItineraryRules.Context(args.pace(), args.transportPref(),
                args.startDate(), dayCount, hubDeadline, RequiredExperiences.places(traced));
        String country = args.country() != null ? args.country() : countryOf(savedSteps);
        ScoreReport saved = scorer.score(new ItineraryScorer.Input(savedSteps, args.pace(), args.transportPref(),
                args.startDate(), candidates, selection, hubDeadline));
        ItineraryRules.Verdict savedRules = rules.evaluate(savedSteps, candidates, ruleContext);
        if (!replay) {
            return new Row(itineraryId, destination, country, true, saved, null, null, savedRules, null);
        }

        List<StepData> replayedSteps = routeOptimizer.schedule(selection, scheduleInput, args).steps();
        if (dumpSteps) {
            Path dir = Path.of(outDir);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("steps-" + itineraryId + ".txt"),
                    "== 저장본\n" + renderSteps(savedSteps) + "\n== 재실행\n" + renderSteps(replayedSteps));
        }
        ScoreReport replayed = scorer.score(new ItineraryScorer.Input(replayedSteps, args.pace(),
                args.transportPref(), args.startDate(), candidates, selection, hubDeadline));
        return new Row(itineraryId, destination, country, true, saved, replayed,
                sameRoute(savedSteps, replayedSteps), savedRules, rules.evaluate(replayedSteps, candidates, ruleContext));
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

    record Row(Long itineraryId, String destination, String country, boolean hasTrace, ScoreReport saved,
               ScoreReport replayed, Boolean sameRoute, ItineraryRules.Verdict savedRules,
               ItineraryRules.Verdict replayedRules) {}
}
