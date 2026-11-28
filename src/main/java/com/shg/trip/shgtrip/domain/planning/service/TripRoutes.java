package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.TransportRoute;
import com.shg.trip.shgtrip.domain.routing.CallAllowance;
import com.shg.trip.shgtrip.domain.routing.Fare;
import com.shg.trip.shgtrip.domain.routing.GeoPoint;
import com.shg.trip.shgtrip.domain.routing.LegMode;
import com.shg.trip.shgtrip.domain.routing.ProviderAttempt;
import com.shg.trip.shgtrip.domain.routing.RouteBasis;
import com.shg.trip.shgtrip.domain.routing.RouteConfidence;
import com.shg.trip.shgtrip.domain.routing.RouteEngine;
import com.shg.trip.shgtrip.domain.routing.RouteItinerary;
import com.shg.trip.shgtrip.domain.routing.RouteLeg;
import com.shg.trip.shgtrip.domain.routing.RouteQuery;
import com.shg.trip.shgtrip.domain.routing.RouteResult;
import com.shg.trip.shgtrip.domain.routing.RouteSource;
import com.shg.trip.shgtrip.domain.routing.RouteStatus;
import com.shg.trip.shgtrip.domain.routing.TravelMode;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 일정에 들어간 이동만 경로 엔진에 묻고, 받은 이동 시간으로 그날 시각을 다시 맞춘다. 후보를 고르는 단계는 거리 추정을
 * 그대로 쓴다. 경로를 받지 못한 구간은 추정으로 두고 추정이라고 남긴다. 조회 실패는 방문할 수 없다는 뜻이 아니다.
 */
@Slf4j
@Component
public class TripRoutes {

    /** 일정 하나가 경로 조회에 쓰는 시간 상한. 넘으면 남은 구간은 거리 추정으로 둔다. */
    static final Duration TRIP_BUDGET = Duration.ofSeconds(20);
    /** 편집 한 번이 경로 조회에 쓰는 시간 상한. 사용자가 응답을 기다리는 동안 묻는다. */
    static final Duration EDIT_BUDGET = Duration.ofSeconds(4);
    /** 일정 하나가 부르는 외부 API 수. 자체 서버(OTP·Valhalla)와 캐시 적중은 세지 않는다. */
    static final int EXTERNAL_CALLS = 12;
    /** 편집 한 번(바뀐 앞뒤 구간과 택시 견주기)이 부르는 외부 API 수. 미리보기는 사용자가 거듭 누른다. */
    static final int EDIT_EXTERNAL_CALLS = 4;
    /** 시각을 다시 맞추고 다시 묻는 횟수 상한. 대개 두 번째에 이동 시간이 더 바뀌지 않는다. */
    static final int MAX_ROUNDS = 3;
    /** 앱 전체에서 동시에 묻는 구간 수. 일정 여러 개를 함께 지어도 로컬 OTP 한 대가 느려지지 않는 정도다. */
    static final int PARALLEL = 8;
    /** 수단 제한이 없는 여행에서 대중교통이 이보다 오래 걸리면 택시와 견준다. 배차가 드문 지방 버스·긴 도보 환승 구간이다. */
    static final int LONG_TRANSIT_MINUTES = 60;
    /** 타기까지·갈아타기 기다림이 이보다 길면 그 시각에는 운행이 끊긴 것으로 본다(막차 뒤 첫차까지). */
    static final int NO_SERVICE_WAIT_MINUTES = 120;
    /** 택시가 이 배 넘게 빠르고 아래만큼 시간을 아낄 때만 택시로 간다. 조금 빠른 정도로는 요금을 더 쓰지 않는다. */
    static final int TAXI_FASTER_FACTOR = 2;
    static final int TAXI_MIN_MINUTES_SAVED = 30;
    /** 택시가 대중교통보다 더 드는 금액(일행 전체)이 여행 하루 예산의 이 몫을 넘으면 택시로 가지 않는다. */
    static final double TAXI_DAY_BUDGET_SHARE = 0.15;
    /** 대중교통 부담을 잴 때 갈아타기 한 번을 이만큼 더 걸린 것으로 본다. 환승 수요 분석의 통상 환승 저항 값이다. */
    static final int TRANSFER_PENALTY_MINUTES = 10;
    /** 대중교통 경로 안 걷기가 이보다 길면 넘는 만큼을 부담으로 더한다. */
    static final int WALK_COMFORT_MINUTES = 15;
    /** 택시 한 대에 타는 여행자 수(중형 승용 정원). */
    static final int TAXI_SEATS = 4;
    /** 렌터카·자가용 한 대에 타는 여행자 수. */
    static final int CAR_SEATS = 5;
    /** 택시 심야 할증이 붙는 현지 시각. 요금표에 할증이 없어 이 시각 택시 요금은 추정으로 낮춘다. */
    static final int NIGHT_FARE_FROM_MIN = 22 * 60;
    static final int NIGHT_FARE_UNTIL_MIN = 5 * 60;
    /** 대중교통으로 물었는데 걷기만 이보다 오래 나오면 이을 탈 것을 못 찾은 것이다(밤늦은 도착이거나 노선 데이터 공백). */
    static final int WALK_ONLY_MAX_MINUTES = 45;
    /**
     * 대중교통 경로가 거리 추정의 이 배 이상이고 아래만큼 더 걸리면 노선 데이터가 빠져 크게 돌아간 경로로 본다. 배차가 드문
     * 지방 버스는 기다림이 길어도 이만큼 벌어지지 않는다.
     */
    static final int DETOUR_FACTOR = 3;
    static final int DETOUR_MIN_EXTRA_MINUTES = 60;
    /**
     * 거리 추정의 이 배 이상 걸리는 경로는 공백 신호가 하나 더 있을 때 데이터 공백으로 본다. 노선이 빠진 곳은 대중교통 경로
     * 안에 긴 걷기가 끼거나(공항버스가 없어 마지막 역에서 걸어감) 다른 날 시간표를 빌려 쓴다.
     */
    static final int SUSPECT_FACTOR = 2;
    static final int LONG_WALK_LEG_MINUTES = 30;
    /** 직선거리로 이보다 빠른 도로 경로는 끝점이 엉뚱한 도로로 붙은 답이다(섬 좌표가 바다 건너 도로에 붙는 경우). */
    static final double MAX_ROAD_KMH = 150;
    /** 끝점을 정류장으로 옮긴 간격은 소요 시간에 없어서 걷는 시간으로 더한다(m/분). */
    static final double GAP_WALK_METERS_PER_MINUTE = 70;
    private static final int NOTE_MAX = 200;
    private static final String BUDGET_EXCEEDED = "trip route budget exceeded";
    /** 요금을 차 한 대마다 내는 수단. */
    private static final java.util.Set<String> VEHICLE_MODES = java.util.Set.of("CAR", "DRIVE", "TAXI");

    /**
     * 사용자 한 명이 편집·미리보기로 경로를 묻는 횟수 한도. 미리보기는 일정을 바꾸지 않아 거듭 눌러도 일정 한도에 걸리지
     * 않으므로 사용자마다 따로 센다. 넘으면 그 편집은 거리 추정으로 둔다.
     */
    static final int EDIT_ROUTES_PER_MINUTE = 10;
    static final int EDIT_ROUTES_PER_DAY = 200;
    private static final String EDIT_COUNTER = "edit-routes-user:";

    private final RouteEngine engine;
    private final Clock clock;
    private final com.shg.trip.shgtrip.domain.routing.ProviderCallBudget userCounters;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    /** 먼저 기다린 구간이 먼저 묻는다. 우선 구간을 앞에 넣은 순서가 지켜져야 상한에 걸려도 같은 구간이 남는다. */
    private final Semaphore parallel = new Semaphore(PARALLEL, true);

    @org.springframework.beans.factory.annotation.Autowired
    public TripRoutes(RouteEngine engine, Clock clock,
                      com.shg.trip.shgtrip.domain.routing.ProviderCallBudget userCounters) {
        this.engine = engine;
        this.clock = clock;
        this.userCounters = userCounters;
    }

    /** 사용자 한도를 세지 않는다. 평가·시험용이다. */
    public TripRoutes(RouteEngine engine, Clock clock) {
        this(engine, clock, null);
    }

    /** 이 사용자가 편집·미리보기에서 경로를 더 물어도 되는지. 한 번 묻는 것으로 센다. */
    public boolean editRoutingAllowed(Long userId) {
        if (!engine.enabled()) return false;
        if (userCounters == null || userId == null) return true;
        return userCounters.tryAcquireQuietly(EDIT_COUNTER + userId, EDIT_ROUTES_PER_DAY, EDIT_ROUTES_PER_MINUTE,
                java.time.ZoneOffset.UTC);
    }

    /**
     * 일정 하나의 조회 묶음. 시간 상한·외부 호출 수·같은 구간 결과를 함께 쓴다. 엔진이 꺼져 있으면 추정만 쓴다.
     *
     * @param dayBudgetWon 여행 하루 예산(원, 일행 전체). 택시로 바꿀 때 더 드는 금액 상한을 정한다. null이면 요금으로 막지 않는다
     * @param dayCount     여행 일수. 시간 상한을 날마다 나눠 앞날이 뒷날 몫을 다 쓰지 않게 한다
     * @param partySize    여행 인원. 모르면 null이고 한 명으로 견준다
     */
    public Session session(String country, BigDecimal dayBudgetWon, int dayCount, Integer partySize) {
        return engine.enabled()
                ? new Session(this, country, TRIP_BUDGET, EXTERNAL_CALLS, taxiCap(dayBudgetWon), dayCount, partySize)
                : estimatesOnly(country);
    }

    public Session session(String country) {
        return session(country, null, 1, null);
    }

    /** 편집은 저장된 여행 조건이 없어 수단을 바꾸지 않는다(택시 대안 없음). */
    public Session editSession(String country) {
        return engine.enabled() ? new Session(this, country, EDIT_BUDGET, EDIT_EXTERNAL_CALLS, 0, 1, null)
                : estimatesOnly(country);
    }

    private static long taxiCap(BigDecimal dayBudgetWon) {
        return dayBudgetWon == null || dayBudgetWon.signum() <= 0 ? Long.MAX_VALUE
                : Math.round(dayBudgetWon.doubleValue() * TAXI_DAY_BUDGET_SHARE);
    }

    @jakarta.annotation.PreDestroy
    void close() {
        executor.close();
    }

    /** 거리 추정만 쓰는 묶음. 경로 엔진이 꺼져 있으면 생성·편집이 모두 이것을 쓴다. */
    public static Session estimatesOnly(String country) {
        return new Session(null, country, Duration.ZERO, 0, 0, 1, null);
    }

    /** 조회 한 묶음(하루·편집 한 번)의 기한과 호출 수. */
    private record Batch(Instant until, CallAllowance calls) {}

    /** 다시 맞춘 하루. frame은 물어 본 구간을 담고 있어 편집으로 다시 지을 때 그대로 쓴다. */
    record Day(DayBuilder.Frame frame, DayBuilder.Built built) {}

    public static final class Session {

        private final TripRoutes owner;
        private final ZoneOffset zone;
        /** 경로 조회에 쓸 수 있는 시간. 플래너가 장소를 고르는 시간은 넣지 않는다. */
        private final Duration budget;
        private final AtomicLong spentMillis = new AtomicLong();
        /** 지금 묻는 묶음(하루·편집 한 번)이 끝나야 하는 시각. */
        private volatile Instant deadline = Instant.MIN;
        private final CallAllowance allowance;
        /** 지금 묶음의 호출 수·기한. 조회는 만들 때의 묶음을 들고 가서 뒤 묶음 기한으로 돌지 않는다. */
        private volatile Batch batch;
        /** 택시로 바꿀 때 대중교통보다 더 드는 금액 상한(원, 일행 전체). 0이면 바꾸지 않고, Long.MAX_VALUE면 예산을 모른다. */
        private final long taxiFareCapWon;
        private final int dayCount;
        private final Integer partySize;
        private final java.util.concurrent.atomic.AtomicInteger daysRouted = new java.util.concurrent.atomic.AtomicInteger();
        private final Map<String, CompletableFuture<TransportLeg>> memo = new ConcurrentHashMap<>();
        /** 구간마다 처음 본 거리 추정. 앞 라운드 답으로 지은 구간도 이 수단으로 다시 묻는다. */
        private final Map<String, TransportLeg> estimates = new ConcurrentHashMap<>();
        private final Stats stats = new Stats();
        private final List<String> notes = java.util.Collections.synchronizedList(new ArrayList<>());

        private Session(TripRoutes owner, String country, Duration budget, int externalCalls, long taxiFareCapWon,
                        int dayCount, Integer partySize) {
            this.owner = owner;
            this.zone = ZoneOffset.ofTotalSeconds(TripCalendar.utcOffsetMinutes(country) * 60);
            this.budget = budget;
            this.allowance = CallAllowance.of(externalCalls);
            this.taxiFareCapWon = taxiFareCapWon;
            this.dayCount = Math.max(1, dayCount);
            this.partySize = partySize;
            this.batch = new Batch(Instant.MIN, allowance);
        }

        private Instant begin() {
            return begin(remaining());
        }

        /** @param share 이번 묶음이 쓸 수 있는 시간. 남은 상한보다 길면 남은 상한이다 */
        private Instant begin(Duration share) {
            Instant now = owner.clock.instant();
            Duration left = remaining();
            deadline = now.plus(share.compareTo(left) < 0 ? share : left);
            batch = new Batch(deadline, allowance.until(deadline));
            return now;
        }

        private Duration remaining() {
            return budget.minusMillis(spentMillis.get());
        }

        /**
         * 아직 짓지 않은 날이 n일이면 남은 시간의 2/(n+1)까지 쓴다. 앞날이 남긴 시간은 뒷날로 넘어가고, 한 날이 몰아 써도
         * 뒷날 몫을 다 쓰지 않는다.
         */
        private Duration dayShare() {
            int left = dayCount - daysRouted.get();
            return left <= 1 ? remaining() : remaining().multipliedBy(2).dividedBy(left + 1);
        }

        private void end(Instant began) {
            long ms = Duration.between(began, owner.clock.instant()).toMillis();
            spentMillis.addAndGet(ms);
            stats.elapsedMillis.addAndGet(ms);
        }

        public Stats stats() {
            return stats;
        }

        /** 다른 수단으로 바꾼 구간과 묻지 못한 구간. 일정 메모에 남긴다. */
        public List<String> notes() {
            synchronized (notes) {
                return notes.stream().distinct().toList();
            }
        }

        boolean enabled() {
            return owner != null;
        }

        /**
         * 그날 들어간 구간을 묻고 받은 시간으로 같은 순서를 다시 맞춘다. 다시 맞추면 떠나는 시각이 바뀌어 대중교통 구간을
         * 그 시각으로 다시 묻는다. 횟수를 다 써도 맞지 않는 구간은 확인한 시각과 떠나는 시각이 달라 추정으로 낮춘다.
         */
        Day route(DayBuilder.Frame f, DayBuilder.Built built) {
            if (!enabled() || built.hops().isEmpty()) return new Day(f, built);
            Instant began = begin(dayShare());
            try {
                DayBuilder.Frame frame = f;
                DayBuilder.Built current = built;
                Map<String, TransportLeg> routes = new HashMap<>(f.routes());
                for (int round = 0; round <= MAX_ROUNDS; round++) {
                    Map<DayBuilder.Hop, TransportLeg> answers = resolve(frame, current, current.hops());
                    if (round == 0) answers.forEach(stats::compareEstimate);
                    boolean consistent = answers.entrySet().stream().allMatch(e -> agrees(e.getKey(), e.getValue()));
                    if (consistent || round == MAX_ROUNDS) return new Day(frame, settled(frame, current, answers));
                    answers.forEach((h, leg) -> {
                        if (leg != null) routes.put(DayBuilder.hopKey(h.from(), h.to()), leg);
                    });
                    frame = frame.withRoutes(routes);
                    current = DayBuilder.retimed(frame, current);
                    stats.retimes.incrementAndGet();
                }
                throw new IllegalStateException("unreachable");
            } finally {
                daysRouted.incrementAndGet();
                end(began);
            }
        }

        /**
         * 지을 때 쓴 구간이 그 출발 시각에 물어 본 답과 같은지. 답이 없으면 지금 구간을 그대로 둔다. 갈 수 없다는 답은
         * 그 시각에 물은 답으로 지었을 때만 같다. 다른 시각의 답이면 다시 지어 그 시각 둘레에서 피한다.
         */
        private static boolean agrees(DayBuilder.Hop hop, TransportLeg answer) {
            if (answer == null) return true;
            TransportLeg used = hop.leg();
            if (answer.unreachable()) {
                return used.unreachable() && Objects.equals(used.route().departAt(), answer.route().departAt());
            }
            return used.durationMin() == answer.durationMin() && Objects.equals(used.mode(), answer.mode())
                    && !used.unreachable();
        }

        /**
         * 구간마다 스텝에 적을 답. 그 출발 시각의 답과 같으면 그 답이다. 다르거나 답을 받지 못했는데 앞 라운드에서 다른 시각에
         * 물은 근거가 남아 있으면, 쓴 시간은 그대로 두고 근거를 추정으로 낮춰 떠나는 시각을 맞춘다.
         */
        DayBuilder.Built settled(DayBuilder.Frame f, DayBuilder.Built built,
                                         Map<DayBuilder.Hop, TransportLeg> answers) {
            Map<DayBuilder.Hop, TransportLeg> shown = new LinkedHashMap<>();
            answers.forEach((h, answer) -> {
                ZonedDateTime departAt = f.date().atStartOfDay(zone).plusMinutes(h.departMin());
                boolean checkedThen = h.leg().route() != null && h.leg().route().departAt() != null
                        && OffsetDateTime.parse(h.leg().route().departAt()).isEqual(departAt.toOffsetDateTime());
                if (answer != null && agrees(h, answer)) shown.put(h, answer);
                else if (answer != null && answer.unreachable()) {
                    TransportLeg used = h.leg();
                    shown.put(h, new TransportLeg(used.mode(), used.durationMin(), used.distanceKm(), used.cost(),
                            answer.route()));
                } else if (h.leg().route() != null && !checkedThen) {
                    shown.put(h, restamped(drifted(h), departAt, false));
                }
            });
            return withLegs(built, shown);
        }

        /** 지은 시각과 확인한 시각이 다른 구간. 쓴 시간은 그대로 두고 근거를 추정으로 낮춘다. */
        private static TransportLeg drifted(DayBuilder.Hop hop) {
            TransportLeg used = hop.leg();
            if (used.route() == null) return used;
            return new TransportLeg(used.mode(), used.durationMin(), used.distanceKm(), used.cost(),
                    used.route().downgraded("departure moved after check"));
        }

        /** 구간마다 받은 답을 스텝에 적는다. 시각은 이미 그 답으로 맞춰져 있다. */
        private static DayBuilder.Built withLegs(DayBuilder.Built built, Map<DayBuilder.Hop, TransportLeg> legs) {
            List<StepData> steps = new ArrayList<>(built.steps());
            legs.forEach((hop, leg) -> {
                if (leg == null || hop.step() < 0 || hop.step() >= steps.size()) return;
                StepData s = steps.get(hop.step());
                steps.set(hop.step(), new StepData(s.stepOrder(), s.dayNumber(), s.startTime(), s.endTime(), s.place(),
                        s.alternatives(), leg.mode(), leg.durationMin(), leg.distanceKm(), leg.cost(), s.notes(),
                        s.estimatedCost(), leg.route()));
            });
            return new DayBuilder.Built(steps, built.drops(), built.placed(), built.stops(), built.hops());
        }

        private Map<DayBuilder.Hop, TransportLeg> resolve(DayBuilder.Frame f, DayBuilder.Built built,
                                                          List<DayBuilder.Hop> hops) {
            Map<DayBuilder.Hop, CompletableFuture<TransportLeg>> pending = new LinkedHashMap<>();
            List<DayBuilder.Hop> ordered = hops.stream()
                    .sorted(java.util.Comparator.comparingInt((DayBuilder.Hop h) -> rank(f, built, h))
                            .thenComparingInt(DayBuilder.Hop::step))
                    .toList();
            for (DayBuilder.Hop h : ordered) {
                pending.put(h, leg(f.dayNumber(), f.date(), h.from(), h.to(), h.departMin(), estimateOf(h), null,
                        unrestricted(f.modeFrom(h.from()))));
            }
            Map<DayBuilder.Hop, TransportLeg> out = new LinkedHashMap<>();
            pending.forEach((h, future) -> out.put(h, await(future)));
            return out;
        }

        /**
         * 먼저 물을 구간. 숙소·출발 허브로 가는 마지막 구간, 도착 허브에서 나서는 구간, 도시 간 이동은 하루 전체 시각을
         * 정하고, 다음은 사용자가 요청한 장소로 가는 구간이다.
         */
        static int rank(DayBuilder.Frame f, DayBuilder.Built built, DayBuilder.Hop h) {
            boolean anchors = h.to() == f.endAt() || h.from() == f.arrivalHub()
                    || ScheduleTuning.INTERCITY_LEG_MODES.contains(h.leg().mode());
            if (anchors) return 0;
            boolean requested = built.stops().stream().anyMatch(st -> st.protect() && st.place() == h.to());
            return requested ? 1 : 2;
        }

        /** 수단을 정하지 않은 여행(상관없음·택시)만 다른 수단을 견준다. 대중교통·도보·자동차는 말한 수단을 지킨다. */
        static boolean unrestricted(String pref) {
            return pref == null || "any".equals(pref) || "taxi".equals(pref);
        }

        /** 거리 추정. 앞 라운드에서 받은 답으로 지은 구간이면 null이라 처음 본 추정을 쓴다. */
        private static TransportLeg estimateOf(DayBuilder.Hop h) {
            return h.leg().route() == null ? h.leg() : null;
        }

        /**
         * 저장된 일정을 고칠 때 바뀐 구간 하나. 그날 그 출발 시각으로 묻고, 받지 못하면 거리 추정을 근거와 함께 돌려준다.
         *
         * @param departTime 앞 스텝이 끝나는 시각(HH:mm)
         * @param modeHint   고치기 전 이 구간의 수단. 추정식은 수단을 모르고 거리로만 고른다. null이면 추정 수단이다
         */
        public TransportLeg between(LocalDate date, String departTime, Place from, Place to,
                                    GeoUtils.TransportLeg estimate, String modeHint) {
            return between(date, departTime, SavedItineraryFacts.candidate(from), SavedItineraryFacts.candidate(to),
                    estimate, modeHint);
        }

        /** 트랜잭션 밖에서 묻도록 장소를 후보로 옮겨 둔 구간. */
        public TransportLeg between(LocalDate date, String departTime, PlaceCandidate from, PlaceCandidate to,
                                    GeoUtils.TransportLeg estimate, String modeHint) {
            TransportLeg est = new TransportLeg(estimate.mode(), estimate.durationMin(), estimate.distanceKm(),
                    estimate.cost());
            Integer depart = ScheduleTimes.toMinutesOrNull(departTime);
            if (!enabled() || date == null || depart == null) return est;
            TravelMode mode = travelMode(modeHint != null && !ScheduleTuning.INTERCITY_LEG_MODES.contains(modeHint)
                    ? modeHint : est.mode());
            Instant began = begin();
            try {
                TransportLeg r = await(leg(0, date, from, to, depart, est, mode, false));
                return r == null ? est : r;
            } finally {
                end(began);
            }
        }

        /** 일정기가 한 구간을 고르는 방식 그대로 묻는다. 비교 러너가 쓴다. */
        TransportLeg legAt(LocalDate date, PlaceCandidate from, PlaceCandidate to, int departMin,
                           TransportLeg estimate, boolean taxiAllowed) {
            if (!enabled()) return estimate;
            Instant began = begin();
            try {
                TransportLeg r = await(leg(0, date, from, to, departMin, estimate, null, taxiAllowed));
                return r == null ? estimate : r;
            } finally {
                end(began);
            }
        }

        private TransportLeg await(CompletableFuture<TransportLeg> future) {
            if (!enabled()) return future.getNow(null);
            long left = Duration.between(owner.clock.instant(), deadline).toMillis();
            try {
                return future.get(Math.max(1, left), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                stats.budgetSkips.incrementAndGet();
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (java.util.concurrent.ExecutionException e) {
                log.warn("trip route failed cause={}", e.getCause() == null ? "?" : e.getCause().getClass().getSimpleName());
                return null;
            }
        }

        /**
         * 두 장소 사이 한 구간. 같은 구간·같은 출발 시각은 일정 안에서 한 번만 묻는다.
         *
         * @param estimate    거리 추정. 경로를 받지 못하면 이것을 근거와 함께 돌려준다. null이면 앞서 본 추정을 쓴다
         * @param askAs       물을 수단. null이면 거리 추정의 수단이다
         * @param taxiAllowed 대중교통이 없거나 너무 오래 걸릴 때 택시로 바꿔도 되는지. 수단을 정한 여행은 false다
         * @return 묻지 않는 구간(좌표 없음·비행기)이면 null
         */
        private CompletableFuture<TransportLeg> leg(int dayNumber, LocalDate date, PlaceCandidate from,
                                                    PlaceCandidate to, int departMin, TransportLeg estimate,
                                                    TravelMode askAs, boolean taxiAllowed) {
            String key = DayBuilder.hopKey(from, to);
            if (key != null && estimate != null) estimates.putIfAbsent(key, estimate);
            TransportLeg base = key == null ? null : estimates.get(key);
            TravelMode mode = base == null ? null : askAs != null ? askAs : travelMode(base.mode());
            if (mode == null || !enabled()) return CompletableFuture.completedFuture(null);
            String memoKey = mode + "|" + taxiAllowed + "|" + key + "|" + date + "|"
                    + (mode == TravelMode.TRANSIT ? departMin : 0);
            ZonedDateTime departAt = date.atStartOfDay(zone).plusMinutes(departMin);
            CompletableFuture<TransportLeg> known = memo.get(memoKey);
            if (known != null) {
                stats.memoHits.incrementAndGet();
                return mode == TravelMode.TRANSIT ? known : known.thenApply(l -> restamped(l, departAt, true));
            }
            GeoPoint a = point(from);
            GeoPoint b = point(to);
            Long fromId = from.placeId();
            Long toId = to.placeId();
            String where = (dayNumber > 0 ? dayNumber + "일차 " : "") + from.name() + " → " + to.name();
            Batch issued = batch;
            CompletableFuture<TransportLeg> asked = memo.computeIfAbsent(memoKey, k -> CompletableFuture.supplyAsync(
                    () -> ask(a, b, fromId, toId, departAt, mode, base, where, taxiAllowed, issued), owner.executor));
            // 상한·공급자 장애로 받지 못한 답은 기억하지 않아 다음 묶음(뒷날 몫·편집 뒤 다시 짓기)에서 다시 묻는다.
            asked.thenAccept(l -> {
                if (unanswered(l)) memo.remove(memoKey, asked);
            });
            return mode == TravelMode.TRANSIT ? asked : asked.thenApply(l -> restamped(l, departAt, true));
        }

        /**
         * 근거의 출발·도착 시각을 이 구간이 실제로 떠나는 시각으로 옮긴다. 도로 경로는 소요 시간이 출발 시각과 무관해 같은 답을
         * 다른 시각에 다시 쓴다.
         *
         * @param legs 토막 시각도 옮길지. 대중교통 토막은 시간표 시각이라 옮기지 않는다
         */
        private static TransportLeg restamped(TransportLeg leg, ZonedDateTime departAt, boolean legs) {
            TransportRoute r = leg == null ? null : leg.route();
            if (r == null || r.departAt() == null) return leg;
            Duration shift = Duration.between(OffsetDateTime.parse(r.departAt()), departAt.toOffsetDateTime());
            if (shift.isZero()) return leg;
            String arrive = departAt.plusMinutes(leg.durationMin()).toOffsetDateTime().toString();
            List<TransportRoute.Leg> moved = !legs ? r.legs() : r.legs().stream().map(l -> new TransportRoute.Leg(
                    l.mode(), l.line(), l.operator(), l.from(), l.to(), plus(l.departAt(), shift),
                    plus(l.arriveAt(), shift), l.minutes())).toList();
            return new TransportLeg(leg.mode(), leg.durationMin(), leg.distanceKm(), leg.cost(),
                    r.movedTo(departAt.toOffsetDateTime().toString(), r.arriveAt() == null ? null : arrive, moved));
        }

        private static String plus(String iso, Duration shift) {
            return iso == null ? null : OffsetDateTime.parse(iso).plus(shift).toString();
        }

        private static boolean unanswered(TransportLeg l) {
            return l != null && l.route() != null && RouteStatus.UNAVAILABLE.name().equals(l.route().status());
        }

        private TransportLeg ask(GeoPoint a, GeoPoint b, Long fromId, Long toId, ZonedDateTime departAt,
                                 TravelMode mode, TransportLeg estimate, String where, boolean taxiAllowed,
                                 Batch issued) {
            if (owner.clock.instant().isAfter(issued.until())) {
                stats.budgetSkips.incrementAndGet();
                return unconfirmed(estimate, RouteStatus.UNAVAILABLE, null, BUDGET_EXCEEDED, fromId, toId, departAt);
            }
            RouteResult r = call(new RouteQuery(a, b, departAt, mode), issued);
            boolean intercity = ScheduleTuning.INTERCITY_LEG_MODES.contains(estimate.mode());
            boolean taxiInCity = taxiAllowed && mode == TravelMode.TRANSIT && !intercity;
            if (r.status() == RouteStatus.FOUND) {
                TransportLeg leg = found(r, estimate, mode, fromId, toId, departAt);
                if (mode != TravelMode.TRANSIT && !plausibleRoad(leg, a, b)) {
                    return unconfirmed(estimate, RouteStatus.NO_CONNECTION, r.providerId(), "implausible road route "
                            + leg.durationMin() + " min", fromId, toId, departAt);
                }
                // 배·비행기는 하루 몇 편이 보통이라 긴 기다림이 운행 끊김이 아니고, 택시로 대신할 수 없다.
                if (crossesWater(leg)) return leg;
                Integer wait = leg.route().waitMinutes();
                if (mode == TravelMode.TRANSIT && wait != null && wait > NO_SERVICE_WAIT_MINUTES) {
                    return noTransit(a, b, fromId, toId, departAt, estimate, where, r.providerId(),
                            "transit waits " + wait + " min", taxiInCity, issued);
                }
                // 걷기만 나오는 것은 시간표가 끊긴 것인지 그 지역 노선 데이터가 없는 것인지 가를 수 없어, 갈 수 없다고 하지 않는다.
                boolean walkOnly = leg.route().legs().stream().allMatch(l -> "WALK".equals(l.mode()));
                if (mode == TravelMode.TRANSIT && walkOnly && leg.durationMin() > WALK_ONLY_MAX_MINUTES) {
                    String why = "only walking " + leg.durationMin() + " min; transit data may be missing";
                    if (taxiInCity) return noTransit(a, b, fromId, toId, departAt, estimate, where, r.providerId(), why,
                            true, issued);
                    return unconfirmed(estimate, RouteStatus.NOT_COVERED, r.providerId(), why, fromId, toId, departAt);
                }
                String gap = mode == TravelMode.TRANSIT ? dataGap(leg, estimate) : null;
                if (gap != null) {
                    stats.suspectDetours.incrementAndGet();
                    notes.add(where + ": 경로가 거리 추정보다 크게 돌아가 노선 데이터가 빠진 것으로 보고 추정 시간으로 둠");
                    return unconfirmed(estimate, RouteStatus.NOT_COVERED, r.providerId(), gap, fromId, toId, departAt);
                }
                if (!taxiInCity || burden(leg) < LONG_TRANSIT_MINUTES) return leg;
                return fasterOf(leg, a, b, fromId, toId, departAt, estimate, issued);
            }
            if (r.status() == RouteStatus.NO_CONNECTION && mode == TravelMode.TRANSIT) {
                return noTransit(a, b, fromId, toId, departAt, estimate, where, r.providerId(),
                        "no transit connection: " + Objects.toString(r.reason(), ""), taxiInCity, issued);
            }
            return unconfirmed(estimate, r.status(), r.providerId(), r.reason(), fromId, toId, departAt);
        }

        /**
         * 노선 데이터가 빠져 돌아간 것으로 보이는 대중교통 경로의 사유. 정상으로 보이면 null이다. 배·비행기 구간은 이 판정 전에
         * 걸러진다.
         */
        static String dataGap(TransportLeg leg, TransportLeg estimate) {
            int est = estimate.durationMin();
            int got = leg.durationMin();
            if (got - est < DETOUR_MIN_EXTRA_MINUTES) return null;
            if (got >= est * DETOUR_FACTOR) {
                return "route " + got + " min, over " + DETOUR_FACTOR + "x estimate; transit data likely missing";
            }
            if (got < est * SUSPECT_FACTOR) return null;
            int longestWalk = leg.route().legs().stream().filter(l -> "WALK".equals(l.mode()))
                    .mapToInt(TransportRoute.Leg::minutes).max().orElse(0);
            if (longestWalk >= LONG_WALK_LEG_MINUTES) {
                return "route " + got + " min with a " + longestWalk + " min walk; transit data likely missing";
            }
            if (RouteConfidence.ESTIMATED.name().equals(leg.route().confidence())
                    && "ESTIMATED".equals(leg.route().basis())) {
                return "route " + got + " min on a borrowed timetable; transit data likely missing";
            }
            return null;
        }

        /** 대중교통 부담(분). 갈아타기와 긴 걷기를 시간으로 더한다. */
        static int burden(TransportLeg transit) {
            if (transit.route() == null) return transit.durationMin();
            int walk = transit.route().legs().stream().filter(l -> "WALK".equals(l.mode()))
                    .mapToInt(TransportRoute.Leg::minutes).sum();
            int transfers = transit.route().transfers() == null ? 0 : transit.route().transfers();
            return transit.durationMin() + transfers * TRANSFER_PENALTY_MINUTES + Math.max(0, walk - WALK_COMFORT_MINUTES);
        }

        /**
         * 오래 걸리는 대중교통과 택시 중 하나. 택시가 대중교통 부담의 절반보다 빠르고 30분 넘게 아끼며, 일행 전체로 더 드는
         * 금액이 하루 예산 몫 안일 때만 택시다. 예산이 있는데 택시 요금을 모르면 예산을 지킨다고 볼 수 없어 바꾸지 않는다.
         */
        private TransportLeg fasterOf(TransportLeg transit, GeoPoint a, GeoPoint b, Long fromId, Long toId,
                                      ZonedDateTime departAt, TransportLeg estimate, Batch issued) {
            RouteResult taxi = call(new RouteQuery(a, b, departAt, TravelMode.TAXI), issued);
            if (taxi.status() != RouteStatus.FOUND) return transit;
            TransportLeg byCar = found(taxi, DayBuilder.taxiLeg(estimate.distanceKm()), TravelMode.TAXI, fromId, toId,
                    departAt);
            if (crossesWater(byCar) || !plausibleRoad(byCar, a, b)) return transit;
            int burden = burden(transit);
            int saved = burden - byCar.durationMin();
            if (byCar.durationMin() * TAXI_FASTER_FACTOR > burden || saved < TAXI_MIN_MINUTES_SAVED
                    || !affordable(byCar, transit)) {
                return transit;
            }
            stats.taxiAlternatives.incrementAndGet();
            return new TransportLeg("TAXI", byCar.durationMin(), byCar.distanceKm(), byCar.cost(),
                    byCar.route().withNote("transit " + transit.durationMin() + " min (burden " + burden
                            + " with transfers and walking); taxi saves " + saved + " min"));
        }

        /** 일행 전체로 택시가 대중교통보다 더 드는 금액이 상한 안인지. 대중교통 요금을 모르면 0원으로 보고 더 엄하게 잰다. */
        private boolean affordable(TransportLeg byCar, TransportLeg transit) {
            if (taxiFareCapWon == Long.MAX_VALUE) return true;
            TransportRoute.Fare taxiFare = byCar.route().fare();
            if (taxiFare == null || taxiFare.forParty(partySize, TAXI_SEATS).totalWon() == null) return false;
            BigDecimal taxiWon = taxiFare.forParty(partySize, TAXI_SEATS).totalWon();
            TransportRoute.Fare transitFare = transit.route() == null ? null : transit.route().fare();
            BigDecimal transitWon = transitFare == null ? null : transitFare.forParty(partySize, 1).totalWon();
            BigDecimal extra = taxiWon.subtract(transitWon == null ? BigDecimal.ZERO : transitWon);
            return extra.compareTo(BigDecimal.valueOf(taxiFareCapWon)) <= 0;
        }

        /**
         * 그 시각 대중교통이 없는 구간. 수단을 정하지 않은 여행의 도시 안 구간은 택시로 묻는다. 그 밖에는 수단을 바꾸지 않고
         * 이 시각에는 갈 수 없다고 표시해, 일정이 이 구간을 피해 다시 짓게 한다.
         */
        private TransportLeg noTransit(GeoPoint a, GeoPoint b, Long fromId, Long toId, ZonedDateTime departAt,
                                       TransportLeg estimate, String where, String transitProvider, String why,
                                       boolean byTaxi, Batch issued) {
            RouteResult taxi = byTaxi ? call(new RouteQuery(a, b, departAt, TravelMode.TAXI), issued) : null;
            TransportLeg guess = DayBuilder.taxiLeg(estimate.distanceKm());
            TransportLeg byCar = taxi != null && taxi.status() == RouteStatus.FOUND
                    ? found(taxi, guess, TravelMode.TAXI, fromId, toId, departAt) : null;
            // 배를 타야 닿는 곳은 택시로 바꿀 수 없어 그 시각 갈 수 없는 구간이다.
            boolean overWater = byCar != null && (crossesWater(byCar) || !plausibleRoad(byCar, a, b));
            if (!byTaxi || overWater) {
                stats.unreachable.incrementAndGet();
                notes.add(where + ": 그 시각 대중교통이 없어 일정을 다시 맞춤");
                TransportLeg leg = unconfirmed(estimate, RouteStatus.NO_CONNECTION, transitProvider, why, fromId, toId,
                        departAt);
                return new TransportLeg(leg.mode(), leg.durationMin(), leg.distanceKm(), leg.cost(),
                        leg.route().markedUnreachable());
            }
            stats.taxiFallbacks.incrementAndGet();
            notes.add(where + ": 그 시각 대중교통이 이어지지 않아 택시로 계산");
            if (byCar != null) {
                return new TransportLeg("TAXI", byCar.durationMin(), byCar.distanceKm(), byCar.cost(),
                        byCar.route().withNote(clip(why)));
            }
            return unconfirmed(guess, RouteStatus.NO_CONNECTION, transitProvider, why, fromId, toId, departAt);
        }

        private static boolean crossesWater(TransportLeg leg) {
            return leg.route() != null && leg.route().legs().stream()
                    .anyMatch(l -> "FERRY".equals(l.mode()) || "AIR".equals(l.mode()));
        }

        private static boolean plausibleRoad(TransportLeg leg, GeoPoint a, GeoPoint b) {
            double km = GeoUtils.haversine(a.lat(), a.lng(), b.lat(), b.lng());
            return leg.durationMin() > 0 ? km / (leg.durationMin() / 60.0) <= MAX_ROAD_KMH : km < 1;
        }

        /** 차례를 기다리는 동안 그 조회를 낸 묶음의 기한이 지나면 묻지 않는다. 버려진 조회가 다음 날 몫을 쓰지 않게 한다. */
        private RouteResult call(RouteQuery q, Batch issued) {
            long waitMs = Duration.between(owner.clock.instant(), issued.until()).toMillis();
            try {
                if (waitMs <= 0 || !owner.parallel.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) {
                    stats.budgetSkips.incrementAndGet();
                    return RouteResult.of(RouteStatus.UNAVAILABLE, null, BUDGET_EXCEEDED);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return RouteResult.of(RouteStatus.UNAVAILABLE, null, "interrupted");
            }
            try {
                if (owner.clock.instant().isAfter(issued.until())) {
                    stats.budgetSkips.incrementAndGet();
                    return RouteResult.of(RouteStatus.UNAVAILABLE, null, BUDGET_EXCEEDED);
                }
                RouteResult r = owner.engine.route(q, issued.calls());
                stats.record(r);
                return r;
            } catch (RuntimeException e) {
                log.warn("trip route engine error cause={}", e.getClass().getSimpleName());
                return RouteResult.of(RouteStatus.UNAVAILABLE, null, "engine error " + e.getClass().getSimpleName());
            } finally {
                owner.parallel.release();
            }
        }

        /** @param asked 물은 수단. 도로 경로는 토막 수단과 상관없이 이 수단 이름을 쓴다 */
        private TransportLeg found(RouteResult r, TransportLeg estimate, TravelMode asked, Long fromId, Long toId,
                                   ZonedDateTime departAt) {
            RouteItinerary best = r.earliestArrival().orElseThrow();
            long elapsed = (long) Math.ceil(Duration.between(departAt.toInstant(), best.arrival().toInstant())
                    .toSeconds() / 60.0);
            int minutes = (int) Math.max(best.durationMinutes(), elapsed)
                    + (int) Math.ceil(best.endpointGapMeters() / GAP_WALK_METERS_PER_MINUTE);
            int moving = best.legs().stream().mapToInt(RouteLeg::durationSeconds).sum() / 60;
            Integer wait = best.usesTransit() ? Math.max(0, minutes - moving) : null;
            BigDecimal km = distanceKm(best, estimate);
            RouteSource src = best.source();
            String validUntil = src.validity() != null && src.validity().until() != null
                    ? src.validity().until().toString()
                    : src.expiresAt() == null ? null : src.expiresAt().toString();
            String checkedAt = owner.clock.instant().toString();
            String mode = modeOf(estimate.mode(), asked, best);
            TransportRoute.Fare fare = fareOf(best, estimate, asked, mode, departAt, r.providerId(), checkedAt);
            TransportRoute route = new TransportRoute(RouteConfidence.of(r).name(), src.basis().name(),
                    RouteStatus.FOUND.name(), r.providerId(), answeredVersion(r), fromId, toId,
                    departAt.toOffsetDateTime().toString(), departAt.plusMinutes(minutes).toOffsetDateTime().toString(),
                    wait, best.transfers(), best.legs().stream().map(Session::legOf).toList(), fare, checkedAt,
                    validUntil, clip(r.reason()), clip(src.attribution()), false);
            return new TransportLeg(mode, minutes, km, fare.totalWon(), route);
        }

        /** 답을 낸 공급자가 조회할 때 쓴 데이터 판. 조회 중 판이 바뀌었거나 모르면 null이다. */
        private static String answeredVersion(RouteResult r) {
            return r.attempts().stream()
                    .filter(a -> a.status() == RouteStatus.FOUND && Objects.equals(a.providerId(), r.providerId()))
                    .map(ProviderAttempt::dataVersion).filter(Objects::nonNull).findFirst().orElse(null);
        }

        /**
         * 경로를 받지 못한 구간. 시간·요금은 거리 추정이다. 추정 요금은 추정한 수단(차·도보)의 요금이라 근거를 추정으로 적는다.
         */
        private TransportLeg unconfirmed(TransportLeg estimate, RouteStatus status, String provider, String reason,
                                         Long fromId, Long toId, ZonedDateTime departAt) {
            String checkedAt = owner.clock.instant().toString();
            TransportRoute route = new TransportRoute(RouteConfidence.UNAVAILABLE.name(), null, status.name(), provider,
                    null, fromId, toId, departAt.toOffsetDateTime().toString(), null, null, null, List.of(),
                    estimatedFare(estimate, checkedAt), checkedAt, null, clip(reason));
            return new TransportLeg(estimate.mode(), estimate.durationMin(), estimate.distanceKm(), estimate.cost(),
                    route);
        }

        /**
         * 구간 한 단위의 요금. 공급자가 준 요금이 있으면 그것이고, 없으면 수단마다 아는 만큼만 적는다. 대중교통 요금을 모르면
         * 금액을 지어내지 않고 모름으로 둔다(차 요금 추정은 대중교통 요금이 아니다).
         *
         * @param mode 스텝에 적을 수단 이름
         */
        private TransportRoute.Fare fareOf(RouteItinerary best, TransportLeg estimate, TravelMode asked, String mode,
                                           ZonedDateTime departAt, String provider, String checkedAt) {
            if ("WALK".equals(mode)) {
                return TransportRoute.Fare.of(BigDecimal.ZERO, null, TransportRoute.Fare.PERSON, "FREE", "walk",
                        checkedAt, null, List.of());
            }
            List<Fare> tickets = new ArrayList<>();
            Fare taxi = null;
            List<Fare> tolls = new ArrayList<>();
            for (Fare f : best.fares()) {
                switch (f.kind()) {
                    case TICKET -> tickets.add(f);
                    case TAXI_TABLE, TAXI_ESTIMATE -> taxi = f;
                    case TOLL -> tolls.add(f);
                }
            }
            String validUntil = best.source().validity() != null && best.source().validity().until() != null
                    ? best.source().validity().until().toString() : null;
            if (asked == TravelMode.TRANSIT) {
                // 이어 붙인 경로는 토막마다 승차권이 따로 온다. 통화가 섞이면 한 금액으로 더할 수 없어 모르는 것으로 둔다.
                boolean oneCurrency = tickets.stream().map(Fare::currency).distinct().count() == 1;
                Fare ticket = !oneCurrency ? null : new Fare(tickets.stream().map(Fare::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add), tickets.getFirst().currency(), Fare.Kind.TICKET);
                if (ticket != null) {
                    boolean confirmed = best.source().officialData() && (best.source().basis() == RouteBasis.REALTIME
                            || best.source().basis() == RouteBasis.OFFICIAL_SCHEDULE);
                    return TransportRoute.Fare.of(ticket.amount(), ticket.currency(), TransportRoute.Fare.PERSON,
                            confirmed ? "CONFIRMED" : "PUBLIC_DATA", provider, checkedAt, validUntil, List.of());
                }
                // 도시 간 이동은 대표 요금표 추정(IntercityLegs)이 있어 그 근거로 남긴다.
                if (ScheduleTuning.INTERCITY_LEG_MODES.contains(estimate.mode()) && estimate.cost() != null) {
                    return estimatedFare(estimate, checkedAt);
                }
                return TransportRoute.Fare.unknown(TransportRoute.Fare.PERSON, provider, checkedAt);
            }
            List<String> excludes = new ArrayList<>();
            if (tolls.isEmpty()) excludes.add("TOLL");
            if (asked == TravelMode.TAXI) {
                if (taxi == null) return TransportRoute.Fare.unknown(TransportRoute.Fare.VEHICLE, provider, checkedAt);
                String basis = taxi.kind() == Fare.Kind.TAXI_TABLE ? "OFFICIAL_TABLE" : "ESTIMATED";
                if (taxi.kind() == Fare.Kind.TAXI_TABLE) {
                    excludes.add("TIME_CHARGE");
                    if (nightFare(departAt)) {
                        excludes.add("NIGHT_SURCHARGE");
                        basis = "ESTIMATED";
                    }
                }
                BigDecimal amount = plusSameCurrency(taxi, tolls, excludes);
                return TransportRoute.Fare.of(amount, taxi.currency(), TransportRoute.Fare.VEHICLE, basis, provider,
                        checkedAt, null, excludes);
            }
            // 렌터카·자가용은 거리 추정 유류비다. 대여료는 구간마다 들지 않아 더하지 않고, 렌터카인지 몰라 빠진 항목으로 적는다.
            excludes.add("PARKING");
            excludes.add("RENTAL_FEE");
            if (estimate.cost() == null) return TransportRoute.Fare.unknown(TransportRoute.Fare.VEHICLE, provider, checkedAt);
            BigDecimal amount = plusSameCurrency(new Fare(estimate.cost(), "KRW", Fare.Kind.TOLL), tolls, excludes);
            return TransportRoute.Fare.of(amount, "KRW", TransportRoute.Fare.VEHICLE, "ESTIMATED", "distance estimate",
                    checkedAt, null, excludes);
        }

        /** 통행료를 같은 통화일 때만 더한다. 다른 통화 통행료는 금액에서 빼고 빠진 항목으로 적는다. */
        private static BigDecimal plusSameCurrency(Fare base, List<Fare> tolls, List<String> excludes) {
            BigDecimal amount = base.amount();
            for (Fare t : tolls) {
                if (Objects.equals(t.currency(), base.currency())) amount = amount.add(t.amount());
                else if (!excludes.contains("TOLL")) excludes.add("TOLL");
            }
            return amount;
        }

        private boolean nightFare(ZonedDateTime departAt) {
            int at = departAt.getHour() * 60 + departAt.getMinute();
            return at >= NIGHT_FARE_FROM_MIN || at < NIGHT_FARE_UNTIL_MIN;
        }

        /** 거리 추정 요금(원). 차·택시는 한 대마다, 그 밖(대중교통·도시 간 열차·비행기)은 사람마다 든다. */
        static TransportRoute.Fare estimatedFare(TransportLeg estimate, String checkedAt) {
            String per = VEHICLE_MODES.contains(estimate.mode()) ? TransportRoute.Fare.VEHICLE
                    : TransportRoute.Fare.PERSON;
            if (estimate.cost() == null) return TransportRoute.Fare.unknown(per, "distance estimate", checkedAt);
            return TransportRoute.Fare.of(estimate.cost(), "KRW", per, "ESTIMATED", "distance estimate", checkedAt,
                    null, List.of());
        }

        private static TransportRoute.Leg legOf(RouteLeg l) {
            return new TransportRoute.Leg(l.mode().name(), l.line(), l.operator(), l.fromName(), l.toName(),
                    l.departure() == null ? null : l.departure().toString(),
                    l.arrival() == null ? null : l.arrival().toString(),
                    (int) Math.round(l.durationSeconds() / 60.0));
        }

        private static GeoPoint point(PlaceCandidate c) {
            double[] p = CandidateGeo.coordsOf(c);
            return new GeoPoint(p[0], p[1]);
        }

        private static String clip(String s) {
            return s == null || s.length() <= NOTE_MAX ? s : s.substring(0, NOTE_MAX);
        }
    }

    /**
     * 플래너 수단 이름을 경로 엔진 수단으로 바꾼다. 비행기는 묻지 않는다(항공 공급자가 없다).
     *
     * @return 묻지 않는 수단이면 null
     */
    static TravelMode travelMode(String plannerMode) {
        if (plannerMode == null) return null;
        return switch (plannerMode) {
            case "WALK" -> TravelMode.WALK;
            case "CAR", "DRIVE" -> TravelMode.CAR;
            case "TAXI" -> TravelMode.TAXI;
            case "BIKE" -> TravelMode.BICYCLE;
            case "FLIGHT" -> null;
            default -> TravelMode.TRANSIT;
        };
    }

    /**
     * 스텝에 적을 수단 이름. 도시 간 구간은 이름이 구간 종류를 뜻해(거리 예산·저장 변환) 바꾸지 않고, 실제로 탄 수단은
     * 경로 근거에 둔다. 차·택시·도보로 물은 구간은 물은 수단이다(차 경로에 배가 끼어도 렌터카 구간이다). 대중교통은 가장
     * 오래 탄 수단이고, 걷기만 하면 도보다.
     */
    static String modeOf(String plannerMode, TravelMode asked, RouteItinerary it) {
        if (ScheduleTuning.INTERCITY_LEG_MODES.contains(plannerMode)) return plannerMode;
        switch (asked) {
            case TAXI -> {
                return "TAXI";
            }
            case CAR -> {
                return "DRIVE".equals(plannerMode) ? plannerMode : "CAR";
            }
            case WALK -> {
                return "WALK";
            }
            case BICYCLE -> {
                return "BIKE";
            }
            default -> {
            }
        }
        RouteLeg longest = it.legs().stream().filter(l -> l.mode().isTransit())
                .max(java.util.Comparator.comparingInt(RouteLeg::durationSeconds)).orElse(null);
        if (longest == null) return it.legs().isEmpty() ? plannerMode : "WALK";
        return switch (longest.mode()) {
            case BUS -> "BUS";
            case SUBWAY, TRAM -> "SUBWAY";
            case RAIL -> "TRAIN";
            case FERRY -> "FERRY";
            case AIR -> "FLIGHT";
            default -> "BUS";
        };
    }

    private static BigDecimal distanceKm(RouteItinerary it, TransportLeg estimate) {
        if (it.legs().isEmpty() || it.legs().stream().anyMatch(l -> l.distanceMeters() == null)) {
            return estimate.distanceKm();
        }
        int meters = it.legs().stream().mapToInt(RouteLeg::distanceMeters).sum();
        return BigDecimal.valueOf(meters / 1000.0).setScale(2, RoundingMode.HALF_UP);
    }

    /** 일정 하나의 경로 조회 기록. 평가가 공급자별 호출 수·캐시 적중률을 읽는다. */
    public static final class Stats {
        private final Map<String, AtomicLong> providerCalls = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> cacheHits = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> statuses = new ConcurrentHashMap<>();
        final AtomicLong memoHits = new AtomicLong();
        final AtomicLong budgetSkips = new AtomicLong();
        final AtomicLong taxiFallbacks = new AtomicLong();
        final AtomicLong taxiAlternatives = new AtomicLong();
        final AtomicLong unreachable = new AtomicLong();
        final AtomicLong suspectDetours = new AtomicLong();
        final AtomicLong retimes = new AtomicLong();
        final AtomicLong elapsedMillis = new AtomicLong();

        /** 거리 추정으로 지은 구간을 그 출발 시각에 실제로 물었을 때 걸리는 시간과의 차(분, 실제-추정). */
        private final List<Integer> estimateGaps = java.util.Collections.synchronizedList(new ArrayList<>());

        void compareEstimate(DayBuilder.Hop hop, TransportLeg answer) {
            if (answer == null || hop.leg().route() != null || answer.route() == null
                    || !RouteStatus.FOUND.name().equals(answer.route().status())) return;
            estimateGaps.add(answer.durationMin() - hop.leg().durationMin());
        }

        void record(RouteResult r) {
            statuses.computeIfAbsent(r.status().name(), k -> new AtomicLong()).incrementAndGet();
            for (ProviderAttempt a : r.attempts()) {
                (a.fromCache() ? cacheHits : providerCalls).computeIfAbsent(a.providerId(), k -> new AtomicLong())
                        .incrementAndGet();
            }
        }

        public Map<String, Object> summary() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("providerCalls", counts(providerCalls));
            out.put("cacheHits", counts(cacheHits));
            out.put("statuses", counts(statuses));
            out.put("memoHits", memoHits.get());
            out.put("budgetSkips", budgetSkips.get());
            out.put("taxiFallbacks", taxiFallbacks.get());
            out.put("taxiAlternatives", taxiAlternatives.get());
            out.put("unreachable", unreachable.get());
            out.put("suspectDetours", suspectDetours.get());
            out.put("retimes", retimes.get());
            out.put("routingMs", elapsedMillis.get());
            synchronized (estimateGaps) {
                out.put("estimateGaps", List.copyOf(estimateGaps));
            }
            return out;
        }

        private static Map<String, Long> counts(Map<String, AtomicLong> m) {
            Map<String, Long> out = new TreeMap<>();
            m.forEach((k, v) -> out.put(k, v.get()));
            return out;
        }
    }
}
