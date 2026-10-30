package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.AlternativeData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.TransportationHub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * {@link RouteOptimizer}의 <b>골든 마스터(characterization) 테스트</b>.
 *
 * <p>RouteOptimizer는 주입 의존성이 없는 순수 계산기다(생성자 없음, {@code private final} 필드 0개).
 * 그래서 같은 입력에 항상 같은 출력이 나오고, <b>출력 전체를 파일로 고정</b>할 수 있다.
 *
 * <p>기존 {@code RouteOptimizerTest}의 71개 테스트는 개별 <i>성질</i>만 본다("점심이 있다",
 * "21시를 안 넘는다"). 그 성질을 전부 만족하면서도 일정 구성이 통째로 달라지는 변경은 잡지 못한다.
 * 구조 리팩터링(상수 분리·타입 외부화·검사기 분리)은 <b>동작이 한 글자도 바뀌면 안 되는</b>
 * 작업이므로, 성질 테스트만으로는 안전망이 부족하다. 이 테스트가 그 간극을 메운다.
 *
 * <h2>스냅샷 갱신</h2>
 * <pre>{@code ./gradlew test --tests "*RouteOptimizerGoldenMasterTest" -Dgolden.update=true}</pre>
 * 갱신 후 <b>반드시 diff를 눈으로 확인</b>한다 — 의도한 동작 변경만 통과시키기 위한 장치다.
 * 의도 없이 갱신하면 이 테스트는 아무것도 지켜주지 않는다.
 */
class RouteOptimizerGoldenMasterTest {

    /**
     * 클래스패스({@code build/resources/test})가 아니라 소스 트리를 직접 읽고 쓴다 —
     * {@code -Dgolden.update=true}가 빌드 산출물이 아닌 <b>커밋 대상 파일</b>을 갱신해야 하기 때문이다.
     * Gradle이 테스트 작업 디렉터리를 프로젝트 루트로 잡으므로 이 상대 경로가 성립한다.
     */
    private static final Path SNAPSHOT_DIR =
            Path.of("src/test/resources/golden/route-optimizer");

    private final RouteOptimizer routeOptimizer = new RouteOptimizer();

    /**
     * 한 시나리오의 입력 전체. {@code schedule()}의 9개 인자를 그대로 담는다 —
     * 인자 하나가 빠지면 그만큼 고정되지 않는 동작이 생긴다.
     */
    record Scenario(String name,
                    SelectionOutput selection,
                    List<PlaceCandidate> candidates,
                    String pace,
                    String transportPref,
                    LocalDate startDate,
                    List<String> themes,
                    TransportationHub hub,
                    boolean compactMode,
                    List<String> userCategories) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Scenario> scenarios() {
        return Stream.of(
                // 제주 2박3일 — 숙소가 전부 spare에만 있는 실측 입력(60·66·67 재발 케이스)
                new Scenario("jeju-3d-lodging-in-spare",
                        Itinerary67Fixture.selectionWithLodgingOnlyInSpare(),
                        Itinerary67Fixture.candidates(),
                        "normal", "car", LocalDate.of(2026, 5, 11),
                        List.of("로맨틱"), null, false, List.of("nature", "food")),

                // 같은 후보 풀, relaxed 페이스 — 페이스에 따른 밀도·버킷 배분을 고정한다
                new Scenario("jeju-3d-relaxed",
                        Itinerary67Fixture.selectionWithLodgingOnlyInSpare(),
                        Itinerary67Fixture.candidates(),
                        "relaxed", "car", LocalDate.of(2026, 5, 11),
                        List.of(), null, false, List.of()),

                // 도보/버스 우선 — 거리 예산 배수가 달라 트림 지점이 바뀐다
                new Scenario("jeju-3d-walk",
                        Itinerary67Fixture.selectionWithLodgingOnlyInSpare(),
                        Itinerary67Fixture.candidates(),
                        "normal", "walk", LocalDate.of(2026, 5, 11),
                        List.of(), null, false, List.of()),

                // 허브 이름이 주어진 경우 — 허브 교정 분기를 고정한다
                new Scenario("jeju-3d-with-hub",
                        Itinerary67Fixture.selectionWithLodgingOnlyInSpare(),
                        Itinerary67Fixture.candidates(),
                        "normal", "car", LocalDate.of(2026, 5, 11),
                        List.of(), new TransportationHub("제주국제공항", "제주국제공항", "AIRPORT"),
                        false, List.of()),

                // itinerary 40 실측 풀(31개) — 경유형 반복·하루 196km·식사 원정을 재현하는 입력.
                // 67 풀(20개)보다 넓어서 유형 상한·커버리지·거리 예산 수리가 실제로 동작한다.
                new Scenario("jeju-40-coastal-drive",
                        itinerary40Selection(),
                        Itinerary40Fixture.candidates(),
                        "normal", "car", LocalDate.of(2026, 5, 11),
                        List.of("ocean"), null, false, List.of("nature")),

                // 같은 풀, compactMode — 최소 밀도로 떨어지는 경로
                new Scenario("jeju-40-compact",
                        itinerary40Selection(),
                        Itinerary40Fixture.candidates(),
                        "normal", "car", LocalDate.of(2026, 5, 11),
                        List.of(), null, true, List.of()),

                // 같은 풀, startDate 없음 — 정기휴무 회피를 건너뛰는 하위 호환 경로
                new Scenario("jeju-40-no-startdate",
                        itinerary40Selection(),
                        Itinerary40Fixture.candidates(),
                        "relaxed", "any", null,
                        List.of("야경"), null, false, List.of()),

                // itinerary 115 재구성 풀(46개) — 맛집 커플 3박4일. 흑돼지 반복·카페 트림·
                // 숙소→점심 원정·등산 오후 갭필·우도·게스트하우스·마지막날 공백을 재현한다.
                new Scenario("jeju-115-food-couple",
                        Itinerary115Fixture.selection(),
                        Itinerary115Fixture.candidates(),
                        "normal", "car", LocalDate.of(2026, 11, 1),
                        List.of("food", "romance"),
                        new TransportationHub("제주국제공항", "제주국제공항", "AIRPORT"),
                        false, List.of("restaurant"))
        );
    }

    /** 40번과 같은 구성: day별로 해안도로가 여러 개 섞인 3일 일정. */
    private static SelectionOutput itinerary40Selection() {
        return new SelectionOutput(
                "제주 해안을 따라가는 드라이브",
                List.of(
                        new SelectionOutput.DayPlan(1, null, List.of(2, 3, 4, 5, 6), 7, null),
                        new SelectionOutput.DayPlan(2, null, List.of(8, 9, 10, 11), 7, null),
                        new SelectionOutput.DayPlan(3, null, List.of(12, 13, 14, 15), null, null)
                ),
                List.of(),
                List.of(16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31),
                List.of(), List.of()
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    @DisplayName("schedule() 출력이 고정 스냅샷과 완전히 일치한다")
    void scheduleMatchesSnapshot(Scenario scenario) throws IOException {
        RouteOptimizer.ScheduleResult result = routeOptimizer.schedule(
                scenario.selection(), scenario.candidates(), scenario.pace(),
                scenario.transportPref(), scenario.startDate(), scenario.themes(),
                scenario.hub(), scenario.compactMode(), scenario.userCategories());

        String actual = render(result);
        Path snapshot = SNAPSHOT_DIR.resolve(scenario.name() + ".txt");

        if (Boolean.getBoolean("golden.update") || !Files.exists(snapshot)) {
            Files.createDirectories(SNAPSHOT_DIR);
            Files.writeString(snapshot, actual, StandardCharsets.UTF_8);
            if (!Boolean.getBoolean("golden.update")) {
                fail("스냅샷이 없어 새로 기록했습니다: %s%n내용을 확인한 뒤 커밋하고 다시 실행하세요."
                        .formatted(snapshot));
            }
            return;
        }

        String expected = Files.readString(snapshot, StandardCharsets.UTF_8);
        assertThat(actual)
                .as("""
                        %s 의 schedule() 출력이 스냅샷과 다릅니다.
                        구조 리팩터링 중이라면 이건 회귀입니다 — 되돌리세요.
                        의도한 동작 변경이라면 -Dgolden.update=true 로 갱신하고 diff를 리뷰하세요.""",
                        scenario.name())
                .isEqualTo(expected);
    }

    /**
     * 결과를 사람이 diff로 읽을 수 있는 형태로 직렬화한다.
     *
     * <p>JSON 대신 고정폭 텍스트를 쓰는 이유는 스냅샷이 깨졌을 때 <b>무엇이 어떻게 달라졌는지</b>가
     * 한눈에 보여야 하기 때문이다. 필드는 {@link StepData} 전체를 덮는다 — 하나라도 빠지면
     * 그 필드의 회귀는 잡히지 않는다.
     */
    private static String render(RouteOptimizer.ScheduleResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(renderMetrics(result));
        int currentDay = -1;

        for (StepData step : result.steps()) {
            if (step.dayNumber() != currentDay) {
                currentDay = step.dayNumber();
                sb.append("== day ").append(currentDay).append(" ==\n");
            }
            sb.append(String.format("  #%-2d %s-%s  %s%n",
                    step.stepOrder(), step.startTime(), step.endTime(), placeOf(step)));
            sb.append(String.format("      이동: %s %s분 %skm %s원%n",
                    nz(step.transportationMode()), nz(step.transportationDuration()),
                    money(step.transportationDistance()), money(step.transportationCost())));
            sb.append(String.format("      비용: %s원 / 메모: %s%n",
                    money(step.estimatedCost()), nz(step.notes())));
            for (AlternativeData alt : nvl(step.alternatives())) {
                sb.append(String.format("      대안: %s | %s | %s원%n",
                        nz(alt.name()), nz(alt.category()), money(alt.estimatedCost())));
            }
        }

        sb.append("-- notices (").append(result.notices().size()).append(") --\n");
        for (String notice : result.notices()) {
            sb.append("  ").append(notice).append('\n');
        }
        return sb.toString();
    }

    /**
     * 스냅샷 맨 앞의 <b>지표 줄</b>. 스냅샷 동등성만으로는 "달라졌다"만 알 수 있고
     * "나아졌는지"는 알 수 없다 — diff 맨 위에 숫자를 두어 그 판단을 가능하게 한다.
     *
     * <p>지표를 고른 기준은 <b>여행자가 실제로 겪는 실패</b>다. 각 항목은 실측 사례에서 왔다.
     * <ul>
     *   <li>연속중복: 같은 장소가 두 스텝으로 (실측 jeju-3d-walk 제주R호텔 19:33/20:04)</li>
     *   <li>모드불일치: 도보 선호인데 CAR (실측 같은 스냅샷 42km CAR 구간)</li>
     *   <li>숙소수: 짐을 몇 번 싸는가 (실측 79번 3박에 3곳)</li>
     *   <li>최대공백: 하루 중간이 얼마나 비는가 (실측 79번 2일차 152분)</li>
     *   <li>늦은시작: 오전이 통째로 비는 날 (실측 78번 4일 내내 11:30 시작)</li>
     * </ul>
     */
    private static String renderMetrics(RouteOptimizer.ScheduleResult result) {
        List<StepData> steps = result.steps();

        int duplicateAdjacent = 0;
        int maxGapMinutes = 0;
        String prevKey = null;
        int prevDay = -1;
        int prevEnd = -1;
        for (StepData s : steps) {
            String key = s.place() != null ? nz(s.place().name()) : "-";
            if (s.dayNumber() == prevDay) {
                if (key.equals(prevKey)) duplicateAdjacent++;
                int gap = toMinutes(s.startTime()) - prevEnd;
                if (gap > maxGapMinutes) maxGapMinutes = gap;
            }
            prevKey = key;
            prevDay = s.dayNumber();
            prevEnd = toMinutes(s.endTime());
        }

        double totalKm = steps.stream()
                .map(StepData::transportationDistance)
                .filter(d -> d != null)
                .mapToDouble(BigDecimal::doubleValue).sum();

        long lodgingCount = steps.stream()
                .filter(s -> s.place() != null)
                .filter(s -> PlaceCategoryConstants.isAccommodation(s.place().category()))
                .map(s -> s.place().name())
                .distinct().count();

        // 하루의 첫 스텝이 점심(11:30) 이후면 오전이 통째로 빈 날이다.
        long lateStartDays = steps.stream()
                .collect(java.util.stream.Collectors.groupingBy(StepData::dayNumber,
                        java.util.stream.Collectors.minBy(
                                java.util.Comparator.comparingInt(s -> toMinutes(s.startTime())))))
                .values().stream()
                .filter(java.util.Optional::isPresent).map(java.util.Optional::get)
                .filter(s -> toMinutes(s.startTime()) > 11 * 60 + 30)
                .count();

        return String.format(
                "-- metrics --%n"
                + "  스텝 %d / 숙소 %d곳 / 총이동 %.0fkm%n"
                + "  연속중복 %d / 최대공백 %d분 / 늦은시작 %d일 / 안내 %d건%n",
                steps.size(), lodgingCount, totalKm,
                duplicateAdjacent, maxGapMinutes, lateStartDays, result.notices().size());
    }

    private static int toMinutes(String hhmm) {
        if (hhmm == null || !hhmm.contains(":")) return 0;
        String[] p = hhmm.split(":");
        try {
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String placeOf(StepData step) {
        if (step.place() == null) return "(장소 없음)";
        return "%s | %s | %s | %s".formatted(
                nz(step.place().name()), nz(step.place().category()),
                nz(step.place().subRegion()), nz(step.place().region()));
    }

    private static List<AlternativeData> nvl(List<AlternativeData> alternatives) {
        return alternatives != null ? alternatives : List.of();
    }

    private static String nz(Object value) {
        return value != null ? value.toString() : "-";
    }

    /** BigDecimal은 scale에 따라 "1.0"/"1.00"이 갈리므로 표시 자릿수를 고정한다. */
    private static String money(BigDecimal value) {
        return value != null ? value.stripTrailingZeros().toPlainString() : "-";
    }
}
