package com.shg.trip.shgtrip.domain.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 경로 공급자 설정. 지역·공급자를 더할 때 코드 대신 이 설정에 항목을 더한다.
 *
 * @param coordinateDecimals 조회 전에 좌표를 이 자릿수로 맞춘다. 같은 장소 조회가 캐시를 함께 쓴다
 * @param taxiFaresFile      classpath 택시 요금표 CSV. 없으면 택시 요금을 모르는 것으로 둔다
 * @param taxiAnchorMaxKm    택시 요금 지역 기준점까지 이 거리 안일 때만 그 지역 요금을 쓴다
 * @param adminAreasFile     classpath 행정구역 경계 JSON. 공급자 coverage-areas 코드를 판정한다
 * @param areaBufferKm       행정구역 경계에서 이 거리 안은 구역 안으로 본다
 * @param allowNonCommercial 상업 이용이 안 되는 공급자(commercial-use: false)를 켠다. 평가·비교 실행에서만 켠다
 * @param breakerFailures    공급자가 연속으로 이만큼 실패하면 breakerOpen 동안 묻지 않는다
 */
@ConfigurationProperties(prefix = "routing")
public record RoutingProperties(
        boolean enabled,
        Integer coordinateDecimals,
        Map<String, ProviderSettings> providers,
        TagoSettings tago,
        String taxiFaresFile,
        Double taxiAnchorMaxKm,
        String adminAreasFile,
        Double areaBufferKm,
        boolean allowNonCommercial,
        Integer breakerFailures,
        Duration breakerOpen
) {

    /** 소수 넷째 자리는 약 11m다. 같은 장소 좌표는 한 칸에 들고 다른 입구는 갈린다. */
    private static final int DEFAULT_COORDINATE_DECIMALS = 4;
    /** 택시 요금 지역 기준점은 시·교통권 중심이라 이보다 멀면 다른 요금 지역일 가능성이 크다. */
    private static final double DEFAULT_TAXI_ANCHOR_MAX_KM = 40;
    /** 1:10m 해안선을 약 500m로 단순화한 경계라 매립지 공항·항만이 3km 안쪽 바다에 놓인다. */
    private static final double DEFAULT_AREA_BUFFER_KM = 3;
    /** 연속 실패가 이만큼이면 장애로 보고 잠시 묻지 않는다. 한 번의 시간 초과로 공급자를 끄지 않는다. */
    private static final int DEFAULT_BREAKER_FAILURES = 5;
    private static final Duration DEFAULT_BREAKER_OPEN = Duration.ofSeconds(60);

    public RoutingProperties {
        if (coordinateDecimals == null) coordinateDecimals = DEFAULT_COORDINATE_DECIMALS;
        if (providers == null) providers = Map.of();
        if (taxiAnchorMaxKm == null) taxiAnchorMaxKm = DEFAULT_TAXI_ANCHOR_MAX_KM;
        if (areaBufferKm == null) areaBufferKm = DEFAULT_AREA_BUFFER_KM;
        if (breakerFailures == null) breakerFailures = DEFAULT_BREAKER_FAILURES;
        if (breakerOpen == null) breakerOpen = DEFAULT_BREAKER_OPEN;
    }

    /**
     * 공급자 하나.
     *
     * @param type           어댑터 종류(otp, tfl, kakao-car)
     * @param priority       작을수록 먼저 묻는다
     * @param coverage       출발지와 도착지가 모두 한 사각형 안에 있을 때 이 공급자에 묻는다
     * @param coverageAreas  ISO 3166-2 구역 코드(JP-13) 또는 나라 코드(JP). 출발지와 도착지가 모두 이 구역들 안이면 묻는다
     * @param dailyLimit     0 이하면 한도 없음. 무료 한도를 넘으면 과금되는 공급자에는 둔다
     * @param cacheBucket    출발 시각을 이 간격으로 묶어 캐시 키를 만든다. 시간표 공급자는 짧게 둔다
     * @param officialData   운영기관이 낸 데이터인지
     * @param timezone       공급자 현지 시간대. 시간대 없는 시각을 주고받을 때와 일일 한도의 날짜를 셀 때 쓴다
     * @param snapToStopMeters 끝점이 길과 이어지지 않아 정류장을 못 찾을 때 이 반경 안 가장 가까운 정류장으로 옮겨 다시 묻는다. 0이면 끈다
     * @param stopToStop     도로 없이 시간표만 넣은 그래프다. 양 끝을 snapToStopMeters 안 가장 가까운 정류장으로 바꿔 묻는다
     * @param stopModes      stopToStop에서 고를 정류장의 차량 종류(OTP vehicleMode). 비면 모두
     * @param unverifiedAgencies 운행일·배차를 검증하지 못한 시간표의 운영기관 이름. 이들 구간이 든 경로는 UNVERIFIED_SCHEDULE이다
     * @param transferMinutes stopToStop 경로에 접근·도착 구간을 붙일 때 역에서 갈아타는 여유
     * @param monthlyLimit   0 이하면 한도 없음. 공급자 현지 달 기준
     * @param commercialUse  공급자 약정이 상업 서비스 이용을 허용하는지. false면 allowNonCommercial일 때만 쓴다
     * @param external       외부 API라 호출이 비용·한도를 쓰는지. 일정당 외부 호출 예산을 센다
     * @param verifiers      찾은 경로의 대중교통 구간을 공식 시간표와 대조할 {@link ScheduleVerifier} id
     * @param unverifiedFeeds 운행일을 확정하지 못하는 피드 id(빌드 피드 목록의 calendarSuspect). 이 피드 구간이 든 경로는 UNVERIFIED_SCHEDULE이다
     */
    /** 역 안 이동·표 구매를 포함한 갈아타기 여유. 큰 역 환승 안내 최소 시간에 맞춘다. */
    private static final int DEFAULT_TRANSFER_MINUTES = 10;

    public record ProviderSettings(
            String type,
            boolean enabled,
            int priority,
            String baseUrl,
            String apiKey,
            Set<TravelMode> modes,
            List<Area> coverage,
            List<String> coverageAreas,
            Duration timeout,
            int dailyLimit,
            int perMinuteLimit,
            Duration cacheTtl,
            Duration noConnectionTtl,
            Duration cacheBucket,
            boolean officialData,
            String attribution,
            String timezone,
            Integer snapToStopMeters,
            boolean stopToStop,
            Set<String> stopModes,
            Set<String> unverifiedAgencies,
            Integer transferMinutes,
            int monthlyLimit,
            Boolean commercialUse,
            boolean external,
            List<String> verifiers,
            Set<String> unverifiedFeeds
    ) {
        public ProviderSettings {
            modes = modes == null ? Set.of() : Set.copyOf(modes);
            coverage = coverage == null ? List.of() : List.copyOf(coverage);
            coverageAreas = coverageAreas == null ? List.of() : List.copyOf(coverageAreas);
            if (timeout == null) timeout = Duration.ofSeconds(8);
            if (cacheTtl == null) cacheTtl = Duration.ofHours(6);
            if (noConnectionTtl == null) noConnectionTtl = Duration.ofHours(1);
            if (cacheBucket == null) cacheBucket = Duration.ofMinutes(15);
            if (snapToStopMeters == null) snapToStopMeters = 0;
            stopModes = stopModes == null ? Set.of() : Set.copyOf(stopModes);
            unverifiedAgencies = unverifiedAgencies == null ? Set.of() : Set.copyOf(unverifiedAgencies);
            if (transferMinutes == null) transferMinutes = DEFAULT_TRANSFER_MINUTES;
            if (commercialUse == null) commercialUse = true;
            verifiers = verifiers == null ? List.of() : List.copyOf(verifiers);
            unverifiedFeeds = unverifiedFeeds == null ? Set.of() : Set.copyOf(unverifiedFeeds);
        }

        public ZoneId zone() {
            return timezone == null || timezone.isBlank() ? ZoneOffset.UTC : ZoneId.of(timezone);
        }

        public boolean covers(RouteQuery q, AdminAreas areas) {
            if (!modes.contains(q.mode())) return false;
            if (coverage.stream().anyMatch(a -> a.contains(q.origin()) && a.contains(q.destination()))) return true;
            return inAreas(q.origin(), areas) && inAreas(q.destination(), areas);
        }

        private boolean inAreas(GeoPoint p, AdminAreas areas) {
            return coverageAreas.stream().anyMatch(code -> areas.contains(code, p));
        }
    }

    /** 위도·경도 사각형. 날짜변경선을 넘는 영역은 둘로 나눠 적는다. */
    public record Area(double minLat, double minLng, double maxLat, double maxLng) {
        public boolean contains(GeoPoint p) {
            return p.lat() >= minLat && p.lat() <= maxLat && p.lng() >= minLng && p.lng() <= maxLng;
        }
    }

    /** 한국 TAGO 열차 시간표. 좌표가 아니라 역 id로 묻는다. */
    public record TagoSettings(boolean enabled, String baseUrl, String serviceKey, Duration timeout,
                               int dailyLimit) {
        public TagoSettings {
            if (timeout == null) timeout = Duration.ofSeconds(8);
        }
    }
}
