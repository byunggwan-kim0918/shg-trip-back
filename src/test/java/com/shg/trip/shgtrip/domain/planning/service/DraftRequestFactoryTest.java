package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.itinerary.dto.PlaceRestriction;
import com.shg.trip.shgtrip.domain.itinerary.dto.TripLayout;
import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.DestinationCatalog;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.domain.planning.service.validation.PlaceRegionValidator;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DraftRequestFactoryTest {

    private final PlaceRepository placeRepository = mock(PlaceRepository.class);
    private final DestinationCatalog catalog = mock(DestinationCatalog.class);
    private final DraftRequestFactory factory = new DraftRequestFactory(Destinations.fromClasspath(), placeRepository,
            mock(PlaceRegionValidator.class), mock(TripHubs.class), catalog);

    @BeforeEach
    void enoughPlaces() {
        when(placeRepository.countRolesByRegions(any())).thenReturn(List.of(
                new Object[]{"SIGHT", 100L}, new Object[]{"MEAL", 100L}, new Object[]{"LODGING", 10L}));
    }

    @Test
    @DisplayName("함께 고른 상위 여행지가 있으면 근교 여행지는 따로 도시로 두지 않는다")
    void nearbyRegionJoinsRequestedParent() {
        assertThat(cities("JP", "Osaka", "Kyoto")).containsExactly("Osaka");
        assertThat(cities("JP", "Kyoto", "Nara")).containsExactly("Kyoto", "Nara");
        assertThat(cities("KR", "Busan", "Gyeongju")).containsExactly("Busan", "Gyeongju");
        assertThat(cities("KR", "Busan", "Osaka")).containsExactly("Busan");
    }

    @Test
    @DisplayName("꼭 할 경험은 시각이 있을 때만 시각을 붙이고, 아침 일출은 일출 요구가 된다")
    void requiredExperiencesBecomeWants() {
        ItineraryGenerateRequest request = request("car", List.of(
                new ItineraryGenerateRequest.RequiredExperience("해운대 일출", "해운대", "morning"),
                new ItineraryGenerateRequest.RequiredExperience("광안리 야경", "광안리", "evening"),
                new ItineraryGenerateRequest.RequiredExperience("감천문화마을 산책", "감천문화마을", null)));

        DraftRequestFactory.Prepared prepared = factory.prepare(request, enriched("KR", "car", "Busan"));

        assertThat(prepared.request().mustDo()).containsExactly("해운대 일출 (아침)", "광안리 야경 (저녁)", "감천문화마을 산책");
        assertThat(prepared.request().userWants()).extracting(DraftItineraryPlanner.UserWant::time)
                .containsExactly("sunrise", "evening", null);
        assertThat(prepared.request().departure()).isEqualTo(LocalTime.of(15, 0));
        assertThat(prepared.input().restrictions()).containsExactly(PlaceRestriction.PORK);
        assertThat(prepared.input().partySize()).isEqualTo(2);
    }

    @Test
    @DisplayName("대화에서 그대로 두기로 한 요구는 꼭 할 경험에 표시하고, 꼭 할 경험이 아니면 따로 요구로 넣는다")
    void acceptedUnmetBecomesAcceptedWants() {
        ItineraryGenerateRequest base = request("car", List.of(
                new ItineraryGenerateRequest.RequiredExperience("나카스 야타이", "나카스", "evening"),
                new ItineraryGenerateRequest.RequiredExperience("광안리 야경", "광안리", "evening")));
        ItineraryGenerateRequest request = new ItineraryGenerateRequest(base.mode(), base.destination(), base.themes(),
                base.categories(), base.pace(), base.transportPref(), base.budget(), base.startDate(), base.endDate(),
                base.description(), null, null, base.companion(), base.partySize(), base.arrivalTime(),
                base.departureTime(), base.requiredExperiences(), null, base.restrictions(), null,
                List.of("나카스 야타이", "캐널시티 하카타"));

        DraftRequestFactory.Prepared prepared = factory.prepare(request, enriched("KR", "car", "Busan"));

        assertThat(prepared.request().userWants()).extracting(DraftItineraryPlanner.UserWant::text,
                        DraftItineraryPlanner.UserWant::accepted)
                .containsExactly(tuple("나카스 야타이", true), tuple("광안리 야경", false), tuple("캐널시티 하카타", true));
        assertThat(prepared.request().mustDo()).doesNotContain("캐널시티 하카타");
    }

    @Test
    @DisplayName("한국 면허로 운전할 수 없는 나라의 자동차 요청은 대중교통 기준으로 바꾸고 알린다")
    void noKoreanLicense() {
        DraftRequestFactory.Prepared prepared = factory.prepare(request("car", List.of()),
                enriched("VN", "car", "Da Nang"));

        assertThat(prepared.input().transportPref()).isEqualTo("any");
        assertThat(prepared.notices()).singleElement().asString().contains("베트남은 한국 운전면허로 운전할 수 없어");
        assertThat(prepared.request().country()).isEqualTo("VN");
    }

    @Test
    @DisplayName("장소가 부족한 근교 여행지만 고르면 상위 도시를 함께 고르라고 알린다")
    void sparseNearbyRegion() {
        when(placeRepository.countRolesByRegions(any())).thenReturn(List.<Object[]>of(new Object[]{"SIGHT", 3L}));

        assertThatThrownBy(() -> factory.prepare(request("any", List.of()), enriched("JP", "any", "Kyoto")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_PLACE_DATA);
                    assertThat(e.getMessage()).contains("'오사카'까지 함께 넣어");
                });
    }

    @Test
    @DisplayName("여행 도시를 알 수 없으면 장소 부족으로 끝낸다")
    void unknownRegion() {
        assertThatThrownBy(() -> factory.prepare(request("any", List.of()), enriched("KR", "any", "Atlantis")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.INSUFFICIENT_PLACE_DATA.getMessage());
    }

    @Test
    @DisplayName("사전에 있는 준비 전 여행지는 상태대로 알리고 요청을 남긴다")
    void catalogedDestinationExplainsStatus() {
        when(catalog.lookup("Moab", "US")).thenReturn(java.util.Optional.of(new DestinationCatalog.Entry(
                "US:gn:5543307", "US", "town", "Moab", "모압", DestinationCatalog.Status.CANDIDATE)));
        when(catalog.lookup("Otaru2", "JP")).thenReturn(java.util.Optional.of(new DestinationCatalog.Entry(
                "JP:gn:1", "JP", "town", "Otaru2", null, DestinationCatalog.Status.PREPARING)));

        assertThatThrownBy(() -> factory.prepare(request("any", List.of()), enriched("US", "any", "Moab")))
                .hasMessageContaining("'모압'은(는) 아직 일정을 만들 수 없는 여행지예요");
        assertThatThrownBy(() -> factory.prepare(request("any", List.of()), enriched("JP", "any", "Otaru2")))
                .hasMessageContaining("모으는 중");
        org.mockito.Mockito.verify(catalog).recordRequest("US:gn:5543307");
        org.mockito.Mockito.verify(catalog).recordRequest("JP:gn:1");
    }

    @Test
    @DisplayName("고른 숙소가 여럿이면 첫 숙소만 고정하고 나머지는 방문 요구로 만들지 않는다")
    void onlyFirstSelectedLodgingIsBooked() {
        Place hotelA = lodging(1L, "광안 호텔", 35.153, 129.118);
        Place hotelB = lodging(2L, "해운대 호텔", 35.159, 129.160);
        when(placeRepository.findAllById(any())).thenReturn(List.of(hotelA, hotelB));
        ItineraryGenerateRequest base = request("car", List.of());
        ItineraryGenerateRequest withHotels = new ItineraryGenerateRequest(base.mode(), base.destination(),
                base.themes(), base.categories(), base.pace(), base.transportPref(), base.budget(), base.startDate(),
                base.endDate(), base.description(), List.of(1L, 2L), null, base.companion(), base.partySize(),
                base.arrivalTime(), base.departureTime(), base.requiredExperiences(), null, base.restrictions());

        DraftRequestFactory.Prepared prepared = factory.prepare(withHotels, enriched("KR", "car", "Busan"));

        assertThat(prepared.request().bookedLodging().placeId()).isEqualTo(1L);
        assertThat(prepared.request().userWants()).isEmpty();
        assertThat(prepared.notices()).anyMatch(n -> n.contains("'해운대 호텔'") && n.contains("'광안 호텔'"));
    }

    @Test
    @DisplayName("피할 장소 이름은 요청 문장이 아니라 초안 프롬프트의 가지 않을 곳 줄로 간다")
    void avoidPlaceNamesReachDraftPrompt() {
        ItineraryGenerateRequest base = request("car", List.of());
        ItineraryGenerateRequest avoiding = new ItineraryGenerateRequest(base.mode(), base.destination(),
                base.themes(), base.categories(), base.pace(), base.transportPref(), base.budget(), base.startDate(),
                base.endDate(), base.description(), null, null, base.companion(), base.partySize(),
                base.arrivalTime(), base.departureTime(), base.requiredExperiences(), null, base.restrictions(),
                List.of("감천문화마을", "해운대"));

        DraftRequestFactory.Prepared prepared = factory.prepare(avoiding, enriched("KR", "car", "Busan"));

        assertThat(DraftItineraryPlanner.prompt(prepared.request())).contains("가지 않을 곳: 감천문화마을, 해운대");
        assertThat(DraftItineraryPlanner.prompt(factory.prepare(base, enriched("KR", "car", "Busan")).request()))
                .doesNotContain("가지 않을 곳");
    }

    @Test
    @DisplayName("한적한 곳 취향은 초안 요청에 장소 성향으로 적고, 성향이 없으면 적지 않는다")
    void fameMixReachesDraftPrompt() {
        ItineraryGenerateRequest base = request("car", List.of());
        VectorEnrichedInput plain = enriched("KR", "car", "Jeju");
        VectorEnrichedInput quiet = new VectorEnrichedInput("여행", List.of("nature", "quiet"), List.of("restaurant"),
                "relaxed", "car", new BigDecimal("500000"), LocalDate.of(2027, 5, 1), LocalDate.of(2027, 5, 3), "여행",
                List.of(), "여행", "KR", List.of("Jeju"), List.of(), Map.of(), null, null, null, null, Map.of(),
                VectorEnrichedInput.CompanionType.UNKNOWN, 3, List.of());

        assertThat(DraftItineraryPlanner.prompt(factory.prepare(base, quiet).request())).contains("장소 성향: 한적한 곳 위주");
        assertThat(DraftItineraryPlanner.prompt(factory.prepare(base, plain).request())).doesNotContain("장소 성향");
    }

    private static Place lodging(long id, String name, double lat, double lng) {
        return Place.builder().id(id).name(name).address("부산").category("Travel and Transportation > Lodging > Hotel")
                .region("Busan").country("KR").placeRole("LODGING").latitude(BigDecimal.valueOf(lat))
                .longitude(BigDecimal.valueOf(lng)).build();
    }

    @Test
    @DisplayName("홋카이도: 묵는 도시가 기준 도시가 되고, 들를 근교는 검색 지역에 들어가며, 지역별 이동 방법을 말한 순서대로 둔다")
    void hokkaidoLayoutDrivesCitiesNightsAndModes() {
        ItineraryGenerateRequest base = request("any", List.of());
        ItineraryGenerateRequest request = new ItineraryGenerateRequest(base.mode(), "홋카이도", base.themes(),
                base.categories(), base.pace(), base.transportPref(), base.budget(), base.startDate(),
                LocalDate.of(2027, 5, 5), base.description(), null, null, base.companion(), base.partySize(), null,
                null, List.of(), null, List.of(), null, null,
                new TripLayout(List.of("삿포로", "오타루", "후라노", "비에이"),
                        List.of(new TripLayout.Stay("삿포로", 2), new TripLayout.Stay("후라노", 1),
                                new TripLayout.Stay("삿포로", 1)),
                        List.of(new TripLayout.AreaTransport("삿포로", "any"),
                                new TripLayout.AreaTransport("오타루", "any"),
                                new TripLayout.AreaTransport("비에이", "car"))));

        DraftRequestFactory.Prepared prepared = factory.prepare(request,
                enriched("JP", "any", LocalDate.of(2027, 5, 5), "Sapporo", "Furano"));

        DraftItineraryPlanner.Layout layout = prepared.request().layout();
        assertThat(prepared.request().cities()).extracting(Destination::name).containsExactly("Sapporo", "Furano");
        assertThat(prepared.input().regions()).contains("Otaru", "Biei");
        assertThat(layout.visitAreas()).extracting(Destination::name)
                .containsExactly("Sapporo", "Otaru", "Furano", "Biei");
        assertThat(layout.nights()).extractingFromEntries(e -> e.getKey() + ":" + e.getValue().name())
                .containsExactly("1:Sapporo", "2:Sapporo", "3:Furano", "4:Sapporo");
        assertThat(layout.modes()).extractingFromEntries(e -> e.getKey().name() + ":" + e.getValue())
                .containsExactly("Sapporo:any", "Otaru:any", "Biei:car");
        assertThat(prepared.request().mustDo()).isEmpty();
    }

    @Test
    @DisplayName("숙박 계획이 일부 밤만 정했으면 보강이 고른 도시도 기준 도시로 남긴다")
    void partialStaysKeepEnrichedCities() {
        ItineraryGenerateRequest base = request("any", List.of());
        ItineraryGenerateRequest request = new ItineraryGenerateRequest(base.mode(), "간사이", base.themes(),
                base.categories(), base.pace(), base.transportPref(), base.budget(), base.startDate(),
                base.endDate(), base.description(), null, null, base.companion(), base.partySize(), null, null,
                List.of(), null, List.of(), null, null,
                new TripLayout(List.of(), List.of(new TripLayout.Stay("교토", 1)), List.of()));

        DraftRequestFactory.Prepared prepared = factory.prepare(request, enriched("JP", "any", "Osaka", "Kyoto"));

        assertThat(prepared.request().cities()).extracting(Destination::name).containsExactly("Kyoto", "Osaka");
        assertThat(prepared.request().layout().nights()).extractingFromEntries(e -> e.getKey() + ":" + e.getValue().name())
                .containsExactly("1:Kyoto");
    }

    @Test
    @DisplayName("목록에 없는 지역은 그대로 두기로 한 요구로 넣어 빠져도 저장을 막지 않고, 행정 단위를 붙인 도시는 찾는다")
    void unknownVisitAreaIsHintOnly() {
        DraftRequestFactory.Prepared prepared = factory.prepare(withLayout("부산",
                new TripLayout(List.of("부산", "경주시", "기장 연화리"), List.of(), List.of())),
                enriched("KR", "any", "Busan"));

        assertThat(prepared.request().mustDo()).contains("기장 연화리");
        assertThat(prepared.request().userWants()).filteredOn(w -> w.text().equals("기장 연화리")).singleElement()
                .extracting(DraftItineraryPlanner.UserWant::accepted).isEqualTo(true);
        assertThat(prepared.request().layout().visitAreas()).extracting(Destination::nameKo)
                .containsExactly("부산", "경주");
        assertThat(prepared.notices()).anyMatch(n -> n.contains("'기장 연화리'"));
    }

    @Test
    @DisplayName("장소 데이터가 없는 들를 지역은 조건에서 빼고 알린다")
    void visitAreaWithoutPlacesIsNoticed() {
        when(placeRepository.countRolesByRegions(List.of("Gyeongju"))).thenReturn(List.of());

        DraftRequestFactory.Prepared prepared = factory.prepare(withLayout("부산",
                new TripLayout(List.of("부산", "경주"), List.of(), List.of())), enriched("KR", "any", "Busan"));

        assertThat(prepared.request().layout().visitAreas()).extracting(Destination::nameKo).containsExactly("부산");
        assertThat(prepared.notices()).anyMatch(n -> n.contains("'경주'") && n.contains("장소 정보가 없어"));
    }

    @Test
    @DisplayName("한국 면허로 운전할 수 없는 나라는 지역별 렌터카도 택시·대중교통으로 바꾸고 알린다")
    void areaCarInNoDrivingCountry() {
        DraftRequestFactory.Prepared prepared = factory.prepare(withLayout("상하이",
                new TripLayout(List.of(), List.of(), List.of(new TripLayout.AreaTransport("쑤저우", "car")))),
                enriched("CN", "any", "Shanghai"));

        assertThat(prepared.request().layout().modes()).extractingFromEntries(e -> e.getKey().name() + ":" + e.getValue())
                .containsExactly("Suzhou:any");
        assertThat(prepared.notices()).anyMatch(n -> n.contains("운전할 수 없어"));
    }

    @Test
    @DisplayName("여행 기간을 넘는 박은 세지 않고, 마지막 밤 도시를 출발 도시로 본다")
    void nightsBeyondTripAreDropped() {
        DraftRequestFactory.Prepared prepared = factory.prepare(withLayout("홋카이도",
                new TripLayout(List.of(), List.of(new TripLayout.Stay("후라노", 1), new TripLayout.Stay("삿포로", 3)),
                        List.of())), enriched("JP", "any", "Sapporo"));

        assertThat(prepared.request().layout().nights()).extractingFromEntries(e -> e.getKey() + ":" + e.getValue().name())
                .containsExactly("1:Furano", "2:Sapporo");
        assertThat(prepared.request().cities()).extracting(Destination::name).containsExactly("Furano", "Sapporo");

        DraftRequestFactory.Prepared outside = factory.prepare(withLayout("홋카이도",
                new TripLayout(List.of(), List.of(new TripLayout.Stay("후라노", 1), new TripLayout.Stay("삿포로", 1),
                        new TripLayout.Stay("하코다테", 1)), List.of())), enriched("JP", "any", "Sapporo"));
        assertThat(outside.request().cities()).as("기간 밖에만 묵는 도시").extracting(Destination::name)
                .containsExactly("Furano", "Sapporo");
    }

    @Test
    @DisplayName("지역별 이동은 같은 도시를 다른 이름으로 다시 말해도 나중 값을 쓰고, 찾지 못한 지역은 알린다")
    void areaTransportLaterWinsAndUnknownIsNoticed() {
        DraftRequestFactory.Prepared prepared = factory.prepare(withLayout("부산",
                new TripLayout(List.of(), List.of(), List.of(new TripLayout.AreaTransport("부산", "any"),
                        new TripLayout.AreaTransport("부산시", "car"), new TripLayout.AreaTransport("기장 연화리", "car")))),
                enriched("KR", "any", "Busan"));

        assertThat(prepared.request().layout().modes()).extractingFromEntries(e -> e.getKey().nameKo() + ":" + e.getValue())
                .containsExactly("부산:car");
        assertThat(prepared.notices()).anyMatch(n -> n.contains("'기장 연화리'") && n.contains("이동 방법"));
    }

    @Test
    @DisplayName("목록 밖 동네는 이름에 그 지명이 든 장소가 한곳에 모였을 때만 구역으로 잇는다")
    void districtFromClusteredPlaces() {
        List<double[]> susukino = List.of(new double[]{43.0556, 141.3530}, new double[]{43.0549, 141.3545},
                new double[]{43.0561, 141.3522}, new double[]{43.0570, 141.3551}, new double[]{43.0640, 141.3469});
        List<double[]> chain = List.of(new double[]{43.06, 141.35}, new double[]{43.19, 140.99},
                new double[]{43.34, 142.38}, new double[]{42.77, 141.69});

        assertThat(DraftRequestFactory.district(susukino)).hasValueSatisfying(d -> {
            assertThat(d[0]).isBetween(43.05, 43.06);
            assertThat(d[2]).isBetween(1.0, 5.0);
        });
        assertThat(DraftRequestFactory.district(chain)).as("도시 곳곳의 같은 이름 상호").isEmpty();
        assertThat(DraftRequestFactory.district(susukino.subList(0, 2))).as("장소가 너무 적음").isEmpty();
    }

    private static ItineraryGenerateRequest withLayout(String destination, TripLayout layout) {
        ItineraryGenerateRequest base = request("any", List.of());
        return new ItineraryGenerateRequest(base.mode(), destination, base.themes(), base.categories(), base.pace(),
                base.transportPref(), base.budget(), base.startDate(), base.endDate(), base.description(), null, null,
                base.companion(), base.partySize(), null, null, List.of(), null, List.of(), null, null, layout);
    }

    private List<String> cities(String country, String... regions) {
        return factory.citiesOf(enriched(country, "any", regions)).stream().map(Destination::name).toList();
    }

    private static ItineraryGenerateRequest request(String transport,
                                                    List<ItineraryGenerateRequest.RequiredExperience> required) {
        return new ItineraryGenerateRequest(ItineraryGenerateRequest.PlanningMode.AUTO, "여행", List.of("food"),
                List.of("restaurant"), "normal", transport, new BigDecimal("500000"), LocalDate.of(2027, 5, 1),
                LocalDate.of(2027, 5, 3), "여행", null, null, "couple", null, null, "15:00", required, null,
                List.of(PlaceRestriction.PORK));
    }

    private static VectorEnrichedInput enriched(String country, String transport, String... regions) {
        return enriched(country, transport, LocalDate.of(2027, 5, 3), regions);
    }

    private static VectorEnrichedInput enriched(String country, String transport, LocalDate end, String... regions) {
        return new VectorEnrichedInput("여행", List.of("food"), List.of("restaurant"), "normal", transport,
                new BigDecimal("500000"), LocalDate.of(2027, 5, 1), end, "여행", List.of(),
                "여행", country, List.of(regions), List.of(), Map.of(), null, null, null, null, Map.of(),
                VectorEnrichedInput.CompanionType.UNKNOWN, 3, List.of());
    }
}
