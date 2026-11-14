package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest.RequiredExperience;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequiredExperiencesTest {

    private static final String BEACH = "Landmarks and Outdoors > Beach";
    private static final String PARK = "Landmarks and Outdoors > Park";
    private static final String RESTAURANT = "Dining and Drinking > Restaurant > Seafood Restaurant";

    private final List<PlaceCandidate> candidates = List.of(
            place(1, "광안리 유가횟집", RESTAURANT, "MEAL", null),
            place(2, "광안리해수욕장", BEACH, "SIGHT", 12),
            place(3, "해운대블루라인파크", PARK, "SIGHT", 5),
            place(4, "해운대해수욕장", BEACH, "SIGHT", 30),
            place(5, "해운대 관광특구", PARK, "SIGHT", 40),
            place(6, "감천문화마을", PARK, "SIGHT", 20),
            place(7, "Paradise Hotel Ocean Pool", "Travel and Transportation > Lodging > Resort", "ACTIVITY", 3),
            place(8, "이기대해안산책로", PARK, "SIGHT", 8));

    @Test
    @DisplayName("지역 낱말이 이름에 든 관광 후보에 시간대와 보호를 싣고, 같은 이름의 식당은 고르지 않는다")
    void attachesIntentToSightNotRestaurant() {
        RequiredExperiences.Match match = RequiredExperiences.attach(
                List.of(new RequiredExperience("광안리 야경", "광안리", "evening")), candidates);

        PlaceCandidate beach = match.candidates().get(1);
        assertThat(match.matched()).extracting(m -> m.place().name()).containsExactly("광안리해수욕장");
        assertThat(beach.intentProtected()).isTrue();
        assertThat(beach.intentTime()).isEqualTo("evening");
        assertThat(beach.userSelected()).isFalse();
        assertThat(match.candidates().get(0)).isSameAs(candidates.get(0));
        assertThat(RequiredExperiences.places(match.candidates())).containsExactly(Map.entry(102L, "광안리해수욕장"));
    }

    @Test
    @DisplayName("이름이 낱말로 시작하는 곳, 그다음 언어판 많은 곳을 고르고 지정 구역은 고르지 않는다")
    void prefersPrefixThenFameAndSkipsZones() {
        RequiredExperiences.Match match = RequiredExperiences.attach(List.of(
                new RequiredExperience("해운대 일출", "해운대", "morning"),
                new RequiredExperience("블루라인파크", "블루라인파크", null)), candidates);

        assertThat(match.matched()).extracting(m -> m.place().name())
                .containsExactly("해운대해수욕장", "해운대블루라인파크");
        assertThat(match.matched().get(1).place().intentTime()).isNull();
    }

    @Test
    @DisplayName("낱말이 여럿이면 붙여 쓴 전체로 먼저 찾고, 없으면 낱말 차례로 찾는다")
    void triesWholePhraseBeforeWords() {
        RequiredExperiences.Match match = RequiredExperiences.attach(List.of(
                new RequiredExperience("해운대 블루라인파크 야경", "해운대 블루라인파크", "evening"),
                new RequiredExperience("감천문화마을 산책", "감천문화마을 산책", null)), candidates);

        assertThat(match.matched()).extracting(m -> m.place().name())
                .containsExactly("해운대블루라인파크", "감천문화마을");
    }

    @Test
    @DisplayName("같은 곳을 다르게 말한 경험은 한 장소에 모으고 먼저 말한 시간대를 따른다")
    void sameAreaSharesOnePlace() {
        RequiredExperiences.Match match = RequiredExperiences.attach(List.of(
                new RequiredExperience("감천 아침 산책", "감천 산책", "morning"),
                new RequiredExperience("감천 야경", "감천", "evening")), candidates);

        assertThat(match.matched()).extracting(m -> m.place().name()).containsExactly("감천문화마을", "감천문화마을");
        assertThat(match.matched()).allSatisfy(m -> assertThat(m.place().intentTime()).isEqualTo("morning"));
        assertThat(match.places()).containsOnlyKeys(106L);
    }

    @Test
    @DisplayName("뒤 낱말(산책)로는 찾지 않고, 관광 역할이어도 숙소 대분류는 고르지 않으며, 모르는 시간대는 버린다")
    void skipsTrailingWordsLodgingAndUnknownTiming() {
        RequiredExperiences.Match match = RequiredExperiences.attach(List.of(
                new RequiredExperience("기장 산책", "기장 산책", null),
                new RequiredExperience("파라다이스 수영장", "paradise", null),
                new RequiredExperience("감천 야경", "감천", "night")), candidates);

        assertThat(match.unmatched()).extracting(RequiredExperience::text)
                .containsExactly("기장 산책", "파라다이스 수영장");
        assertThat(match.matched().get(0).place().intentTime()).isNull();
    }

    @Test
    @DisplayName("요청에 필수 경험이 없으면 후보 목록을 그대로 둔다")
    void noExperiencesKeepsCandidates() {
        assertThat(RequiredExperiences.attach(null, candidates).candidates()).isSameAs(candidates);
        assertThat(RequiredExperiences.places(candidates)).isEmpty();
    }

    @Test
    @DisplayName("못 맞춘 경험은 이름이 든 장소가 일정에 없을 때만 안내한다")
    void unplacedOnlyWhenNoStepCarriesTheName() {
        RequiredExperiences.Match match = RequiredExperiences.attach(List.of(
                new RequiredExperience("광안리 야경", "광안리", "evening"),
                new RequiredExperience("자갈치 회", "자갈치", null),
                new RequiredExperience("기장 해녀촌", "기장", null)), candidates);
        List<StepData> steps = List.of(step("감천문화마을", 106L), step("자갈치 꼼장어", 900L));

        assertThat(match.places()).containsOnlyKeys(102L);
        assertThat(RequiredExperiences.unplaced(match, steps)).containsExactly("기장 해녀촌");
    }

    private static StepData step(String name, Long placeId) {
        return new StepData(1, 1, "10:00", "11:00",
                new PlaceData(name, null, BEACH, "Busan", "KR", null, null, placeId, null),
                List.of(), null, null, null, null, null, null);
    }

    private static PlaceCandidate place(int index, String name, String category, String role, Integer sitelinks) {
        return new PlaceCandidate(index, 100L + index, name, "부산 수영구 " + index, category, List.of(), "Busan",
                "KR", BigDecimal.valueOf(35.15), BigDecimal.valueOf(129.11), null, null, 0.9, null, null,
                null, 60, false, null, null, null, sitelinks, role, null, false, null);
    }
}
