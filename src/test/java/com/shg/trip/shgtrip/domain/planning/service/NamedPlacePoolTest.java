package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.embedding.EmbeddingService;
import com.shg.trip.shgtrip.domain.place.vector.PlaceVectorSearchService;
import com.shg.trip.shgtrip.domain.place.vector.VectorSearchRequest;
import com.shg.trip.shgtrip.domain.place.vector.VectorSearchResult;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class NamedPlacePoolTest {

    @Mock private EmbeddingService embeddingService;
    @Mock private PlaceVectorSearchService placeVectorSearchService;

    private static VectorSearchResult place(long id, String name, String category, int sitelinks) {
        return new VectorSearchResult(id, name, "부산 수영구", category, List.of(), "Busan", "KR",
                BigDecimal.valueOf(35.153), BigDecimal.valueOf(129.118), null, null, null, sitelinks, 0.3,
                null, null, null);
    }

    private static VectorEnrichedInput busan(String description) {
        return new VectorEnrichedInput(
                "부산", List.of("ocean"), List.of("beach"), "normal", "any", BigDecimal.valueOf(900000),
                LocalDate.of(2026, 11, 28), LocalDate.of(2026, 11, 29), description, null,
                "부산", "KR", List.of("Busan"), List.of("해변"), null, "MEDIUM", "늦가을", "", null, null);
    }

    @Test
    @DisplayName("요청 문장에 이름이 나온 명소는 검색에 안 잡혀도 후보에 들어간다. 같은 말로 시작하는 식당·다른 명소는 넣지 않는다")
    void addsMentionedAttraction() {
        given(embeddingService.embedBatch(anyList())).willAnswer(inv -> {
            List<float[]> v = new ArrayList<>();
            for (int i = 0; i < ((List<?>) inv.getArgument(0)).size(); i++) v.add(new float[]{0.1f});
            return v;
        });
        given(placeVectorSearchService.search(any(VectorSearchRequest.class))).willReturn(List.of());
        given(placeVectorSearchService.findPopular(any(), anyString(), anyList(), anyInt())).willReturn(List.of(
                place(1, "부산타워", "Landmarks and Outdoors > Scenic Lookout", 30),
                place(2, "광안리해수욕장", "Landmarks and Outdoors > Beach", 11),
                place(3, "광안리유가횟집", "Dining and Drinking > Restaurant > Seafood Restaurant", 2),
                place(4, "광안리 해변테마거리", "Landmarks and Outdoors > Tourist Attraction", 1),
                place(5, "해운대 블루라인파크", "Landmarks and Outdoors > Tourist Attraction", 3),
                place(6, "광안대교", "Landmarks and Outdoors > Bridge", 9)));

        List<PlaceCandidate> result = new VectorSearchQueryService(embeddingService, placeVectorSearchService)
                .search(busan("KTX로 가요. 해운대랑 광안리 보고 싶어요."));

        assertThat(result).extracting(PlaceCandidate::name).containsExactly("광안리해수욕장");
    }

    @Test
    @DisplayName("해외 명소는 원문 이름이 문장에 없어도 한국어 이름으로 말한 것을 알아본다")
    void addsOverseasAttractionMentionedByKoreanName() {
        given(embeddingService.embedBatch(anyList())).willAnswer(inv -> {
            List<float[]> v = new ArrayList<>();
            for (int i = 0; i < ((List<?>) inv.getArgument(0)).size(); i++) v.add(new float[]{0.1f});
            return v;
        });
        given(placeVectorSearchService.search(any(VectorSearchRequest.class))).willReturn(List.of());
        given(placeVectorSearchService.findPopular(any(), anyString(), anyList(), anyInt())).willReturn(List.of(
                tokyo(1, "東京スカイツリー", "도쿄 스카이트리", 40),
                tokyo(2, "浅草寺", "센소지", 30),
                tokyo(3, "明治神宮", "메이지 신궁", 25)));
        VectorEnrichedInput tokyo = new VectorEnrichedInput(
                "도쿄", List.of("culture"), List.of("attraction"), "normal", "any", BigDecimal.valueOf(900000),
                LocalDate.of(2026, 11, 28), LocalDate.of(2026, 11, 30), "센소지는 꼭 가고 싶어요.", null,
                "도쿄", "JP", List.of("Tokyo"), List.of("문화"), null, "MEDIUM", "늦가을", "", null, null);

        List<PlaceCandidate> result = new VectorSearchQueryService(embeddingService, placeVectorSearchService)
                .search(tokyo);

        assertThat(result).extracting(PlaceCandidate::name).containsExactly("浅草寺");
        assertThat(result.get(0).nameKo()).isEqualTo("센소지");
    }

    private static VectorSearchResult tokyo(long id, String name, String nameKo, int sitelinks) {
        return new VectorSearchResult(id, name, "東京都", "Spiritual Center > Buddhist Temple", List.of(), "Tokyo", "JP",
                BigDecimal.valueOf(35.71), BigDecimal.valueOf(139.79), null, null, null, sitelinks, 0.3,
                null, null, null, null, nameKo);
    }

    @Test
    @DisplayName("같은 말로 시작하는 식당이 이미 후보에 있어도 명소는 들어간다")
    void restaurantDoesNotCoverMention() {
        given(embeddingService.embedBatch(anyList())).willAnswer(inv -> {
            List<float[]> v = new ArrayList<>();
            for (int i = 0; i < ((List<?>) inv.getArgument(0)).size(); i++) v.add(new float[]{0.1f});
            return v;
        });
        VectorSearchResult sashimi = new VectorSearchResult(20L, "광안리유가횟집", "부산 수영구",
                "Dining and Drinking > Restaurant > Seafood Restaurant", List.of("해산물"), "Busan", "KR",
                BigDecimal.valueOf(35.153), BigDecimal.valueOf(129.118), null, null, null, 0, 0.9,
                "MEAL", "SEAFOOD", null);
        given(placeVectorSearchService.search(any(VectorSearchRequest.class))).willReturn(List.of(sashimi));
        given(placeVectorSearchService.findPopular(any(), anyString(), anyList(), anyInt())).willReturn(List.of(
                place(2, "광안리해수욕장", "Landmarks and Outdoors > Beach", 11)));

        List<PlaceCandidate> result = new VectorSearchQueryService(embeddingService, placeVectorSearchService)
                .search(busan("광안리 보고 싶어요."));

        assertThat(result).extracting(PlaceCandidate::name).contains("광안리유가횟집", "광안리해수욕장");
    }

    @Test
    @DisplayName("이미 후보에 있는 명소의 부속 시설은 넣지 않는다: 불국사가 있으면 불국사 대웅전은 빠진다")
    void skipsPartOfKnownPlace() {
        given(embeddingService.embedBatch(anyList())).willAnswer(inv -> {
            List<float[]> v = new ArrayList<>();
            for (int i = 0; i < ((List<?>) inv.getArgument(0)).size(); i++) v.add(new float[]{0.1f});
            return v;
        });
        VectorSearchResult bulguksa = place(10, "불국사 (佛國寺)", "Landmarks and Outdoors > Historic and Protected Site", 40);
        given(placeVectorSearchService.search(any(VectorSearchRequest.class))).willReturn(List.of(bulguksa));
        given(placeVectorSearchService.findPopular(any(), anyString(), anyList(), anyInt())).willReturn(List.of(
                bulguksa, place(11, "불국사 대웅전", "Landmarks and Outdoors > Historic and Protected Site", 3)));

        List<PlaceCandidate> result = new VectorSearchQueryService(embeddingService, placeVectorSearchService)
                .search(busan("부모님 모시고 불국사 꼭 가고 싶어요."));

        assertThat(result).extracting(PlaceCandidate::name).doesNotContain("불국사 대웅전");
    }

    @Test
    @DisplayName("이름 앞부분이 이름 길이의 40% 이상 문장에 있어야 한다")
    void mentionedPrefixNeedsEnoughOfName() {
        assertThat(VectorSearchQueryService.mentionedPrefix("광안리해수욕장", "해운대랑광안리보고싶어요")).isEqualTo("광안리");
        assertThat(VectorSearchQueryService.mentionedPrefix("해운대블루라인파크", "해운대랑광안리")).isNull();
        assertThat(VectorSearchQueryService.mentionedPrefix("성산일출봉", "성산일출봉꼭가고싶어요")).isEqualTo("성산일출봉");
    }
}
