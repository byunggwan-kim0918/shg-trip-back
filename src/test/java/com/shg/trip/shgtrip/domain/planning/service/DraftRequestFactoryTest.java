package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.itinerary.dto.PlaceRestriction;
import com.shg.trip.shgtrip.domain.place.destination.Destination;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DraftRequestFactoryTest {

    private final PlaceRepository placeRepository = mock(PlaceRepository.class);
    private final DraftRequestFactory factory = new DraftRequestFactory(Destinations.fromClasspath(), placeRepository,
            mock(PlaceRegionValidator.class), mock(TripHubs.class));

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
                .isInstanceOf(BusinessException.class);
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

    private static Place lodging(long id, String name, double lat, double lng) {
        return Place.builder().id(id).name(name).address("부산").category("Travel and Transportation > Lodging > Hotel")
                .region("Busan").country("KR").placeRole("LODGING").latitude(BigDecimal.valueOf(lat))
                .longitude(BigDecimal.valueOf(lng)).build();
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
        return new VectorEnrichedInput("여행", List.of("food"), List.of("restaurant"), "normal", transport,
                new BigDecimal("500000"), LocalDate.of(2027, 5, 1), LocalDate.of(2027, 5, 3), "여행", List.of(),
                "여행", country, List.of(regions), List.of(), Map.of(), null, null, null, null, Map.of(),
                VectorEnrichedInput.CompanionType.UNKNOWN, 3, List.of());
    }
}
