package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.dto.AlternativeData;
import com.shg.trip.shgtrip.domain.planning.dto.EnrichedInput;
import com.shg.trip.shgtrip.domain.planning.dto.ItineraryData;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PlaceData → Place 해소 규칙 검증.
 *
 * <p>해소는 **DB에서만** 한다. 예전에는 DB에 없으면 Google Places로 찾아 새 Place를 저장하고,
 * 그마저 실패하면 좌표 (0,0)짜리 fallback 행을 만들었다. 두 경로 모두 제거됐다 —
 * 전자는 Maps 약관 3.2.3(a)(세부정보·좌표 캐싱 금지) 위반이고, 후자는 지도에도 벡터 검색에도
 * 안 걸리는 쓰레기 행을 남긴다.
 *
 * <p>살아남은 안전장치는 거리 검증이다. 실사례: 제주 일정인데 DB에 같은 이름+주소로 캐시된
 * 서울 장소(약 450km)가 좌표로 채택돼 지도에 서울이 찍혔다. 기준점은 이제 후보 좌표의
 * median(referenceCoord) 하나뿐이다 — Redis 캐시는 쓰기 경로가 사라져 함께 제거됐다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ItineraryDataMapperTest {

    @Mock private PlaceRepository placeRepository;
    @Mock private RouteOptimizer routeOptimizer;
    @Mock private ItineraryAutoFixer autoFixer;

    @InjectMocks
    private ItineraryDataMapper mapper;

    private static final double JEJU_LAT = 33.5;
    private static final double JEJU_LNG = 126.5;
    private static final double SEOUL_LAT = 37.5;
    private static final double SEOUL_LNG = 127.0;

    private static final double[] JEJU_CENTER = {JEJU_LAT, JEJU_LNG};

    /** ItineraryDataMapper.MEDIAN_SAMPLE와 같은 값. 자체 median을 쓰려면 이만큼 필요하다. */
    private static final int MEDIAN_SAMPLE = 4;

    private EnrichedInput jejuInput() {
        return new EnrichedInput("제주", List.of(), List.of(), "normal", "any",
                BigDecimal.valueOf(1000000), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3),
                null, null, null);
    }

    private Place placeAt(String name, String address, double lat, double lng) {
        return Place.builder()
                .name(name).address(address)
                .latitude(BigDecimal.valueOf(lat)).longitude(BigDecimal.valueOf(lng))
                .category("관광").region("제주").country("Korea")
                .source("tourapi").savedAt(OffsetDateTime.now())
                .build();
    }

    private StepData step(int order, String name, String address) {
        return new StepData(order, 1, "09:00", "10:00",
                new PlaceData(name, address, "관광", "제주", "Korea"),
                List.of(), null, null, null, null, null, BigDecimal.ZERO);
    }

    private ItineraryData oneStep(String name, String address) {
        return new ItineraryData("제주 여행", "제주", BigDecimal.ZERO, List.of(),
                List.of(step(1, name, address)));
    }

    @Test
    @DisplayName("DB 장소가 여행지 근처면 그대로 채택한다")
    void acceptsNearbyDbPlace() {
        when(placeRepository.findByNameAndAddress("캐시장소", "addr-3"))
                .thenReturn(Optional.of(placeAt("캐시장소", "addr-3", JEJU_LAT + 0.01, JEJU_LNG + 0.01)));

        Itinerary itinerary = mapper.toEntity(
                oneStep("캐시장소", "addr-3"), jejuInput(), 1L, true, JEJU_CENTER);

        assertThat(itinerary.getSteps()).hasSize(1);
        assertThat(itinerary.getSteps().get(0).getPlace().getName()).isEqualTo("캐시장소");
    }

    @Test
    @DisplayName("DB 장소가 여행지에서 너무 멀면(오매칭) 스텝째 버린다")
    void dropsStepWhenDbPlaceIsFarFromDestination() {
        // 같은 이름+주소로 서울 장소가 캐시돼 있다 — 약 450km
        when(placeRepository.findByNameAndAddress("착한장소", "addr-1"))
                .thenReturn(Optional.of(placeAt("착한장소", "addr-1", SEOUL_LAT, SEOUL_LNG)));

        Itinerary itinerary = mapper.toEntity(
                oneStep("착한장소", "addr-1"), jejuInput(), 1L, true, JEJU_CENTER);

        assertThat(itinerary.getSteps()).isEmpty();
        verify(placeRepository, never()).save(any(Place.class));
    }

    @Test
    @DisplayName("기준 좌표가 없으면 거리 검증을 건너뛴다 — 기준 없이 거르면 멀쩡한 장소까지 버린다")
    void skipsDistanceCheckWithoutReferenceCoord() {
        when(placeRepository.findByNameAndAddress("서울장소", "addr-8"))
                .thenReturn(Optional.of(placeAt("서울장소", "addr-8", SEOUL_LAT, SEOUL_LNG)));

        Itinerary itinerary = mapper.toEntity(oneStep("서울장소", "addr-8"), jejuInput(), 1L, true);

        assertThat(itinerary.getSteps()).hasSize(1);
    }

    @Test
    @DisplayName("DB에 없는 장소는 (0,0) fallback을 만들지 않고 스텝째 버린다")
    void dropsStepInsteadOfCreatingFallbackPlace() {
        when(placeRepository.findByNameAndAddress(anyString(), anyString()))
                .thenReturn(Optional.empty());

        Itinerary itinerary = mapper.toEntity(
                oneStep("DB에없는곳", "addr-9"), jejuInput(), 1L, true, JEJU_CENTER);

        assertThat(itinerary.getSteps()).isEmpty();
        // 과거엔 여기서 latitude=0, longitude=0, address="주소 미확인" 행을 저장했다
        verify(placeRepository, never()).save(any(Place.class));
    }

    @Test
    @DisplayName("해소 못 한 스텝만 버리고 나머지는 유지한다")
    void dropsOnlyUnresolvedSteps() {
        when(placeRepository.findByNameAndAddress("있는곳", "addr-a"))
                .thenReturn(Optional.of(placeAt("있는곳", "addr-a", JEJU_LAT, JEJU_LNG)));
        when(placeRepository.findByNameAndAddress("없는곳", "addr-b"))
                .thenReturn(Optional.empty());

        ItineraryData data = new ItineraryData("제주 여행", "제주", BigDecimal.ZERO, List.of(),
                List.of(step(1, "있는곳", "addr-a"), step(2, "없는곳", "addr-b")));

        Itinerary itinerary = mapper.toEntity(data, jejuInput(), 1L, true, JEJU_CENTER);

        assertThat(itinerary.getSteps()).hasSize(1);
        assertThat(itinerary.getSteps().get(0).getPlace().getName()).isEqualTo("있는곳");
    }

    @Test
    @DisplayName("기준 좌표를 안 넘겨도 해소된 장소들의 median으로 거리 가드가 동작한다")
    void fallsBackToMedianOfResolvedPlaces() {
        // Fallback 경로(ItineraryGenerationExecutor)는 referenceCoord를 넘기지 않는다.
        // 예전엔 Redis 캐시/Google 검색이 기준을 만들어 줬지만 둘 다 사라졌다. 자체 median이
        // 없으면 "제주 일정에 서울 좌표" 오매칭 가드가 통째로 꺼진다.
        for (int i = 0; i < MEDIAN_SAMPLE; i++) {
            String name = "제주장소" + i;
            when(placeRepository.findByNameAndAddress(name, "a" + i))
                    .thenReturn(Optional.of(placeAt(name, "a" + i, JEJU_LAT, JEJU_LNG)));
        }
        when(placeRepository.findByNameAndAddress("서울장소", "zz"))
                .thenReturn(Optional.of(placeAt("서울장소", "zz", SEOUL_LAT, SEOUL_LNG)));

        List<StepData> steps = new java.util.ArrayList<>();
        for (int i = 0; i < MEDIAN_SAMPLE; i++) {
            steps.add(step(i + 1, "제주장소" + i, "a" + i));
        }
        steps.add(step(MEDIAN_SAMPLE + 1, "서울장소", "zz"));
        ItineraryData data = new ItineraryData("제주 여행", "제주", BigDecimal.ZERO, List.of(), steps);

        // referenceCoord 없이 호출 — median이 제주로 잡혀 서울 장소만 걸러져야 한다
        Itinerary itinerary = mapper.toEntity(data, jejuInput(), 1L, true);

        assertThat(itinerary.getSteps()).hasSize(MEDIAN_SAMPLE);
        assertThat(itinerary.getSteps())
                .noneMatch(s -> "서울장소".equals(s.getPlace().getName()));
    }

    @Test
    @DisplayName("먼 장소가 과반이면 거리 필터를 포기한다 — 일정을 통째로 비우는 것보다 낫다")
    void skipsDistanceFilterWhenMajorityWouldBeDropped() {
        // medianOf는 위도·경도 median을 독립 계산하므로 두 지역이 반반이면 중심점이 그 사이
        // 허공에 찍힌다 — 제주 2 + 서울 2 → (35.5, 126.75), 양쪽 224km로 전부 임계 초과.
        // 이 가드가 없으면 스텝 0개짜리 일정이 저장된다.
        when(placeRepository.findByNameAndAddress("제주1", "j1"))
                .thenReturn(Optional.of(placeAt("제주1", "j1", JEJU_LAT, JEJU_LNG)));
        when(placeRepository.findByNameAndAddress("제주2", "j2"))
                .thenReturn(Optional.of(placeAt("제주2", "j2", JEJU_LAT, JEJU_LNG)));
        when(placeRepository.findByNameAndAddress("서울1", "s1"))
                .thenReturn(Optional.of(placeAt("서울1", "s1", SEOUL_LAT, SEOUL_LNG)));
        when(placeRepository.findByNameAndAddress("서울2", "s2"))
                .thenReturn(Optional.of(placeAt("서울2", "s2", SEOUL_LAT, SEOUL_LNG)));

        ItineraryData data = new ItineraryData("전국 여행", "제주", BigDecimal.ZERO, List.of(),
                List.of(step(1, "제주1", "j1"), step(2, "제주2", "j2"),
                        step(3, "서울1", "s1"), step(4, "서울2", "s2")));

        Itinerary itinerary = mapper.toEntity(data, jejuInput(), 1L, true);

        assertThat(itinerary.getSteps()).hasSize(4);
    }

    @Test
    @DisplayName("해소 못 한 대안은 버린다 — place가 null이면 조회 시 PlaceResponse.from에서 NPE가 난다")
    void dropsUnresolvedAlternatives() {
        when(placeRepository.findByNameAndAddress("있는곳", "addr-a"))
                .thenReturn(Optional.of(placeAt("있는곳", "addr-a", JEJU_LAT, JEJU_LNG)));
        when(placeRepository.findByNameAndAddress("없는대안", "addr-x"))
                .thenReturn(Optional.empty());
        when(placeRepository.findByNameAndAddress("있는대안", "addr-y"))
                .thenReturn(Optional.of(placeAt("있는대안", "addr-y", JEJU_LAT, JEJU_LNG)));

        StepData s = new StepData(1, 1, "09:00", "10:00",
                new PlaceData("있는곳", "addr-a", "관광", "제주", "Korea"),
                List.of(
                        new AlternativeData("없는대안", "addr-x", "관광", "제주", "Korea", null, BigDecimal.ZERO),
                        new AlternativeData("있는대안", "addr-y", "관광", "제주", "Korea", null, BigDecimal.ZERO)),
                null, null, null, null, null, BigDecimal.ZERO);

        Itinerary itinerary = mapper.toEntity(
                new ItineraryData("제주 여행", "제주", BigDecimal.ZERO, List.of(), List.of(s)),
                jejuInput(), 1L, true, JEJU_CENTER);

        var alts = itinerary.getSteps().get(0).getAlternatives();
        assertThat(alts).hasSize(1);
        assertThat(alts.get(0).getPlace()).isNotNull();
        assertThat(alts.get(0).getPlace().getName()).isEqualTo("있는대안");
        // optionOrder는 살아남은 것들로 1부터 다시 매겨진다
        assertThat(alts.get(0).getOptionOrder()).isEqualTo(1);
    }

    @Test
    @DisplayName("앞 스텝이 버려지면 다음 스텝의 교통 정보를 비운다 — 사라진 출발지에서 온 구간이다")
    void clearsTransportAfterDroppedStep() {
        when(placeRepository.findByNameAndAddress("없는곳", "addr-b"))
                .thenReturn(Optional.empty());
        when(placeRepository.findByNameAndAddress("있는곳", "addr-a"))
                .thenReturn(Optional.of(placeAt("있는곳", "addr-a", JEJU_LAT, JEJU_LNG)));

        StepData dropped = step(1, "없는곳", "addr-b");
        StepData kept = new StepData(2, 1, "11:00", "12:00",
                new PlaceData("있는곳", "addr-a", "관광", "제주", "Korea"),
                List.of(), "car", 32, BigDecimal.valueOf(20.7), BigDecimal.valueOf(5000),
                null, BigDecimal.ZERO);

        Itinerary itinerary = mapper.toEntity(
                new ItineraryData("제주 여행", "제주", BigDecimal.ZERO, List.of(), List.of(dropped, kept)),
                jejuInput(), 1L, true, JEJU_CENTER);

        assertThat(itinerary.getSteps()).hasSize(1);
        ItineraryStep only = itinerary.getSteps().get(0);
        assertThat(only.getTransportationDistance()).isNull();
        assertThat(only.getTransportationDuration()).isNull();
        assertThat(only.getTransportationMode()).isNull();
    }

    @Test
    @DisplayName("장소를 해소하는 동안 외부 호출도 신규 저장도 하지 않는다")
    void resolvesWithoutAnyWrite() {
        when(placeRepository.findByNameAndAddress("장소", "addr-4"))
                .thenReturn(Optional.of(placeAt("장소", "addr-4", JEJU_LAT, JEJU_LNG)));

        mapper.toEntity(oneStep("장소", "addr-4"), jejuInput(), 1L, true, JEJU_CENTER);

        verify(placeRepository, never()).save(any(Place.class));
        verify(placeRepository, never()).saveAll(any());
    }
}
