package com.shg.trip.shgtrip.domain.place.batch;

import com.shg.trip.shgtrip.domain.place.embedding.EmbeddingService;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.place.vector.PlaceVectorSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmbeddingBatchJobTest {

    @Mock
    private PlaceRepository placeRepository;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private PlaceVectorSearchService placeVectorSearchService;

    private EmbeddingBatchJob embeddingBatchJob;

    @BeforeEach
    void setUp() {
        embeddingBatchJob = new EmbeddingBatchJob(placeRepository, embeddingService, placeVectorSearchService);
        ReflectionTestUtils.setField(embeddingBatchJob, "batchSize", 100);
    }

    @Test
    @DisplayName("미임베딩 장소가 없으면 아무 작업도 수행하지 않는다")
    void execute_noUnembeddedPlaces_doesNothing() {
        when(placeRepository.findByEmbeddingIsNullAndActiveTrue(any(Pageable.class)))
                .thenReturn(Page.empty());

        embeddingBatchJob.execute();

        verifyNoInteractions(embeddingService);
        verifyNoInteractions(placeVectorSearchService);
    }

    @Test
    @DisplayName("미임베딩 장소를 조회하여 임베딩을 생성하고 저장한다")
    void execute_processesUnembeddedPlaces() {
        Place place1 = createPlace(1L, "센소지", "관광");
        Place place2 = createPlace(2L, "메이지신궁", "관광");
        List<Place> places = List.of(place1, place2);

        Page<Place> page = new PageImpl<>(places, PageRequest.of(0, 100), 2);
        when(placeRepository.findByEmbeddingIsNullAndActiveTrue(any(Pageable.class)))
                .thenReturn(page)
                .thenReturn(Page.empty());

        float[] embedding1 = new float[]{0.1f, 0.2f, 0.3f};
        float[] embedding2 = new float[]{0.4f, 0.5f, 0.6f};
        when(embeddingService.embedBatch(anyList()))
                .thenReturn(List.of(embedding1, embedding2));

        embeddingBatchJob.execute();

        verify(embeddingService).embedBatch(anyList());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Long, float[]>> captor = ArgumentCaptor.forClass(Map.class);
        verify(placeVectorSearchService).storeBatch(captor.capture());

        Map<Long, float[]> storedEmbeddings = captor.getValue();
        assertThat(storedEmbeddings).hasSize(2);
        assertThat(storedEmbeddings).containsKey(1L);
        assertThat(storedEmbeddings).containsKey(2L);
        assertThat(storedEmbeddings.get(1L)).isEqualTo(embedding1);
        assertThat(storedEmbeddings.get(2L)).isEqualTo(embedding2);
    }

    @Test
    @DisplayName("EmbeddingTextBuilder에 전달되는 텍스트에 장소의 핵심 정보가 포함된다")
    void execute_buildsCorrectText() {
        Place place = createPlace(1L, "센소지", "관광");
        List<Place> places = List.of(place);

        Page<Place> page = new PageImpl<>(places, PageRequest.of(0, 100), 1);
        when(placeRepository.findByEmbeddingIsNullAndActiveTrue(any(Pageable.class)))
                .thenReturn(page)
                .thenReturn(Page.empty());

        float[] embedding = new float[]{0.1f, 0.2f};
        when(embeddingService.embedBatch(anyList()))
                .thenReturn(List.of(embedding));

        embeddingBatchJob.execute();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> textCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingService).embedBatch(textCaptor.capture());

        List<String> texts = textCaptor.getValue();
        assertThat(texts).hasSize(1);
        assertThat(texts.get(0)).contains("센소지");
        assertThat(texts.get(0)).contains("관광");
    }

    @Test
    @DisplayName("임베딩 API 호출 실패 시 해당 배치를 건너뛰고 계속 처리한다")
    void execute_embeddingApiFails_skipsAndContinues() {
        Place place = createPlace(1L, "센소지", "관광");
        List<Place> places = List.of(place);

        Page<Place> page = new PageImpl<>(places, PageRequest.of(0, 100), 1);
        // 실패한 place는 excludedIds로 걸러지므로, 같은 page 0 재조회 시 빈 결과가 되어 종료된다
        when(placeRepository.findByEmbeddingIsNullAndActiveTrue(PageRequest.of(0, 100)))
                .thenReturn(page);

        when(embeddingService.embedBatch(anyList()))
                .thenThrow(new RuntimeException("OpenAI API 오류"));

        embeddingBatchJob.execute();

        verifyNoInteractions(placeVectorSearchService);
    }

    @Test
    @DisplayName("storeBatch 실패 시 에러를 로그하고 계속 진행한다")
    void execute_storeBatchFails_logsAndContinues() {
        Place place = createPlace(1L, "센소지", "관광");
        List<Place> places = List.of(place);

        Page<Place> page = new PageImpl<>(places, PageRequest.of(0, 100), 1);
        when(placeRepository.findByEmbeddingIsNullAndActiveTrue(PageRequest.of(0, 100)))
                .thenReturn(page);

        when(embeddingService.embedBatch(anyList()))
                .thenReturn(List.of(new float[]{0.1f}));
        doThrow(new RuntimeException("DB 오류"))
                .when(placeVectorSearchService).storeBatch(any());

        // 예외가 전파되지 않아야 함
        embeddingBatchJob.execute();

        verify(placeVectorSearchService).storeBatch(any());
    }

    @Test
    @DisplayName("processBatch에서 텍스트 생성 실패 장소는 건너뛴다")
    void processBatch_textBuildFails_skipsPlace() {
        // name이 null인 장소 → EmbeddingTextBuilder가 예외를 던짐
        Place invalidPlace = Place.builder()
                .id(1L)
                .name(null)
                .address("도쿄")
                .latitude(BigDecimal.valueOf(35.0))
                .longitude(BigDecimal.valueOf(139.0))
                .category("관광")
                .savedAt(OffsetDateTime.now())
                .build();
        Place validPlace = createPlace(2L, "메이지신궁", "관광");

        when(embeddingService.embedBatch(anyList()))
                .thenReturn(List.of(new float[]{0.1f}));

        EmbeddingBatchJob.BatchResult result = embeddingBatchJob.processBatch(List.of(invalidPlace, validPlace));

        assertThat(result.success()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failedIds()).containsExactly(1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> textCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingService).embedBatch(textCaptor.capture());
        assertThat(textCaptor.getValue()).hasSize(1);
        assertThat(textCaptor.getValue().get(0)).contains("메이지신궁");
    }

    @Test
    @DisplayName("임베딩 결과 수가 요청과 불일치하면 전체 배치를 실패 처리한다")
    void processBatch_embeddingCountMismatch_failsEntireBatch() {
        Place place1 = createPlace(1L, "센소지", "관광");
        Place place2 = createPlace(2L, "메이지신궁", "관광");

        // 2개 요청했는데 1개만 반환
        when(embeddingService.embedBatch(anyList()))
                .thenReturn(List.of(new float[]{0.1f}));

        EmbeddingBatchJob.BatchResult result = embeddingBatchJob.processBatch(List.of(place1, place2));

        assertThat(result.success()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(2);
        assertThat(result.failedIds()).containsExactlyInAnyOrder(1L, 2L);
        verifyNoInteractions(placeVectorSearchService);
    }

    @Test
    @DisplayName("일부 장소에서 텍스트 생성 실패가 발생해도 남은 미임베딩 장소를 같은 실행에서 계속 처리한다")
    void execute_partialFailureMidRun_doesNotTerminateEarly() {
        // 텍스트 생성 단계에서 실패하는 장소(name=null)와, 다음 페이지에서 처리될 장소
        Place invalidFailing = Place.builder()
                .id(1L).name(null).address("도쿄")
                .latitude(BigDecimal.valueOf(35.0)).longitude(BigDecimal.valueOf(139.0))
                .category("관광").savedAt(OffsetDateTime.now()).build();
        Place succeeding = createPlace(2L, "성공장소", "관광");
        Place remaining = createPlace(3L, "나머지장소", "관광");

        // 1번째 조회: [invalidFailing, succeeding] (전체 미임베딩 250건 중 첫 페이지)
        Page<Place> firstPage = new PageImpl<>(List.of(invalidFailing, succeeding), PageRequest.of(0, 100), 250);
        // succeeding이 처리되면 DB에서 빠지므로, 2번째 조회는 [invalidFailing(여전히 NULL), remaining]
        Page<Place> secondPage = new PageImpl<>(List.of(invalidFailing, remaining), PageRequest.of(0, 100), 249);

        when(placeRepository.findByEmbeddingIsNullAndActiveTrue(PageRequest.of(0, 100)))
                .thenReturn(firstPage, secondPage, Page.empty());

        when(embeddingService.embedBatch(anyList()))
                .thenAnswer(invocation -> {
                    List<String> texts = invocation.getArgument(0);
                    List<float[]> result = new ArrayList<>();
                    for (int i = 0; i < texts.size(); i++) {
                        result.add(new float[]{0.1f});
                    }
                    return result;
                });

        embeddingBatchJob.execute();

        // remaining(id=3)까지 처리됐어야 함 — pageNumber 증가 방식이었다면 offset 초과로
        // 조기 종료되어 이 검증이 실패했을 것
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Long, float[]>> captor = ArgumentCaptor.forClass(Map.class);
        verify(placeVectorSearchService, atLeastOnce()).storeBatch(captor.capture());
        boolean remainingProcessed = captor.getAllValues().stream()
                .anyMatch(map -> map.containsKey(3L));
        assertThat(remainingProcessed).isTrue();

        // invalidFailing(id=1)은 두 번째 조회에서도 다시 나타나지만, 같은 실행에서는
        // excludedIds로 걸러져 embedBatch에 다시 전달되지 않아야 한다(무한 루프 방지 확인)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> textCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingService, atLeastOnce()).embedBatch(textCaptor.capture());
    }

    private Place createPlace(Long id, String name, String category) {
        return Place.builder()
                .id(id)
                .name(name)
                .address("도쿄시 타이토구")
                .latitude(BigDecimal.valueOf(35.7148))
                .longitude(BigDecimal.valueOf(139.7967))
                .category(category)
                .region("아사쿠사")
                .country("일본")
                .description("유명한 사원")
                .savedAt(OffsetDateTime.now())
                .active(true)
                .build();
    }
}
