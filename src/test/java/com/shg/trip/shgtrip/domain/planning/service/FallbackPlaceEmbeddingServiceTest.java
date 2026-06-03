package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.embedding.EmbeddingService;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.vector.PlaceVectorSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * FallbackPlaceEmbeddingService 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class FallbackPlaceEmbeddingServiceTest {

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private PlaceVectorSearchService placeVectorSearchService;

    @InjectMocks
    private FallbackPlaceEmbeddingService service;

    @Nested
    @DisplayName("generateEmbeddingsAsync")
    class GenerateEmbeddingsAsync {

        @Test
        @DisplayName("임베딩이 없는 장소에 대해 임베딩을 생성하고 저장한다")
        void generatesAndStoresEmbeddingsForPlacesWithoutEmbedding() {
            Place place1 = createPlace(1L, "센소지", null);
            Place place2 = createPlace(2L, "메이지신궁", null);

            float[] embedding1 = new float[]{0.1f, 0.2f, 0.3f};
            float[] embedding2 = new float[]{0.4f, 0.5f, 0.6f};

            when(embeddingService.embed(any(String.class)))
                    .thenReturn(embedding1)
                    .thenReturn(embedding2);

            service.generateEmbeddingsAsync(List.of(place1, place2));

            verify(embeddingService, times(2)).embed(any(String.class));
            verify(placeVectorSearchService).store(eq(1L), eq(embedding1));
            verify(placeVectorSearchService).store(eq(2L), eq(embedding2));
        }

        @Test
        @DisplayName("이미 임베딩이 있는 장소는 건너뛴다")
        void skipsPlacesWithExistingEmbedding() {
            Place placeWithEmbedding = createPlace(1L, "센소지", "some-embedding-value");
            Place placeWithoutEmbedding = createPlace(2L, "메이지신궁", null);

            float[] embedding = new float[]{0.1f, 0.2f, 0.3f};
            when(embeddingService.embed(any(String.class))).thenReturn(embedding);

            service.generateEmbeddingsAsync(List.of(placeWithEmbedding, placeWithoutEmbedding));

            verify(embeddingService, times(1)).embed(any(String.class));
            verify(placeVectorSearchService, never()).store(eq(1L), any());
            verify(placeVectorSearchService).store(eq(2L), eq(embedding));
        }

        @Test
        @DisplayName("null 리스트가 전달되면 아무 작업도 수행하지 않는다")
        void doesNothingForNullList() {
            service.generateEmbeddingsAsync(null);

            verifyNoInteractions(embeddingService, placeVectorSearchService);
        }

        @Test
        @DisplayName("빈 리스트가 전달되면 아무 작업도 수행하지 않는다")
        void doesNothingForEmptyList() {
            service.generateEmbeddingsAsync(Collections.emptyList());

            verifyNoInteractions(embeddingService, placeVectorSearchService);
        }

        @Test
        @DisplayName("임베딩 생성 실패 시 해당 장소를 건너뛰고 나머지를 계속 처리한다")
        void continuesOnEmbeddingGenerationFailure() {
            Place place1 = createPlace(1L, "센소지", null);
            Place place2 = createPlace(2L, "메이지신궁", null);
            Place place3 = createPlace(3L, "시부야", null);

            float[] embedding = new float[]{0.1f, 0.2f, 0.3f};

            when(embeddingService.embed(any(String.class)))
                    .thenReturn(embedding)
                    .thenThrow(new RuntimeException("OpenAI API 실패"))
                    .thenReturn(embedding);

            service.generateEmbeddingsAsync(List.of(place1, place2, place3));

            verify(placeVectorSearchService).store(eq(1L), eq(embedding));
            verify(placeVectorSearchService, never()).store(eq(2L), any());
            verify(placeVectorSearchService).store(eq(3L), eq(embedding));
        }

        @Test
        @DisplayName("저장 실패 시 해당 장소를 건너뛰고 나머지를 계속 처리한다")
        void continuesOnStoreFailure() {
            Place place1 = createPlace(1L, "센소지", null);
            Place place2 = createPlace(2L, "메이지신궁", null);

            float[] embedding = new float[]{0.1f, 0.2f, 0.3f};
            when(embeddingService.embed(any(String.class))).thenReturn(embedding);

            doThrow(new RuntimeException("DB 저장 실패"))
                    .when(placeVectorSearchService).store(eq(1L), any());

            service.generateEmbeddingsAsync(List.of(place1, place2));

            verify(placeVectorSearchService).store(eq(1L), eq(embedding));
            verify(placeVectorSearchService).store(eq(2L), eq(embedding));
        }

        @Test
        @DisplayName("모든 장소에 이미 임베딩이 있으면 임베딩 서비스를 호출하지 않는다")
        void noEmbeddingServiceCallWhenAllHaveEmbeddings() {
            Place place1 = createPlace(1L, "센소지", "embedding-1");
            Place place2 = createPlace(2L, "메이지신궁", "embedding-2");

            service.generateEmbeddingsAsync(List.of(place1, place2));

            verifyNoInteractions(embeddingService);
            verifyNoInteractions(placeVectorSearchService);
        }
    }

    private Place createPlace(Long id, String name, String embedding) {
        return Place.builder()
                .id(id)
                .name(name)
                .address("도쿄시 " + name)
                .latitude(BigDecimal.valueOf(35.7))
                .longitude(BigDecimal.valueOf(139.7))
                .category("관광")
                .region("도쿄")
                .country("일본")
                .description(name + " 설명")
                .savedAt(OffsetDateTime.now())
                .embedding(embedding)
                .source("llm_generated")
                .active(true)
                .build();
    }
}
