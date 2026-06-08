package com.shg.trip.shgtrip.domain.place.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BatchJobRunnerTest {

    @Mock
    private FoursquareSeeder foursquareSeeder;

    @Mock
    private EmbeddingBatchJob embeddingBatchJob;

    @Mock
    private BatchEnrichScheduler batchEnrichScheduler;

    @InjectMocks
    private BatchJobRunner batchJobRunner;

    @Test
    @DisplayName("배치 파이프라인은 시딩 → 임베딩 순서로 실행된다 (enrich 비활성화 시)")
    void run_executesInCorrectOrder() throws Exception {
        batchJobRunner.run();

        InOrder inOrder = inOrder(foursquareSeeder, embeddingBatchJob);
        inOrder.verify(foursquareSeeder).seed();
        inOrder.verify(embeddingBatchJob).execute();
    }

    @Test
    @DisplayName("FoursquareSeeder.seed()가 호출된다")
    void run_callsFoursquareSeeder() throws Exception {
        batchJobRunner.run();

        verify(foursquareSeeder).seed();
    }

    @Test
    @DisplayName("EmbeddingBatchJob.execute()가 호출된다")
    void run_callsEmbeddingBatchJob() throws Exception {
        batchJobRunner.run();

        verify(embeddingBatchJob).execute();
    }

    @Test
    @DisplayName("enrichEnabled=false이면 BatchEnrichScheduler.enrich()가 호출되지 않는다")
    void run_enrichDisabled_doesNotCallBatchEnrichScheduler() throws Exception {
        batchJobRunner.run();

        verify(batchEnrichScheduler, org.mockito.Mockito.never()).enrich();
    }
}
