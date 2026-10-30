package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * TourAPI 이미지 백필 규칙 테스트.
 *
 * <p>시더는 원래 중복 행을 스킵만 했다. 그 사이 Google 동기화가 이 행들의 image_url을 덮어썼고,
 * 약관에 따라 Google 데이터를 지우면서 같이 비었다 — 실측으로 TourAPI 제주 관광지 175건이
 * DB에 전부 있는데 이미지는 0건이었고(154건은 source까지 'google'로 재라벨), 그 탓에 일정
 * 커버 이미지가 전멸했다. HTTP는 건드리지 않고 백필 판정만 본다.
 */
@ExtendWith(MockitoExtension.class)
class TourApiSeederTest {

    @Mock
    private PlaceRepository placeRepository;

    private TourApiSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = new TourApiSeeder(placeRepository, new ObjectMapper());
    }

    private Place imageless(String name) {
        return Place.builder()
                .name(name).address("addr").region("Jeju").country("KR")
                .latitude(BigDecimal.valueOf(33.45)).longitude(BigDecimal.valueOf(126.93))
                .category("Landmarks and Outdoors > Tourist Attraction")
                .source("google")          // Google 동기화가 라벨을 덮어쓴 TourAPI 행
                .active(true).savedAt(OffsetDateTime.now())
                .build();
    }

    @Test
    @DisplayName("이미지가 빈 기존 행을 TourAPI 대표 이미지로 채운다")
    void fillsImagelessRow() {
        Place target = imageless("성산일출봉");
        given(placeRepository.findImagelessByNameAndRegion("성산일출봉", "Jeju"))
                .willReturn(List.of(target));

        int filled = seeder.backfillImage("성산일출봉", "Jeju", "http://tong.visitkorea.or.kr/a.jpg");

        assertThat(filled).isEqualTo(1);
        assertThat(target.getImageUrl()).isEqualTo("http://tong.visitkorea.or.kr/a.jpg");
        verify(placeRepository).saveAll(List.of(target));
    }

    @Test
    @DisplayName("같은 이름이 여러 행이면 전부 채운다 — 중복 적재분까지 살린다")
    void fillsAllDuplicateRows() {
        Place a = imageless("우도 등대");
        Place b = imageless("우도 등대");
        given(placeRepository.findImagelessByNameAndRegion("우도 등대", "Jeju"))
                .willReturn(List.of(a, b));

        assertThat(seeder.backfillImage("우도 등대", "Jeju", "http://img/u.jpg")).isEqualTo(2);
        assertThat(a.getImageUrl()).isEqualTo("http://img/u.jpg");
        assertThat(b.getImageUrl()).isEqualTo("http://img/u.jpg");
    }

    @Test
    @DisplayName("TourAPI가 이미지를 주지 않으면 아무것도 하지 않는다 — 조회조차 하지 않는다")
    void skipsWhenApiHasNoImage() {
        assertThat(seeder.backfillImage("무명장소", "Jeju", null)).isZero();
        assertThat(seeder.backfillImage("무명장소", "Jeju", "")).isZero();
        assertThat(seeder.backfillImage("무명장소", "Jeju", "   ")).isZero();

        verify(placeRepository, never()).findImagelessByNameAndRegion(anyString(), anyString());
        verify(placeRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("이미 이미지가 있는 행은 대상이 아니다 — 쿼리가 걸러내므로 저장도 하지 않는다")
    void doesNotTouchRowsThatAlreadyHaveImage() {
        // findImagelessByNameAndRegion이 imageUrl IS NULL만 반환한다는 계약에 기댄다
        given(placeRepository.findImagelessByNameAndRegion("천지연폭포", "Jeju"))
                .willReturn(List.of());

        assertThat(seeder.backfillImage("천지연폭포", "Jeju", "http://img/c.jpg")).isZero();
        verify(placeRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("키워드 백필: 대상이 없으면 외부 호출 없이 0을 반환한다")
    void keywordBackfillNoTargets() {
        given(placeRepository.findImagelessAttractions(eq("Jeju"), any(Pageable.class)))
                .willReturn(List.of());

        assertThat(seeder.backfillImagesByKeyword("Jeju", 300)).isZero();
        verify(placeRepository, never()).save(any(Place.class));
    }

    @Test
    @DisplayName("키워드 백필은 인기도 높은 순으로 상한만큼만 가져온다")
    void keywordBackfillRespectsLimitAndOrder() {
        given(placeRepository.findImagelessAttractions(eq("Jeju"), any(Pageable.class)))
                .willReturn(List.of());

        seeder.backfillImagesByKeyword("Jeju", 50);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(placeRepository).findImagelessAttractions(eq("Jeju"), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(50);
        assertThat(captor.getValue().getPageNumber()).isZero();
    }

    @Test
    @DisplayName("음식점 중 카페 소분류(A05020900)는 카페로, 베이커리 이름은 베이커리로 적재한다")
    void diningItemsAreSplitIntoCafeAndBakery() throws Exception {
        ObjectMapper om = new ObjectMapper();
        String restaurant = "Dining and Drinking > Restaurant";

        assertThat(TourApiSeeder.diningCategory(om.readTree("{\"cat3\":\"A05020900\"}"), "랜딩커피", restaurant))
                .isEqualTo(TourApiSeeder.CAFE_CATEGORY);
        assertThat(TourApiSeeder.diningCategory(om.readTree("{\"cat3\":\"A05020100\"}"), "수애기베이커리", restaurant))
                .isEqualTo(TourApiSeeder.BAKERY_CATEGORY);
        assertThat(TourApiSeeder.diningCategory(om.readTree("{\"cat3\":\"A05020100\"}"), "형돈", restaurant))
                .isEqualTo(restaurant);
    }

    @Test
    @DisplayName("설정 토큰은 법정동 코드 조회로 바뀐다 — 광역은 시도 전체, 도 안의 도시는 시군구별로 나눠 조회")
    void parseArea_metroAndCityTokens() {
        assertThat(TourApiSeeder.parseArea("39").queries()).containsExactly("&lDongRegnCd=50");
        assertThat(TourApiSeeder.parseArea(" 32-1 ").queries())
                .containsExactly("&lDongRegnCd=51&lDongSignguCd=150");
        assertThat(TourApiSeeder.parseArea("35-2").region()).isEqualTo("Gyeongju");
        // 수원은 구가 넷이라 네 번 조회한다
        assertThat(TourApiSeeder.parseArea("31-13").queries()).hasSize(4);
    }

    @Test
    @DisplayName("광주는 법정동 코드 데이터 이상(12)으로 지역코드 조회를 유지한다")
    void parseArea_gwangjuUsesLegacyAreaCode() {
        assertThat(TourApiSeeder.parseArea("5").queries()).containsExactly("&areaCode=5");
    }

    @Test
    @DisplayName("도 코드만 주면 거부한다 — 강원(32) 전체를 Gangneung으로 적재하면 평창·속초가 섞인다")
    void parseArea_rejectsProvinceWithoutCity() {
        assertThat(TourApiSeeder.parseArea("32")).isNull();
        assertThat(TourApiSeeder.parseArea("99-1")).isNull();
        assertThat(TourApiSeeder.parseArea("abc")).isNull();
    }
}
