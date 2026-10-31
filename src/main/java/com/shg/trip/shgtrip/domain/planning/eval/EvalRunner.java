package com.shg.trip.shgtrip.domain.planning.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.itinerary.entity.GenerationTrace;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;
import com.shg.trip.shgtrip.domain.itinerary.repository.GenerationTraceRepository;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryRepository;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.ScheduleArgs;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.service.ItineraryScorer;
import com.shg.trip.shgtrip.domain.planning.service.RouteOptimizer;
import com.shg.trip.shgtrip.domain.planning.service.ScoreReport;
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
import java.util.*;

/**
 * <pre>
 * java -jar app.jar --spring.profiles.active=local,eval --eval.mode=replay --eval.ids=181,182
 * </pre>
 * score: 저장본만 채점 / replay: 현재 코드로 다시 돌려 비교 / corpus: 기록 있는 일정 전부 replay
 */
@Slf4j
@Component
@Profile("eval")
@RequiredArgsConstructor
public class EvalRunner implements CommandLineRunner {

    private final ItineraryRepository itineraryRepository;
    private final GenerationTraceRepository traceRepository;
    private final RouteOptimizer routeOptimizer;
    private final ObjectMapper objectMapper;
    private final ApplicationContext context;

    private final ItineraryScorer scorer = new ItineraryScorer();

    @Value("${eval.mode:score}")
    private String mode;

    @Value("${eval.ids:}")
    private String ids;

    @Value("${eval.out:build/eval}")
    private String outDir;

    @Override
    @Transactional(readOnly = true)
    public void run(String... args) throws Exception {
        List<Long> targets = "corpus".equals(mode)
                ? traceRepository.findAll().stream().map(GenerationTrace::getItineraryId).sorted().toList()
                : parseIds(ids);
        boolean replay = !"score".equals(mode);

        List<Row> rows = new ArrayList<>();
        for (Long id : targets) {
            Optional<Itinerary> found = itineraryRepository.findByIdWithDetails(id);
            if (found.isEmpty() || found.get().isDeleted()) {
                log.warn("평가 대상 없음 또는 삭제됨: itineraryId={}", id);
                continue;
            }
            rows.add(evaluate(found.get(), traceRepository.findByItineraryId(id).orElse(null), replay));
        }

        Path dir = Path.of(outDir);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("report.md"), EvalReport.markdown(mode, rows));
        Files.writeString(dir.resolve("report.csv"), EvalReport.csv(rows));
        log.info("평가 완료: mode={}, 일정 {}건 → {}", mode, rows.size(), dir.toAbsolutePath());

        System.exit(SpringApplication.exit(context, () -> 0));
    }

    private Row evaluate(Itinerary itinerary, GenerationTrace trace, boolean replay) throws Exception {
        List<StepData> savedSteps = toStepData(itinerary);
        if (trace == null) {
            ScoreReport saved = scorer.score(new ItineraryScorer.Input(savedSteps, null, null,
                    itinerary.getStartDate(), candidatesFromPlaces(itinerary), null));
            return new Row(itinerary.getId(), itinerary.getDestination(), false, saved, null, null);
        }

        List<PlaceCandidate> candidates = objectMapper.readValue(trace.getCandidates(), new TypeReference<>() {});
        SelectionOutput selection = objectMapper.readValue(trace.getSelectionFinal(), SelectionOutput.class);
        ScheduleArgs args = objectMapper.readValue(trace.getScheduleArgs(), ScheduleArgs.class);

        ScoreReport saved = scorer.score(new ItineraryScorer.Input(savedSteps, args.pace(), args.transportPref(),
                args.startDate(), candidates, selection));
        if (!replay) return new Row(itinerary.getId(), itinerary.getDestination(), true, saved, null, null);

        List<StepData> replayedSteps = routeOptimizer.schedule(selection, candidates, args).steps();
        ScoreReport replayed = scorer.score(new ItineraryScorer.Input(replayedSteps, args.pace(),
                args.transportPref(), args.startDate(), candidates, selection));
        return new Row(itinerary.getId(), itinerary.getDestination(), true, saved, replayed,
                sameRoute(savedSteps, replayedSteps));
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

    private static List<StepData> toStepData(Itinerary itinerary) {
        return itinerary.getSteps().stream()
                .sorted(Comparator.comparingInt(ItineraryStep::getDayNumber)
                        .thenComparingInt(ItineraryStep::getStepOrder))
                .map(s -> {
                    Place p = s.getPlace();
                    PlaceData place = p == null ? null
                            : new PlaceData(p.getName(), p.getAddress(), p.getCategory(), p.getRegion(), p.getCountry());
                    return new StepData(s.getStepOrder(), s.getDayNumber(), s.getStartTime(), s.getEndTime(), place,
                            List.of(), s.getTransportationMode(), s.getTransportationDuration(),
                            s.getTransportationDistance(), s.getTransportationCost(), s.getNotes(),
                            s.getEstimatedCost());
                })
                .toList();
    }

    /** 생성 기록이 없는 옛 일정용. */
    private static List<PlaceCandidate> candidatesFromPlaces(Itinerary itinerary) {
        List<PlaceCandidate> out = new ArrayList<>();
        int index = 1;
        Set<Long> seen = new HashSet<>();
        for (ItineraryStep s : itinerary.getSteps()) {
            Place p = s.getPlace();
            if (p == null || !seen.add(p.getId())) continue;
            out.add(new PlaceCandidate(index++, p.getId(), p.getName(), p.getAddress(), p.getCategory(), p.getTags(),
                    p.getRegion(), p.getCountry(), p.getLatitude(), p.getLongitude(), p.getDescription(),
                    p.getRating(), 0, p.getPriceLevel(), p.getOpeningHours(), p.getRecommendedTimeSlots(),
                    p.getRecommendedDurationMinutes(), false, p.getAdmissionFee(), null, p.getUserRatingCount(),
                    p.getWikidataSitelinks(), p.getPlaceRole(), p.getCuisine()));
        }
        return out;
    }

    private static List<Long> parseIds(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Long::valueOf).toList();
    }

    record Row(Long itineraryId, String destination, boolean hasTrace, ScoreReport saved, ScoreReport replayed,
               Boolean sameRoute) {}
}
