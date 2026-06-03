package com.shg.trip.shgtrip.domain.place.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BatchEnrichSchedulerTest {

    @Mock
    private PlaceRepository placeRepository;

    private BatchEnrichScheduler scheduler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        scheduler = new BatchEnrichScheduler(placeRepository, objectMapper);
        ReflectionTestUtils.setField(scheduler, "chunkSize", 1000);
        ReflectionTestUtils.setField(scheduler, "anthropicApiKey", "test-api-key");
    }

    @Test
    @DisplayName("API 키가 없으면 보강을 건너뛴다")
    void enrich_noApiKey_skips() {
        ReflectionTestUtils.setField(scheduler, "anthropicApiKey", "");

        scheduler.enrich();

        verifyNoInteractions(placeRepository);
    }

    @Test
    @DisplayName("미보강 장소가 없으면 아무 작업도 수행하지 않는다")
    void enrich_noUnenrichedPlaces_doesNothing() {
        when(placeRepository.findByEnrichedAtIsNullAndActiveTrue(any(Pageable.class)))
                .thenReturn(Page.empty());

        scheduler.enrich();

        verify(placeRepository).findByEnrichedAtIsNullAndActiveTrue(any(Pageable.class));
        verify(placeRepository, never()).save(any(Place.class));
    }

    @Test
    @DisplayName("buildEnrichPrompt는 장소 정보를 포함한 프롬프트를 생성한다")
    void buildEnrichPrompt_containsPlaceInfo() {
        Place place = createPlace(1L, "센소지", "관광", "아사쿠사", "일본");

        String prompt = scheduler.buildEnrichPrompt(place);

        assertThat(prompt).contains("센소지");
        assertThat(prompt).contains("관광");
        assertThat(prompt).contains("아사쿠사");
        assertThat(prompt).contains("일본");
        assertThat(prompt).contains("tags");
        assertThat(prompt).contains("description");
        assertThat(prompt).contains("recommended_time_slots");
    }

    @Test
    @DisplayName("buildEnrichPrompt는 기존 설명이 있으면 포함한다")
    void buildEnrichPrompt_includesExistingDescription() {
        Place place = Place.builder()
                .id(1L)
                .name("메이지신궁")
                .address("도쿄시 시부야구")
                .latitude(BigDecimal.valueOf(35.6764))
                .longitude(BigDecimal.valueOf(139.6993))
                .category("관광")
                .region("하라주쿠")
                .country("일본")
                .description("메이지 천황을 기리는 신사")
                .savedAt(OffsetDateTime.now())
                .active(true)
                .build();

        String prompt = scheduler.buildEnrichPrompt(place);

        assertThat(prompt).contains("메이지 천황을 기리는 신사");
    }

    @Test
    @DisplayName("buildBatchRequests는 장소마다 요청을 생성한다")
    void buildBatchRequests_createsRequestPerPlace() {
        Place place1 = createPlace(1L, "센소지", "관광", "아사쿠사", "일본");
        Place place2 = createPlace(2L, "메이지신궁", "관광", "하라주쿠", "일본");

        List<Map<String, Object>> requests = scheduler.buildBatchRequests(List.of(place1, place2));

        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).get("custom_id")).isEqualTo("place_1");
        assertThat(requests.get(1).get("custom_id")).isEqualTo("place_2");
    }

    @Test
    @DisplayName("applyEnrichment는 유효한 JSON 응답을 파싱하여 장소를 보강한다")
    void applyEnrichment_validJson_enrichesPlace() {
        Place place = createPlace(1L, "센소지", "관광", "아사쿠사", "일본");

        String content = """
                {
                  "tags": ["사찰", "관광명소", "역사", "문화유산", "아사쿠사"],
                  "description": "도쿄에서 가장 오래된 사찰로, 628년에 창건되었습니다. 아사쿠사의 상징적인 관광지입니다.",
                  "recommended_time_slots": ["morning", "afternoon"]
                }
                """;

        boolean result = scheduler.applyEnrichment(place, content);

        assertThat(result).isTrue();
        assertThat(place.getTags()).containsExactly("사찰", "관광명소", "역사", "문화유산", "아사쿠사");
        assertThat(place.getDescription()).contains("도쿄에서 가장 오래된 사찰");
        assertThat(place.getRecommendedTimeSlots()).containsExactly("morning", "afternoon");
        assertThat(place.getEnrichedAt()).isNotNull();
    }

    @Test
    @DisplayName("applyEnrichment는 코드 블록으로 감싸진 JSON도 처리한다")
    void applyEnrichment_codeBlock_parsesCorrectly() {
        Place place = createPlace(1L, "센소지", "관광", "아사쿠사", "일본");

        String content = """
                ```json
                {
                  "tags": ["사찰", "관광명소"],
                  "description": "유명한 사찰입니다.",
                  "recommended_time_slots": ["morning"]
                }
                ```
                """;

        boolean result = scheduler.applyEnrichment(place, content);

        assertThat(result).isTrue();
        assertThat(place.getTags()).containsExactly("사찰", "관광명소");
    }

    @Test
    @DisplayName("applyEnrichment는 잘못된 JSON이면 false를 반환한다")
    void applyEnrichment_invalidJson_returnsFalse() {
        Place place = createPlace(1L, "센소지", "관광", "아사쿠사", "일본");

        String content = "이것은 유효하지 않은 응답입니다.";

        boolean result = scheduler.applyEnrichment(place, content);

        assertThat(result).isFalse();
        assertThat(place.getEnrichedAt()).isNull();
    }

    @Test
    @DisplayName("extractJson은 순수 JSON을 그대로 반환한다")
    void extractJson_pureJson_returnsAsIs() {
        String json = "{\"tags\": [\"test\"]}";

        String result = scheduler.extractJson(json);

        assertThat(result).isEqualTo(json);
    }

    @Test
    @DisplayName("extractJson은 코드 블록을 제거한다")
    void extractJson_codeBlock_strips() {
        String input = "```json\n{\"tags\": [\"test\"]}\n```";

        String result = scheduler.extractJson(input);

        assertThat(result).isEqualTo("{\"tags\": [\"test\"]}");
    }

    @Test
    @DisplayName("extractJson은 텍스트 전후의 JSON을 추출한다")
    void extractJson_surroundingText_extractsJson() {
        String input = "Here is the result:\n{\"tags\": [\"test\"]}\nDone.";

        String result = scheduler.extractJson(input);

        assertThat(result).isEqualTo("{\"tags\": [\"test\"]}");
    }

    @Test
    @DisplayName("extractTextContent는 메시지에서 텍스트를 추출한다")
    void extractTextContent_validMessage_extractsText() throws Exception {
        String messageJson = """
                {
                  "content": [
                    {"type": "text", "text": "Hello"},
                    {"type": "text", "text": " World"}
                  ]
                }
                """;
        var messageNode = objectMapper.readTree(messageJson);

        String result = scheduler.extractTextContent(messageNode);

        assertThat(result).isEqualTo("Hello World");
    }

    @Test
    @DisplayName("extractTextContent는 메시지가 null이면 null을 반환한다")
    void extractTextContent_nullMessage_returnsNull() {
        String result = scheduler.extractTextContent(null);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("splitIntoChunks는 1000건 단위로 분할한다")
    void splitIntoChunks_splitsCorrectly() {
        List<Place> places = new java.util.ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            places.add(createPlace((long) i, "Place" + i, "관광", "지역", "국가"));
        }

        List<List<Place>> chunks = scheduler.splitIntoChunks(places);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).hasSize(1000);
        assertThat(chunks.get(1)).hasSize(1000);
        assertThat(chunks.get(2)).hasSize(500);
    }

    @Test
    @DisplayName("splitIntoChunks는 chunkSize 이하면 하나의 청크만 반환한다")
    void splitIntoChunks_smallList_singleChunk() {
        List<Place> places = List.of(
                createPlace(1L, "센소지", "관광", "아사쿠사", "일본"),
                createPlace(2L, "메이지신궁", "관광", "하라주쿠", "일본")
        );

        List<List<Place>> chunks = scheduler.splitIntoChunks(places);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).hasSize(2);
    }

    @Test
    @DisplayName("processChunk는 배치 제출 실패 시 전체 실패를 반환한다")
    void processChunk_submitFails_returnsAllFailed() {
        // HttpClient를 모킹하지 않으므로 실제 HTTP 호출이 실패할 것
        // (test-api-key가 유효하지 않으므로)
        List<Place> places = List.of(
                createPlace(1L, "센소지", "관광", "아사쿠사", "일본")
        );

        int[] result = scheduler.processChunk(places);

        assertThat(result[0]).isEqualTo(0); // 성공 0
        assertThat(result[1]).isEqualTo(1); // 실패 1
    }

    private Place createPlace(Long id, String name, String category, String region, String country) {
        return Place.builder()
                .id(id)
                .name(name)
                .address("도쿄시 타이토구")
                .latitude(BigDecimal.valueOf(35.7148))
                .longitude(BigDecimal.valueOf(139.7967))
                .category(category)
                .region(region)
                .country(country)
                .savedAt(OffsetDateTime.now())
                .active(true)
                .build();
    }
}
